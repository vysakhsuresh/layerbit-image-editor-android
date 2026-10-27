package com.layerbit.studio.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The arithmetic of getting a picture under a size, with no codec in sight.
 *
 * "Under 50 KB" is a hard upper limit, however the form that asked counts: 1 KB is 1,000 bytes
 * here, so a result is under the limit in base 1024 too. The search is by halving: the best
 * quality that fits first, and only if the lowest acceptable quality still does not fit, the
 * largest picture that does - found by the same halving on scale. Both are handed an [encode]
 * function and never see a bitmap, which is how a test runs them with a fake codec in a
 * millisecond and the app runs them with the real one.
 */
object FitSize {

    const val BEST_QUALITY = 92
    const val LOWEST_QUALITY = 40
    const val SMALLEST_SIDE = 96

    class Shot(val fits: Boolean, val bytes: ByteArray, val quality: Int)

    /** The highest quality in [LOWEST_QUALITY]..[maxQuality] whose encoding is under [limit]. */
    fun bestQuality(limit: Int, maxQuality: Int = BEST_QUALITY, encode: (Int) -> ByteArray): Shot {
        val top = maxQuality.coerceIn(LOWEST_QUALITY, 100)
        val best = encode(top)
        if (best.size < limit) return Shot(true, best, top)
        val lowest = encode(LOWEST_QUALITY)
        if (lowest.size >= limit) return Shot(false, lowest, LOWEST_QUALITY)
        var lo = LOWEST_QUALITY
        var loBytes = lowest
        var hi = top
        while (hi - lo > 2) {
            val mid = (lo + hi) / 2
            val bytes = encode(mid)
            if (bytes.size < limit) { lo = mid; loBytes = bytes } else hi = mid
        }
        return Shot(true, loBytes, lo)
    }

    data class Scaled(val width: Int, val height: Int, val shot: Shot)

    /**
     * When even the lowest quality at full size is over the limit: the largest size at which
     * the lowest quality fits, then the best quality at that size. [encodeAt] renders and encodes
     * at a size and quality. Returns null when nothing down to [SMALLEST_SIDE] fits.
     */
    fun bestSize(limit: Int, width: Int, height: Int, maxQuality: Int = BEST_QUALITY, encodeAt: (Int, Int, Int) -> ByteArray): Scaled? {
        val floorScale = min(1.0, SMALLEST_SIDE.toDouble() / max(width, height))
        fun at(scale: Double) = max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
        var failing = 1.0
        var failingBytes = encodeAt(width, height, LOWEST_QUALITY).size
        var fitting: Double? = null
        var tries = 0
        while (tries < 12 && failing > floorScale) {
            tries++
            // Guess from the byte ratio - size scales with area - then halve towards it.
            val scale = max(floorScale, min(failing * 0.92, failing * sqrt(limit.toDouble() / failingBytes) * 0.9))
            val (w, h) = at(scale)
            val size = encodeAt(w, h, LOWEST_QUALITY).size
            if (size < limit) { fitting = scale; break }
            failing = scale
            failingBytes = size
            if (scale <= floorScale) break
        }
        val fit = fitting ?: return null
        var lo = fit
        var hi = failing
        repeat(6) {
            val loSide = max(at(lo).first, at(lo).second)
            val hiSide = max(at(hi).first, at(hi).second)
            if (hiSide - loSide <= max(2, (loSide * 0.02).toInt())) return@repeat
            val mid = (lo + hi) / 2
            val (w, h) = at(mid)
            if (encodeAt(w, h, LOWEST_QUALITY).size < limit) lo = mid else hi = mid
        }
        val (w, h) = at(lo)
        val shot = bestQuality(limit, maxQuality) { q -> encodeAt(w, h, q) }
        return Scaled(w, h, shot)
    }

    /** "50 KB", "19.5 KB", "1.2 MB" for a limit typed in kilobytes. */
    fun limitText(kb: Float): String {
        if (kb >= 1000f) {
            val mb = (kb / 100f).roundToInt() / 10.0
            return if (mb == floor(mb)) "${mb.toLong()} MB" else "$mb MB"
        }
        val rounded = (kb * 10).roundToInt() / 10.0
        return if (rounded == floor(rounded)) "${rounded.toLong()} KB" else "$rounded KB"
    }

    /** A size that never rounds up: 49,999 bytes is "49 KB", not "50 KB". */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${floor(bytes / 100_000.0) / 10} MB"
        bytes >= 10_000 -> "${bytes / 1000} KB"
        else -> "${max(0.1, floor(bytes / 100.0) / 10)} KB"
    }

    /** Bytes for a limit typed as [value] with [unit] "KB" or "MB"; 1,000 to the unit. */
    fun limitBytes(value: Float, unit: String): Int = (value * if (unit == "MB") 1_000_000 else 1_000).roundToInt()
}
