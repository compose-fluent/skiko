package org.jetbrains.skiko.winui

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkikoRenderDelegate

open class WinUISkiaLayerRenderDelegate(
    val layer: WinUISkiaLayerSurface,
    val renderDelegate: SkikoRenderDelegate,
) : SkikoRenderDelegate {
    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        val contentScale = layer.contentScale
        canvas.scale(contentScale, contentScale)
        renderDelegate.onRender(
            canvas = canvas,
            width = (width / contentScale).toInt(),
            height = (height / contentScale).toInt(),
            nanoTime = nanoTime,
        )
    }
}
