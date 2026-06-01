package org.jetbrains.skiko.sample

import io.github.composefluent.winrt.runtime.RuntimeScope
import io.github.composefluent.winrt.runtime.WinRtWindowsAppSdkBootstrap
import microsoft.ui.xaml.Application

fun main() {
    println("skia-mpp-winui: bootstrap begin")
    WinRtWindowsAppSdkBootstrap.initialize().use {
        println("skia-mpp-winui: runtime scope begin")
        RuntimeScope.initializeSingleThreaded().use {
            println("skia-mpp-winui: application start")
            Application.start {
                println("skia-mpp-winui: application callback")
                val application = Application.current ?: Application()
                launchWinUISample(
                    application = application,
                    autoExit = System.getProperty("skiko.winui.sample.autoExit") == "true",
                    dispatcherRepro = System.getProperty("skiko.winui.sample.dispatcherRepro"),
                )
            }
            println("skia-mpp-winui: application returned")
        }
    }
    println("skia-mpp-winui: done")
}
