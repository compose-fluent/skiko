package SkiaWinUISample

import microsoft.ui.xaml.Application
import microsoft.ui.xaml.HorizontalAlignment
import microsoft.ui.xaml.LaunchActivatedEventArgs
import microsoft.ui.xaml.VerticalAlignment
import microsoft.ui.xaml.Window
import microsoft.ui.xaml.media.MicaBackdrop
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.winui.WinUIFocusEvent
import org.jetbrains.skiko.winui.WinUIFocusState
import org.jetbrains.skiko.winui.WinUIInputHandler
import org.jetbrains.skiko.winui.WinUIKeyEvent
import org.jetbrains.skiko.winui.WinUIPointerEvent
import org.jetbrains.skiko.winui.WinUIPointerEventType
import org.jetbrains.skiko.winui.WinUIDispatcherTimer
import org.jetbrains.skiko.winui.WinUISkiaLayer
import org.jetbrains.skiko.winui.WinUISkiaLayerRenderDelegate
import org.jetbrains.skiko.winui.WinUITextInputEvent
import windows.foundation.TypedEventHandler
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    main(emptyArray())
}

fun main(args: Array<String>) {
    sampleOptions = SampleOptions.from(args)
    Application.start {
        SkiaWinUISampleApp()
    }
}

class SkiaWinUISampleApp : Application() {
    override fun onLaunched(args: LaunchActivatedEventArgs) {
        activeApplication = this
        val skiaLayer = WinUISkiaLayer()
        val scene = ClocksWinUI(
            renderProvider = { skiaLayer.renderApi },
        )
        skiaLayer.renderDelegate = WinUISkiaLayerRenderDelegate(skiaLayer, scene)
        skiaLayer.inputHandler = scene
        skiaLayer.component.horizontalAlignment = HorizontalAlignment.Stretch
        skiaLayer.component.verticalAlignment = VerticalAlignment.Stretch

        val winuiWindow = Window()
        winuiWindow.title = "Skia WinUI Sample"
        winuiWindow.systemBackdrop = MicaBackdrop()
        skiaLayer.attachTo(winuiWindow)

        winuiWindow.closed.add(TypedEventHandler { _, _ ->
            closeSample(closeWindow = false)
        })

        activeWindow = winuiWindow
        activeLayer = skiaLayer
        winuiWindow.activate()
        skiaLayer.needRender(throttledToVsync = false)
        skiaLayer.requestFocus()
        if (sampleOptions.autoExit) {
            startAutoExit()
        } else {
            skiaLayer.startFrameScheduler()
        }
    }
}

private data class SampleOptions(
    val autoExit: Boolean,
) {
    companion object {
        fun from(args: Array<String>): SampleOptions = SampleOptions(
            autoExit = "--auto-exit" in args || isSampleAutoExitRequested(),
        )
    }
}

private var sampleOptions: SampleOptions = SampleOptions(autoExit = false)
private var activeApplication: Application? = null
private var activeWindow: Window? = null
private var activeLayer: WinUISkiaLayer? = null
private var activeExitTimer: WinUIDispatcherTimer? = null
private var activeExitTimeoutTimer: WinUIDispatcherTimer? = null
private var closingSample = false

private fun startAutoExit() {
    println("skia-winui-sample: auto exit scheduled")
    activeExitTimer = WinUIDispatcherTimer(
        interval = 750.milliseconds,
        repeating = false,
    ) {
        println("skia-winui-sample: auto exit")
        closeSample(closeWindow = true)
    }.also { timer ->
        timer.start()
    }
    activeExitTimeoutTimer = WinUIDispatcherTimer(
        interval = 5_000.milliseconds,
        repeating = false,
    ) {
        println("skia-winui-sample: auto exit timeout")
        exitProcess(2)
    }.also { timer ->
        timer.start()
    }
}

private fun closeSample(closeWindow: Boolean) {
    if (closingSample) {
        return
    }
    closingSample = true
    activeExitTimer?.close()
    activeExitTimer = null
    activeExitTimeoutTimer?.close()
    activeExitTimeoutTimer = null
    val window = activeWindow
    val application = activeApplication
    activeLayer?.close()
    activeLayer = null
    activeWindow = null
    activeApplication = null
    if (closeWindow) {
        window?.close()
    }
    application?.exit()
}

