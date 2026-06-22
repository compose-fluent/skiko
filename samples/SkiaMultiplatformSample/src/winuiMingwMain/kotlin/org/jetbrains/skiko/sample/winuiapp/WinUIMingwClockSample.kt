package org.jetbrains.skiko.sample.winuiapp

import io.github.composefluent.winrt.runtime.RuntimeScope
import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun runWinUIRuntime(block: () -> Unit) {
    println("skia-mpp-winui-mingw: runtime scope begin")
    RuntimeScope.initializeSingleThreaded().use {
        block()
    }
}

actual fun isWinUISampleAutoExitRequested(): Boolean =
    getenv("SKIKO_WINUI_SAMPLE_AUTO_EXIT")?.toKString() == "true"

actual fun winUISampleDispatcherRepro(): String? =
    getenv("SKIKO_WINUI_SAMPLE_DISPATCHER_REPRO")?.toKString()
