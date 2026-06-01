package org.jetbrains.skiko.winui

import microsoft.ui.xaml.automation.peers.AutomationPeer
import microsoft.ui.xaml.controls.Grid
import microsoft.ui.xaml.controls.SwapChainPanel
import windows.foundation.Rect
import windows.foundation.Size

class WinUISkiaHostPanel : Grid() {
    internal val renderPanel: SwapChainPanel = SwapChainPanel()
    internal var automationPeerCreateCount: Int = 0
        private set
    private var layer: WinUISkiaLayer? = null

    init {
        children?.add(renderPanel)
    }

    internal fun bindLayer(layer: WinUISkiaLayer) {
        check(this.layer == null || this.layer === layer) {
            "WinUISkiaHostPanel is already bound to another WinUISkiaLayer."
        }
        this.layer = layer
    }

    override fun onCreateAutomationPeer(): AutomationPeer {
        automationPeerCreateCount += 1
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
