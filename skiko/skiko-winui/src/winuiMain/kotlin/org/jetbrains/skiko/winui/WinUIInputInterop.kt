package org.jetbrains.skiko.winui

import io.github.composefluent.winrt.runtime.EventRegistrationToken
import microsoft.ui.input.PointerDeviceType
import microsoft.ui.input.PointerPointProperties
import microsoft.ui.input.PointerUpdateKind
import microsoft.ui.xaml.FocusState
import microsoft.ui.xaml.RoutedEventHandler
import microsoft.ui.xaml.controls.SwapChainPanel
import microsoft.ui.xaml.input.CharacterReceivedRoutedEventArgs
import microsoft.ui.xaml.input.KeyEventHandler
import microsoft.ui.xaml.input.KeyRoutedEventArgs
import microsoft.ui.xaml.input.PointerEventHandler
import microsoft.ui.xaml.input.PointerRoutedEventArgs
import windows.foundation.TypedEventHandler
import windows.foundation.Rect
import windows.system.VirtualKey
import windows.system.VirtualKeyModifiers
import windows.ui.text.core.CoreTextCompositionCompletedEventArgs
import windows.ui.text.core.CoreTextCompositionStartedEventArgs
import windows.ui.text.core.CoreTextEditContext
import windows.ui.text.core.CoreTextInputPaneDisplayPolicy
import windows.ui.text.core.CoreTextInputScope
import windows.ui.text.core.CoreTextLayoutRequestedEventArgs
import windows.ui.text.core.CoreTextRange
import windows.ui.text.core.CoreTextSelectionRequestedEventArgs
import windows.ui.text.core.CoreTextSelectionUpdatingEventArgs
import windows.ui.text.core.CoreTextSelectionUpdatingResult
import windows.ui.text.core.CoreTextServicesManager
import windows.ui.text.core.CoreTextTextRequestedEventArgs
import windows.ui.text.core.CoreTextTextUpdatingEventArgs
import windows.ui.text.core.CoreTextTextUpdatingResult

