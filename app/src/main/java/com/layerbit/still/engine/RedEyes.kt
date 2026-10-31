package com.layerbit.still.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds flash red-eye on its own, so the red-eye tool can put circles where it thinks pupils
 * are and the user only confirms or removes them. Nothing is changed here: each [Eye] maps one to
 * one onto a [MarkKind.REDEYE] mark, and the pixels are still corrected by [Retouch.redEye] when
 * the mark is drawn, exactly as for a mark placed by a tap.
 *
 * The method is plain and measurable rather than learned: pixels far redder than their green and
 * blue form blobs; a blob is kept only when it is small, round and sits in a surround that is
 * not red itself. Skin, lips, a red shirt or a red ball fail one of those tests. A catchlight or a
 * dark iris ring next to it, and a partner blob at a plausible eye distance, raise its score.
 */
object RedEyes {

    /**
     * One proposed pupil. [x] and [y] are fractions of the picture's width and height (the same
     * as a mark's point), [radius] is a fraction of the shorter edge (the same as a REDEYE mark's
     * width) and already padded so [Retouch.redEye]'s feathered rim falls outside the red.
     * [score] only orders proposals: higher is more eye-like, about 0..2.
     */
    data class Eye(val x: Float, val y: Float, val radius: Float, val score: Float) {
        fun mark(): Mark = Mark(MarkKind.REDEYE, listOf(Pt(x, y)), width = radius)
    }

    /** Larger pictures are box-averaged down to about this many pixels first; pupils survive it. */
    private const val WORK_PIXELS = 1_500_000

    /** Pupil radius limits as fractions of the shorter edge: below is noise, above is not a pupil. */
    private const val MIN_RADIUS = 0.0015f
    private const val MAX_RADIUS = 0.04f

