package org.jetbrains.skiko.winui

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.SkikoRenderDelegate

internal fun verifyFrameCanvasIsolation(layer: WinUISkiaLayer) {
    val previousDelegate = layer.renderDelegate
    try {
        Surface.makeRasterN32Premul(64, 64).use { surface ->
            Bitmap().use { pixels ->
                Paint().use { paint ->
                    check(pixels.allocN32Pixels(64, 64))
                    paint.color = Color.BLUE
                    val scaledDelegate = SkikoRenderDelegate { canvas, _, _, _ ->
                        // Like the clock demo's DPI adapter, the delegate scales on every frame.
                        canvas.scale(2f, 2f)
                        canvas.drawRect(Rect.makeXYWH(4f, 4f, 8f, 8f), paint)
                    }
                    fun renderFrame() {
                        layer.inDrawScope(64, 64, 2f, 0L) {
                            layer.renderInto(surface.canvas, 0L)
                        }
                    }
                    fun verifyPixels() {
                        surface.canvas.clear(Color.WHITE)
                        renderFrame()
                        check(surface.readPixels(pixels, 0, 0))
                        check(pixels.getColor(9, 9) == Color.BLUE && pixels.getColor(25, 25) == Color.WHITE) {
                            "A previous frame changed the scale or clip of the reused canvas."
                        }
                    }
                    layer.renderDelegate = scaledDelegate
                    repeat(12) { verifyPixels() }

                    val saveCount = surface.canvas.saveCount
                    val expectedFailure = IllegalStateException("Expected frame failure")
                    layer.renderDelegate = SkikoRenderDelegate { canvas, _, _, _ ->
                        canvas.save()
                        canvas.translate(40f, 40f)
                        canvas.clipRect(Rect.makeXYWH(0f, 0f, 1f, 1f))
                        throw expectedFailure
                    }
                    check(runCatching { renderFrame() }.exceptionOrNull() === expectedFailure)
                    check(surface.canvas.saveCount == saveCount) {
                        "A failed frame left saved canvas state behind."
                    }
                    layer.renderDelegate = scaledDelegate
                    verifyPixels()
                }
            }
        }
    } finally {
        layer.renderDelegate = previousDelegate
    }
    println("skiko-winui-smoke: frame canvas isolation verified")
}
