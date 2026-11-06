package com.alwan.accessbubble

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
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
            text = "🛡️ فقاعة الوصول"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E3A8A.toInt())
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF111827.toInt())
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.3f)
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            background = rounded(Color.WHITE)
        }
        root.addView(status, lp(top = 14))

        root.addView(button("1) السماح بالظهور فوق التطبيقات", 0xFF2563EB.toInt()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }, lp(top = 14))

        root.addView(button("2) نسخ أمر منح الصلاحية (ADB)", 0xFF7C3AED.toInt()) {
            val cmd = "adb shell " + Acc.grantCommand(this)
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("cmd", cmd))
            Toast.makeText(this, "انسخ الأمر ✔", Toast.LENGTH_SHORT).show()
        }, lp(top = 10))

        root.addView(button("منح الصلاحية بالروت (إذا جهازك مروّت)", 0xFF6B7280.toInt()) {
            val ok = Acc.tryRoot(this)
            Toast.makeText(this, if (ok) "تمت ✔" else "ما نجحت (الجهاز مو مروّت؟)", Toast.LENGTH_SHORT).show()
            refresh()
        }, lp(top = 10))

        root.addView(button("3) تشغيل الفقاعة ▶", 0xFF16A34A.toInt()) { start() }, lp(top = 10))
        root.addView(button("إيقاف الفقاعة ■", 0xFFDC2626.toInt()) {
            stopService(Intent(this, BubbleService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 300)
        }, lp(top = 10))

        root.addView(CheckBox(this).apply {
            text = "أول ما تشتغل الفقاعة تطفي كل التطبيقات"
            textSize = 15f
            setTextColor(0xFF111827.toInt())
            isChecked = Prefs.autoOff(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setAutoOff(this@MainActivity, c) }
        }, lp(top = 16))
        root.addView(CheckBox(this).apply {
            text = "من أطفي الفقاعة يرجّع الوضع اللي چان قبلها"
            textSize = 15f
            setTextColor(0xFF111827.toInt())
            isChecked = Prefs.restoreOnStop(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setRestoreOnStop(this@MainActivity, c) }
        }, lp(top = 4))

        root.addView(TextView(this).apply {
            text = "التطبيقات اللي عدها إمكانية وصول"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E3A8A.toInt())
        }, lp(top = 18))
        root.addView(TextView(this).apply {
            text = "المفتاح = تشغيل/إطفاء هسه • «استثناء» = ما ينطفي ويه «إطفاء الكل»"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
        })
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * dp).toInt()
            setPadding(p, p, p, p)
            background = rounded(Color.WHITE)
        }
        root.addView(list, lp(top = 8))

        root.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.35f)
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            background = rounded(Color.WHITE)
            text = """
                منح الصلاحية (مرة وحدة بس) من نفس الموبايل عبر Termux:
                1. الإعدادات ← حول الهاتف ← اضغط «رقم الإصدار» 7 مرات (تنفتح خيارات المطوّر).
                2. خيارات المطوّر ← فعّل «تصحيح الأخطاء اللاسلكي» (لازم تكون على واي فاي).
                3. بـ Termux اكتب:  pkg install android-tools
                4. ادخل على «تصحيح الأخطاء اللاسلكي» ← «إقران الجهاز برمز الإقران». يطلعلك رقم بورت ورمز من 6 أرقام. خلّي الشاشة مقسومة ويه Termux واكتب:
                   adb pair 127.0.0.1:البورت الرمز
                5. ارجع لشاشة «تصحيح الأخطاء اللاسلكي» وشوف البورت المكتوب جوه «عنوان IP والمنفذ» (غير بورت الإقران) واكتب:
                   adb connect 127.0.0.1:البورت
                6. الصق الأمر اللي نسخته من الزر رقم 2.
                7. ارجع للتطبيق، لازم تشوف «الصلاحية ✅».

                • أجهزة شاومي/ريدمي: فعّل كمان «تصحيح أخطاء USB (إعدادات الأمان)» بخيارات المطوّر.
                • الصلاحية تبقى حتى بعد إعادة التشغيل والتحديثات. تروح بس إذا حذفت التطبيق.

                استخدام الفقاعة:
                • 🟢 ✓ = كل التطبيقات طافية.
                • 🟠 رقم = عدد التطبيقات المشغّلة هسه.
                • ⚪ ! = الصلاحية ناقصة.
                • ضغطة = تفتح قائمة تشغّل منها التطبيق اللي تريده بس.
                • ضغطة مطوّلة = إطفاء الكل فوراً.
                • اسحبها لأي مكان يريحك.
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
        val perm = Acc.canWrite(this)
        val on = Acc.enabled(this)
        status.text = buildString {
            append(if (overlay) "الظهور فوق التطبيقات ✅" else "الظهور فوق التطبيقات ❌")
            append("\n")
            append(if (perm) "الصلاحية ✅" else "الصلاحية ❌ (اتبع الخطوات جوه)")
            append("\n")
            append(if (BubbleService.running) "الفقاعة شغّالة ▶" else "الفقاعة طافية")
            append("\nالمشغّل هسه: ${on.size}")
        }
        fillList(perm, on)
    }

    private fun fillList(perm: Boolean, on: Set<android.content.ComponentName>) {
        list.removeAllViews()
        val svcs = Acc.installed(this)
        if (svcs.isEmpty()) {
            list.addView(TextView(this).apply { text = "ماكو تطبيقات." ; textSize = 14f })
            return
        }
        val keep = Prefs.whitelist(this)
        for (s in svcs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val sw = Switch(this).apply {
                text = s.label
                textSize = 15f
                setTextColor(0xFF111827.toInt())
                isChecked = on.contains(s.cn)
                isEnabled = perm
            }
            sw.setOnCheckedChangeListener { _, c ->
                if (!Acc.toggle(this, s.cn, c)) {
                    Toast.makeText(this, "ما گدرت أغيّرها", Toast.LENGTH_SHORT).show()
                }
                Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 150)
            }
            row.addView(sw, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(CheckBox(this).apply {
                text = "استثناء"
                textSize = 13f
                isChecked = keep.contains(s.cn.flattenToString())
                setOnCheckedChangeListener { _, c -> Prefs.setKeep(this@MainActivity, s.cn, c) }
            })
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() })
        }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "أول شي فعّل الظهور فوق التطبيقات", Toast.LENGTH_LONG).show()
            return
        }
        if (!Acc.canWrite(this)) {
            Toast.makeText(this, "الصلاحية ناقصة — الفقاعة تشتغل بس ما تگدر تغيّر شي", Toast.LENGTH_LONG).show()
        }
        val i = Intent(this, BubbleService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 400)
    }

    private fun rounded(c: Int) = GradientDrawable().apply {
        setColor(c)
        cornerRadius = 16 * dp
    }

    private fun button(t: String, col: Int, f: () -> Unit) = Button(this).apply {
        text = t
        textSize = 16f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = rounded(col)
        setOnClickListener { f() }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (top * dp).toInt() }
}
