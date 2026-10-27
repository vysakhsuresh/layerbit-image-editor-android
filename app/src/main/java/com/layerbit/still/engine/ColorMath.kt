package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The colour pipeline, decided once per recipe and then applied per pixel.
 *
 * Two stages, both pure arithmetic on packed ARGB ints:
 *
 *  1. A 3×4 matrix for everything linear in RGB - exposure, temperature, tint, saturation,
 *     hue - plus vibrance, which needs each pixel's own saturation. Twelve multiplies a pixel.
 *  2. Three 256-entry tables for everything that is a curve on a channel - contrast, whites,
 *     blacks, highlights, shadows, fade, the user's curves and the look's curves. Three lookups
 *     a pixel.
 *
 * Nothing here reads a neighbour or a position; those effects live in [Spatial]. Because every
 * value is decided from the pixel alone, the 900-pixel preview and the 24-megapixel export go
 * through the same two steps and agree to the rounding - which is the property the editor's
 * "what you see is what you save" rests on, and the one [ToneTest] checks.
 */
class TonePipeline private constructor(
    /** Row-major 3×4: r' = m[0]r + m[1]g + m[2]b + m[3], and so on. */
    val matrix: FloatArray,
    val vibrance: Float,
    val lutR: IntArray,
    val lutG: IntArray,
    val lutB: IntArray,
    val isIdentity: Boolean
) {

    /** Applies the pipeline to [pixels] in place, rows [fromRow] until [toRow] of a [width]-wide image. */
    fun apply(pixels: IntArray, width: Int, fromRow: Int, toRow: Int) {
        if (isIdentity) return
        val m = matrix
        val v = vibrance
        val start = fromRow * width
        val end = min(pixels.size, toRow * width)
        for (i in start until end) {
            val p = pixels[i]
            val a = p ushr 24
            if (a == 0) continue
            var r = ((p shr 16) and 0xFF).toFloat()
            var g = ((p shr 8) and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()
            if (v != 0f) {
                // Saturation that backs off as the pixel's own saturation rises.
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val sat = if (mx <= 0f) 0f else (mx - mn) / mx
                val k = 1f + v * (1f - sat)
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = l + (r - l) * k; g = l + (g - l) * k; b = l + (b - l) * k
            }
            val nr = m[0] * r + m[1] * g + m[2] * b + m[3]
            val ng = m[4] * r + m[5] * g + m[6] * b + m[7]
            val nb = m[8] * r + m[9] * g + m[10] * b + m[11]
            val ir = lutR[clamp255(nr)]
            val ig = lutG[clamp255(ng)]
            val ib = lutB[clamp255(nb)]
            pixels[i] = (a shl 24) or (ir shl 16) or (ig shl 8) or ib
        }
    }

    /** One colour through the pipeline, for swatches and tests. */
    fun apply(argb: Int): Int {
        val one = intArrayOf(argb)
        apply(one, 1, 0, 1)
        return one[0]
    }

    companion object {
        /** How far a full whites or blacks slider moves its end point, as a fraction of the scale. */
        const val LEVELS_RANGE = 0.4f

        val IDENTITY: TonePipeline = build(Tone(), Curves(), Look.NONE, 0f)

        private fun clamp255(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

        fun build(tone: Tone, curves: Curves, look: Look, lookStrength: Float): TonePipeline {
            val effective = Looks.combine(tone, curves, look, lookStrength)
            val t = effective.tone
            val c = effective.curves
            val matrix = matrixFor(t)
            val lutR = IntArray(256); val lutG = IntArray(256); val lutB = IntArray(256)
            val master = CurveMath.table(c.master)
            val red = CurveMath.table(c.red); val green = CurveMath.table(c.green); val blue = CurveMath.table(c.blue)
            for (i in 0..255) {
                val x = i / 255f
                val y = channelCurve(x, t)
                val mastered = CurveMath.at(master, y)
                lutR[i] = (CurveMath.at(red, mastered) * 255f).roundToInt().coerceIn(0, 255)
                lutG[i] = (CurveMath.at(green, mastered) * 255f).roundToInt().coerceIn(0, 255)
                lutB[i] = (CurveMath.at(blue, mastered) * 255f).roundToInt().coerceIn(0, 255)
            }
            val identity = t.pointIdentity && c.isIdentity && isIdentityMatrix(matrix) && t.vibrance == 0f &&
                (0..255).all { lutR[it] == it && lutG[it] == it && lutB[it] == it }
            return TonePipeline(matrix, t.vibrance, lutR, lutG, lutB, identity)
        }

        private fun isIdentityMatrix(m: FloatArray): Boolean {
            val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
            return m.indices.all { abs(m[it] - id[it]) < 1e-6f }
        }

        /**
         * The value a channel takes after the per-channel sliders, before the curves. Each slider
         * is a simple, separately testable shape; they compose in a fixed order.
         */
        internal fun channelCurve(x0: Float, t: Tone): Float {
            var x = x0
            // Blacks and whites: where the ends land. Positive whites brings the white point down
            // so the top brightens; positive blacks pushes the black point below zero so the
            // bottom lifts towards grey, as the usual sliders do. A full slider moves its end by
            // [LEVELS_RANGE] of the scale, which is what AutoEnhance divides by.
            val black = -LEVELS_RANGE * t.blacks
            val white = 1f - LEVELS_RANGE * t.whites
            x = ((x - black) / (white - black)).coerceIn(0f, 1f)
            // Shadows and highlights: a lift or drop that fades out towards the other end.
            if (t.shadows != 0f) x += t.shadows * 0.3f * (1f - x).pow(2) * x.pow(0.5f) * 2f
            if (t.highlights != 0f) x += t.highlights * 0.3f * x.pow(2) * (1f - x).pow(0.5f) * 2f
            x = x.coerceIn(0f, 1f)
            // Contrast: an S about mid-grey, by a smooth sigmoid blend rather than a straight slope,
            // so strong contrast rolls off rather than clipping.
            if (t.contrast != 0f) {
                val k = 1f + 1.5f * abs(t.contrast)
                val s = sigmoidContrast(x, k)
                x = if (t.contrast > 0f) s else lerp(x, inverseSigmoid(x, k), -t.contrast)
                x = x.coerceIn(0f, 1f)
            }
            // Fade: lift the black point towards grey, the matte-print look.
            if (t.fade > 0f) x = x * (1f - 0.35f * t.fade) + 0.35f * t.fade * 0.6f
            // Dehaze's tonal part: a contrast lift in the lower half with the floor pulled down.
            if (t.dehaze > 0f) {
                val lowered = ((x - 0.08f * t.dehaze) / (1f - 0.08f * t.dehaze)).coerceIn(0f, 1f)
                x = lerp(x, lowered.pow(1f + 0.35f * t.dehaze), t.dehaze)
            }
            return x.coerceIn(0f, 1f)
        }

        private fun sigmoidContrast(x: Float, k: Float): Float {
            // Normalised so 0 → 0, 1 → 1, 0.5 → 0.5 for any k.
            val a = 1f / (1f + exp(-k * 4f * (x - 0.5f)))
            val lo = 1f / (1f + exp(k * 2f))
            val hi = 1f / (1f + exp(-k * 2f))
            return (a - lo) / (hi - lo)
        }

        private fun inverseSigmoid(y: Float, k: Float): Float {
            // Numeric inverse by bisection: the opposite shape for negative contrast.
            var lo = 0f; var hi = 1f
            repeat(24) { val mid = (lo + hi) / 2; if (sigmoidContrast(mid, k) < y) lo = mid else hi = mid }
            return (lo + hi) / 2
        }

        private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        /** Exposure, temperature, tint, saturation and hue as one 3×4 matrix on 0..255 values. */
        internal fun matrixFor(t: Tone): FloatArray {
            var m = identity()
            // Exposure in stops.
            if (t.exposure != 0f) m = multiply(scale(2f.pow(t.exposure)), m)
            // Temperature and tint as channel gains, normalised so mid-grey keeps its luminance.
            if (t.temperature != 0f || t.tint != 0f) {
                var r = 1f + 0.18f * t.temperature - 0.04f * t.tint
                var g = 1f + 0.10f * t.tint
                var b = 1f - 0.18f * t.temperature - 0.04f * t.tint
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r /= l; g /= l; b /= l
                m = multiply(diag(r, g, b), m)
            }
            if (t.saturation != 0f) m = multiply(saturation(1f + t.saturation), m)
            if (t.hue != 0f) m = multiply(hueRotate(t.hue * 180f), m)
            return m
        }

        private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
        private fun scale(k: Float) = diag(k, k, k)
        private fun diag(r: Float, g: Float, b: Float) = floatArrayOf(r, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, b, 0f)

        /** Rec.709 luminance-preserving saturation; 0 is greyscale, 1 is unchanged. */
        internal fun saturation(s: Float): FloatArray {
            val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
            return floatArrayOf(
                lr + (1 - lr) * s, lg - lg * s, lb - lb * s, 0f,
                lr - lr * s, lg + (1 - lg) * s, lb - lb * s, 0f,
                lr - lr * s, lg - lg * s, lb + (1 - lb) * s, 0f
            )
        }

        /** Rotation about the grey axis, the standard luminance-preserving form. */
        internal fun hueRotate(degrees: Float): FloatArray {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
            return floatArrayOf(
                lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0f,
                lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f,
                lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0f
            )
        }

        /** a × b for 3×4 affine matrices (apply b first, then a). */
        internal fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(12)
            for (row in 0 until 3) {
                for (col in 0 until 4) {
                    var sum = 0f
                    for (k in 0 until 3) sum += a[row * 4 + k] * b[k * 4 + col]
                    if (col == 3) sum += a[row * 4 + 3]
                    out[row * 4 + col] = sum
                }
            }
            return out
        }
    }
}

/** Monotone cubic interpolation through curve control points, tabulated to 1024 steps. */
object CurveMath {
    private const val STEPS = 1024

    fun table(points: List<Pt>): FloatArray {
        if (points == Curves.IDENTITY_POINTS) return FloatArray(STEPS + 1) { it / STEPS.toFloat() }
        val n = points.size
        val xs = FloatArray(n) { points[it].x }
        val ys = FloatArray(n) { points[it].y }
        // Fritsch–Carlson slopes: monotone, so a curve never overshoots between its own points.
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val tau = 3f / kotlin.math.sqrt(s); m[i] = tau * a * d[i]; m[i + 1] = tau * b * d[i] }
        }
        val out = FloatArray(STEPS + 1)
        var seg = 0
        for (i in 0..STEPS) {
            val x = i / STEPS.toFloat()
            while (seg < n - 2 && x > xs[seg + 1]) seg++
            val h = xs[seg + 1] - xs[seg]
            val t = ((x - xs[seg]) / h).coerceIn(0f, 1f)
            val t2 = t * t; val t3 = t2 * t
            val y = (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * m[seg] +
                (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * m[seg + 1]
            out[i] = if (x < xs[0]) ys[0] else if (x > xs[n - 1]) ys[n - 1] else y.coerceIn(0f, 1f)
        }
        return out
    }

    fun at(table: FloatArray, x: Float): Float {
        val f = x.coerceIn(0f, 1f) * STEPS
        val i = f.toInt().coerceIn(0, STEPS - 1)
        val frac = f - i
        return table[i] + (table[i + 1] - table[i]) * frac
    }
}

/**
 * Each look as the sliders it moves, so a look is a starting point and never a secret. The
 * user's own tone is applied on top: a look's +0.2 contrast and a user's +0.1 make +0.3.
 */
object Looks {

    data class Effective(val tone: Tone, val curves: Curves)

    fun recipeFor(look: Look): Effective = when (look) {
        Look.NONE -> Effective(Tone(), Curves())
        Look.MONO -> Effective(Tone(saturation = -1f, contrast = 0.1f), Curves())
        Look.NOIR -> Effective(Tone(saturation = -1f, contrast = 0.55f, blacks = -0.3f, vignette = 0.35f), Curves())
        Look.SEPIA -> Effective(
            Tone(saturation = -1f, contrast = 0.08f),
            Curves(red = listOf(Pt(0f, 0.02f), Pt(0.5f, 0.56f), Pt(1f, 1f)), green = listOf(Pt(0f, 0f), Pt(0.5f, 0.48f), Pt(1f, 0.95f)), blue = listOf(Pt(0f, 0f), Pt(0.5f, 0.38f), Pt(1f, 0.82f)))
        )
        Look.WARM -> Effective(Tone(temperature = 0.4f, vibrance = 0.15f), Curves())
        Look.COOL -> Effective(Tone(temperature = -0.4f, tint = -0.05f), Curves())
        Look.FADE -> Effective(Tone(fade = 0.6f, saturation = -0.2f, contrast = -0.1f), Curves())
        Look.MATTE -> Effective(Tone(fade = 0.45f, contrast = 0.15f, highlights = -0.2f), Curves())
        Look.VIVID -> Effective(Tone(vibrance = 0.5f, saturation = 0.15f, contrast = 0.2f, clarity = 0.2f), Curves())
        Look.FILM -> Effective(
            Tone(fade = 0.25f, saturation = -0.1f, grain = 0.3f, contrast = 0.15f),
            Curves(master = listOf(Pt(0f, 0.04f), Pt(0.25f, 0.22f), Pt(0.75f, 0.8f), Pt(1f, 0.96f)))
        )
        Look.CHROME -> Effective(
            Tone(contrast = 0.35f, saturation = 0.25f, clarity = 0.3f),
            Curves(blue = listOf(Pt(0f, 0.06f), Pt(0.5f, 0.5f), Pt(1f, 0.96f)))
        )
        Look.TEAL_ORANGE -> Effective(
            Tone(vibrance = 0.2f, contrast = 0.15f),
            Curves(
                red = listOf(Pt(0f, 0f), Pt(0.3f, 0.26f), Pt(0.7f, 0.76f), Pt(1f, 1f)),
                blue = listOf(Pt(0f, 0.08f), Pt(0.3f, 0.36f), Pt(0.7f, 0.64f), Pt(1f, 0.92f))
            )
        )
        Look.PORTRAIT -> Effective(Tone(temperature = 0.12f, highlights = -0.15f, shadows = 0.15f, vibrance = 0.1f, saturation = -0.05f, clarity = -0.1f), Curves())
        Look.DOCUMENT -> Effective(Tone(saturation = -0.6f, contrast = 0.4f, whites = 0.3f, blacks = -0.25f, sharpness = 0.3f, dehaze = 0.2f), Curves())
    }

    /** The look at [strength] under the user's tone and curves. */
    fun combine(user: Tone, userCurves: Curves, look: Look, strength: Float): Effective {
        if (look == Look.NONE || strength <= 0f) return Effective(user, userCurves)
        val l = recipeFor(look)
        val s = strength.coerceIn(0f, 1f)
        val t = Tone(
            exposure = (user.exposure + l.tone.exposure * s).coerceIn(-2f, 2f),
            contrast = (user.contrast + l.tone.contrast * s).coerceIn(-1f, 1f),
            highlights = (user.highlights + l.tone.highlights * s).coerceIn(-1f, 1f),
            shadows = (user.shadows + l.tone.shadows * s).coerceIn(-1f, 1f),
            whites = (user.whites + l.tone.whites * s).coerceIn(-1f, 1f),
            blacks = (user.blacks + l.tone.blacks * s).coerceIn(-1f, 1f),
            saturation = (user.saturation + l.tone.saturation * s).coerceIn(-1f, 1f),
            vibrance = (user.vibrance + l.tone.vibrance * s).coerceIn(-1f, 1f),
            temperature = (user.temperature + l.tone.temperature * s).coerceIn(-1f, 1f),
            tint = (user.tint + l.tone.tint * s).coerceIn(-1f, 1f),
            hue = (user.hue + l.tone.hue * s).coerceIn(-1f, 1f),
            fade = (user.fade + l.tone.fade * s).coerceIn(0f, 1f),
            clarity = (user.clarity + l.tone.clarity * s).coerceIn(-1f, 1f),
            dehaze = (user.dehaze + l.tone.dehaze * s).coerceIn(0f, 1f),
            sharpness = (user.sharpness + l.tone.sharpness * s).coerceIn(0f, 1f),
            vignette = (user.vignette + l.tone.vignette * s).coerceIn(-1f, 1f),
            grain = (user.grain + l.tone.grain * s).coerceIn(0f, 1f)
        )
        val c = Curves(
            master = blend(userCurves.master, l.curves.master, s),
            red = blend(userCurves.red, l.curves.red, s),
            green = blend(userCurves.green, l.curves.green, s),
            blue = blend(userCurves.blue, l.curves.blue, s)
        )
        return Effective(t, c)
    }

    /** Compose two curves: the look's, faded towards identity by [s], applied after the user's. */
    private fun blend(user: List<Pt>, look: List<Pt>, s: Float): List<Pt> {
        if (look == Curves.IDENTITY_POINTS) return user
        val lookTable = CurveMath.table(look)
        val userTable = CurveMath.table(user)
        // Sample the composition at a dozen points and keep it as control points again, which is
        // what the rest of the pipeline knows how to tabulate.
        return (0..12).map { i ->
            val x = i / 12f
            val u = CurveMath.at(userTable, x)
            val faded = u + (CurveMath.at(lookTable, u) - u) * s
            Pt(x, faded.coerceIn(0f, 1f))
        }
    }
}

/** Packed-colour helpers used across the engine. */
object Argb {
    fun alpha(c: Int) = c ushr 24
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun pack(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun luma(c: Int): Int = (red(c) * 77 + green(c) * 150 + blue(c) * 29) shr 8
    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}
