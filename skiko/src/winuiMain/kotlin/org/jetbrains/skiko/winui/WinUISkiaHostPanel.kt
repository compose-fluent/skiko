package org.jetbrains.skiko.winui

import microsoft.ui.xaml.FrameworkElement
import microsoft.ui.xaml.automation.peers.AutomationPeer
import microsoft.ui.xaml.controls.Grid
import microsoft.ui.xaml.controls.SwapChainPanel
import windows.foundation.Rect
import windows.foundation.Size

internal interface WinUISkiaHost {
    val component: FrameworkElement
    val renderPanel: SwapChainPanel
    val automationPeerCreateCount: Int

    fun bindLayer(layer: WinUISkiaLayer)
}

class WinUISkiaHostPanel : Grid(), WinUISkiaHost {
    override val component: FrameworkElement
        get() = this

    override val renderPanel: SwapChainPanel = SwapChainPanel()

    internal var automationPeerCreatedCount: Int = 0
        private set

    override val automationPeerCreateCount: Int
        get() = automationPeerCreatedCount

    private var layer: WinUISkiaLayer? = null

    init {
        renderPanel.opacity = 0.999999
        children?.add(renderPanel)
    }

    override fun bindLayer(layer: WinUISkiaLayer) {
        check(this.layer == null || this.layer === layer) {
            "WinUISkiaHostPanel is already bound to another WinUISkiaLayer."
        }
        this.layer = layer
    }

    override fun onCreateAutomationPeer(): AutomationPeer {
        automationPeerCreatedCount += 1
        return layer?.let(::WinUISkiaAutomationPeer) ?: super.onCreateAutomationPeer()
    }

    override fun measureOverride(availableSize: Size): Size {
        renderPanel.measure(availableSize)
        return availableSize
    }

    override fun arrangeOverride(finalSize: Size): Size {
        renderPanel.arrange(Rect(0f, 0f, finalSize.width, finalSize.height))
        return finalSize
    }
}

internal expect fun createWinUISkiaHost(): WinUISkiaHost
