package org.jetbrains.skiko.winui

interface WinUIInputHandler {
    fun onPointerEvent(event: WinUIPointerEvent): Boolean = false
    fun onIndirectPointerEvent(event: WinUIIndirectPointerEvent): Boolean = false
    fun onIndirectPointerCancel() {}
    fun onKeyEvent(event: WinUIKeyEvent): Boolean = false
    fun onTextInputEvent(event: WinUITextInputEvent): Boolean = false
    fun onTextCompositionEvent(event: WinUITextCompositionEvent): Boolean = false
    fun onFocusEvent(event: WinUIFocusEvent): Boolean = false
}

enum class WinUIIndirectPointerEventType {
    PRESS,
    MOVE,
    RELEASE,
}

enum class WinUIIndirectPointerPrimaryDirectionalMotionAxis {
    NONE,
    X,
    Y,
}

data class WinUIIndirectPointerDeviceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class WinUIIndirectPointerChange(
    val pointerId: Long,
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
    val pressed: Boolean,
    val pressure: Float,
    val previousTimestampMillis: Long,
    val previousX: Float,
    val previousY: Float,
    val previousPressed: Boolean,
)

data class WinUIIndirectPointerEvent(
    val type: WinUIIndirectPointerEventType,
    val changes: List<WinUIIndirectPointerChange>,
    val primaryDirectionalMotionAxis: WinUIIndirectPointerPrimaryDirectionalMotionAxis,
    val deviceId: Long,
    val deviceRect: WinUIIndirectPointerDeviceRect?,
    val frameId: Long,
)

enum class WinUIPointerEventType {
    ENTERED,
    PRESSED,
    MOVED,
    RELEASED,
    WHEEL,
    EXITED,
    CANCELED,
    CAPTURE_LOST,
}

enum class WinUIPointerDeviceType {
    TOUCH,
    PEN,
    MOUSE,
    TOUCHPAD,
    UNKNOWN,
}

enum class WinUIPointerButtonChange {
    NONE,
    LEFT_PRESSED,
    LEFT_RELEASED,
    RIGHT_PRESSED,
    RIGHT_RELEASED,
    MIDDLE_PRESSED,
    MIDDLE_RELEASED,
    X1_PRESSED,
    X1_RELEASED,
    X2_PRESSED,
    X2_RELEASED,
}

enum class WinUIFocusState {
    UNFOCUSED,
    POINTER,
    KEYBOARD,
    PROGRAMMATIC,
    UNKNOWN,
}

enum class WinUITextCompositionEventType {
    STARTED,
    UPDATED,
    COMMITTED,
    CANCELED,
}

data class WinUIKeyModifiers(
    val control: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val windows: Boolean = false,
)

data class WinUIPointerButtons(
    val left: Boolean = false,
    val right: Boolean = false,
    val middle: Boolean = false,
    val x1: Boolean = false,
    val x2: Boolean = false,
)

data class WinUIPointerContactRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class WinUIPointerEvent(
    val type: WinUIPointerEventType,
    val pointerId: Long,
    val frameId: Long,
    val x: Float,
    val y: Float,
    val deviceType: WinUIPointerDeviceType,
    val buttons: WinUIPointerButtons,
    val buttonChange: WinUIPointerButtonChange,
    val wheelDelta: Int,
    val isInContact: Boolean,
    val isInRange: Boolean,
    val isPrimary: Boolean,
    val isCanceled: Boolean,
    val isHorizontalMouseWheel: Boolean,
    val isBarrelButtonPressed: Boolean,
    val isEraser: Boolean,
    val isInverted: Boolean,
    val pressure: Float,
    val orientation: Float,
    val twist: Float,
    val xTilt: Float,
    val yTilt: Float,
    val touchConfidence: Boolean,
    val contactRect: WinUIPointerContactRect,
    val modifiers: WinUIKeyModifiers,
    val timestamp: Long,
)

data class WinUIKeyEvent(
    val pressed: Boolean,
    val keyCode: Int,
    val originalKeyCode: Int,
    val modifiers: WinUIKeyModifiers,
    val scanCode: Int,
    val repeatCount: Int,
    val isExtendedKey: Boolean,
    val wasKeyDown: Boolean,
    val isKeyReleased: Boolean,
    val deviceId: String,
)

data class WinUITextInputEvent(
    val character: Char,
    val codePoint: Int,
    val modifiers: WinUIKeyModifiers,
    val scanCode: Int,
    val repeatCount: Int,
    val isExtendedKey: Boolean,
    val isMenuKeyDown: Boolean,
    val wasKeyDown: Boolean,
    val isKeyReleased: Boolean,
)

data class WinUITextRange(
    val start: Int,
    val end: Int,
)

data class WinUIRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class WinUITextLayoutBounds(
    val textBounds: WinUIRect,
    val controlBounds: WinUIRect,
)

data class WinUITextCompositionEvent(
    val type: WinUITextCompositionEventType,
    val text: String,
    val selection: WinUITextRange,
    val replacementRange: WinUITextRange,
    val compositionRange: WinUITextRange,
    val modifiers: WinUIKeyModifiers,
    val inputLanguage: String? = null,
)

data class WinUIFocusEvent(
    val focused: Boolean,
    val focusState: WinUIFocusState,
)
