package org.jetbrains.skiko.winui

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.reachabilityBarrier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.native.internal.NativePtr

internal actual fun winuiNativePointer(value: Long): WinUINativePointer =
    value

private fun skiaNativePointer(value: WinUINativePointer): NativePtr =
    NativePtr.NULL + value

/**
 * A Direct3D context whose GPU resources each get a heap of their own size
 * (`WinUIDirect3DContext.cc`), instead of Skia's default D3D12MA blocks of 32 to 256 MB.
 */
internal actual fun winuiMakeDirect3DContext(
    adapterPtr: WinUINativePointer,
    devicePtr: WinUINativePointer,
    queuePtr: WinUINativePointer,
): DirectContext {
    // SKIKO_WINUI_D3D12MA=1 goes back to Skia's own allocator (for comparisons).
    if (winuiEnvironmentVariable("SKIKO_WINUI_D3D12MA") == "1") {
        return DirectContext.makeDirect3D(
            adapterPtr = skiaNativePointer(adapterPtr),
            devicePtr = skiaNativePointer(devicePtr),
            queuePtr = skiaNativePointer(queuePtr),
        )
    }
    val context = _nMakeWinUIDirect3DContext(
        skiaNativePointer(adapterPtr),
        skiaNativePointer(devicePtr),
        skiaNativePointer(queuePtr),
    )
    check(context != NativePtr.NULL) { "Failed to create the Direct3D context of a WinUI layer." }
    return DirectContext(context)
}

@ExternalSymbolName("org_jetbrains_skiko_winui_WinUIDirect3D__1nMakeContext")
private external fun _nMakeWinUIDirect3DContext(
    adapterPtr: NativePtr,
    devicePtr: NativePtr,
    queuePtr: NativePtr,
): NativePtr

internal actual fun winuiPerformDeferredCleanup(context: DirectContext, notUsedMillis: Long) {
    _nPerformDeferredCleanup(context._ptr, notUsedMillis)
    reachabilityBarrier(context)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun winuiDescribeGpuMemory(context: DirectContext): String? {
    val usage = LongArray(4)
    usage.usePinned { _nGetMemoryUsage(context._ptr, it.addressOf(0).rawValue) }
    reachabilityBarrier(context)
    fun mb(bytes: Long) = (bytes * 10 / 1048576) / 10.0
    return "cache=${mb(usage[1])}MB/${usage[0]} purgeable=${mb(usage[2])}MB heaps=${mb(usage[3])}MB"
}

@ExternalSymbolName("org_jetbrains_skiko_winui_WinUIDirect3D__1nPerformDeferredCleanup")
private external fun _nPerformDeferredCleanup(contextPtr: NativePtr, notUsedMillis: Long)

@ExternalSymbolName("org_jetbrains_skiko_winui_WinUIDirect3D__1nGetMemoryUsage")
private external fun _nGetMemoryUsage(contextPtr: NativePtr, result: NativePtr)

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
