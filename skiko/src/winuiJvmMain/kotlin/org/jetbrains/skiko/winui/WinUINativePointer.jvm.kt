package org.jetbrains.skiko.winui

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext

internal actual fun winuiNativePointer(value: Long): WinUINativePointer = value

internal actual fun winuiMakeDirect3DContext(
    adapterPtr: WinUINativePointer,
    devicePtr: WinUINativePointer,
    queuePtr: WinUINativePointer,
): DirectContext =
    DirectContext.makeDirect3D(
        adapterPtr = adapterPtr,
        devicePtr = devicePtr,
        queuePtr = queuePtr,
    )

// The JVM target keeps the resource cache of Skia as it is.
internal actual fun winuiPerformDeferredCleanup(context: DirectContext, notUsedMillis: Long) = Unit

internal actual fun winuiDescribeGpuMemory(context: DirectContext): String? = null

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
        texturePtr = texturePtr,
        format = format,
        sampleCnt = sampleCnt,
        levelCnt = levelCnt,
    )
