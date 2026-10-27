package com.layerbit.studio.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * What the picture's values look like, and the one-tap enhancement derived from them.
 *
 * Auto is not a model. It is levels and a gentle exposure: find where the darkest and brightest
 * half-percent of pixels sit, pull them to black and white, put the median near a pleasant
 * mid-grey, and add a little vibrance. Every step is a slider the user can see afterwards and
 * undo, which is the whole difference between this and a button that does something unknown.
 */
class Histogram private constructor(
    val luma: IntArray, val red: IntArray, val green: IntArray, val blue: IntArray, val total: Long
) {

    /** Fraction of pixels at or below [v] in the luminance histogram. */
    fun cumulative(v: Int): Float {
        var sum = 0L
        for (i in 0..v.coerceIn(0, 255)) sum += luma[i]
        return if (total == 0L) 0f else sum.toFloat() / total
    }

    /** The value below which [fraction] of pixels lie. */
    fun percentile(fraction: Float): Int {
        val target = (total * fraction.coerceIn(0f, 1f)).toLong()
        var sum = 0L
        for (i in 0..255) { sum += luma[i]; if (sum >= target) return i }
        return 255
    }

    /** Pixels at the two ends, as fractions: the clipping warning on the Light panel. */
    val clippedBlack: Float get() = if (total == 0L) 0f else luma[0].toFloat() / total
    val clippedWhite: Float get() = if (total == 0L) 0f else luma[255].toFloat() / total

    /** Channel histograms scaled 0..1 against their shared peak, for drawing. */
    fun normalised(channel: IntArray): FloatArray {
        val peak = max(1, max(red.max(), max(green.max(), max(blue.max(), luma.max()))))
        // Log scale: a picture with one huge spike still shows the rest of its shape.
        val k = ln(1f + peak.toFloat())
        return FloatArray(256) { ln(1f + channel[it]) / k }
    }

    companion object {
        /** Counts every [step]-th pixel; a preview needs no more than about 250,000 samples. */
        fun of(pixels: IntArray, step: Int = max(1, pixels.size / 250_000)): Histogram {
            val l = IntArray(256); val r = IntArray(256); val g = IntArray(256); val b = IntArray(256)
            var n = 0L
            var i = 0
            while (i < pixels.size) {
                val p = pixels[i]
                if ((p ushr 24) != 0) {
                    r[(p shr 16) and 0xFF]++; g[(p shr 8) and 0xFF]++; b[p and 0xFF]++; l[Argb.luma(p)]++; n++
                }
                i += step
            }
            return Histogram(l, r, g, b, n)
        }
    }
}

object AutoEnhance {

    /**
     * The tone that brings [h]'s ends to black and white and its middle to mid-grey. Returns
     * the user's [base] tone with exposure, whites, blacks and vibrance adjusted, so Auto on a
     * picture someone already warmed keeps it warm.
     */
    fun tone(h: Histogram, base: Tone = Tone()): Tone {
        if (h.total == 0L) return base
        val low = h.percentile(0.005f) / 255f
        val high = h.percentile(0.995f) / 255f
        val median = h.percentile(0.5f) / 255f
        // Levels. Whites up when the brightest pixels fall short of white; blacks down when the
        // darkest fall short of black. A full slider moves its end by LEVELS_RANGE, so the
        // slider needed is the gap over that, and a gap too wide for one slider is left partly
        // uncorrected rather than clipped.
        val range = TonePipeline.LEVELS_RANGE
        val whites = ((1f - high) / range).coerceIn(0f, 1f)
        val blacks = -(low / range).coerceIn(0f, 1f)
        // Where the median lands after those levels; exposure nudges it towards mid-grey. Half
        // strength and a dead zone, so a picture that is merely dark on purpose stays dark.
        val black = -range * blacks
        val white = 1f - range * whites
        val medianAfter = ((median - black) / max(1e-3f, white - black)).coerceIn(0.02f, 0.98f)
        var exposure = (ln(0.5f / medianAfter) / ln(2f) * 0.5f).coerceIn(-1f, 1f)
        if (abs(exposure) < 0.06f) exposure = 0f
        // A little vibrance on a picture that is not already saturated.
        val vibrance = 0.12f
        return base.copy(
            exposure = (base.exposure + exposure).coerceIn(-2f, 2f),
            whites = (base.whites + whites).coerceIn(-1f, 1f),
            blacks = (base.blacks + blacks).coerceIn(-1f, 1f),
            vibrance = (base.vibrance + vibrance).coerceIn(-1f, 1f),
            // A touch of contrast when the picture is flat: a narrow histogram.
            contrast = if (high - low < 160 / 255f) (base.contrast + 0.12f).coerceIn(-1f, 1f) else base.contrast
        )
    }

    /** Whether Auto would change anything noticeable: the button's enabled state. */
    fun worthwhile(h: Histogram): Boolean {
        val t = tone(h)
        return abs(t.exposure) > 0.05f || abs(t.whites) > 0.05f || abs(t.blacks) > 0.05f || t.contrast != 0f
    }

    /** Otsu's threshold on a luminance histogram: ink from paper, for the document look. */
    fun otsu(h: Histogram): Int {
        val hist = h.luma
        val total = h.total.toDouble()
        if (total == 0.0) return 128
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * hist[i]
        var sumBelow = 0.0; var weightBelow = 0.0
        var bestVariance = -1.0; var best = 128
        for (t in 0 until 256) {
            weightBelow += hist[t]
            if (weightBelow == 0.0) continue
            val weightAbove = total - weightBelow
            if (weightAbove == 0.0) break
            sumBelow += t.toDouble() * hist[t]
            val meanBelow = sumBelow / weightBelow
            val meanAbove = (sum - sumBelow) / weightAbove
            val variance = weightBelow * weightAbove * (meanBelow - meanAbove) * (meanBelow - meanAbove)
            if (variance > bestVariance) { bestVariance = variance; best = t }
        }
        return min(255, best + 1)
    }
}
