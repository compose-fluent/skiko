package org.jetbrains.skiko.winui

import microsoft.ui.xaml.FrameworkElement
import microsoft.ui.xaml.UIElement
import microsoft.ui.xaml.Window
import windows.foundation.EventRegistrationToken

class WinUISkiaWindowBinding internal constructor(
    val window: Window,
    val layer: WinUISkiaLayerSurface,
    private val previousContent: UIElement?,
    private val closeLayerOnWindowClosed: Boolean,
    private var indirectPointerInputBinding: WinUIIndirectPointerInputBinding? = null,
) : AutoCloseable {
    private var closedToken: EventRegistrationToken? = null
    private var frameScheduler: WinUIFrameScheduler? = null
    private var isClosed = false

    internal fun bindClosedEvent() {
        if (closeLayerOnWindowClosed && closedToken == null) {
            closedToken = window.closed.add { _, _ ->
                close()
            }
        }
    }

    override fun close() {
        if (isClosed) {
            return
        }
        indirectPointerInputBinding?.close()
        indirectPointerInputBinding = null
        isClosed = true
        closedToken?.let(window.closed::remove)
        closedToken = null
        frameScheduler?.close()
        frameScheduler = null
        if (window.content == layer.component) {
            window.content = previousContent
        }
        if (closeLayerOnWindowClosed) {
            layer.close()
        }
    }

    fun frameScheduler(): WinUIFrameScheduler {
        check(!isClosed) { "WinUISkiaWindowBinding is closed" }
        return frameScheduler ?: createFrameScheduler().also { scheduler ->
            frameScheduler = scheduler
        }
    }

    fun startFrameScheduler(): WinUIFrameScheduler =
        frameScheduler().also { scheduler ->
            scheduler.start()
        }

    fun stopFrameScheduler() {
        frameScheduler?.stop()
    }

    private fun createFrameScheduler(): WinUIFrameScheduler {
        val skiaLayer = layer as? WinUISkiaLayer
        return if (skiaLayer == null) {
            WinUIFrameScheduler(layer)
        } else {
            WinUIFrameScheduler(
                layer = layer,
                dispatcherQueue = skiaLayer.dispatcherQueue,
            )
        }
    }
}

fun Window.hostWinUISkiaLayer(
    layer: WinUISkiaLayerSurface,
    width: Double? = null,
    height: Double? = null,
    closeLayerOnWindowClosed: Boolean = true,
    enableIndirectPointerInput: Boolean = false,
): WinUISkiaWindowBinding {
    val component = layer.component
    val frameworkElement: FrameworkElement = component
    val previousContent = content
    if (width != null) {
        frameworkElement.width = width
    }
    if (height != null) {
        frameworkElement.height = height
    }
    content = component
    val indirectPointerInputBinding = if (enableIndirectPointerInput) {
        bindWinUIIndirectPointerInput(layer)
    } else {
        null
    }
    return WinUISkiaWindowBinding(
        window = this,
        layer = layer,
        previousContent = previousContent,
        closeLayerOnWindowClosed = closeLayerOnWindowClosed,
        indirectPointerInputBinding = indirectPointerInputBinding,
    ).also { binding ->
        binding.bindClosedEvent()
    }
}
