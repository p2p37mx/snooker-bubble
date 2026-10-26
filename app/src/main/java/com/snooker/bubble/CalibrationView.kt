package com.snooker.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * شاشة شفافة فوق اللعبة: المستخدم يضغط على الجيوب الستة.
 * الضغط على نقطة موجودة يحذفها. التعليمات بنص الشاشة (ما بيها جيوب).
 */
class CalibrationView(
    context: Context,
    private val onDone: (List<FloatArray>) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val raw = mutableListOf<FloatArray>()
    private val local = mutableListOf<FloatArray>()
    private val d = resources.displayMetrics.density
    private var done = false

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF59E0B.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD1D5DB.toInt(); textSize = 12 * d; textAlign = Paint.Align.CENTER
    }
    private val boxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9111827.toInt() }
    private val cancelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEF4444.toInt() }
    private val infoRect = RectF()
    private val cancelRect = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x26000000)
        val cx = width / 2f
        val cy = height / 2f

        infoRect.set(cx - 150 * d, cy - 46 * d, cx + 150 * d, cy + 4 * d)
        canvas.drawRoundRect(infoRect, 12 * d, 12 * d, boxBg)
        canvas.drawText("اضغط على الجيوب الستة (${raw.size}/6)", cx, cy - 24 * d, txt)
        canvas.drawText("ضغطة على نقطة موجودة = حذفها", cx, cy - 6 * d, small)

        cancelRect.set(cx - 45 * d, cy + 12 * d, cx + 45 * d, cy + 46 * d)
        canvas.drawRoundRect(cancelRect, 17 * d, 17 * d, cancelBg)
        canvas.drawText("إلغاء", cancelRect.centerX(),
            cancelRect.centerY() - (txt.ascent() + txt.descent()) / 2f, txt)

        for ((i, p) in local.withIndex()) {
            canvas.drawCircle(p[0], p[1], 13 * d, dot)
            canvas.drawCircle(p[0], p[1], 13 * d, ring)
            canvas.drawText("${i + 1}", p[0], p[1] - (txt.ascent() + txt.descent()) / 2f, txt)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP || done) return true
        if (cancelRect.contains(e.x, e.y)) { done = true; onCancel(); return true }
        if (infoRect.contains(e.x, e.y)) return true

        val idx = local.indexOfFirst { hypot(it[0] - e.x, it[1] - e.y) < 24 * d }
        if (idx >= 0) {
            local.removeAt(idx); raw.removeAt(idx)
            invalidate()
            return true
        }
        local.add(floatArrayOf(e.x, e.y))
        raw.add(floatArrayOf(e.rawX, e.rawY))
        invalidate()
        if (raw.size == 6) {
            done = true
            postDelayed({ onDone(raw.toList()) }, 350)
        }
        return true
    }
}
