package SkiaWinUISample

import io.github.composefluent.winrt.runtime.RuntimeScope
import io.github.composefluent.winrt.runtime.WinRtWindowsAppSdkBootstrap
import microsoft.ui.xaml.Application
import microsoft.ui.xaml.Window
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.winui.WinUIFocusEvent
import org.jetbrains.skiko.winui.WinUIInputHandler
import org.jetbrains.skiko.winui.WinUIKeyEvent
import org.jetbrains.skiko.winui.WinUIPointerEvent
import org.jetbrains.skiko.winui.WinUITextInputEvent
import org.jetbrains.skiko.winui.WinUISkiaLayer
import org.jetbrains.skiko.winui.WinUISkiaLayerRenderDelegate
import windows.foundation.TypedEventHandler
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

fun main() {
    WinRtWindowsAppSdkBootstrap.initialize().use {
        RuntimeScope.initializeSingleThreaded().use {
            Application.start {
                val application = Application.current ?: Application()
                val scene = ClockScene()
                val layer = WinUISkiaLayer()
                layer.renderDelegate = WinUISkiaLayerRenderDelegate(layer, scene)
                layer.inputHandler = scene
                layer.component.width = 800.0
                layer.component.height = 600.0

                val window = Window()
                window.title = "Skia WinUI Sample"
                layer.attachTo(window)

                window.closed.add(TypedEventHandler { _, _ ->
                    layer.close()
                    application.exit()
                })

                window.activate()
                layer.needRender(throttledToVsync = false)
                layer.requestFocus()
                layer.startFrameScheduler()
            }
        }
    }
}

private class ClockScene : SkikoRenderDelegate, WinUIInputHandler {
    private var frame = 0
    private var inputSummary = "Input: move pointer or press a key"

    override fun onPointerEvent(event: WinUIPointerEvent): Boolean {
        inputSummary = buildString {
            append("Pointer ${event.type} id=${event.pointerId} ")
            append("x=${event.x.toInt()} y=${event.y.toInt()} ")
            append("device=${event.deviceType}")
            if (event.wheelDelta != 0) append(" wheel=${event.wheelDelta}")
        }
        return false
    }

    override fun onKeyEvent(event: WinUIKeyEvent): Boolean {
        inputSummary = if (event.pressed) {
            "Key down key=${event.keyCode} scan=${event.scanCode} repeat=${event.repeatCount}"
        } else {
            "Key up key=${event.keyCode} scan=${event.scanCode}"
        }
        return false
    }

    override fun onTextInputEvent(event: WinUITextInputEvent): Boolean {
        inputSummary = "Text '${event.character}' code=${event.codePoint} scan=${event.scanCode} repeat=${event.repeatCount}"
        return false
    }

    override fun onFocusEvent(event: WinUIFocusEvent): Boolean {
        inputSummary = "Focus ${if (event.focused) "gained" else "lost"} state=${event.focusState}"
        return false
    }

    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        canvas.clear(Color.WHITE)
        Paint().use { paint ->
            paint.isAntiAlias = true
            paint.color = Color.makeRGB(0x20, 0x78, 0xD4)
            val radius = min(width, height) * 0.34f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, radius, paint)

            paint.color = Color.WHITE
            for (index in 0 until 12) {
                val angle = (index / 12.0) * 2.0 * PI
                canvas.drawCircle(
                    cx + (radius * 0.82f * sin(angle)).toFloat(),
                    cy - (radius * 0.82f * cos(angle)).toFloat(),
                    4f,
                    paint,
                )
            }

            val seconds = (nanoTime / 1_000_000_000.0) % 60.0
            val secondAngle = seconds / 60.0 * 2.0 * PI
            paint.strokeWidth = 5f
            canvas.drawLine(
                cx,
                cy,
                cx + (radius * 0.72f * sin(secondAngle)).toFloat(),
                cy - (radius * 0.72f * cos(secondAngle)).toFloat(),
                paint,
            )

            paint.color = Color.makeRGB(0x18, 0x18, 0x18)
            canvas.drawString("Skiko WinUI DIRECT3D  frame ${frame++}", 24f, 42f, Font(), paint)
            canvas.drawString(inputSummary, 24f, (height - 28).coerceAtLeast(58).toFloat(), Font(), paint)
        }
    }
}
