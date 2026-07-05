package org.jetbrains.skiko.winui

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext

internal typealias WinUINativePointer = Long

internal expect fun winuiNativePointer(value: Long): WinUINativePointer

internal expect fun winuiMakeDirect3DContext(
    adapterPtr: WinUINativePointer,
    devicePtr: WinUINativePointer,
    queuePtr: WinUINativePointer,
): DirectContext

internal expect fun winuiMakeDirect3DRenderTarget(
    width: Int,
    height: Int,
    texturePtr: WinUINativePointer,
    format: Int,
    sampleCnt: Int,
    levelCnt: Int,
): BackendRenderTarget
