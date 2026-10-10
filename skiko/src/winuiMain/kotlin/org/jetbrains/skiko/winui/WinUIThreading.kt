package org.jetbrains.skiko.winui

internal expect class WinUILock()

internal expect inline fun <T> winuiSynchronized(lock: WinUILock, block: () -> T): T
