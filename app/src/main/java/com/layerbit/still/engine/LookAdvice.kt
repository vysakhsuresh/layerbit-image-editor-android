package com.layerbit.still.engine

import kotlin.math.max

/**
 * The looks that suit a picture, from what is in it: a page wants Document, a face wants
 * Portrait, a flat or muted scene wants Vivid or Chrome, sky and warm light want Teal and
 * orange, a blue cast wants Warm, a picture with almost no colour wants Mono or Noir.
 * A suggestion only; every look stays one tap away.
 */
object LookAdvice {

    /** What the suggestion is decided from, measured on a sparse sample. */
    data class Reading(
        val saturation: Float, val spread: Float, val skin: Float, val paper: Float,
        val blue: Float, val warm: Float, val cast: Float, val mean: Float
    )

    fun read(pixels: IntArray, step: Int = max(1, pixels.size / 60_000)): Reading? {
        var n = 0; var sat = 0f; var skin = 0; var paper = 0; var ink = 0; var blue = 0; var warm = 0; var cast = 0f; var sum = 0f
        val luma = IntArray(256)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]; i += step
            if (Argb.alpha(c) < 128) continue
            val r = Argb.red(c); val g = Argb.green(c); val b = Argb.blue(c)
            val hsv = Palette.hsv(r, g, b)
            val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
            val l = Argb.luma(c)
            luma[l]++; n++; sum += l
            sat += s * v
            cast += (b - r) / 255f
            if (h in 5f..45f && s in 0.15f..0.6f && v in 0.3f..0.97f && r > g && g > b) skin++
            if (s < 0.12f && v > 0.72f) paper++
            if (v < 0.35f && s < 0.3f) ink++
            if (h in 185f..240f && s > 0.25f && v > 0.3f) blue++
            if ((h < 50f || h > 340f) && s > 0.3f && v > 0.35f) warm++
        }
        if (n == 0) return null
        fun pct(f: Float): Int { val target = n * f; var acc = 0; for (k in 0..255) { acc += luma[k]; if (acc >= target) return k }; return 255 }
        val pageLike = if (paper > n * 0.5f && ink > n * 0.01f) paper.toFloat() / n else 0f
        return Reading(sat / n, (pct(0.95f) - pct(0.05f)) / 255f, skin.toFloat() / n, pageLike, blue.toFloat() / n, warm.toFloat() / n, cast / n, sum / n / 255f)
    }

    /** Up to three looks, best first; empty when nothing stands out. */
    fun suggest(pixels: IntArray): List<Look> {
        val r = read(pixels) ?: return emptyList()
        val scored = LinkedHashMap<Look, Float>()
        fun vote(look: Look, score: Float) { if (score > 0f) scored[look] = max(scored[look] ?: 0f, score) }
        vote(Look.DOCUMENT, if (r.paper > 0f) 1f + r.paper else 0f)
        vote(Look.PORTRAIT, if (r.skin > 0.08f) 0.6f + r.skin * 2f else 0f)
        vote(Look.TEAL_ORANGE, if (r.blue > 0.12f && r.warm > 0.06f) 0.7f + r.blue + r.warm else 0f)
        vote(Look.VIVID, if (r.saturation < 0.18f && r.saturation > 0.04f) 0.65f + (0.18f - r.saturation) * 2f else 0f)
        vote(Look.CHROME, if (r.spread < 0.5f) 0.6f + (0.5f - r.spread) else 0f)
        vote(Look.WARM, if (r.cast > 0.06f) 0.55f + r.cast * 2f else 0f)
        vote(Look.MONO, if (r.saturation <= 0.04f) 0.9f else 0f)
        vote(Look.NOIR, if (r.saturation <= 0.06f && r.mean < 0.45f) 0.85f else 0f)
        vote(Look.FILM, if (r.skin > 0.03f || r.warm > 0.1f) 0.4f else 0f)
        return scored.entries.sortedByDescending { it.value }.take(3).map { it.key }
    }
}
