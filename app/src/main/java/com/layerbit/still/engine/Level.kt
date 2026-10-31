package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A straighten suggestion, found the way a person levels a picture: look for the long lines
 * that ought to be level or plumb - a horizon, a shelf, the sides of a building - and see how
 * far they lean.
 *
 * Every strong edge votes for the tilt of the line it lies on, but only if that line is near
 * horizontal or vertical; a roof at 35 degrees is a roof, not a tilted horizon. The votes are
 * weighted by edge strength and gathered in a histogram, so one long horizon or many parallel
 * verticals outvote foliage, texture and noise, which spread their votes evenly. How much of the
 * vote sits under the peak is the confidence. The peak only says roughly where to look; the lean
 * itself is measured from where the edge pixels lie, which a 3x3 kernel does not bias. A picture
 * with no clear peak, or one leaning more than [LIMIT], gets no suggestion rather than a guess,
 * because a wrong nudge is worse than none.
 *
 * The answer is in [Geometry.straighten]'s own units and sign - degrees, positive clockwise on
 * screen, the way the renderer turns the picture - so applying [Suggestion.degrees] levels the
 * line, and the user sees it on the same dial and can move it.
 *
 * Classical and small on purpose: a downscale, a gradient and two histograms, tens of
 * milliseconds, and every step can be checked by drawing a line at a known angle.
 */
object Level {

    /** No suggestion leans further than this from level or plumb. */
    const val LIMIT = 20f

    /** A tilt smaller than this is not worth a nudge; the picture is already level. */
    const val DEAD_ZONE = 0.3f

    /** Below this confidence there is no dominant direction, and no suggestion. */
    const val MIN_CONFIDENCE = 0.2f

    /** The long side the picture is reduced to before looking for lines. */
    const val WORK_SIZE = 400

    /**
     * How far from level or plumb an edge may lean and still vote. Wider than [LIMIT] because a
     * 3x3 kernel reads a thin edge up to a quarter too steep; the lean is then re-measured.
     */
    private const val VOTE_LIMIT = 28f

    /** Histogram resolution: tenths of a degree, from -[VOTE_LIMIT] to +[VOTE_LIMIT]. */
    private const val BINS_PER_DEGREE = 10
    private const val BINS = (2 * VOTE_LIMIT * BINS_PER_DEGREE).toInt() + 1

    /** The width, in degrees either side of a level peak, that counts as "on the peak". */
    private const val PEAK_HALF_WIDTH = 1f

    /**
     * @property degrees the straighten angle to apply, in [Geometry.straighten]'s convention:
     *   degrees, positive turns the picture clockwise on screen. Within ±[LIMIT].
     * @property confidence 0..1, the share of line evidence that agrees, above what chance gives.
     * @property x0 the dominant line's ends in the picture as given, as fractions of its width
     *   and height (0,0 top left), so the UI can draw a guide along what was found.
     */
    data class Suggestion(
        val degrees: Float,
        val confidence: Float,
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float
    )

    /**
     * The straighten angle that levels [pixels] (ARGB, [w]×[h]), or null when there is no clear
     * dominant line or it is already level. [pixels] must be the frame straightening acts on:
     * after perspective, quarter turns and flips, as the crop panel shows it.
     */
    fun suggest(pixels: IntArray, w: Int, h: Int): Suggestion? {
        if (w < 16 || h < 16 || pixels.size < w * h) return null
        val step = max(1, ceil(max(w, h) / WORK_SIZE.toDouble()).toInt())
        val sw = w / step; val sh = h / step
        if (sw < 8 || sh < 8) return null
        val lum = luminance(pixels, w, step, sw, sh)

        // Scharr gradients: closer to isotropic than Sobel, which matters when the answer is
        // wanted to a few tenths of a degree.
        val n = sw * sh
        val mag = FloatArray(n)
        val tilt = FloatArray(n) // the lean of the line through this pixel, in degrees
        val vertical = BooleanArray(n)
        var maxMag = 0f
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            val a = lum[i - sw - 1]; val b = lum[i - sw]; val c = lum[i - sw + 1]
            val d = lum[i - 1]; val f = lum[i + 1]
            val g = lum[i + sw - 1]; val k = lum[i + sw]; val l = lum[i + sw + 1]
            val gx = 3 * (c - a) + 10 * (f - d) + 3 * (l - g)
            val gy = 3 * (g - a) + 10 * (k - b) + 3 * (l - c)
            val m = sqrt(gx * gx + gy * gy)
            if (m == 0f) continue
            // The line runs across the gradient: direction (-gy, gx), folded to (-90, 90].
            var deg = Math.toDegrees(atan2(gx.toDouble(), -gy.toDouble())).toFloat()
            if (deg > 90f) deg -= 180f else if (deg <= -90f) deg += 180f
            val t = when {
                abs(deg) <= VOTE_LIMIT -> deg
                abs(deg) >= 90f - VOTE_LIMIT -> { vertical[i] = true; if (deg > 0f) deg - 90f else deg + 90f }
                else -> continue
            }
            mag[i] = m; tilt[i] = t
            if (m > maxMag) maxMag = m
        }

