package org.jetbrains.skiko.winui

internal actual fun winuiEnvironmentVariable(name: String): String? = System.getenv(name)
