package org.jetbrains.skiko.winui

import kotlinx.cinterop.*
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_AXIS_NONE
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_AXIS_X
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_AXIS_Y
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_CONSUMED
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_UNCONSUMED
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_MOVE
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_PRESS
import org.jetbrains.skiko.winui.internal.indirect.SKIKO_WINUI_INDIRECT_POINTER_RELEASE
import org.jetbrains.skiko.winui.internal.indirect.SkikoWinUIIndirectPointerChangeView
import org.jetbrains.skiko.winui.internal.indirect.SkikoWinUIIndirectPointerEventView
import org.jetbrains.skiko.winui.internal.indirect.skiko_winui_indirect_pointer_cancel
import org.jetbrains.skiko.winui.internal.indirect.skiko_winui_indirect_pointer_close
import org.jetbrains.skiko.winui.internal.indirect.skiko_winui_indirect_pointer_create
import org.jetbrains.skiko.winui.internal.indirect.skiko_winui_indirect_pointer_emit_smoke
import org.jetbrains.skiko.winui.internal.indirect.skiko_winui_indirect_pointer_is_active
import kotlin.native.internal.NativePtr

@OptIn(ExperimentalForeignApi::class)
internal actual fun createWinUIIndirectPointerInputNativeBinding(
    windowPointer: Long,
    callback: WinUIIndirectPointerInputNativeCallback,
): WinUIIndirectPointerInputNativeBinding {
    val callbackContext = StableRef.create(CallbackContext(callback))
    val creation = memScoped {
        val unavailableReason = alloc<IntVar>()
        unavailableReason.value = 0
        val binding = skiko_winui_indirect_pointer_create(
            window_inspectable = interpretCPointer<ByteVar>(NativePtr.NULL + windowPointer),
            context = callbackContext.asCPointer(),
            event_callback = staticCFunction(::onNativeIndirectPointerEvent),
            cancel_callback = staticCFunction(::onNativeIndirectPointerCancel),
            unavailable_reason = unavailableReason.ptr,
        )
        NativeCreation(binding, unavailableReason.value.toUnavailableReason())
    }
    if (creation.binding == null) {
        callbackContext.dispose()
        return WinUIIndirectPointerInputNativeBindingMingw(
            binding = null,
            callbackContext = null,
            unavailableReason = creation.unavailableReason,
        )
    }
    return WinUIIndirectPointerInputNativeBindingMingw(
        binding = creation.binding,
        callbackContext = callbackContext,
        unavailableReason = creation.unavailableReason,
    )
}

