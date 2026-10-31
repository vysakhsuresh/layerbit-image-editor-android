package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The colour mixer: hue, saturation and lightness for eight families of colour, the way a
 * photographer darkens a sky without touching a face. Each pixel takes its change from the two
 * nearest families, blended by how far between them its hue sits, and in proportion to its own
 * saturation, so greys and whites never move.
 */
data class ColourMix(
    val hue: List<Float> = ZERO,
    val saturation: List<Float> = ZERO,
    val lightness: List<Float> = ZERO
) {
    val isIdentity: Boolean get() = hue.all { it == 0f } && saturation.all { it == 0f } && lightness.all { it == 0f }

    val valid: Boolean
        get() = listOf(hue, saturation, lightness).all { l -> l.size == BANDS && l.all { it.isFinite() && it in -1f..1f } }

    fun with(kind: Kind, band: Int, value: Float): ColourMix {
        fun List<Float>.set(v: Float) = toMutableList().also { it[band] = v.coerceIn(-1f, 1f) }
        return when (kind) {
            Kind.HUE -> copy(hue = hue.set(value))
            Kind.SATURATION -> copy(saturation = saturation.set(value))
            Kind.LIGHTNESS -> copy(lightness = lightness.set(value))
        }
    }

    enum class Kind { HUE, SATURATION, LIGHTNESS }

    /** The mixer compiled to a table per degree of hue, for [apply]. */
    fun compile(): Compiled? {
        if (isIdentity) return null
        val dh = FloatArray(360); val ds = FloatArray(360); val dl = FloatArray(360)
        for (deg in 0 until 360) {
            val (i, t) = locate(deg.toFloat())
            val j = (i + 1) % BANDS
            val a = 1f - smooth(t); val b = smooth(t)
            dh[deg] = (hue[i] * a + hue[j] * b) * MAX_HUE_SHIFT
            ds[deg] = saturation[i] * a + saturation[j] * b
            dl[deg] = (lightness[i] * a + lightness[j] * b) * MAX_LIGHTNESS
        }
        return Compiled(dh, ds, dl)
    }

    class Compiled internal constructor(private val dh: FloatArray, private val ds: FloatArray, private val dl: FloatArray) {
        fun apply(pixels: IntArray, width: Int, fromRow: Int, toRow: Int) {
            for (i in fromRow * width until toRow * width) pixels[i] = apply(pixels[i])
        }

        fun apply(argb: Int): Int {
            val r = Argb.red(argb) / 255f; val g = Argb.green(argb) / 255f; val b = Argb.blue(argb) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val d = mx - mn
            if (d < 1e-3f) return argb
            val l = (mx + mn) / 2f
            val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-4f)
            var h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (h < 0f) h += 360f
            val k = h.toInt().coerceIn(0, 359)
            // How much the pixel is a colour at all: a near-grey barely moves.
            val presence = (s * 2.5f).coerceIn(0f, 1f)
            val nh = ((h + dh[k] * presence) % 360f + 360f) % 360f
            val ns = (s * (1f + ds[k])).coerceIn(0f, 1f)
            val nl = (l + dl[k] * presence * s).coerceIn(0f, 1f)
            return Argb.withAlpha(hsl(nh, ns, nl), Argb.alpha(argb))
        }
    }

    companion object {
        const val BANDS = 8
        /** Red, orange, yellow, green, aqua, blue, purple, magenta, in degrees of hue. */
        val CENTRES = floatArrayOf(0f, 30f, 60f, 120f, 180f, 225f, 275f, 320f)
        val ZERO: List<Float> = List(BANDS) { 0f }
        /** A full hue slider turns its colour this many degrees. */
        const val MAX_HUE_SHIFT = 30f
        /** A full lightness slider moves a fully saturated colour this far in lightness. */
        const val MAX_LIGHTNESS = 0.3f

        /** The band at or below [h] and how far towards the next one it is, 0..1. */
        internal fun locate(h: Float): Pair<Int, Float> {
            for (i in 0 until BANDS) {
                val a = CENTRES[i]; val b = if (i == BANDS - 1) CENTRES[0] + 360f else CENTRES[i + 1]
                val x = if (h < a) h + 360f else h
                if (x >= a && x < b) return i to (x - a) / (b - a)
            }
            return 0 to 0f
        }

        private fun smooth(t: Float) = t * t * (3f - 2f * t)

        internal fun hsl(h: Float, s: Float, l: Float): Int {
            val c = (1f - abs(2f * l - 1f)) * s
            val hh = h / 60f
            val x = c * (1f - abs(hh % 2f - 1f))
            val (r1, g1, b1) = when {
                hh < 1f -> Triple(c, x, 0f); hh < 2f -> Triple(x, c, 0f); hh < 3f -> Triple(0f, c, x)
                hh < 4f -> Triple(0f, x, c); hh < 5f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
            }
            val m = l - c / 2f
            fun to8(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return Argb.pack(255, to8(r1), to8(g1), to8(b1))
        }
    }
}
