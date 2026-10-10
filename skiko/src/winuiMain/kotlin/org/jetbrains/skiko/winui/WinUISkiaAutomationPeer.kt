package org.jetbrains.skiko.winui

import microsoft.ui.xaml.automation.peers.AutomationControlType
import microsoft.ui.xaml.automation.peers.AutomationLiveSetting
import microsoft.ui.xaml.automation.peers.AutomationNavigationDirection
import microsoft.ui.xaml.automation.peers.AutomationOrientation
import microsoft.ui.xaml.automation.peers.AutomationPeer
import microsoft.ui.xaml.automation.peers.PatternInterface
import windows.foundation.Point
import windows.foundation.Rect

internal class WinUISkiaAutomationPeer(
    private val layer: WinUISkiaLayer,
    private val nodeId: Long? = null,
) : AutomationPeer() {
    private val model: WinUIAccessibilityAutomationModel
        get() = layer.accessibilityAutomationModel

    private val node: WinUIAccessibilityAutomationNode?
        get() = nodeId?.let(model::node) ?: model.root()

    override fun getPatternCore(patternInterface: PatternInterface): Any? =
        node?.nodeId?.let { id -> model.patternProvider(id, patternInterface) }

    override fun getAutomationControlTypeCore(): AutomationControlType =
        node?.controlType ?: AutomationControlType.Custom

    override fun getAutomationIdCore(): String =
        node?.automationId.orEmpty()

    override fun getBoundingRectangleCore(): Rect =
        node?.bounds?.toWinRTRect() ?: Rect(0f, 0f, 0f, 0f)

    override fun getChildrenCore(): MutableList<AutomationPeer> =
        node
            ?.childIds
            ?.map { childId -> WinUISkiaAutomationPeer(layer, childId) }
            ?.toMutableList()
            ?: mutableListOf()

    override fun navigateCore(direction: AutomationNavigationDirection): Any? {
        val id = node?.nodeId ?: return null
        val target = when (direction) {
            AutomationNavigationDirection.Parent -> model.parentOf(id)
            AutomationNavigationDirection.FirstChild -> model.childrenOf(id).firstOrNull()
            AutomationNavigationDirection.LastChild -> model.childrenOf(id).lastOrNull()
            AutomationNavigationDirection.NextSibling -> siblingOf(id, offset = 1)
            AutomationNavigationDirection.PreviousSibling -> siblingOf(id, offset = -1)
            else -> null
        } ?: return null
        return WinUISkiaAutomationPeer(layer, target.nodeId)
    }

    override fun getClassNameCore(): String =
        "WinUISkiaLayer"

    override fun getClickablePointCore(): Point {
        val bounds = node?.bounds ?: return Point(0f, 0f)
        return Point(
            x = bounds.x + bounds.width / 2f,
            y = bounds.y + bounds.height / 2f,
        )
    }

    override fun getHelpTextCore(): String =
        node?.helpText.orEmpty()

    override fun getLocalizedControlTypeCore(): String =
        node?.localizedControlType.orEmpty()

    override fun getNameCore(): String =
        node?.name.orEmpty()

    override fun getOrientationCore(): AutomationOrientation =
        AutomationOrientation.None

    override fun hasKeyboardFocusCore(): Boolean =
        node?.focused == true

    override fun isContentElementCore(): Boolean =
        node != null

    override fun isControlElementCore(): Boolean =
        node != null

    override fun isEnabledCore(): Boolean =
        node?.enabled ?: false

    override fun isKeyboardFocusableCore(): Boolean =
        node?.focusable ?: false

    override fun isOffscreenCore(): Boolean =
        node == null

    override fun isPasswordCore(): Boolean =
        node?.password ?: false

    override fun setFocusCore() {
        node?.nodeId?.let(model::setFocus)
    }

    override fun getPeerFromPointCore(point: Point): AutomationPeer {
        lastPeerFromPoint = point
        peerFromPointCallCount += 1
        return model.hitTest(point.x, point.y)?.nodeId?.let { hitNodeId ->
            WinUISkiaAutomationPeer(layer, hitNodeId)
        } ?: this
    }

    override fun getFocusedElementCore(): Any? =
        model.focusedNode()?.nodeId?.let { focusedNodeId ->
            WinUISkiaAutomationPeer(layer, focusedNodeId)
        }

    override fun getLiveSettingCore(): AutomationLiveSetting =
        layer.accessibilityInfo.liveSetting.toAutomationLiveSetting()

    override fun getFullDescriptionCore(): String =
        node?.fullDescription.orEmpty()

    private fun siblingOf(id: Long, offset: Int): WinUIAccessibilityAutomationNode? {
        val parent = model.parentOf(id) ?: return null
        val siblings = parent.childIds
        val index = siblings.indexOf(id)
        if (index < 0) {
            return null
        }
        return siblings.getOrNull(index + offset)?.let(model::node)
    }

    internal companion object {
        var peerFromPointCallCount: Int = 0
            private set
        var lastPeerFromPoint: Point? = null
            private set

        fun resetDiagnostics() {
            peerFromPointCallCount = 0
            lastPeerFromPoint = null
        }
    }
}

private fun WinUIRect.toWinRTRect(): Rect =
    Rect(x, y, width, height)

private fun WinUIAccessibilityLiveSetting.toAutomationLiveSetting(): AutomationLiveSetting = when (this) {
    WinUIAccessibilityLiveSetting.OFF -> AutomationLiveSetting.Off
    WinUIAccessibilityLiveSetting.POLITE -> AutomationLiveSetting.Polite
    WinUIAccessibilityLiveSetting.ASSERTIVE -> AutomationLiveSetting.Assertive
}
