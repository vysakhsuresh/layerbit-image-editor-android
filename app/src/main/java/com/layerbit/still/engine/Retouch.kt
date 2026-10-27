package com.layerbit.still.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Small, local corrections decided from the pixels under a tap. */
object Retouch {

    /**
     * Flash red-eye within [radius] of ([cx], [cy]): pixels that are red far beyond their green
     * and blue get a dark pupil back, feathered at the circle's edge so nothing shows a rim.
     * Returns how many pixels changed, so a tap on a cheek can say it found no red eye.
     */
    fun redEye(pixels: IntArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float): Int {
        val r = max(2f, radius)
        val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt() + 1)
        val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt() + 1)
        var changed = 0
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (d > r) continue
            val i = y * w + x
            val c = pixels[i]
            val red = Argb.red(c); val green = Argb.green(c); val blue = Argb.blue(c)
            val excess = red - max(green, blue)
            if (red < 70 || excess < 40) continue
            // Fade the correction over the last fifth of the radius.
            val edge = ((r - d) / (r * 0.2f)).coerceIn(0f, 1f)
            val amount = (excess / 110f).coerceIn(0f, 1f) * edge
            val pupil = ((green + blue) / 2f * 0.75f)
            val nr = red + (pupil - red) * amount
            val ng = green + (pupil - green) * amount * 0.4f
            val nb = blue + (pupil - blue) * amount * 0.4f
            pixels[i] = Argb.pack(Argb.alpha(c), nr.roundToInt().coerceIn(0, 255), ng.roundToInt().coerceIn(0, 255), nb.roundToInt().coerceIn(0, 255))
            changed++
        }
        return changed
    }

    /**
     * The temperature and tint that make the tapped colour neutral: a blue cast asks for warmth,
     * a green cast for magenta. The picture's other sliders are kept.
     */
    fun neutralise(r: Int, g: Int, b: Int, base: Tone): Tone {
        val temperature = ((b - r) / 255f * 1.6f).coerceIn(-1f, 1f)
        val tint = ((g - (r + b) / 2f) / 255f * 1.6f).coerceIn(-1f, 1f)
        return base.copy(temperature = temperature, tint = tint)
    }

    /** The mean colour of a small square around a point, for [neutralise]. */
    fun sample(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, half: Int = 2): IntArray {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (yy in max(0, y - half)..min(h - 1, y + half)) for (xx in max(0, x - half)..min(w - 1, x + half)) {
            val c = pixels[yy * w + xx]; r += Argb.red(c); g += Argb.green(c); b += Argb.blue(c); n++
        }
        if (n == 0) return intArrayOf(128, 128, 128)
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