private class ClocksWinUI(
    private val renderProvider: () -> GraphicsApi = { GraphicsApi.UNKNOWN },
) : SkikoRenderDelegate, WinUIInputHandler {
    private val typeface = FontMgr.default.makeFromFile("fonts/JetBrainsMono-Regular.ttf")
    private val font = Font(typeface, 13f).apply {
        edging = FontEdging.SUBPIXEL_ANTI_ALIAS
        hinting = FontHinting.SLIGHT
    }
    private val paint = Paint().apply {
        color = 0xff9BC730L.toInt()
        mode = PaintMode.FILL
        strokeWidth = 1f
    }
    private val watchFill = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val watchStroke = Paint().apply {
        color = Color.RED
        mode = PaintMode.STROKE
        strokeWidth = 1f
    }
    private val watchStrokeAA = Paint().apply {
        color = 0xFF000000.toInt()
        mode = PaintMode.STROKE
        strokeWidth = 1f
    }
    private val watchFillHover = Paint().apply { color = 0xFFE4FF01.toInt() }
    private val picturePaint = Paint()
    private val infoFont = Font(typeface, 13f).apply {
        edging = FontEdging.SUBPIXEL_ANTI_ALIAS
        hinting = FontHinting.SLIGHT
    }
    private val infoPaint = Paint().apply {
        color = 0xFF000000.toInt()
        mode = PaintMode.FILL
        strokeWidth = 1f
    }

    private var frame = 0
    private var xpos = 0
    private var ypos = 0

    override fun onPointerEvent(event: WinUIPointerEvent): Boolean {
        if (event.type == WinUIPointerEventType.MOVED ||
            event.type == WinUIPointerEventType.PRESSED ||
            event.type == WinUIPointerEventType.ENTERED
        ) {
            xpos = event.x.toInt()
            ypos = event.y.toInt()
        }
        return false
    }

    override fun onKeyEvent(event: WinUIKeyEvent): Boolean {
        return false
    }

    override fun onTextInputEvent(event: WinUITextInputEvent): Boolean {
        return false
    }

    override fun onFocusEvent(event: WinUIFocusEvent): Boolean {
        if (!event.focused && event.focusState == WinUIFocusState.UNFOCUSED) {
            xpos = 0
            ypos = 0
        }
        return false
    }

    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        canvas.clear(0x00000000)

        for (x in 0..(width - 50) step 50) {
            for (y in 20..(height - 50) step 50) {
                val hover = xpos > x && xpos < x + 50 && ypos > y && ypos < y + 50
                val fill = if (hover) watchFillHover else watchFill
                val stroke = if (x > width / 2) watchStrokeAA else watchStroke
                canvas.drawOval(Rect.makeXYWH(x + 5f, y + 5f, 40f, 40f), fill)
                canvas.drawOval(Rect.makeXYWH(x + 5f, y + 5f, 40f, 40f), stroke)
                var angle = 0f
                while (angle < 2f * PI) {
                    canvas.drawLine(
                        (x + 25 - 17 * sin(angle)),
                        (y + 25 + 17 * cos(angle)),
                        (x + 25 - 20 * sin(angle)),
                        (y + 25 + 20 * cos(angle)),
                        stroke,
                    )
                    angle += (2.0 * PI / 12.0).toFloat()
                }
                val time = (nanoTime / 1E6) % 60000 +
                    (x.toFloat() / width * 5000).toLong() +
                    (y.toFloat() / width * 5000).toLong()

                val angle1 = (time.toFloat() / 5000 * 2f * PI).toFloat()
                canvas.drawLine(
                    x + 25f,
                    y + 25f,
                    x + 25f - 15f * sin(angle1),
                    y + 25f + 15 * cos(angle1),
                    stroke,
                )

                val angle2 = (time / 60000 * 2f * PI).toFloat()
                canvas.drawLine(
                    x + 25f,
                    y + 25f,
                    x + 25f - 10f * sin(angle2),
                    y + 25f + 10f * cos(angle2),
                    stroke,
                )
            }
        }

        val text = "Frames: ${frame++}!"
        canvas.drawString(text, xpos.toFloat(), ypos.toFloat(), font, paint)

        canvas.drawString(
            "Graphic API: ${renderProvider()}, ${sampleRuntimeDescription()}",
            5f,
            15f,
            infoFont,
            infoPaint,
        )

        val rectW = 100f
        val rectH = 100f
        val left = (width - rectW) / 2f
        val top = (height - rectH) / 2f
        canvas.drawLine(left, top, left + rectW, top + rectH, picturePaint)
        canvas.drawLine(left, top + rectH, left + rectW, top, picturePaint)
    }
}

expect fun sampleRuntimeDescription(): String

expect fun isSampleAutoExitRequested(): Boolean
