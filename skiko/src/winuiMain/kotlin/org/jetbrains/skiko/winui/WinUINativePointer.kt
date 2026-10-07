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

/** Frees the GPU resources of the cache of [context] that no frame used for [notUsedMillis]. */
internal expect fun winuiPerformDeferredCleanup(context: DirectContext, notUsedMillis: Long)

/** The GPU memory of [context], for the frame statistics; `null` where it is not known. */
internal expect fun winuiDescribeGpuMemory(context: DirectContext): String?

internal expect fun winuiMakeDirect3DRenderTarget(
    width: Int,
    height: Int,
    texturePtr: WinUINativePointer,
    format: Int,
    sampleCnt: Int,
    levelCnt: Int,
): BackendRenderTarget
