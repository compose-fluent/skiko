package org.jetbrains.skiko.winui

import org.jetbrains.skia.ExternalSymbolName
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.cstr

@OptIn(ExperimentalForeignApi::class)
internal actual val WinUINullPointer: WinUINativePointer = 0L

@OptIn(ExperimentalForeignApi::class)
internal object WinUISkiaLayerNative : WinUIDirect3DRenderBridge {
    override fun chooseAdapter(adapterPriority: Int): WinUINativePointer =
        winuiChooseAdapter(adapterPriority)

    override fun createDirectXDeviceForSwapChainPanel(
        adapter: WinUINativePointer,
        panelPointer: WinUINativePointer,
    ): WinUINativePointer =
        winuiCreateDirectXDeviceForSwapChainPanel(adapter, panelPointer)

    override fun getAdapterPtr(device: WinUINativePointer): WinUINativePointer =
        winuiGetAdapterPtr(device)

    override fun getDevicePtr(device: WinUINativePointer): WinUINativePointer =
        winuiGetDevicePtr(device)

    override fun getQueuePtr(device: WinUINativePointer): WinUINativePointer =
        winuiGetQueuePtr(device)

    override fun initSwapChain(device: WinUINativePointer, width: Int, height: Int) {
        requireNativeSuccess(
            operation = "skiko_winui_initSwapChain",
            success = winuiInitSwapChain(device, width, height),
        )
    }

    override fun initFence(device: WinUINativePointer) {
        requireNativeSuccess(
            operation = "skiko_winui_initFence",
            success = winuiInitFence(device),
        )
    }

    override fun getBufferResourcePtr(device: WinUINativePointer, index: Int): WinUINativePointer =
        winuiGetBufferResourcePtr(device, index).also { pointer ->
            if (pointer == WinUINullPointer) {
                throw nativeFailure("skiko_winui_getBufferResourcePtr")
            }
        }

    override fun releaseBufferResources(device: WinUINativePointer) {
        winuiReleaseBufferResources(device)
    }

    override fun getBufferIndex(device: WinUINativePointer): Int =
        winuiGetBufferIndex(device)

    override fun present(device: WinUINativePointer, isVsyncEnabled: Boolean) {
        requireNativeSuccess(
            operation = "skiko_winui_present",
            success = winuiPresent(device, isVsyncEnabled),
        )
    }

    override fun resizeBuffers(device: WinUINativePointer, width: Int, height: Int) {
        requireNativeSuccess(
            operation = "skiko_winui_resizeBuffers",
            success = winuiResizeBuffers(device, width, height),
        )
    }

    override fun setSwapChainTransform(
        device: WinUINativePointer,
        contentScaleX: Float,
        contentScaleY: Float,
    ) {
        requireNativeSuccess(
            operation = "skiko_winui_setSwapChainTransform",
            success = winuiSetSwapChainTransform(device, contentScaleX, contentScaleY),
        )
    }

    override fun disposeDevice(device: WinUINativePointer) {
        winuiDisposeDevice(device)
    }

    override fun getAdapterName(adapter: WinUINativePointer): String {
        return readNativeString { buffer, bufferSize ->
            winuiGetAdapterName(adapter, buffer, bufferSize)
        }
    }

    override fun getAdapterMemorySize(adapter: WinUINativePointer): Long =
        winuiGetAdapterMemorySize(adapter)

    override fun getLastErrorMessage(): String =
        readNativeString(::winuiGetLastErrorMessage)

    override fun throwRenderExceptionForSmoke(message: String) {
        memScoped {
            winuiThrowRenderExceptionForSmoke(message.cstr.ptr)
        }
        throw WinUIRenderException("skiko_winui_throwRenderExceptionForSmoke: $message")
    }

    private fun requireNativeSuccess(operation: String, success: Boolean) {
        if (!success) {
            throw nativeFailure(operation)
        }
    }

    private fun nativeFailure(operation: String): WinUIRenderException {
        val lastError = getLastErrorMessage()
        val message = when {
            lastError.isBlank() -> "$operation failed."
            lastError.startsWith("$operation failed") -> lastError
            else -> "$operation failed. $lastError"
        }
        return WinUIRenderException(message)
    }

    private fun readNativeString(read: (CPointer<ByteVar>?, Int) -> Int): String {
        val requiredSize = read(null, 0)
        if (requiredSize <= 0) {
            return ""
        }
        return memScoped {
            val buffer = allocArray<ByteVar>(requiredSize + 1)
            read(buffer, requiredSize + 1)
            buffer.toKString()
        }
    }
}

@ExternalSymbolName("skiko_winui_chooseAdapter")
private external fun winuiChooseAdapter(adapterPriority: Int): WinUINativePointer

@ExternalSymbolName("skiko_winui_createDirectXDeviceForSwapChainPanel")
private external fun winuiCreateDirectXDeviceForSwapChainPanel(
    adapter: WinUINativePointer,
    panelPointer: WinUINativePointer,
): WinUINativePointer

@ExternalSymbolName("skiko_winui_getAdapterPtr")
private external fun winuiGetAdapterPtr(device: WinUINativePointer): WinUINativePointer

@ExternalSymbolName("skiko_winui_getDevicePtr")
private external fun winuiGetDevicePtr(device: WinUINativePointer): WinUINativePointer

@ExternalSymbolName("skiko_winui_getQueuePtr")
private external fun winuiGetQueuePtr(device: WinUINativePointer): WinUINativePointer

@ExternalSymbolName("skiko_winui_initSwapChain")
private external fun winuiInitSwapChain(device: WinUINativePointer, width: Int, height: Int): Boolean

@ExternalSymbolName("skiko_winui_initFence")
private external fun winuiInitFence(device: WinUINativePointer): Boolean

@ExternalSymbolName("skiko_winui_getBufferResourcePtr")
private external fun winuiGetBufferResourcePtr(device: WinUINativePointer, index: Int): WinUINativePointer

@ExternalSymbolName("skiko_winui_releaseBufferResources")
private external fun winuiReleaseBufferResources(device: WinUINativePointer)

@ExternalSymbolName("skiko_winui_getBufferIndex")
private external fun winuiGetBufferIndex(device: WinUINativePointer): Int

@ExternalSymbolName("skiko_winui_present")
private external fun winuiPresent(device: WinUINativePointer, isVsyncEnabled: Boolean): Boolean

@ExternalSymbolName("skiko_winui_resizeBuffers")
private external fun winuiResizeBuffers(device: WinUINativePointer, width: Int, height: Int): Boolean

@ExternalSymbolName("skiko_winui_setSwapChainTransform")
private external fun winuiSetSwapChainTransform(
    device: WinUINativePointer,
    contentScaleX: Float,
    contentScaleY: Float,
): Boolean

@ExternalSymbolName("skiko_winui_disposeDevice")
private external fun winuiDisposeDevice(device: WinUINativePointer)

@ExternalSymbolName("skiko_winui_getAdapterName")
private external fun winuiGetAdapterName(
    adapter: WinUINativePointer,
    buffer: CPointer<ByteVar>?,
    bufferSize: Int,
): Int

@ExternalSymbolName("skiko_winui_getAdapterMemorySize")
private external fun winuiGetAdapterMemorySize(adapter: WinUINativePointer): Long

@ExternalSymbolName("skiko_winui_getLastErrorMessage")
private external fun winuiGetLastErrorMessage(buffer: CPointer<ByteVar>?, bufferSize: Int): Int

@ExternalSymbolName("skiko_winui_throwRenderExceptionForSmoke")
private external fun winuiThrowRenderExceptionForSmoke(message: CPointer<ByteVar>)
