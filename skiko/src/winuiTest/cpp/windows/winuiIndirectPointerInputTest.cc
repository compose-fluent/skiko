#include "winuiIndirectPointerInput.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <vector>

using skiko::winui::indirect::EventType;
using skiko::winui::indirect::HistoryProcessor;

namespace {
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
}

int main() {
    parsesReverseChronologicalHistory();
    preservesMultiContactTransitions();
    prioritizesPressWhenFrameAlsoReleases();
    rejectsMalformedPayloadAndDeviceChanges();
    extendsWrappedDwTime();
    puts("winui indirect pointer native tests passed");
    return 0;
}
