package com.snooker.bubble

import android.content.Context

object Prefs {
    private const val NAME = "snooker_bubble"

    /** منظرين للكاميرا: من فوق ، و 3D */
    const val SLOT_TOP = "top"
    const val SLOT_3D = "3d"
    val SLOTS = arrayOf(SLOT_TOP, SLOT_3D)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private fun key(landscape: Boolean, slot: String) =
        "cal4_" + (if (landscape) "land_" else "port_") + slot

    /** 4 جيوب محفوظة كنِسَب (0..1) من عرض وطول الشاشة */
    fun savePockets(ctx: Context, landscape: Boolean, slot: String, pts: List<FloatArray>) {
        val s = pts.joinToString(";") { "${it[0]},${it[1]}" }
        sp(ctx).edit().putString(key(landscape, slot), s).apply()
    }

    fun loadPockets(ctx: Context, landscape: Boolean, slot: String): Array<FloatArray>? {
        val s = sp(ctx).getString(key(landscape, slot), null) ?: return null
        val list = s.split(";").mapNotNull { part ->
            val a = part.split(",")
            if (a.size == 2) {
                val x = a[0].toFloatOrNull()
                val y = a[1].toFloatOrNull()
                if (x != null && y != null) floatArrayOf(x, y) else null
            } else null
        }
        return if (list.size == 4) list.toTypedArray() else null
    }

    fun hasAny(ctx: Context, landscape: Boolean): Boolean =
        SLOTS.any { loadPockets(ctx, landscape, it) != null }

    fun clearPockets(ctx: Context) {
        val e = sp(ctx).edit()
        for (l in arrayOf(true, false)) for (s in SLOTS) e.remove(key(l, s))
        e.apply()
    }

    /** هامش الخطأ المسموح بالدرجات */
    fun tolerance(ctx: Context): Int = sp(ctx).getInt("tol", 3)

    fun setTolerance(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("tol", v).apply()
    }
}
