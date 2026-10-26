package com.snooker.bubble

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * يحلل صورة الشاشة:
 * - الطاولة معرّفة بـ 4 جيوب (زاويتين بعيدتين + جيبين وسطيين) => homography
 *   تحوّل الشاشة (حتى لو 3D) لإحداثيات طاولة حقيقية: العرض = 1 ، الطول = 2.
 * - الكرة البيضة = دائرة بيضة محاطة بالقماش، يطلع منها خط التصويب.
 * - نمشي على خط التصويب لحد الكرة الملونة.
 * - نحسب نقطة الاصطدام بحجم الكرة الحقيقي، ونشوف اتجاه الكرة الملونة يدخل جيب لو لا.
 */
class Analyzer {

    enum class State { NONE, GOOD, BAD }

    class Result(val state: State, val errDeg: Float, val reason: String)

    /** معلومات التشخيص لآخر إطار (بإحداثيات الصورة) */
    class Debug {
        var hasCue = false
        var cx = 0f; var cy = 0f; var rad = 0f
        var hasLine = false
        var ex = 0f; var ey = 0f
        var hasObj = false
        var ox = 0f; var oy = 0f; var orad = 0f
        var hasPath = false
        var px = 0f; var py = 0f
        var reason = ""
        var errDeg = 0f
        var state = State.NONE
    }

    val debug = Debug()

    companion object {
        const val BALL_R = 0.0148f
        private val OFFSETS = intArrayOf(0, 1, -1, 2, -2, 3, -3)

        val POCKETS = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(1f, 0f),
            floatArrayOf(0f, 1f), floatArrayOf(1f, 1f),
            floatArrayOf(0f, 2f), floatArrayOf(1f, 2f)
        )

