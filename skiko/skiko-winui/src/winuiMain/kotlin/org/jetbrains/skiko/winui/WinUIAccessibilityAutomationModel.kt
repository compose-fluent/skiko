package org.jetbrains.skiko.winui

import microsoft.ui.xaml.automation.peers.AutomationControlType
import microsoft.ui.xaml.automation.peers.AutomationEvents
import microsoft.ui.xaml.automation.peers.AutomationStructureChangeType
import microsoft.ui.xaml.automation.peers.PatternInterface

internal class WinUIAccessibilityAutomationModel(
    private val tree: WinUIAccessibilityTree?,
    private val changeSource: (Long) -> WinUIAccessibilityChanges,
    private val actionHandler: (WinUIAccessibilityActionRequest) -> Boolean,
) {
    val rootId: Long?
        get() = tree?.root?.id

    fun node(nodeId: Long): WinUIAccessibilityAutomationNode? {
        val node = tree?.findNode(nodeId) ?: return null
        return node.toAutomationNode(
            parentId = tree.parentOf(nodeId)?.id,
            childIds = tree.childrenOf(nodeId).map(WinUIAccessibilityNode::id),
        )
    }

    fun root(): WinUIAccessibilityAutomationNode? =
        rootId?.let(::node)

    fun parentOf(nodeId: Long): WinUIAccessibilityAutomationNode? =
        tree?.parentOf(nodeId)?.id?.let(::node)

    fun childrenOf(nodeId: Long): List<WinUIAccessibilityAutomationNode> =
        tree?.childrenOf(nodeId)
            ?.mapNotNull { child -> node(child.id) }
            .orEmpty()

    fun hitTest(x: Float, y: Float): WinUIAccessibilityAutomationNode? =
        tree?.hitTest(x, y)?.id?.let(::node)

    fun focusedNode(): WinUIAccessibilityAutomationNode? =
        tree?.focusedNode?.id?.let(::node)

    fun patternProvider(
        nodeId: Long,
        pattern: PatternInterface,
    ): WinUIAccessibilityAutomationPatternProvider? {
        val node = node(nodeId) ?: return null
        return if (pattern in node.patterns) {
            WinUIAccessibilityAutomationPatternProvider(
                nodeId = nodeId,
                pattern = pattern,
            )
        } else {
            null
        }
    }

    fun setFocus(nodeId: Long): Boolean =
        performAction(nodeId, WinUIAccessibilityAction.FOCUS)

    fun invoke(nodeId: Long): Boolean =
        performAction(nodeId, WinUIAccessibilityAction.CLICK)

    fun expand(nodeId: Long): Boolean =
        performAction(nodeId, WinUIAccessibilityAction.EXPAND)

    fun collapse(nodeId: Long): Boolean =
        performAction(nodeId, WinUIAccessibilityAction.COLLAPSE)

    fun increment(nodeId: Long): Boolean =
        performAction(nodeId, WinUIAccessibilityAction.INCREMENT)

    fun decrement(nodeId: Long): Boolean =
        performAction(nodeId, WinUIAccessibilityAction.DECREMENT)

    fun setText(nodeId: Long, text: String): Boolean =
        actionHandler(
            WinUIAccessibilityActionRequest(
                nodeId = nodeId,
                action = WinUIAccessibilityAction.SET_TEXT,
                text = text,
            )
        )

    fun changes(afterVersion: Long): WinUIAccessibilityAutomationChanges {
        val changes = changeSource(afterVersion)
        return WinUIAccessibilityAutomationChanges(
            version = changes.version,
            events = changes.changes.mapNotNull(WinUIAccessibilityChangeRecord::toAutomationEvent),
        )
    }

    private fun performAction(
        nodeId: Long,
        action: WinUIAccessibilityAction,
    ): Boolean =
        actionHandler(
            WinUIAccessibilityActionRequest(
                nodeId = nodeId,
                action = action,
            )
        )
}

internal data class WinUIAccessibilityAutomationNode(
    val nodeId: Long,
    val runtimeId: IntArray,
    val parentId: Long?,
    val childIds: List<Long>,
    val bounds: WinUIRect,
    val name: String,
    val automationId: String,
    val helpText: String,
    val fullDescription: String,
    val localizedControlType: String,
    val controlType: AutomationControlType,
    val patterns: Set<PatternInterface>,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val checked: Boolean?,
    val expanded: Boolean?,
    val editable: Boolean,
    val password: Boolean,
    val value: String,
)

internal data class WinUIAccessibilityAutomationPatternProvider(
    val nodeId: Long,
    val pattern: PatternInterface,
)

internal data class WinUIAccessibilityAutomationChanges(
    val version: Long,
    val events: List<WinUIAccessibilityAutomationEvent>,
)

internal data class WinUIAccessibilityAutomationEvent(
    val version: Long,
    val nodeId: Long?,
    val event: AutomationEvents,
    val structureChangeType: AutomationStructureChangeType? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
)

private fun WinUIAccessibilityNode.toAutomationNode(
    parentId: Long?,
    childIds: List<Long>,
): WinUIAccessibilityAutomationNode =
    WinUIAccessibilityAutomationNode(
        nodeId = id,
        runtimeId = runtimeId(),
        parentId = parentId,
        childIds = childIds,
        bounds = bounds,
        name = info.name,
        automationId = info.automationId.ifEmpty { "skiko-winui-$id" },
        helpText = info.helpText,
        fullDescription = info.fullDescription,
        localizedControlType = info.localizedControlType,
        controlType = info.role.toAutomationControlType(),
        patterns = automationPatterns(),
        enabled = state.enabled,
        focusable = state.focusable,
        focused = state.focused,
        selected = state.selected,
        checked = state.checked,
        expanded = state.expanded,
        editable = state.editable,
        password = state.password,
        value = value,
    )

