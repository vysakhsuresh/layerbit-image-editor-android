package com.layerbit.still.engine

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A stroke painted on the picture to say "keep this" ([keep]) or "remove this", in canvas
 * fractions. [width] is the brush diameter as a fraction of the shorter edge.
 */
data class Scribble(val points: List<Pt>, val width: Float, val keep: Boolean) {
    val valid: Boolean
        get() = points.isNotEmpty() && points.size <= Mark.MAX_POINTS && points.all { it.finite && it.x in 0f..1f && it.y in 0f..1f } && width in 0.002f..0.3f
}

enum class CutoutMode {
    /** The subject against its surroundings: what a photo of a person, a product or a pet needs. */
    SUBJECT,
    /** Ink and stamps against paper: a signature, a seal, a doodle, lifted off the page. */
    INK
}

enum class Backdrop { TRANSPARENT, COLOUR, BLUR }

/**
 * Which part of the picture to keep, and what to put behind it.
 *
 * The decision is made on the picture at a fixed analysis size and scaled to whatever size is
 * being drawn, so the preview's edge is the export's edge. Nothing here is a model: the subject
 * is found by colour and by the edges a stroke must cross, and the user's own strokes always
 * win over the guess. A wrong guess is corrected with a stroke, never with a reload.
 */
data class Cutout(
    /** Whether the cut-out is applied. Off keeps the strokes for later without using them. */
    val enabled: Boolean = false,
    val mode: CutoutMode = CutoutMode.SUBJECT,
    val scribbles: List<Scribble> = emptyList(),
    /** Edge softness as a fraction of the shorter edge, 0..0.05. */
    val feather: Float = 0.004f,
    /** INK: where paper ends and ink begins, 0..1 of luminance. 0 means decide from the picture. */
    val threshold: Float = 0f,
    /** Keep the background instead of the subject. */
    val invert: Boolean = false,
    val backdrop: Backdrop = Backdrop.TRANSPARENT,
    val color: Int = 0xFFFFFFFF.toInt(),
    /** Blur radius for a blurred backdrop, as a fraction of the shorter edge, 0..0.1. */
    val blur: Float = 0.03f
) {
    val isIdentity: Boolean get() = this == Cutout()
    val valid: Boolean
        get() = scribbles.size <= MAX_SCRIBBLES && scribbles.all { it.valid } && feather in 0f..0.05f && threshold in 0f..1f && blur in 0f..0.1f

    /** What decides the mask itself, as a key: the strokes, the mode and the threshold. */
    val maskKey: String get() = "$mode|$threshold|" + scribbles.joinToString(";") { s -> (if (s.keep) "k" else "r") + s.width + ":" + s.points.joinToString(",") { "${it.x},${it.y}" } }

    companion object { const val MAX_SCRIBBLES = 200 }
}

/**
 * Finds the subject, or the ink, and hands back a mask: 255 where the picture is kept, 0 where
 * it goes, soft in between.
 *
 * Subject mode: the border of the picture is assumed to be background unless a stroke says
 * otherwise, and a few colours are learnt from it. Pixels in the middle that look nothing like
 * those colours are the first guess at the subject. From the two sets of seeds a geodesic
 * distance is grown across the picture, where crossing an edge costs much more than crossing
 * flat colour, and each pixel goes to whichever seed set is nearer. A "keep" stroke is a
 * subject seed; a "remove" stroke is a background seed; both overrule the guess. Small islands
 * are then dropped and small holes filled, unless a stroke asked for them.
 *
 * Ink mode: dark or strongly coloured marks on light paper are kept by luminance and
 * colourfulness against an automatic or chosen threshold, which lifts a signature or a stamp
 * off a photographed page with a soft, anti-aliased edge.
 */
object Segment {

    /** The pixel count the decision is made at: enough for a clean edge, cheap enough to redo per stroke. */
    const val ANALYSIS_PIXELS = 160_000L

    /** The analysis size for a picture of [width] x [height]: decided from the aspect alone, so every size agrees. */
    fun analysisSize(width: Int, height: Int): Geometry2D.Size {
        val aspect = width.toDouble() / height
        val w = sqrt(ANALYSIS_PIXELS * aspect).roundToInt().coerceIn(8, 2048)
        val h = (w / aspect).roundToInt().coerceIn(8, 2048)
        return Geometry2D.Size(w, h)
    }

    /** The mask for [cutout] on [pixels], 0..255 per pixel, before feathering and inversion. */
    fun mask(pixels: IntArray, w: Int, h: Int, cutout: Cutout, check: () -> Unit = {}): IntArray = when (cutout.mode) {
        CutoutMode.SUBJECT -> subject(pixels, w, h, cutout.scribbles, check)
        CutoutMode.INK -> ink(pixels, w, h, cutout.threshold)
    }

    // ------------------------------------------------------------------ subject