internal class WinUIInputInterop(
    private val layer: WinUISkiaLayer,
    private val panel: SwapChainPanel,
) : AutoCloseable {
    private val eventTokens = mutableListOf<WinUIInputEventToken>()
    private val keyboardModifiers = WinUIKeyboardModifierState()
    private val textCompositionInterop = WinUITextCompositionInterop(
        layer = layer,
        panel = panel,
        modifiers = { keyboardModifiers.current },
    )
    private var isClosed = false

    init {
        subscribeEvents()
    }

    override fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        eventTokens.forEach { it.remove() }
        eventTokens.clear()
        textCompositionInterop.close()
    }

    fun updateTextInputState(
        text: String,
        selection: WinUITextRange,
        compositionRange: WinUITextRange,
    ) {
        textCompositionInterop.updateTextInputState(text, selection, compositionRange)
    }

    fun updateTextInputLayout(bounds: WinUITextLayoutBounds) {
        textCompositionInterop.updateTextInputLayout(bounds)
    }

    fun notifyTextInputLayoutChanged() {
        textCompositionInterop.notifyTextInputLayoutChanged()
    }

    private fun subscribeEvents() {
        fun pointerToken(
            add: (PointerEventHandler) -> EventRegistrationToken,
            remove: (EventRegistrationToken) -> Unit,
            type: WinUIPointerEventType,
        ) {
            val token = add(PointerEventHandler { _, args ->
                handlePointerEvent(type, args)
            })
            eventTokens += WinUIInputEventToken(token, remove)
        }

        pointerToken(panel.pointerEntered::add, panel.pointerEntered::remove, WinUIPointerEventType.ENTERED)
        pointerToken(panel.pointerPressed::add, panel.pointerPressed::remove, WinUIPointerEventType.PRESSED)
        pointerToken(panel.pointerMoved::add, panel.pointerMoved::remove, WinUIPointerEventType.MOVED)
        pointerToken(panel.pointerReleased::add, panel.pointerReleased::remove, WinUIPointerEventType.RELEASED)
        pointerToken(panel.pointerWheelChanged::add, panel.pointerWheelChanged::remove, WinUIPointerEventType.WHEEL)
        pointerToken(panel.pointerExited::add, panel.pointerExited::remove, WinUIPointerEventType.EXITED)
        pointerToken(panel.pointerCanceled::add, panel.pointerCanceled::remove, WinUIPointerEventType.CANCELED)
        pointerToken(panel.pointerCaptureLost::add, panel.pointerCaptureLost::remove, WinUIPointerEventType.CAPTURE_LOST)

        val keyDownToken = panel.keyDown.add(KeyEventHandler { _, args ->
            handleKeyEvent(pressed = true, args)
        })
        eventTokens += WinUIInputEventToken(keyDownToken, panel.keyDown::remove)

        val keyUpToken = panel.keyUp.add(KeyEventHandler { _, args ->
            handleKeyEvent(pressed = false, args)
        })
        eventTokens += WinUIInputEventToken(keyUpToken, panel.keyUp::remove)

        val characterReceivedToken = panel.characterReceived.add(TypedEventHandler { _, args ->
            handleTextInputEvent(args)
        })
        eventTokens += WinUIInputEventToken(characterReceivedToken, panel.characterReceived::remove)

        val gotFocusToken = panel.gotFocus.add(RoutedEventHandler { _, _ ->
            handleFocusEvent(focused = true)
        })
        eventTokens += WinUIInputEventToken(gotFocusToken, panel.gotFocus::remove)

        val lostFocusToken = panel.lostFocus.add(RoutedEventHandler { _, _ ->
            handleFocusEvent(focused = false)
        })
        eventTokens += WinUIInputEventToken(lostFocusToken, panel.lostFocus::remove)
    }

    private fun handlePointerEvent(type: WinUIPointerEventType, args: PointerRoutedEventArgs) {
        val point = args.getCurrentPoint(panel)
        if (type == WinUIPointerEventType.PRESSED) {
            args.pointer?.let(panel::capturePointer)
            panel.focus(FocusState.Pointer)
        }
        if (type == WinUIPointerEventType.RELEASED || type == WinUIPointerEventType.CANCELED) {
            panel.releasePointerCaptures()
        }
        val event = WinUIPointerEvent(
            type = type,
            pointerId = point.pointerId.toLong(),
            frameId = point.frameId.toLong(),
            x = point.position.x,
            y = point.position.y,
            deviceType = point.pointerDeviceType.toWinUIDeviceType(),
            buttons = point.properties.toWinUIButtons(),
            buttonChange = point.properties.toWinUIButtonChange(),
            wheelDelta = point.properties?.mouseWheelDelta ?: 0,
            isInContact = point.isInContact,
            isInRange = point.properties?.isInRange ?: false,
            isPrimary = point.properties?.isPrimary ?: false,
            isCanceled = point.properties?.isCanceled ?: false,
            isHorizontalMouseWheel = point.properties?.isHorizontalMouseWheel ?: false,
            isBarrelButtonPressed = point.properties?.isBarrelButtonPressed ?: false,
            isEraser = point.properties?.isEraser ?: false,
            isInverted = point.properties?.isInverted ?: false,
            pressure = point.properties?.pressure ?: 0f,
            orientation = point.properties?.orientation ?: 0f,
            twist = point.properties?.twist ?: 0f,
            xTilt = point.properties?.xTilt ?: 0f,
            yTilt = point.properties?.yTilt ?: 0f,
            touchConfidence = point.properties?.touchConfidence ?: false,
            contactRect = point.properties.toWinUIContactRect(),
            modifiers = args.keyModifiers.toWinUIModifiers(),
            timestamp = point.timestamp.toLong(),
        )
        if (layer.inputHandler?.onPointerEvent(event) == true) {
            args.handled = true
        }
    }

    private fun handleKeyEvent(pressed: Boolean, args: KeyRoutedEventArgs) {
        val status = args.keyStatus
        keyboardModifiers.update(args.key, pressed)
        keyboardModifiers.update(args.originalKey, pressed)
        val event = WinUIKeyEvent(
            pressed = pressed,
            keyCode = args.key.abiValue,
            originalKeyCode = args.originalKey.abiValue,
            modifiers = keyboardModifiers.current,
            scanCode = status.scanCode.toInt(),
            repeatCount = status.repeatCount.toInt(),
            isExtendedKey = status.isExtendedKey,
            wasKeyDown = status.wasKeyDown,
            isKeyReleased = status.isKeyReleased,
            deviceId = args.deviceId,
        )
        if (layer.inputHandler?.onKeyEvent(event) == true) {
            args.handled = true
        }
    }

    private fun handleTextInputEvent(args: CharacterReceivedRoutedEventArgs) {
        val status = args.keyStatus
        val character = args.character
        val text = character.toString()
        val event = WinUITextInputEvent(
            character = character,
            codePoint = character.code,
            modifiers = keyboardModifiers.current,
            scanCode = status.scanCode.toInt(),
            repeatCount = status.repeatCount.toInt(),
            isExtendedKey = status.isExtendedKey,
            isMenuKeyDown = status.isMenuKeyDown,
            wasKeyDown = status.wasKeyDown,
            isKeyReleased = status.isKeyReleased,
        )
        val compositionEvent = WinUITextCompositionEvent(
            type = WinUITextCompositionEventType.COMMITTED,
            text = text,
            selection = WinUITextRange(start = text.length, end = text.length),
            replacementRange = WinUITextRange(start = 0, end = 0),
            compositionRange = WinUITextRange(start = 0, end = text.length),
            modifiers = keyboardModifiers.current,
        )
        val handled = layer.inputHandler?.onTextInputEvent(event) == true ||
            layer.inputHandler?.onTextCompositionEvent(compositionEvent) == true
        if (handled) {
            args.handled = true
        }
    }

    private fun handleFocusEvent(focused: Boolean) {
        textCompositionInterop.onFocusChanged(focused)
        val event = WinUIFocusEvent(
            focused = focused,
            focusState = panel.focusState.toWinUIFocusState(),
        )
        layer.inputHandler?.onFocusEvent(event)
    }
}

