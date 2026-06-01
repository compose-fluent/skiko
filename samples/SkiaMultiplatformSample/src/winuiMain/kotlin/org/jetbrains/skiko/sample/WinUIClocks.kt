package org.jetbrains.skiko.sample

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.winui.WinUIFocusEvent
import org.jetbrains.skiko.winui.WinUIInputHandler
import org.jetbrains.skiko.winui.WinUIKeyEvent
import org.jetbrains.skiko.winui.WinUIPointerEvent
import org.jetbrains.skiko.winui.WinUITextInputEvent

class WinUIClocks(renderApi: () -> GraphicsApi) : Clocks(renderApi), WinUIInputHandler {
    private val fontCollection = FontCollection().setDefaultFontManager(FontMgr.default)
    private val paragraphStyle = ParagraphStyle()
    private var inputSummary = "Input: move pointer or press a key"

    override fun onPointerEvent(event: WinUIPointerEvent): Boolean {
        xpos = event.x.toDouble()
        ypos = event.y.toDouble()
        inputSummary = buildString {
            append("Pointer ${event.type} id=${event.pointerId} ")
            append("x=${event.x.toInt()} y=${event.y.toInt()} ")
            append("device=${event.deviceType} buttons=${event.buttons} ")
            if (event.wheelDelta != 0) append("wheel=${event.wheelDelta} ")
            append("mod=${event.modifiers}")
        }
        return false
    }

    override fun onKeyEvent(event: WinUIKeyEvent): Boolean {
        inputSummary = buildString {
            append(if (event.pressed) "Key down" else "Key up")
            append(" key=${event.keyCode} scan=${event.scanCode} repeat=${event.repeatCount}")
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
        super.onRender(canvas, width, height, nanoTime)
        drawInputSummary(canvas, height)
    }

    private fun drawInputSummary(canvas: Canvas, height: Int) {
        val paragraph = ParagraphBuilder(paragraphStyle, fontCollection)
            .pushStyle(TextStyle().setColor(0xFF202020.toInt()).setFontSize(14f))
            .addText(inputSummary)
            .popStyle()
            .build()
        paragraph.layout(900f)
        paragraph.paint(canvas, 5f, (height - 28).coerceAtLeast(5).toFloat())
    }
}
