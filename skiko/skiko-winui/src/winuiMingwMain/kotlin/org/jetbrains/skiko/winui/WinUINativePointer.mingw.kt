package org.jetbrains.skiko.winui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.native.internal.NativePtr

@OptIn(ExperimentalForeignApi::class)
internal actual fun winuiNativePointer(value: Long): WinUINativePointer =
    NativePtr.NULL + value