        // Strong edges only: above a floor (a step of about 16 levels) and in the top tenth.
        val threshold = max(16f * 16f, percentile(mag, maxMag, 0.90f))
        val hist = DoubleArray(BINS)
        val histV = DoubleArray(BINS)
        var total = 0.0
        for (i in 0 until n) {
            val m = mag[i]
            if (m < threshold) continue
            val pos = (tilt[i] + VOTE_LIMIT) * BINS_PER_DEGREE
            val lo = floor(pos).toInt(); val frac = pos - lo
            if (lo in 0 until BINS) { hist[lo] += m * (1 - frac); if (vertical[i]) histV[lo] += m * (1 - frac) }
            if (lo + 1 in 0 until BINS) { hist[lo + 1] += m * frac; if (vertical[i]) histV[lo + 1] += m * frac }
            total += m
        }
        if (total <= 0.0) return null

        val smooth = blur(hist, 3.0)
        var peak = 0
        for (b in 1 until BINS) if (smooth[b] > smooth[peak]) peak = b
        // The kernel spreads a steep edge's votes, so the peak is wider the further it leans.
        val peakLean = abs(peak.toFloat() / BINS_PER_DEGREE - VOTE_LIMIT)
        val half = ((PEAK_HALF_WIDTH + 0.3f * peakLean) * BINS_PER_DEGREE).toInt()
        var mass = 0.0; var moment = 0.0; var massV = 0.0
        for (b in max(0, peak - half)..min(BINS - 1, peak + half)) {
            mass += hist[b]; moment += hist[b] * b; massV += histV[b]
        }
        if (mass <= 0.0) return null
        val chance = (2 * half + 1).toDouble() / BINS
        val confidence = ((mass / total - chance) / (1 - chance)).coerceIn(0.0, 1.0).toFloat()
        // Enough evidence in absolute terms too: a line about a tenth of the long side.
        val support = mass / threshold
        if (confidence < MIN_CONFIDENCE || support < 0.1 * max(sw, sh)) return null
        val coarse = (moment / mass / BINS_PER_DEGREE - VOTE_LIMIT).toFloat()
        val plumb = massV > mass / 2

        // The histogram finds the lines but overstates their lean: a 3x3 kernel reads a thin
        // edge's angle about a quarter too steep at the pixels where it is strongest. Where the
        // edge pixels sit is not biased, so the lean is refined by position: the angle at which
        // the pixels near the peak line up best across the line, a Hough transform on one axis.
        val reach = 3f + 0.4f * abs(coarse)
        var count = 0
        for (i in 0 until n) if (mag[i] >= threshold / 2 && vertical[i] == plumb && abs(tilt[i] - coarse) <= reach) count++
        val px = FloatArray(count); val py = FloatArray(count); val pw = FloatArray(count)
        count = 0
        for (i in 0 until n) if (mag[i] >= threshold / 2 && vertical[i] == plumb && abs(tilt[i] - coarse) <= reach) {
            px[count] = (i % sw).toFloat(); py[count] = (i / sw).toFloat(); pw[count] = mag[i]; count++
        }
        val diag = sqrt((sw * sw + sh * sh).toFloat()).toInt() + 2
        val rho = DoubleArray(2 * diag + 2)
        fun focus(lean: Float): Double {
            val phi = Math.toRadians((if (plumb) 90f + lean else lean).toDouble())
            val dx = cos(phi).toFloat(); val dy = sin(phi).toFloat()
            rho.fill(0.0)
            for (k in 0 until count) {
                val r = -px[k] * dy + py[k] * dx + diag
                val lo = r.toInt(); val f = r - lo
                rho[lo] += pw[k] * (1 - f); rho[lo + 1] += pw[k] * f
            }
            var s = 0.0
            for (v in rho) s += v * v
            return s
        }
        fun best(from: Float, to: Float, by: Float): Float {
            var a = from; var top = from; var topF = -1.0
            while (a <= to + 1e-4f) { val f = focus(a); if (f > topF) { topF = f; top = a }; a += by }
            return top
        }
        val rough = best(coarse - reach, coarse + reach, 0.25f)
        val lean = best(rough - 0.25f, rough + 0.25f, 0.05f)
        if (abs(lean) < DEAD_ZONE || abs(lean) > LIMIT) return null

