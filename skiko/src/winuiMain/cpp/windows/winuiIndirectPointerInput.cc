#include "winuiIndirectPointerInput.h"

#include <algorithm>
#include <limits>
#include <utility>

namespace skiko::winui::indirect {
namespace {
    constexpr uint32_t MaxHistoryEntries = 256;
    constexpr uint32_t MaxPointersPerFrame = 32;
    constexpr size_t MaxRememberedFrames = 512;

    uint64_t device_id(HANDLE device) {
        return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(device));
    }

    uint64_t raw_timestamp(const POINTER_INFO& pointer_info) {
        return pointer_info.PerformanceCount != 0
            ? pointer_info.PerformanceCount
            : static_cast<uint64_t>(pointer_info.dwTime);
    }

    bool is_pressed(POINTER_FLAGS flags) {
        if ((flags & POINTER_FLAG_UP) != 0) {
            return false;
        }
        return (flags & (POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN)) != 0;
    }

    bool is_clean_down(const POINTER_TOUCH_INFO* row, uint32_t pointer_count) {
        for (uint32_t index = 0; index < pointer_count; ++index) {
            const auto flags = row[index].pointerInfo.pointerFlags;
            if (!is_pressed(flags) || (flags & (POINTER_FLAG_DOWN | POINTER_FLAG_NEW)) == 0) {
                return false;
            }
        }
        return true;
    }

    float normalized_pressure(const POINTER_TOUCH_INFO& touch_info, bool pressed) {
        if ((touch_info.touchMask & TOUCH_MASK_PRESSURE) == 0) {
            return pressed ? 1.0f : 0.0f;
        }
        const uint32_t pressure = std::min<uint32_t>(touch_info.pressure, 1024);
        return static_cast<float>(pressure) / 1024.0f;
    }

    template <typename T, typename Predicate>
    auto find_if(std::vector<T>& values, Predicate predicate) {
        return std::find_if(values.begin(), values.end(), predicate);
    }

    template <typename T, typename Predicate>
    auto find_if(const std::vector<T>& values, Predicate predicate) {
        return std::find_if(values.begin(), values.end(), predicate);
    }
}

HistoryProcessor::HistoryProcessor(uint64_t performance_frequency)
    : performance_frequency_(performance_frequency) {
}