private fun WinUIAccessibilityNode.runtimeId(): IntArray =
    intArrayOf(
        SKIKO_WINUI_RUNTIME_ID_PREFIX,
        (id ushr 32).toInt(),
        id.toInt(),
    )

private fun WinUIAccessibilityNode.automationPatterns(): Set<PatternInterface> =
    buildSet {
        if (WinUIAccessibilityAction.CLICK in actions) {
            add(PatternInterface.Invoke)
        }
        if (WinUIAccessibilityAction.SET_TEXT in actions || state.editable) {
            add(PatternInterface.Value)
            add(PatternInterface.Text)
            add(PatternInterface.TextEdit)
        } else if (info.role == WinUIAccessibilityRole.TEXT) {
            add(PatternInterface.Text)
        }
        if (WinUIAccessibilityAction.EXPAND in actions || WinUIAccessibilityAction.COLLAPSE in actions || state.expanded != null) {
            add(PatternInterface.ExpandCollapse)
        }
        if (WinUIAccessibilityAction.INCREMENT in actions || WinUIAccessibilityAction.DECREMENT in actions) {
            add(PatternInterface.RangeValue)
        }
        if (state.checked != null) {
            add(PatternInterface.Toggle)
        }
        if (state.selected) {
            add(PatternInterface.SelectionItem)
        }
    }

private fun WinUIAccessibilityChangeRecord.toAutomationEvent(): WinUIAccessibilityAutomationEvent? {
    val change = change
    return when (change.type) {
        WinUIAccessibilityChangeType.STRUCTURE_CHANGED -> WinUIAccessibilityAutomationEvent(
            version = version,
            nodeId = change.nodeId,
            event = AutomationEvents.StructureChanged,
            structureChangeType = AutomationStructureChangeType.ChildrenInvalidated,
            oldValue = change.oldValue,
            newValue = change.newValue,
        )
        WinUIAccessibilityChangeType.NODE_ADDED -> WinUIAccessibilityAutomationEvent(
            version = version,
            nodeId = change.nodeId,
            event = AutomationEvents.StructureChanged,
            structureChangeType = AutomationStructureChangeType.ChildAdded,
            oldValue = change.oldValue,
            newValue = change.newValue,
        )
        WinUIAccessibilityChangeType.NODE_REMOVED -> WinUIAccessibilityAutomationEvent(
            version = version,
            nodeId = change.nodeId,
            event = AutomationEvents.StructureChanged,
            structureChangeType = AutomationStructureChangeType.ChildRemoved,
            oldValue = change.oldValue,
            newValue = change.newValue,
        )
        WinUIAccessibilityChangeType.NODE_UPDATED,
        WinUIAccessibilityChangeType.VALUE_CHANGED -> WinUIAccessibilityAutomationEvent(
            version = version,
            nodeId = change.nodeId,
            event = AutomationEvents.PropertyChanged,
            oldValue = change.oldValue,
            newValue = change.newValue,
        )
        WinUIAccessibilityChangeType.FOCUS_CHANGED -> WinUIAccessibilityAutomationEvent(
            version = version,
            nodeId = change.nodeId,
            event = AutomationEvents.AutomationFocusChanged,
            oldValue = change.oldValue,
            newValue = change.newValue,
        )
        WinUIAccessibilityChangeType.TEXT_CHANGED -> WinUIAccessibilityAutomationEvent(
            version = version,
            nodeId = change.nodeId,
            event = AutomationEvents.TextPatternOnTextChanged,
            oldValue = change.oldValue,
            newValue = change.newValue,
        )
        WinUIAccessibilityChangeType.LIVE_REGION_CHANGED -> WinUIAccessibilityAutomationEvent(
            version = version,
            nodeId = change.nodeId,
            event = AutomationEvents.LiveRegionChanged,
            oldValue = change.oldValue,
            newValue = change.newValue,
        )
    }
}

private fun WinUIAccessibilityRole.toAutomationControlType(): AutomationControlType = when (this) {
    WinUIAccessibilityRole.CUSTOM -> AutomationControlType.Custom
    WinUIAccessibilityRole.BUTTON -> AutomationControlType.Button
    WinUIAccessibilityRole.CHECK_BOX -> AutomationControlType.CheckBox
    WinUIAccessibilityRole.COMBO_BOX -> AutomationControlType.ComboBox
    WinUIAccessibilityRole.EDIT -> AutomationControlType.Edit
    WinUIAccessibilityRole.IMAGE -> AutomationControlType.Image
    WinUIAccessibilityRole.LIST -> AutomationControlType.List
    WinUIAccessibilityRole.LIST_ITEM -> AutomationControlType.ListItem
    WinUIAccessibilityRole.TEXT -> AutomationControlType.Text
    WinUIAccessibilityRole.DOCUMENT -> AutomationControlType.Document
    WinUIAccessibilityRole.PANE -> AutomationControlType.Pane
    WinUIAccessibilityRole.SLIDER -> AutomationControlType.Slider
    WinUIAccessibilityRole.WINDOW -> AutomationControlType.Window
}

private const val SKIKO_WINUI_RUNTIME_ID_PREFIX = 0x5A17A
