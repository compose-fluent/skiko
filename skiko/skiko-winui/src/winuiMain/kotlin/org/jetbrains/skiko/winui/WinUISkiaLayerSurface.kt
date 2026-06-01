package org.jetbrains.skiko.winui

import microsoft.ui.xaml.FrameworkElement
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkikoRenderDelegate

interface WinUISkiaLayerSurface : AutoCloseable {
    val component: FrameworkElement
    var renderDelegate: SkikoRenderDelegate?
    var inputHandler: WinUIInputHandler?
    var accessibilityInfo: WinUIAccessibilityInfo
    var accessibilityProvider: WinUIAccessibilityProvider?
    val accessibilitySnapshot: WinUIAccessibilitySnapshot?
    val accessibilityDiagnostics: WinUIAccessibilityDiagnostics
    var renderApi: GraphicsApi
    val contentScale: Float
    val pixelGeometry: PixelGeometry
    var fullscreen: Boolean
    val width: Float
    val height: Float
    val focusState: WinUIFocusState

    fun attachTo(container: Any)
    fun detach()
    fun requestFocus(focusState: WinUIFocusState = WinUIFocusState.PROGRAMMATIC): Boolean
    fun startFrameScheduler(): WinUIFrameScheduler
    fun needRender(throttledToVsync: Boolean = true)
    fun updateTextInputState(
        text: String,
        selection: WinUITextRange = WinUITextRange(text.length, text.length),
        compositionRange: WinUITextRange = selection,
    )
    fun updateTextInputLayout(bounds: WinUITextLayoutBounds)
    fun notifyTextInputLayoutChanged()
    fun invalidateAccessibility()
    fun notifyAccessibilityChanged(change: WinUIAccessibilityChange)
    fun accessibilityRootNode(): WinUIAccessibilityNode?
    fun accessibilityNode(nodeId: Long): WinUIAccessibilityNode?
    fun accessibilityParent(nodeId: Long): WinUIAccessibilityNode?
    fun accessibilityChildren(nodeId: Long): List<WinUIAccessibilityNode>
    fun accessibilityNodeAt(x: Float, y: Float): WinUIAccessibilityNode?
    fun accessibilityFocusedNode(): WinUIAccessibilityNode?
    fun moveAccessibilityFocus(direction: WinUIAccessibilityFocusDirection): Boolean
    fun requestAccessibilityFocus(nodeId: Long): Boolean
    fun performAccessibilityAction(
        nodeId: Long,
        action: WinUIAccessibilityAction,
        text: String? = null,
    ): Boolean

    @Deprecated(
        message = "Use needRender() instead",
        replaceWith = ReplaceWith("needRender()"),
    )
    fun needRedraw()

    fun dispose()
}
