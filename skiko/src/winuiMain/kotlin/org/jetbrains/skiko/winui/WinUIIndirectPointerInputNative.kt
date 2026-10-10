package org.jetbrains.skiko.winui

internal interface WinUIIndirectPointerInputNativeBinding : AutoCloseable {
    val isActive: Boolean
    val unavailableReason: WinUIIndirectPointerInputUnavailableReason?

    fun cancel()
}

internal interface WinUIIndirectPointerInputNativeCallback {
    fun onEvent(event: WinUIIndirectPointerEvent): Boolean
    fun onCancel()
}

internal expect fun createWinUIIndirectPointerInputNativeBinding(
    windowPointer: Long,
    callback: WinUIIndirectPointerInputNativeCallback,
): WinUIIndirectPointerInputNativeBinding
