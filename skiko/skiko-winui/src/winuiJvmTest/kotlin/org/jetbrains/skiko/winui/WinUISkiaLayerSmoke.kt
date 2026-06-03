package org.jetbrains.skiko.winui

import io.github.composefluent.winrt.runtime.ComVtableInvoker
import io.github.composefluent.winrt.runtime.Guid
import io.github.composefluent.winrt.runtime.HResult
import io.github.composefluent.winrt.runtime.RuntimeScope
import io.github.composefluent.winrt.runtime.WinRtWindowsAppSdkBootstrap
import microsoft.ui.dispatching.DispatcherQueue
import microsoft.ui.dispatching.DispatcherQueueTimer
import microsoft.ui.xaml.Application
import microsoft.ui.xaml.FrameworkElement
import microsoft.ui.xaml.UIElement
import microsoft.ui.xaml.Window
import microsoft.ui.xaml.automation.AutomationProperties
import microsoft.ui.xaml.automation.peers.AutomationControlType
import microsoft.ui.xaml.automation.peers.AutomationEvents
import microsoft.ui.xaml.automation.peers.AutomationPeer
import microsoft.ui.xaml.automation.peers.AutomationNavigationDirection
import microsoft.ui.xaml.automation.peers.AutomationStructureChangeType
import microsoft.ui.xaml.automation.peers.FrameworkElementAutomationPeer
import microsoft.ui.xaml.automation.peers.PatternInterface
import microsoft.ui.xaml.controls.SwapChainPanel
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skia.impl.Library
import windows.foundation.Point
import windows.foundation.Rect
import windows.foundation.Size
import windows.foundation.TypedEventHandler
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

object WinUISkiaLayerSmoke {
    private var activeSession: SmokeSession? = null

    @JvmStatic
    fun main(args: Array<String>) {
        println("skiko-winui-smoke: main")
        val options = SmokeOptions.from(args)
        println("skiko-winui-smoke: preload skiko native")
        Library.staticLoad()
        println("skiko-winui-smoke: skiko native preloaded")
        println("skiko-winui-smoke: verify native failure exception")
        WinUISkiaLayerNative.ensureLoaded()
        verifyNativeFailureException()
        println("skiko-winui-smoke: native failure exception verified")
        start(options)
    }

    private fun verifyNativeFailureException() {
        try {
            WinUISkiaLayerNative.throwRenderExceptionForSmoke("expected smoke failure")
            error("Expected WinUIRenderException from native smoke hook.")
        } catch (exception: WinUIRenderException) {
            check(exception.message?.contains("expected smoke failure") == true) {
                "Unexpected WinUIRenderException message: ${exception.message}"
            }
        }
    }

    private fun start(options: SmokeOptions = SmokeOptions()) {
        println("skiko-winui-smoke: bootstrap begin")
        WinRtWindowsAppSdkBootstrap.initialize().use { bootstrap ->
            println("skiko-winui-smoke: bootstrap=${bootstrap?.bootstrapDll ?: "not-found"}")
            println("skiko-winui-smoke: runtime scope begin")
            RuntimeScope.initializeSingleThreaded().use {
                println("skiko-winui-smoke: application start")
                Application.start {
                    println("skiko-winui-smoke: application callback")
                    val application = if (options.useApplicationCurrent) {
                        println("skiko-winui-smoke: use application current")
                        Application.current ?: Application()
                    } else {
                        Application()
                    }
                    println("skiko-winui-smoke: application pointer ${application.nativeObject.pointer.value}")
                    println(
                        "skiko-winui-smoke: current pointer " +
                            "${Application.current?.nativeObject?.pointer?.value ?: 0L}"
                    )
                    activeSession = SmokeSession(application, options).also { session ->
                        session.launch()
                    }
                }
            }
            println("skiko-winui-smoke: application returned")
            activeSession?.close()
            activeSession = null
        }
        println("skiko-winui-smoke: done")
    }
}