private class WinUITextCompositionInterop(
    private val layer: WinUISkiaLayer,
    private val panel: SwapChainPanel,
    private val modifiers: () -> WinUIKeyModifiers,
) : AutoCloseable {
    private val eventTokens = mutableListOf<WinUIInputEventToken>()
    private var editContext: CoreTextEditContext? = null
    private var text = ""
    private var selection = WinUITextRange(start = 0, end = 0)
    private var compositionRange = WinUITextRange(start = 0, end = 0)
    private var layoutBounds: WinUITextLayoutBounds? = null
    private var isComposing = false
    private var isClosed = false

    fun updateTextInputState(
        text: String,
        selection: WinUITextRange,
        compositionRange: WinUITextRange,
    ) {
        if (isClosed) {
            return
        }
        val previousTextLength = this.text.length
        this.text = text
        this.selection = selection.boundedBy(text.length)
        this.compositionRange = compositionRange.boundedBy(text.length)
        val context = ensureEditContext() ?: return
        context.notifyTextChanged(
            modifiedRange = CoreTextRange(0, previousTextLength),
            newLength = text.length,
            newSelection = this.selection.toCoreTextRange(),
        )
        context.notifySelectionChanged(this.selection.toCoreTextRange())
    }

    fun updateTextInputLayout(bounds: WinUITextLayoutBounds) {
        if (isClosed) {
            return
        }
        layoutBounds = bounds
        notifyTextInputLayoutChanged()
    }

    fun notifyTextInputLayoutChanged() {
        if (!isClosed) {
            editContext?.notifyLayoutChanged()
        }
    }

    fun onFocusChanged(focused: Boolean) {
        if (isClosed) {
            return
        }
        val context = ensureEditContext() ?: return
        if (focused) {
            context.notifyFocusEnter()
        } else {
            context.notifyFocusLeave()
        }
    }

    override fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        eventTokens.forEach { it.remove() }
        eventTokens.clear()
        editContext = null
        text = ""
        selection = WinUITextRange(start = 0, end = 0)
        compositionRange = WinUITextRange(start = 0, end = 0)
        isComposing = false
        layoutBounds = null
    }

    private fun ensureEditContext(): CoreTextEditContext? {
        editContext?.let { return it }
        val context = runCatching {
            CoreTextServicesManager.getForCurrentView().createEditContext()
        }.getOrNull() ?: return null
        context.name = "Skiko WinUI"
        context.inputScope = CoreTextInputScope.Text
        context.inputPaneDisplayPolicy = CoreTextInputPaneDisplayPolicy.Automatic
        subscribe(context)
        editContext = context
        return context
    }

    private fun subscribe(context: CoreTextEditContext) {
        eventTokens += WinUIInputEventToken(
            context.textRequested.add(TypedEventHandler { _, args ->
                handleTextRequested(args)
            }),
            context.textRequested::remove,
        )
        eventTokens += WinUIInputEventToken(
            context.selectionRequested.add(TypedEventHandler { _, args ->
                handleSelectionRequested(args)
            }),
            context.selectionRequested::remove,
        )
        eventTokens += WinUIInputEventToken(
            context.layoutRequested.add(TypedEventHandler { _, args ->
                handleLayoutRequested(args)
            }),
            context.layoutRequested::remove,
        )
        eventTokens += WinUIInputEventToken(
            context.textUpdating.add(TypedEventHandler { _, args ->
                handleTextUpdating(args)
            }),
            context.textUpdating::remove,
        )
        eventTokens += WinUIInputEventToken(
            context.selectionUpdating.add(TypedEventHandler { _, args ->
                handleSelectionUpdating(args)
            }),
            context.selectionUpdating::remove,
        )
        eventTokens += WinUIInputEventToken(
            context.compositionStarted.add(TypedEventHandler { _, args ->
                handleCompositionStarted(args)
            }),
            context.compositionStarted::remove,
        )
        eventTokens += WinUIInputEventToken(
            context.compositionCompleted.add(TypedEventHandler { _, args ->
                handleCompositionCompleted(args)
            }),
            context.compositionCompleted::remove,
        )
    }

    private fun handleTextRequested(args: CoreTextTextRequestedEventArgs) {
        val request = args.request ?: return
        if (request.isCanceled) {
            return
        }
        request.text = text.slice(request.range.toWinUITextRange().boundedBy(text.length))
    }

    private fun handleSelectionRequested(args: CoreTextSelectionRequestedEventArgs) {
        val request = args.request ?: return
        if (!request.isCanceled) {
            request.selection = selection.toCoreTextRange()
        }
    }

    private fun handleLayoutRequested(args: CoreTextLayoutRequestedEventArgs) {
        val request = args.request ?: return
        if (request.isCanceled) {
            return
        }
        val bounds = request.layoutBounds ?: request.layoutBoundsVisualPixels ?: return
        val fallbackBounds = WinUITextLayoutBounds(
            textBounds = WinUIRect(
                x = 0f,
                y = 0f,
                width = panel.actualWidth.toFloat(),
                height = panel.actualHeight.toFloat(),
            ),
            controlBounds = WinUIRect(
                x = 0f,
                y = 0f,
                width = panel.actualWidth.toFloat(),
                height = panel.actualHeight.toFloat(),
            ),
        )
        val currentBounds = layoutBounds ?: fallbackBounds
        bounds.controlBounds = currentBounds.controlBounds.toRect()
        bounds.textBounds = currentBounds.textBounds.toRect()
    }

    private fun handleTextUpdating(args: CoreTextTextUpdatingEventArgs) {
        if (args.isCanceled) {
            args.result = CoreTextTextUpdatingResult.Failed
            emitCompositionEvent(WinUITextCompositionEventType.CANCELED, text = "")
            return
        }
        val replacementRange = args.range.toWinUITextRange().boundedBy(text.length)
        text = text.replaceRange(replacementRange.start, replacementRange.end, args.text)
        selection = args.newSelection.toWinUITextRange().boundedBy(text.length)
        compositionRange = WinUITextRange(
            start = replacementRange.start,
            end = replacementRange.start + args.text.length,
        ).boundedBy(text.length)
        emitCompositionEvent(
            type = if (isComposing) WinUITextCompositionEventType.UPDATED else WinUITextCompositionEventType.COMMITTED,
            text = args.text,
            replacementRange = replacementRange,
            activeCompositionRange = compositionRange,
            inputLanguage = args.inputLanguage?.languageTag,
        )
        args.result = CoreTextTextUpdatingResult.Succeeded
    }

    private fun handleSelectionUpdating(args: CoreTextSelectionUpdatingEventArgs) {
        if (args.isCanceled) {
            args.result = CoreTextSelectionUpdatingResult.Failed
            return
        }
        selection = args.selection.toWinUITextRange().boundedBy(text.length)
        args.result = CoreTextSelectionUpdatingResult.Succeeded
    }

    private fun handleCompositionStarted(args: CoreTextCompositionStartedEventArgs) {
        isComposing = !args.isCanceled
        emitCompositionEvent(
            type = if (args.isCanceled) WinUITextCompositionEventType.CANCELED else WinUITextCompositionEventType.STARTED,
            text = "",
        )
    }

    private fun handleCompositionCompleted(args: CoreTextCompositionCompletedEventArgs) {
        val eventText = text.slice(compositionRange.boundedBy(text.length))
        emitCompositionEvent(
            type = if (args.isCanceled) WinUITextCompositionEventType.CANCELED else WinUITextCompositionEventType.COMMITTED,
            text = eventText,
        )
        isComposing = false
        compositionRange = WinUITextRange(selection.start, selection.start)
    }

    private fun emitCompositionEvent(
        type: WinUITextCompositionEventType,
        text: String,
        replacementRange: WinUITextRange = this.compositionRange,
        activeCompositionRange: WinUITextRange = this.compositionRange,
        inputLanguage: String? = null,
    ) {
        layer.inputHandler?.onTextCompositionEvent(
            WinUITextCompositionEvent(
                type = type,
                text = text,
                selection = selection,
                replacementRange = replacementRange,
                compositionRange = activeCompositionRange,
                modifiers = modifiers(),
                inputLanguage = inputLanguage,
            )
        )
    }
}

