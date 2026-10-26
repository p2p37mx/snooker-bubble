package com.snooker.bubble

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqCapture = 11
    private lateinit var status: TextView
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = (18 * dp).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(0xFFF3F4F6.toInt())
        }

        root.addView(TextView(this).apply {
            text = "🎱 مساعد السنوكر"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF0F5132.toInt())
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF111827.toInt())
            textDirection = View.TEXT_DIRECTION_RTL
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            background = rounded(Color.WHITE)
        }
        root.addView(status, lp(top = 14))

        root.addView(button("1) السماح بالظهور فوق التطبيقات", 0xFF2563EB.toInt()) {
            openOverlaySettings()
        }, lp(top = 14))
        root.addView(button("2) تشغيل الفقاعة ▶", 0xFF16A34A.toInt()) { start() }, lp(top = 10))
        root.addView(button("إيقاف الفقاعة ■", 0xFFDC2626.toInt()) {
            stopService(Intent(this, BubbleService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 300)
        }, lp(top = 10))
        root.addView(button("مسح المعايرة", 0xFF6B7280.toInt()) {
            Prefs.clearPockets(this)
            Toast.makeText(this, "انمسحت. سوّي معايرة جديدة من الفقاعة", Toast.LENGTH_SHORT).show()
            refresh()
        }, lp(top = 10))

        // دقة الحكم
        val tolLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF111827.toInt())
        }
        fun setTolText(v: Int) {
            tolLabel.text = "هامش الخطأ المسموح: ±$v° (أقل = أصعب)"
        }
        setTolText(Prefs.tolerance(this))
        root.addView(tolLabel, lp(top = 20))
        root.addView(SeekBar(this).apply {
            max = 9
            progress = Prefs.tolerance(this@MainActivity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    Prefs.setTolerance(this@MainActivity, p + 1)
                    setTolText(p + 1)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, lp(top = 4))

        root.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.35f)
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            background = rounded(Color.WHITE)
            text = """
                طريقة الاستخدام:
                • شغّل الفقاعة ووافق على تسجيل الشاشة.
                • افتح اللعبة بالوضع الأفقي.
                • أول مرة: اضغط مطوّلاً على الفقاعة، وبعدين اضغط على الجيوب الستة للطاولة.
                • صوّب بالعصا: الفقاعة تصير 🟢 إذا الكرة راح تدخل جيب، و🔴 إذا لا (والرقم = كم درجة مغلّط).
                • ضغطة وحدة على الفقاعة = إيقاف مؤقت / تشغيل.
                • تگدر تسحب الفقاعة لأي مكان ما يغطي الطاولة.

                ملاحظة: مخصص للتدريب واللعب الأوفلاين. استخدامه بمباريات أونلاين ضد ناس ممكن يسبب حظر حسابك باللعبة.
            """.trimIndent()
        }, lp(top = 20))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val calibrated = Prefs.loadPockets(this, true) != null
        val run = BubbleService.running
        status.text = buildString {
            append("• إذن الظهور فوق التطبيقات: ").append(if (overlay) "✅" else "❌").append('\n')
            append("• المعايرة (الوضع الأفقي): ").append(if (calibrated) "✅" else "لسه").append('\n')
            append("• الفقاعة: ").append(if (run) "شغّالة 🟢" else "متوقفة")
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل إذن الظهور فوق التطبيقات أول", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (BubbleService.running) {
            Toast.makeText(this, "الفقاعة شغّالة من قبل", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, BubbleService::class.java)
                .putExtra(BubbleService.EXTRA_CODE, resultCode)
                .putExtra(BubbleService.EXTRA_DATA, data)
            startForegroundService(i)
            Toast.makeText(this, "الفقاعة اشتغلت 🎱 افتح اللعبة", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "لازم توافق على تسجيل الشاشة حتى تشتغل", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- أدوات الواجهة ----------
    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 14 * dp
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color)
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (top * dp).toInt() }
}
