package org.jetbrains.skiko.winui

import io.github.composefluent.winrt.runtime.EventRegistrationToken
import io.github.composefluent.winrt.runtime.asWinRT
import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.xaml.FrameworkElement
import microsoft.ui.xaml.RoutedEventHandler
import microsoft.ui.xaml.SizeChangedEventHandler
import microsoft.ui.xaml.Window
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkikoRenderDelegate
import kotlin.math.roundToInt

/**
 * AWT-free Skiko layer hosted by WinUI.
 *
 * V1 is intentionally Direct3D-only and uses a WinUI SwapChainPanel as its platform component.
 */
class WinUISkiaLayer(
    override var renderDelegate: SkikoRenderDelegate? = null,
) : WinUISkiaLayerSurface {
    override var inputHandler: WinUIInputHandler? = null
    override var accessibilityProvider: WinUIAccessibilityProvider? = null
        set(value) {
            check(!isDisposed) { "WinUISkiaLayer is disposed" }
            field = value
            invalidateAccessibility()
        }

    internal val dispatcherQueue: DispatcherQueue = DispatcherQueue.getForCurrentThread()
    private val hostPanel = createWinUISkiaHost()
    override val renderPanel = hostPanel.renderPanel
    private val panel = renderPanel
    private val platformInterop = WinUISkiaLayerPlatformInterop(this, panel)
    private val inputInterop = createWinUIInputInterop(this, panel)
    private val accessibilityInterop = WinUIAccessibilityInterop(hostPanel.component)
    private val renderDispatcher = WinUIRenderDispatcher(
        dispatcherQueue = dispatcherQueue,
        isDisposed = { isDisposed },
        renderNow = ::renderNow,
    )
    private var attachedWindowBinding: WinUISkiaWindowBinding? = null
    private var standaloneFrameScheduler: WinUIFrameScheduler? = null
    private var sizeChangedToken: EventRegistrationToken? = null
    private var compositionScaleChangedToken: EventRegistrationToken? = null
    private var loadedToken: EventRegistrationToken? = null
    private var unloadedToken: EventRegistrationToken? = null
    private val pictureLock = WinUILock()
    private val pictureRecorder = PictureRecorder()
    private var picture: WinUIPictureHolder? = null
    private var drawScope: WinUILayerDrawScope? = null
    private var lastRenderedState: WinUILayerRenderState? = null
    private var lastInvalidatedState: WinUILayerRenderState? = null
    private var lastPlatformResult: WinUIPlatformRenderResult? = null
    private var lastRenderFailure: WinUILayerRenderFailure? = null
    private var renderVersion = 0L
    private var isDisposed = false
    private var hasLoadedForRender = false
    private var renderRequestedWhileUnloaded = false

    override var accessibilityInfo: WinUIAccessibilityInfo = WinUIAccessibilityInfo()
        set(value) {
            check(!isDisposed) { "WinUISkiaLayer is disposed" }
            field = value
            accessibilityInterop.update(value)
        }

    override val accessibilitySnapshot: WinUIAccessibilitySnapshot?
        get() = accessibilityInterop.snapshot

    override val accessibilityDiagnostics: WinUIAccessibilityDiagnostics
        get() = accessibilityInterop.currentDiagnostics

    internal val accessibilityChangeVersion: Long
        get() = accessibilityInterop.version

    init {
        hostPanel.bindLayer(this)
        hasLoadedForRender = hostPanel.component.isLoaded
        sizeChangedToken = panel.sizeChanged.add(SizeChangedEventHandler { _, _ ->
            invalidateRender()
        })
        compositionScaleChangedToken = panel.compositionScaleChanged.add { _, _ ->
            invalidateRender()
        }
        loadedToken = hostPanel.component.loaded.add(RoutedEventHandler { _, _ ->
            onLoaded()
        })
        unloadedToken = hostPanel.component.unloaded.add(RoutedEventHandler { _, _ ->
            onUnloaded()
        })
        hostPanel.component.isTabStop = true
        panel.isTabStop = true
        accessibilityInterop.update(accessibilityInfo)
    }

    override val component: FrameworkElement
        get() = hostPanel.component

    override var renderApi: GraphicsApi
        get() = GraphicsApi.DIRECT3D
        set(value) {
            if (value != GraphicsApi.DIRECT3D) {
                throw UnsupportedOperationException("WinUISkiaLayer supports only ${GraphicsApi.DIRECT3D}.")
            }
        }

    override val contentScale: Float
        get() = maxOf(panel.compositionScaleX, panel.compositionScaleY)

    override val pixelGeometry: PixelGeometry
        get() = PixelGeometry.UNKNOWN

    override var fullscreen: Boolean
        get() = true
        set(_) {
            throw UnsupportedOperationException("WinUISkiaLayer fullscreen mode is owned by the WinUI host.")
        }

    override val width: Float
        get() = logicalRenderWidth()

    override val height: Float
        get() = logicalRenderHeight()

    override val focusState: WinUIFocusState
        get() = panel.focusState.toWinUIFocusState()

    override fun attachTo(container: Any) {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        val window = container.toWinUIWindowOrNull()
            ?: throw IllegalArgumentException(
                "WinUISkiaLayer.attachTo expects a Microsoft.UI.Xaml.Window, got ${container::class.qualifiedName}."
            )
        detach()
        attachedWindowBinding = window.hostWinUISkiaLayer(
            layer = this,
            closeLayerOnWindowClosed = false,
        )
    }

    override fun detach() {
        attachedWindowBinding?.close()
        attachedWindowBinding = null
        standaloneFrameScheduler?.close()
        standaloneFrameScheduler = null
    }

    override fun requestFocus(focusState: WinUIFocusState): Boolean {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return focusState.toFocusState()?.let(panel::focus) ?: false
    }

    override fun startFrameScheduler(): WinUIFrameScheduler {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        val scheduler = attachedWindowBinding?.frameScheduler()
            ?: (standaloneFrameScheduler ?: WinUIFrameScheduler(this, dispatcherQueue = dispatcherQueue).also { scheduler ->
                standaloneFrameScheduler = scheduler
            })
        if (isReadyForRender) {
            scheduler.start()
        }
        return scheduler
    }

    override fun needRender(throttledToVsync: Boolean) {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        if (dispatcherQueue.hasThreadAccess && !isReadyForRender) {
            renderRequestedWhileUnloaded = true
            return
        }
        renderDispatcher.needRender(throttledToVsync)
    }

    override fun updateTextInputState(
        text: String,
        selection: WinUITextRange,
        compositionRange: WinUITextRange,
    ) {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        inputInterop.updateTextInputState(text, selection, compositionRange)
    }

    override fun updateTextInputLayout(bounds: WinUITextLayoutBounds) {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        inputInterop.updateTextInputLayout(bounds)
    }

    override fun notifyTextInputLayoutChanged() {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        inputInterop.notifyTextInputLayoutChanged()
    }

    override fun invalidateAccessibility() {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        val previousSnapshot = accessibilityInterop.snapshot
        val snapshot = accessibilityProvider?.snapshot()
        accessibilityInterop.updateSnapshot(
            snapshot = snapshot,
            changes = WinUIAccessibilityDiff.changes(
                oldSnapshot = previousSnapshot,
                newSnapshot = snapshot,
            ),
        )
    }

    override fun notifyAccessibilityChanged(change: WinUIAccessibilityChange) {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        accessibilityInterop.updateSnapshot(
            snapshot = accessibilityProvider?.snapshot(),
            change = change,
        )
    }

    override fun accessibilityRootNode(): WinUIAccessibilityNode? {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return accessibilityInterop.rootNode()
    }

    override fun accessibilityNode(nodeId: Long): WinUIAccessibilityNode? {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return accessibilityInterop.findNode(nodeId)
    }

    override fun accessibilityParent(nodeId: Long): WinUIAccessibilityNode? {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return accessibilityInterop.parentOf(nodeId)
    }

    override fun accessibilityChildren(nodeId: Long): List<WinUIAccessibilityNode> {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return accessibilityInterop.childrenOf(nodeId)
    }

    override fun accessibilityNodeAt(x: Float, y: Float): WinUIAccessibilityNode? {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return accessibilityInterop.hitTest(x, y)
    }

    override fun accessibilityFocusedNode(): WinUIAccessibilityNode? {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return accessibilityInterop.focusedNode()
    }

    override fun moveAccessibilityFocus(direction: WinUIAccessibilityFocusDirection): Boolean {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        val target = accessibilityInterop.focusTarget(direction) ?: return false
        return requestAccessibilityFocus(target.id)
    }

    override fun requestAccessibilityFocus(nodeId: Long): Boolean {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        val target = accessibilityInterop.findNode(nodeId) ?: return false
        if (!target.state.enabled || !target.state.focusable) {
            return false
        }
        val handled = performAccessibilityAction(nodeId, WinUIAccessibilityAction.FOCUS)
        if (handled) {
            notifyAccessibilityChanged(
                WinUIAccessibilityChange(
                    type = WinUIAccessibilityChangeType.FOCUS_CHANGED,
                    nodeId = nodeId,
                )
            )
        }
        return handled
    }

    override fun performAccessibilityAction(
        nodeId: Long,
        action: WinUIAccessibilityAction,
        text: String?,
    ): Boolean {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        return accessibilityProvider?.performAction(
            WinUIAccessibilityActionRequest(
                nodeId = nodeId,
                action = action,
                text = text,
            )
        ) == true
    }

    internal fun consumeAccessibilityChanges(afterVersion: Long): WinUIAccessibilityChanges =
        accessibilityInterop.consumeChanges(afterVersion)

    internal val accessibilityAutomationModel: WinUIAccessibilityAutomationModel
        get() = accessibilityInterop.automationModel { request ->
            accessibilityProvider?.performAction(request) == true
        }

    internal val automationPeerCreateCount: Int
        get() = hostPanel.automationPeerCreateCount

    override val renderDiagnostics: WinUILayerRenderDiagnostics
        get() = WinUILayerRenderDiagnostics(
            renderVersion = renderVersion,
            lastRenderedState = lastRenderedState,
            pendingInvalidatedState = lastInvalidatedState,
            lastPlatformResult = lastPlatformResult,
            lastFailure = lastRenderFailure,
        )

    private fun invalidateRender() {
        if (!isReadyForRender) {
            renderRequestedWhileUnloaded = true
            return
        }
        if (renderRequestedWhileUnloaded && currentRenderState() != null) {
            renderRequestedWhileUnloaded = false
            renderDispatcher.scheduleRender(throttledToVsync = false)
            return
        }
        if (!isDisposed && shouldInvalidateRender()) {
            renderDispatcher.scheduleRender(throttledToVsync = false)
        }
    }

    private fun shouldInvalidateRender(): Boolean {
        val state = currentRenderState() ?: return false
        if (state == lastRenderedState || state == lastInvalidatedState) {
            return false
        }
        lastInvalidatedState = state
        return true
    }

    @Deprecated(
        message = "Use needRender() instead",
        replaceWith = ReplaceWith("needRender()"),
    )
    override fun needRedraw() {
        needRender()
    }

    internal fun draw(canvas: Canvas) {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        check(drawScope != null) { "WinUISkiaLayer.draw() is only valid inside native render." }
        lockPicture { holder ->
            canvas.drawPicture(holder.picture)
        }
    }

    internal fun update(nanoTime: Long) {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        val scope = drawScope ?: throw IllegalStateException("WinUISkiaLayer.update() is only valid inside native render.")
        val pictureWidth = scope.width.toFloat().coerceAtLeast(0f)
        val pictureHeight = scope.height.toFloat().coerceAtLeast(0f)
        val canvas = pictureRecorder.beginRecording(0f, 0f, pictureWidth, pictureHeight).apply {
            clear(0x00000000)
        }
        renderDelegate?.onRender(
            canvas = canvas,
            width = scope.width,
            height = scope.height,
            nanoTime = nanoTime,
        )
        if (!isDisposed && !pictureRecorder.isClosed) {
            winuiSynchronized(pictureLock) {
                picture?.picture?.close()
                picture = WinUIPictureHolder(
                    picture = pictureRecorder.finishRecordingAsPicture(),
                    width = scope.width,
                    height = scope.height,
                )
            }
        }
    }

    internal inline fun inDrawScope(
        width: Int,
        height: Int,
        contentScale: Float,
        nanoTime: Long,
        block: () -> Unit,
    ) {
        check(!isDisposed) { "WinUISkiaLayer is disposed" }
        val previous = drawScope
        drawScope = WinUILayerDrawScope(
            width = width,
            height = height,
            contentScale = contentScale,
            nanoTime = nanoTime,
        )
        try {
            block()
        } finally {
            drawScope = previous
        }
    }

    override fun dispose() {
        close()
    }

    override fun close() {
        if (isDisposed) {
            return
        }
        detach()
        isDisposed = true
        sizeChangedToken?.let(panel.sizeChanged::remove)
        sizeChangedToken = null
        compositionScaleChangedToken?.let(panel.compositionScaleChanged::remove)
        compositionScaleChangedToken = null
        loadedToken?.let(hostPanel.component.loaded::remove)
        loadedToken = null
        unloadedToken?.let(hostPanel.component.unloaded::remove)
        unloadedToken = null
        inputInterop.close()
        accessibilityInterop.close()
        renderDispatcher.close()
        platformInterop.close()
        winuiSynchronized(pictureLock) {
            picture?.picture?.close()
            picture = null
        }
        lastRenderedState = null
        lastInvalidatedState = null
        lastPlatformResult = null
        lastRenderFailure = null
        renderVersion = 0L
        hasLoadedForRender = false
        renderRequestedWhileUnloaded = false
        pictureRecorder.close()
    }

    private fun renderNow(throttledToVsync: Boolean) {
        val state = currentRenderState()
        if (state == null) {
            renderRequestedWhileUnloaded = true
            return
        }
        val platformResult = try {
            platformInterop.render(
                width = state.scaledWidth,
                height = state.scaledHeight,
                contentScale = state.contentScale,
                throttledToVsync = throttledToVsync,
            )
        } catch (throwable: Throwable) {
            lastRenderFailure = throwable.toRenderFailure(
                state = state,
                throttledToVsync = throttledToVsync,
                renderVersion = renderVersion,
            )
            throw throwable
        }
        lastPlatformResult = platformResult
        lastRenderedState = state
        lastRenderFailure = null
        renderVersion += 1
        if (lastInvalidatedState == state) {
            lastInvalidatedState = null
        }
    }

    private fun currentRenderState(): WinUILayerRenderState? {
        if (!isReadyForRender) {
            return null
        }
        val scale = contentScale
        val logicalWidth = logicalRenderWidth()
        val logicalHeight = logicalRenderHeight()
        val scaledWidth = (logicalWidth * scale).roundToInt()
        val scaledHeight = (logicalHeight * scale).roundToInt()
        if (scaledWidth <= 0 || scaledHeight <= 0) {
            return null
        }
        return WinUILayerRenderState(
            logicalWidth = logicalWidth,
            logicalHeight = logicalHeight,
            scaledWidth = scaledWidth,
            scaledHeight = scaledHeight,
            contentScale = scale,
        )
    }

    private val isReadyForRender: Boolean
        get() = !isDisposed && (hasLoadedForRender || attachedWindowBinding != null)

    private fun onLoaded() {
        if (!isDisposed) {
            hasLoadedForRender = true
            if (renderRequestedWhileUnloaded) {
                invalidateRender()
            }
        }
    }

    private fun onUnloaded() {
        if (isDisposed) {
            return
        }
        hasLoadedForRender = false
        attachedWindowBinding?.stopFrameScheduler()
        standaloneFrameScheduler?.stop()
        renderRequestedWhileUnloaded = true
    }

    private fun <T : Any> lockPicture(action: (WinUIPictureHolder) -> T): T? =
        winuiSynchronized(pictureLock) {
            picture?.let(action)
        }

    private fun logicalRenderWidth(): Float =
        hostPanel.component.width.toRenderableDimension()
            ?: hostPanel.component.actualWidth.toFloat().takeIf { it > 0f }
            ?: 0f

    private fun logicalRenderHeight(): Float =
        hostPanel.component.height.toRenderableDimension()
            ?: hostPanel.component.actualHeight.toFloat().takeIf { it > 0f }
            ?: 0f

    private fun Double.toRenderableDimension(): Float? =
        takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 }?.toFloat()

    private fun Any.toWinUIWindowOrNull(): Window? =
        runCatching { asWinRT<Window>() }.getOrNull()

    private fun Throwable.toRenderFailure(
        state: WinUILayerRenderState,
        throttledToVsync: Boolean,
        renderVersion: Long,
    ): WinUILayerRenderFailure =
        WinUILayerRenderFailure(
            state = state,
            throttledToVsync = throttledToVsync,
            renderVersion = renderVersion,
            exceptionClass = this::class.qualifiedName ?: this::class.simpleName ?: "Throwable",
            message = message,
            causeClass = cause?.let { it::class.qualifiedName ?: it::class.simpleName ?: "Throwable" },
            causeMessage = cause?.message,
        )
}

internal class WinUILayerDrawScope(
    val width: Int,
    val height: Int,
    val contentScale: Float,
    val nanoTime: Long,
) {
    val logicalWidth: Int
        get() = (width / contentScale).toInt()

    val logicalHeight: Int
        get() = (height / contentScale).toInt()
}

private class WinUIPictureHolder(
    val picture: Picture,
    val width: Int,
    val height: Int,
)
