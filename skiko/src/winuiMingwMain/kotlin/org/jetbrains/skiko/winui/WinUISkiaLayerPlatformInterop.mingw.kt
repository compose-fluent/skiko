package org.jetbrains.skiko.winui

internal actual fun prepareWinUIDirect3DRenderBridge() {
}

internal actual fun winuiDirect3DRenderBridge(): WinUIDirect3DRenderBridge =
    WinUISkiaLayerNative
