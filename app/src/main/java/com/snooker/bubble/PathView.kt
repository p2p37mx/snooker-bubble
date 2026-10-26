package com.snooker.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.hypot

/**
 * طبقة شفافة فوق اللعبة (ما تستقبل لمس): ترسم خط اتجاه الكرة الملونة
 * من الكرة لحد الجيب (أخضر) أو لحد حافة الطاولة (أحمر).
 */
class PathView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
    }

    private var show = false
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var startGap = 0f
    private var good = false

    fun set(sx: Float, sy: Float, ex: Float, ey: Float, startGap: Float, good: Boolean) {
        this.sx = sx; this.sy = sy; this.ex = ex; this.ey = ey
        this.startGap = startGap; this.good = good
        show = true
        invalidate()
    }

    fun clear() {
        if (show) {
            show = false
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!show) return
        val dx = ex - sx
        val dy = ey - sy
        val len = hypot(dx, dy)
        if (len <= startGap + 4f) return
        val ux = dx / len
        val uy = dy / len
        // نبدي بعد الكرة شوية حتى ما نغطيها
        val ax = sx + ux * startGap
        val ay = sy + uy * startGap
        val col = if (good) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
        line.color = col
        line.alpha = 220
        canvas.drawLine(ax, ay, ex, ey, line)
        if (good) {
            ring.color = col
            canvas.drawCircle(ex, ey, 14f * d, ring)
        }
    }
}