        val line = dominantLine(px, py, pw, count, diag, lean, plumb) ?: return null
        // Renderer turns by +degrees clockwise; a line that leans by +lean is levelled by -lean.
        val sx = step.toFloat() / w; val sy = step.toFloat() / h
        return Suggestion(
            -lean, confidence,
            ((line[0] + 0.5f) * sx).coerceIn(0f, 1f), ((line[1] + 0.5f) * sy).coerceIn(0f, 1f),
            ((line[2] + 0.5f) * sx).coerceIn(0f, 1f), ((line[3] + 0.5f) * sy).coerceIn(0f, 1f)
        )
    }

    /**
     * The single strongest line at [lean] among the candidate pixels: they are binned by their
     * distance across the line, the fullest few bins are the line, and its extent along the
     * line gives the ends. Working-size pixels.
     */
    private fun dominantLine(
        px: FloatArray, py: FloatArray, pw: FloatArray, count: Int, diag: Int, lean: Float, plumb: Boolean
    ): FloatArray? {
        val phi = Math.toRadians((if (plumb) 90f + lean else lean).toDouble())
        val dx = cos(phi).toFloat(); val dy = sin(phi).toFloat()
        val rhoHist = DoubleArray(2 * diag + 2)
        for (k in 0 until count) rhoHist[(-px[k] * dy + py[k] * dx).roundToInt() + diag] += pw[k].toDouble()
        var best = 0; var bestMass = -1.0
        for (r in 1 until rhoHist.size - 1) {
            val m = rhoHist[r - 1] + rhoHist[r] + rhoHist[r + 1]
            if (m > bestMass) { bestMass = m; best = r }
        }
        if (bestMass <= 0.0) return null
        val along = ArrayList<Float>()
        var rhoSum = 0.0; var rhoW = 0.0
        for (k in 0 until count) {
            val rho = -px[k] * dy + py[k] * dx
            if (abs(rho + diag - best) > 1.5f) continue
            along += px[k] * dx + py[k] * dy
            rhoSum += rho * pw[k]; rhoW += pw[k]
        }
        if (along.size < 2) return null
        along.sort()
        // The 2nd and 98th percentiles, so a stray pixel far along the line does not stretch it.
        val s0 = along[(along.size * 0.02).toInt()]
        val s1 = along[min(along.size - 1, (along.size * 0.98).toInt())]
        val rho = (rhoSum / rhoW).toFloat()
        // Point = s * d + rho * n, with n = (-dy, dx).
        return floatArrayOf(s0 * dx - rho * dy, s0 * dy + rho * dx, s1 * dx - rho * dy, s1 * dy + rho * dx)
    }

    /** Box-averaged luma (Rec. 601 weights) at 1/[step] scale; the averaging also antialiases. */
    private fun luminance(pixels: IntArray, w: Int, step: Int, sw: Int, sh: Int): FloatArray {
        val out = FloatArray(sw * sh)
        val inv = 1f / (step * step)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0
            for (yy in y * step until (y + 1) * step) {
                var i = yy * w + x * step
                for (xx in 0 until step) {
                    val p = pixels[i++]
                    sum += 299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)
                }
            }
            out[y * sw + x] = sum * inv / 1000f
        }
        return out
    }

    /** The value below which [q] of the non-zero entries of [v] fall, to 1/256 of [max]. */
    private fun percentile(v: FloatArray, max: Float, q: Float): Float {
        if (max <= 0f) return 0f
        val counts = IntArray(256)
        var n = 0
        for (m in v) if (m > 0f) { counts[min(255, (m / max * 255f).toInt())]++; n++ }
        var seen = 0
        for (b in 0 until 256) {
            seen += counts[b]
            if (seen >= n * q) return b / 255f * max
        }
        return max
    }

    private fun blur(v: DoubleArray, sigma: Double): DoubleArray {
        val r = (3 * sigma).toInt()
        val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        return DoubleArray(v.size) { i ->
            var s = 0.0
            for (j in -r..r) { val p = i + j; if (p in v.indices) s += v[p] * k[j + r] }
            s
        }
    }
}
