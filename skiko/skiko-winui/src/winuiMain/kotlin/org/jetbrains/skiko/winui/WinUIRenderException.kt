package org.jetbrains.skiko.winui

class WinUIRenderException : RuntimeException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable?) : super(message, cause)
}
