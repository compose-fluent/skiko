#include "winuiIndirectPointerInput.h"

#include <algorithm>
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <vector>

using skiko::winui::indirect::EventType;
using skiko::winui::indirect::HistoryProcessor;

namespace {
    struct FakeRuntime {
        bool set_subclass_result = true;
        bool registration_result = true;
        int32_t event_result = SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_UNCONSUMED;
        int register_enable_calls = 0;
        int register_disable_calls = 0;
        int remove_subclass_calls = 0;
        int def_subclass_calls = 0;
        int event_calls = 0;
        int cancel_calls = 0;
        std::vector<POINTER_TOUCH_INFO> history;
        uint32_t entries_count = 0;
        uint32_t pointer_count = 0;
    };

    FakeRuntime* active_runtime = nullptr;

    BOOL WINAPI fake_register_touchpad_window(HWND, BOOL enable) {
        if (enable) {
            ++active_runtime->register_enable_calls;
            return active_runtime->registration_result ? TRUE : FALSE;
        }
        ++active_runtime->register_disable_calls;
        return TRUE;
    }

    BOOL WINAPI fake_get_touchpad_history(
        uint32_t,
        uint32_t* entries_count,
        uint32_t* pointer_count,
        POINTER_TOUCH_INFO* history
    ) {
        if (history == nullptr) {
            *entries_count = active_runtime->entries_count;
            *pointer_count = active_runtime->pointer_count;
            return TRUE;
        }
        const size_t required = static_cast<size_t>(active_runtime->entries_count) *
            active_runtime->pointer_count;
        if (*entries_count < active_runtime->entries_count ||
            *pointer_count < active_runtime->pointer_count) {
            *entries_count = active_runtime->entries_count;
            *pointer_count = active_runtime->pointer_count;
            return FALSE;
        }
        std::copy(active_runtime->history.begin(), active_runtime->history.end(), history);
        *entries_count = active_runtime->entries_count;
        *pointer_count = active_runtime->pointer_count;
        return required == active_runtime->history.size() ? TRUE : FALSE;
    }

    BOOL WINAPI fake_get_pointer_type(uint32_t, POINTER_INPUT_TYPE* pointer_type) {
        *pointer_type = PT_TOUCHPAD;
        return TRUE;
    }

    BOOL WINAPI fake_get_pointer_device_rects(HANDLE, RECT* device_rect, RECT* display_rect) {
        *device_rect = RECT{0, 0, 12'000, 7'000};
        *display_rect = RECT{0, 0, 1'920, 1'080};
        return TRUE;
    }

    BOOL WINAPI fake_set_window_subclass(HWND, SUBCLASSPROC, UINT_PTR, DWORD_PTR) {
        return active_runtime->set_subclass_result ? TRUE : FALSE;
    }

    BOOL WINAPI fake_remove_window_subclass(HWND, SUBCLASSPROC, UINT_PTR) {
        ++active_runtime->remove_subclass_calls;
        return TRUE;
    }

    LRESULT WINAPI fake_def_subclass_proc(HWND, UINT, WPARAM, LPARAM) {
        ++active_runtime->def_subclass_calls;
        return 1234;
    }

    int32_t on_fake_event(void* context, const SkikoWinUIIndirectPointerEventView* event) {
        auto* runtime = static_cast<FakeRuntime*>(context);
        ++runtime->event_calls;
        assert(event->change_count > 0);
        assert(event->primary_directional_motion_axis ==
            SKIKO_WINUI_INDIRECT_POINTER_AXIS_NONE);
        return runtime->event_result;
    }

    void on_fake_cancel(void* context) {
        ++static_cast<FakeRuntime*>(context)->cancel_calls;
    }

    skiko::winui::indirect::NativeApiTable fake_api() {
        return skiko::winui::indirect::NativeApiTable{
            fake_register_touchpad_window,
            fake_get_touchpad_history,
            fake_get_pointer_type,
            fake_get_pointer_device_rects,
            fake_set_window_subclass,
            fake_remove_window_subclass,
            fake_def_subclass_proc,
        };
    }

