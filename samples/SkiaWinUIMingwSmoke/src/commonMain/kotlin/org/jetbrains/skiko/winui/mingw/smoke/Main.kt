package org.jetbrains.skiko.winui.mingw.smoke

import io.github.composefluent.winrt.runtime.EventRegistrationToken
import io.github.composefluent.winrt.runtime.IUnknownReference
import io.github.composefluent.winrt.runtime.IWinRTObject
import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.dispatching.DispatcherQueueHandler
import microsoft.ui.dispatching.DispatcherQueueTimer
import microsoft.ui.xaml.Application
import microsoft.ui.xaml.UIElement
import microsoft.ui.xaml.LaunchActivatedEventArgs
import microsoft.ui.xaml.Window
import microsoft.ui.xaml.automation.peers.AutomationControlType
import microsoft.ui.xaml.automation.peers.AutomationNavigationDirection
import microsoft.ui.xaml.automation.peers.AutomationPeer
import microsoft.ui.xaml.automation.peers.FrameworkElementAutomationPeer
import org.jetbrains.skia.Color
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.winui.WinUIAccessibilityAction
import org.jetbrains.skiko.winui.WinUIAccessibilityActionRequest
import org.jetbrains.skiko.winui.WinUIAccessibilityFocusDirection
import org.jetbrains.skiko.winui.WinUIAccessibilityInfo
import org.jetbrains.skiko.winui.WinUIAccessibilityNode
import org.jetbrains.skiko.winui.WinUIAccessibilityProvider
import org.jetbrains.skiko.winui.WinUIAccessibilityRole
import org.jetbrains.skiko.winui.WinUIAccessibilitySnapshot
import org.jetbrains.skiko.winui.WinUIAccessibilityState
import org.jetbrains.skiko.winui.WinUIFocusEvent
import org.jetbrains.skiko.winui.WinUIFocusState
import org.jetbrains.skiko.winui.WinUIInputHandler
import org.jetbrains.skiko.winui.WinUIKeyEvent
import org.jetbrains.skiko.winui.WinUIPointerEvent
import org.jetbrains.skiko.winui.WinUIRect
import org.jetbrains.skiko.winui.WinUISkiaLayer
import org.jetbrains.skiko.winui.WinUITextCompositionEvent
import org.jetbrains.skiko.winui.WinUITextInputEvent
import org.jetbrains.skiko.winui.WinUITextLayoutBounds
import org.jetbrains.skiko.winui.WinUITextRange
import windows.foundation.Point
import windows.foundation.Rect
import windows.foundation.Size
import windows.foundation.TypedEventHandler
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private var activeSmoke: WinUIMingwSmoke? = null

fun main() {
    main(emptyArray())
}

fun main(args: Array<String>) {
    println("skiko-winui-mingw-smoke: runtime scope begin")
    val options = SmokeOptions.from(args)
    println("skiko-winui-mingw-smoke: application start")
    Application.start {
        val application = Application.current ?: WinUIMingwSmokeApplication()
        activeSmoke = WinUIMingwSmoke(application, options).also { smoke ->
            smoke.launch()
        }
    }
    println("skiko-winui-mingw-smoke: application returned")
    activeSmoke?.close()
    activeSmoke = null
    println("skiko-winui-mingw-smoke: done")
}

private class WinUIMingwSmokeApplication : Application() {
    override fun onLaunched(args: LaunchActivatedEventArgs) {
    }
}