private class WinUIInputEventToken(
    private val token: EventRegistrationToken,
    private val removeToken: (EventRegistrationToken) -> Unit,
) {
    fun remove() {
        removeToken(token)
    }
}

private class WinUIKeyboardModifierState {
    private val pressedKeys = mutableSetOf<VirtualKey>()

    val current: WinUIKeyModifiers
        get() = WinUIKeyModifiers(
            control = hasAny(VirtualKey.Control, VirtualKey.LeftControl, VirtualKey.RightControl),
            alt = hasAny(VirtualKey.Menu, VirtualKey.LeftMenu, VirtualKey.RightMenu),
            shift = hasAny(VirtualKey.Shift, VirtualKey.LeftShift, VirtualKey.RightShift),
            windows = hasAny(VirtualKey.LeftWindows, VirtualKey.RightWindows),
        )

    fun update(key: VirtualKey, pressed: Boolean) {
        if (key == VirtualKey.None) {
            return
        }
        if (pressed) {
            pressedKeys += key
        } else {
            pressedKeys -= key
        }
    }

    private fun hasAny(vararg keys: VirtualKey): Boolean = keys.any(pressedKeys::contains)
}

private fun CoreTextRange.toWinUITextRange(): WinUITextRange =
    WinUITextRange(
        start = startCaretPosition,
        end = endCaretPosition,
    )