private class SmokeSession(
    private val application: Application,
    private val options: SmokeOptions,
) : AutoCloseable {
    private var window: Window? = null
    private var layer: WinUISkiaLayerSurface? = null
    private var windowBinding: WinUISkiaWindowBinding? = null
    private var focusTimer: DispatcherQueueTimer? = null
    private var focusTimerTickToken: io.github.composefluent.winrt.runtime.EventRegistrationToken? = null
    private var renderCount = 0
    private var failure: Throwable? = null

    fun launch() {
        println("skiko-winui-smoke: launched")
        println("skiko-winui-smoke: create raw panel")
        SwapChainPanel()
        println("skiko-winui-smoke: raw panel created")
        println("skiko-winui-smoke: load skiko native")
        Library.staticLoad()
        println("skiko-winui-smoke: skiko native loaded")
        println("skiko-winui-smoke: load native")
        WinUISkiaLayerNative.ensureLoaded()
        println("skiko-winui-smoke: native loaded")
        println("skiko-winui-smoke: create layer")
        var renderDepth = 0
        lateinit var skiaLayer: WinUISkiaLayerSurface
        skiaLayer = try {
            WinUISkiaLayer { canvas, width, height, _ ->
                renderDepth += 1
                check(renderDepth == 1) {
                    "WinUISkiaLayer.needRender() re-entered rendering recursively."
                }
                println("skiko-winui-smoke: render $width x $height")
                renderCount += 1
                try {
                    if (renderCount == 1) {
                        skiaLayer.needRender(throttledToVsync = false)
                    }
                    canvas.clear(Color.WHITE)
                    val paint = Paint()
                    try {
                        paint.isAntiAlias = true
                        paint.color = Color.makeRGB(0x20, 0x78, 0xD4)
                        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) * 0.25f, paint)
                    } finally {
                        paint.close()
                    }
                } finally {
                    renderDepth -= 1
                }
            }
        } catch (exception: Throwable) {
            println("skiko-winui-smoke: create layer failed ${exception::class.qualifiedName}: ${exception.message}")
            exception.printStackTrace()
            throw exception
        }
        println("skiko-winui-smoke: layer created")
        verifySkiaLayerSurfaceApi(skiaLayer)
        if (options.verifyInputHandler) {
            verifyInputHandlerApi(skiaLayer)
        }

        println("skiko-winui-smoke: create window")
        val winuiWindow = Window()
        println("skiko-winui-smoke: window created")
        if (options.useGeneratedWindowApi) {
            println("skiko-winui-smoke: generated title set")
            winuiWindow.title = "Skiko WinUI smoke"
        }
        val component = skiaLayer.component
        val frameworkElement: FrameworkElement = component
        frameworkElement.width = INITIAL_WIDTH
        frameworkElement.height = INITIAL_HEIGHT
        check(!skiaLayer.startFrameScheduler().isRunning) {
            "Expected unattached WinUISkiaLayer frame scheduler to wait until the layer is hosted."
        }
        skiaLayer.needRender(throttledToVsync = false)
        check(renderCount == 0) {
            "Expected unattached WinUISkiaLayer.needRender() to wait until hosted, got $renderCount renders."
        }
        skiaLayer.startFrameScheduler().close()
        if (options.useGeneratedWindowApi) {
            if (options.useLayerAttach) {
                println("skiko-winui-smoke: host layer with WinUISkiaLayer.attachTo")
                skiaLayer.attachTo(winuiWindow)
                skiaLayer.detach()
                skiaLayer.attachTo(winuiWindow)
                check(skiaLayer.startFrameScheduler().isRunning) {
                    "Expected WinUISkiaLayer frame scheduler to start after attachTo(Window)."
                }
                skiaLayer.startFrameScheduler().stop()
            } else {
                println("skiko-winui-smoke: host layer with generated window api")
                windowBinding = winuiWindow.hostWinUISkiaLayer(
                    layer = skiaLayer,
                    width = INITIAL_WIDTH,
                    height = INITIAL_HEIGHT,
                )
                check(windowBinding?.startFrameScheduler()?.isRunning == true) {
                    "Expected WinUISkiaWindowBinding frame scheduler to start."
                }
                windowBinding?.startFrameScheduler()?.stop()
            }
        } else {
            setWindowContent(winuiWindow, component)
        }
        println("skiko-winui-smoke: content set")

        layer = skiaLayer
        window = winuiWindow
        println("skiko-winui-smoke: activate window")
        if (options.useGeneratedWindowApi) {
            println("skiko-winui-smoke: generated activate")
            winuiWindow.activate()
        } else {
            activateWindow(winuiWindow)
        }
        println("skiko-winui-smoke: render after activate")
        runSmokeFrames(frameworkElement, component, skiaLayer)
    }

    override fun close() {
        closeResources()
        failure?.let { throw it }
    }

    private fun closeResources() {
        println("skiko-winui-smoke: close")
        focusTimer?.stop()
        focusTimerTickToken?.let { token ->
            focusTimer?.tick?.remove(token)
        }
        focusTimerTickToken = null
        focusTimer = null
        println("skiko-winui-smoke: close window binding")
        windowBinding?.close()
        windowBinding = null
        println("skiko-winui-smoke: close layer first")
        layer?.close()
        println("skiko-winui-smoke: close layer second")
        layer?.close()
        println("skiko-winui-smoke: layer closed")
        layer = null
        println("skiko-winui-smoke: close window")
        window?.close()
        println("skiko-winui-smoke: window closed")
        window = null
    }

    private fun runSmokeFrames(
        frameworkElement: FrameworkElement,
        uiElement: UIElement,
        skiaLayer: WinUISkiaLayerSurface,
    ) {
        try {
            println("skiko-winui-smoke: render callback")
            forceLayout(uiElement, INITIAL_WIDTH, INITIAL_HEIGHT)
            println(
                "skiko-winui-smoke: initial actual ${frameworkElement.actualWidth} x ${frameworkElement.actualHeight}"
            )
            check(skiaLayer.width == INITIAL_WIDTH.toFloat() && skiaLayer.height == INITIAL_HEIGHT.toFloat()) {
                "Expected WinUISkiaLayer size ${INITIAL_WIDTH}x${INITIAL_HEIGHT}, got ${skiaLayer.width}x${skiaLayer.height}."
            }
            println("skiko-winui-smoke: request focus")
            val invalidFocusRequested = skiaLayer.requestFocus(WinUIFocusState.UNFOCUSED)
            check(!invalidFocusRequested) {
                "Expected requestFocus(UNFOCUSED) to be rejected."
            }
            val focusRequested = skiaLayer.requestFocus(WinUIFocusState.KEYBOARD)
            println("skiko-winui-smoke: request keyboard focus result $focusRequested state ${skiaLayer.focusState}")
            skiaLayer.needRender(throttledToVsync = false)

            println("skiko-winui-smoke: resize panel")
            frameworkElement.width = RESIZED_WIDTH
            frameworkElement.height = RESIZED_HEIGHT
            forceLayout(uiElement, RESIZED_WIDTH, RESIZED_HEIGHT)
            println(
                "skiko-winui-smoke: resized actual ${frameworkElement.actualWidth} x ${frameworkElement.actualHeight}"
            )
            check(skiaLayer.width == RESIZED_WIDTH.toFloat() && skiaLayer.height == RESIZED_HEIGHT.toFloat()) {
                "Expected WinUISkiaLayer resized size ${RESIZED_WIDTH}x${RESIZED_HEIGHT}, got ${skiaLayer.width}x${skiaLayer.height}."
            }
            skiaLayer.needRender(throttledToVsync = false)

            check(renderCount >= 2) {
                "Expected at least two Skia render callbacks after initial render and resize, got $renderCount."
            }
            println("skiko-winui-smoke: render count $renderCount")
            if (skiaLayer is WinUISkiaLayer) {
                verifyAutomationPeer(skiaLayer)
            }
            if (options.verifyFocusAfterDispatcher) {
                println("skiko-winui-smoke: schedule dispatcher focus verification")
                scheduleDispatcherFocusVerification(skiaLayer)
                return
            }
            if (options.autoExit) {
                println("skiko-winui-smoke: auto exit")
                if (options.useApplicationExit) {
                    if (options.closeBeforeApplicationExit) {
                        close()
                    }
                    println("skiko-winui-smoke: application exit")
                    application.exit()
                    println("skiko-winui-smoke: application exit requested")
                } else {
                    close()
                    println("skiko-winui-smoke: exit process")
                    exitProcess(0)
                }
            }
        } catch (throwable: Throwable) {
            failure = throwable
            closeResources()
            if (options.autoExit) {
                throwable.printStackTrace()
                exitProcess(1)
            } else {
                application.exit()
            }
        }
    }

    private fun scheduleDispatcherFocusVerification(skiaLayer: WinUISkiaLayerSurface) {
        val focusHandler = CountingInputHandler()
        skiaLayer.inputHandler = focusHandler
        val timer = DispatcherQueue.getForCurrentThread().createTimer()
        focusTimer = timer
        timer.interval = 16.milliseconds
        timer.isRepeating = true
        var tickCount = 0
        var focusRequested = false
        focusTimerTickToken = timer.tick.add(TypedEventHandler { _, _ ->
            try {
                tickCount += 1
                if (tickCount == 1) {
                    val invalidFocusRequested = skiaLayer.requestFocus(WinUIFocusState.UNKNOWN)
                    check(!invalidFocusRequested) {
                        "Expected requestFocus(UNKNOWN) to be rejected."
                    }
                    focusRequested = skiaLayer.requestFocus(WinUIFocusState.KEYBOARD)
                    println(
                        "skiko-winui-smoke: dispatcher keyboard focus result " +
                            "$focusRequested state ${skiaLayer.focusState}"
                    )
                    check(focusRequested && skiaLayer.focusState == WinUIFocusState.KEYBOARD) {
                        "Expected dispatcher-delayed keyboard focus to succeed, got $focusRequested and ${skiaLayer.focusState}."
                    }
                    return@TypedEventHandler
                }

                timer.stop()
                focusTimerTickToken?.let(timer.tick::remove)
                focusTimerTickToken = null
                focusTimer = null

                check(focusRequested) {
                    "Expected dispatcher-delayed keyboard focus request to have succeeded."
                }
                check(focusHandler.focusEvents > 0) {
                    "Expected dispatcher-delayed keyboard focus to dispatch a focus event."
                }
                println("skiko-winui-smoke: focus handler events ${focusHandler.focusEvents}")

                if (options.autoExit) {
                    close()
                    println("skiko-winui-smoke: application exit")
                    application.exit()
                    println("skiko-winui-smoke: application exit requested")
                }
            } catch (throwable: Throwable) {
                failure = throwable
                closeResources()
                if (options.autoExit) {
                    throwable.printStackTrace()
                    exitProcess(1)
                } else {
                    application.exit()
                }
            }
        })
        timer.start()
    }

    private fun forceLayout(uiElement: UIElement, width: Double, height: Double) {
        uiElement.measure(Size(width.toFloat(), height.toFloat()))
        uiElement.arrange(Rect(0f, 0f, width.toFloat(), height.toFloat()))
        uiElement.updateLayout()
    }

    private fun verifyInputHandlerApi(skiaLayer: WinUISkiaLayerSurface) {
        println("skiko-winui-smoke: verify input handler api")
        val first = CountingInputHandler()
        val second = CountingInputHandler()
        skiaLayer.inputHandler = first
        check(skiaLayer.inputHandler === first) {
            "Expected first input handler to be installed."
        }
        skiaLayer.inputHandler = second
        check(skiaLayer.inputHandler === second) {
            "Expected second input handler to replace the first handler."
        }
        skiaLayer.inputHandler = null
        check(skiaLayer.inputHandler == null) {
            "Expected input handler to be clearable."
        }
        println("skiko-winui-smoke: input handler api verified")
    }

    private fun verifySkiaLayerSurfaceApi(skiaLayer: WinUISkiaLayerSurface) {
        println("skiko-winui-smoke: verify SkiaLayer surface api")
        check(skiaLayer.renderApi == GraphicsApi.DIRECT3D) {
            "Expected WinUISkiaLayer renderApi to be DIRECT3D, got ${skiaLayer.renderApi}."
        }
        skiaLayer.renderApi = GraphicsApi.DIRECT3D
        check(skiaLayer.pixelGeometry == PixelGeometry.UNKNOWN) {
            "Expected WinUISkiaLayer pixelGeometry to be UNKNOWN, got ${skiaLayer.pixelGeometry}."
        }
        check(skiaLayer.fullscreen) {
            "Expected WinUISkiaLayer fullscreen to report true."
        }
        val accessibilityInfo = WinUIAccessibilityInfo(
            name = "Skiko WinUI smoke surface",
            automationId = "SkikoWinUISmokeSurface",
            helpText = "Skiko WinUI smoke accessibility help",
            fullDescription = "Skiko WinUI smoke accessibility description",
            localizedControlType = "skia surface",
            view = WinUIAccessibilityView.CONTENT,
            liveSetting = WinUIAccessibilityLiveSetting.POLITE,
            role = WinUIAccessibilityRole.PANE,
        )
        skiaLayer.accessibilityInfo = accessibilityInfo
        check(skiaLayer.accessibilityInfo == accessibilityInfo) {
            "Expected WinUISkiaLayer accessibilityInfo to round-trip."
        }
        check(AutomationProperties.getName(skiaLayer.component) == accessibilityInfo.name) {
            "Expected WinUI AutomationProperties.Name to be updated."
        }
        check(AutomationProperties.getAutomationId(skiaLayer.component) == accessibilityInfo.automationId) {
            "Expected WinUI AutomationProperties.AutomationId to be updated."
        }
        check(AutomationProperties.getHelpText(skiaLayer.component) == accessibilityInfo.helpText) {
            "Expected WinUI AutomationProperties.HelpText to be updated."
        }
        val accessibilityProvider = SmokeAccessibilityProvider()
        skiaLayer.accessibilityProvider = accessibilityProvider
        check(skiaLayer.accessibilitySnapshot == accessibilityProvider.snapshot()) {
            "Expected WinUISkiaLayer accessibility snapshot to come from the provider."
        }
        check(skiaLayer.accessibilityDiagnostics.duplicateNodeIds.isEmpty()) {
            "Expected accessibility diagnostics to report no duplicate node ids."
        }
        check(!skiaLayer.accessibilityDiagnostics.focusedNodeMissing) {
            "Expected accessibility diagnostics to report focused node present."
        }
        check(AutomationProperties.getName(skiaLayer.component) == "Smoke root") {
            "Expected WinUI AutomationProperties.Name to follow the accessibility root node."
        }
        check(skiaLayer.accessibilityRootNode()?.id == SmokeAccessibilityProvider.ROOT_ID) {
            "Expected accessibility root query to return the root node."
        }
        check(skiaLayer.accessibilityNode(SmokeAccessibilityProvider.BUTTON_ID)?.info?.name == "Smoke button") {
            "Expected accessibility node query to return node metadata."
        }
        check(skiaLayer.accessibilityParent(SmokeAccessibilityProvider.BUTTON_ID)?.id == SmokeAccessibilityProvider.ROOT_ID) {
            "Expected accessibility parent query to return root for child node."
        }
        check(
            skiaLayer.accessibilityChildren(SmokeAccessibilityProvider.ROOT_ID).map { it.id } ==
                listOf(SmokeAccessibilityProvider.BUTTON_ID, SmokeAccessibilityProvider.TEXT_ID)
        ) {
            "Expected accessibility children query to preserve provider child order."
        }
        check(skiaLayer.accessibilityNodeAt(24f, 24f)?.id == SmokeAccessibilityProvider.BUTTON_ID) {
            "Expected accessibility hit testing to return the deepest matching node."
        }
        check(skiaLayer.performAccessibilityAction(SmokeAccessibilityProvider.BUTTON_ID, WinUIAccessibilityAction.CLICK)) {
            "Expected accessibility action to be delegated to provider."
        }
        check(accessibilityProvider.performedActions == listOf(WinUIAccessibilityAction.CLICK)) {
            "Expected provider to record delegated accessibility action."
        }
        check(skiaLayer.accessibilityFocusedNode()?.id == SmokeAccessibilityProvider.BUTTON_ID) {
            "Expected accessibility focused node to come from the provider snapshot."
        }
        check(skiaLayer.moveAccessibilityFocus(WinUIAccessibilityFocusDirection.NEXT)) {
            "Expected accessibility focus navigation to move to the next focusable node."
        }
        check(skiaLayer.accessibilityFocusedNode()?.id == SmokeAccessibilityProvider.TEXT_ID) {
            "Expected accessibility focus navigation to refresh the focused node snapshot."
        }
        check(skiaLayer.requestAccessibilityFocus(SmokeAccessibilityProvider.BUTTON_ID)) {
            "Expected accessibility focus request to delegate to provider."
        }
        check(skiaLayer.accessibilityFocusedNode()?.id == SmokeAccessibilityProvider.BUTTON_ID) {
            "Expected accessibility focus request to refresh the focused node snapshot."
        }
        if (skiaLayer is WinUISkiaLayer) {
            val versionBeforeDiff = skiaLayer.accessibilityChangeVersion
            accessibilityProvider.textValue = "updated text"
            accessibilityProvider.includeExtraNode = true
            skiaLayer.invalidateAccessibility()
            val diffChanges = skiaLayer.consumeAccessibilityChanges(versionBeforeDiff)
            check(diffChanges.version > versionBeforeDiff) {
                "Expected accessibility diff change version to advance."
            }
            check(diffChanges.changes.any {
                it.change.type == WinUIAccessibilityChangeType.TEXT_CHANGED &&
                    it.change.nodeId == SmokeAccessibilityProvider.TEXT_ID &&
                    it.change.newValue == "updated text"
            }) {
                "Expected accessibility snapshot diff to record text changes."
            }
            check(diffChanges.changes.any {
                it.change.type == WinUIAccessibilityChangeType.NODE_ADDED &&
                    it.change.nodeId == SmokeAccessibilityProvider.EXTRA_ID
            }) {
                "Expected accessibility snapshot diff to record added nodes."
            }
            val automationModel = skiaLayer.accessibilityAutomationModel
            val rootPeer = automationModel.root()
            check(rootPeer?.nodeId == SmokeAccessibilityProvider.ROOT_ID) {
                "Expected automation model to expose the root node."
            }
            val buttonPeer = automationModel.node(SmokeAccessibilityProvider.BUTTON_ID)
                ?: error("Expected automation model to expose the button node.")
            check(buttonPeer.controlType == AutomationControlType.Button) {
                "Expected automation model to map button role to AutomationControlType.Button."
            }
            check(PatternInterface.Invoke in buttonPeer.patterns) {
                "Expected automation model to expose Invoke pattern for clickable nodes."
            }
            check(
                automationModel.patternProvider(
                    SmokeAccessibilityProvider.BUTTON_ID,
                    PatternInterface.Invoke,
                )?.nodeId == SmokeAccessibilityProvider.BUTTON_ID
            ) {
                "Expected automation model to return a pattern provider for supported patterns."
            }
            check(automationModel.childrenOf(SmokeAccessibilityProvider.ROOT_ID).map { it.nodeId }.contains(SmokeAccessibilityProvider.EXTRA_ID)) {
                "Expected automation model children to reflect the current snapshot."
            }
            check(automationModel.hitTest(24f, 24f)?.nodeId == SmokeAccessibilityProvider.BUTTON_ID) {
                "Expected automation model hit-test to use Skiko accessibility bounds."
            }
            check(automationModel.invoke(SmokeAccessibilityProvider.BUTTON_ID)) {
                "Expected automation model invoke to dispatch to the provider."
            }
            val automationEvents = automationModel.changes(versionBeforeDiff)
            check(automationEvents.events.any {
                it.event == AutomationEvents.TextPatternOnTextChanged &&
                    it.nodeId == SmokeAccessibilityProvider.TEXT_ID
            }) {
                "Expected automation model to map text changes to UIA text events."
            }
            check(automationEvents.events.any {
                it.event == AutomationEvents.StructureChanged &&
                    it.structureChangeType == AutomationStructureChangeType.ChildAdded &&
                    it.nodeId == SmokeAccessibilityProvider.EXTRA_ID
            }) {
                "Expected automation model to map added nodes to UIA child-added events."
            }

            val versionBeforeChange = diffChanges.version
            skiaLayer.notifyAccessibilityChanged(
                WinUIAccessibilityChange(
                    type = WinUIAccessibilityChangeType.FOCUS_CHANGED,
                    nodeId = SmokeAccessibilityProvider.BUTTON_ID,
                )
            )
            val changes = skiaLayer.consumeAccessibilityChanges(versionBeforeChange)
            check(changes.version > versionBeforeChange) {
                "Expected accessibility change version to advance."
            }
            check(changes.changes.any {
                it.version > versionBeforeChange &&
                    it.change.type == WinUIAccessibilityChangeType.FOCUS_CHANGED &&
                    it.change.nodeId == SmokeAccessibilityProvider.BUTTON_ID
            }) {
                "Expected accessibility focus change to be recorded."
            }
            val sameChangesAgain = skiaLayer.consumeAccessibilityChanges(versionBeforeChange)
            check(sameChangesAgain.changes == changes.changes) {
                "Expected accessibility change history reads to be stable for the same version."
            }
            check(skiaLayer.consumeAccessibilityChanges(changes.version).changes.isEmpty()) {
                "Expected accessibility change history to return no changes after the latest version."
            }
        }
        skiaLayer.accessibilityProvider = null
        check(skiaLayer.accessibilitySnapshot == null) {
            "Expected accessibility snapshot to clear with provider."
        }
        check(AutomationProperties.getName(skiaLayer.component) == accessibilityInfo.name) {
            "Expected WinUI AutomationProperties.Name to fall back to layer accessibilityInfo."
        }
        if (skiaLayer is WinUISkiaLayer) {
            Surface.makeRasterN32Premul(1, 1).use { surface ->
                try {
                    skiaLayer.draw(surface.canvas)
                    error("Expected WinUISkiaLayer.draw(canvas) outside native render to fail.")
                } catch (exception: IllegalStateException) {
                    check(exception.message?.contains("inside native render") == true) {
                        "Unexpected draw failure message: ${exception.message}"
                    }
                }
            }
        }
        println("skiko-winui-smoke: SkiaLayer surface api verified")
    }

    private fun verifyAutomationPeer(skiaLayer: WinUISkiaLayer) {
        println("skiko-winui-smoke: verify automation peer")
        skiaLayer.accessibilityProvider = SmokeAccessibilityProvider().also { provider ->
            provider.textValue = "updated text"
            provider.includeExtraNode = true
        }
        val rootPeer = FrameworkElementAutomationPeer.createPeerForElement(skiaLayer.component)
        println("skiko-winui-smoke: automation peer getName")
        check(rootPeer.getName() == "Smoke root") {
            "Expected automation peer root name to come from accessibility root."
        }
        println("skiko-winui-smoke: automation peer getAutomationControlType")
        check(rootPeer.getAutomationControlType() == AutomationControlType.Pane) {
            "Expected automation peer root control type to be Pane."
        }
        println("skiko-winui-smoke: automation peer getClassName")
        val className = rootPeer.getClassName()
        println("skiko-winui-smoke: automation peer className $className")
        check(className == "WinUISkiaLayer") {
            "Expected WinUISkiaLayer custom peer, got $className. " +
                "onCreateAutomationPeer calls=${skiaLayer.automationPeerCreateCount}."
        }
        println("skiko-winui-smoke: automation peer getAutomationId")
        check(rootPeer.getAutomationId() == "skiko-winui-${SmokeAccessibilityProvider.ROOT_ID}") {
            "Expected automation peer root automation id fallback to include node id."
        }
        println("skiko-winui-smoke: automation peer isContentElement/isControlElement")
        check(rootPeer.isContentElement() && rootPeer.isControlElement()) {
            "Expected automation peer root to be both content and control element."
        }
        println("skiko-winui-smoke: automation peer getChildren")
        val children = rootPeer.getChildren()
        check(children.map { it.getName() }.containsAll(listOf("Smoke button", "Smoke text", "Smoke extra"))) {
            "Expected automation peer children to mirror the accessibility tree, got ${children.map { it.getName() }}."
        }
        val firstChild = rootPeer.navigate(AutomationNavigationDirection.FirstChild) as? AutomationPeer
            ?: error("Expected automation peer first-child navigation to return a peer.")
        check(firstChild.getName() == "Smoke button") {
            "Expected first child peer to be Smoke button."
        }
        val nextSibling = firstChild.navigate(AutomationNavigationDirection.NextSibling) as? AutomationPeer
            ?: error("Expected automation peer next-sibling navigation to return a peer.")
        check(nextSibling.getName() == "Smoke text") {
            "Expected next sibling peer to be Smoke text."
        }
        WinUISkiaAutomationPeer.resetDiagnostics()
        val hitPeer = rootPeer.getPeerFromPoint(Point(24f, 24f))
        val hitPeerName = hitPeer.getName()
        val hitPoint = WinUISkiaAutomationPeer.lastPeerFromPoint
        println(
            "skiko-winui-smoke: automation peer hit-test $hitPeerName " +
                "calls=${WinUISkiaAutomationPeer.peerFromPointCallCount} point=${hitPoint?.x},${hitPoint?.y}"
        )
        check(hitPeerName == "Smoke button") {
            "Expected peer hit-test to return Smoke button, got $hitPeerName. " +
                "calls=${WinUISkiaAutomationPeer.peerFromPointCallCount}, point=${hitPoint?.x},${hitPoint?.y}."
        }
        val focusedPeer = rootPeer.getFocusedElement() as? AutomationPeer
            ?: error("Expected focused automation element to be a peer.")
        check(focusedPeer.getName() == "Smoke button" && focusedPeer.hasKeyboardFocus()) {
            "Expected focused automation peer to be Smoke button."
        }
        println("skiko-winui-smoke: automation peer verified")
    }

    private fun setWindowContent(window: Window, content: UIElement) {
        val iContent = content.nativeObject.queryInterface(IUIELEMENT_IID).getOrThrow()
        iContent.use { contentReference ->
            withIWindow(window) { windowReference ->
                HResult(
                    ComVtableInvoker.invokeArgs(
                        windowReference.pointer,
                        IWINDOW_CONTENT_SETTER_SLOT,
                        contentReference.pointer,
                    )
                ).requireSuccess("IWindow.Content setter")
            }
        }
    }

    private fun activateWindow(window: Window) {
        withIWindow(window) { windowReference ->
            HResult(
                ComVtableInvoker.invoke(
                    windowReference.pointer,
                    IWINDOW_ACTIVATE_SLOT,
                )
            ).requireSuccess("IWindow.Activate")
        }
    }

    private inline fun withIWindow(window: Window, block: (io.github.composefluent.winrt.runtime.IUnknownReference) -> Unit) {
        val iWindow = window.nativeObject.queryInterface(IWINDOW_IID).getOrThrow()
        iWindow.use { windowReference ->
            io.github.composefluent.winrt.runtime.IUnknownReference(
                windowReference.getRefPointer(),
                IWINDOW_IID,
            ).use(block)
        }
    }

    private companion object {
        private const val INITIAL_WIDTH = 320.0
        private const val INITIAL_HEIGHT = 240.0
        private const val RESIZED_WIDTH = 480.0
        private const val RESIZED_HEIGHT = 360.0
        private const val IWINDOW_CONTENT_SETTER_SLOT = 9
        private const val IWINDOW_ACTIVATE_SLOT = 26
        private val IWINDOW_IID = Guid("61F0EC79-5D52-56B5-86FB-40FA4AF288B0")
        private val IUIELEMENT_IID = Guid("C3C01020-320C-5CF6-9D24-D396BBFA4D8B")
    }
}

