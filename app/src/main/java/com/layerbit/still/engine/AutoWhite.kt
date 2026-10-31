package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The colour cast, found the way a person would: look at what ought to be grey.
 *
 * Pixels that are nearly neutral and neither black nor blown are averaged; their tint is the
 * cast. When a picture has too few of them (a sunset, a lawn), the whole picture's mean is used
 * at half strength, because a scene that is all one colour is often meant to be. The answer is
 * temperature and tint, the same two sliders a tap on something grey sets, so the user sees it
 * and can move it.
 */
object AutoWhite {

    /** Below this share of near-grey samples, the grey-world fallback takes over. */
    const val MIN_NEUTRAL_SHARE = 0.01f

    /** No slider moves less than this; a cast too small to see is left alone. */
    const val DEAD_ZONE = 0.03f

    /** Auto never moves a slider further than this; the rest is the user's call. */
    const val LIMIT = 0.6f

    /** The estimated colour of grey in [pixels], and whether it came from real near-greys. */
    data class Estimate(val r: Int, val g: Int, val b: Int, val fromNeutrals: Boolean)

    fun estimate(pixels: IntArray, step: Int = max(1, pixels.size / 250_000)): Estimate? {
        var nr = 0L; var ng = 0L; var nb = 0L; var nn = 0L
        var ar = 0L; var ag = 0L; var ab = 0L; var an = 0L
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            i += step
            if ((p ushr 24) == 0) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val hi = max(r, max(g, b)); val lo = min(r, min(g, b))
            if (hi < 24 || hi > 245) continue
            ar += r; ag += g; ab += b; an++
            // Near-grey: the channels within about a third of the brightest one.
            if (hi >= 50 && (hi - lo) * 10 < hi * 3) { nr += r; ng += g; nb += b; nn++ }
        }
        if (an == 0L) return null
        return if (nn >= an * MIN_NEUTRAL_SHARE && nn >= 16) Estimate((nr / nn).toInt(), (ng / nn).toInt(), (nb / nn).toInt(), true)
        else Estimate((ar / an).toInt(), (ag / an).toInt(), (ab / an).toInt(), false)
    }

    /**
     * [base] with temperature and tint set to cancel the cast in [pixels]. Other sliders are
     * kept. Returns [base] unchanged when there is nothing to correct.
     */
    fun tone(pixels: IntArray, base: Tone = Tone()): Tone {
        val e = estimate(pixels) ?: return base
        val target = Retouch.neutralise(e.r, e.g, e.b, Tone())
        val k = if (e.fromNeutrals) 1f else 0.5f
        var temperature = (target.temperature * k).coerceIn(-LIMIT, LIMIT)
        var tint = (target.tint * k).coerceIn(-LIMIT, LIMIT)
        if (abs(temperature) < DEAD_ZONE) temperature = 0f
        if (abs(tint) < DEAD_ZONE) tint = 0f
        return base.copy(temperature = temperature, tint = tint)
    }

    /** Whether Auto colour would change anything: the button's enabled state. */
    fun worthwhile(pixels: IntArray): Boolean {
        val t = tone(pixels)
        return t.temperature != 0f || t.tint != 0f
    }
}

/**
 * What an Auto press changed, kept so the user can turn it up or down afterwards without losing
 * slider moves made since: a strength change adds the difference, never resets.
 */
data class ToneStep(val delta: Tone, val amount: Float = 1f) {

    /** [current] with this step at [newAmount] instead of [amount]. */
    fun rescale(current: Tone, newAmount: Float): Tone {
        val k = newAmount - amount
        val d = delta
        return Tone(
            exposure = (current.exposure + d.exposure * k).coerceIn(-2f, 2f),
            contrast = (current.contrast + d.contrast * k).coerceIn(-1f, 1f),
            highlights = (current.highlights + d.highlights * k).coerceIn(-1f, 1f),
            shadows = (current.shadows + d.shadows * k).coerceIn(-1f, 1f),
            whites = (current.whites + d.whites * k).coerceIn(-1f, 1f),
            blacks = (current.blacks + d.blacks * k).coerceIn(-1f, 1f),
            saturation = (current.saturation + d.saturation * k).coerceIn(-1f, 1f),
            vibrance = (current.vibrance + d.vibrance * k).coerceIn(-1f, 1f),
            temperature = (current.temperature + d.temperature * k).coerceIn(-1f, 1f),
            tint = (current.tint + d.tint * k).coerceIn(-1f, 1f),
            hue = current.hue, fade = current.fade, clarity = current.clarity, dehaze = current.dehaze,
            sharpness = current.sharpness, vignette = current.vignette, grain = current.grain, noise = current.noise
        )
    }

    val isEmpty: Boolean get() = delta == Tone()

    companion object {
        /** The step that takes [before] to [after]. */
        fun between(before: Tone, after: Tone) = ToneStep(
            Tone(
                exposure = after.exposure - before.exposure, contrast = after.contrast - before.contrast,
                highlights = after.highlights - before.highlights, shadows = after.shadows - before.shadows,
                whites = after.whites - before.whites, blacks = after.blacks - before.blacks,
                saturation = after.saturation - before.saturation, vibrance = after.vibrance - before.vibrance,
                temperature = after.temperature - before.temperature, tint = after.tint - before.tint
            )
        )

        /** The strength slider's range: off to half again as much. */
        val RANGE = 0f..1.5f
    }
}
