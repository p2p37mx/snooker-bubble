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
 * يحلل صورة مصغّرة من الشاشة:
 * 1) يلگه الكرة البيضة (أكبر دائرة بيضة على الطاولة).
 * 2) يلگه خط التصويب الأبيض اللي ترسمه اللعبة ويحسب اتجاهه.
 * 3) يلگه الكرة الملونة اللي راح تنضرب.
 * 4) يحسب وين راح تروح الكرة الملونة بعد الضربة ويشوف هل تدخل بأي جيب.
 */
class Analyzer {

    enum class State { NONE, GOOD, BAD }

    class Result(val state: State, val errDeg: Float)

    private val none = Result(State.NONE, 0f)

    // لون القماش (يتحدث تلقائياً)
    private var feltR = 20
    private var feltG = 110
    private var feltB = 60
    private var frame = 0

    // 0 = قماش/خلفية ، 1 = أبيض ، 2 = كرة/جسم ملون
    private var mask = ByteArray(0)
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

    fun analyze(
        px: IntArray, w: Int, h: Int,
        pockets: Array<FloatArray>,
        excl: IntArray?,
        tolDeg: Float
    ): Result {
        // حدود الطاولة من الجيوب
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -1f
        var maxY = -1f
        for (p in pockets) {
            minX = min(minX, p[0]); minY = min(minY, p[1])
            maxX = max(maxX, p[0]); maxY = max(maxY, p[1])
        }
        val x0 = max(0, minX.toInt())
        val y0 = max(0, minY.toInt())
        val x1 = min(w - 1, maxX.toInt())
        val y1 = min(h - 1, maxY.toInt())
        tw = x1 - x0 + 1
        th = y1 - y0 + 1
        if (tw < 60 || th < 30) return none
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

        val longSide = max(tw, th).toFloat()
        val band = max(2, (longSide / 90f).toInt())
        val pocketR = longSide / 38f
        val pr2 = pocketR * pocketR
        val pkx = FloatArray(pockets.size) { pockets[it][0] - x0 }
        val pky = FloatArray(pockets.size) { pockets[it][1] - y0 }

        // ---------- تصنيف البكسلات ----------
        for (y in 0 until th) {
            val row = (y0 + y) * w + x0
            val gy = y + y0
            for (x in 0 until tw) {
                var m = 0
                val gx = x + x0
                val inExcl = excl != null &&
                        gx >= excl[0] && gx <= excl[2] && gy >= excl[1] && gy <= excl[3]
                if (!inExcl) {
                    val c = px[row + x]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    val mn = min(r, min(g, b))
                    val mx = max(r, max(g, b))
                    if (mn >= 175 && mx - mn <= 55) {
                        m = 1
                    } else {
                        val dr = r - feltR
                        val dg = g - feltG
                        val db = b - feltB
                        if (dr * dr + dg * dg + db * db > 4900) {
                            m = 2
                            if (x < band || y < band || x >= tw - band || y >= th - band) {
                                m = 0
                            } else {
                                for (k in pkx.indices) {
                                    val ddx = x - pkx[k]
                                    val ddy = y - pky[k]
                                    if (ddx * ddx + ddy * ddy < pr2) { m = 0; break }
                                }
                            }
                        }
                    }
                }
                mask[y * tw + x] = m.toByte()
            }
        }
        buildIntegrals()

        // ---------- 1) الكرة البيضة ----------
        val dMax = max(6, (longSide / 28f).toInt())
        val dMin = max(4, (longSide / 90f).toInt())
        var cx = -1f
        var cy = -1f
        var rad = 0f
        var d = dMax
        while (d >= dMin) {
            if (d >= tw || d >= th) { d--; continue }
            val need = (0.62f * d * d).toInt()
            val step = max(1, d / 4)
            var best = -1
            var bx = 0
            var by = 0
            var y = 0
            while (y + d <= th) {
                var x = 0
                while (x + d <= tw) {
                    val s = box(whiteI, x, y, d)
                    if (s > best) { best = s; bx = x; by = y }
                    x += step
                }
                y += step
            }
            if (best >= need) {
                // تدقيق المكان
                val ry0 = max(0, by - step); val ry1 = min(th - d, by + step)
                val rx0 = max(0, bx - step); val rx1 = min(tw - d, bx + step)
                for (yy in ry0..ry1) for (xx in rx0..rx1) {
                    val s = box(whiteI, xx, yy, d)
                    if (s > best) { best = s; bx = xx; by = yy }
                }
                var sx = 0f; var sy = 0f; var cnt = 0
                for (yy in by until by + d) for (xx in bx until bx + d) {
                    if (mask[yy * tw + xx].toInt() == 1) { sx += xx; sy += yy; cnt++ }
                }
                if (cnt > 0) {
                    cx = sx / cnt; cy = sy / cnt
                    rad = d * 0.46f
                }
                break
            }
            d--
        }
        if (rad <= 0f) return none

        // ---------- 2) اتجاه خط التصويب ----------
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
        var peak = -1
        var peakSum = 0f
        for (i in 0 until 360) {
            val s = hist[(i + 359) % 360] + hist[i] + hist[(i + 1) % 360]
            if (s > peakSum) { peakSum = s; peak = i }
        }
        if (peak < 0 || peakSum < max(5f, rad * 1.5f)) return none

        var vx = 0f; var vy = 0f
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
        val dirX = cos(theta)
        val dirY = sin(theta)

        // ---------- 3) نمشي على الخط لحد ما نوصل الكرة الملونة ----------
        var t = rad * 1.5f
        var lastWhite = -1f
        var hitBall = -1f
        val maxGap = rad * 3f
        while (true) {
            val x = cx + dirX * t
            val y = cy + dirY * t
            if (x < 1 || y < 1 || x >= tw - 1 || y >= th - 1) break
            if (t > rad * 2f && count(x, y, 2) >= 5) { hitBall = t; break }
            if (count(x, y, 1) > 0) {
                lastWhite = t
            } else if (lastWhite > 0 && t - lastWhite > maxGap) {
                break
            } else if (lastWhite < 0 && t > rad * 4f) {
                break
            }
            t += 1f
        }
        val qx: Float
        val qy: Float
        if (hitBall > 0) {
            qx = cx + dirX * (hitBall + rad)
            qy = cy + dirY * (hitBall + rad)
        } else {
            if (lastWhite < rad * 2f) return none
            qx = cx + dirX * lastWhite
            qy = cy + dirY * lastWhite
        }

        // البحث عن الكرة الملونة قرب نهاية الخط
        val bs = max(3, (rad * 1.3f).toInt())
        val sr = (rad * 4f).toInt()
        val sy0 = max(0, qy.toInt() - sr); val sy1 = min(th - bs, qy.toInt() + sr)
        val sx0 = max(0, qx.toInt() - sr); val sx1 = min(tw - bs, qx.toInt() + sr)
        var bBest = -1
        var obx = 0
        var oby = 0
        if (sy1 < sy0 || sx1 < sx0) return none
        for (y in sy0..sy1) for (x in sx0..sx1) {
            val s = box(ballI, x, y, bs)
            if (s > bBest) { bBest = s; obx = x; oby = y }
        }
        if (bBest < (0.45f * bs * bs)) return none

        val wcx = obx + bs / 2f
        val wcy = oby + bs / 2f
        var osx = 0f; var osy = 0f; var oc = 0
        val rr = rad * 1.1f
        val ey0 = max(0, (wcy - rr).toInt()); val ey1 = min(th - 1, (wcy + rr).toInt())
        val ex0 = max(0, (wcx - rr).toInt()); val ex1 = min(tw - 1, (wcx + rr).toInt())
        for (y in ey0..ey1) for (x in ex0..ex1) {
            if (mask[y * tw + x].toInt() != 2) continue
            val dx = x - wcx; val dy = y - wcy
            if (dx * dx + dy * dy <= rr * rr) { osx += x; osy += y; oc++ }
        }
        if (oc == 0) return none
        val ox = osx / oc
        val oy = osy / oc

        // ---------- 4) مكان الكرة البيضة لحظة الاصطدام ----------
        val fx = cx - ox
        val fy = cy - oy
        val fd = fx * dirX + fy * dirY
        val ff = fx * fx + fy * fy
        val twoR = 2f * rad
        val disc = fd * fd - (ff - twoR * twoR)
        val tHit: Float
        if (disc >= 0f) {
            tHit = -fd - sqrt(disc)
        } else {
            val wide = twoR * 1.25f
            if (fd * fd - (ff - wide * wide) < 0f) return Result(State.BAD, 99f)
            tHit = -fd
        }
        if (tHit <= 0f) return none
        val gx = cx + dirX * tHit
        val gy = cy + dirY * tHit
        var ux = ox - gx
        var uy = oy - gy
        val ul = hypot(ux, uy)
        if (ul < 1e-3f) return none
        ux /= ul; uy /= ul

        // ---------- 5) هل تدخل بجيب؟ ----------
        var bestErr = 999f
        for (k in pkx.indices) {
            val px2 = pkx[k] - ox
            val py2 = pky[k] - oy
            val dist = hypot(px2, py2)
            if (dist < rad) continue
            val c = (ux * px2 + uy * py2) / dist
            if (c <= 0f) continue
            val ang = Math.toDegrees(acos(c.coerceIn(-1f, 1f)).toDouble()).toFloat()
            val allow = Math.toDegrees(atan((rad * 1.0f) / dist).toDouble()).toFloat()
            val err = max(0f, ang - allow)
            if (err < bestErr) bestErr = err
        }
        if (bestErr >= 999f) return Result(State.BAD, 99f)
        return if (bestErr <= tolDeg) Result(State.GOOD, bestErr) else Result(State.BAD, bestErr)
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
            val row = (y0 + y) * w + x0
            var x = 0
            while (x < tw) {
                val c = px[row + x]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                bucket[k]++
                sumR[k] += r; sumG[k] += g; sumB[k] += b
                x += 3
            }
            y += 3
        }
        var best = 0
        for (i in 1 until 4096) if (bucket[i] > bucket[best]) best = i
        val cnt = bucket[best]
        if (cnt > 0) {
            feltR = sumR[best] / cnt
            feltG = sumG[best] / cnt
            feltB = sumB[best] / cnt
        }
    }
}
