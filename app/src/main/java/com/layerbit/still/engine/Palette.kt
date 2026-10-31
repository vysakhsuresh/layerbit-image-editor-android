package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The few colours that carry a picture, for the glow behind the editor: the fullest hue bins
 * of a sparse sample, each bin's mean lifted to a saturation and brightness that read as a
 * light rather than a smear. A grey picture gives nothing, and the brand's own colours stand in.
 */
object Palette {
    private const val BINS = 24

    /** Up to [count] ARGB colours, strongest first, or empty for a picture with no colour in it. */
    fun of(pixels: IntArray, count: Int = 3, step: Int = max(1, pixels.size / 40_000)): List<Int> =
        strongest(pixels, count, step).map { rgb(it[0], max(it[1], 0.6f), max(it[2], 0.8f)) }

    /**
     * Backgrounds that belong with the picture, for frames and cut-out backdrops: for each of its
     * two strongest colours, a pale tint and a deep shade of the same hue. A designer's trick: a
     * frame in the picture's own colour, quiet enough not to compete with it. Empty for a grey
     * picture.
     */
    fun matched(pixels: IntArray, step: Int = max(1, pixels.size / 40_000)): List<Int> =
        strongest(pixels, 2, step).flatMap { listOf(rgb(it[0], min(it[1], 0.16f), 0.96f), rgb(it[0], max(0.45f, min(it[1], 0.7f)), 0.26f)) }

    /** The mean hue, saturation and value of the fullest, well-separated hue bins. */
    private fun strongest(pixels: IntArray, count: Int, step: Int): List<FloatArray> {
        if (pixels.isEmpty()) return emptyList()
        val weight = FloatArray(BINS); val sr = FloatArray(BINS); val sg = FloatArray(BINS); val sb = FloatArray(BINS)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]; i += step
            if (Argb.alpha(c) < 128) continue
            val r = Argb.red(c); val g = Argb.green(c); val b = Argb.blue(c)
            val hsv = hsv(r, g, b)
            val s = hsv[1]; val v = hsv[2]
            if (s < 0.14f || v < 0.12f) continue
            val w = s * (0.3f + 0.7f * v)
            val bin = ((hsv[0] / 360f) * BINS).toInt().coerceIn(0, BINS - 1)
            weight[bin] += w; sr[bin] += r * w; sg[bin] += g * w; sb[bin] += b * w
        }
        val order = (0 until BINS).filter { weight[it] > 0f }.sortedByDescending { weight[it] }
        val picked = ArrayList<FloatArray>(count)
        val pickedBins = ArrayList<Int>(count)
        val total = weight.sum()
        for (bin in order) {
            if (picked.size >= count) break
            // A colour that is less than a fiftieth of the colour in the picture is noise, not a colour.
            if (weight[bin] < total / 50f) break
            if (pickedBins.any { abs(it - bin).let { d -> min(d, BINS - d) } < 2 }) continue
            val w = weight[bin]
            picked.add(hsv((sr[bin] / w).toInt(), (sg[bin] / w).toInt(), (sb[bin] / w).toInt()))
            pickedBins.add(bin)
        }
        return picked
    }

    /** Hue in degrees, saturation and value in 0..1. */
    fun hsv(r: Int, g: Int, b: Int): FloatArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val d = (mx - mn).toFloat()
        val v = mx / 255f
        val s = if (mx == 0) 0f else d / mx
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf(if (h < 0f) h + 360f else h, s, v)
    }

    /** Opaque ARGB from hue in degrees, saturation and value in 0..1. */
    fun rgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val hh = ((h % 360f + 360f) % 360f) / 60f
        val x = c * (1f - abs(hh % 2f - 1f))
        val (r1, g1, b1) = when {
            hh < 1f -> Triple(c, x, 0f); hh < 2f -> Triple(x, c, 0f); hh < 3f -> Triple(0f, c, x)
            hh < 4f -> Triple(0f, x, c); hh < 5f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = v - c
        return Argb.pack(255, ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255), ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255), ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255))
    }
}
