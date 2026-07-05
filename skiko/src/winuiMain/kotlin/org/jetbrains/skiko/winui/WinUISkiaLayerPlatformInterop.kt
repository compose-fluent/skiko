package org.jetbrains.skiko.winui

import io.github.composefluent.winrt.runtime.ComObjectReference
import io.github.composefluent.winrt.runtime.Guid
import microsoft.ui.xaml.controls.SwapChainPanel

internal class WinUISkiaLayerPlatformInterop(
    layer: WinUISkiaLayer,
    private val panel: SwapChainPanel,
) : AutoCloseable {
    private val panelNativeReference: ComObjectReference
    private val renderer: WinUIDirect3DRenderer
    private var isDisposed = false

    init {
        prepareWinUIDirect3DRenderBridge()
        panelNativeReference = panel.nativeObject.queryInterface(SWAP_CHAIN_PANEL_NATIVE_IID).getOrElse { cause ->
            throw WinUIRenderException(
                "Failed to query ISwapChainPanelNative from WinUI SwapChainPanel.",
                cause,
            )
        }
        renderer = WinUIDirect3DRenderer(
            layer = layer,
            bridge = winuiDirect3DRenderBridge(),
            panelPointer = winuiNativePointer(panelNativeReference.pointer.value),
        )
    }

    fun render(
        width: Int,
        height: Int,
        contentScale: Float,
        throttledToVsync: Boolean,
    ): WinUIPlatformRenderResult {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return renderer.render(
            width = width,
            height = height,
            contentScale = contentScale,
            contentScaleX = panel.compositionScaleX,
            contentScaleY = panel.compositionScaleY,
            throttledToVsync = throttledToVsync,
        )
    }

    override fun close() {
        if (isDisposed) {
            return
        }
        renderer.close()
        panelNativeReference.close()
        isDisposed = true
    }

    private companion object {
        private val SWAP_CHAIN_PANEL_NATIVE_IID = Guid("63AAD0B8-7C24-40FF-85A8-640D944CC325")
    }
}

internal expect fun prepareWinUIDirect3DRenderBridge()

internal expect fun winuiDirect3DRenderBridge(): WinUIDirect3DRenderBridge
