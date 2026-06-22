package org.jetbrains.skiko.sample.winuiapp

import io.github.composefluent.winrt.runtime.RuntimeScope
import io.github.composefluent.winrt.runtime.WinRtWindowsAppSdkBootstrap

actual fun runWinUIRuntime(block: () -> Unit) {
    WinRtWindowsAppSdkBootstrap.initialize().use {
        println("skia-mpp-winui: runtime scope begin")
        RuntimeScope.initializeSingleThreaded().use {
            block()
        }
    }
}

actual fun isWinUISampleAutoExitRequested(): Boolean =
    System.getProperty("skiko.winui.sample.autoExit") == "true"

actual fun winUISampleDispatcherRepro(): String? =
    System.getProperty("skiko.winui.sample.dispatcherRepro")
