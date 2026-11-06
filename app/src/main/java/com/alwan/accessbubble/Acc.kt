package com.alwan.accessbubble

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** كل شغل قراءة/كتابة خدمات إمكانية الوصول */
object Acc {

    const val PERM = "android.permission.WRITE_SECURE_SETTINGS"

    data class Svc(val cn: ComponentName, val label: String, val pkg: String)

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun grantCommand(ctx: Context) = "pm grant ${ctx.packageName} $PERM"

    /** كل التطبيقات اللي عدها خدمة إمكانية وصول */
    fun installed(ctx: Context): List<Svc> {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        val pm = ctx.packageManager
        return am.installedAccessibilityServiceList.mapNotNull { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
            if (si.packageName == ctx.packageName) return@mapNotNull null
            val app = try { si.applicationInfo.loadLabel(pm).toString() } catch (e: Exception) { si.packageName }
            val svc = try { si.loadLabel(pm).toString() } catch (e: Exception) { "" }
            val label = if (svc.isBlank() || svc == app) app else "$app — $svc"
            Svc(ComponentName(si.packageName, si.name), label, si.packageName)
        }.sortedBy { it.label.lowercase() }
    }

    /** الخدمات المفعّلة حالياً */
    fun enabled(ctx: Context): Set<ComponentName> {
        val s = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return emptySet()
        return s.split(':').mapNotNull {
            val t = it.trim()
            if (t.isEmpty()) null else ComponentName.unflattenFromString(t)
        }.toSet()
    }

    fun setEnabled(ctx: Context, set: Set<ComponentName>): Boolean = try {
        val cr = ctx.contentResolver
        Settings.Secure.putString(
            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            set.joinToString(":") { it.flattenToString() }
        )
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (set.isEmpty()) 0 else 1)
        true
    } catch (e: Exception) {
        false
    }

    fun toggle(ctx: Context, cn: ComponentName, on: Boolean): Boolean {
        val s = enabled(ctx).toMutableSet()
        if (on) s.add(cn) else s.remove(cn)
        return setEnabled(ctx, s)
    }

    /** يطفي الكل (عدا المستثناة) ويحفظ الوضع السابق حتى تگدر ترجعه */
    fun disableAll(ctx: Context): Boolean {
        val cur = enabled(ctx)
        if (cur.isNotEmpty()) Prefs.saveLast(ctx, cur)
        val keep = Prefs.whitelist(ctx)
        return setEnabled(ctx, cur.filter { keep.contains(it.flattenToString()) }.toSet())
    }

    fun restore(ctx: Context): Boolean {
        val last = Prefs.last(ctx)
        if (last.isEmpty()) return false
        return setEnabled(ctx, enabled(ctx) + last)
    }

    /** محاولة منح الصلاحية بالروت (إذا الجهاز مروّت) */
    fun tryRoot(ctx: Context): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", grantCommand(ctx)))
        p.waitFor() == 0 && canWrite(ctx)
    } catch (e: Exception) {
        false
    }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("acc", Context.MODE_PRIVATE)

    fun saveLast(ctx: Context, set: Set<ComponentName>) =
        sp(ctx).edit().putStringSet("last", set.map { it.flattenToString() }.toSet()).apply()

    fun last(ctx: Context): Set<ComponentName> =
        (sp(ctx).getStringSet("last", emptySet()) ?: emptySet())
            .mapNotNull { ComponentName.unflattenFromString(it) }.toSet()

    fun whitelist(ctx: Context): MutableSet<String> =
        HashSet(sp(ctx).getStringSet("keep", emptySet()) ?: emptySet())

    fun setKeep(ctx: Context, cn: ComponentName, keep: Boolean) {
        val s = whitelist(ctx)
        if (keep) s.add(cn.flattenToString()) else s.remove(cn.flattenToString())
        sp(ctx).edit().putStringSet("keep", s).apply()
    }

    fun autoOff(ctx: Context) = sp(ctx).getBoolean("autoOff", true)
    fun setAutoOff(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("autoOff", v).apply()

    fun restoreOnStop(ctx: Context) = sp(ctx).getBoolean("restoreOnStop", false)
    fun setRestoreOnStop(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("restoreOnStop", v).apply()

    fun pos(ctx: Context): IntArray? {
        val x = sp(ctx).getInt("bx", Int.MIN_VALUE)
        return if (x == Int.MIN_VALUE) null else intArrayOf(x, sp(ctx).getInt("by", 0))
    }
    fun setPos(ctx: Context, x: Int, y: Int) = sp(ctx).edit().putInt("bx", x).putInt("by", y).apply()
}