        /** ترتيب نقاط المعايرة: زاوية1 ، زاوية2 (نفس الطرف) ، وسطي بجهة1 ، وسطي بجهة2 */
        val CAL_TABLE = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(1f, 0f),
            floatArrayOf(0f, 1f), floatArrayOf(1f, 1f)
        )

        fun homography(src: Array<FloatArray>, dst: Array<FloatArray>): DoubleArray? {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = src[i][0].toDouble(); val y = src[i][1].toDouble()
                val u = dst[i][0].toDouble(); val v = dst[i][1].toDouble()
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (c in col until 9) a[r][c] -= f * a[col][c]
                }
            }
            val h = DoubleArray(9)
            for (i in 0 until 8) h[i] = a[i][8] / a[i][i]
            h[8] = 1.0
            return h
        }

        fun invert(m: DoubleArray): DoubleArray? {
            val a = m[0]; val b = m[1]; val c = m[2]
            val d = m[3]; val e = m[4]; val f = m[5]
            val g = m[6]; val h = m[7]; val i = m[8]
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            if (abs(det) < 1e-18) return null
            val k = 1.0 / det
            return doubleArrayOf(
                (e * i - f * h) * k, (c * h - b * i) * k, (b * f - c * e) * k,
                (f * g - d * i) * k, (a * i - c * g) * k, (c * d - a * f) * k,
                (d * h - e * g) * k, (b * g - a * h) * k, (a * e - b * d) * k
            )
        }

        fun apply(m: DoubleArray, x: Float, y: Float, out: FloatArray): Boolean {
            val w = m[6] * x + m[7] * y + m[8]
            if (abs(w) < 1e-12) return false
            out[0] = ((m[0] * x + m[1] * y + m[2]) / w).toFloat()
            out[1] = ((m[3] * x + m[4] * y + m[5]) / w).toFloat()
            return true
        }
    }

    // لون القماش
    private var feltHue = 120f
    private var frame = 0

    // منطقة الطاولة (مخزنة)
    private var region = ByteArray(0)      // 0 خارج ، 1 داخل ، 2 داخل قرب جيب
    private var regionH: DoubleArray? = null
    private var regionW = 0
    private var regionHt = 0
    private var rx0 = 0; private var ry0 = 0; private var rx1 = -1; private var ry1 = -1

    // صور العمل (بإحداثيات محلية داخل حدود الطاولة)
    private var mask = ByteArray(0)        // 0 لا شي ، 1 أبيض ، 2 كرة/جسم ، 3 قماش
    private var whiteI = IntArray(0)
    private var ballI = IntArray(0)
    private val hist = FloatArray(360)
    private val bucket = IntArray(4096)
    private val sumR = IntArray(4096)
    private val sumG = IntArray(4096)
    private val sumB = IntArray(4096)

    private var tw = 0
    private var th = 0
    private var w1 = 0
    private val tmp = FloatArray(2)
    private val tmp2 = FloatArray(2)
    private val tmp3 = FloatArray(2)

    private fun fail(reason: String): Result {
        debug.reason = reason
        debug.state = State.NONE
        return Result(State.NONE, 0f, reason)
    }

    private fun buildRegion(H: DoubleArray, w: Int, h: Int, refX: Float, refY: Float) {
        if (region.size < w * h) region = ByteArray(w * h)
        val refSign = H[6] * refX + H[7] * refY + H[8] > 0
        rx0 = w; ry0 = h; rx1 = -1; ry1 = -1
        val pr2 = 0.05f * 0.05f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ww = H[6] * x + H[7] * y + H[8]
                var r = 0
                if (ww != 0.0 && (ww > 0) == refSign) {
                    val u = ((H[0] * x + H[1] * y + H[2]) / ww).toFloat()
                    val v = ((H[3] * x + H[4] * y + H[5]) / ww).toFloat()
                    if (u > 0.012f && u < 0.988f && v > 0.012f && v < 1.988f) {
                        r = 1
                        for (p in POCKETS) {
                            val du = u - p[0]; val dv = v - p[1]
                            if (du * du + dv * dv < pr2) { r = 2; break }
                        }
                    }
                }
                region[y * w + x] = r.toByte()
                if (r != 0) {
                    if (x < rx0) rx0 = x
                    if (x > rx1) rx1 = x
                    if (y < ry0) ry0 = y
                    if (y > ry1) ry1 = y
                }
            }
        }
        regionH = H.copyOf()
        regionW = w
        regionHt = h
    }

    fun analyze(
        px: IntArray, w: Int, h: Int,
        H: DoubleArray, Hi: DoubleArray,
        refX: Float, refY: Float,
        excl: IntArray?, tolDeg: Float
    ): Result {
        debug.hasCue = false; debug.hasLine = false; debug.hasObj = false; debug.hasPath = false

        val rh = regionH
        if (rh == null || regionW != w || regionHt != h || !rh.contentEquals(H)) {
            buildRegion(H, w, h, refX, refY)
            frame = 0
        }
        if (rx1 < rx0 || ry1 < ry0) return fail("الطاولة خارج الشاشة - عيد المعايرة")
        val x0 = rx0; val y0 = ry0
        tw = rx1 - rx0 + 1
        th = ry1 - ry0 + 1
        if (tw < 60 || th < 30) return fail("الطاولة صغيرة - عيد المعايرة")
        w1 = tw + 1

        if (frame % 25 == 0) estimateFelt(px, w, x0, y0)
        frame++

        val n = tw * th
        if (mask.size < n) mask = ByteArray(n)
        val ni = (tw + 1) * (th + 1)
        if (whiteI.size < ni) {
            whiteI = IntArray(ni)
            ballI = IntArray(ni)
        }

        // ---------- تصنيف البكسلات ----------
        for (y in 0 until th) {
            val gy = y + y0
            val row = gy * w
            for (x in 0 until tw) {
                val gx = x + x0
                val rg = region[row + gx].toInt()
                var m = 0
                val inExcl = excl != null &&
                        gx >= excl[0] && gx <= excl[2] && gy >= excl[1] && gy <= excl[3]
                if (rg != 0 && !inExcl) {
                    val c = px[row + gx]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    val mn = min(r, min(g, b))
                    val mx = max(r, max(g, b))
                    if (mn >= 140 && mx - mn <= 70 && r - g <= 35) {
                        m = 1
                    } else if (rg == 2) {
                        m = 0
                    } else {
                        var felt = false
                        val ch = mx - mn
                        if (mx >= 50 && ch > 0.3f * mx) {
                            var dh = abs(hue(r, g, b, mx, ch) - feltHue)
                            if (dh > 180f) dh = 360f - dh
                            felt = dh < 22f
                        }
                        m = if (felt) 3 else 2
                    }
                }
                mask[y * tw + x] = m.toByte()
            }
        }
        buildIntegrals()

        // ---------- 1) مرشحين للكرة البيضة ----------
        val dMax = max(8, w / 35)
        val dMin = max(5, w / 130)
        val candX = FloatArray(12)
        val candY = FloatArray(12)
        val candR = FloatArray(12)
        var nc = 0
        var d = dMax
        outer@ while (d >= dMin) {
            if (d * 2 >= tw || d * 2 >= th) { d--; continue }
            val need = (0.55f * d * d).toInt()
            val step = max(1, d / 4)
            val bx = (1.4f * d).toInt()
            val q = max(1, bx / 4)
            var y = 0
            while (y + d <= th) {
                var x = 0
                while (x + d <= tw) {
                    val s = box(whiteI, x, y, d)
                    if (s >= need) {
                        val ox = x + d / 2 - bx / 2
                        val oy = y + d / 2 - bx / 2
                        if (ox >= 0 && oy >= 0 && ox + bx <= tw && oy + bx <= th) {
                            val cs = box(whiteI, ox, oy, q) + box(whiteI, ox + bx - q, oy, q) +
                                    box(whiteI, ox, oy + bx - q, q) + box(whiteI, ox + bx - q, oy + bx - q, q)
                            if (cs <= q * q) {
                                val ccx = x + d / 2f
                                val ccy = y + d / 2f
                                var overlap = false
                                for (k in 0 until nc) {
                                    if (hypot(ccx - candX[k], ccy - candY[k]) < max(d.toFloat(), candR[k] * 2f)) {
                                        overlap = true; break
                                    }
                                }
                                if (!overlap && feltRing(ccx, ccy, d * 0.46f * 1.8f) >= 14) {
                                    candX[nc] = ccx; candY[nc] = ccy; candR[nc] = d * 0.46f
                                    nc++
                                    if (nc >= 12) break@outer
                                }
                            }
                        }
                    }
                    x += step
                }
                y += step
            }
            d--
        }
        if (nc == 0) return fail("ما لگيت الكرة البيضة")

        // ---------- 2) أي مرشح يطلع منه خط تصويب ----------
        var bestScore = -1f
        var cx = 0f; var cy = 0f; var rad = 0f; var peak = 0
        for (k in 0 until nc) {
            val r0 = candR[k]
            var sx = 0f; var sy = 0f; var cnt = 0
            val yA = max(0, (candY[k] - r0).toInt()); val yB = min(th - 1, (candY[k] + r0).toInt())
            val xA = max(0, (candX[k] - r0).toInt()); val xB = min(tw - 1, (candX[k] + r0).toInt())
            for (yy in yA..yB) for (xx in xA..xB) {
                if (mask[yy * tw + xx].toInt() == 1) { sx += xx; sy += yy; cnt++ }
            }
            if (cnt == 0) continue
            val ccx = sx / cnt; val ccy = sy / cnt
            val pk = aimHistogram(ccx, ccy, r0)
            val score = peakSum / r0
            if (score > bestScore) {
                bestScore = score; cx = ccx; cy = ccy; rad = r0; peak = pk
            }
        }
        if (bestScore < 0f) return fail("ما لگيت الكرة البيضة")
        debug.hasCue = true
        debug.cx = cx + x0; debug.cy = cy + y0; debug.rad = rad
        if (bestScore < 3f) return fail("ما لگيت خط التصويب")

        // اتجاه دقيق
        aimHistogram(cx, cy, rad)
        val rIn = rad * 1.5f
        val rOut = rad * 14f
        var vx = 0f; var vy = 0f
        val ax0 = max(0, (cx - rOut).toInt()); val ax1 = min(tw - 1, (cx + rOut).toInt())
        val ay0 = max(0, (cy - rOut).toInt()); val ay1 = min(th - 1, (cy + rOut).toInt())
        for (y in ay0..ay1) {
            val row = y * tw
            for (x in ax0..ax1) {
                if (mask[row + x].toInt() != 1) continue
                val dx = x - cx; val dy = y - cy
                val dd = dx * dx + dy * dy
                if (dd < rIn * rIn || dd > rOut * rOut) continue
                var a = Math.toDegrees(atan2(dy, dx).toDouble()).toInt()
                if (a < 0) a += 360
                var diff = abs(a - peak)
                if (diff > 180) diff = 360 - diff
                if (diff <= 2) {
                    val l = sqrt(dd)
                    vx += dx / l; vy += dy / l
                }
            }
        }
        val theta = atan2(vy, vx)

        // ---------- 3) نتبع الخط (متقطع أحياناً) ----------
        var dirX = cos(theta)
        var dirY = sin(theta)
        var t = rad * 1.5f
        var lastWhite = -1f
        var hitBall = -1f
        var nFound = 0
        val maxGap = max(rad * 3f, w / 20f)
        while (true) {
            val x = cx + dirX * t
            val y = cy + dirY * t
            if (x < 4 || y < 4 || x >= tw - 4 || y >= th - 4) break
            if (t > rad * 2f && count(x, y, 2) >= 5) { hitBall = t; break }
            var fx = -1
            var fy = -1
            for (o in OFFSETS) {
                val xx = Math.round(x - dirY * o)
                val yy = Math.round(y + dirX * o)
                if (xx in 0 until tw && yy in 0 until th && mask[yy * tw + xx].toInt() == 1) {
                    fx = xx; fy = yy; break
                }
            }
            if (fx >= 0) {
                lastWhite = t
                nFound++
                if (nFound % 8 == 0 && t > rad * 6f) {
                    val ddx = fx - cx
                    val ddy = fy - cy
                    val l = hypot(ddx, ddy)
                    if (l > 0f) { dirX = ddx / l; dirY = ddy / l }
                }
            } else if (lastWhite > 0 && t - lastWhite > maxGap) {
                break
            } else if (lastWhite < 0 && t > rad * 4f) {
                break
            }
            t += 1f
        }
        if (lastWhite < rad * 2f) return fail("خط التصويب قصير")
        val ex = cx + dirX * lastWhite
        val ey = cy + dirY * lastWhite
        debug.hasLine = true
        debug.ex = ex + x0; debug.ey = ey + y0

        // ---------- 4) الكرة الملونة قرب نهاية الخط ----------
        val qx = if (hitBall > 0) cx + dirX * (hitBall + rad * 0.5f) else ex
        val qy = if (hitBall > 0) cy + dirY * (hitBall + rad * 0.5f) else ey
        val bs = max(3, rad.toInt())
        val sr = (rad * 4f).toInt()
        val sy0 = max(0, qy.toInt() - sr); val sy1 = min(th - bs, qy.toInt() + sr)
        val sx0 = max(0, qx.toInt() - sr); val sx1 = min(tw - bs, qx.toInt() + sr)
        if (sy1 < sy0 || sx1 < sx0) return fail("ما لگيت الكرة الملونة")
        var bBest = -1
        var obx = 0
        var oby = 0
        for (y in sy0..sy1) for (x in sx0..sx1) {
            val s = box(ballI, x, y, bs)
            if (s > bBest) { bBest = s; obx = x; oby = y }
        }
        if (bBest < (0.45f * bs * bs)) return fail("ما لگيت الكرة الملونة")
        val wcx = obx + bs / 2f
        val wcy = oby + bs / 2f
        var osx = 0f; var osy = 0f; var oc = 0
        val rr = rad * 1.3f
        val ey0 = max(0, (wcy - rr).toInt()); val ey1 = min(th - 1, (wcy + rr).toInt())
        val ex0 = max(0, (wcx - rr).toInt()); val ex1 = min(tw - 1, (wcx + rr).toInt())
        for (y in ey0..ey1) for (x in ex0..ex1) {
            if (mask[y * tw + x].toInt() != 2) continue
            val dx = x - wcx; val dy = y - wcy
            if (dx * dx + dy * dy <= rr * rr) { osx += x; osy += y; oc++ }
        }
        if (oc == 0) return fail("ما لگيت الكرة الملونة")
        val ox = osx / oc
        val oy = osy / oc
        val orad = sqrt(oc / Math.PI.toFloat()).coerceIn(rad * 0.4f, rad * 1.3f)
        debug.hasObj = true
        debug.ox = ox + x0; debug.oy = oy + y0; debug.orad = orad

        // ---------- 5) الحساب على الطاولة الحقيقية ----------
        val gOx = ox + x0
        val gOy = oy + y0
        // تصحيح ارتفاع الكرة بالمنظور 3D
        if (!apply(H, gOx, gOy, tmp)) return fail("خطأ بالمعايرة")
        val f = foreshortening(Hi, tmp[0], tmp[1])
        val shift = orad * sqrt(max(0f, 1f - f * f))
        if (!apply(H, gOx, gOy + shift, tmp)) return fail("خطأ بالمعايرة")
        val otx = tmp[0]; val oty = tmp[1]

        if (!apply(H, cx + dirX * 3f * rad + x0, cy + dirY * 3f * rad + y0, tmp2)) return fail("خطأ بالمعايرة")
        if (!apply(H, ex + x0, ey + y0, tmp3)) return fail("خطأ بالمعايرة")
        var dtx = tmp3[0] - tmp2[0]
        var dty = tmp3[1] - tmp2[1]
        val dl = hypot(dtx, dty)
        if (dl < 1e-5f) return fail("خط التصويب قصير")
        dtx /= dl; dty /= dl

        val fx = tmp2[0] - otx
        val fy = tmp2[1] - oty
        val fd = fx * dtx + fy * dty
        val ff = fx * fx + fy * fy
        val twoR = 2f * BALL_R
        val disc = fd * fd - (ff - twoR * twoR)
        val tHit: Float
        if (disc >= 0f) {
            tHit = -fd - sqrt(disc)
        } else {
            val wide = twoR * 1.6f
            if (fd * fd - (ff - wide * wide) < 0f) {
                debug.reason = "الضربة ما تلمس الكرة"
                debug.state = State.BAD; debug.errDeg = 99f
                return Result(State.BAD, 99f, debug.reason)
            }
            tHit = -fd
        }
        val gtx = tmp2[0] + dtx * tHit
        val gty = tmp2[1] + dty * tHit
        var ux = otx - gtx
        var uy = oty - gty
        val ul = hypot(ux, uy)
        if (ul < 1e-6f) return fail("خطأ بالحساب")
        ux /= ul; uy /= ul

        // ---------- 6) هل تدخل بجيب؟ ----------
        var bestErr = 999f
        var bestK = -1
        for ((k, p) in POCKETS.withIndex()) {
            val pxv = p[0] - otx
            val pyv = p[1] - oty
            val dist = hypot(pxv, pyv)
            if (dist < BALL_R) continue
            val c = (ux * pxv + uy * pyv) / dist
            if (c <= 0f) continue
            val ang = Math.toDegrees(acos(c.coerceIn(-1f, 1f)).toDouble()).toFloat()
            val allow = Math.toDegrees(atan(0.028f / dist).toDouble()).toFloat()
            val err = max(0f, ang - allow)
            if (err < bestErr) { bestErr = err; bestK = k }
        }
        val st = if (bestErr <= tolDeg) State.GOOD else State.BAD

        // نهاية خط الاتجاه: الجيب إذا داخلة، وإلا حافة الطاولة
        var endU: Float
        var endV: Float
        if (st == State.GOOD && bestK >= 0) {
            endU = POCKETS[bestK][0]; endV = POCKETS[bestK][1]
        } else {
            var tEnd = 3f
            if (ux > 1e-6f) tEnd = min(tEnd, (1f - otx) / ux)
            if (ux < -1e-6f) tEnd = min(tEnd, (0f - otx) / ux)
            if (uy > 1e-6f) tEnd = min(tEnd, (2f - oty) / uy)
            if (uy < -1e-6f) tEnd = min(tEnd, (0f - oty) / uy)
            tEnd = max(0f, tEnd)
            endU = otx + ux * tEnd; endV = oty + uy * tEnd
        }
        if (apply(Hi, endU, endV, tmp)) {
            debug.hasPath = true
            debug.px = tmp[0]; debug.py = tmp[1]
        }
        val e = if (bestErr >= 999f) 99f else bestErr
        debug.state = st
        debug.errDeg = e
        debug.reason = if (st == State.GOOD) "داخلة ✓" else "مو داخلة"
        return Result(st, e, debug.reason)
    }

    private var peakSum = 0f

    private fun aimHistogram(cx: Float, cy: Float, rad: Float): Int {
        java.util.Arrays.fill(hist, 0f)
        val rIn = rad * 1.5f
        val rOut = rad * 14f
        val ax0 = max(0, (cx - rOut).toInt()); val ax1 = min(tw - 1, (cx + rOut).toInt())
        val ay0 = max(0, (cy - rOut).toInt()); val ay1 = min(th - 1, (cy + rOut).toInt())
        for (y in ay0..ay1) {
            val row = y * tw
            for (x in ax0..ax1) {
                if (mask[row + x].toInt() != 1) continue
                val dx = x - cx; val dy = y - cy
                val dd = dx * dx + dy * dy
                if (dd < rIn * rIn || dd > rOut * rOut) continue
                var a = Math.toDegrees(atan2(dy, dx).toDouble()).toInt()
                if (a < 0) a += 360
                hist[a % 360] += 1f
            }
        }
        var peak = 0
        var best = 0f
        for (i in 0 until 360) {
            val s = hist[(i + 359) % 360] + hist[i] + hist[(i + 1) % 360]
            if (s > best) { best = s; peak = i }
        }
        peakSum = best
        return peak
    }

    private fun feltRing(cx: Float, cy: Float, r: Float): Int {
        var n = 0
        for (i in 0 until 32) {
            val a = i * Math.PI / 16.0
            val x = (cx + r * cos(a)).toInt()
            val y = (cy + r * sin(a)).toInt()
            if (x in 0 until tw && y in 0 until th && mask[y * tw + x].toInt() == 3) n++
        }
        return n
    }

    /** نسبة الانضغاط بالمنظور (1 = منظر من فوق ، أصغر = 3D مايل) */
    fun foreshortening(Hi: DoubleArray, u: Float, v: Float): Float {
        val e = 0.01f
        val a = FloatArray(2); val b = FloatArray(2); val c = FloatArray(2)
        if (!apply(Hi, u, v, a) || !apply(Hi, u + e, v, b) || !apply(Hi, u, v + e, c)) return 1f
        val p1 = (b[0] - a[0]) / e; val p2 = (b[1] - a[1]) / e
        val q1 = (c[0] - a[0]) / e; val q2 = (c[1] - a[1]) / e
        val pp = p1 * p1 + p2 * p2
        val qq = q1 * q1 + q2 * q2
        val pq = p1 * q1 + p2 * q2
        val tr = pp + qq
        val det = pp * qq - pq * pq
        val dsc = sqrt(max(0f, tr * tr / 4f - det))
        val l1 = tr / 2f + dsc
        val l2 = max(0f, tr / 2f - dsc)
        if (l1 <= 0f) return 1f
        return sqrt(l2 / l1).coerceIn(0f, 1f)
    }

    private fun hue(r: Int, g: Int, b: Int, mx: Int, ch: Int): Float {
        val c = ch.toFloat()
        val h = when (mx) {
            r -> ((g - b) / c).let { if (it < 0) it + 6f else it }
            g -> (b - r) / c + 2f
            else -> (r - g) / c + 4f
        }
        return h * 60f
    }

    private fun count(xf: Float, yf: Float, v: Int): Int {
        val xi = xf.toInt()
        val yi = yf.toInt()
        var c = 0
        for (yy in yi - 1..yi + 1) for (xx in xi - 1..xi + 1) {
            if (xx < 0 || yy < 0 || xx >= tw || yy >= th) continue
            if (mask[yy * tw + xx].toInt() == v) c++
        }
        return c
    }

    private fun box(I: IntArray, x: Int, y: Int, s: Int): Int =
        I[(y + s) * w1 + x + s] - I[y * w1 + x + s] - I[(y + s) * w1 + x] + I[y * w1 + x]

    private fun buildIntegrals() {
        for (x in 0..tw) { whiteI[x] = 0; ballI[x] = 0 }
        for (y in 1..th) {
            val base = y * w1
            val prev = (y - 1) * w1
            whiteI[base] = 0
            ballI[base] = 0
            var rw = 0
            var rb = 0
            val mrow = (y - 1) * tw
            for (x in 1..tw) {
                val m = mask[mrow + x - 1].toInt()
                if (m == 1) rw++ else if (m == 2) rb++
                whiteI[base + x] = whiteI[prev + x] + rw
                ballI[base + x] = ballI[prev + x] + rb
            }
        }
    }

    /** أكثر لون متكرر داخل الطاولة = لون القماش */
    private fun estimateFelt(px: IntArray, w: Int, x0: Int, y0: Int) {
        java.util.Arrays.fill(bucket, 0)
        java.util.Arrays.fill(sumR, 0)
        java.util.Arrays.fill(sumG, 0)
        java.util.Arrays.fill(sumB, 0)
        var y = 0
        while (y < th) {
            val gy = y + y0
            var x = 0
            while (x < tw) {
                val gx = x + x0
                if (region[gy * w + gx].toInt() == 1) {
                    val c = px[gy * w + gx]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    bucket[k]++
                    sumR[k] += r; sumG[k] += g; sumB[k] += b
                }
                x += 3
            }
            y += 3
        }
        var best = 0
        for (i in 1 until 4096) if (bucket[i] > bucket[best]) best = i
        val cnt = bucket[best]
        if (cnt > 0) {
            val r = sumR[best] / cnt
            val g = sumG[best] / cnt
            val b = sumB[best] / cnt
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if (mx > mn) feltHue = hue(r, g, b, mx, mx - mn)
        }
    }
}
