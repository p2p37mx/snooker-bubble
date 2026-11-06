package com.alwan.accessbubble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "com.alwan.accessbubble.STOP"
        private const val CHANNEL = "bubble"
        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var bubble: Dot? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelList: LinearLayout? = null
    private val dp get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp).toInt()

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (bubble != null) return START_NOT_STICKY

        if (!showBubble()) return START_NOT_STICKY
        running = true
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer
        )
        if (Prefs.autoOff(this)) {
            if (Acc.canWrite(this)) {
                Acc.disableAll(this)
                toast("انطفت كل تطبيقات إمكانية الوصول 🛡️")
            } else {
                toast("الصلاحية ناقصة — افتح التطبيق وامنحها")
            }
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {}
        if (Prefs.restoreOnStop(this) && Acc.canWrite(this)) Acc.restore(this)
        closePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }

    // ---------------- الإشعار ----------------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "فقاعة الوصول", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("فقاعة الوصول شغّالة")
            .setContentText("ضغطة = القائمة • ضغطة مطوّلة = إطفاء الكل")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إيقاف الفقاعة", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(9, n)
        }
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ---------------- الفقاعة ----------------
    private fun showBubble(): Boolean {
        val size = dp(58)
        val v = Dot(this)
        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        val saved = Prefs.pos(this)
        val m = resources.displayMetrics
        p.x = saved?.get(0) ?: (m.widthPixels - size - dp(10))
        p.y = saved?.get(1) ?: (m.heightPixels / 3)
        v.setOnTouchListener(Drag(p))
        return try {
            wm.addView(v, p)
            bubble = v
            bubbleParams = p
            true
        } catch (e: Exception) {
            toast("لازم تفعّل إذن الظهور فوق التطبيقات")
            stopSelf()
            false
        }
    }

    private fun refresh() {
        val ok = Acc.canWrite(this)
        val n = Acc.enabled(this).size
        bubble?.set(ok, n)
        fillPanel()
    }

    private var closedAt = 0L

    private fun onTap() {
        if (panel != null) closePanel()
        else if (android.os.SystemClock.uptimeMillis() - closedAt > 350) openPanel()
    }

    private fun onLong() {
        if (!Acc.canWrite(this)) {
            toast("الصلاحية ناقصة — افتح التطبيق وامنحها")
            return
        }
        Acc.disableAll(this)
        toast("انطفى الكل 🛡️")
        refresh()
    }

    // ---------------- القائمة ----------------
    private fun ui(): Context = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)

    @SuppressLint("ClickableViewAccessibility")
    private fun openPanel() {
        val c = ui()
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val pd = dp(14)
            setPadding(pd, pd, pd, pd)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(10).toFloat()
        }
        root.addView(TextView(c).apply {
            text = "🛡️ إمكانية الوصول"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E3A8A.toInt())
        })
        root.addView(TextView(c).apply {
            text = "شغّل بس التطبيق اللي تريده:"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
        })

        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(c).apply { addView(list) }
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        panelList = list

        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn(c, "إطفاء الكل", 0xFFDC2626.toInt()) { onLong() }, weightLp())
        row.addView(btn(c, "رجّع السابق", 0xFF2563EB.toInt()) {
            if (!Acc.canWrite(this)) toast("الصلاحية ناقصة")
            else if (!Acc.restore(this)) toast("ماكو وضع سابق محفوظ")
            refresh()
        }, weightLp())
        row.addView(btn(c, "سدّ", 0xFF6B7280.toInt()) { closePanel() }, weightLp())
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val w = min(resources.displayMetrics.widthPixels - dp(24), dp(380))
        val p = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.CENTER
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false
        }
        try {
            wm.addView(root, p)
            panel = root
        } catch (e: Exception) {
            return
        }
        fillPanel()
        // حد أقصى لطول القائمة
        scroll.post {
            if (scroll.height > maxH) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
            }
        }
    }

    private fun closePanel() {
        panel?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            closedAt = android.os.SystemClock.uptimeMillis()
        }
        panel = null
        panelList = null
    }

    private fun fillPanel() {
        val list = panelList ?: return
        val c = list.context
        list.removeAllViews()
        val ok = Acc.canWrite(this)
        if (!ok) {
            list.addView(TextView(c).apply {
                text = "⚠️ الصلاحية مو ممنوحة. افتح التطبيق واتبع الخطوات."
                textSize = 14f
                setTextColor(0xFFB91C1C.toInt())
            })
        }
        val on = Acc.enabled(this)
        val svcs = Acc.installed(this)
        if (svcs.isEmpty()) {
            list.addView(TextView(c).apply {
                text = "ماكو تطبيقات عدها إمكانية وصول."
                textSize = 14f
            })
        }
        for (s in svcs) {
            val sw = Switch(c).apply {
                text = s.label
                textSize = 15f
                setTextColor(0xFF111827.toInt())
                isChecked = on.contains(s.cn)
                isEnabled = ok
                val pd = dp(6)
                setPadding(0, pd, 0, pd)
            }
            sw.setOnCheckedChangeListener { b, checked ->
                if (!Acc.toggle(this, s.cn, checked)) {
                    toast("ما گدرت أغيّرها")
                    b.setOnCheckedChangeListener(null)
                    b.isChecked = !checked
                }
                main.postDelayed({ refresh() }, 150)
            }
            list.addView(sw, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun btn(c: Context, t: String, col: Int, f: () -> Unit) = Button(c).apply {
        text = t
        textSize = 13f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(col)
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { f() }
    }

    private fun weightLp() = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------------- السحب واللمس ----------------
    private inner class Drag(val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private var sx = 0f
        private var sy = 0f
        private var px = 0
        private var py = 0
        private var moved = false
        private var longDone = false
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private val longRun = Runnable {
            if (!moved) {
                longDone = true
                bubble?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLong()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = p.x; py = p.y
                    moved = false; longDone = false
                    main.postDelayed(longRun, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        main.removeCallbacks(longRun)
                    }
                    if (moved) {
                        p.x = px + dx.toInt()
                        p.y = py + dy.toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longRun)
                    if (moved) Prefs.setPos(this@BubbleService, p.x, p.y)
                    else if (!longDone) onTap()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longRun)
            }
            return true
        }
    }

    // ---------------- رسم الفقاعة ----------------
    private class Dot(ctx: Context) : View(ctx) {
        private var ok = true
        private var count = 0
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            alpha = 210
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        fun set(ok: Boolean, count: Int) {
            if (ok != this.ok || count != this.count) {
                this.ok = ok
                this.count = count
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f
            val col = when {
                !ok -> 0xFF6B7280.toInt()     // رمادي: الصلاحية ناقصة
                count == 0 -> 0xFF16A34A.toInt() // أخضر: الكل طافي
                else -> 0xFFF59E0B.toInt()    // برتقالي: أكو تطبيق مفعّل
            }
            fill.color = col
            fill.alpha = 90
            canvas.drawCircle(cx, cy, r, fill)
            fill.alpha = 240
            canvas.drawCircle(cx, cy, r * 0.78f, fill)
            ring.strokeWidth = r * 0.06f
            canvas.drawCircle(cx, cy, r * 0.78f, ring)
            val s = when {
                !ok -> "!"
                count == 0 -> "✓"
                else -> count.toString()
            }
            text.textSize = r * 0.75f
            canvas.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2f, text)
        }
    }
}