    fun subject(pixels: IntArray, w: Int, h: Int, scribbles: List<Scribble>, check: () -> Unit = {}): IntArray {
        require(pixels.size == w * h && w > 0 && h > 0)
        val n = w * h
        val shorter = min(w, h)
        // What the user said: 1 = keep, 2 = remove. These are the only hard decisions.
        val stroked = ByteArray(n)
        for (s in scribbles) stamp(s, w, h, shorter) { i -> stroked[i] = if (s.keep) 1 else 2 }
        val hasKeep = scribbles.any { it.keep }; val hasRemove = scribbles.any { !it.keep }
        // How far each pixel is, through flat colour, from a keep stroke and from a remove stroke:
        // a guess near a stroke that says otherwise gives way to the stroke.
        val fromKeep = if (hasKeep) geodesic(pixels, w, h, stroked, 1, null, towardsSubject = true, check) else null
        val fromRemove = if (hasRemove) geodesic(pixels, w, h, stroked, 2, null, towardsSubject = false, check) else null
        val seed = stroked.copyOf()
        // The border is background unless a keep stroke reaches it through flat colour.
        val band = max(2, (0.03f * shorter).roundToInt())
        for (y in 0 until h) for (x in 0 until w) {
            if (x < band || y < band || x >= w - band || y >= h - band) {
                val i = y * w + x
                if (seed[i].toInt() == 0 && (fromKeep == null || fromKeep[i] > STROKE_REACH)) seed[i] = 2
            }
        }
        check()
        // A few background colours, learnt from the border, and how far every pixel is from them.
        val centres = kMeans(pixels, seed, 2, 6)
        val bgDist = IntArray(n) { i -> nearest(pixels[i], centres) }
        // The first guess at the subject: central pixels unlike the background, eroded so noise does not count,
        // and not where a remove stroke reaches.
        var guessed = 0
        val x0 = (w * 0.18f).toInt(); val x1 = (w * 0.82f).toInt(); val y0 = (h * 0.12f).toInt(); val y1 = (h * 0.88f).toInt()
        val candidate = BooleanArray(n) { i -> val x = i % w; val y = i / w; x in x0 until x1 && y in y0 until y1 && bgDist[i] > 48 }
        for (y in y0 + 2 until y1 - 2) for (x in x0 + 2 until x1 - 2) {
            val i = y * w + x
            if (!candidate[i] || seed[i].toInt() != 0) continue
            if (fromRemove != null && fromRemove[i] <= STROKE_REACH) continue
            var all = true
            loop@ for (dy in -2..2) for (dx in -2..2) if (!candidate[i + dy * w + dx]) { all = false; break@loop }
            if (all) { seed[i] = 1; guessed++ }
        }
        if (guessed < n / 200 && !hasKeep) {
            // Nothing stood out: assume the middle, and let strokes do the rest.
            val r = 0.12f * shorter; val cx = w / 2f; val cy = h / 2f
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (seed[i].toInt() == 0 && (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r && (fromRemove == null || fromRemove[i] > STROKE_REACH)) seed[i] = 1
            }
        }
        check()
        val toKeep = geodesic(pixels, w, h, seed, 1, bgDist, towardsSubject = true, check)
        val toRemove = geodesic(pixels, w, h, seed, 2, bgDist, towardsSubject = false, check)
        val mask = IntArray(n) { i ->
            when (stroked[i].toInt()) { 1 -> 255; 2 -> 0; else -> if (toKeep[i] < toRemove[i]) 255 else 0 }
        }
        tidy(mask, w, h, stroked, minArea = max(16, n / 250))
        return mask
    }

    /** How far, in flat colour, a stroke's say reaches past its own pixels: about a few percent of the picture. */
    private const val STROKE_REACH = 0.25f

