package org.jetbrains.skiko.winui

import microsoft.ui.xaml.FocusState

internal fun FocusState.toWinUIFocusState(): WinUIFocusState = when (this) {
    FocusState.Unfocused -> WinUIFocusState.UNFOCUSED
    FocusState.Pointer -> WinUIFocusState.POINTER
    FocusState.Keyboard -> WinUIFocusState.KEYBOARD
    FocusState.Programmatic -> WinUIFocusState.PROGRAMMATIC
    else -> WinUIFocusState.UNKNOWN
}

internal fun WinUIFocusState.toFocusState(): FocusState? = when (this) {
    WinUIFocusState.POINTER -> FocusState.Pointer
    WinUIFocusState.KEYBOARD -> FocusState.Keyboard
    WinUIFocusState.PROGRAMMATIC -> FocusState.Programmatic
    WinUIFocusState.UNFOCUSED,
    WinUIFocusState.UNKNOWN -> null
}
