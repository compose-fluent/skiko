package org.jetbrains.skiko.winui

internal actual class WinUILock {
    internal val monitor = Any()
}

internal actual inline fun <T> winuiSynchronized(lock: WinUILock, block: () -> T): T =
    synchronized(lock.monitor, block)
