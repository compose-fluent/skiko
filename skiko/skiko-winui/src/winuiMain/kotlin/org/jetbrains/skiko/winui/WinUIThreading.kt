package org.jetbrains.skiko.winui

internal expect inline fun <T> winuiSynchronized(lock: Any, block: () -> T): T
