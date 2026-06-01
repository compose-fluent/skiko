package org.jetbrains.skiko.winui

import microsoft.ui.xaml.controls.SwapChainPanel

internal expect class WinUISkiaLayerPlatformInterop(
    layer: WinUISkiaLayer,
    panel: SwapChainPanel,
) : AutoCloseable {
    fun render(
        width: Int,
        height: Int,
        contentScale: Float,
        throttledToVsync: Boolean,
    ): WinUIPlatformRenderResult

    override fun close()
}
