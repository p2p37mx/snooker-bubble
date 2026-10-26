package com.snooker.bubble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot

class BubbleService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.snooker.bubble.STOP"
        private const val CHANNEL = "bubble"
        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler

    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var calib: CalibrationView? = null

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    @Volatile private var scrW = 0
    @Volatile private var scrH = 0
    @Volatile private var pockets: Array<FloatArray>? = null
    @Volatile private var bubbleRect: IntArray? = null
    @Volatile private var paused = false
    @Volatile private var calibrating = false

    private val analyzer = Analyzer()
    private var pixels = IntArray(0)
    private var lastFrame = 0L
    private val history = ArrayDeque<Analyzer.State>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        worker = HandlerThread("analyzer").apply { start() }
        bg = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { dataExtra(it) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        running = true
        readScreenSize()
        showBubble()
        bg.post { setupCapture() }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun dataExtra(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)

    // ---------------- الإشعار ----------------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "فقاعة السنوكر", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("مساعد السنوكر شغّال")
            .setContentText("ضغطة مطوّلة على الفقاعة = معايرة الجيوب")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إيقاف", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(7, n)
        }
    }

    // ---------------- الشاشة ----------------
    @Suppress("DEPRECATION")
    private fun readScreenSize() {
        val dm = getSystemService(DisplayManager::class.java)
        val disp = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        disp.getRealMetrics(m)
        scrW = m.widthPixels
        scrH = m.heightPixels
        pockets = Prefs.loadPockets(this, scrW > scrH)
    }

    /** يشتغل على خيط التحليل */
    private fun setupCapture() {
        val proj = projection ?: return
        val w = scrW
        val h = scrH
        if (w <= 0 || h <= 0) return
        val scale = 640f / maxOf(w, h)
        val cw = maxOf(2, ((w * scale).toInt() / 2) * 2)
        val ch = maxOf(2, ((h * scale).toInt() / 2) * 2)
        val dpi = resources.displayMetrics.densityDpi

        val old = reader
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, bg)
        reader = r
        val vd = vdisplay
        if (vd == null) {
            vdisplay = proj.createVirtualDisplay(
                "snooker-bubble", cw, ch, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, bg
            )
        } else {
            vd.resize(cw, ch, dpi)
            vd.surface = r.surface
        }
        try { old?.close() } catch (ignored: Exception) { }
        history.clear()
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (paused || calibrating || now - lastFrame < 170) return
            lastFrame = now

            val w = img.width
            val h = img.height
            // إذا الشاشة اندارت وبعدنا ما حدثنا الحجم
            if ((w > h) != (scrW > scrH)) return

            val plane = img.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride
            val rs = plane.rowStride
            if (pixels.size < w * h) pixels = IntArray(w * h)
            for (y in 0 until h) {
                var i = y * rs
                val o = y * w
                for (x in 0 until w) {
                    val rr = buf.get(i).toInt() and 0xff
                    val gg = buf.get(i + 1).toInt() and 0xff
                    val bb = buf.get(i + 2).toInt() and 0xff
                    pixels[o + x] = (rr shl 16) or (gg shl 8) or bb
                    i += ps
                }
            }

            val pk = pockets
            if (pk == null) {
                show(BubbleView.Mode.CALIB, 0f)
                return
            }
            val pts = Array(pk.size) { floatArrayOf(pk[it][0] * w, pk[it][1] * h) }
            val sx = w.toFloat() / scrW
            val sy = h.toFloat() / scrH
            val excl = bubbleRect?.let {
                intArrayOf(
                    (it[0] * sx).toInt(), (it[1] * sy).toInt(),
                    (it[2] * sx).toInt(), (it[3] * sy).toInt()
                )
            }
            val res = analyzer.analyze(
                pixels, w, h, pts, excl, Prefs.tolerance(this).toFloat()
            )

            history.addLast(res.state)
            while (history.size > 3) history.removeFirst()
            val good = history.count { it == Analyzer.State.GOOD }
            val bad = history.count { it == Analyzer.State.BAD }
            val mode = when {
                good >= 2 -> BubbleView.Mode.GOOD
                bad >= 2 -> BubbleView.Mode.BAD
                res.state == Analyzer.State.GOOD -> BubbleView.Mode.GOOD
                res.state == Analyzer.State.BAD -> BubbleView.Mode.BAD
                else -> BubbleView.Mode.IDLE
            }
            show(mode, res.errDeg)
        } catch (ignored: Exception) {
        } finally {
            img.close()
        }
    }

    private fun show(mode: BubbleView.Mode, err: Float) {
        main.post {
            if (!paused && !calibrating) bubble?.set(mode, err)
        }
    }

    // ---------------- الفقاعة ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = dp(60)
        val v = BubbleView(this)
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = scrW - size - dp(12)
        p.y = scrH / 3
        v.setOnTouchListener(DragListener(p))
        try {
            wm.addView(v, p)
        } catch (e: Exception) {
            Toast.makeText(this, "لازم تفعّل إذن الظهور فوق التطبيقات", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        bubble = v
        bubbleParams = p
        v.set(if (pockets == null) BubbleView.Mode.CALIB else BubbleView.Mode.IDLE, 0f)
        updateRect()
    }

    private fun updateRect() {
        val p = bubbleParams ?: return
        val m = dp(6)
        bubbleRect = intArrayOf(p.x - m, p.y - m, p.x + p.width + m, p.y + p.height + m)
    }

    private fun clampBubble() {
        val p = bubbleParams ?: return
        val v = bubble ?: return
        p.x = p.x.coerceIn(0, maxOf(0, scrW - p.width))
        p.y = p.y.coerceIn(0, maxOf(0, scrH - p.height))
        try { wm.updateViewLayout(v, p) } catch (ignored: Exception) { }
        updateRect()
    }

    private fun togglePause() {
        paused = !paused
        bg.post { history.clear() }
        bubble?.set(
            if (paused) BubbleView.Mode.PAUSED
            else if (pockets == null) BubbleView.Mode.CALIB
            else BubbleView.Mode.IDLE, 0f
        )
        Toast.makeText(this, if (paused) "إيقاف مؤقت" else "رجع يشتغل", Toast.LENGTH_SHORT).show()
    }

    private inner class DragListener(val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private var sx = 0
        private var sy = 0
        private var tx = 0f
        private var ty = 0f
        private var moved = false
        private var longFired = false
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private val longRunnable = Runnable {
            if (!moved) {
                longFired = true
                startCalibration()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY
                    moved = false; longFired = false
                    main.postDelayed(longRunnable, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        main.removeCallbacks(longRunnable)
                    }
                    if (moved) {
                        p.x = (sx + dx).toInt().coerceIn(0, maxOf(0, scrW - p.width))
                        p.y = (sy + dy).toInt().coerceIn(0, maxOf(0, scrH - p.height))
                        try { wm.updateViewLayout(v, p) } catch (ignored: Exception) { }
                        updateRect()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longRunnable)
                    if (!moved && !longFired) togglePause()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longRunnable)
            }
            return true
        }
    }

    // ---------------- المعايرة ----------------
    private fun startCalibration() {
        if (calib != null) return
        calibrating = true
        val v = CalibrationView(
            this,
            onDone = { pts -> finishCalibration(pts) },
            onCancel = { closeCalibration() }
        )
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, p)
            calib = v
        } catch (e: Exception) {
            calibrating = false
        }
    }

    private fun finishCalibration(raw: List<FloatArray>) {
        readScreenSize()
        val landscape = scrW > scrH
        val norm = raw.map { floatArrayOf(it[0] / scrW, it[1] / scrH) }
        Prefs.savePockets(this, landscape, norm)
        pockets = norm.toTypedArray()
        closeCalibration()
        bubble?.set(BubbleView.Mode.IDLE, 0f)
        Toast.makeText(this, "تمت المعايرة ✓", Toast.LENGTH_SHORT).show()
    }

    private fun closeCalibration() {
        calib?.let { try { wm.removeView(it) } catch (ignored: Exception) { } }
        calib = null
        calibrating = false
        bg.post { history.clear() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({
            if (projection == null) return@postDelayed
            if (calib != null) closeCalibration()
            readScreenSize()
            clampBubble()
            if (!paused) bubble?.set(
                if (pockets == null) BubbleView.Mode.CALIB else BubbleView.Mode.IDLE, 0f
            )
            bg.post { setupCapture() }
        }, 400)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        bubble?.let { try { wm.removeView(it) } catch (ignored: Exception) { } }
        bubble = null
        calib?.let { try { wm.removeView(it) } catch (ignored: Exception) { } }
        calib = null

        val vd = vdisplay
        val r = reader
        val p = projection
        vdisplay = null
        reader = null
        projection = null
        bg.post {
            try { vd?.release() } catch (ignored: Exception) { }
            try { r?.close() } catch (ignored: Exception) { }
        }
        try { p?.stop() } catch (ignored: Exception) { }
        worker.quitSafely()
        super.onDestroy()
    }
}
