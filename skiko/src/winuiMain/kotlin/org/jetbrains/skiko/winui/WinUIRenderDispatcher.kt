package org.jetbrains.skiko.winui

import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.dispatching.DispatcherQueueHandler

/**
 * Coalesces render requests of a layer and runs them on its UI thread.
 *
 * A request throttled to vsync renders on the next frame of the XAML compositor
 * ([WinUICompositorFrameTicker]), so content that asks for a frame on every frame (animations,
 * scrolling) renders at the refresh rate of the display and in step with it. An unthrottled request
 * (a resize, the first frame) renders as soon as the dispatcher queue runs it. Without requests the
 * ticker is disarmed and nothing renders.
 */
internal class WinUIRenderDispatcher(
    private val dispatcherQueue: DispatcherQueue,
    private val isDisposed: () -> Boolean,
    private val renderNow: (throttledToVsync: Boolean) -> Unit,
) : AutoCloseable {
    private val lock = WinUILock()
    private var pendingRender = false
    private var pendingRenderThrottledToVsync = true
    private var isImmediateRenderEnqueued = false
    private var isClosed = false

    // Only touched on the UI thread.
    private var idleFrames = 0

    private val frameTicker = WinUICompositorFrameTicker(dispatcherQueue, ::onCompositorFrame)

    fun needRender(throttledToVsync: Boolean) {
        checkOpen()
        scheduleRender(throttledToVsync)
    }

    fun scheduleRender(throttledToVsync: Boolean) {
        checkOpen()
        val enqueueImmediate = winuiSynchronized(lock) {
            pendingRender = true
            pendingRenderThrottledToVsync = pendingRenderThrottledToVsync && throttledToVsync
            if (!throttledToVsync && !isImmediateRenderEnqueued) {
                isImmediateRenderEnqueued = true
                true
            } else {
                false
            }
        }
        if (enqueueImmediate) {
            enqueueImmediateRender()
        } else if (throttledToVsync) {
            frameTicker.arm()
        }
    }

    private fun enqueueImmediateRender() {
        val enqueued = dispatcherQueue.tryEnqueue(DispatcherQueueHandler {
            winuiSynchronized(lock) { isImmediateRenderEnqueued = false }
            renderPending()
        })
        if (!enqueued) {
            winuiSynchronized(lock) { isImmediateRenderEnqueued = false }
        }
    }

    private fun onCompositorFrame() {
        if (renderPending()) {
            idleFrames = 0
        } else if (++idleFrames >= IdleFramesBeforeDisarm) {
            idleFrames = 0
            frameTicker.disarm()
            // A request that raced with the disarm re-arms the ticker.
            if (winuiSynchronized(lock) { pendingRender && !isClosed }) {
                frameTicker.arm()
            }
        }
    }

    /** Renders the pending request, if any; `true` when it rendered. */
    private fun renderPending(): Boolean {
        val throttledToVsync = winuiSynchronized(lock) {
            if (isClosed || !pendingRender) {
                return false
            }
            val throttled = pendingRenderThrottledToVsync
            pendingRender = false
            pendingRenderThrottledToVsync = true
            throttled
        }
        if (isDisposed()) {
            return false
        }
        renderNow(throttledToVsync)
        return true
    }

    override fun close() {
        winuiSynchronized(lock) {
            isClosed = true
            pendingRender = false
            pendingRenderThrottledToVsync = true
            isImmediateRenderEnqueued = false
        }
        frameTicker.close()
    }

    private fun checkOpen() {
        check(!isClosedOrDisposed()) { "WinUISkiaLayer is disposed" }
    }

    private fun isClosedOrDisposed(): Boolean =
        winuiSynchronized(lock) {
            isClosed
        } || isDisposed()

    private companion object {
        // Frames without a request before the ticker unsubscribes: keeps it subscribed through the
        // short gaps of an animation that requests its next frame late.
        const val IdleFramesBeforeDisarm = 2
    }
}
