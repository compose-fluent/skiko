package org.jetbrains.skiko.winui

internal actual fun createWinUISkiaHost(): WinUISkiaHost =
    WinUISkiaHostPanel()
