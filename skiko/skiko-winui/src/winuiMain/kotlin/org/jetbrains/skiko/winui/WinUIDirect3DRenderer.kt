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
    private var context: DirectContext? = null
    private var surfaces = arrayOfNulls<Surface>(bufferCount)
    private var renderTargets = arrayOfNulls<BackendRenderTarget>(bufferCount)
    private var isSwapChainInitialized = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var isDisposed = false
    private val renderClockStart = TimeSource.Monotonic.markNow()

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
                context?.flush()
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
            layer.update(nanoTime)
            bufferIndex = drawAndPresent(throttledToVsync)
        }
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
        val adapter = bridge.chooseAdapter(adapterPriority = 0)
        if (adapter == WinUINullPointer) {
            throw WinUIRenderException(bridge.failureMessage("Failed to choose DirectX12 adapter for WinUI SwapChainPanel."))
        }
        device = bridge.createDirectXDeviceForSwapChainPanel(adapter, panelPointer)
        if (device == WinUINullPointer) {
            throw WinUIRenderException(bridge.failureMessage("Failed to create DirectX12 device for WinUI SwapChainPanel."))
        }
    }

    private fun createContext() {
        if (context != null) {
            return
        }
        context = DirectContext.makeDirect3D(
            adapterPtr = bridge.getAdapterPtr(device),
            devicePtr = bridge.getDevicePtr(device),
            queuePtr = bridge.getQueuePtr(device),
        )
    }

    private fun initializeSwapChain(width: Int, height: Int) {
        bridge.initSwapChain(device, width, height)
        createContext()
        createSurfaces(width, height)
        bridge.initFence(device)
        isSwapChainInitialized = true
    }

    private fun createSurfaces(width: Int, height: Int) {
        val context = context ?: return
        try {
            for (index in 0 until bufferCount) {
                val renderTarget = BackendRenderTarget.makeDirect3D(
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

    private fun drawAndPresent(throttledToVsync: Boolean): Int {
        val context = context ?: return -1
        val bufferIndex = bridge.getBufferIndex(device)
        val surface = surfaces[bufferIndex] ?: return -1
        val canvas = surface.canvas
        canvas.clear(0x00000000)
        layer.draw(canvas)
        context.flushAndSubmit(surface, syncCpu = true)
        bridge.present(device, throttledToVsync)
        return bufferIndex
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
        context?.flush()
        disposeSurfaces()
        bridge.releaseBufferResources(device)
        context?.close()
        context = null
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
