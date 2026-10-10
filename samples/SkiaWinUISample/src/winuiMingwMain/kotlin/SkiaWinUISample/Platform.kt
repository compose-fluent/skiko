package SkiaWinUISample

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun sampleRuntimeDescription(): String = "Kotlin/Native mingwX64"

@OptIn(ExperimentalForeignApi::class)
actual fun isSampleAutoExitRequested(): Boolean =
    getenv("SKIKO_WINUI_SAMPLE_AUTO_EXIT")?.toKString() == "true"