private class WinUIMingwSmoke(
    private val application: Application,
    private val options: SmokeOptions,
) : AutoCloseable {
    private lateinit var layer: WinUISkiaLayer
    private lateinit var window: Window
    private var frameTimer: DispatcherQueueTimer? = null
    private var frameTimerTickToken: EventRegistrationToken? = null
    private var closed = false
    private var renderCount = 0
    private var exitQueued = false

    fun launch() {
        println("skiko-winui-mingw-smoke: create layer")
        layer = WinUISkiaLayer { canvas, width, height, _ ->
            println("skiko-winui-mingw-smoke: render $width x $height")
            renderCount += 1
            canvas.clear(Color.WHITE)
        }

        verifyLayerApi()
        layer.component.width = 320.0
        layer.component.height = 240.0

        println("skiko-winui-mingw-smoke: create window")
        window = Window()
        window.title = "Skiko WinUI mingw smoke"
        println("skiko-winui-mingw-smoke: attach layer")
        layer.attachTo(window)
        check(layer.startFrameScheduler().isRunning) {
            "Expected frame scheduler to start after attachTo(Window)."
        }
        layer.startFrameScheduler().stop()

        println("skiko-winui-mingw-smoke: activate window")
        window.activate()
        runSmokeFrames(layer.component, layer)
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        frameTimer?.stop()
        frameTimerTickToken?.let { token ->
            frameTimer?.tick?.remove(token)
        }
        frameTimerTickToken = null
        frameTimer = null
        if (::layer.isInitialized) {
            println("skiko-winui-mingw-smoke: close layer")
            layer.close()
        }
        if (::window.isInitialized) {
            println("skiko-winui-mingw-smoke: close window")
            window.close()
        }
        activeSmoke = null
    }

    private fun enqueueExit() {
        if (exitQueued) {
            return
        }
        exitQueued = true
        val enqueued = DispatcherQueue.getForCurrentThread().tryEnqueue(DispatcherQueueHandler {
            try {
                println("skiko-winui-mingw-smoke: exit")
                close()
                application.exit()
                exitProcess(0)
            } catch (throwable: Throwable) {
                throwable.printStackTrace()
                close()
                exitProcess(1)
            }
        })
        if (!enqueued) {
            error("Failed to enqueue skiko-winui mingw smoke exit.")
        }
    }

    private fun verifyLayerApi() {
        println("skiko-winui-mingw-smoke: verify layer api")
        check(layer.renderApi == GraphicsApi.DIRECT3D) {
            "Expected WinUISkiaLayer renderApi to be DIRECT3D, got ${layer.renderApi}."
        }
        layer.renderApi = GraphicsApi.DIRECT3D
        check(layer.fullscreen) {
            "Expected WinUISkiaLayer fullscreen to report true."
        }
        check(layer.focusState == WinUIFocusState.UNFOCUSED) {
            "Expected unattached WinUISkiaLayer focusState to be UNFOCUSED, got ${layer.focusState}."
        }
        check(!layer.startFrameScheduler().isRunning) {
            "Expected unattached WinUISkiaLayer frame scheduler to wait until the layer is hosted."
        }
        layer.startFrameScheduler().close()
        layer.needRender(throttledToVsync = false)
        check(renderCount == 0) {
            "Expected unattached WinUISkiaLayer.needRender() to wait until hosted, got $renderCount renders."
        }

        val firstInputHandler = CountingInputHandler()
        val secondInputHandler = CountingInputHandler()
        layer.inputHandler = firstInputHandler
        check(layer.inputHandler === firstInputHandler) {
            "Expected first input handler to be installed."
        }
        layer.inputHandler = secondInputHandler
        check(layer.inputHandler === secondInputHandler) {
            "Expected second input handler to replace first input handler."
        }
        layer.inputHandler = null
        check(layer.inputHandler == null) {
            "Expected input handler to be clearable."
        }

        layer.updateTextInputState(
            text = "mingw",
            selection = WinUITextRange(start = 2, end = 5),
            compositionRange = WinUITextRange(start = 0, end = 5),
        )
        layer.updateTextInputLayout(
            WinUITextLayoutBounds(
                textBounds = WinUIRect(x = 4f, y = 8f, width = 64f, height = 24f),
                controlBounds = WinUIRect(x = 0f, y = 0f, width = 320f, height = 240f),
            )
        )
        layer.notifyTextInputLayoutChanged()

        val accessibilityInfo = WinUIAccessibilityInfo(
            name = "Skiko WinUI mingw smoke surface",
            automationId = "SkikoWinUIMingwSmokeSurface",
            role = WinUIAccessibilityRole.PANE,
        )
        layer.accessibilityInfo = accessibilityInfo
        check(layer.accessibilityInfo == accessibilityInfo) {
            "Expected accessibilityInfo to round-trip."
        }

        val accessibilityProvider = SmokeAccessibilityProvider()
        layer.accessibilityProvider = accessibilityProvider
        check(layer.accessibilitySnapshot == accessibilityProvider.snapshot()) {
            "Expected accessibility snapshot to come from provider."
        }
        check(layer.accessibilityDiagnostics.duplicateNodeIds.isEmpty()) {
            "Expected no duplicate accessibility node ids."
        }
        check(layer.accessibilityRootNode()?.id == SmokeAccessibilityProvider.ROOT_ID) {
            "Expected accessibility root query to return root node."
        }
        check(layer.accessibilityNode(SmokeAccessibilityProvider.BUTTON_ID)?.info?.name == "Smoke button") {
            "Expected accessibility node query to return button node."
        }
        check(layer.accessibilityNodeAt(24f, 24f)?.id == SmokeAccessibilityProvider.BUTTON_ID) {
            "Expected accessibility hit testing to return button node."
        }
        check(layer.performAccessibilityAction(SmokeAccessibilityProvider.BUTTON_ID, WinUIAccessibilityAction.CLICK)) {
            "Expected accessibility action to be delegated to provider."
        }
        check(accessibilityProvider.performedActions == listOf(WinUIAccessibilityAction.CLICK)) {
            "Expected provider to record delegated click action."
        }
        check(layer.moveAccessibilityFocus(WinUIAccessibilityFocusDirection.NEXT)) {
            "Expected accessibility focus navigation to move to next node."
        }
        check(layer.accessibilityFocusedNode()?.id == SmokeAccessibilityProvider.TEXT_ID) {
            "Expected accessibility focused node to update after focus navigation."
        }
        layer.accessibilityProvider = null
        check(layer.accessibilitySnapshot == null) {
            "Expected accessibility snapshot to clear with provider."
        }
        println("skiko-winui-mingw-smoke: layer api verified")
    }

    private fun runSmokeFrames(uiElement: UIElement, skiaLayer: WinUISkiaLayer) {
        println("skiko-winui-mingw-smoke: render callback")
        val timer = DispatcherQueue.getForCurrentThread().createTimer()
        frameTimer = timer
        timer.interval = 16.milliseconds
        timer.isRepeating = true
        var stage = 0
        var ticks = 0
        frameTimerTickToken = timer.tick.add(TypedEventHandler { _, _ ->
            try {
                ticks += 1
                check(ticks < 240) {
                    "Timed out waiting for skiko-winui mingw render callbacks; stage=$stage renderCount=$renderCount."
                }
                when (stage) {
                    0 -> {
                        forceLayout(uiElement, INITIAL_WIDTH, INITIAL_HEIGHT)
                        check(skiaLayer.width == INITIAL_WIDTH.toFloat() && skiaLayer.height == INITIAL_HEIGHT.toFloat()) {
                            "Expected initial WinUISkiaLayer size ${INITIAL_WIDTH}x${INITIAL_HEIGHT}, got ${skiaLayer.width}x${skiaLayer.height}."
                        }
                        skiaLayer.needRender(throttledToVsync = false)
                        stage = 1
                    }
                    1 -> {
                        if (renderCount < 1) {
                            return@TypedEventHandler
                        }
                        if (!options.verifyResize) {
                            timer.stop()
                            frameTimerTickToken?.let(timer.tick::remove)
                            frameTimerTickToken = null
                            frameTimer = null
                            verifyRenderDiagnostics(INITIAL_WIDTH.toFloat(), INITIAL_HEIGHT.toFloat())
                            verifyAutomationPeer(skiaLayer)
                            enqueueExit()
                            return@TypedEventHandler
                        }
                        println("skiko-winui-mingw-smoke: resize layer")
                        skiaLayer.component.width = RESIZED_WIDTH
                        skiaLayer.component.height = RESIZED_HEIGHT
                        forceLayout(uiElement, RESIZED_WIDTH, RESIZED_HEIGHT)
                        check(skiaLayer.width == RESIZED_WIDTH.toFloat() && skiaLayer.height == RESIZED_HEIGHT.toFloat()) {
                            "Expected resized WinUISkiaLayer size ${RESIZED_WIDTH}x${RESIZED_HEIGHT}, got ${skiaLayer.width}x${skiaLayer.height}."
                        }
                        skiaLayer.needRender(throttledToVsync = false)
                        stage = 2
                    }
                    2 -> {
                        if (renderCount < 2) {
                            return@TypedEventHandler
                        }
                        timer.stop()
                        frameTimerTickToken?.let(timer.tick::remove)
                        frameTimerTickToken = null
                        frameTimer = null
                        verifyRenderDiagnostics(RESIZED_WIDTH.toFloat(), RESIZED_HEIGHT.toFloat())
                        verifyAutomationPeer(skiaLayer)
                        enqueueExit()
                    }
                }
            } catch (throwable: Throwable) {
                throwable.printStackTrace()
                close()
                exitProcess(1)
            }
        })
        timer.start()
    }

    private fun forceLayout(uiElement: UIElement, width: Double, height: Double) {
        uiElement.measure(Size(width.toFloat(), height.toFloat()))
        uiElement.arrange(Rect(0f, 0f, width.toFloat(), height.toFloat()))
        uiElement.updateLayout()
    }

    private fun verifyRenderDiagnostics(expectedWidth: Float, expectedHeight: Float) {
        println("skiko-winui-mingw-smoke: verify render diagnostics")
        check(renderCount > 0) {
            "Expected at least one render before exit."
        }
        val diagnostics = layer.renderDiagnostics
        val renderedState = diagnostics.lastRenderedState
            ?: error("Expected render diagnostics to expose last rendered state.")
        val platformResult = diagnostics.lastPlatformResult
            ?: error("Expected render diagnostics to expose last platform result.")
        check(diagnostics.renderVersion > 0) {
            "Expected renderVersion to advance after render."
        }
        check(renderedState.logicalWidth == expectedWidth && renderedState.logicalHeight == expectedHeight) {
            "Expected diagnostics logical size ${expectedWidth}x${expectedHeight}, got ${renderedState.logicalWidth}x${renderedState.logicalHeight}."
        }
        check(renderedState.scaledWidth == platformResult.width && renderedState.scaledHeight == platformResult.height) {
            "Expected platform result size to match rendered state, got $platformResult and $renderedState."
        }
        check(diagnostics.lastFailure == null) {
            "Expected no render failure, got ${diagnostics.lastFailure}."
        }
        println(
            "skiko-winui-mingw-smoke: diagnostics renderVersion=${diagnostics.renderVersion} " +
            "platform=${platformResult.width}x${platformResult.height}"
        )
    }

    private fun verifyAutomationPeer(skiaLayer: WinUISkiaLayer) {
        println("skiko-winui-mingw-smoke: verify automation peer")
        skiaLayer.accessibilityProvider = SmokeAccessibilityProvider()
        val rootPeer = FrameworkElementAutomationPeer.createPeerForElement(skiaLayer.component)
        check(rootPeer.getName() == "Smoke root") {
            "Expected automation peer root name to come from accessibility root."
        }
        check(rootPeer.getAutomationControlType() == AutomationControlType.Pane) {
            "Expected automation peer root control type to be Pane."
        }
        val className = rootPeer.getClassName()
        check(className == "WinUISkiaLayer") {
            "Expected WinUISkiaLayer custom peer, got $className."
        }
        check(rootPeer.getAutomationId() == "skiko-winui-${SmokeAccessibilityProvider.ROOT_ID}") {
            "Expected automation peer root automation id fallback to include node id."
        }
        val children = rootPeer.getChildren()
        check(children.map { it.getName() }.containsAll(listOf("Smoke button", "Smoke text"))) {
            "Expected automation peer children to mirror the accessibility tree, got ${children.map { it.getName() }}."
        }
        val firstChild = rootPeer.navigate(AutomationNavigationDirection.FirstChild).toAutomationPeerOrNull()
            ?: error("Expected automation peer first-child navigation to return a peer.")
        check(firstChild.getName() == "Smoke button") {
            "Expected first child peer to be Smoke button."
        }
        val nextSibling = firstChild.navigate(AutomationNavigationDirection.NextSibling).toAutomationPeerOrNull()
            ?: error("Expected automation peer next-sibling navigation to return a peer.")
        check(nextSibling.getName() == "Smoke text") {
            "Expected next sibling peer to be Smoke text."
        }
        val hitPeerName = rootPeer.getPeerFromPoint(Point(24f, 24f)).getName()
        check(hitPeerName == "Smoke button") {
            "Expected peer hit-test to return Smoke button, got $hitPeerName."
        }
        println("skiko-winui-mingw-smoke: automation peer verified")
    }

    private fun Any?.toAutomationPeerOrNull(): AutomationPeer? {
        val winRtObject = this as? IWinRTObject ?: return null
        return winRtObject.nativeObject
            .queryInterface(AutomationPeer.Metadata.DEFAULT_INTERFACE_IID)
            .getOrNull()
            ?.use { peerReference ->
                IUnknownReference(
                    peerReference.getRefPointer(),
                    AutomationPeer.Metadata.DEFAULT_INTERFACE_IID,
                ).use(AutomationPeer.Metadata::wrap)
            }
    }

    private companion object {
        private const val INITIAL_WIDTH = 320.0
        private const val INITIAL_HEIGHT = 240.0
        private const val RESIZED_WIDTH = 480.0
        private const val RESIZED_HEIGHT = 360.0
    }
}

