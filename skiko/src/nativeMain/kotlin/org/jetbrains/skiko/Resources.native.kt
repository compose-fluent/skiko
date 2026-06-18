package org.jetbrains.skiko

actual suspend fun loadBytesFromPath(path: String): ByteArray = loadBytesFromNativePath(path)

internal expect fun loadBytesFromNativePath(path: String): ByteArray
