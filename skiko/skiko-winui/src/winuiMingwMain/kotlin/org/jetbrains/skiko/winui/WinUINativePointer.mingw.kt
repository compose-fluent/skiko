package org.jetbrains.skiko.winui

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext
import kotlin.native.internal.NativePtr

internal actual fun winuiNativePointer(value: Long): WinUINativePointer =
    value

private fun skiaNativePointer(value: WinUINativePointer): NativePtr =
    NativePtr.NULL + value

internal actual fun winuiMakeDirect3DContext(
    adapterPtr: WinUINativePointer,
    devicePtr: WinUINativePointer,
    queuePtr: WinUINativePointer,
): DirectContext =
    DirectContext.makeDirect3D(
        adapterPtr = skiaNativePointer(adapterPtr),
        devicePtr = skiaNativePointer(devicePtr),
        queuePtr = skiaNativePointer(queuePtr),
    )

internal actual fun winuiMakeDirect3DRenderTarget(
    width: Int,
    height: Int,
    texturePtr: WinUINativePointer,
    format: Int,
    sampleCnt: Int,
    levelCnt: Int,
): BackendRenderTarget =
    BackendRenderTarget.makeDirect3D(
        width = width,
        height = height,
        texturePtr = skiaNativePointer(texturePtr),
        format = format,
        sampleCnt = sampleCnt,
        levelCnt = levelCnt,
    )