private data class SmokeOptions(
    val verifyResize: Boolean = true,
) {
    companion object {
        fun from(args: Array<String>): SmokeOptions =
            SmokeOptions(
                verifyResize = !args.contains("--skip-resize"),
            )
    }
}

private class CountingInputHandler : WinUIInputHandler {
    var pointerEvents = 0
        private set
    var keyEvents = 0
        private set
    var textInputEvents = 0
        private set
    var textCompositionEvents = 0
        private set
    var focusEvents = 0
        private set

    override fun onPointerEvent(event: WinUIPointerEvent): Boolean {
        pointerEvents += 1
        return false
    }

    override fun onKeyEvent(event: WinUIKeyEvent): Boolean {
        keyEvents += 1
        return false
    }

    override fun onTextInputEvent(event: WinUITextInputEvent): Boolean {
        textInputEvents += 1
        return false
    }

    override fun onTextCompositionEvent(event: WinUITextCompositionEvent): Boolean {
        textCompositionEvents += 1
        return false
    }

    override fun onFocusEvent(event: WinUIFocusEvent): Boolean {
        focusEvents += 1
        return false
    }
}

private class SmokeAccessibilityProvider : WinUIAccessibilityProvider {
    var performedActions = emptyList<WinUIAccessibilityAction>()
        private set
    private var focusedNodeId = BUTTON_ID