@OptIn(ExperimentalForeignApi::class)
internal fun emitWinUIIndirectPointerInputNativeSmoke(
    callback: WinUIIndirectPointerInputNativeCallback,
): Int {
    val callbackContext = StableRef.create(CallbackContext(callback))
    return try {
        skiko_winui_indirect_pointer_emit_smoke(
            context = callbackContext.asCPointer(),
            event_callback = staticCFunction(::onNativeIndirectPointerEvent),
            cancel_callback = staticCFunction(::onNativeIndirectPointerCancel),
        )
    } finally {
        callbackContext.dispose()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class WinUIIndirectPointerInputNativeBindingMingw(
    private var binding: COpaquePointer?,
    private var callbackContext: StableRef<CallbackContext>?,
    override val unavailableReason: WinUIIndirectPointerInputUnavailableReason?,
) : WinUIIndirectPointerInputNativeBinding {
    override val isActive: Boolean
        get() = binding?.let(::skiko_winui_indirect_pointer_is_active) ?: false

    override fun cancel() {
        val currentBinding = binding
        if (currentBinding != null &&
            !skiko_winui_indirect_pointer_cancel(currentBinding)) {
            throw IllegalStateException(
                "WinUI indirect pointer input must be cancelled on its owner thread " +
                    "outside an input callback.",
            )
        }
    }

    override fun close() {
        val currentBinding = binding ?: return
        if (!skiko_winui_indirect_pointer_close(currentBinding)) {
            throw IllegalStateException(
                "WinUI indirect pointer input must be closed on its owner thread " +
                    "outside an input callback.",
            )
        }
        binding = null
        callbackContext?.dispose()
        callbackContext = null
    }
}

private class CallbackContext(
    val callback: WinUIIndirectPointerInputNativeCallback,
)

@OptIn(ExperimentalForeignApi::class)
private data class NativeCreation(
    val binding: COpaquePointer?,
    val unavailableReason: WinUIIndirectPointerInputUnavailableReason?,
)

@OptIn(ExperimentalForeignApi::class)
private fun onNativeIndirectPointerEvent(
    context: COpaquePointer?,
    event: CPointer<SkikoWinUIIndirectPointerEventView>?,
): Int {
    val callback = context?.asStableRef<CallbackContext>()?.get()?.callback
        ?: return SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED
    return try {
        if (callback.onEvent(requireNotNull(event).toKotlinEvent())) {
            SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_CONSUMED
        } else {
            SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_UNCONSUMED
        }
    } catch (_: Throwable) {
        SKIKO_WINUI_INDIRECT_POINTER_CALLBACK_FAILED
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun onNativeIndirectPointerCancel(context: COpaquePointer?) {
    val callback = context?.asStableRef<CallbackContext>()?.get()?.callback ?: return
    try {
        callback.onCancel()
    } catch (_: Throwable) {
        // Kotlin exceptions must not cross the C callback boundary.
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<SkikoWinUIIndirectPointerEventView>.toKotlinEvent():
    WinUIIndirectPointerEvent = pointed.readValue().useContents {
    val changePointer = changes
    val changeCount = change_count.toInt()
    require(changeCount == 0 || changePointer != null) {
        "WinUI indirect pointer event has changes without a change buffer."
    }
    WinUIIndirectPointerEvent(
        type = type.toEventType(),
        changes = List(changeCount) { index ->
            requireNotNull(changePointer).readChange(index)
        },
        primaryDirectionalMotionAxis =
            primary_directional_motion_axis.toPrimaryDirectionalMotionAxis(),
        deviceId = device_id.toLong(),
        deviceRect = if (has_device_rect != 0.toUByte()) {
            WinUIIndirectPointerDeviceRect(
                left = device_rect_left,
                top = device_rect_top,
                right = device_rect_right,
                bottom = device_rect_bottom,
            )
        } else {
            null
        },
        frameId = frame_id.toLong(),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<SkikoWinUIIndirectPointerChangeView>.readChange(
    index: Int,
): WinUIIndirectPointerChange = this[index].readValue().useContents {
    WinUIIndirectPointerChange(
        pointerId = pointer_id.toLong(),
        timestampMillis = timestamp_millis,
        x = x,
        y = y,
        pressed = pressed != 0.toUByte(),
        pressure = pressure,
        previousTimestampMillis = previous_timestamp_millis,
        previousX = previous_x,
        previousY = previous_y,
        previousPressed = previous_pressed != 0.toUByte(),
    )
}

private fun Int.toEventType(): WinUIIndirectPointerEventType = when (toUInt()) {
    SKIKO_WINUI_INDIRECT_POINTER_PRESS -> WinUIIndirectPointerEventType.PRESS
    SKIKO_WINUI_INDIRECT_POINTER_MOVE -> WinUIIndirectPointerEventType.MOVE
    SKIKO_WINUI_INDIRECT_POINTER_RELEASE -> WinUIIndirectPointerEventType.RELEASE
    else -> error("Unknown WinUI indirect pointer event type: $this")
}

private fun Int.toPrimaryDirectionalMotionAxis():
    WinUIIndirectPointerPrimaryDirectionalMotionAxis = when (toUInt()) {
    SKIKO_WINUI_INDIRECT_POINTER_AXIS_NONE ->
        WinUIIndirectPointerPrimaryDirectionalMotionAxis.NONE
    SKIKO_WINUI_INDIRECT_POINTER_AXIS_X ->
        WinUIIndirectPointerPrimaryDirectionalMotionAxis.X
    SKIKO_WINUI_INDIRECT_POINTER_AXIS_Y ->
        WinUIIndirectPointerPrimaryDirectionalMotionAxis.Y
    else -> error("Unknown WinUI indirect pointer primary axis: $this")
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
