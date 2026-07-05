package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.Redrawer

actual fun setSystemLookAndFeel() = Unit

actual val currentSystemTheme: SystemTheme
    get() = SystemTheme.UNKNOWN

internal actual fun makeDefaultRenderFactory(): RenderFactory =
    RenderFactory { _, renderApi, _, _ ->
        object : Redrawer {
            override fun dispose() = Unit

            override fun needRender(throttledToVsync: Boolean) = Unit

            override fun renderImmediately() {
                throw UnsupportedOperationException("skiko-winui does not use the Skiko AWT redrawer for $renderApi.")
            }

            override fun update(nanoTime: Long) = Unit

            override val renderInfo: String = "skiko-winui"

            override fun isTransparentBackgroundSupported(): Boolean = false
        }
    }
