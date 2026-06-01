package org.jetbrains.skiko.winui

import microsoft.ui.xaml.controls.SwapChainPanel

internal actual class WinUISkiaLayerPlatformInterop actual constructor(
    private val layer: WinUISkiaLayer,
    private val panel: SwapChainPanel,
) : AutoCloseable {
    actual fun render(
        width: Int,
        height: Int,
        contentScale: Float,
        throttledToVsync: Boolean,
    ): WinUIPlatformRenderResult {
        error("WinUISkiaLayer native rendering for winui-mingw is not implemented yet.")
    }

    actual override fun close() {
    }
}
