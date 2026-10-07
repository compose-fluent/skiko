package org.jetbrains.skiko.winui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun winuiEnvironmentVariable(name: String): String? = getenv(name)?.toKString()
