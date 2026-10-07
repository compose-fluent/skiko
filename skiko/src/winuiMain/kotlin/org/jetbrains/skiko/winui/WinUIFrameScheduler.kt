package org.jetbrains.skiko.winui

import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.dispatching.DispatcherQueueTimer
import windows.foundation.EventRegistrationToken
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class WinUIDispatcherTimer(
    dispatcherQueue: DispatcherQueue = DispatcherQueue.getForCurrentThread(),
    private val interval: Duration = 16.milliseconds,
    private val repeating: Boolean = true,
    private val onTick: () -> Unit,
) : AutoCloseable {
    private val timer: DispatcherQueueTimer = dispatcherQueue.createTimer()
    private var tickToken: EventRegistrationToken? = null
    private var isClosed = false

    val isRunning: Boolean
        get() = timer.isRunning

    fun start() {
        check(!isClosed) { "WinUIDispatcherTimer is closed" }
        if (tickToken == null) {
            tickToken = timer.tick.add { _, _ ->
                if (!isClosed) {
                    onTick()
                }
            }
        }
        timer.interval = interval
        timer.isRepeating = repeating
        timer.start()
    }

    fun stop() {
        if (!isClosed) {
            timer.stop()
        }
    }

    override fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        timer.stop()
        tickToken?.let(timer.tick::remove)
        tickToken = null
    }
}

/**
 * Requests a render of [layer] on every frame of the XAML compositor while running, at the refresh
 * rate of the display. [interval] is ignored: frames follow the compositor (see
 * [WinUICompositorFrameTicker] for why a timer cannot pace them).
 */
class WinUIFrameScheduler(
    private val layer: WinUISkiaLayerSurface,
    @Suppress("UNUSED_PARAMETER") interval: Duration = 16.milliseconds,
    dispatcherQueue: DispatcherQueue = DispatcherQueue.getForCurrentThread(),
    private val throttledToVsync: Boolean = true,
) : AutoCloseable {
    private val ticker = WinUICompositorFrameTicker(dispatcherQueue) {
        layer.needRender(throttledToVsync = throttledToVsync)
    }

    val isRunning: Boolean
        get() = ticker.isArmed

    fun start() {
        ticker.arm()
    }

    fun stop() {
        ticker.disarm()
    }

    override fun close() {
        ticker.close()
    }
}
