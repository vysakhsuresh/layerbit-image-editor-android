package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the page is in a photograph of a page: four corners for the Scan and Perspective tools
 * to start from, so the user drags a handle only when this guessed wrong.
 *
 * Classical and small on purpose - no model, nothing downloaded, the same answer every time. A
 * page is the one large, bright, four-sided region in the picture that does not reach most of
 * the way round its border:
 *  1. the picture is shrunk to about [WORK] pixels on its long side and blurred a little, which
 *     makes it fast and turns text, paper grain and table texture into flat areas;
 *  2. brightness is split into page and not-page at two Otsu thresholds, the whole picture's
 *     and its brighter half's, because a white page on a grey desk next to a dark keyboard needs
 *     the second split, not the first. (A split of the darker half is not tried: it only ever
 *     joins highlights in the table to the page.)
 *  3. the largest bright connected region that touches little of the frame's edge is taken, its
 *     edge found to a fraction of a pixel, and its convex hull reduced to the four-sided shape
 *     of largest area;
 *  4. a straight line is fitted to the edge along each side and neighbouring lines intersected,
 *     which puts the corners back where the blur rounded them off;
 *  5. the result has to fill the region it came from, cover a real share of the picture and not
 *     be the frame itself, or there is no page and the answer is null - a handle the user has
 *     to move is better than one that confidently sits on the wrong thing.
 */
object PageQuad {

    /** Corners in the same frame and order as [Quad], and how sure the finder is, 0 to 1. */
    data class Found(val quad: Quad, val confidence: Float)

    /** Long side of the working copy. A page is large, so this is plenty and it keeps a 12 MP photo under a few milliseconds of work. */
    const val WORK = 256

    /** The page must cover at least this much of the picture. */
    const val MIN_AREA = 0.15f

    private const val MIN_FIT = 0.85f
    private const val MAX_BORDER_TOUCH = 0.4f
    /** Luma a page's edge must rise by, inside over outside, along every side that is not the frame's. */
    private const val MIN_STEP = 20f

    /**
     * The page in [pixels] (ARGB, [w] by [h], upright), or null when there is not clearly one.
     * Corners are fractions of the picture: (0, 0) top left, (1, 1) bottom right.
     */
    fun find(pixels: IntArray, w: Int, h: Int): Found? {
        if (w < 16 || h < 16 || pixels.size < w * h) return null
        val s = max(1, ceil(max(w, h) / WORK.toDouble()).toInt())
        val sw = w / s; val sh = h / s
        if (sw < 12 || sh < 12) return null
        val lum = blur(shrink(pixels, w, s, sw, sh), sw, sh)

        val hist = IntArray(256)
        for (v in lum) hist[v.toInt().coerceIn(0, 255)]++
        val t0 = otsu(hist, 0, 255)
        val thresholds = linkedSetOf(t0, otsu(hist, t0, 255))

        var best: Found? = null; var bestArea = 0f
        for (t in thresholds) {
            val found = candidate(lum, sw, sh, t + 0.5f) ?: continue
            val area = shoelace(found.quad.points.map { it.x to it.y })
            if (best == null || found.confidence > best.confidence + 0.05f || (found.confidence > best.confidence - 0.05f && area > bestArea)) {
                best = found; bestArea = area
            }
        }
        // Back from the working copy's pixel centres to fractions of the source.
        return best?.let { f ->
            fun back(p: Pt) = Pt(((p.x + 0.5f) * s / w).coerceIn(0f, 1f), ((p.y + 0.5f) * s / h).coerceIn(0f, 1f))
            val q = Quad(back(f.quad.tl), back(f.quad.tr), back(f.quad.br), back(f.quad.bl))
            if (!q.valid || nearFrame(q)) null else Found(q, f.confidence)
        }
    }

    /** Mean luma over [s] by [s] blocks: one pass over the source, the only one. */
    private fun shrink(pixels: IntArray, w: Int, s: Int, sw: Int, sh: Int): FloatArray {
        val sum = IntArray(sw * sh)
        for (y in 0 until sh * s) {
            val row = (y / s) * sw; val base = y * w
            for (bx in 0 until sw) {
                var acc = 0; val from = base + bx * s
                for (i in from until from + s) acc += Argb.luma(pixels[i])
                sum[row + bx] += acc
            }
        }
        val n = (s * s).toFloat()
        return FloatArray(sw * sh) { sum[it] / n }
    }

