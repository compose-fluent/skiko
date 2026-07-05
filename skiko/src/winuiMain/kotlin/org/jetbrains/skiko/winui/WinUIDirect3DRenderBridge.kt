package org.jetbrains.skiko.winui

internal expect val WinUINullPointer: WinUINativePointer

internal interface WinUIDirect3DRenderBridge {
    fun chooseAdapter(adapterPriority: Int): WinUINativePointer
    fun createDirectXDeviceForSwapChainPanel(
        adapter: WinUINativePointer,
        panelPointer: WinUINativePointer,
    ): WinUINativePointer
    fun getAdapterPtr(device: WinUINativePointer): WinUINativePointer
    fun getDevicePtr(device: WinUINativePointer): WinUINativePointer
    fun getQueuePtr(device: WinUINativePointer): WinUINativePointer
    fun initSwapChain(device: WinUINativePointer, width: Int, height: Int)
    fun initFence(device: WinUINativePointer)
    fun getBufferResourcePtr(device: WinUINativePointer, index: Int): WinUINativePointer
    fun releaseBufferResources(device: WinUINativePointer)
    fun getBufferIndex(device: WinUINativePointer): Int
    fun present(device: WinUINativePointer, isVsyncEnabled: Boolean)
    fun resizeBuffers(device: WinUINativePointer, width: Int, height: Int)
    fun setSwapChainTransform(device: WinUINativePointer, contentScaleX: Float, contentScaleY: Float)
    fun disposeDevice(device: WinUINativePointer)
    fun getAdapterName(adapter: WinUINativePointer): String
    fun getAdapterMemorySize(adapter: WinUINativePointer): Long
    fun getLastErrorMessage(): String
    fun throwRenderExceptionForSmoke(message: String)
}
