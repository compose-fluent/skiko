#include "winuiIndirectPointerInput.h"

#ifdef SKIKO_WINUI_MINGW
#include <ole2.h>
#else
#include <Unknwn.h>
#endif
#include <algorithm>
#include <limits>
#include <utility>

namespace skiko::winui::indirect {
namespace {
    constexpr POINTER_INPUT_TYPE TouchpadPointerType =
        static_cast<POINTER_INPUT_TYPE>(5);

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

        bool row_is_valid = first_pointer.pointerType == TouchpadPointerType &&
            first_pointer.sourceDevice != nullptr;
        for (uint32_t pointer_index = 0; pointer_index < pointer_count && row_is_valid;
             ++pointer_index) {
            const POINTER_INFO& pointer = row[pointer_index].pointerInfo;
            row_is_valid = pointer.pointerType == TouchpadPointerType &&
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
        event.raw_timestamp = row_raw_timestamp;
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

namespace {
    struct IWindowNative : IUnknown {
        virtual HRESULT STDMETHODCALLTYPE get_WindowHandle(HWND* hwnd) = 0;
    };

    constexpr IID IWindowNativeIid = {
        0x45d64a29,
        0xa63e,
        0x4cb6,
        {0xb4, 0x98, 0x57, 0x81, 0xd2, 0x98, 0xcb, 0x4f},
    };

    uint64_t query_performance_frequency() {
        LARGE_INTEGER frequency = {};
        return QueryPerformanceFrequency(&frequency) && frequency.QuadPart > 0
            ? static_cast<uint64_t>(frequency.QuadPart)
            : 0;
    }

    bool api_is_complete(const NativeApiTable& api) {
        return api.register_touchpad_capable_window != nullptr &&
            api.get_pointer_frame_touchpad_info_history != nullptr &&
            api.get_pointer_type != nullptr &&
            api.get_pointer_device_rects != nullptr &&
            api.set_window_subclass != nullptr &&
            api.remove_window_subclass != nullptr &&
            api.def_subclass_proc != nullptr;
    }

    NativeApiTable resolve_native_api() {
        HMODULE user32 = GetModuleHandleW(L"User32.dll");
        if (user32 == nullptr) {
            user32 = LoadLibraryW(L"User32.dll");
        }
        NativeApiTable api;
        if (user32 != nullptr) {
            api.register_touchpad_capable_window =
                reinterpret_cast<RegisterTouchpadCapableWindowFunction>(
                    GetProcAddress(user32, MAKEINTRESOURCEA(2689))
                );
            api.get_pointer_frame_touchpad_info_history =
                reinterpret_cast<GetPointerFrameTouchpadInfoHistoryFunction>(
                    GetProcAddress(user32, MAKEINTRESOURCEA(2694))
                );
        }
        api.get_pointer_type = &GetPointerType;
        api.get_pointer_device_rects = &GetPointerDeviceRects;
        api.set_window_subclass = &SetWindowSubclass;
        api.remove_window_subclass = &RemoveWindowSubclass;
        api.def_subclass_proc = &DefSubclassProc;
        return api;
    }

    HWND window_handle(void* window_inspectable) {
        if (window_inspectable == nullptr) {
            return nullptr;
        }
        auto* unknown = static_cast<IUnknown*>(window_inspectable);
        IWindowNative* window_native = nullptr;
        const HRESULT query_result = unknown->QueryInterface(
            IWindowNativeIid,
            reinterpret_cast<void**>(&window_native)
        );
        if (FAILED(query_result) || window_native == nullptr) {
            return nullptr;
        }
        HWND hwnd = nullptr;
        const HRESULT handle_result = window_native->get_WindowHandle(&hwnd);
        window_native->Release();
        return SUCCEEDED(handle_result) ? hwnd : nullptr;
    }
}

class Binding {
public:
    Binding(
        HWND hwnd,
        const NativeApiTable& api,
        void* context,
        SkikoWinUIIndirectPointerEventCallback event_callback,
        SkikoWinUIIndirectPointerCancelCallback cancel_callback
    )
        : hwnd_(hwnd),
          api_(api),
          context_(context),
          event_callback_(event_callback),
          cancel_callback_(cancel_callback),
          processor_(query_performance_frequency()),
          owner_thread_id_(GetCurrentThreadId()) {
    }

    bool initialize(int32_t* unavailable_reason);
    LRESULT dispatch(UINT message, WPARAM w_param, LPARAM l_param);
    bool cancel();
    bool close();
    bool is_active() const;
    HWND hwnd() const { return hwnd_; }
    Binding* registry_next = nullptr;

private:
    struct FrameDecision {
        uint64_t device_id;
        uint64_t frame_id;
        uint64_t raw_timestamp;
        bool consumed;
    };

    struct HistoryPayload {
        std::vector<POINTER_TOUCH_INFO> values;
        uint32_t entries_count = 0;
        uint32_t pointer_count = 0;
        RECT device_rect = {};
        bool has_device_rect = false;
    };

    HWND hwnd_;
    NativeApiTable api_;
    void* context_;
    SkikoWinUIIndirectPointerEventCallback event_callback_;
    SkikoWinUIIndirectPointerCancelCallback cancel_callback_;
    HistoryProcessor processor_;
    DWORD owner_thread_id_;
    bool subclass_installed_ = false;
    bool registration_active_ = false;
    bool registered_in_process_ = false;
    bool closed_ = false;
    uint32_t callback_depth_ = 0;
    std::vector<FrameDecision> frame_decisions_;

    bool is_owner_thread() const;
    UINT_PTR subclass_id() const;
    LRESULT forward(UINT message, WPARAM w_param, LPARAM l_param) const;
    bool load_history(uint32_t pointer_id, HistoryPayload* payload);
    bool dispatch_history(const HistoryPayload& payload);
    bool cached_consumption(
        uint64_t device_id,
        uint64_t frame_id,
        uint64_t timestamp
    ) const;
    void cache_consumption(const Event& event, bool consumed);
    void notify_cancel();
    void clear_stream(bool notify);
    void on_window_destroyed();
};

namespace {
    SRWLOCK registry_lock = SRWLOCK_INIT;
    Binding* registry_head = nullptr;

    bool reserve_hwnd(Binding* binding) {
        AcquireSRWLockExclusive(&registry_lock);
        for (Binding* current = registry_head; current != nullptr;
             current = current->registry_next) {
            if (current->hwnd() == binding->hwnd()) {
                ReleaseSRWLockExclusive(&registry_lock);
                return false;
            }
        }
        binding->registry_next = registry_head;
        registry_head = binding;
        ReleaseSRWLockExclusive(&registry_lock);
        return true;
    }

    void release_hwnd(Binding* binding) {
        AcquireSRWLockExclusive(&registry_lock);
        Binding** current = &registry_head;
        while (*current != nullptr) {
            if (*current == binding) {
                *current = binding->registry_next;
                binding->registry_next = nullptr;
                break;
            }
            current = &((*current)->registry_next);
        }
        ReleaseSRWLockExclusive(&registry_lock);
    }

    LRESULT CALLBACK binding_subclass_proc(
        HWND,
        UINT message,
        WPARAM w_param,
        LPARAM l_param,
        UINT_PTR,
        DWORD_PTR reference_data
    ) {
        auto* binding = reinterpret_cast<Binding*>(reference_data);
        return binding != nullptr
            ? binding->dispatch(message, w_param, l_param)
            : DefSubclassProc(nullptr, message, w_param, l_param);
    }

    bool is_pointer_message(UINT message) {
        return message == WM_POINTERDOWN ||
            message == WM_POINTERUPDATE ||
            message == WM_POINTERUP;
    }

    bool is_cancellation_message(UINT message, WPARAM w_param) {
        return message == WM_KILLFOCUS ||
            message == WM_CANCELMODE ||
            message == WM_CAPTURECHANGED ||
            message == WM_POINTERCAPTURECHANGED ||
            message == WM_POINTERDEVICEOUTOFRANGE ||
            (message == WM_ACTIVATE && LOWORD(w_param) == WA_INACTIVE);
    }

}

bool Binding::initialize(int32_t* unavailable_reason) {
    if (!reserve_hwnd(this)) {
        if (unavailable_reason != nullptr) {
            *unavailable_reason = SKIKO_WINUI_INDIRECT_POINTER_ALREADY_BOUND;
        }
        return false;
    }
    registered_in_process_ = true;
    if (!api_.set_window_subclass(
            hwnd_,
            binding_subclass_proc,
            subclass_id(),
            reinterpret_cast<DWORD_PTR>(this)
        )) {
        release_hwnd(this);
        registered_in_process_ = false;
        if (unavailable_reason != nullptr) {
            *unavailable_reason = SKIKO_WINUI_INDIRECT_POINTER_SUBCLASS_FAILED;
        }
        return false;
    }
    subclass_installed_ = true;
    if (!api_.register_touchpad_capable_window(hwnd_, TRUE)) {
        api_.remove_window_subclass(hwnd_, binding_subclass_proc, subclass_id());
        subclass_installed_ = false;
        release_hwnd(this);
        registered_in_process_ = false;
        if (unavailable_reason != nullptr) {
            *unavailable_reason = SKIKO_WINUI_INDIRECT_POINTER_REGISTRATION_FAILED;
        }
        return false;
    }
    registration_active_ = true;
    if (unavailable_reason != nullptr) {
        *unavailable_reason = SKIKO_WINUI_INDIRECT_POINTER_AVAILABLE;
    }
    return true;
}

LRESULT Binding::dispatch(UINT message, WPARAM w_param, LPARAM l_param) {
    if (closed_) {
        return forward(message, w_param, l_param);
    }
    if (message == WM_NCDESTROY) {
        clear_stream(true);
        const LRESULT result = forward(message, w_param, l_param);
        on_window_destroyed();
        return result;
    }
    if (message == WM_DESTROY || is_cancellation_message(message, w_param)) {
        clear_stream(true);
        return forward(message, w_param, l_param);
    }
    if (!is_pointer_message(message)) {
        return forward(message, w_param, l_param);
    }

    const uint32_t pointer_id = GET_POINTERID_WPARAM(w_param);
    POINTER_INPUT_TYPE pointer_type = PT_POINTER;
    if (!api_.get_pointer_type(pointer_id, &pointer_type) ||
        pointer_type != TouchpadPointerType) {
        return forward(message, w_param, l_param);
    }

    HistoryPayload payload;
    if (!load_history(pointer_id, &payload)) {
        clear_stream(true);
        return forward(message, w_param, l_param);
    }
    return dispatch_history(payload)
        ? 0
        : forward(message, w_param, l_param);
}

bool Binding::cancel() {
    if (!is_owner_thread() || callback_depth_ != 0) {
        return false;
    }
    clear_stream(true);
    return true;
}

bool Binding::close() {
    if (!is_owner_thread() || callback_depth_ != 0) {
        return false;
    }
    if (closed_) {
        return true;
    }
    clear_stream(true);
    if (registration_active_) {
        api_.register_touchpad_capable_window(hwnd_, FALSE);
        registration_active_ = false;
    }
    if (subclass_installed_) {
        api_.remove_window_subclass(hwnd_, binding_subclass_proc, subclass_id());
        subclass_installed_ = false;
    }
    if (registered_in_process_) {
        release_hwnd(this);
        registered_in_process_ = false;
    }
    closed_ = true;
    return true;
}

bool Binding::is_active() const {
    return !closed_ && registration_active_ && subclass_installed_;
}

bool Binding::is_owner_thread() const {
    return GetCurrentThreadId() == owner_thread_id_;
}

UINT_PTR Binding::subclass_id() const {
    return reinterpret_cast<UINT_PTR>(this);
}

LRESULT Binding::forward(UINT message, WPARAM w_param, LPARAM l_param) const {
    return api_.def_subclass_proc(hwnd_, message, w_param, l_param);
}

bool Binding::load_history(uint32_t pointer_id, HistoryPayload* payload) {
    for (int attempt = 0; attempt < 3; ++attempt) {
        uint32_t entries_count = 0;
        uint32_t pointer_count = 0;
        const BOOL query_result = api_.get_pointer_frame_touchpad_info_history(
            pointer_id,
            &entries_count,
            &pointer_count,
            nullptr
        );
        if ((!query_result && (entries_count == 0 || pointer_count == 0)) ||
            entries_count == 0 || pointer_count == 0 ||
            entries_count > MaxHistoryEntries || pointer_count > MaxPointersPerFrame ||
            entries_count > std::numeric_limits<size_t>::max() / pointer_count) {
            return false;
        }

        const size_t value_count =
            static_cast<size_t>(entries_count) * static_cast<size_t>(pointer_count);
        payload->values.assign(value_count, POINTER_TOUCH_INFO{});
        uint32_t fetched_entries = entries_count;
        uint32_t fetched_pointers = pointer_count;
        if (api_.get_pointer_frame_touchpad_info_history(
                pointer_id,
                &fetched_entries,
                &fetched_pointers,
                payload->values.data()
            )) {
            if (fetched_entries == 0 || fetched_pointers == 0 ||
                fetched_entries > entries_count || fetched_pointers > pointer_count ||
                static_cast<size_t>(fetched_entries) * fetched_pointers > value_count) {
                return false;
            }
            payload->entries_count = fetched_entries;
            payload->pointer_count = fetched_pointers;
            payload->values.resize(
                static_cast<size_t>(fetched_entries) * fetched_pointers
            );
            if (!payload->values.empty()) {
                RECT display_rect = {};
                payload->has_device_rect = api_.get_pointer_device_rects(
                    payload->values[0].pointerInfo.sourceDevice,
                    &payload->device_rect,
                    &display_rect
                ) == TRUE;
            }
            return true;
        }
    }
    return false;
}

bool Binding::dispatch_history(const HistoryPayload& payload) {
    bool consumed = false;
    for (uint32_t entry = 0; entry < payload.entries_count; ++entry) {
        const POINTER_INFO& pointer =
            payload.values[static_cast<size_t>(entry) * payload.pointer_count].pointerInfo;
        consumed = consumed || cached_consumption(
            device_id(pointer.sourceDevice),
            static_cast<uint64_t>(pointer.frameId),
            raw_timestamp(pointer)
        );
    }

    const ProcessResult process_result = processor_.process(
        payload.values.data(),
        payload.entries_count,
        payload.pointer_count,
        payload.has_device_rect ? &payload.device_rect : nullptr
    );
    if (process_result.malformed) {
        frame_decisions_.clear();
        if (process_result.cancelled && cancel_callback_ != nullptr) {
            notify_cancel();
        }
        return consumed;
    }

    for (const Event& event : process_result.events) {
        std::vector<SkikoWinUIIndirectPointerChangeView> change_views;
        change_views.reserve(event.changes.size());
        for (const Change& change : event.changes) {
            change_views.push_back(SkikoWinUIIndirectPointerChangeView{
                change.pointer_id,
                change.timestamp_millis,
                change.x,
                change.y,
                change.pressed,
                change.pressure,
                change.previous_timestamp_millis,
                change.previous_x,
                change.previous_y,
                change.previous_pressed,
            });
        }
        const SkikoWinUIIndirectPointerEventView event_view{
            static_cast<int32_t>(event.type),
            change_views.data(),
            static_cast<uint32_t>(change_views.size()),
            event.primary_directional_motion_axis,
            event.device_id,
            event.device_rect.has_value() ? uint8_t{1} : uint8_t{0},
            event.device_rect.has_value() ? event.device_rect->left : 0,
            event.device_rect.has_value() ? event.device_rect->top : 0,
            event.device_rect.has_value() ? event.device_rect->right : 0,
            event.device_rect.has_value() ? event.device_rect->bottom : 0,
            event.frame_id,
        };
        int32_t callback_result = SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_UNCONSUMED;
        if (event_callback_ != nullptr) {
            ++callback_depth_;
            callback_result = event_callback_(context_, &event_view);
            --callback_depth_;
        }
        if (callback_result == SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED) {
            processor_.cancel();
            frame_decisions_.clear();
            if (cancel_callback_ != nullptr) {
                notify_cancel();
            }
            return consumed;
        }
        const bool event_consumed =
            callback_result == SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_CONSUMED;
        consumed = consumed || event_consumed;
        cache_consumption(event, event_consumed);
    }
    return consumed;
}

bool Binding::cached_consumption(
    uint64_t device_id_value,
    uint64_t frame_id_value,
    uint64_t timestamp
) const {
    const auto decision = std::find_if(
        frame_decisions_.begin(),
        frame_decisions_.end(),
        [&](const FrameDecision& candidate) {
            return candidate.device_id == device_id_value &&
                candidate.frame_id == frame_id_value &&
                candidate.raw_timestamp == timestamp;
        }
    );
    return decision != frame_decisions_.end() && decision->consumed;
}

void Binding::cache_consumption(const Event& event, bool consumed) {
    auto decision = std::find_if(
        frame_decisions_.begin(),
        frame_decisions_.end(),
        [&](const FrameDecision& candidate) {
            return candidate.device_id == event.device_id &&
                candidate.frame_id == event.frame_id &&
                candidate.raw_timestamp == event.raw_timestamp;
        }
    );
    const FrameDecision value{
        event.device_id,
        event.frame_id,
        event.raw_timestamp,
        consumed,
    };
    if (decision == frame_decisions_.end()) {
        frame_decisions_.push_back(value);
        if (frame_decisions_.size() > MaxRememberedFrames) {
            frame_decisions_.erase(frame_decisions_.begin());
        }
    } else {
        *decision = value;
    }
}

void Binding::notify_cancel() {
    if (cancel_callback_ == nullptr) {
        return;
    }
    ++callback_depth_;
    cancel_callback_(context_);
    --callback_depth_;
}

void Binding::clear_stream(bool notify) {
    const bool had_active_stream = processor_.cancel();
    frame_decisions_.clear();
    if (notify && had_active_stream && cancel_callback_ != nullptr) {
        notify_cancel();
    }
}

void Binding::on_window_destroyed() {
    if (registration_active_) {
        api_.register_touchpad_capable_window(hwnd_, FALSE);
        registration_active_ = false;
    }
    subclass_installed_ = false;
    if (registered_in_process_) {
        release_hwnd(this);
        registered_in_process_ = false;
    }
    closed_ = true;
}

Binding* create_binding_for_test(
    HWND hwnd,
    const NativeApiTable& api,
    void* context,
    SkikoWinUIIndirectPointerEventCallback event_callback,
    SkikoWinUIIndirectPointerCancelCallback cancel_callback,
    int32_t* unavailable_reason
) {
    if (!api_is_complete(api)) {
        if (unavailable_reason != nullptr) {
            *unavailable_reason = SKIKO_WINUI_INDIRECT_POINTER_API_NOT_PRESENT;
        }
        return nullptr;
    }
    if (hwnd == nullptr) {
        if (unavailable_reason != nullptr) {
            *unavailable_reason = SKIKO_WINUI_INDIRECT_POINTER_HWND_UNAVAILABLE;
        }
        return nullptr;
    }
    auto* binding = new Binding(hwnd, api, context, event_callback, cancel_callback);
    if (!binding->initialize(unavailable_reason)) {
        delete binding;
        return nullptr;
    }
    return binding;
}

LRESULT dispatch_message_for_test(
    Binding* binding,
    UINT message,
    WPARAM w_param,
    LPARAM l_param
) {
    return binding != nullptr
        ? binding->dispatch(message, w_param, l_param)
        : 0;
}

bool cancel_binding_for_test(Binding* binding) {
    return binding == nullptr || binding->cancel();
}

bool close_binding_for_test(Binding* binding) {
    return binding == nullptr || binding->close();
}

void destroy_binding_for_test(Binding* binding) {
    if (binding != nullptr) {
        binding->close();
        delete binding;
    }
}

}  // namespace skiko::winui::indirect

extern "C" {
    void* skiko_winui_indirect_pointer_create(
        void* window_inspectable,
        void* context,
        SkikoWinUIIndirectPointerEventCallback event_callback,
        SkikoWinUIIndirectPointerCancelCallback cancel_callback,
        int32_t* unavailable_reason
    ) {
        const skiko::winui::indirect::NativeApiTable api =
            skiko::winui::indirect::resolve_native_api();
        if (!skiko::winui::indirect::api_is_complete(api)) {
            if (unavailable_reason != nullptr) {
                *unavailable_reason = SKIKO_WINUI_INDIRECT_POINTER_API_NOT_PRESENT;
            }
            return nullptr;
        }
        HWND hwnd = skiko::winui::indirect::window_handle(window_inspectable);
        return skiko::winui::indirect::create_binding_for_test(
            hwnd,
            api,
            context,
            event_callback,
            cancel_callback,
            unavailable_reason
        );
    }

    bool skiko_winui_indirect_pointer_cancel(void* binding) {
        return skiko::winui::indirect::cancel_binding_for_test(
            static_cast<skiko::winui::indirect::Binding*>(binding)
        );
    }

    bool skiko_winui_indirect_pointer_close(void* binding) {
        auto* native_binding = static_cast<skiko::winui::indirect::Binding*>(binding);
        if (native_binding == nullptr) {
            return true;
        }
        if (!native_binding->close()) {
            return false;
        }
        delete native_binding;
        return true;
    }

    bool skiko_winui_indirect_pointer_is_active(void* binding) {
        auto* native_binding = static_cast<skiko::winui::indirect::Binding*>(binding);
        return native_binding != nullptr && native_binding->is_active();
    }
}
