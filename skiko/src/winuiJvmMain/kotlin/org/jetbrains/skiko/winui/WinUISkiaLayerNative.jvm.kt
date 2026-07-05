package org.jetbrains.skiko.winui

import org.jetbrains.skia.impl.Library
import java.nio.file.Files

internal actual val WinUINullPointer: WinUINativePointer = 0L

internal object WinUISkiaLayerNative : WinUIDirect3DRenderBridge {
    private var isLoaded = false

    @Synchronized
    fun ensureLoaded() {
        if (isLoaded) {
            return
        }
        Library.staticLoad()
        val explicitPath = System.getProperty("skiko.winui.native.library.path")
        if (explicitPath != null) {
            System.load(explicitPath)
        } else if (!loadFromResource()) {
            System.loadLibrary(NATIVE_LIBRARY_NAME)
        }
        isLoaded = true
    }

    private fun loadFromResource(): Boolean {
        val resource = WinUISkiaLayerNative::class.java.getResourceAsStream(NATIVE_LIBRARY_RESOURCE)
            ?: return false
        resource.use { input ->
            val directory = Files.createTempDirectory(NATIVE_LIBRARY_NAME)
            val library = directory.resolve(NATIVE_LIBRARY_FILE)
            Files.copy(input, library)
            library.toFile().deleteOnExit()
            directory.toFile().deleteOnExit()
            System.load(library.toAbsolutePath().toString())
            return true
        }
    }

    private const val NATIVE_LIBRARY_NAME = "skiko-winui"
    private const val NATIVE_LIBRARY_FILE = "$NATIVE_LIBRARY_NAME.dll"
    private const val NATIVE_LIBRARY_RESOURCE =
        "/org/jetbrains/skiko/winui/native/windows-x64/$NATIVE_LIBRARY_FILE"

    override external fun chooseAdapter(adapterPriority: Int): WinUINativePointer

    override external fun createDirectXDeviceForSwapChainPanel(
        adapter: WinUINativePointer,
        panelPointer: WinUINativePointer,
    ): WinUINativePointer

    override external fun getAdapterPtr(device: WinUINativePointer): WinUINativePointer

    override external fun getDevicePtr(device: WinUINativePointer): WinUINativePointer

    override external fun getQueuePtr(device: WinUINativePointer): WinUINativePointer

    override external fun initSwapChain(device: WinUINativePointer, width: Int, height: Int)

    override external fun initFence(device: WinUINativePointer)

    override external fun getBufferResourcePtr(device: WinUINativePointer, index: Int): WinUINativePointer

    override external fun releaseBufferResources(device: WinUINativePointer)

    override external fun getBufferIndex(device: WinUINativePointer): Int

    override external fun present(device: WinUINativePointer, isVsyncEnabled: Boolean)

    override external fun resizeBuffers(device: WinUINativePointer, width: Int, height: Int)

    override external fun setSwapChainTransform(
        device: WinUINativePointer,
        contentScaleX: Float,
        contentScaleY: Float,
    )

    override external fun disposeDevice(device: WinUINativePointer)

    override external fun getAdapterName(adapter: WinUINativePointer): String

    override external fun getAdapterMemorySize(adapter: WinUINativePointer): Long

    override external fun getLastErrorMessage(): String

    override external fun throwRenderExceptionForSmoke(message: String)
}
