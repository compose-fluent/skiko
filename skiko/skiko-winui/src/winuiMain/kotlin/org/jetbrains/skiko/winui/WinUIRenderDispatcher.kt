package org.jetbrains.skiko.winui

import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.dispatching.DispatcherQueueHandler

internal class WinUIRenderDispatcher(
    private val dispatcherQueue: DispatcherQueue,
    private val isDisposed: () -> Boolean,
    private val renderNow: (throttledToVsync: Boolean) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var isRendering = false
    private var pendingRender = false
    private var pendingRenderThrottledToVsync = true
    private var isPendingRenderEnqueued = false
    private var isClosed = false

    fun needRender(throttledToVsync: Boolean) {
        checkOpen()
        if (!dispatcherQueue.hasThreadAccess) {
            scheduleRender(throttledToVsync)
            return
        }
        var shouldEnqueue = false
        var shouldRenderNow = true
        winuiSynchronized(lock) {
            if (isRendering) {
                shouldEnqueue = scheduleRenderLocked(throttledToVsync)
                shouldRenderNow = false
            } else {
                pendingRender = false
                pendingRenderThrottledToVsync = true
            }
        }
        if (shouldEnqueue) {
            enqueuePendingRender()
        }
        if (shouldRenderNow) {
            render(throttledToVsync)
        }
    }

    fun scheduleRender(throttledToVsync: Boolean) {
        checkOpen()
        val shouldEnqueue = winuiSynchronized(lock) {
            scheduleRenderLocked(throttledToVsync)
        }
        if (shouldEnqueue) {
            enqueuePendingRender()
        }
    }

    private fun scheduleRenderLocked(throttledToVsync: Boolean): Boolean {
        pendingRender = true
        pendingRenderThrottledToVsync = pendingRenderThrottledToVsync && throttledToVsync
        if (isPendingRenderEnqueued) {
            return false
        }
        isPendingRenderEnqueued = true
        return true
    }

    private fun enqueuePendingRender() {
        val enqueued = dispatcherQueue.tryEnqueue(DispatcherQueueHandler {
            val pendingThrottledToVsync = winuiSynchronized(lock) {
                isPendingRenderEnqueued = false
                if (isClosed || isDisposed() || !pendingRender) {
                    return@DispatcherQueueHandler
                }
                val pendingThrottledToVsync = pendingRenderThrottledToVsync
                pendingRender = false
                pendingRenderThrottledToVsync = true
                pendingThrottledToVsync
            }
            if (isClosedOrDisposed()) {
                return@DispatcherQueueHandler
            }
            needRender(throttledToVsync = pendingThrottledToVsync)
        })
        if (!enqueued) {
            winuiSynchronized(lock) {
                isPendingRenderEnqueued = false
            }
        }
    }

    private fun render(throttledToVsync: Boolean) {
        winuiSynchronized(lock) {
            isRendering = true
        }
        try {
            renderNow(throttledToVsync)
        } finally {
            winuiSynchronized(lock) {
                isRendering = false
            }
        }
    }

    override fun close() {
        winuiSynchronized(lock) {
            isClosed = true
            pendingRender = false
            pendingRenderThrottledToVsync = true
            isPendingRenderEnqueued = false
        }
    }

    private fun checkOpen() {
        check(!isClosedOrDisposed()) { "WinUISkiaLayer is disposed" }
    }

    private fun isClosedOrDisposed(): Boolean =
        winuiSynchronized(lock) {
            isClosed
        } || isDisposed()
}