    override fun snapshot(): WinUIAccessibilitySnapshot = WinUIAccessibilitySnapshot(
        root = rootNode(),
        focusedNodeId = focusedNodeId,
    )

    override fun performAction(request: WinUIAccessibilityActionRequest): Boolean {
        val node = rootNode().children.firstOrNull { it.id == request.nodeId } ?: return false
        if (request.action !in node.actions) {
            return false
        }
        if (request.action == WinUIAccessibilityAction.FOCUS) {
            focusedNodeId = request.nodeId
        }
        performedActions = performedActions + request.action
        return true
    }

    private fun rootNode(): WinUIAccessibilityNode = WinUIAccessibilityNode(
        id = ROOT_ID,
        bounds = WinUIRect(x = 0f, y = 0f, width = 320f, height = 240f),
        info = WinUIAccessibilityInfo(name = "Smoke root", role = WinUIAccessibilityRole.PANE),
        children = listOf(
            WinUIAccessibilityNode(
                id = BUTTON_ID,
                bounds = WinUIRect(x = 16f, y = 16f, width = 96f, height = 48f),
                info = WinUIAccessibilityInfo(name = "Smoke button", role = WinUIAccessibilityRole.BUTTON),
                state = WinUIAccessibilityState(
                    focusable = true,
                    focused = focusedNodeId == BUTTON_ID,
                ),
                actions = setOf(
                    WinUIAccessibilityAction.CLICK,
                    WinUIAccessibilityAction.FOCUS,
                ),
            ),
            WinUIAccessibilityNode(
                id = TEXT_ID,
                bounds = WinUIRect(x = 16f, y = 72f, width = 160f, height = 32f),
                info = WinUIAccessibilityInfo(name = "Smoke text", role = WinUIAccessibilityRole.TEXT),
                state = WinUIAccessibilityState(
                    focusable = true,
                    focused = focusedNodeId == TEXT_ID,
                ),
                actions = setOf(WinUIAccessibilityAction.FOCUS),
            ),
        ),
    )

    companion object {
        const val ROOT_ID = 1L
        const val BUTTON_ID = 2L
        const val TEXT_ID = 3L
    }
}
