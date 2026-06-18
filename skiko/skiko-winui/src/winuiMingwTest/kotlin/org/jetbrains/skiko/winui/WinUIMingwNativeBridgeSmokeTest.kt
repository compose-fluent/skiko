package org.jetbrains.skiko.winui

import kotlin.test.Test
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
}
