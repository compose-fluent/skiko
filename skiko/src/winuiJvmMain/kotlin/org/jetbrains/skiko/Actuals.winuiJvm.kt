package org.jetbrains.skiko

actual fun setSystemLookAndFeel() = Unit

actual val currentSystemTheme: SystemTheme
    get() = SystemTheme.UNKNOWN