ProcessResult HistoryProcessor::process(
    const POINTER_TOUCH_INFO* history,
    uint32_t entries_count,
    uint32_t pointer_count,
    const RECT* device_rect
) {
    if (history == nullptr || entries_count == 0 || pointer_count == 0 ||
        entries_count > MaxHistoryEntries || pointer_count > MaxPointersPerFrame ||
        entries_count > std::numeric_limits<size_t>::max() / pointer_count) {
        return malformed_result();
    }

    ProcessResult result;
    for (uint32_t reverse_index = entries_count; reverse_index > 0; --reverse_index) {
        const uint32_t row_index = reverse_index - 1;
        const POINTER_TOUCH_INFO* row = history +
            static_cast<size_t>(row_index) * static_cast<size_t>(pointer_count);
        const POINTER_INFO& first_pointer = row[0].pointerInfo;
        const uint64_t row_device_id = device_id(first_pointer.sourceDevice);
        const uint64_t row_raw_timestamp = raw_timestamp(first_pointer);
        const FrameKey frame_key{
            row_device_id,
            static_cast<uint64_t>(first_pointer.frameId),
            row_raw_timestamp,
        };

        bool row_is_valid = first_pointer.pointerType == PT_TOUCHPAD &&
            first_pointer.sourceDevice != nullptr;
        for (uint32_t pointer_index = 0; pointer_index < pointer_count && row_is_valid;
             ++pointer_index) {
            const POINTER_INFO& pointer = row[pointer_index].pointerInfo;
            row_is_valid = pointer.pointerType == PT_TOUCHPAD &&
                pointer.sourceDevice == first_pointer.sourceDevice &&
                pointer.frameId == first_pointer.frameId &&
                raw_timestamp(pointer) == row_raw_timestamp &&
                (pointer.pointerFlags & POINTER_FLAG_CANCELED) == 0;
            for (uint32_t previous_index = 0;
                 previous_index < pointer_index && row_is_valid;
                 ++previous_index) {
                row_is_valid = row[previous_index].pointerInfo.pointerId != pointer.pointerId;
            }
        }
        if (!row_is_valid) {
            result = malformed_result();
            return result;
        }

        const bool already_seen = find_if(
            seen_frames_,
            [&](const FrameKey& seen) {
                return seen.device_id == frame_key.device_id &&
                    seen.frame_id == frame_key.frame_id &&
                    seen.raw_timestamp == frame_key.raw_timestamp;
            }
        ) != seen_frames_.end();
        if (already_seen) {
            continue;
        }

        if (ignore_until_clean_down_) {
            if (!is_clean_down(row, pointer_count)) {
                continue;
            }
            ignore_until_clean_down_ = false;
            pointer_states_.clear();
            has_active_device_ = false;
            active_device_id_ = 0;
        }

        if (has_active_device_ && active_device_id_ != row_device_id) {
            result = malformed_result();
            return result;
        }

        for (const PointerState& state : pointer_states_) {
            bool is_present = false;
            for (uint32_t pointer_index = 0; pointer_index < pointer_count; ++pointer_index) {
                if (row[pointer_index].pointerInfo.pointerId == state.pointer_id) {
                    is_present = true;
                    break;
                }
            }
            if (!is_present) {
                result = malformed_result();
                return result;
            }
        }

        if (pointer_states_.empty() && !is_clean_down(row, pointer_count)) {
            result = malformed_result();
            return result;
        }

        const int64_t frame_timestamp_millis = timestamp_millis(first_pointer);
        Event event;
        event.primary_directional_motion_axis = SKIKO_WINUI_INDIRECT_POINTER_AXIS_NONE;
        event.device_id = row_device_id;
        event.frame_id = static_cast<uint64_t>(first_pointer.frameId);
        if (device_rect != nullptr) {
            event.device_rect = DeviceRect{
                device_rect->left,
                device_rect->top,
                device_rect->right,
                device_rect->bottom,
            };
        }
        event.changes.reserve(pointer_count);

        bool has_press = false;
        bool has_release = false;
        std::vector<PointerState> next_states = pointer_states_;
        for (uint32_t pointer_index = 0; pointer_index < pointer_count; ++pointer_index) {
            const POINTER_TOUCH_INFO& touch_info = row[pointer_index];
            const POINTER_INFO& pointer = touch_info.pointerInfo;
            const uint64_t pointer_id = static_cast<uint64_t>(pointer.pointerId);
            const bool pressed = is_pressed(pointer.pointerFlags);
            const auto previous = find_if(
                pointer_states_,
                [&](const PointerState& state) { return state.pointer_id == pointer_id; }
            );
            const bool is_new = previous == pointer_states_.end();

            if (is_new) {
                if (!pressed ||
                    (pointer.pointerFlags & (POINTER_FLAG_DOWN | POINTER_FLAG_NEW)) == 0) {
                    result = malformed_result();
                    return result;
                }
                has_press = true;
            } else if ((pointer.pointerFlags & (POINTER_FLAG_DOWN | POINTER_FLAG_NEW)) != 0) {
                result = malformed_result();
                return result;
            }

            if (!pressed) {
                if (is_new || (pointer.pointerFlags & POINTER_FLAG_UP) == 0) {
                    result = malformed_result();
                    return result;
                }
                has_release = true;
            }

            const float x = static_cast<float>(pointer.ptHimetricLocation.x);
            const float y = static_cast<float>(pointer.ptHimetricLocation.y);
            event.changes.push_back(Change{
                pointer_id,
                frame_timestamp_millis,
                x,
                y,
                pressed ? uint8_t{1} : uint8_t{0},
                normalized_pressure(touch_info, pressed),
                is_new ? frame_timestamp_millis : previous->timestamp_millis,
                is_new ? x : previous->x,
                is_new ? y : previous->y,
                is_new ? uint8_t{0} : uint8_t{1},
            });

            auto next = find_if(
                next_states,
                [&](const PointerState& state) { return state.pointer_id == pointer_id; }
            );
            if (pressed) {
                const PointerState updated{
                    pointer_id,
                    frame_timestamp_millis,
                    x,
                    y,
                };
                if (next == next_states.end()) {
                    next_states.push_back(updated);
                } else {
                    *next = updated;
                }
            } else if (next != next_states.end()) {
                next_states.erase(next);
            }
        }

        event.type = has_press
            ? EventType::Press
            : (has_release ? EventType::Release : EventType::Move);
        pointer_states_ = std::move(next_states);
        has_active_device_ = !pointer_states_.empty();
        active_device_id_ = has_active_device_ ? row_device_id : 0;

        seen_frames_.push_back(frame_key);
        if (seen_frames_.size() > MaxRememberedFrames) {
            seen_frames_.erase(seen_frames_.begin());
        }
        result.events.push_back(std::move(event));
    }
    return result;
}

bool HistoryProcessor::cancel() {
    const bool had_active_stream = has_active_stream();
    pointer_states_.clear();
    seen_frames_.clear();
    has_active_device_ = false;
    active_device_id_ = 0;
    ignore_until_clean_down_ = false;
    has_extended_dw_time_ = false;
    last_dw_time_ = 0;
    extended_dw_time_ = 0;
    return had_active_stream;
}

bool HistoryProcessor::has_active_stream() const {
    return !pointer_states_.empty();
}

ProcessResult HistoryProcessor::malformed_result() {
    ProcessResult result;
    result.malformed = true;
    result.cancelled = cancel();
    ignore_until_clean_down_ = true;
    return result;
}

int64_t HistoryProcessor::timestamp_millis(const POINTER_INFO& pointer_info) {
    if (performance_frequency_ != 0 && pointer_info.PerformanceCount != 0) {
        const long double millis =
            static_cast<long double>(pointer_info.PerformanceCount) * 1000.0L /
            static_cast<long double>(performance_frequency_);
        return static_cast<int64_t>(millis);
    }

    const uint32_t current = pointer_info.dwTime;
    if (!has_extended_dw_time_) {
        has_extended_dw_time_ = true;
        last_dw_time_ = current;
        extended_dw_time_ = current;
    } else {
        const uint32_t delta = current - last_dw_time_;
        extended_dw_time_ += delta;
        last_dw_time_ = current;
    }
    return static_cast<int64_t>(extended_dw_time_);
}

}  // namespace skiko::winui::indirect
