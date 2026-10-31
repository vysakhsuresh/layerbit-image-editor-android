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

/** What goes behind the subject: nothing, a flat colour, a blur of the picture, or the picture drained of colour (the subject stays in colour). */
enum class Backdrop { TRANSPARENT, COLOUR, BLUR, MONO }

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
    val blur: Float = 0.03f,
    /** Grows (positive) or shrinks (negative) the kept area, as a fraction of the shorter edge, -0.03..0.03. */
    val grow: Float = 0f,
    /** Decide the edge pixels from the picture's own colours, and clean the colour fringe off them. */
    val refine: Boolean = true,
    /** A sticker outline around the subject, as a fraction of the shorter edge, 0..0.04. 0 is none. */
    val outline: Float = 0f,
    val outlineColor: Int = 0xFFFFFFFF.toInt(),
    /** Crop the result to the subject, with a small margin. */
    val trim: Boolean = false,
    /** A soft shadow cast down and right of the subject onto what is behind it, 0..1. */
    val shadow: Float = 0f
) {
    val isIdentity: Boolean get() = this == Cutout()
    val valid: Boolean
        get() = scribbles.size <= MAX_SCRIBBLES && scribbles.all { it.valid } && feather in 0f..0.05f && threshold in 0f..1f && blur in 0f..0.1f &&
            grow in -0.03f..0.03f && outline in 0f..0.04f && shadow in 0f..1f

    /** What decides the mask itself, as a key: the strokes, the mode, the threshold and the grow. */
    val maskKey: String get() = "$mode|$threshold|$grow|" + scribbles.joinToString(";") { s -> (if (s.keep) "k" else "r") + s.width + ":" + s.points.joinToString(",") { "${it.x},${it.y}" } }

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
    fun mask(pixels: IntArray, w: Int, h: Int, cutout: Cutout, check: () -> Unit = {}): IntArray {
        val decided = when (cutout.mode) {
            CutoutMode.SUBJECT -> subject(pixels, w, h, cutout.scribbles, check)
            CutoutMode.INK -> ink(pixels, w, h, cutout.threshold)
        }
        val px = (cutout.grow * min(w, h)).roundToInt()
        return when {
            px > 0 -> grow(decided, w, h, px)
            px < 0 -> shrink(decided, w, h, -px)
            else -> decided
        }
    }

    /** The mask widened by [radiusPx]: a kept pixel within reach makes a pixel kept (a separable max). */
    fun grow(mask: IntArray, w: Int, h: Int, radiusPx: Int): IntArray = extremum(mask, w, h, radiusPx, wantMax = true)

    /** The mask narrowed by [radiusPx]: a removed pixel within reach makes a pixel removed (a separable min). */
    fun shrink(mask: IntArray, w: Int, h: Int, radiusPx: Int): IntArray = extremum(mask, w, h, radiusPx, wantMax = false)

    private fun extremum(mask: IntArray, w: Int, h: Int, r: Int, wantMax: Boolean): IntArray {
        if (r <= 0) return mask
        val tmp = IntArray(mask.size); val out = IntArray(mask.size)
        Parallel.bands(h, minimum = 64) { from, to -> for (y in from until to) for (x in 0 until w) {
            var v = mask[y * w + x]
            for (k in max(0, x - r)..min(w - 1, x + r)) { val m = mask[y * w + k]; v = if (wantMax) max(v, m) else min(v, m) }
            tmp[y * w + x] = v
        } }
        Parallel.bands(h, minimum = 64) { from, to -> for (y in from until to) for (x in 0 until w) {
            var v = tmp[y * w + x]
            for (k in max(0, y - r)..min(h - 1, y + r)) { val m = tmp[k * w + x]; v = if (wantMax) max(v, m) else min(v, m) }
            out[y * w + x] = v
        } }
        return out
    }

    /** The kept area's bounds as fractions of the picture, widened by [margin], or null when nothing is kept. */
    fun bounds(mask: IntArray, w: Int, h: Int, margin: Float = 0.02f, threshold: Int = 16): FloatArray? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x] >= threshold) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        return floatArrayOf(
            ((x0.toFloat() / w) - margin).coerceIn(0f, 1f), ((y0.toFloat() / h) - margin).coerceIn(0f, 1f),
            (((x1 + 1).toFloat() / w) + margin).coerceIn(0f, 1f), (((y1 + 1).toFloat() / h) + margin).coerceIn(0f, 1f)
        )
    }

    // ------------------------------------------------------------------ the edge, from the picture's own colours

    /**
     * Two colour maps the size of the mask: what the subject looks like near each pixel, and
     * what the background looks like, each a box-weighted mean of the confident pixels on its
     * side within [radiusPx]. Where a side is out of reach its global mean stands in.
     */
    fun localModels(pixels: IntArray, w: Int, h: Int, mask: IntArray, radiusPx: Int): Pair<IntArray, IntArray> {
        val n = w * h
        val fgW = FloatArray(n); val bgW = FloatArray(n)
        val fr = FloatArray(n); val fg = FloatArray(n); val fb = FloatArray(n)
        val br = FloatArray(n); val bgG = FloatArray(n); val bb = FloatArray(n)
        var gfr = 0.0; var gfg = 0.0; var gfb = 0.0; var gfn = 0.0; var gbr = 0.0; var gbg = 0.0; var gbb = 0.0; var gbn = 0.0
        for (i in 0 until n) {
            val c = pixels[i]; val m = mask[i]
            val r = Argb.red(c).toFloat(); val g = Argb.green(c).toFloat(); val b = Argb.blue(c).toFloat()
            if (m >= 240) { fgW[i] = 1f; fr[i] = r; fg[i] = g; fb[i] = b; gfr += r; gfg += g; gfb += b; gfn++ }
            else if (m <= 15) { bgW[i] = 1f; br[i] = r; bgG[i] = g; bb[i] = b; gbr += r; gbg += g; gbb += b; gbn++ }
        }
        val r = max(1, radiusPx)
        for (a in listOf(fgW, fr, fg, fb, bgW, br, bgG, bb)) blurFloat(a, w, h, r)
        val gFg = if (gfn > 0) Argb.pack(255, (gfr / gfn).toInt(), (gfg / gfn).toInt(), (gfb / gfn).toInt()) else Argb.pack(255, 128, 128, 128)
        val gBg = if (gbn > 0) Argb.pack(255, (gbr / gbn).toInt(), (gbg / gbn).toInt(), (gbb / gbn).toInt()) else Argb.pack(255, 128, 128, 128)
        val fgMap = IntArray(n); val bgMap = IntArray(n)
        for (i in 0 until n) {
            fgMap[i] = if (fgW[i] > 0.002f) Argb.pack(255, (fr[i] / fgW[i]).toInt().coerceIn(0, 255), (fg[i] / fgW[i]).toInt().coerceIn(0, 255), (fb[i] / fgW[i]).toInt().coerceIn(0, 255)) else gFg
            bgMap[i] = if (bgW[i] > 0.002f) Argb.pack(255, (br[i] / bgW[i]).toInt().coerceIn(0, 255), (bgG[i] / bgW[i]).toInt().coerceIn(0, 255), (bb[i] / bgW[i]).toInt().coerceIn(0, 255)) else gBg
        }
        return fgMap to bgMap
    }

    /** Two passes of a separable box mean over a float map, in place. */
    private fun blurFloat(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(a.size)
        repeat(2) {
            Parallel.bands(h, minimum = 64) { from, to -> for (y in from until to) {
                var sum = 0f; var count = 0
                for (i in 0..min(w - 1, r)) { sum += a[y * w + i]; count++ }
                for (x in 0 until w) {
                    tmp[y * w + x] = sum / count
                    val add = x + r + 1; val drop = x - r
                    if (add < w) { sum += a[y * w + add]; count++ }
                    if (drop >= 0) { sum -= a[y * w + drop]; count-- }
                }
            } }
            Parallel.bands(w, minimum = 64) { from, to -> for (x in from until to) {
                var sum = 0f; var count = 0
                for (i in 0..min(h - 1, r)) { sum += tmp[i * w + x]; count++ }
                for (y in 0 until h) {
                    a[y * w + x] = sum / count
                    val add = y + r + 1; val drop = y - r
                    if (add < h) { sum += tmp[add * w + x]; count++ }
                    if (drop >= 0) { sum -= tmp[drop * w + x]; count-- }
                }
            } }
        }
    }

    /**
     * How much of [c] is subject, 0..255, judged from how close it is to the local subject colour
     * [fg] against the local background colour [bg]; -1 when the two are too alike to say.
     */
    fun colourAlpha(c: Int, fg: Int, bg: Int): Int {
        val dr = Argb.red(fg) - Argb.red(bg); val dg = Argb.green(fg) - Argb.green(bg); val db = Argb.blue(fg) - Argb.blue(bg)
        val len2 = dr * dr + dg * dg + db * db
        if (len2 < 40 * 40) return -1
        // The pixel projected onto the line from background to subject: 0 at the background, 1 at the subject.
        val t = ((Argb.red(c) - Argb.red(bg)) * dr + (Argb.green(c) - Argb.green(bg)) * dg + (Argb.blue(c) - Argb.blue(bg)) * db).toFloat() / len2
        return (t * 255f).roundToInt().coerceIn(0, 255)
    }

    /** [c] with the background's share taken out, so a soft edge carries the subject's colour and not a fringe. */
    fun decontaminate(c: Int, bg: Int, alpha: Int): Int {
        if (alpha >= 250 || alpha <= 5) return c
        val a = alpha / 255f
        fun ch(v: Int, b: Int) = ((v - (1f - a) * b) / a).roundToInt().coerceIn(0, 255)
        return Argb.pack(Argb.alpha(c), ch(Argb.red(c), Argb.red(bg)), ch(Argb.green(c), Argb.green(bg)), ch(Argb.blue(c), Argb.blue(bg)))
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
        val (fromKeep, fromRemove) = Parallel.both(
            { if (hasKeep) geodesic(pixels, w, h, stroked, 1, null, towardsSubject = true, check) else null },
            { if (hasRemove) geodesic(pixels, w, h, stroked, 2, null, towardsSubject = false, check) else null }
        )
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
        // The two searches never touch each other's answer, so they run side by side.
        val (toKeep, toRemove) = Parallel.both(
            { geodesic(pixels, w, h, seed, 1, bgDist, towardsSubject = true, check) },
            { geodesic(pixels, w, h, seed, 2, bgDist, towardsSubject = false, check) }
        )
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
        Parallel.bands(lines, minimum = 64) { from, to -> for (o in from until to) {
            fun idx(i: Int) = if (horizontal) o * w + i else i * w + o
            var sum = 0; var count = 0
            for (i in 0..min(length - 1, r)) { sum += input[idx(i)]; count++ }
            for (i in 0 until length) {
                output[idx(i)] = sum / count
                val add = i + r + 1; val drop = i - r
                if (add < length) { sum += input[idx(add)]; count++ }
                if (drop >= 0) { sum -= input[idx(drop)]; count-- }
            }
        } }
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

    /** One value of a small map at a fractional position, bilinear; with [channels], the map holds ARGB and each channel is mixed. */
    fun sample(map: IntArray, mw: Int, mh: Int, fx: Float, fy: Float, channels: Boolean = false): Int {
        if (channels) {
            val x0 = fx.toInt().coerceIn(0, mw - 1); val y0 = fy.toInt().coerceIn(0, mh - 1)
            val x1 = min(mw - 1, x0 + 1); val y1 = min(mh - 1, y0 + 1)
            val wx = (fx - x0).coerceIn(0f, 1f); val wy = (fy - y0).coerceIn(0f, 1f)
            val a = map[y0 * mw + x0]; val b = map[y0 * mw + x1]; val c = map[y1 * mw + x0]; val d = map[y1 * mw + x1]
            fun ch(shift: Int): Int {
                val top = ((a shr shift) and 0xFF) * (1 - wx) + ((b shr shift) and 0xFF) * wx
                val bottom = ((c shr shift) and 0xFF) * (1 - wx) + ((d shr shift) and 0xFF) * wx
                return (top * (1 - wy) + bottom * wy).roundToInt().coerceIn(0, 255)
            }
            return Argb.pack(255, ch(16), ch(8), ch(0))
        }
        val x0 = fx.toInt().coerceIn(0, mw - 1); val y0 = fy.toInt().coerceIn(0, mh - 1)
        val x1 = min(mw - 1, x0 + 1); val y1 = min(mh - 1, y0 + 1)
        val wx = (fx - x0).coerceIn(0f, 1f); val wy = (fy - y0).coerceIn(0f, 1f)
        val top = map[y0 * mw + x0] * (1 - wx) + map[y0 * mw + x1] * wx
        val bottom = map[y1 * mw + x0] * (1 - wx) + map[y1 * mw + x1] * wx
        return (top * (1 - wy) + bottom * wy).roundToInt()
    }

    /** The mask value under picture pixel ([x], [y]) of a [w] x [h] picture, from a mask of [mw] x [mh]. */
    fun maskAt(mask: IntArray, mw: Int, mh: Int, x: Int, y: Int, w: Int, h: Int, invert: Boolean): Int {
        val m = sample(mask, mw, mh, x.toFloat() * (mw - 1) / max(1, w - 1), y.toFloat() * (mh - 1) / max(1, h - 1))
        return if (invert) 255 - m else m
    }

    /**
     * Source-over with straight alpha: [top] over [under], both ARGB. Colour is weighted by each
     * layer's own alpha, so a translucent edge over a translucent ground stays the right colour.
     */
    /**
     * The shadow a kept area casts: the mask moved down and right by [offsetPx] and softened by
     * [softPx], at the analysis size, so it scales with the picture like the mask itself.
     */
    fun shadow(mask: IntArray, w: Int, h: Int, offsetPx: Int, softPx: Int): IntArray {
        val moved = IntArray(mask.size)
        val dx = offsetPx / 2; val dy = offsetPx
        for (y in 0 until h) {
            val sy = y - dy
            if (sy !in 0 until h) continue
            for (x in 0 until w) {
                val sx = x - dx
                if (sx in 0 until w) moved[y * w + x] = mask[sy * w + sx]
            }
        }
        return feather(moved, w, h, softPx)
    }

    fun over(top: Int, under: Int): Int {
        val a = Argb.alpha(top); val ua = Argb.alpha(under)
        if (a == 255 || ua == 0) return top
        val outA = a + ua * (255 - a) / 255
        if (outA == 0) return 0
        fun ch(t: Int, u: Int) = ((t * a + u * ua * (255 - a) / 255) / outA).coerceIn(0, 255)
        return Argb.pack(outA, ch(Argb.red(top), Argb.red(under)), ch(Argb.green(top), Argb.green(under)), ch(Argb.blue(top), Argb.blue(under)))
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
