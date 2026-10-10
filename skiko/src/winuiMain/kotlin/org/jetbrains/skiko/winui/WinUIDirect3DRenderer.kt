package org.jetbrains.skiko.winui

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import kotlin.time.TimeSource

internal class WinUIDirect3DRenderer(
    private val layer: WinUISkiaLayer,
    private val bridge: WinUIDirect3DRenderBridge,
    private val panelPointer: WinUINativePointer,
) : AutoCloseable {
    private val bufferCount = 2
    private var device: WinUINativePointer = WinUINullPointer
    private var shared: WinUISharedDirect3D? = null
    private var context: DirectContext? = null
    private var interop: Interop? = null
    private var surfaces = arrayOfNulls<Surface>(bufferCount)
    private var renderTargets = arrayOfNulls<BackendRenderTarget>(bufferCount)
    private var isSwapChainInitialized = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var isDisposed = false
    private val renderClockStart = TimeSource.Monotonic.markNow()
    private val frameStats = if (WinUIFrameStats.isEnabled) WinUIFrameStats("layer@${layer.hashCode()}") else null

    fun render(
        width: Int,
        height: Int,
        contentScale: Float,
        contentScaleX: Float,
        contentScaleY: Float,
        throttledToVsync: Boolean,
    ): WinUIPlatformRenderResult {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        if (panelPointer == WinUINullPointer) {
            return WinUIPlatformRenderResult(
                width = width,
                height = height,
                contentScale = contentScale,
                throttledToVsync = throttledToVsync,
                swapChainCreated = false,
                swapChainResized = false,
                bufferIndex = -1,
            )
        }
        ensureDevice()
        var swapChainCreated = false
        var swapChainResized = false
        if (isSwapChainInitialized) {
            if (width != lastWidth || height != lastHeight) {
                disposeSurfaces()
                finishGpuWork()
                bridge.releaseBufferResources(device)
                bridge.resizeBuffers(device, width, height)
                createSurfaces(width, height)
                swapChainResized = true
            }
        } else {
            initializeSwapChain(width, height)
            swapChainCreated = true
        }
        bridge.setSwapChainTransform(
            device = device,
            contentScaleX = contentScaleX,
            contentScaleY = contentScaleY,
        )
        lastWidth = width
        lastHeight = height
        val nanoTime = renderClockStart.elapsedNow().inWholeNanoseconds
        var bufferIndex = -1
        layer.inDrawScope(
            width = width,
            height = height,
            contentScale = contentScale,
            nanoTime = nanoTime,
        ) {
            frameStats?.beginFrame()
            bufferIndex = drawAndPresent(nanoTime, throttledToVsync)
            frameStats?.endFrame(width, height)
        }
        shared?.afterFrame()
        return WinUIPlatformRenderResult(
            width = width,
            height = height,
            contentScale = contentScale,
            throttledToVsync = throttledToVsync,
            swapChainCreated = swapChainCreated,
            swapChainResized = swapChainResized,
            bufferIndex = bufferIndex,
        )
    }

    override fun close() {
        if (isDisposed) {
            return
        }
        resetDevice()
        isDisposed = true
    }

    private fun ensureDevice() {
        if (device != WinUINullPointer) {
            return
        }
        val (sharedDevice, nativeDevice) = WinUISharedDirect3D.acquire(bridge, panelPointer) {
            val adapter = bridge.chooseAdapter(adapterPriority = 0)
            if (adapter == WinUINullPointer) {
                throw WinUIRenderException(bridge.failureMessage("Failed to choose DirectX12 adapter for WinUI SwapChainPanel."))
            }
            val created = bridge.createDirectXDeviceForSwapChainPanel(adapter, panelPointer)
            if (created == WinUINullPointer) {
                throw WinUIRenderException(bridge.failureMessage("Failed to create DirectX12 device for WinUI SwapChainPanel."))
            }
            created
        }
        shared = sharedDevice
        device = nativeDevice
    }

    private fun createContext() {
        if (context != null) {
            return
        }
        context = shared?.context()
    }

    private fun initializeSwapChain(width: Int, height: Int) {
        bridge.initSwapChain(device, width, height)
        createContext()
        createSurfaces(width, height)
        bridge.initFence(device)
        isSwapChainInitialized = true
        interop = context?.let { context ->
            Interop(
                directContext = context,
                devicePtr = bridge.getDevicePtr(device),
                queuePtr = bridge.getQueuePtr(device),
            )
        }
    }

    /** The GPU objects of the current device, once the first frame created them. */
    val direct3DInterop: WinUIDirect3DInterop?
        get() = interop

    private class Interop(
        override val directContext: DirectContext,
        override val devicePtr: Long,
        override val queuePtr: Long,
    ) : WinUIDirect3DInterop {
        override var isValid: Boolean = true
    }

    private fun createSurfaces(width: Int, height: Int) {
        val context = context ?: return
        try {
            for (index in 0 until bufferCount) {
                val renderTarget = winuiMakeDirect3DRenderTarget(
                    width = width,
                    height = height,
                    texturePtr = bridge.getBufferResourcePtr(device, index),
                    format = DXGI_FORMAT_R8G8B8A8_UNORM,
                    sampleCnt = 1,
                    levelCnt = 1,
                )
                renderTargets[index] = renderTarget
                surfaces[index] = Surface.makeFromBackendRenderTarget(
                    context = context,
                    rt = renderTarget,
                    origin = SurfaceOrigin.TOP_LEFT,
                    colorFormat = SurfaceColorFormat.RGBA_8888,
                    colorSpace = ColorSpace.sRGB,
                )
            }
        } catch (throwable: Throwable) {
            disposeSurfaces()
            bridge.releaseBufferResources(device)
            throw throwable
        }
    }

    private fun drawAndPresent(nanoTime: Long, throttledToVsync: Boolean): Int {
        val context = context ?: return -1
        val bufferIndex = bridge.getBufferIndex(device)
        frameStats?.mark(WinUIFrameStats.Phase.Wait)
        val surface = surfaces[bufferIndex] ?: return -1
        val canvas = surface.canvas
        canvas.clear(0x00000000)
        layer.renderInto(canvas, nanoTime)
        frameStats?.mark(WinUIFrameStats.Phase.Draw)
        // No CPU sync: rendering runs on the UI thread, which must not wait for the GPU. The fence
        // that present() signals keeps the CPU from reusing a back buffer the GPU still uses
        // (getBufferIndex waits for it), so the CPU runs at most a swap chain ahead.
        context.flushAndSubmit(surface, syncCpu = false)
        frameStats?.mark(WinUIFrameStats.Phase.Submit)
        bridge.present(device, throttledToVsync)
        frameStats?.mark(WinUIFrameStats.Phase.Present)
        return bufferIndex
    }

    /**
     * Waits until the GPU has run the submitted work. Frames are submitted without a CPU sync, so
     * Skia releases the back buffers of closed surfaces only once the GPU is done with them, and
     * ResizeBuffers fails while any reference to a back buffer is left.
     */
    private fun finishGpuWork() {
        val context = context ?: return
        context.flush()
        context.submit(syncCpu = true)
    }

    private fun disposeSurfaces() {
        for (index in 0 until bufferCount) {
            val surface = surfaces[index]
            val renderTarget = renderTargets[index]
            surfaces[index] = null
            renderTargets[index] = null
            surface?.close()
            renderTarget?.close()
        }
    }

    private fun resetDevice() {
        if (device == WinUINullPointer) {
            isSwapChainInitialized = false
            lastWidth = 0
            lastHeight = 0
            return
        }
        disposeSurfaces()
        finishGpuWork()
        bridge.releaseBufferResources(device)
        interop?.isValid = false
        interop = null
        // The context belongs to the device that the layers of this thread share: the last layer
        // closes it.
        context = null
        shared?.release(device)
        shared = null
        bridge.disposeDevice(device)
        device = WinUINullPointer
        isSwapChainInitialized = false
        lastWidth = 0
        lastHeight = 0
    }

    private companion object {
        private const val DXGI_FORMAT_R8G8B8A8_UNORM = 28
    }
}

private fun WinUIDirect3DRenderBridge.failureMessage(fallback: String): String {
    val lastError = getLastErrorMessage()
    return if (lastError.isBlank()) fallback else "$fallback $lastError"
}
