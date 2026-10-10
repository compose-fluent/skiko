package org.jetbrains.skiko.winui

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import platform.windows.GetCurrentThreadId
import platform.windows.SwitchToThread

@OptIn(ExperimentalAtomicApi::class)
internal actual class WinUILock {
    internal val ownerThreadId = AtomicInt(0)
    internal val recursionDepth = AtomicInt(0)
}

@OptIn(ExperimentalAtomicApi::class)
internal actual inline fun <T> winuiSynchronized(lock: WinUILock, block: () -> T): T {
    val currentThreadId = GetCurrentThreadId().toInt()
    if (lock.ownerThreadId.load() == currentThreadId) {
        lock.recursionDepth.addAndFetch(1)
        return try {
            block()
        } finally {
            release(lock)
        }
    }
    while (!lock.ownerThreadId.compareAndSet(0, currentThreadId)) {
        SwitchToThread()
    }
    lock.recursionDepth.store(1)
    try {
        return block()
    } finally {
        release(lock)
    }
}

@OptIn(ExperimentalAtomicApi::class)
@PublishedApi
internal fun release(lock: WinUILock) {
    if (lock.recursionDepth.addAndFetch(-1) == 0) {
        lock.ownerThreadId.store(0)
    }
}