    POINTER_TOUCH_INFO touch(
        uint32_t pointerId,
        uint32_t frameId,
        POINTER_FLAGS flags,
        int32_t x,
        int32_t y,
        uint32_t pressure,
        TOUCH_MASK touchMask,
        uint32_t time,
        uint64_t performanceCount,
        uintptr_t device = 1
    ) {
        POINTER_TOUCH_INFO value = {};
        value.pointerInfo.pointerType = PT_TOUCHPAD;
        value.pointerInfo.pointerId = pointerId;
        value.pointerInfo.frameId = frameId;
        value.pointerInfo.pointerFlags = flags;
        value.pointerInfo.sourceDevice = reinterpret_cast<HANDLE>(device);
        value.pointerInfo.ptHimetricLocation = POINT{x, y};
        value.pointerInfo.dwTime = time;
        value.pointerInfo.PerformanceCount = performanceCount;
        value.touchMask = touchMask;
        value.pressure = pressure;
        return value;
    }

    void parsesReverseChronologicalHistory() {
        HistoryProcessor processor(1'000);
        const RECT deviceRect{0, 0, 12'000, 7'000};
        const std::vector<POINTER_TOUCH_INFO> history = {
            touch(7, 12, POINTER_FLAG_UP, 1'400, 350, 0, TOUCH_MASK_PRESSURE, 30, 30),
            touch(7, 11, POINTER_FLAG_UPDATE | POINTER_FLAG_INCONTACT, 1'300, 340, 512, TOUCH_MASK_PRESSURE, 20, 20),
            touch(7, 10, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW, 1'200, 320, 256, TOUCH_MASK_PRESSURE, 10, 10),
        };

        const auto result = processor.process(history.data(), 3, 1, &deviceRect);

        assert(!result.malformed);
        assert(!result.cancelled);
        assert(result.events.size() == 3);
        assert(result.events[0].type == EventType::Press);
        assert(result.events[0].changes[0].x == 1'200.0f);
        assert(result.events[0].changes[0].previous_x == 1'200.0f);
        assert(result.events[0].changes[0].previous_pressed == 0);
        assert(result.events[1].type == EventType::Move);
        assert(result.events[1].changes[0].previous_x == 1'200.0f);
        assert(result.events[1].changes[0].pressure == 0.5f);
        assert(result.events[2].type == EventType::Release);
        assert(result.events[2].changes[0].pressed == 0);
        assert(result.events[2].device_rect.has_value());
        assert(result.events[2].device_rect->right == 12'000);

        const auto duplicate = processor.process(history.data(), 3, 1, &deviceRect);
        assert(!duplicate.malformed);
        assert(duplicate.events.empty());
    }

    void preservesMultiContactTransitions() {
        HistoryProcessor processor(1'000);
        const std::vector<POINTER_TOUCH_INFO> first = {
            touch(1, 1, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW, 100, 200, 0, TOUCH_MASK_NONE, 1, 1),
        };
        assert(processor.process(first.data(), 1, 1, nullptr).events.size() == 1);

        const std::vector<POINTER_TOUCH_INFO> joined = {
            touch(1, 2, POINTER_FLAG_UPDATE | POINTER_FLAG_INCONTACT, 110, 210, 0, TOUCH_MASK_NONE, 2, 2),
            touch(2, 2, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW, 300, 400, 0, TOUCH_MASK_NONE, 2, 2),
        };
        const auto press = processor.process(joined.data(), 1, 2, nullptr);
        assert(press.events.size() == 1);
        assert(press.events[0].type == EventType::Press);
        assert(press.events[0].changes.size() == 2);
        assert(press.events[0].changes[0].pressure == 1.0f);
        assert(press.events[0].changes[1].previous_pressed == 0);

        const std::vector<POINTER_TOUCH_INFO> released = {
            touch(1, 3, POINTER_FLAG_UPDATE | POINTER_FLAG_INCONTACT, 120, 220, 0, TOUCH_MASK_NONE, 3, 3),
            touch(2, 3, POINTER_FLAG_UP, 310, 410, 0, TOUCH_MASK_NONE, 3, 3),
        };
        const auto release = processor.process(released.data(), 1, 2, nullptr);
        assert(release.events.size() == 1);
        assert(release.events[0].type == EventType::Release);
        assert(release.events[0].changes[1].pressure == 0.0f);

        const std::vector<POINTER_TOUCH_INFO> remaining = {
            touch(1, 4, POINTER_FLAG_UPDATE | POINTER_FLAG_INCONTACT, 130, 230, 0, TOUCH_MASK_NONE, 4, 4),
        };
        const auto move = processor.process(remaining.data(), 1, 1, nullptr);
        assert(move.events.size() == 1);
        assert(move.events[0].type == EventType::Move);
        assert(move.events[0].changes.size() == 1);
    }

    void prioritizesPressWhenFrameAlsoReleases() {
        HistoryProcessor processor(1'000);
        const std::vector<POINTER_TOUCH_INFO> first = {
            touch(1, 1, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW, 10, 10, 0, TOUCH_MASK_NONE, 1, 1),
        };
        processor.process(first.data(), 1, 1, nullptr);

        const std::vector<POINTER_TOUCH_INFO> mixed = {
            touch(1, 2, POINTER_FLAG_UP, 20, 20, 0, TOUCH_MASK_NONE, 2, 2),
            touch(2, 2, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW, 30, 30, 0, TOUCH_MASK_NONE, 2, 2),
        };
        const auto result = processor.process(mixed.data(), 1, 2, nullptr);
        assert(result.events.size() == 1);
        assert(result.events[0].type == EventType::Press);
    }

    void rejectsMalformedPayloadAndDeviceChanges() {
        HistoryProcessor processor(1'000);
        assert(processor.process(nullptr, 257, 1, nullptr).malformed);
        assert(processor.process(nullptr, 1, 33, nullptr).malformed);

        const std::vector<POINTER_TOUCH_INFO> first = {
            touch(1, 1, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW, 10, 10, 0, TOUCH_MASK_NONE, 1, 1, 1),
        };
        processor.process(first.data(), 1, 1, nullptr);
        const std::vector<POINTER_TOUCH_INFO> changedDevice = {
            touch(1, 2, POINTER_FLAG_UPDATE | POINTER_FLAG_INCONTACT, 20, 20, 0, TOUCH_MASK_NONE, 2, 2, 2),
        };
        const auto result = processor.process(changedDevice.data(), 1, 1, nullptr);
        assert(result.malformed);
        assert(result.cancelled);
    }

    void extendsWrappedDwTime() {
        HistoryProcessor processor(0);
        const std::vector<POINTER_TOUCH_INFO> down = {
            touch(1, 1, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW, 10, 10, 0, TOUCH_MASK_NONE, 0xfffffff0u, 0),
        };
        const auto first = processor.process(down.data(), 1, 1, nullptr);
        const std::vector<POINTER_TOUCH_INFO> move = {
            touch(1, 2, POINTER_FLAG_UPDATE | POINTER_FLAG_INCONTACT, 20, 20, 0, TOUCH_MASK_NONE, 0x10u, 0),
        };
        const auto second = processor.process(move.data(), 1, 1, nullptr);
        assert(second.events[0].changes[0].timestamp_millis -
            first.events[0].changes[0].timestamp_millis == 32);
    }

    void reportsCapabilityFailuresAndExclusiveOwnership() {
        using namespace skiko::winui::indirect;
        FakeRuntime runtime;
        active_runtime = &runtime;
        int32_t reason = SKIKO_WINUI_INDIRECT_POINTER_AVAILABLE;

        auto* binding = create_binding_for_test(
            reinterpret_cast<HWND>(1),
            NativeApiTable{},
            &runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(binding == nullptr);
        assert(reason == SKIKO_WINUI_INDIRECT_POINTER_API_NOT_PRESENT);

        binding = create_binding_for_test(
            nullptr,
            fake_api(),
            &runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(binding == nullptr);
        assert(reason == SKIKO_WINUI_INDIRECT_POINTER_HWND_UNAVAILABLE);

        runtime.set_subclass_result = false;
        binding = create_binding_for_test(
            reinterpret_cast<HWND>(2),
            fake_api(),
            &runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(binding == nullptr);
        assert(reason == SKIKO_WINUI_INDIRECT_POINTER_SUBCLASS_FAILED);

        runtime.set_subclass_result = true;
        runtime.registration_result = false;
        binding = create_binding_for_test(
            reinterpret_cast<HWND>(3),
            fake_api(),
            &runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(binding == nullptr);
        assert(reason == SKIKO_WINUI_INDIRECT_POINTER_REGISTRATION_FAILED);

        runtime.registration_result = true;
        binding = create_binding_for_test(
            reinterpret_cast<HWND>(4),
            fake_api(),
            &runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(binding != nullptr);
        assert(reason == SKIKO_WINUI_INDIRECT_POINTER_AVAILABLE);
        auto* duplicate = create_binding_for_test(
            reinterpret_cast<HWND>(4),
            fake_api(),
            &runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(duplicate == nullptr);
        assert(reason == SKIKO_WINUI_INDIRECT_POINTER_ALREADY_BOUND);
        assert(close_binding_for_test(binding));
        assert(close_binding_for_test(binding));
        destroy_binding_for_test(binding);
    }

    void preservesFallbackAndCachedConsumption() {
        using namespace skiko::winui::indirect;
        FakeRuntime runtime;
        runtime.history = {
            touch(1, 1, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW,
                10, 20, 0, TOUCH_MASK_NONE, 1, 1),
        };
        runtime.entries_count = 1;
        runtime.pointer_count = 1;
        active_runtime = &runtime;
        int32_t reason = 0;
        auto* binding = create_binding_for_test(
            reinterpret_cast<HWND>(5),
            fake_api(),
            &runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(binding != nullptr);

        const LRESULT first = dispatch_message_for_test(
            binding,
            WM_POINTERDOWN,
            MAKEWPARAM(1, 0),
            0
        );
        assert(first == 1234);
        assert(runtime.event_calls == 1);
        assert(runtime.def_subclass_calls == 1);

        dispatch_message_for_test(binding, WM_POINTERUPDATE, MAKEWPARAM(1, 0), 0);
        assert(runtime.event_calls == 1);
        assert(runtime.def_subclass_calls == 2);
        destroy_binding_for_test(binding);

        FakeRuntime consumed_runtime;
        consumed_runtime.event_result = SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_CONSUMED;
        consumed_runtime.history = runtime.history;
        consumed_runtime.entries_count = 1;
        consumed_runtime.pointer_count = 1;
        active_runtime = &consumed_runtime;
        binding = create_binding_for_test(
            reinterpret_cast<HWND>(6),
            fake_api(),
            &consumed_runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(binding != nullptr);
        assert(dispatch_message_for_test(
            binding,
            WM_POINTERDOWN,
            MAKEWPARAM(1, 0),
            0
        ) == 0);
        assert(consumed_runtime.def_subclass_calls == 0);
        dispatch_message_for_test(binding, WM_POINTERUPDATE, MAKEWPARAM(1, 0), 0);
        assert(consumed_runtime.event_calls == 1);
        assert(consumed_runtime.def_subclass_calls == 0);
        destroy_binding_for_test(binding);
    }

    void cancelsActiveStreamsAndContainsCallbackFailures() {
        using namespace skiko::winui::indirect;
        FakeRuntime runtime;
        runtime.history = {
            touch(1, 1, POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_NEW,
                10, 20, 0, TOUCH_MASK_NONE, 1, 1),
        };
        runtime.entries_count = 1;
        runtime.pointer_count = 1;
        active_runtime = &runtime;
        int32_t reason = 0;
        auto* binding = create_binding_for_test(
            reinterpret_cast<HWND>(7),
            fake_api(),
            &runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        dispatch_message_for_test(binding, WM_POINTERDOWN, MAKEWPARAM(1, 0), 0);
        assert(cancel_binding_for_test(binding));
        assert(cancel_binding_for_test(binding));
        assert(runtime.cancel_calls == 1);
        destroy_binding_for_test(binding);

        FakeRuntime failed_runtime;
        failed_runtime.event_result = SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED;
        failed_runtime.history = runtime.history;
        failed_runtime.entries_count = 1;
        failed_runtime.pointer_count = 1;
        active_runtime = &failed_runtime;
        binding = create_binding_for_test(
            reinterpret_cast<HWND>(8),
            fake_api(),
            &failed_runtime,
            on_fake_event,
            on_fake_cancel,
            &reason
        );
        assert(dispatch_message_for_test(
            binding,
            WM_POINTERDOWN,
            MAKEWPARAM(1, 0),
            0
        ) == 1234);
        assert(failed_runtime.cancel_calls == 1);
        assert(failed_runtime.def_subclass_calls == 1);
        destroy_binding_for_test(binding);
    }
}

int main() {
    parsesReverseChronologicalHistory();
    preservesMultiContactTransitions();
    prioritizesPressWhenFrameAlsoReleases();
    rejectsMalformedPayloadAndDeviceChanges();
    extendsWrappedDwTime();
    reportsCapabilityFailuresAndExclusiveOwnership();
    preservesFallbackAndCachedConsumption();
    cancelsActiveStreamsAndContainsCallbackFailures();
    puts("winui indirect pointer native tests passed");
    return 0;
}
