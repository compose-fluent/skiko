package org.jetbrains.skiko.winui

enum class WinUIAccessibilityView {
    RAW,
    CONTROL,
    CONTENT,
}

enum class WinUIAccessibilityLiveSetting {
    OFF,
    POLITE,
    ASSERTIVE,
}

enum class WinUIAccessibilityRole {
    CUSTOM,
    BUTTON,
    CHECK_BOX,
    COMBO_BOX,
    EDIT,
    IMAGE,
    LIST,
    LIST_ITEM,
    TEXT,
    DOCUMENT,
    PANE,
    SLIDER,
    WINDOW,
}

enum class WinUIAccessibilityAction {
    FOCUS,
    CLICK,
    EXPAND,
    COLLAPSE,
    INCREMENT,
    DECREMENT,
    SET_TEXT,
}

enum class WinUIAccessibilityChangeType {
    STRUCTURE_CHANGED,
    NODE_ADDED,
    NODE_REMOVED,
    NODE_UPDATED,
    FOCUS_CHANGED,
    VALUE_CHANGED,
    TEXT_CHANGED,
    LIVE_REGION_CHANGED,
}

enum class WinUIAccessibilityFocusDirection {
    FIRST,
    LAST,
    NEXT,
    PREVIOUS,
    PARENT,
    FIRST_CHILD,
}

data class WinUIAccessibilityInfo(
    val name: String = "Skiko WinUI",
    val automationId: String = "",
    val helpText: String = "",
    val fullDescription: String = "",
    val localizedControlType: String = "",
    val view: WinUIAccessibilityView = WinUIAccessibilityView.CONTENT,
    val liveSetting: WinUIAccessibilityLiveSetting = WinUIAccessibilityLiveSetting.OFF,
    val role: WinUIAccessibilityRole = WinUIAccessibilityRole.CUSTOM,
)

data class WinUIAccessibilityState(
    val enabled: Boolean = true,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean? = null,
    val expanded: Boolean? = null,
    val editable: Boolean = false,
    val password: Boolean = false,
)

data class WinUIAccessibilityNode(
    val id: Long,
    val bounds: WinUIRect,
    val info: WinUIAccessibilityInfo,
    val state: WinUIAccessibilityState = WinUIAccessibilityState(),
    val value: String = "",
    val actions: Set<WinUIAccessibilityAction> = emptySet(),
    val children: List<WinUIAccessibilityNode> = emptyList(),
)

data class WinUIAccessibilitySnapshot(
    val root: WinUIAccessibilityNode,
    val focusedNodeId: Long? = null,
)

data class WinUIAccessibilityDiagnostics(
    val version: Long = 0L,
    val duplicateNodeIds: Set<Long> = emptySet(),
    val focusedNodeMissing: Boolean = false,
)

data class WinUIAccessibilityActionRequest(
    val nodeId: Long,
    val action: WinUIAccessibilityAction,
    val text: String? = null,
)

data class WinUIAccessibilityChange(
    val type: WinUIAccessibilityChangeType,
    val nodeId: Long? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
)

interface WinUIAccessibilityProvider {
    fun snapshot(): WinUIAccessibilitySnapshot?
    fun performAction(request: WinUIAccessibilityActionRequest): Boolean = false
}
