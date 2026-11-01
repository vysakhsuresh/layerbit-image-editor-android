package com.layerbit.still.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Which style an effect should start in for this picture, so the first tap already suits it and
 * the user only steers. Decided from what the picture holds, measured on the preview.
 */
object EffectAdvice {

    /** What a picture holds, as shares of its pixels. */
    data class Content(val sky: Float, val foliage: Float, val skin: Float)

    fun content(px: IntArray, step: Int = 3): Content {
        var n = 0; var sky = 0; var leaf = 0; var skin = 0f
        var i = 0
        while (i < px.size) {
            val c = px[i]; i += step
            if ((c ushr 24) < 128) continue
            n++
            val r = Argb.red(c); val g = Argb.green(c); val b = Argb.blue(c)
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            // Blue sky: blue leads clearly, bright enough not to be a shadow.
            if (b > r + 25 && b >= g && mx > 110) sky++
            // Foliage: green leads, with some colour, not near white.
            else if (g > r + 12 && g > b + 12 && mx - mn > 25 && mx < 235) leaf++
            skin += Effects.skinWeight(c)
        }
        if (n == 0) return Content(0f, 0f, 0f)
        return Content(sky.toFloat() / n, leaf.toFloat() / n, skin / n)
    }

    /**
     * The black-and-white filter a photographer would screw on: red for a blue sky (it goes
     * dramatic), green for leaves (they go light), orange for a face (skin goes clear), neutral otherwise.
     */
    fun blackWhiteFilter(c: Content): Int = when {
        c.skin >= 0.06f && c.skin >= c.sky -> 2      // orange
        c.sky >= 0.08f -> 1                          // red
        c.foliage >= 0.15f -> 4                      // green
        else -> 0
    }

    /** The style index for [kind] on a picture holding [c]; 0 when the effect has no opinion. */
    fun style(kind: EffectKind, c: Content): Int = when (kind) {
        EffectKind.BLACK_WHITE -> blackWhiteFilter(c)
        // HDR: people get the gentler style, landscapes the nature one.
        EffectKind.HDR_SCAPE -> if (c.skin >= 0.06f) 1 else 0
        else -> 0
    }

    /** Where the subject sits: its centre and half-extents as fractions of width and height, and how much it covers. */
    data class Subject(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val coverage: Float)

    /**
     * The subject's centre and spread from its mask (0..255), by moments rather than the bounding
     * box, so a stray speck far away does not stretch it. About two spreads hold the body of a shape.
     */
    fun subject(mask: IntArray, w: Int, h: Int): Subject? {
        var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val m = mask[y * w + x] / 255.0
            if (m <= 0.0) continue
            val fx = (x + 0.5) / w; val fy = (y + 0.5) / h
            sw += m; sx += m * fx; sy += m * fy; sxx += m * fx * fx; syy += m * fy * fy
        }
        if (sw <= 0.0) return null
        val cx = sx / sw; val cy = sy / sw
        val vx = (sxx / sw - cx * cx).coerceAtLeast(0.0); val vy = (syy / sw - cy * cy).coerceAtLeast(0.0)
        return Subject(cx.toFloat(), cy.toFloat(), (2.0 * kotlin.math.sqrt(vx)).toFloat(), (2.0 * kotlin.math.sqrt(vy)).toFloat(), (sw / (w * h)).toFloat())
    }

    /**
     * [e] placed on [s] for a [w] x [h] canvas: a lens blur turns round and holds the subject sharp,
     * a vignette centres its oval on it. A subject that is most of the frame, or a speck, changes nothing.
     */
    fun placed(e: Effect, s: Subject?, w: Int, h: Int): Effect {
        if (s == null || s.coverage < 0.02f || s.coverage > 0.7f || w <= 0 || h <= 0) return e
        val short = min(w, h).toFloat()
        val rx = s.rx * w / short; val ry = s.ry * h / short
        val centre = Pt(s.cx.coerceIn(0.05f, 0.95f), s.cy.coerceIn(0.05f, 0.95f))
        return when (e.kind) {
            EffectKind.LENS_BLUR -> e.copy(
                style = 1, center = centre,
                size = (rx * 1.05f).coerceIn(0.08f, 0.9f),
                aspect = (ry / rx.coerceAtLeast(1e-3f)).coerceIn(0.4f, 3f)
            )
            EffectKind.VIGNETTE -> e.copy(
                center = centre,
                size = (max(rx, ry) * 1.5f).coerceIn(0.3f, 1.2f),
                aspect = (ry / rx.coerceAtLeast(1e-3f)).coerceIn(0.6f, 1.8f)
            )
            else -> e
        }
    }
}
