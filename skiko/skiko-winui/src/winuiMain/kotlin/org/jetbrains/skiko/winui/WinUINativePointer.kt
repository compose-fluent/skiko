package org.jetbrains.skiko.winui

internal typealias WinUINativePointer = Long

internal expect fun winuiNativePointer(value: Long): WinUINativePointer
