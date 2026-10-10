package org.jetbrains.skiko.winui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WinUIIndirectPointerEventTest {
    @Test
    fun preservesDeviceRelativeFrameFields() {
        val event = WinUIIndirectPointerEvent(
            type = WinUIIndirectPointerEventType.PRESS,
            changes = listOf(
                WinUIIndirectPointerChange(
                    pointerId = 7L,
                    timestampMillis = 12L,
                    x = 1450f,
                    y = 320f,
                    pressed = true,
                    pressure = 0.5f,
                    previousTimestampMillis = 12L,
                    previousX = 1450f,
                    previousY = 320f,
                    previousPressed = false,
                ),
                WinUIIndirectPointerChange(
                    pointerId = 8L,
                    timestampMillis = 12L,
                    x = 2100f,
                    y = 640f,
                    pressed = true,
                    pressure = 1f,
                    previousTimestampMillis = 10L,
                    previousX = 2050f,
                    previousY = 600f,
                    previousPressed = true,
                ),
            ),
            primaryDirectionalMotionAxis =
                WinUIIndirectPointerPrimaryDirectionalMotionAxis.NONE,
            deviceId = 99L,
            deviceRect = WinUIIndirectPointerDeviceRect(0, 0, 12_000, 7_000),
            frameId = 42L,
        )

        assertEquals(WinUIIndirectPointerEventType.PRESS, event.type)
        assertEquals(2, event.changes.size)
        assertEquals(1450f, event.changes[0].x)
        assertEquals(600f, event.changes[1].previousY)
        assertEquals(99L, event.deviceId)
        assertEquals(WinUIIndirectPointerDeviceRect(0, 0, 12_000, 7_000), event.deviceRect)
        assertEquals(42L, event.frameId)
    }

    @Test
    fun handlerDefaultsLeaveIndirectInputUnconsumed() {
        val handler = object : WinUIInputHandler {}
        val event = WinUIIndirectPointerEvent(
            type = WinUIIndirectPointerEventType.MOVE,
            changes = listOf(
                WinUIIndirectPointerChange(
                    pointerId = 1L,
                    timestampMillis = 1L,
                    x = 10f,
                    y = 20f,
                    pressed = true,
                    pressure = 1f,
                    previousTimestampMillis = 1L,
                    previousX = 10f,
                    previousY = 20f,
                    previousPressed = false,
                )
            ),
            primaryDirectionalMotionAxis =
                WinUIIndirectPointerPrimaryDirectionalMotionAxis.NONE,
            deviceId = 2L,
            deviceRect = null,
            frameId = 3L,
        )

        assertFalse(handler.onIndirectPointerEvent(event))
        handler.onIndirectPointerCancel()
    }
}