private fun WinUITextRange.toCoreTextRange(): CoreTextRange =
    CoreTextRange(
        startCaretPosition = start,
        endCaretPosition = end,
    )

private fun WinUIRect.toRect(): Rect =
    Rect(
        x = x,
        y = y,
        width = width,
        height = height,
    )

private fun WinUITextRange.boundedBy(length: Int): WinUITextRange {
    val boundedStart = start.coerceIn(0, length)
    val boundedEnd = end.coerceIn(0, length)
    return if (boundedStart <= boundedEnd) {
        WinUITextRange(boundedStart, boundedEnd)
    } else {
        WinUITextRange(boundedEnd, boundedStart)
    }
}

private fun String.slice(range: WinUITextRange): String =
    substring(range.start, range.end)

private fun PointerDeviceType.toWinUIDeviceType(): WinUIPointerDeviceType = when (this) {
    PointerDeviceType.Touch -> WinUIPointerDeviceType.TOUCH
    PointerDeviceType.Pen -> WinUIPointerDeviceType.PEN
    PointerDeviceType.Mouse -> WinUIPointerDeviceType.MOUSE
    PointerDeviceType.Touchpad -> WinUIPointerDeviceType.TOUCHPAD
}

private fun PointerPointProperties?.toWinUIButtons(): WinUIPointerButtons = if (this == null) {
    WinUIPointerButtons()
} else {
    WinUIPointerButtons(
        left = isLeftButtonPressed,
        right = isRightButtonPressed,
        middle = isMiddleButtonPressed,
        x1 = isXButton1Pressed,
        x2 = isXButton2Pressed,
    )
}

