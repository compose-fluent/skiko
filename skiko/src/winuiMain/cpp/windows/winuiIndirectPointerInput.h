#pragma once

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <Windows.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum SkikoWinUIIndirectPointerEventType {
    SKIKO_WINUI_INDIRECT_POINTER_PRESS = 0,
    SKIKO_WINUI_INDIRECT_POINTER_MOVE = 1,
    SKIKO_WINUI_INDIRECT_POINTER_RELEASE = 2,
};

enum SkikoWinUIIndirectPointerPrimaryDirectionalMotionAxis {
    SKIKO_WINUI_INDIRECT_POINTER_AXIS_NONE = 0,
    SKIKO_WINUI_INDIRECT_POINTER_AXIS_X = 1,
    SKIKO_WINUI_INDIRECT_POINTER_AXIS_Y = 2,
};

enum SkikoWinUIIndirectPointerUnavailableReason {
    SKIKO_WINUI_INDIRECT_POINTER_AVAILABLE = 0,
    SKIKO_WINUI_INDIRECT_POINTER_API_NOT_PRESENT = 1,
    SKIKO_WINUI_INDIRECT_POINTER_HWND_UNAVAILABLE = 2,
    SKIKO_WINUI_INDIRECT_POINTER_ALREADY_BOUND = 3,
    SKIKO_WINUI_INDIRECT_POINTER_SUBCLASS_FAILED = 4,
    SKIKO_WINUI_INDIRECT_POINTER_REGISTRATION_FAILED = 5,
};

enum SkikoWinUIIndirectPointerCallbackResult {
    SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED = -1,
    SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_UNCONSUMED = 0,
    SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_CONSUMED = 1,
};

struct SkikoWinUIIndirectPointerChangeView {
    uint64_t pointer_id;
    int64_t timestamp_millis;
    float x;
    float y;
    uint8_t pressed;
    float pressure;
    int64_t previous_timestamp_millis;
    float previous_x;
    float previous_y;
    uint8_t previous_pressed;
};

struct SkikoWinUIIndirectPointerEventView {
    int32_t type;
    const SkikoWinUIIndirectPointerChangeView* changes;
    uint32_t change_count;
    int32_t primary_directional_motion_axis;
    uint64_t device_id;
    uint8_t has_device_rect;
    int32_t device_rect_left;
    int32_t device_rect_top;
    int32_t device_rect_right;
    int32_t device_rect_bottom;
    uint64_t frame_id;
};

typedef int32_t (*SkikoWinUIIndirectPointerEventCallback)(
    void* context,
    const SkikoWinUIIndirectPointerEventView* event
);

typedef void (*SkikoWinUIIndirectPointerCancelCallback)(void* context);

void* skiko_winui_indirect_pointer_create(
    void* window_inspectable,
    void* context,
    SkikoWinUIIndirectPointerEventCallback event_callback,
    SkikoWinUIIndirectPointerCancelCallback cancel_callback,
    int32_t* unavailable_reason
);

bool skiko_winui_indirect_pointer_cancel(void* binding);
bool skiko_winui_indirect_pointer_close(void* binding);
bool skiko_winui_indirect_pointer_is_active(void* binding);

#ifdef __cplusplus
}

#include <optional>
#include <vector>

namespace skiko::winui::indirect {

enum class EventType : int32_t {
    Press = SKIKO_WINUI_INDIRECT_POINTER_PRESS,
    Move = SKIKO_WINUI_INDIRECT_POINTER_MOVE,
    Release = SKIKO_WINUI_INDIRECT_POINTER_RELEASE,
};

struct DeviceRect {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
};

struct Change {
    uint64_t pointer_id;
    int64_t timestamp_millis;
    float x;
    float y;
    uint8_t pressed;
    float pressure;
    int64_t previous_timestamp_millis;
    float previous_x;
    float previous_y;
    uint8_t previous_pressed;
};

struct Event {
    EventType type;
    std::vector<Change> changes;
    int32_t primary_directional_motion_axis;
    uint64_t device_id;
    std::optional<DeviceRect> device_rect;
    uint64_t frame_id;
};

struct ProcessResult {
    bool malformed = false;
    bool cancelled = false;
    std::vector<Event> events;
};

class HistoryProcessor {
public:
    explicit HistoryProcessor(uint64_t performance_frequency = 0);

    ProcessResult process(
        const POINTER_TOUCH_INFO* history,
        uint32_t entries_count,
        uint32_t pointer_count,
        const RECT* device_rect
    );

    bool cancel();
    bool has_active_stream() const;

private:
    struct PointerState {
        uint64_t pointer_id;
        int64_t timestamp_millis;
        float x;
        float y;
    };

    struct FrameKey {
        uint64_t device_id;
        uint64_t frame_id;
        uint64_t raw_timestamp;
    };

    uint64_t performance_frequency_;
    bool has_extended_dw_time_ = false;
    uint32_t last_dw_time_ = 0;
    uint64_t extended_dw_time_ = 0;
    bool has_active_device_ = false;
    uint64_t active_device_id_ = 0;
    bool ignore_until_clean_down_ = false;
    std::vector<PointerState> pointer_states_;
    std::vector<FrameKey> seen_frames_;

    ProcessResult malformed_result();
    int64_t timestamp_millis(const POINTER_INFO& pointer_info);
};

}  // namespace skiko::winui::indirect
#endif
