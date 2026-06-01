package org.jetbrains.skiko.winui

internal actual inline fun <T> winuiSynchronized(lock: Any, block: () -> T): T =
    block()
