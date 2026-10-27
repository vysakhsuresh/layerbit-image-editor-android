package com.layerbit.still.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Effects that read a neighbourhood or a position: blur, sharpening, clarity, dehaze's local
 * part, vignette and grain. All on packed ARGB arrays, all pure.
 *
 * Every radius here is scaled by [Geometry2D.resolutionScale], so a blur chosen on a preview is
 * the same blur, relative to the picture, on the export. The box blur is a sliding window and
 * costs the same at any radius, which is why nothing caps it.
 */
object Spatial {

    /** [radius] passes of a separable box, which approaches a Gaussian by the third pass. */
    fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3, check: () -> Unit = {}): IntArray {
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        var a = src.copyOf()
        var tmp = IntArray(a.size)
        repeat(passes) {
            pass(a, tmp, w, h, radius, horizontal = true, check)
            pass(tmp, a, w, h, radius, horizontal = false, check)
        }
        return a
    }

    private fun pass(input: IntArray, output: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean, check: () -> Unit) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val window = radius * 2 + 1
        for (o in 0 until outer) {
            if (o % 64 == 0) check()
            var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            fun idx(i: Int) = if (horizontal) o * w + i else i * w + o
            fun add(i: Int, sign: Int) {
                val p = input[idx(i.coerceIn(0, inner - 1))]
                // Premultiplied so transparent pixels do not drag colour into the edge.
                val pa = p ushr 24
                sa += pa * sign; sr += ((p shr 16) and 0xFF) * pa * sign
                sg += ((p shr 8) and 0xFF) * pa * sign; sb += (p and 0xFF) * pa * sign
            }
            for (i in -radius..radius) add(i, 1)
            for (i in 0 until inner) {
                val a = (sa / window).toInt()
                val p = if (sa == 0L) 0 else
                    (a shl 24) or ((sr / sa).toInt().coerceIn(0, 255) shl 16) or ((sg / sa).toInt().coerceIn(0, 255) shl 8) or (sb / sa).toInt().coerceIn(0, 255)
                output[idx(i)] = p
                add(i - radius, -1); add(i + radius + 1, 1)
            }
        }
    }

    /**
     * Unsharp mask: original plus [amount] times what the blur took away. [radiusPx] is the
     * kernel at this resolution. Alpha is left alone.
     */
    fun unsharp(src: IntArray, w: Int, h: Int, radiusPx: Int, amount: Float, check: () -> Unit = {}, threshold: Int = 0): IntArray {
        if (amount == 0f || radiusPx <= 0) return src.copyOf()
        val blurred = boxBlur(src, w, h, radiusPx, passes = 2, check = check)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]; val q = blurred[i]
            val a = p ushr 24
            fun ch(shift: Int): Int {
                val v = (p shr shift) and 0xFF
                val d = v - ((q shr shift) and 0xFF)
                if (kotlin.math.abs(d) < threshold) return v
                return (v + d * amount).roundToInt().coerceIn(0, 255)
            }
            out[i] = (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }

    /**
     * Sharpness, clarity and dehaze's local part together, so the picture is blurred as few
     * times as it needs. Sharpness is a small radius; clarity and dehaze are large ones.
     */
    fun detail(src: IntArray, w: Int, h: Int, tone: Tone, check: () -> Unit = {}): IntArray {
        val scale = Geometry2D.resolutionScale(Geometry2D.Size(w, h))
        var a = src
        if (tone.clarity != 0f || tone.dehaze > 0f) {
            val radius = max(1, (28f * scale).roundToInt())
            val amount = tone.clarity * 0.8f + tone.dehaze * 0.5f
            a = unsharp(a, w, h, radius, amount, check, threshold = 0)
        }
        if (tone.sharpness > 0f) {
            val radius = max(1, (1.6f * scale).roundToInt())
            a = unsharp(a, w, h, radius, tone.sharpness * 1.6f, check, threshold = 2)
        }
        return a
    }

    /**
     * Darkens (or brightens) towards the corners. Smooth, centred, and sized to the picture.
     * Positive [amount] darkens, which is the usual meaning.
     */
    fun vignette(pixels: IntArray, w: Int, h: Int, amount: Float, check: () -> Unit = {}) {
        if (amount == 0f) return
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val rmax = sqrt(cx * cx + cy * cy)
        val strength = amount.coerceIn(-1f, 1f)
        for (y in 0 until h) {
            if (y % 64 == 0) check()
            val dy = (y - cy) / rmax
            for (x in 0 until w) {
                val dx = (x - cx) / rmax
                val d = sqrt(dx * dx + dy * dy)
                // Nothing inside 40% of the radius, then a smooth fall to the corners.
                val t = ((d - 0.4f) / 0.6f).coerceIn(0f, 1f)
                val f = t * t * (3 - 2 * t)
                val k = 1f - 0.75f * strength * f
                val i = y * w + x
                val p = pixels[i]
                val a = p ushr 24
                fun ch(shift: Int): Int {
                    val v = (p shr shift) and 0xFF
                    return if (k >= 1f) min(255, (v + (255 - v) * (k - 1f)).roundToInt()) else (v * k).roundToInt()
                }
                pixels[i] = (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
    }

    /**
     * Film grain from a hash of the position, on a grid whose cell size follows the resolution,
     * so the preview's grain and the export's grain are the same grain. Luminance only, stronger
     * in the midtones as silver grain is.
     */
    fun grain(pixels: IntArray, w: Int, h: Int, amount: Float, seed: Int = 7, check: () -> Unit = {}) {
        if (amount <= 0f) return
        val scale = Geometry2D.resolutionScale(Geometry2D.Size(w, h))
        val cell = max(1f, 1.4f * scale)
        val strength = 38f * amount
        for (y in 0 until h) {
            if (y % 64 == 0) check()
            val gy = (y / cell).toInt()
            for (x in 0 until w) {
                val gx = (x / cell).toInt()
                val n = hash(gx, gy, seed) // 0..1
                val i = y * w + x
                val p = pixels[i]
                val a = p ushr 24
                val l = Argb.luma(p) / 255f
                val mid = 1f - kotlin.math.abs(l - 0.5f) * 1.4f
                val delta = ((n - 0.5f) * strength * (0.4f + 0.6f * mid)).roundToInt()
                val r = (((p shr 16) and 0xFF) + delta).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + delta).coerceIn(0, 255)
                val b = ((p and 0xFF) + delta).coerceIn(0, 255)
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    /** Averages [divisor]×[divisor] cells into blocks: pixelation that keeps the block edges crisp. */
    fun pixelate(pixels: IntArray, w: Int, h: Int, x0: Int, y0: Int, rw: Int, rh: Int, divisor: Int) {
        val d = max(1, divisor)
        var by = y0
        while (by < y0 + rh) {
            var bx = x0
            val bh = min(d, y0 + rh - by)
            while (bx < x0 + rw) {
                val bw = min(d, x0 + rw - bx)
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val p = pixels[y * w + x]
                    sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; n++
                }
                val avg = ((sa / n).toInt() shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
                for (y in by until by + bh) for (x in bx until bx + bw) pixels[y * w + x] = avg
                bx += bw
            }
            by += bh
        }
    }
}
