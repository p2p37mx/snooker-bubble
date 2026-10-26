package com.snooker.bubble

import android.content.Context

object Prefs {
    private const val NAME = "snooker_bubble"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private fun key(landscape: Boolean) = if (landscape) "pockets_land" else "pockets_port"

    /** الجيوب محفوظة كنِسَب (0..1) من عرض وطول الشاشة */
    fun savePockets(ctx: Context, landscape: Boolean, pts: List<FloatArray>) {
        val s = pts.joinToString(";") { "${it[0]},${it[1]}" }
        sp(ctx).edit().putString(key(landscape), s).apply()
    }

    fun loadPockets(ctx: Context, landscape: Boolean): Array<FloatArray>? {
        val s = sp(ctx).getString(key(landscape), null) ?: return null
        val list = s.split(";").mapNotNull { part ->
            val a = part.split(",")
            if (a.size == 2) {
                val x = a[0].toFloatOrNull()
                val y = a[1].toFloatOrNull()
                if (x != null && y != null) floatArrayOf(x, y) else null
            } else null
        }
        return if (list.size == 6) list.toTypedArray() else null
    }

    fun clearPockets(ctx: Context) {
        sp(ctx).edit().remove(key(true)).remove(key(false)).apply()
    }

    /** هامش الخطأ المسموح بالدرجات */
    fun tolerance(ctx: Context): Int = sp(ctx).getInt("tol", 3)

    fun setTolerance(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("tol", v).apply()
    }
}
