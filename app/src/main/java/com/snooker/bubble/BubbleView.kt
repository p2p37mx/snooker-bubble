package com.snooker.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class BubbleView(context: Context) : View(context) {

    enum class Mode { IDLE, GOOD, BAD, CALIB, PAUSED, VIEW }

    private var mode = Mode.IDLE
    private var err = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 200
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun set(m: Mode, e: Float) {
        if (m != mode || abs(e - err) > 0.5f) {
            mode = m
            err = e
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        val col = when (mode) {
            Mode.GOOD -> 0xFF22C55E.toInt()
            Mode.BAD -> 0xFFEF4444.toInt()
            Mode.CALIB -> 0xFFF59E0B.toInt()
            Mode.PAUSED -> 0xFF6B7280.toInt()
            Mode.IDLE -> 0xFF374151.toInt()
            Mode.VIEW -> 0xFF2563EB.toInt()
        }

        // توهج خارجي
        fill.color = col
        fill.alpha = 90
        canvas.drawCircle(cx, cy, r, fill)
        // الفقاعة
        fill.alpha = 235
        canvas.drawCircle(cx, cy, r * 0.78f, fill)
        ring.strokeWidth = r * 0.06f
        canvas.drawCircle(cx, cy, r * 0.78f, ring)

        val symbol = when (mode) {
            Mode.GOOD -> "✓"
            Mode.BAD -> "✗"
            Mode.CALIB -> "!"
            Mode.PAUSED -> "II"
            Mode.IDLE -> "•"
            Mode.VIEW -> "⬒"
        }
        val showDeg = mode == Mode.BAD && err < 90f
        text.textSize = r * (if (showDeg) 0.6f else 0.8f)
        val sy = if (showDeg) cy - r * 0.12f else cy
        canvas.drawText(symbol, cx, sy - (text.ascent() + text.descent()) / 2f, text)
        if (showDeg) {
            text.textSize = r * 0.3f
            canvas.drawText("${err.roundToInt()}°", cx, cy + r * 0.5f, text)
        }
    }
}
