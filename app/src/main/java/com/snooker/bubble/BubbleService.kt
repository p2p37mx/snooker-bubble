package com.snooker.bubble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
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

    /** معايرة جاهزة لحجم صورة معيّن */
    private class Cal(
        val slot: String,
        val norm: Array<FloatArray>,
        val w: Int, val h: Int,
        val H: DoubleArray, val Hi: DoubleArray,
        val refX: Float, val refY: Float
    )

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler

    private var bubble: BubbleView? = null
    private var pathView: PathView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var calib: CalibrationView? = null

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    @Volatile private var scrW = 0
    @Volatile private var scrH = 0
    @Volatile private var calNorm = HashMap<String, Array<FloatArray>>()
    @Volatile private var bubbleRect: IntArray? = null
    @Volatile private var paused = false
    @Volatile private var calibrating = false

    // يستخدم فقط على خيط التحليل
    private val analyzer = Analyzer()
    private var pixels = IntArray(0)
    private var lastW = 0
    private var lastH = 0
    private var lastCal: Cal? = null
    private var lastFrame = 0L
    private var frameNo = 0
    private val calCache = HashMap<String, Cal>()
    private var activeSlot: String? = null
    private val history = ArrayDeque<Analyzer.State>()

    // كشف تلقائي للطاولة بالمنظر الفوقي
    private var autoBox: IntArray? = null
    private var autoCal: Cal? = null
    private var autoMiss = 0

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
            .setContentText("ضغطة = إيقاف مؤقت • ضغطتين = لقطة تشخيص")
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
        loadCalibrations()
    }

    private fun loadCalibrations() {
        val map = HashMap<String, Array<FloatArray>>()
        for (s in Prefs.SLOTS) {
            Prefs.loadPockets(this, scrW > scrH, s)?.let { map[s] = it }
        }
        calNorm = map
    }

    /** يشتغل على خيط التحليل */
    private fun setupCapture() {
        val proj = projection ?: return
        val w = scrW
        val h = scrH
        if (w <= 0 || h <= 0) return
        val scale = 1280f / maxOf(w, h)
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
        calCache.clear()
        autoBox = null
        autoCal = null
    }

    private fun calFor(slot: String, w: Int, h: Int): Cal? {
        val norm = calNorm[slot] ?: return null
        val c = calCache[slot]
        if (c != null && c.w == w && c.h == h && c.norm === norm) return c
        val src = Array(4) { floatArrayOf(norm[it][0] * w, norm[it][1] * h) }
        val H = Analyzer.homography(src, Analyzer.CAL_TABLE) ?: return null
        val Hi = Analyzer.invert(H) ?: return null
        val nc = Cal(slot, norm, w, h, H, Hi, src[0][0], src[0][1])
        calCache[slot] = nc
        return nc
    }

    /**
     * المنظر الفوقي: القماش مستطيل أخضر نسبته ~2:1 ما يلمس حواف الشاشة.
     * الجيوب = زوايا المستطيل + نص الضلعين الطوال.
     */
    private fun detectTopTable(w: Int, h: Int): IntArray? {
        val rows = IntArray(h)
        val cols = IntArray(w)
        var y = 0
        while (y < h) {
            val o = y * w
            var x = 0
            while (x < w) {
                val p = pixels[o + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                if (g > r + 40 && g > b + 40) { rows[y]++; cols[x]++ }
                x += 2
            }
            y += 2
        }
        var mr = 0; var mc = 0
        for (v in rows) if (v > mr) mr = v
        for (v in cols) if (v > mc) mc = v
        if (mr < 20 || mc < 10) return null
        var top = -1; var bot = -1; var left = -1; var right = -1
        for (i in 0 until h) if (rows[i] > mr / 2) { if (top < 0) top = i; bot = i }
        for (i in 0 until w) if (cols[i] > mc / 2) { if (left < 0) left = i; right = i }
        if (top < 0 || left < 0) return null
        if (left <= 3 || top <= 3 || right >= w - 4 || bot >= h - 4) return null
        val bw = (right - left).toFloat()
        val bh = (bot - top).toFloat()
        if (bw < 50 || bh < 50) return null
        val aspect = if (bw > bh) bw / bh else bh / bw
        if (aspect < 1.8f || aspect > 2.15f) return null
        var gN = 0; var tN = 0
        y = top
        while (y <= bot) {
            var x = left
            while (x <= right) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                if (g > r + 40 && g > b + 40) gN++
                tN++
                x += 4
            }
            y += 4
        }
        if (tN == 0 || gN.toFloat() / tN < 0.88f) return null
        return intArrayOf(left, top, right, bot)
    }

    private fun updateAutoTable(w: Int, h: Int) {
        val b = detectTopTable(w, h)
        if (b == null) {
            autoMiss++
            if (autoMiss >= 3) { autoBox = null; autoCal = null }
            return
        }
        autoMiss = 0
        val old = autoBox
        if (old != null && autoCal?.w == w && autoCal?.h == h &&
            (0 until 4).all { kotlin.math.abs(old[it] - b[it]) <= 3 }
        ) return
        val l = b[0].toFloat(); val t = b[1].toFloat(); val r = b[2].toFloat(); val bt = b[3].toFloat()
        val src = if (r - l >= bt - t) {
            val mx = (l + r) / 2f
            arrayOf(floatArrayOf(l, t), floatArrayOf(l, bt), floatArrayOf(mx, t), floatArrayOf(mx, bt))
        } else {
            val my = (t + bt) / 2f
            arrayOf(floatArrayOf(l, t), floatArrayOf(r, t), floatArrayOf(l, my), floatArrayOf(r, my))
        }
        val H = Analyzer.homography(src, Analyzer.CAL_TABLE) ?: return
        val Hi = Analyzer.invert(H) ?: return
        if (old == null) history.clear()
        autoBox = b
        autoCal = Cal("auto", src, w, h, H, Hi, l, t)
    }

    /** إذا عندنا معايرتين، نختار اللي قماشها أخضر أكثر بالصورة الحالية */
    private fun chooseCal(w: Int, h: Int): Cal? {
        val cals = Prefs.SLOTS.mapNotNull { calFor(it, w, h) }
        if (cals.isEmpty()) return null
        if (cals.size == 1) return cals[0]
        val cur = activeSlot
        if (cur != null && frameNo % 6 != 0) {
            cals.firstOrNull { it.slot == cur }?.let { return it }
        }
        var best = cals[0]
        var bestScore = -1
        val out = FloatArray(2)
        for (c in cals) {
            var score = 0
            var u = 0.1f
            while (u < 0.95f) {
                var v = 0.1f
                while (v < 1.95f) {
                    if (Analyzer.apply(c.Hi, u, v, out)) {
                        val x = out[0].toInt()
                        val y = out[1].toInt()
                        if (x in 0 until w && y in 0 until h) {
                            val p = pixels[y * w + x]
                            val r = (p shr 16) and 0xff
                            val g = (p shr 8) and 0xff
                            val b = p and 0xff
                            if (g > r + 25 && g > b + 25) score++
                        }
                    }
                    v += 0.2f
                }
                u += 0.1f
            }
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (activeSlot != best.slot) {
            activeSlot = best.slot
            history.clear()
        }
        return best
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (paused || calibrating || now - lastFrame < 200) return
            lastFrame = now
            frameNo++

            val w = img.width
            val h = img.height
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
            lastW = w
            lastH = h

            if (frameNo % 5 == 1 || autoBox == null) updateAutoTable(w, h)
            val cal = if (autoBox != null) autoCal else chooseCal(w, h)
            lastCal = cal
            if (cal == null) {
                analyzer.debug.reason = "منظر 3D - حوّل للمنظر الفوقي"
                show(BubbleView.Mode.VIEW, 0f)
                main.post { pathView?.clear() }
                return
            }
            val sx = w.toFloat() / scrW
            val sy = h.toFloat() / scrH
            val excl = bubbleRect?.let {
                intArrayOf(
                    (it[0] * sx).toInt(), (it[1] * sy).toInt(),
                    (it[2] * sx).toInt(), (it[3] * sy).toInt()
                )
            }
            val res = analyzer.analyze(
                pixels, w, h, cal.H, cal.Hi, cal.refX, cal.refY,
                excl, Prefs.tolerance(this).toFloat()
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

            val dbg = analyzer.debug
            if (Prefs.showLine(this) && dbg.hasObj && dbg.hasPath &&
                (mode == BubbleView.Mode.GOOD || mode == BubbleView.Mode.BAD)
            ) {
                val kx = scrW.toFloat() / w
                val ky = scrH.toFloat() / h
                val ax = dbg.ox * kx; val ay = dbg.oy * ky
                val bx = dbg.px * kx; val by = dbg.py * ky
                val gap = dbg.orad * kx * 2.6f
                val good = mode == BubbleView.Mode.GOOD
                main.post { if (!paused && !calibrating) pathView?.set(ax, ay, bx, by, gap, good) }
            } else {
                main.post { pathView?.clear() }
            }
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

    // ---------------- لقطة التشخيص ----------------
    /** يشتغل على خيط التحليل: يرسم شنو شاف المساعد على آخر صورة ويحفظها بالصور */
    private fun saveSnapshot() {
        val w = lastW
        val h = lastH
        if (w == 0 || h == 0) {
            toast("بعد ما وصلت صورة، جرب بعد ثانية")
            return
        }
        try {
            val argb = IntArray(w * h)
            for (i in 0 until w * h) argb[i] = pixels[i] or (0xFF shl 24)
            val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
            val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
            val dbg = analyzer.debug
            val cal = lastCal
            val o = FloatArray(2)
            val o2 = FloatArray(2)

            if (cal != null) {
                // حدود الطاولة
                p.color = Color.YELLOW
                val corners = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 2f), floatArrayOf(0f, 2f))
                for (i in 0 until 4) {
                    val a = corners[i]; val b = corners[(i + 1) % 4]
                    if (Analyzer.apply(cal.Hi, a[0], a[1], o) && Analyzer.apply(cal.Hi, b[0], b[1], o2)) {
                        cv.drawLine(o[0], o[1], o2[0], o2[1], p)
                    }
                }
                fillP.color = 0xFFFF8800.toInt()
                for (pk in Analyzer.POCKETS) {
                    if (Analyzer.apply(cal.Hi, pk[0], pk[1], o)) cv.drawCircle(o[0], o[1], 7f, fillP)
                }
            }
            if (dbg.hasCue) {
                p.color = Color.CYAN
                cv.drawCircle(dbg.cx, dbg.cy, dbg.rad, p)
                if (dbg.hasLine) cv.drawLine(dbg.cx, dbg.cy, dbg.ex, dbg.ey, p)
            }
            if (dbg.hasObj) {
                p.color = Color.MAGENTA
                cv.drawCircle(dbg.ox, dbg.oy, dbg.orad + 2f, p)
            }
            if (dbg.hasPath && dbg.hasObj) {
                p.color = if (dbg.state == Analyzer.State.GOOD) Color.GREEN else Color.RED
                p.strokeWidth = 4f
                cv.drawLine(dbg.ox, dbg.oy, dbg.px, dbg.py, p)
            }
            // النص
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f }
            val bgP = Paint().apply { color = 0xCC000000.toInt() }
            val line1 = "الحالة: ${dbg.reason}" +
                    (if (dbg.state == Analyzer.State.BAD && dbg.errDeg < 90f) "  (${"%.1f".format(dbg.errDeg)}°)" else "")
            val line2 = "المنظر: " + when (cal?.slot) {
                "auto" -> "من فوق (تلقائي)"
                Prefs.SLOT_TOP -> "من فوق (يدوي)"
                Prefs.SLOT_3D -> "3D (يدوي)"
                else -> "3D بدون معايرة"
            }
            cv.drawRect(0f, 0f, w.toFloat(), 70f, bgP)
            cv.drawText(line1, 12f, 30f, tp)
            cv.drawText(line2, 12f, 62f, tp)

            val name = "snooker_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnookerBubble")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no uri")
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                toast("📸 انحفظت اللقطة بالصور ← SnookerBubble")
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                val f = File(dir, name)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                toast("📸 انحفظت: ${f.absolutePath}")
            }
        } catch (e: Exception) {
            toast("ما گدرت أحفظ اللقطة")
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------- الفقاعة ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showPathOverlay() {
        val v = PathView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        // لازم <= 0.8 حتى اللمس يعبر للعبة (أندرويد 12+)
        p.alpha = 0.8f
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, p)
            pathView = v
        } catch (ignored: Exception) { }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        showPathOverlay()
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
        v.set(BubbleView.Mode.IDLE, 0f)
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

    private fun idleMode() = BubbleView.Mode.IDLE

    private fun togglePause() {
        paused = !paused
        bg.post { history.clear() }
        bubble?.set(if (paused) BubbleView.Mode.PAUSED else idleMode(), 0f)
        pathView?.clear()
        Toast.makeText(this, if (paused) "إيقاف مؤقت" else "رجع يشتغل", Toast.LENGTH_SHORT).show()
    }

    private inner class DragListener(val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private var sx = 0
        private var sy = 0
        private var tx = 0f
        private var ty = 0f
        private var moved = false
        private var longFired = false
        private var pendingTap = false
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private val longRunnable = Runnable {
            if (!moved) {
                longFired = true
                pendingTap = false
                main.removeCallbacks(singleTap)
                startCalibration()
            }
        }
        private val singleTap = Runnable {
            pendingTap = false
            togglePause()
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
                    if (!moved && !longFired) {
                        if (pendingTap) {
                            // ضغطتين = لقطة تشخيص
                            pendingTap = false
                            main.removeCallbacks(singleTap)
                            bg.post { saveSnapshot() }
                        } else {
                            pendingTap = true
                            main.postDelayed(singleTap, 300)
                        }
                    }
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
        pathView?.clear()
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

        // نحدد المنظر: من فوق (بدون منظور) لو 3D
        val src = Array(4) { floatArrayOf(raw[it][0], raw[it][1]) }
        val H = Analyzer.homography(src, Analyzer.CAL_TABLE)
        val Hi = H?.let { Analyzer.invert(it) }
        if (H == null || Hi == null) {
            closeCalibration()
            Toast.makeText(this, "النقاط غلط، عيد المعايرة بالترتيب", Toast.LENGTH_LONG).show()
            return
        }
        val f = analyzer.foreshortening(Hi, 0.5f, 1f)
        val slot = if (f > 0.75f) Prefs.SLOT_TOP else Prefs.SLOT_3D
        Prefs.savePockets(this, landscape, slot, norm)
        loadCalibrations()
        bg.post { calCache.clear(); activeSlot = null }
        closeCalibration()
        bubble?.set(BubbleView.Mode.IDLE, 0f)
        val name = if (slot == Prefs.SLOT_TOP) "المنظر الفوقي" else "منظر 3D"
        Toast.makeText(this, "تمت معايرة $name ✓", Toast.LENGTH_SHORT).show()
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
            if (!paused) bubble?.set(idleMode(), 0f)
            bg.post { setupCapture() }
        }, 400)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        bubble?.let { try { wm.removeView(it) } catch (ignored: Exception) { } }
        bubble = null
        pathView?.let { try { wm.removeView(it) } catch (ignored: Exception) { } }
        pathView = null
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
