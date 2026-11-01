package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Auto upright: walls and buildings that lean in because the camera was tilted up (or out,
 * tilted down) are stood straight with a perspective correction.
 *
 * Every strong edge that runs near vertical votes with its lean, weighted by its strength. In a
 * tilted shot the lean changes steadily across the picture (left edges lean one way, right edges
 * the other), so a straight-line fit of lean against position gives the keystone directly; the
 * shared part of the lean is plain rotation and is left to Straighten. Texture and foliage vote
 * in every direction and cancel; a fit that does not hold up is reported as nothing found.
 */
object Upright {

    /** How much of the measured keystone is corrected: a little short of full looks natural. */
    const val STRENGTH = 0.85f

    /**
     * The perspective quad that stands the verticals of [px] ([w] x [h]) upright, or null when
     * the picture has too few verticals or they already stand straight.
     */
    fun suggest(px: IntArray, w: Int, h: Int): Quad? {
        if (w < 32 || h < 32) return null
        val step = max(1, sqrt(w.toDouble() * h / 250_000.0).toInt())
        val sw = w / step; val sh = h / step
        val lum = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = px[(y * step) * w + x * step]
            lum[y * sw + x] = 0.299f * Argb.red(c) + 0.587f * Argb.green(c) + 0.114f * Argb.blue(c)
        }
        // Edge orientation from the structure tensor: the gradient's square products averaged over a
        // few pixels, so a line's slight, steady lean is read even where it steps pixel by pixel.
        val n = sw * sh
        val jxx = FloatArray(n); val jyy = FloatArray(n); val jxy = FloatArray(n); val mag = FloatArray(n)
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            val gx = (lum[i - sw + 1] + 2 * lum[i + 1] + lum[i + sw + 1]) - (lum[i - sw - 1] + 2 * lum[i - 1] + lum[i + sw - 1])
            val gy = (lum[i + sw - 1] + 2 * lum[i + sw] + lum[i + sw + 1]) - (lum[i - sw - 1] + 2 * lum[i - sw] + lum[i - sw + 1])
            jxx[i] = gx * gx; jyy[i] = gy * gy; jxy[i] = gx * gy; mag[i] = abs(gx) + abs(gy)
        }
        val r = max(4, minOf(sw, sh) / 30)
        val sxx = Planes.blur(jxx, sw, sh, r, 1); val syy = Planes.blur(jyy, sw, sh, r, 1); val sxy = Planes.blur(jxy, sw, sh, r, 1)
        // Near-vertical edges: the gradient runs across the line, so it is mostly horizontal.
        val xs = ArrayList<Float>(); val leans = ArrayList<Float>(); val ws = ArrayList<Float>()
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            if (mag[i] < 120f) continue
            val a2 = sxx[i] - syy[i]; val b2 = 2f * sxy[i]
            val trace = sxx[i] + syy[i]
            if (trace <= 0f) continue
            // Coherence: how much the neighbourhood agrees on one direction. Texture does not.
            if (sqrt(a2 * a2 + b2 * b2) / trace < 0.7f) continue
            val theta = 0.5 * kotlin.math.atan2(b2.toDouble(), a2.toDouble())
            val c = kotlin.math.cos(theta); val sn = kotlin.math.sin(theta)
            if (abs(c) < abs(sn) * 2.75) continue
            // The line runs across the gradient: its sideways step per step down.
            val lean = (-sn / c).toFloat()
            if (abs(lean) > 0.36f) continue
            xs += (x + 0.5f) / sw - 0.5f; leans += lean; ws += mag[i]
        }
        if (xs.size < 150) return null
        // Both sides must have verticals, or the slope is a guess.
        var left = 0f; var right = 0f
        for (k in xs.indices) { if (xs[k] < -0.15f) left += ws[k] else if (xs[k] > 0.15f) right += ws[k] }
        val total = ws.sum()
        if (left < total * 0.12f || right < total * 0.12f) return null
        // Weighted line fit, three times: each after dropping the votes far off the last line.
        var a = 0f; var b = 0f
        var keep = BooleanArray(xs.size) { true }
        for (pass in 0 until 3) {
            var sw0 = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (k in xs.indices) if (keep[k]) {
                val wk = ws[k].toDouble(); val xk = xs[k].toDouble(); val yk = leans[k].toDouble()
                sw0 += wk; sx += wk * xk; sy += wk * yk; sxx += wk * xk * xk; sxy += wk * xk * yk
            }
            val det = sw0 * sxx - sx * sx
            if (sw0 <= 0.0 || abs(det) < 1e-9) return null
            b = ((sw0 * sxy - sx * sy) / det).toFloat()
            a = ((sy - b * sx) / sw0).toFloat()
            if (pass < 2) keep = BooleanArray(xs.size) { k -> abs(leans[k] - (a + b * xs[k])) < 0.06f }
        }
        // How well the line holds: much of the weight must sit on it.
        var on = 0f
        for (k in xs.indices) if (abs(leans[k] - (a + b * xs[k])) < 0.03f) on += ws[k]
        if (on < total * 0.35f) return null
        // The lean at an edge carries it this far sideways over the picture's height, as a fraction of its width.
        val inset = abs(b) / 2f * h / w * STRENGTH
        if (inset < 0.006f || inset > 0.22f) return null
        return if (b > 0f) {
            // Lines lean in towards the top: the top corners come in.
            Quad(Pt(inset, 0f), Pt(1f - inset, 0f), Pt(1f, 1f), Pt(0f, 1f))
        } else {
            Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f - inset, 1f), Pt(inset, 1f))
        }
    }

    /**
     * The same for a picture shown a quarter turn round: what looks vertical on screen is
     * horizontal in the stored picture, so it is read transposed and the quad turned back.
     */
    fun suggestTransposed(px: IntArray, w: Int, h: Int): Quad? {
        val t = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) t[x * h + y] = px[y * w + x]
        val q = suggest(t, h, w) ?: return null
        fun tr(p: Pt) = Pt(p.y, p.x)
        return Quad(tl = tr(q.tl), tr = tr(q.bl), br = tr(q.br), bl = tr(q.tr))
    }
}