    /**
     * Up to [max] likely red pupils in an ARGB picture of [w] x [h], best first. Runs in one pass
     * over a picture of at most [WORK_PIXELS] plus a little work per blob.
     */
    fun find(pixels: IntArray, w: Int, h: Int, max: Int = 8): List<Eye> {
        if (w <= 0 || h <= 0 || max <= 0) return emptyList()
        val s = if (w.toLong() * h > WORK_PIXELS) ceil(sqrt(w.toDouble() * h / WORK_PIXELS)).toInt() else 1
        val sw = max(1, w / s); val sh = max(1, h / s)
        val excess = IntArray(sw * sh)   // red - max(green, blue), never below 0
        val luma = IntArray(sw * sh)
        val strong = BooleanArray(sw * sh)
        for (by in 0 until sh) for (bx in 0 until sw) {
            var r = 0; var g = 0; var b = 0
            for (yy in by * s until min(h, by * s + s)) for (xx in bx * s until min(w, bx * s + s)) {
                val c = pixels[yy * w + xx]; r += Argb.red(c); g += Argb.green(c); b += Argb.blue(c)
            }
            val n = s * s; r /= n; g /= n; b /= n
            val i = by * sw + bx
            val e = r - max(g, b)
            excess[i] = max(0, e)
            luma[i] = (r * 77 + g * 150 + b * 29) shr 8
            // Retouch.redEye's own test (red >= 70, excess >= 40), plus: the red must dominate,
            // so warm skin (excess about a quarter of red) never joins a pupil into one big blob.
            strong[i] = r >= 70 && e >= 40 && e * 20 >= r * 9
        }

        val short = min(sw, sh).toFloat()
        val minR = max(1.0f, MIN_RADIUS * short); val maxR = MAX_RADIUS * short
        val maxArea = (PI * maxR * maxR * 1.3).toInt()
        val seen = BooleanArray(sw * sh)
        val stack = IntArray(sw * sh)
        val found = ArrayList<Blob>()
        for (start in strong.indices) {
            if (!strong[start] || seen[start]) continue
            // Flood one 8-connected component, keeping only its sums.
            var top = 0; stack[top++] = start; seen[start] = true
            var area = 0; var sx = 0L; var sy = 0L; var se = 0L
            var x0 = sw; var x1 = -1; var y0 = sh; var y1 = -1
            while (top > 0) {
                val i = stack[--top]
                val x = i % sw; val y = i / sw
                area++; sx += x; sy += y; se += excess[i]
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) continue
                    val j = ny * sw + nx
                    if (strong[j] && !seen[j]) { seen[j] = true; stack[top++] = j }
                }
            }
            if (area > maxArea) continue
            val r = sqrt(area / PI).toFloat()
            if (r < minR || r > maxR) continue
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (max(bw, bh).toFloat() / min(bw, bh) > 1.8f) continue      // lips, streaks
            if (area.toFloat() / (bw * bh) < 0.45f) continue               // rings, scribbles
            val cx = sx.toFloat() / area; val cy = sy.toFloat() / area
            val blobExcess = se.toFloat() / area
            val score = judge(excess, luma, strong, sw, sh, cx, cy, r, blobExcess) ?: continue
            found += Blob(cx, cy, r, score)
        }

        pairUp(found)
        return found.sortedByDescending { it.score }.take(max).map { b ->
            // Back to full-resolution pixels: block bx covers bx*s .. bx*s+s-1.
            val fx = b.cx * s + (s - 1) / 2f; val fy = b.cy * s + (s - 1) / 2f
            // Pad so Retouch.redEye's full-strength core (80% of its radius) covers the blob's edge.
            val padded = (b.r * 1.4f + 1f) * s
            Eye(fx / w, fy / h, padded / min(w, h), b.score)
        }
    }

    private class Blob(val cx: Float, val cy: Float, val r: Float, var score: Float)

    /**
     * A blob's score, or null when its surround says it is part of something red rather than a
     * pupil. The ring from 1.5 to 2.5 radii must be far less red than the blob; a catchlight
     * within the pupil and a dark iris just outside it each add to the score.
     */
    private fun judge(
        excess: IntArray, luma: IntArray, strong: BooleanArray, sw: Int, sh: Int,
        cx: Float, cy: Float, r: Float, blobExcess: Float,
    ): Float? {
        val outer = r * 2.5f
        var ringN = 0; var ringRed = 0; var ringExcess = 0L
        var irisN = 0; var irisDark = 0
        var highlight = false
        val x0 = max(0, (cx - outer).toInt()); val x1 = min(sw - 1, (cx + outer).toInt() + 1)
        val y0 = max(0, (cy - outer).toInt()); val y1 = min(sh - 1, (cy + outer).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val i = y * sw + x
            when {
                d <= r * 1.2f -> if (luma[i] >= 200 && excess[i] < 40) highlight = true
                d <= r * 2f -> { irisN++; if (luma[i] < 90 && !strong[i]) irisDark++ }
            }
            if (d >= r * 1.5f && d <= outer) {
                ringN++; ringExcess += excess[i]; if (strong[i]) ringRed++
            }
        }
        // Too close to the edge to see a surround: still allowed, judged on what is visible.
        if (ringN > 0) {
            if (ringRed > ringN * 0.15f) return null
            if (ringExcess.toFloat() / ringN > blobExcess * 0.4f) return null
        }
        var score = (blobExcess / 160f).coerceIn(0.2f, 1f)
        if (highlight) score += 0.3f
        if (irisN > 0 && irisDark > irisN * 0.3f) score += 0.3f
        return score
    }

    /**
     * Two pupils of similar size, side by side at three to twenty radii apart, are far likelier
     * to be a pair of eyes than two red dots anywhere; each blob takes the bonus at most once.
     */
    private fun pairUp(blobs: List<Blob>) {
        val paired = BooleanArray(blobs.size)
        for (i in blobs.indices) for (j in i + 1 until blobs.size) {
            val a = blobs[i]; val b = blobs[j]
            val ratio = a.r / b.r
            if (ratio < 0.6f || ratio > 1.67f) continue
            val dx = abs(a.cx - b.cx); val dy = abs(a.cy - b.cy)
            if (dy > dx * 0.45f) continue                       // within about 24 degrees of level
            val d = sqrt(dx * dx + dy * dy); val mr = (a.r + b.r) / 2f
            if (d < mr * 3f || d > mr * 20f) continue
            paired[i] = true; paired[j] = true
        }
        for (i in blobs.indices) if (paired[i]) blobs[i].score += 0.5f
    }
}