private fun PointerPointProperties?.toWinUIContactRect(): WinUIPointerContactRect {
    val rect = this?.contactRect
    return if (rect == null) {
        WinUIPointerContactRect(x = 0f, y = 0f, width = 0f, height = 0f)
    } else {
        WinUIPointerContactRect(
            x = rect.x,
            y = rect.y,
            width = rect.width,
            height = rect.height,
        )
    }
}

private fun PointerPointProperties?.toWinUIButtonChange(): WinUIPointerButtonChange = when (this?.pointerUpdateKind) {
    PointerUpdateKind.LeftButtonPressed -> WinUIPointerButtonChange.LEFT_PRESSED
    PointerUpdateKind.LeftButtonReleased -> WinUIPointerButtonChange.LEFT_RELEASED
    PointerUpdateKind.RightButtonPressed -> WinUIPointerButtonChange.RIGHT_PRESSED
    PointerUpdateKind.RightButtonReleased -> WinUIPointerButtonChange.RIGHT_RELEASED
    PointerUpdateKind.MiddleButtonPressed -> WinUIPointerButtonChange.MIDDLE_PRESSED
    PointerUpdateKind.MiddleButtonReleased -> WinUIPointerButtonChange.MIDDLE_RELEASED
    PointerUpdateKind.XButton1Pressed -> WinUIPointerButtonChange.X1_PRESSED
    PointerUpdateKind.XButton1Released -> WinUIPointerButtonChange.X1_RELEASED
    PointerUpdateKind.XButton2Pressed -> WinUIPointerButtonChange.X2_PRESSED
    PointerUpdateKind.XButton2Released -> WinUIPointerButtonChange.X2_RELEASED
    PointerUpdateKind.Other, null -> WinUIPointerButtonChange.NONE
}

private fun VirtualKeyModifiers.toWinUIModifiers(): WinUIKeyModifiers = WinUIKeyModifiers(
    control = hasFlag(VirtualKeyModifiers.Control),
    alt = hasFlag(VirtualKeyModifiers.Menu),
    shift = hasFlag(VirtualKeyModifiers.Shift),
    windows = hasFlag(VirtualKeyModifiers.Windows),
)
