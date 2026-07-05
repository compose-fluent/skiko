package org.jetbrains.skiko

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.PixelGeometry

actual open class SkiaLayer {
    actual var renderApi: GraphicsApi = GraphicsApi.UNKNOWN

    actual val contentScale: Float
        get() = 1.0f

    actual val pixelGeometry: PixelGeometry = PixelGeometry.UNKNOWN

    actual var fullscreen: Boolean = false

    actual val component: Any?
        get() = null

    actual var renderDelegate: SkikoRenderDelegate? = null

    actual fun attachTo(container: Any) {
        throw UnsupportedOperationException("skiko-winui hosts rendering through WinUISkiaLayer.")
    }

    actual fun detach() = Unit

    actual fun needRender(throttledToVsync: Boolean) = Unit

    actual fun needRedraw() {
        needRender()
    }

    actual internal fun draw(canvas: Canvas) {
        throw UnsupportedOperationException("skiko-winui hosts rendering through WinUISkiaLayer.")
    }
}
