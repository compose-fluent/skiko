package org.jetbrains.skiko.winui

import org.jetbrains.skia.impl.Library
import java.nio.file.Files

internal object WinUISkiaLayerNative {
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

    @JvmStatic
    external fun chooseAdapter(adapterPriority: Int): Long

    @JvmStatic
    external fun createDirectXDeviceForSwapChainPanel(adapter: Long, panelPointer: Long): Long

    @JvmStatic
    external fun getAdapterPtr(device: Long): Long

    @JvmStatic
    external fun getDevicePtr(device: Long): Long

    @JvmStatic
    external fun getQueuePtr(device: Long): Long

    @JvmStatic
    external fun initSwapChain(device: Long, width: Int, height: Int)

    @JvmStatic
    external fun initFence(device: Long)

    @JvmStatic
    external fun getBufferResourcePtr(device: Long, index: Int): Long

    @JvmStatic
    external fun getBufferIndex(device: Long): Int

    @JvmStatic
    external fun present(device: Long, isVsyncEnabled: Boolean)

    @JvmStatic
    external fun resizeBuffers(device: Long, width: Int, height: Int)

    @JvmStatic
    external fun disposeDevice(device: Long)

    @JvmStatic
    external fun getAdapterName(adapter: Long): String

    @JvmStatic
    external fun getAdapterMemorySize(adapter: Long): Long

    @JvmStatic
    external fun throwRenderExceptionForSmoke(message: String)
}
