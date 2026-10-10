package org.jetbrains.skiko

import platform.posix.fclose
import platform.posix.fopen

private val resourcesPaths = listOf(
    "src/commonTest/resources",
    "skiko/src/commonTest/resources",
    "../../../../src/commonTest/resources",
)

actual fun resourcePath(resourceId: String): String {
    val candidates = resourcesPaths.map { "$it/$resourceId" }
    return candidates.firstOrNull(::isFile) ?: candidates.first()
}

private fun isFile(path: String): Boolean =
    fopen(path, "rb")?.let {
        fclose(it)
        true
    } ?: false
