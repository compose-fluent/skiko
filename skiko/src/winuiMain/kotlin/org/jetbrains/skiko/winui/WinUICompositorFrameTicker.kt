package org.jetbrains.skiko.winui

import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.dispatching.DispatcherQueueHandler
import microsoft.ui.xaml.media.CompositionTarget
import windows.foundation.EventHandler
import windows.foundation.EventRegistrationToken

/**
 * Calls [onFrame] on the UI thread once per frame of the XAML compositor while armed, that is in
 * step with the refresh rate of the display (`CompositionTarget.Rendering`).
 *
 * A DispatcherQueueTimer cannot pace frames: its period is rounded to the system timer resolution
 * (15.6 ms by default), so it caps rendering near 64 Hz on any display and drifts against the
 * vertical blank. Arm the ticker only while there is something to render: a subscribed Rendering
 * handler keeps the compositor producing frames.
 *
 * [arm] and [disarm] may be called from any thread; the subscription itself is changed on the
 * thread of [dispatcherQueue].
 */
internal class WinUICompositorFrameTicker(
    private val dispatcherQueue: DispatcherQueue,
    private val onFrame: () -> Unit,
) : AutoCloseable {
    private val lock = WinUILock()
    private var wantsArmed = false
    private var isUpdateEnqueued = false
    private var isClosed = false

    // Only touched on the UI thread.
    private var token: EventRegistrationToken? = null
    private val handler = EventHandler<Any?> { _, _ ->
        if (token != null) {
            onFrame()
        }
    }

    val isArmed: Boolean
        get() = winuiSynchronized(lock) { wantsArmed && !isClosed }

    fun arm() = setArmed(true)

    fun disarm() = setArmed(false)

    override fun close() {
        winuiSynchronized(lock) {
            isClosed = true
            wantsArmed = false
        }
        updateSubscription()
    }

    private fun setArmed(armed: Boolean) {
        val changed = winuiSynchronized(lock) {
            if (isClosed || wantsArmed == armed) return
            wantsArmed = armed
            true
        }
        if (changed) {
            updateSubscription()
        }
    }

    private fun updateSubscription() {
        if (dispatcherQueue.hasThreadAccess) {
            applySubscription()
            return
        }
        val shouldEnqueue = winuiSynchronized(lock) {
            if (isUpdateEnqueued) false else {
                isUpdateEnqueued = true
                true
            }
        }
        if (shouldEnqueue) {
            val enqueued = dispatcherQueue.tryEnqueue(DispatcherQueueHandler {
                winuiSynchronized(lock) { isUpdateEnqueued = false }
                applySubscription()
            })
            if (!enqueued) {
                winuiSynchronized(lock) { isUpdateEnqueued = false }
            }
        }
    }

    private fun applySubscription() {
        val armed = winuiSynchronized(lock) { wantsArmed && !isClosed }
        val current = token
        if (armed && current == null) {
            token = CompositionTarget.rendering.add(handler)
        } else if (!armed && current != null) {
            token = null
            runCatching { CompositionTarget.rendering.remove(current) }
        }
    }
}
