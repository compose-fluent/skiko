package org.jetbrains.skiko.winui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WinUIMingwNativeBridgeSmokeTest {
    @Test
    fun choosesDirect3DAdapterThroughNativeBridge() {
        val bridge = winuiDirect3DRenderBridge()
        val adapter = bridge.chooseAdapter(adapterPriority = 0)

        assertNotEquals(
            illegal = WinUINullPointer,
            actual = adapter,
            message = "Expected skiko-winui mingw native bridge to find a non-software D3D12 adapter.",
        )

        val name = bridge.getAdapterName(adapter)
        assertTrue(
            actual = name.isNotBlank(),
            message = "Expected skiko-winui mingw native bridge to expose adapter name.",
        )

        val memorySize = bridge.getAdapterMemorySize(adapter)
        assertTrue(
            actual = memorySize >= 0,
            message = "Expected skiko-winui mingw native bridge to expose adapter memory size.",
        )
    }

    @Test
    fun exposesRenderExceptionSmokeHookLikeJvmBridge() {
        val bridge = winuiDirect3DRenderBridge()
        val exception = assertFailsWith<WinUIRenderException> {
            bridge.throwRenderExceptionForSmoke("expected smoke failure")
        }

        assertTrue(
            actual = exception.message?.contains("expected smoke failure") == true,
            message = "Expected skiko-winui mingw smoke hook to preserve exception message.",
        )
    }

    @Test
    fun exposesNativeFailureDiagnostics() {
        val bridge = winuiDirect3DRenderBridge()
        val exception = assertFailsWith<WinUIRenderException> {
            bridge.getBufferResourcePtr(WinUINullPointer, -1)
        }

        assertTrue(
            actual = exception.message?.contains("skiko_winui_getBufferResourcePtr") == true,
            message = "Expected skiko-winui mingw bridge failure to include native operation.",
        )
        assertTrue(
            actual = exception.message?.contains("Back buffer index is out of range") == true,
            message = "Expected skiko-winui mingw bridge failure to include native diagnostic detail.",
        )
    }

    @Test
    fun preservesIndirectPointerFieldsThroughNativeCallback() {
        var delivered: WinUIIndirectPointerEvent? = null
        val result = emitWinUIIndirectPointerInputNativeSmoke(
            object : WinUIIndirectPointerInputNativeCallback {
                override fun onEvent(event: WinUIIndirectPointerEvent): Boolean {
                    delivered = event
                    return true
                }

                override fun onCancel() = Unit
            },
        )

        assertEquals(1, result)
        assertEquals(sampleIndirectPointerEvent(), delivered)
    }

    @Test
    fun containsIndirectPointerCallbackFailuresAndCancels() {
        var cancelCalls = 0
        val result = emitWinUIIndirectPointerInputNativeSmoke(
            object : WinUIIndirectPointerInputNativeCallback {
                override fun onEvent(event: WinUIIndirectPointerEvent): Boolean {
                    error("expected mingw callback failure")
                }

                override fun onCancel() {
                    cancelCalls++
                }
            },
        )

        assertEquals(-1, result)
        assertEquals(1, cancelCalls)
    }

    private fun sampleIndirectPointerEvent(): WinUIIndirectPointerEvent =
        WinUIIndirectPointerEvent(
            type = WinUIIndirectPointerEventType.MOVE,
            changes = listOf(
                WinUIIndirectPointerChange(
                    pointerId = 7L,
                    timestampMillis = 12L,
                    x = 1450f,
                    y = 320f,
                    pressed = true,
                    pressure = 0.5f,
                    previousTimestampMillis = 11L,
                    previousX = 1400f,
                    previousY = 300f,
                    previousPressed = true,
                ),
                WinUIIndirectPointerChange(
                    pointerId = 8L,
                    timestampMillis = 13L,
                    x = 2100f,
                    y = 640f,
                    pressed = false,
                    pressure = 0f,
                    previousTimestampMillis = 12L,
                    previousX = 2050f,
                    previousY = 600f,
                    previousPressed = true,
                ),
            ),
            primaryDirectionalMotionAxis = WinUIIndirectPointerPrimaryDirectionalMotionAxis.Y,
            deviceId = 99L,
            deviceRect = WinUIIndirectPointerDeviceRect(-10, 20, 12_000, 7_000),
            frameId = 42L,
        )
}
