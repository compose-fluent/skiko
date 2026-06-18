package org.jetbrains.skiko.sample.winuiapp

import io.github.composefluent.winrt.runtime.RuntimeScope
import kotlinx.cinterop.toKString
import microsoft.ui.xaml.Application
import org.jetbrains.skiko.sample.launchWinUISample
import platform.posix.getenv

actual fun launchWinUIMingwClockSample() {
    println("skia-mpp-winui-mingw: runtime scope begin")
    RuntimeScope.initializeSingleThreaded().use {
        println("skia-mpp-winui-mingw: application start")
        Application.start {
            println("skia-mpp-winui-mingw: application callback")
            val application = Application.current ?: Application()
            launchWinUISample(
                application = application,
                autoExit = getenv("SKIKO_WINUI_SAMPLE_AUTO_EXIT")?.toKString() == "true",
                dispatcherRepro = getenv("SKIKO_WINUI_SAMPLE_DISPATCHER_REPRO")?.toKString(),
            )
        }
        println("skia-mpp-winui-mingw: application returned")
    }
    println("skia-mpp-winui-mingw: done")
}
