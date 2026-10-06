package org.jetbrains.skiko.winui

import org.jetbrains.skia.DirectContext

/**
 * The Direct3D 12 objects a [WinUISkiaLayer] renders with, for libraries that bring their own GPU
 * resources into Skia: a video backend, for example, opens its shared textures on [devicePtr] and
 * wraps them for [directContext] with `BackendRenderTarget.makeDirect3D` /
 * `Surface.makeFromBackendRenderTarget`, so Compose can draw them without a copy.
 *
 * The objects belong to the layer. They exist from the first rendered frame until the layer is
 * closed (see [WinUISkiaLayer.direct3DInterop]); after that [isValid] is false and nothing here may
 * be used. Pointers carry no reference of their own. Everything, Skia resources included, must be
 * used on the layer's UI thread, between frames or inside the render delegate.
 */
interface WinUIDirect3DInterop {
    /** The Skia context of the layer. Flushes and submissions are the layer's; callers only record into it. */
    val directContext: DirectContext

    /** `ID3D12Device*` the swap chain and [directContext] were created on. */
    val devicePtr: Long

    /** `ID3D12CommandQueue*` (direct queue) [directContext] submits to. */
    val queuePtr: Long

    /** False once the layer released the device; a new interop appears with the next device. */
    val isValid: Boolean
}
