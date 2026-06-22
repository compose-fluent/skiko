package org.jetbrains.skiko.sample

import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.dispatching.DispatcherQueueHandler
import microsoft.ui.xaml.Application
import microsoft.ui.xaml.HorizontalAlignment
import microsoft.ui.xaml.VerticalAlignment
import microsoft.ui.xaml.Window
import microsoft.ui.xaml.media.MicaBackdrop
import org.jetbrains.skiko.winui.WinUIDispatcherTimer
import org.jetbrains.skiko.winui.WinUISkiaLayer
import org.jetbrains.skiko.winui.WinUISkiaLayerRenderDelegate
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private var activeWinUISampleSession: WinUISampleSession? = null

fun launchWinUISample(
    application: Application,
    autoExit: Boolean = false,
    dispatcherRepro: String? = null,
) {
    println("skia-mpp-winui: launch sample")
    activeWinUISampleSession?.close()
    activeWinUISampleSession = WinUISampleSession(application, autoExit, dispatcherRepro).also { session ->
        session.launch()
    }
}

private class WinUISampleSession(
    private val application: Application,
    private val autoExit: Boolean,
    private val dispatcherRepro: String?,
) : AutoCloseable {
    private val layer = WinUISkiaLayer()
    private val window = Window()
    private val dispatcherQueue = DispatcherQueue.getForCurrentThread()
    private var renderTimer: WinUIDispatcherTimer? = null
    private var timeoutTimer: WinUIDispatcherTimer? = null
    private var isClosed = false

    fun launch() {
        println("skia-mpp-winui: create layer")
        val scene = WinUIClockScene(layer::renderApi)
        layer.renderDelegate = WinUISkiaLayerRenderDelegate(layer, scene)
        layer.inputHandler = scene

        println("skia-mpp-winui: configure component")
        layer.component.horizontalAlignment = HorizontalAlignment.Stretch
        layer.component.verticalAlignment = VerticalAlignment.Stretch

        println("skia-mpp-winui: create window")
        window.title = "Skiko WinUI multiplatform sample"
        window.systemBackdrop = MicaBackdrop()
        layer.attachTo(window)

        println("skia-mpp-winui: activate window")
        window.activate()
        println("skia-mpp-winui: render first frame")
        layer.needRender(throttledToVsync = false)
        layer.requestFocus()

        if (autoExit && dispatcherRepro == "timer") {
            println("skia-mpp-winui: dispatcher timer auto exit")
            startRenderTimer(repeating = false) {
                println("skia-mpp-winui: timer handler entered")
                closeResources(closeWindow = true)
                println("skia-mpp-winui: application exit")
                application.exit()
                println("skia-mpp-winui: application exit requested")
            }
            startAutoExitTimeout("timer")
        } else if (autoExit && dispatcherRepro == "handler") {
            println("skia-mpp-winui: dispatcher handler auto exit")
            dispatcherQueue.tryEnqueue(DispatcherQueueHandler {
                println("skia-mpp-winui: dispatcher handler entered")
                closeResources(closeWindow = true)
                println("skia-mpp-winui: application exit")
                application.exit()
                println("skia-mpp-winui: application exit requested")
            })
        } else if (autoExit) {
            println("skia-mpp-winui: synchronous auto exit")
            closeResources(closeWindow = true)
            println("skia-mpp-winui: application exit")
            application.exit()
            println("skia-mpp-winui: application exit requested")
        } else {
            println("skia-mpp-winui: start dispatcher render timer")
            val scheduler = layer.startFrameScheduler()
            println("skia-mpp-winui: frame scheduler started running=${scheduler.isRunning}")
        }
    }

    override fun close() {
        closeResources(closeWindow = true)
    }

    private fun closeResources(closeWindow: Boolean) {
        if (isClosed) {
            println("skia-mpp-winui: close skipped")
            return
        }
        println("skia-mpp-winui: close resources")
        isClosed = true
        renderTimer?.close()
        renderTimer = null
        timeoutTimer?.close()
        timeoutTimer = null
        println("skia-mpp-winui: close layer")
        layer.close()
        activeWinUISampleSession = null
        if (closeWindow) {
            println("skia-mpp-winui: close window")
            window.close()
        }
        println("skia-mpp-winui: close resources done")
    }

    private fun startRenderTimer(
        repeating: Boolean,
        onTick: () -> Unit,
    ) {
        val timer = WinUIDispatcherTimer(
            interval = 16.milliseconds,
            repeating = repeating,
        ) {
            if (!isClosed) {
                onTick()
            }
        }
        renderTimer = timer
        timer.start()
        println("skia-mpp-winui: timer started running=${timer.isRunning}")
    }

    private fun startAutoExitTimeout(name: String) {
        timeoutTimer = WinUIDispatcherTimer(
            interval = 5_000.milliseconds,
            repeating = false,
        ) {
            if (!isClosed) {
                println("skia-mpp-winui: $name auto exit timeout")
                exitProcess(2)
            }
        }.also { timer ->
            timer.start()
        }
    }
}