private class SmokeAccessibilityProvider : WinUIAccessibilityProvider {
    var performedActions = emptyList<WinUIAccessibilityAction>()
        private set
    var textValue = "initial text"
    var includeExtraNode = false
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
        bounds = WinUIRect(0f, 0f, 320f, 240f),
        info = WinUIAccessibilityInfo(
            name = "Smoke root",
            role = WinUIAccessibilityRole.PANE,
        ),
        children = listOfNotNull(
            WinUIAccessibilityNode(
                id = BUTTON_ID,
                bounds = WinUIRect(16f, 16f, 96f, 48f),
                info = WinUIAccessibilityInfo(
                    name = "Smoke button",
                    role = WinUIAccessibilityRole.BUTTON,
                ),
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
                bounds = WinUIRect(16f, 72f, 160f, 32f),
                info = WinUIAccessibilityInfo(
                    name = "Smoke text",
                    role = WinUIAccessibilityRole.TEXT,
                ),
                state = WinUIAccessibilityState(
                    focusable = true,
                    focused = focusedNodeId == TEXT_ID,
                ),
                value = textValue,
                actions = setOf(WinUIAccessibilityAction.FOCUS),
            ),
            if (includeExtraNode) {
                WinUIAccessibilityNode(
                    id = EXTRA_ID,
                    bounds = WinUIRect(16f, 112f, 120f, 32f),
                    info = WinUIAccessibilityInfo(
                        name = "Smoke extra",
                        role = WinUIAccessibilityRole.TEXT,
                    ),
                )
            } else {
                null
            },
        ),
    )

    companion object {
        const val ROOT_ID = 1L
        const val BUTTON_ID = 2L
        const val TEXT_ID = 3L
        const val EXTRA_ID = 4L
    }
}

private data class SmokeOptions(
    val autoExit: Boolean = true,
    val useGeneratedWindowApi: Boolean = true,
    val useApplicationExit: Boolean = true,
    val useApplicationCurrent: Boolean = false,
    val closeBeforeApplicationExit: Boolean = true,
    val verifyInputHandler: Boolean = true,
    val verifyFocusAfterDispatcher: Boolean = false,
    val useLayerAttach: Boolean = false,
) {
    companion object {
        fun from(args: Array<String>): SmokeOptions =
            SmokeOptions(
                autoExit = !args.contains("--keep-open"),
                useGeneratedWindowApi = !args.contains("--direct-window-vtable"),
                useApplicationExit = !args.contains("--use-process-exit"),
                useApplicationCurrent = args.contains("--use-application-current"),
                closeBeforeApplicationExit = !args.contains("--application-exit-before-close"),
                verifyInputHandler = !args.contains("--skip-input-handler-check"),
                verifyFocusAfterDispatcher = args.contains("--verify-focus-after-dispatcher"),
                useLayerAttach = args.contains("--use-layer-attach"),
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