    private fun stamp(s: Scribble, w: Int, h: Int, shorter: Int, mark: (Int) -> Unit) {
        val r = max(1f, s.width * shorter / 2f)
        val pts = s.points.map { Pair(it.x * (w - 1), it.y * (h - 1)) }
        fun disc(cx: Float, cy: Float) {
            val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt() + 1)
            val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt() + 1)
            for (y in y0..y1) for (x in x0..x1) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) mark(y * w + x)
        }
        if (pts.size == 1) disc(pts[0].first, pts[0].second)
        for (k in 1 until pts.size) {
            val (ax, ay) = pts[k - 1]; val (bx, by) = pts[k]
            val steps = max(1, (max(abs(bx - ax), abs(by - ay)) / max(1f, r * 0.5f)).toInt())
            for (t in 0..steps) { val f = t.toFloat() / steps; disc(ax + (bx - ax) * f, ay + (by - ay) * f) }
        }
    }

    /** K-means in RGB over the pixels whose seed equals [of], from evenly spaced starts; deterministic. */
    private fun kMeans(pixels: IntArray, seed: ByteArray, of: Int, k: Int): IntArray {
        val sample = ArrayList<Int>()
        var count = 0
        for (i in pixels.indices) if (seed[i].toInt() == of) { if (count % 3 == 0) sample += pixels[i]; count++ }
        if (sample.isEmpty()) return intArrayOf(0xFFFFFFFF.toInt())
        val centres = IntArray(min(k, sample.size)) { sample[it * sample.size / min(k, sample.size)] }
        val sums = LongArray(centres.size * 3); val counts = IntArray(centres.size)
        repeat(6) {
            sums.fill(0); counts.fill(0)
            for (c in sample) {
                var best = 0; var bd = Int.MAX_VALUE
                for (j in centres.indices) { val d = dist2(c, centres[j]); if (d < bd) { bd = d; best = j } }
                sums[best * 3] += Argb.red(c).toLong(); sums[best * 3 + 1] += Argb.green(c).toLong(); sums[best * 3 + 2] += Argb.blue(c).toLong(); counts[best]++
            }
            for (j in centres.indices) if (counts[j] > 0) centres[j] = Argb.pack(255, (sums[j * 3] / counts[j]).toInt(), (sums[j * 3 + 1] / counts[j]).toInt(), (sums[j * 3 + 2] / counts[j]).toInt())
        }
        return centres
    }

    private fun dist2(a: Int, b: Int): Int { val dr = Argb.red(a) - Argb.red(b); val dg = Argb.green(a) - Argb.green(b); val db = Argb.blue(a) - Argb.blue(b); return dr * dr + dg * dg + db * db }
    private fun nearest(c: Int, centres: IntArray): Int { var best = Int.MAX_VALUE; for (m in centres) best = min(best, dist2(c, m)); return sqrt(best.toFloat()).toInt() }

    /**
     * Dijkstra from every pixel whose seed is [from], over 8 neighbours. A step costs its length
     * times a small floor plus the colour change it crosses, so distance follows edges; stepping
     * through colours that look like background is dearer on the way to the subject, and the
     * reverse on the way to the background, which is the colour prior.
     */
    private fun geodesic(pixels: IntArray, w: Int, h: Int, seed: ByteArray, from: Int, bgDist: IntArray?, towardsSubject: Boolean, check: () -> Unit): FloatArray {
        val n = w * h
        val dist = FloatArray(n) { Float.MAX_VALUE }
        val queue = PriorityQueue<Long>()
        for (i in 0 until n) if (seed[i].toInt() == from) { dist[i] = 0f; queue.add(i.toLong()) }
        val dx = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1); val dy = intArrayOf(0, 0, -1, 1, -1, -1, 1, 1)
        val len = floatArrayOf(1f, 1f, 1f, 1f, 1.4142f, 1.4142f, 1.4142f, 1.4142f)
        var popped = 0
        while (queue.isNotEmpty()) {
            val packed = queue.poll() ?: break
            val d = java.lang.Float.intBitsToFloat((packed ushr 32).toInt())
            val i = (packed and 0xFFFFFFFFL).toInt()
            if (d > dist[i]) continue
            if (++popped and 0x3FFF == 0) check()
            val x = i % w; val y = i / w
            val c = pixels[i]
            for (k in 0 until 8) {
                val nx = x + dx[k]; val ny = y + dy[k]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                val diff = maxChannelDiff(c, pixels[j]) / 255f
                val prior = if (bgDist == null) 1f else if (towardsSubject) 1f + max(0f, 1f - bgDist[j] / 90f) else 1f + (bgDist[j] - 40f).coerceIn(0f, 90f) / 90f
                val nd = d + len[k] * (0.02f + diff) * prior
                if (nd < dist[j]) { dist[j] = nd; queue.add((java.lang.Float.floatToIntBits(nd).toLong() shl 32) or j.toLong()) }
            }
        }
        return dist
    }

    private fun maxChannelDiff(a: Int, b: Int): Int = max(abs(Argb.red(a) - Argb.red(b)), max(abs(Argb.green(a) - Argb.green(b)), abs(Argb.blue(a) - Argb.blue(b))))

    /** Drops kept islands and fills removed holes smaller than [minArea], except where a stroke asked for them. */
    private fun tidy(mask: IntArray, w: Int, h: Int, stroked: ByteArray, minArea: Int) {
        val n = w * h
        val label = IntArray(n)
        val stack = IntArray(n)
        var next = 1
        for (start in 0 until n) {
            if (label[start] != 0) continue
            val value = mask[start]
            var sp = 0; stack[sp++] = start; label[start] = next
            var area = 0; var asked = false; var touchesBorder = false
            val members = ArrayList<Int>()
            while (sp > 0) {
                val i = stack[--sp]; area++; members += i
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesBorder = true
                val s = stroked[i].toInt(); if ((value == 255 && s == 1) || (value == 0 && s == 2)) asked = true
                if (x > 0 && label[i - 1] == 0 && mask[i - 1] == value) { label[i - 1] = next; stack[sp++] = i - 1 }
                if (x < w - 1 && label[i + 1] == 0 && mask[i + 1] == value) { label[i + 1] = next; stack[sp++] = i + 1 }
                if (y > 0 && label[i - w] == 0 && mask[i - w] == value) { label[i - w] = next; stack[sp++] = i - w }
                if (y < h - 1 && label[i + w] == 0 && mask[i + w] == value) { label[i + w] = next; stack[sp++] = i + w }
            }
            // A removed region that touches the border is the background itself, never a hole.
            if (area < minArea && !asked && !(value == 0 && touchesBorder)) for (i in members) mask[i] = 255 - value
            next++
        }
    }

    // ------------------------------------------------------------------ ink

    /** Dark or strongly coloured marks on light paper, kept with a soft edge. */
    fun ink(pixels: IntArray, w: Int, h: Int, threshold: Float): IntArray {
        require(pixels.size == w * h)
        val thr = if (threshold > 0f) (threshold * 255).roundToInt() else {
            val otsu = AutoEnhance.otsu(Histogram.of(pixels, 1))
            // Paper photographed in shade is grey, not white: lean the cut towards the paper.
            min(otsu + 12, 235)
        }
        val soft = 12f
        return IntArray(w * h) { i ->
            val c = pixels[i]
            val l = Argb.luma(c)
            val dark = ((thr + soft - l) / (2 * soft)).coerceIn(0f, 1f)
            val r = Argb.red(c); val g = Argb.green(c); val b = Argb.blue(c)
            val colourful = (max(r, max(g, b)) - min(r, min(g, b)) - 40) / 50f
            (255 * max(dark, colourful.coerceIn(0f, 1f))).roundToInt()
        }
    }

    // ------------------------------------------------------------------ finishing

    /** Softens the edge by [radiusPx] with two box passes; the mask stays 0..255. */
    fun feather(mask: IntArray, w: Int, h: Int, radiusPx: Int): IntArray {
        if (radiusPx <= 0) return mask
        var a = mask.copyOf()
        var tmp = IntArray(a.size)
        repeat(2) {
            boxPass(a, tmp, w, h, radiusPx, horizontal = true)
            boxPass(tmp, a, w, h, radiusPx, horizontal = false)
        }
        return a
    }

    private fun boxPass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        for (o in 0 until lines) {
            fun idx(i: Int) = if (horizontal) o * w + i else i * w + o
            var sum = 0; var count = 0
            for (i in 0..min(length - 1, r)) { sum += input[idx(i)]; count++ }
            for (i in 0 until length) {
                output[idx(i)] = sum / count
                val add = i + r + 1; val drop = i - r
                if (add < length) { sum += input[idx(add)]; count++ }
                if (drop >= 0) { sum -= input[idx(drop)]; count-- }
            }
        }
    }

    /** The mask at another size, bilinear, so a decision made small draws smooth large. */
    fun resample(mask: IntArray, w: Int, h: Int, outW: Int, outH: Int): IntArray {
        if (w == outW && h == outH) return mask
        val out = IntArray(outW * outH)
        val sx = (w - 1).toFloat() / max(1, outW - 1); val sy = (h - 1).toFloat() / max(1, outH - 1)
        for (y in 0 until outH) {
            val fy = y * sy; val y0 = fy.toInt().coerceIn(0, h - 1); val y1 = min(h - 1, y0 + 1); val wy = fy - y0
            for (x in 0 until outW) {
                val fx = x * sx; val x0 = fx.toInt().coerceIn(0, w - 1); val x1 = min(w - 1, x0 + 1); val wx = fx - x0
                val top = mask[y0 * w + x0] * (1 - wx) + mask[y0 * w + x1] * wx
                val bottom = mask[y1 * w + x0] * (1 - wx) + mask[y1 * w + x1] * wx
                out[y * outW + x] = (top * (1 - wy) + bottom * wy).roundToInt()
            }
        }
        return out
    }

    /** Multiplies the pixels' alpha by the mask (inverted if asked), premultiplication-free since the pixels are straight ARGB. */
    fun applyAlpha(pixels: IntArray, mask: IntArray, invert: Boolean) {
        for (i in pixels.indices) {
            val m = if (invert) 255 - mask[i] else mask[i]
            val a = Argb.alpha(pixels[i]) * m / 255
            pixels[i] = Argb.withAlpha(pixels[i], a)
        }
    }

    /** The kept fraction of the picture, for the result note. */
    fun coverage(mask: IntArray): Float = if (mask.isEmpty()) 0f else (mask.sumOf { it.toLong() }.toDouble() / (255.0 * mask.size)).toFloat()
}
