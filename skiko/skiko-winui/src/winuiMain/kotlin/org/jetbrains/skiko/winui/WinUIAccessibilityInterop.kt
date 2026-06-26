package org.jetbrains.skiko.winui

import microsoft.ui.xaml.automation.AutomationProperties
import microsoft.ui.xaml.automation.peers.AccessibilityView
import microsoft.ui.xaml.automation.peers.AutomationControlType
import microsoft.ui.xaml.automation.peers.AutomationLiveSetting
import microsoft.ui.xaml.UIElement

internal class WinUIAccessibilityInterop(
    private val element: UIElement,
) : AutoCloseable {
    private var baseInfo = WinUIAccessibilityInfo()
    private var currentSnapshot: WinUIAccessibilitySnapshot? = null
    private var currentTree: WinUIAccessibilityTree? = null
    private val changeHistory = mutableListOf<WinUIAccessibilityChangeRecord>()
    private var changeVersion = 0L
    private var diagnostics = WinUIAccessibilityDiagnostics()

    val snapshot: WinUIAccessibilitySnapshot?
        get() = currentSnapshot

    val version: Long
        get() = changeVersion

    val currentDiagnostics: WinUIAccessibilityDiagnostics
        get() = diagnostics

    fun automationModel(actionHandler: (WinUIAccessibilityActionRequest) -> Boolean): WinUIAccessibilityAutomationModel =
        WinUIAccessibilityAutomationModel(
            tree = currentTree,
            changeSource = ::consumeChanges,
            actionHandler = actionHandler,
        )

    fun update(info: WinUIAccessibilityInfo) {
        baseInfo = info
        updateAutomationProperties(currentSnapshot?.root?.info ?: baseInfo)
    }

    fun updateSnapshot(
        snapshot: WinUIAccessibilitySnapshot?,
        change: WinUIAccessibilityChange? = null,
    ) {
        updateSnapshot(
            snapshot = snapshot,
            changes = change?.let(::listOf).orEmpty(),
        )
    }

    fun updateSnapshot(
        snapshot: WinUIAccessibilitySnapshot?,
        changes: List<WinUIAccessibilityChange>,
    ) {
        currentSnapshot = snapshot
        currentTree = snapshot?.let(WinUIAccessibilityTree::build)
        updateDiagnostics()
        updateAutomationProperties(currentTree?.root?.info ?: baseInfo)
        for (change in changes) {
            changeVersion += 1
            changeHistory += WinUIAccessibilityChangeRecord(
                version = changeVersion,
                change = change,
            )
        }
        trimChangeHistory()
        updateDiagnostics()
    }

    fun consumeChanges(afterVersion: Long): WinUIAccessibilityChanges {
        if (afterVersion >= changeVersion) {
            return WinUIAccessibilityChanges(version = changeVersion, changes = emptyList())
        }
        val changes = changeHistory.filter { record ->
            record.version > afterVersion
        }
        return WinUIAccessibilityChanges(
            version = changeVersion,
            changes = changes,
        )
    }

    fun findNode(nodeId: Long): WinUIAccessibilityNode? =
        currentTree?.findNode(nodeId)

    fun rootNode(): WinUIAccessibilityNode? =
        currentTree?.root

    fun parentOf(nodeId: Long): WinUIAccessibilityNode? =
        currentTree?.parentOf(nodeId)

    fun childrenOf(nodeId: Long): List<WinUIAccessibilityNode> =
        currentTree?.childrenOf(nodeId) ?: emptyList()

    fun focusedNode(): WinUIAccessibilityNode? =
        currentTree?.focusedNode

    fun hitTest(x: Float, y: Float): WinUIAccessibilityNode? =
        currentTree?.hitTest(x, y)

    fun focusTarget(direction: WinUIAccessibilityFocusDirection): WinUIAccessibilityNode? =
        currentTree?.focusTarget(direction)

    override fun close() {
        changeHistory.clear()
        currentSnapshot = null
        currentTree = null
        diagnostics = WinUIAccessibilityDiagnostics()
    }

    private fun trimChangeHistory() {
        if (changeHistory.size <= MAX_CHANGE_HISTORY) {
            return
        }
        changeHistory.subList(0, changeHistory.size - MAX_CHANGE_HISTORY).clear()
    }

    private fun updateDiagnostics() {
        val tree = currentTree
        val focusedNodeId = currentSnapshot?.focusedNodeId
        diagnostics = WinUIAccessibilityDiagnostics(
            version = changeVersion,
            duplicateNodeIds = tree?.duplicateNodeIds ?: emptySet(),
            focusedNodeMissing = focusedNodeId != null && tree?.findNode(focusedNodeId) == null,
        )
    }

    private fun updateAutomationProperties(info: WinUIAccessibilityInfo) {
        AutomationProperties.setName(element, info.name)
        AutomationProperties.setAutomationId(element, info.automationId)
        AutomationProperties.setHelpText(element, info.helpText)
        AutomationProperties.setFullDescription(element, info.fullDescription)
        AutomationProperties.setLocalizedControlType(element, info.localizedControlType)
        AutomationProperties.setAccessibilityView(element, info.view.toWinRTAccessibilityView())
        AutomationProperties.setLiveSetting(element, info.liveSetting.toWinRTLiveSetting())
        AutomationProperties.setAutomationControlType(element, info.role.toWinRTControlType())
    }
}

internal data class WinUIAccessibilityChanges(
    val version: Long,
    val changes: List<WinUIAccessibilityChangeRecord>,
)

internal data class WinUIAccessibilityChangeRecord(
    val version: Long,
    val change: WinUIAccessibilityChange,
)

private const val MAX_CHANGE_HISTORY = 256

private fun WinUIAccessibilityView.toWinRTAccessibilityView(): AccessibilityView = when (this) {
    WinUIAccessibilityView.RAW -> AccessibilityView.Raw
    WinUIAccessibilityView.CONTROL -> AccessibilityView.Control
    WinUIAccessibilityView.CONTENT -> AccessibilityView.Content
}

private fun WinUIAccessibilityLiveSetting.toWinRTLiveSetting(): AutomationLiveSetting = when (this) {
    WinUIAccessibilityLiveSetting.OFF -> AutomationLiveSetting.Off
    WinUIAccessibilityLiveSetting.POLITE -> AutomationLiveSetting.Polite
    WinUIAccessibilityLiveSetting.ASSERTIVE -> AutomationLiveSetting.Assertive
}

private fun WinUIAccessibilityRole.toWinRTControlType(): AutomationControlType = when (this) {
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
