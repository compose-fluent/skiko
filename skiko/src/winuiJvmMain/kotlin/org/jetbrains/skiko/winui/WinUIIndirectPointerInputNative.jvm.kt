package org.jetbrains.skiko.winui

internal actual fun createWinUIIndirectPointerInputNativeBinding(
    windowPointer: Long,
    callback: WinUIIndirectPointerInputNativeCallback,
): WinUIIndirectPointerInputNativeBinding =
    WinUIIndirectPointerInputNativeBindingJvm(windowPointer, callback)

internal fun emitWinUIIndirectPointerInputNativeSmoke(
    callback: WinUIIndirectPointerInputNativeCallback,
): Int {
    WinUINativeLibrary.ensureLoaded()
    return WinUIIndirectPointerInputJni.emitSmoke(
        WinUIIndirectPointerInputNativeCallbackPeer(callback),
    )
}

private class WinUIIndirectPointerInputNativeBindingJvm(
    windowPointer: Long,
    callback: WinUIIndirectPointerInputNativeCallback,
) : WinUIIndirectPointerInputNativeBinding {
    private var handle: Long

    override val unavailableReason: WinUIIndirectPointerInputUnavailableReason?

    init {
        WinUINativeLibrary.ensureLoaded()
        val reason = IntArray(1)
        handle = WinUIIndirectPointerInputJni.create(
            windowPointer = windowPointer,
            callback = WinUIIndirectPointerInputNativeCallbackPeer(callback),
            unavailableReason = reason,
        )
        unavailableReason = reason[0].toUnavailableReason()
    }

    override val isActive: Boolean
        get() = handle != 0L && WinUIIndirectPointerInputJni.isActive(handle)

    override fun cancel() {
        val currentHandle = handle
        if (currentHandle != 0L && !WinUIIndirectPointerInputJni.cancel(currentHandle)) {
            throw IllegalStateException(
                "WinUI indirect pointer input must be cancelled on its owner thread " +
                    "outside an input callback.",
            )
        }
    }

    override fun close() {
        val currentHandle = handle
        if (currentHandle == 0L) {
            return
        }
        if (!WinUIIndirectPointerInputJni.close(currentHandle)) {
            throw IllegalStateException(
                "WinUI indirect pointer input must be closed on its owner thread " +
                    "outside an input callback.",
            )
        }
        handle = 0L
    }
}

internal class WinUIIndirectPointerInputNativeCallbackPeer(
    private val callback: WinUIIndirectPointerInputNativeCallback,
) {
    @Suppress("LongParameterList")
    fun onNativeEvent(
        type: Int,
        pointerIds: LongArray,
        timestampsMillis: LongArray,
        x: FloatArray,
        y: FloatArray,
        pressed: BooleanArray,
        pressure: FloatArray,
        previousTimestampsMillis: LongArray,
        previousX: FloatArray,
        previousY: FloatArray,
        previousPressed: BooleanArray,
        primaryDirectionalMotionAxis: Int,
        deviceId: Long,
        hasDeviceRect: Boolean,
        deviceRectLeft: Int,
        deviceRectTop: Int,
        deviceRectRight: Int,
        deviceRectBottom: Int,
        frameId: Long,
    ): Boolean {
        val changeCount = pointerIds.size
        require(
            timestampsMillis.size == changeCount &&
                x.size == changeCount &&
                y.size == changeCount &&
                pressed.size == changeCount &&
                pressure.size == changeCount &&
                previousTimestampsMillis.size == changeCount &&
                previousX.size == changeCount &&
                previousY.size == changeCount &&
                previousPressed.size == changeCount
        ) { "WinUI indirect pointer callback arrays have inconsistent sizes." }

        val changes = List(changeCount) { index ->
            WinUIIndirectPointerChange(
                pointerId = pointerIds[index],
                timestampMillis = timestampsMillis[index],
                x = x[index],
                y = y[index],
                pressed = pressed[index],
                pressure = pressure[index],
                previousTimestampMillis = previousTimestampsMillis[index],
                previousX = previousX[index],
                previousY = previousY[index],
                previousPressed = previousPressed[index],
            )
        }
        return callback.onEvent(
            WinUIIndirectPointerEvent(
                type = WinUIIndirectPointerEventType.entries[type],
                changes = changes,
                primaryDirectionalMotionAxis =
                    WinUIIndirectPointerPrimaryDirectionalMotionAxis.entries[
                        primaryDirectionalMotionAxis
                    ],
                deviceId = deviceId,
                deviceRect = if (hasDeviceRect) {
                    WinUIIndirectPointerDeviceRect(
                        left = deviceRectLeft,
                        top = deviceRectTop,
                        right = deviceRectRight,
                        bottom = deviceRectBottom,
                    )
                } else {
                    null
                },
                frameId = frameId,
            ),
        )
    }

    fun onNativeCancel() {
        callback.onCancel()
    }
}

private object WinUIIndirectPointerInputJni {
    external fun create(
        windowPointer: Long,
        callback: WinUIIndirectPointerInputNativeCallbackPeer,
        unavailableReason: IntArray,
    ): Long

    external fun cancel(handle: Long): Boolean
    external fun close(handle: Long): Boolean
    external fun isActive(handle: Long): Boolean
    external fun emitSmoke(callback: WinUIIndirectPointerInputNativeCallbackPeer): Int
}

private fun Int.toUnavailableReason(): WinUIIndirectPointerInputUnavailableReason? = when (this) {
    0 -> null
    1 -> WinUIIndirectPointerInputUnavailableReason.API_NOT_PRESENT
    2 -> WinUIIndirectPointerInputUnavailableReason.HWND_UNAVAILABLE
    3 -> WinUIIndirectPointerInputUnavailableReason.ALREADY_BOUND
    4 -> WinUIIndirectPointerInputUnavailableReason.SUBCLASS_FAILED
    5 -> WinUIIndirectPointerInputUnavailableReason.REGISTRATION_FAILED
    else -> error("Unknown WinUI indirect pointer unavailability reason: $this")
}
