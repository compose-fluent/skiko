package org.jetbrains.skiko.winui

import io.github.composefluent.winrt.runtime.ComObjectReference
import io.github.composefluent.winrt.runtime.Guid
import microsoft.ui.xaml.controls.SwapChainPanel
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

internal actual class WinUISkiaLayerPlatformInterop actual constructor(
    private val layer: WinUISkiaLayer,
    panel: SwapChainPanel,
) : AutoCloseable {
    private val bufferCount = 2
    private val panelNativeReference: ComObjectReference
    private val panelPointer: Long
    private var device: Long = 0L
    private var context: DirectContext? = null
    private var surfaces = arrayOfNulls<Surface>(bufferCount)
    private var renderTargets = arrayOfNulls<BackendRenderTarget>(bufferCount)
    private var isSwapChainInitialized = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var isDisposed = false

    init {
        WinUISkiaLayerNative.ensureLoaded()
        panelNativeReference = panel.nativeObject.queryInterface(SWAP_CHAIN_PANEL_NATIVE_IID).getOrElse { cause ->
            throw WinUIRenderException(
                "Failed to query ISwapChainPanelNative from WinUI SwapChainPanel.",
                cause,
            )
        }
        panelPointer = panelNativeReference.pointer.value
    }

    actual fun render(
        width: Int,
        height: Int,
        contentScale: Float,
        throttledToVsync: Boolean,
    ): WinUIPlatformRenderResult {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        if (panelPointer == 0L) {
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
                WinUISkiaLayerNative.resizeBuffers(device, width, height)
                createSurfaces(width, height)
                swapChainResized = true
            }
        } else {
            WinUISkiaLayerNative.initSwapChain(device, width, height)
            createContext()
            createSurfaces(width, height)
            WinUISkiaLayerNative.initFence(device)
            isSwapChainInitialized = true
            swapChainCreated = true
        }
        lastWidth = width
        lastHeight = height
        val nanoTime = System.nanoTime()
        var bufferIndex = -1
        layer.inDrawScope(
            width = width,
            height = height,
            contentScale = contentScale,
            nanoTime = nanoTime,
        ) {
            layer.update(nanoTime)
            bufferIndex = drawAndPresent(width, height, contentScale, throttledToVsync)
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

    actual override fun close() {
        if (isDisposed) {
            return
        }
        if (device != 0L) {
            disposeSurfaces()
            context?.close()
            context = null
            WinUISkiaLayerNative.disposeDevice(device)
            device = 0L
        }
        panelNativeReference.close()
        isSwapChainInitialized = false
        lastWidth = 0
        lastHeight = 0
        isDisposed = true
    }

    private fun ensureDevice() {
        if (device != 0L) {
            return
        }
        val adapter = WinUISkiaLayerNative.chooseAdapter(adapterPriority = 0)
        if (adapter == 0L) {
            throw WinUIRenderException("Failed to choose DirectX12 adapter for WinUI SwapChainPanel.")
        }
        device = WinUISkiaLayerNative.createDirectXDeviceForSwapChainPanel(adapter, panelPointer)
        if (device == 0L) {
            throw WinUIRenderException("Failed to create DirectX12 device for WinUI SwapChainPanel.")
        }
    }

    private fun createContext() {
        if (context != null) {
            return
        }
        context = DirectContext.makeDirect3D(
            adapterPtr = WinUISkiaLayerNative.getAdapterPtr(device),
            devicePtr = WinUISkiaLayerNative.getDevicePtr(device),
            queuePtr = WinUISkiaLayerNative.getQueuePtr(device),
        )
    }

    private fun createSurfaces(width: Int, height: Int) {
        val context = context ?: return
        try {
            for (index in 0 until bufferCount) {
                val renderTarget = BackendRenderTarget.makeDirect3D(
                    width = width,
                    height = height,
                    texturePtr = WinUISkiaLayerNative.getBufferResourcePtr(device, index),
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
            throw throwable
        }
    }

    private fun drawAndPresent(
        width: Int,
        height: Int,
        contentScale: Float,
        throttledToVsync: Boolean,
    ): Int {
        val context = context ?: return -1
        val bufferIndex = WinUISkiaLayerNative.getBufferIndex(device)
        val surface = surfaces[bufferIndex] ?: return -1
        val canvas = surface.canvas
        canvas.clear(0x00000000)
        layer.draw(canvas)
        context.flushAndSubmit(surface, syncCpu = true)
        WinUISkiaLayerNative.present(device, throttledToVsync)
        return bufferIndex
    }

    private fun disposeSurfaces() {
        for (index in 0 until bufferCount) {
            surfaces[index]?.close()
            surfaces[index] = null
            renderTargets[index]?.close()
            renderTargets[index] = null
        }
    }

    private companion object {
        private const val DXGI_FORMAT_R8G8B8A8_UNORM = 28
        private val SWAP_CHAIN_PANEL_NATIVE_IID = Guid("63AAD0B8-7C24-40FF-85A8-640D944CC325")
    }
}
