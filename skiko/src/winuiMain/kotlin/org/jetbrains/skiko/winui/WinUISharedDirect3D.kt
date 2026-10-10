package org.jetbrains.skiko.winui

import microsoft.ui.dispatching.DispatcherQueue
import org.jetbrains.skia.DirectContext
import kotlin.time.Duration.Companion.seconds

/** The id of the calling thread. */
internal expect fun winuiCurrentThreadId(): Long

/**
 * The Direct3D device, queue and Skia context that the layers of one UI thread share.
 *
 * Every layer (a window, and each popup or dialog in it) used to create a Direct3D 12 device, a queue
 * and a Skia context of its own, each with its own driver allocations, descriptor heaps, glyph
 * atlases and resource cache. The layers of a thread render one after another on that thread, so
 * they can use one device and one context; each keeps its own swap chain, buffers and fence (the
 * native device a layer gets is its own, sharing the Direct3D objects of the others).
 *
 * The context also frees the GPU resources that no frame used for a while: Skia keeps the textures
 * of images, glyph atlases and scratch targets in its resource cache until its budget of 256 MB is
 * reached, so after scrolling through images most of that video memory would stay allocated.
 */
internal class WinUISharedDirect3D private constructor(
    private val bridge: WinUIDirect3DRenderBridge,
    private val threadId: Long,
) {
    /** The native devices of the layers that use this device, in creation order. */
    private val members = ArrayList<WinUINativePointer>()
    private var context: DirectContext? = null
    private var cleanupTimer: WinUIDispatcherTimer? = null
    private var isCleanupTimerRunning = false
    private var hasFramesSinceCleanup = false

    /** The Skia context of the device, created on first use. */
    fun context(): DirectContext =
        context ?: run {
            val source = members.first()
            winuiMakeDirect3DContext(
                adapterPtr = bridge.getAdapterPtr(source),
                devicePtr = bridge.getDevicePtr(source),
                queuePtr = bridge.getQueuePtr(source),
            ).also { context = it }
        }

    /**
     * Ends the use of [device] by its layer, which disposes [device] itself afterwards. The last
     * layer closes the context.
     */
    fun release(device: WinUINativePointer) {
        val isLast = winuiSynchronized(lock) {
            members.remove(device)
            if (members.isEmpty()) {
                byThread.remove(threadId)
                true
            } else {
                false
            }
        }
        if (isLast) {
            cleanupTimer?.close()
            cleanupTimer = null
            context?.close()
            context = null
        }
    }

    /**
     * Called after each frame of a layer. A timer frees the cached resources that no frame used
     * for [ResourceIdleTime]; it stops once a period passes without frames, after a last cleanup,
     * so an idle window does not wake the thread.
     */
    fun afterFrame() {
        if (!isCleanupEnabled || context == null) {
            return
        }
        hasFramesSinceCleanup = true
        val timer = cleanupTimer ?: WinUIDispatcherTimer(
            dispatcherQueue = DispatcherQueue.getForCurrentThread(),
            interval = ResourceIdleTime,
            repeating = true,
            onTick = ::cleanUp,
        ).also { cleanupTimer = it }
        if (!isCleanupTimerRunning) {
            isCleanupTimerRunning = true
            timer.start()
        }
    }

    private fun cleanUp() {
        val context = context ?: return
        val before = if (WinUIFrameStats.isEnabled) winuiDescribeGpuMemory(context) else null
        winuiPerformDeferredCleanup(context, ResourceIdleTime.inWholeMilliseconds)
        if (before != null) {
            println("skiko-winui gpu memory: $before -> ${winuiDescribeGpuMemory(context)}")
        }
        if (!hasFramesSinceCleanup) {
            cleanupTimer?.stop()
            isCleanupTimerRunning = false
        }
        hasFramesSinceCleanup = false
    }

    companion object {
        private val lock = WinUILock()
        private val byThread = HashMap<Long, WinUISharedDirect3D>()

        private val ResourceIdleTime = 10.seconds

        // SKIKO_WINUI_GPU_CLEANUP=0 keeps unused resources until the cache budget is reached.
        private val isCleanupEnabled: Boolean by lazy {
            winuiEnvironmentVariable("SKIKO_WINUI_GPU_CLEANUP") != "0"
        }

        // SKIKO_WINUI_SHARE_DEVICE=0 gives every layer a device of its own again (for comparisons).
        private val isSharingEnabled: Boolean by lazy {
            winuiEnvironmentVariable("SKIKO_WINUI_SHARE_DEVICE") != "0"
        }

        /**
         * A native device for [panelPointer]: on the Direct3D device of the calling thread when it
         * has one, else on a new device of the preferred adapter.
         */
        fun acquire(
            bridge: WinUIDirect3DRenderBridge,
            panelPointer: WinUINativePointer,
            createDevice: () -> WinUINativePointer,
        ): Pair<WinUISharedDirect3D, WinUINativePointer> {
            val threadId = winuiCurrentThreadId()
            val existing = winuiSynchronized(lock) { byThread[threadId]?.takeIf { it.members.isNotEmpty() } }
                ?.takeIf { isSharingEnabled }
            if (existing != null) {
                val device = bridge.createDirectXDeviceSharing(existing.members.first(), panelPointer)
                if (device != WinUINullPointer) {
                    winuiSynchronized(lock) { existing.members += device }
                    return existing to device
                }
            }
            val device = createDevice()
            val shared = WinUISharedDirect3D(bridge, threadId)
            shared.members += device
            // A device that could not be shared stays on its own; the shared one remains registered.
            winuiSynchronized(lock) {
                if (byThread[threadId]?.members.isNullOrEmpty()) {
                    byThread[threadId] = shared
                }
            }
            return shared to device
        }
    }
}