    /** Two passes of a 3x3 box: close to a Gaussian of radius 2, enough to dissolve text and grain. */
    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
        var a = src; var b = FloatArray(src.size)
        repeat(2) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                b[i] = (a[i - if (x > 0) 1 else 0] + a[i] + a[i + if (x < w - 1) 1 else 0]) / 3f
            }
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                a[i] = (b[i - if (y > 0) w else 0] + b[i] + b[i + if (y < h - 1) w else 0]) / 3f
            }
        }
        return a
    }

    /** Otsu's threshold over bins [lo]..[hi] only; pixels at or below the result are "dark". */
    private fun otsu(hist: IntArray, lo: Int, hi: Int): Int {
        if (hi <= lo) return lo
        var total = 0.0; var sum = 0.0
        for (i in lo..hi) { total += hist[i]; sum += i.toDouble() * hist[i] }
        var below = 0.0; var sumBelow = 0.0; var bestVar = -1.0; var best = (lo + hi) / 2
        for (t in lo until hi) {
            below += hist[t]; sumBelow += t.toDouble() * hist[t]
            val above = total - below
            if (below == 0.0) continue
            if (above == 0.0) break
            val d = sumBelow / below - (sum - sumBelow) / above
            val v = below * above * d * d
            if (v > bestVar) { bestVar = v; best = t }
        }
        return best
    }

    /** The page at one threshold, in working-copy pixel coordinates, or null. */
    private fun candidate(lum: FloatArray, w: Int, h: Int, t: Float): Found? {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val border = 2 * (w + h) - 4
        var next = 0; var bestLabel = 0; var bestCount = 0
        for (seed in label.indices) {
            if (label[seed] != 0 || lum[seed] <= t) continue
            next++
            var top = 0; stack[top++] = seed; label[seed] = next
            var count = 0; var touch = 0
            while (top > 0) {
                val i = stack[--top]; count++
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touch++
                if (x > 0 && label[i - 1] == 0 && lum[i - 1] > t) { label[i - 1] = next; stack[top++] = i - 1 }
                if (x < w - 1 && label[i + 1] == 0 && lum[i + 1] > t) { label[i + 1] = next; stack[top++] = i + 1 }
                if (y > 0 && label[i - w] == 0 && lum[i - w] > t) { label[i - w] = next; stack[top++] = i - w }
                if (y < h - 1 && label[i + w] == 0 && lum[i + w] > t) { label[i + w] = next; stack[top++] = i + w }
            }
            // Touching most of the frame's edge makes it the background, or a page filling the frame.
            if (touch <= MAX_BORDER_TOUCH * border && count > bestCount) { bestCount = count; bestLabel = next }
        }
        if (bestCount < MIN_AREA * 0.8f * w * h) return null

        // The region's edge, to a fraction of a pixel, from every row and every column it crosses.
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        fun edge(a: Int, b: Int): Float { // a outside, b inside, one step apart
            val la = lum[a]; val lb = lum[b]
            return if (lb > la) ((t - la) / (lb - la)).coerceIn(0f, 1f) else 0.5f
        }
        for (y in 0 until h) {
            var l = -1; var r = -1
            for (x in 0 until w) if (label[y * w + x] == bestLabel) { if (l < 0) l = x; r = x }
            if (l < 0) continue
            xs += if (l > 0) l - 1 + edge(y * w + l - 1, y * w + l) else l - 0.5f; ys += y.toFloat()
            xs += if (r < w - 1) r + 1 - edge(y * w + r + 1, y * w + r) else r + 0.5f; ys += y.toFloat()
        }
        for (x in 0 until w) {
            var tp = -1; var bt = -1
            for (y in 0 until h) if (label[y * w + x] == bestLabel) { if (tp < 0) tp = y; bt = y }
            if (tp < 0) continue
            xs += x.toFloat(); ys += if (tp > 0) tp - 1 + edge((tp - 1) * w + x, tp * w + x) else tp - 0.5f
            xs += x.toFloat(); ys += if (bt < h - 1) bt + 1 - edge((bt + 1) * w + x, bt * w + x) else bt + 0.5f
        }

        // The region with its holes filled (text, a stain): everything not reachable from the frame's edge without crossing it.
        var top = 0; var outside = 0
        val seen = BooleanArray(w * h)
        for (i in label.indices) {
            val x = i % w; val y = i / w
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && label[i] != bestLabel && !seen[i]) { seen[i] = true; stack[top++] = i }
        }
        while (top > 0) {
            val i = stack[--top]; outside++
            val x = i % w; val y = i / w
            if (x > 0 && !seen[i - 1] && label[i - 1] != bestLabel) { seen[i - 1] = true; stack[top++] = i - 1 }
            if (x < w - 1 && !seen[i + 1] && label[i + 1] != bestLabel) { seen[i + 1] = true; stack[top++] = i + 1 }
            if (y > 0 && !seen[i - w] && label[i - w] != bestLabel) { seen[i - w] = true; stack[top++] = i - w }
            if (y < h - 1 && !seen[i + w] && label[i + w] != bestLabel) { seen[i + w] = true; stack[top++] = i + w }
        }
        val filled = (w * h - outside).toFloat()

        val hull = simplify(hull(xs, ys), 40)
        if (hull.size < 4) return null
        val corners = refine(largestQuad(hull), xs, ys, max(w, h)) ?: return null
        val ordered = order(corners)
        val quadArea = shoelace(ordered)
        if (quadArea < MIN_AREA * w * h) return null
        // A page fills the shape fitted to it; a disc, a blob or a cloud of speckle does not.
        val fit = min(quadArea, filled) / max(quadArea, filled)
        if (fit < MIN_FIT) return null
        val step = weakestSide(ordered, lum, w, h) ?: return null
        if (step < MIN_STEP) return null
        val confidence = ((fit - MIN_FIT) / (0.97f - MIN_FIT)).coerceIn(0f, 1f) * (step / 64f).coerceIn(0f, 1f)
        val p = ordered.map { Pt(it.first, it.second) }
        return Found(Quad(p[0], p[1], p[2], p[3]), confidence)
    }

    /**
     * The smallest brightness step across any side of [c]: inside minus outside, a few pixels
     * either way, averaged along the side's middle. A region found in texture or noise has an
     * outline but no edge, and this is what tells the two apart. A side lying along the frame
     * (a page cut off by the photo) has no outside to measure and is skipped; null if all are.
     */
    private fun weakestSide(c: List<Pair<Float, Float>>, lum: FloatArray, w: Int, h: Int): Float? {
        val d = 3f
        var weakest: Float? = null
        for (k in 0 until 4) {
            val a = c[k]; val b = c[(k + 1) % 4]
            val dx = b.first - a.first; val dy = b.second - a.second
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1f) return null
            // Inward normal: corners run clockwise on screen, so inside is to the right of a->b.
            val nx = -dy / len; val ny = dx / len
            var sum = 0f; var n = 0
            for (j in 0 until 16) {
                val f = 0.15f + 0.7f * j / 15f
                val px = a.first + dx * f; val py = a.second + dy * f
                val ox = (px - nx * d).toInt(); val oy = (py - ny * d).toInt()
                val ix = (px + nx * d).toInt(); val iy = (py + ny * d).toInt()
                if (ox !in 0 until w || oy !in 0 until h || ix !in 0 until w || iy !in 0 until h) continue
                sum += lum[iy * w + ix] - lum[oy * w + ox]; n++
            }
            if (n >= 8) weakest = min(weakest ?: Float.MAX_VALUE, sum / n)
        }
        return weakest
    }

    /** Andrew's monotone chain; counter-clockwise on screen (y down), no repeated points. */
    private fun hull(xs: List<Float>, ys: List<Float>): List<Pair<Float, Float>> {
        val pts = xs.indices.map { xs[it] to ys[it] }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (pts.size < 3) return pts
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val out = ArrayList<Pair<Float, Float>>()
        for (p in pts) { while (out.size >= 2 && cross(out[out.size - 2], out.last(), p) <= 0f) out.removeAt(out.size - 1); out += p }
        val lower = out.size + 1
        for (p in pts.asReversed().drop(1)) { while (out.size >= lower && cross(out[out.size - 2], out.last(), p) <= 0f) out.removeAt(out.size - 1); out += p }
        out.removeAt(out.size - 1)
        return out
    }

    /** Drops the hull vertex that adds least area until [max] remain, so the quad search stays tiny. */
    private fun simplify(hull: List<Pair<Float, Float>>, max: Int): List<Pair<Float, Float>> {
        val p = hull.toMutableList()
        while (p.size > max) {
            var drop = 0; var least = Float.MAX_VALUE
            for (i in p.indices) {
                val a = tri(p[(i - 1 + p.size) % p.size], p[i], p[(i + 1) % p.size])
                if (a < least) { least = a; drop = i }
            }
            p.removeAt(drop)
        }
        return p
    }

    private fun tri(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>) =
        abs((b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)) / 2f

    /** The four hull vertices enclosing the most area: for every diagonal, the farthest vertex on each side. */
    private fun largestQuad(p: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val n = p.size
        var best = -1f; var pick = intArrayOf(0, 1, 2, 3)
        for (i in 0 until n) for (j in i + 2 until n) {
            var k = -1; var ka = -1f
            for (m in i + 1 until j) { val a = tri(p[i], p[m], p[j]); if (a > ka) { ka = a; k = m } }
            var l = -1; var la = -1f
            for (m in j + 1 until i + n) { val a = tri(p[j], p[m % n], p[i]); if (a > la) { la = a; l = m % n } }
            if (k >= 0 && l >= 0 && ka + la > best) { best = ka + la; pick = intArrayOf(i, k, j, l) }
        }
        return pick.map { p[it] }
    }

    /**
     * Each side replaced by the straight line fitted to the edge points beside its middle, and the
     * corners by where neighbouring lines cross. The blur rounds corners inwards; lines do not.
     * A corner that would move implausibly far keeps the hull's answer.
     */
    private fun refine(c: List<Pair<Float, Float>>, xs: List<Float>, ys: List<Float>, size: Int): List<Pair<Float, Float>>? {
        val reach = 2f + size * 0.015f
        val lines = (0 until 4).map { s ->
            val a = c[s]; val b = c[(s + 1) % 4]
            val dx = b.first - a.first; val dy = b.second - a.second
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1f) return null
            var n = 0; var mx = 0.0; var my = 0.0
            val keep = ArrayList<Int>()
            for (i in xs.indices) {
                val px = xs[i] - a.first; val py = ys[i] - a.second
                val along = (px * dx + py * dy) / (len * len)
                val off = abs(px * dy - py * dx) / len
                if (along in 0.12f..0.88f && off <= reach) { keep += i; n++; mx += xs[i]; my += ys[i] }
            }
            if (n < 6) null else {
                mx /= n; my /= n
                var sxx = 0.0; var sxy = 0.0; var syy = 0.0
                for (i in keep) { val u = xs[i] - mx; val v = ys[i] - my; sxx += u * u; sxy += u * v; syy += v * v }
                // Principal direction of the points: total least squares, fine for any slope.
                val theta = 0.5 * atan2(2 * sxy, sxx - syy)
                doubleArrayOf(mx, my, cos(theta), sin(theta))
            }
        }
        return (0 until 4).map { k ->
            val l1 = lines[(k + 3) % 4]; val l2 = lines[k]
            val hullCorner = c[k]
            if (l1 == null || l2 == null) return@map hullCorner
            val det = l1[2] * l2[3] - l1[3] * l2[2]
            if (abs(det) < 0.2) return@map hullCorner // nearly parallel sides: the crossing is unstable
            val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / det
            val x = (l1[0] + t * l1[2]).toFloat(); val y = (l1[1] + t * l1[3]).toFloat()
            val moved = sqrt((x - hullCorner.first) * (x - hullCorner.first) + (y - hullCorner.second) * (y - hullCorner.second))
            if (moved > size * 0.04f) hullCorner else x to y
        }
    }

    /** Top-left first, then clockwise on screen: the order of [Quad]. */
    private fun order(c: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val cx = c.sumOf { it.first.toDouble() } / 4; val cy = c.sumOf { it.second.toDouble() } / 4
        val byAngle = c.sortedBy { atan2(it.second - cy, it.first - cx) } // y down: ascending angle is clockwise
        val first = byAngle.indices.minBy { byAngle[it].first + byAngle[it].second }
        return (0 until 4).map { byAngle[(first + it) % 4] }
    }

    private fun shoelace(p: List<Pair<Float, Float>>): Float {
        var a = 0f
        for (i in p.indices) { val q = p[i]; val r = p[(i + 1) % p.size]; a += q.first * r.second - r.first * q.second }
        return abs(a) / 2f
    }

    /** Every corner within 2% of the frame's own: that is the whole picture, not a page in it. */
    private fun nearFrame(q: Quad): Boolean =
        q.points.zip(Quad.IDENTITY.points).all { (p, f) -> abs(p.x - f.x) < 0.02f && abs(p.y - f.y) < 0.02f }
}
