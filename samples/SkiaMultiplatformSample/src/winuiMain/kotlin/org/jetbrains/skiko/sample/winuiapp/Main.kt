package org.jetbrains.skiko.sample.winuiapp

import microsoft.ui.xaml.Application
import org.jetbrains.skiko.sample.launchWinUISample

fun main() {
    println("skia-mpp-winui: bootstrap begin")
    runWinUIRuntime {
        println("skia-mpp-winui: application start")
        Application.start {
            println("skia-mpp-winui: application callback")
            val application = Application.current ?: Application()
            launchWinUISample(
                application = application,
                autoExit = isWinUISampleAutoExitRequested(),
                dispatcherRepro = winUISampleDispatcherRepro(),
            )
        }
        println("skia-mpp-winui: application returned")
    }
    println("skia-mpp-winui: done")
}

expect fun runWinUIRuntime(block: () -> Unit)

expect fun isWinUISampleAutoExitRequested(): Boolean

expect fun winUISampleDispatcherRepro(): String?
