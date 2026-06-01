package org.jetbrains.skiko.winui

import io.github.composefluent.winrt.runtime.EventRegistrationToken
import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.dispatching.DispatcherQueueTimer
import windows.foundation.TypedEventHandler
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
            tickToken = timer.tick.add(TypedEventHandler { _, _ ->
                if (!isClosed) {
                    onTick()
                }
            })
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

class WinUIFrameScheduler(
    private val layer: WinUISkiaLayerSurface,
    interval: Duration = 16.milliseconds,
    dispatcherQueue: DispatcherQueue = DispatcherQueue.getForCurrentThread(),
    private val throttledToVsync: Boolean = true,
) : AutoCloseable {
    private val timer = WinUIDispatcherTimer(
        dispatcherQueue = dispatcherQueue,
        interval = interval,
        repeating = true,
    ) {
        layer.needRender(throttledToVsync = throttledToVsync)
    }

    val isRunning: Boolean
        get() = timer.isRunning

    fun start() {
        timer.start()
    }

    fun stop() {
        timer.stop()
    }

    override fun close() {
        timer.close()
    }
}
