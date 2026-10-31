package com.layerbit.still.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spot healing: a brushed blemish is filled from a clean patch nearby, and the patch's colour
 * and brightness are bent to the blemish's surroundings so no edge shows.
 *
 * Two steps, both pure:
 *  - [findSource] picks the patch automatically, once, on the preview: every offset in a ring
 *    around the stroke is scored by how well the patch's border matches the blemish's border.
 *    The winner is stored on the mark as its [Mark.source], exactly as clone stores its source,
 *    so the export reuses the same choice and the preview predicts it.
 *  - [apply] copies the patch's texture and adds a smooth correction: the border difference
 *    between blemish and patch, spread across the inside by normalised convolution (a cheap
 *    membrane). The edge is feathered.
 *
 * Every distance is a fraction of the brush radius, which is itself a fraction of the shorter
 * canvas edge, so the same mark heals the same way on a 900-pixel preview and a 6000-pixel export.
 */
object Heal {

    /** The patch blends out to this many brush radii. */
    const val FEATHER = 1.4f
    /** The border band used for matching and colour correction, in brush radii beyond the feather. */
    const val BAND = 0.45f
    /** How far the automatic search looks, in multiples of the healed area's reach. */
    const val SEARCH = 5f

    /** A stroke in pixels of one canvas: its points, simplified, and its radii. */
    class Shape(val xs: FloatArray, val ys: FloatArray, val radius: Float) {
        val inner: Float get() = radius
        val outer: Float get() = max(radius * FEATHER, radius + 1.5f)
        val band: Float get() = max(1.5f, radius * BAND)
        val reach: Float get() = outer + band
        val minX: Float get() = xs.min(); val maxX: Float get() = xs.max()
        val minY: Float get() = ys.min(); val maxY: Float get() = ys.max()

        fun scaled(k: Float) = Shape(FloatArray(xs.size) { xs[it] * k }, FloatArray(ys.size) { ys[it] * k }, radius * k)
    }

    /**
     * The stroke [points] (canvas fractions) of brush [width] (a fraction of the shorter edge, as
     * a diameter, like every other brush) on a [w] by [h] canvas. Points closer together than a
     * quarter radius add nothing to the shape and are dropped, which keeps a long stroke cheap.
     */
    fun shape(points: List<Pt>, width: Float, w: Int, h: Int): Shape {
        val radius = max(1f, Geometry2D.px(width, Geometry2D.Size(w, h)) / 2f)
        val step = radius * 0.25f
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for ((i, p) in points.withIndex()) {
            val x = p.x * w; val y = p.y * h
            val last = i == points.lastIndex
            if (xs.isEmpty() || last || hypot(x - xs.last(), y - ys.last()) >= step) {
                if (last && xs.size > 1 && hypot(x - xs.last(), y - ys.last()) < step * 0.5f) { xs[xs.lastIndex] = x; ys[ys.lastIndex] = y } else { xs += x; ys += y }
            }
        }
        return Shape(xs.toFloatArray(), ys.toFloatArray(), radius)
    }

    private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

    /**
     * Distance from each pixel centre of the window [x0], [y0], [ww] by [wh] to the stroke, up to
     * [limit]; farther pixels hold [Float.MAX_VALUE].
     */
    fun distances(s: Shape, x0: Int, y0: Int, ww: Int, wh: Int, limit: Float): FloatArray {
        val d2 = FloatArray(ww * wh) { Float.MAX_VALUE }
        val lim2 = limit * limit
        val n = s.xs.size
        for (i in 0 until max(1, n - 1)) {
            val ax = s.xs[i]; val ay = s.ys[i]
            val bx = s.xs[min(i + 1, n - 1)]; val by = s.ys[min(i + 1, n - 1)]
            val vx = bx - ax; val vy = by - ay
            val len2 = vx * vx + vy * vy
            val fx = max(x0, floor(min(ax, bx) - limit).toInt()); val tx = min(x0 + ww - 1, ceil(max(ax, bx) + limit).toInt())
            val fy = max(y0, floor(min(ay, by) - limit).toInt()); val ty = min(y0 + wh - 1, ceil(max(ay, by) + limit).toInt())
            for (y in fy..ty) {
                val py = y + 0.5f
                val row = (y - y0) * ww - x0
                for (x in fx..tx) {
                    val px = x + 0.5f
                    var t = if (len2 > 0f) ((px - ax) * vx + (py - ay) * vy) / len2 else 0f
                    t = t.coerceIn(0f, 1f)
                    val dx = px - (ax + t * vx); val dy = py - (ay + t * vy)
                    val dd = dx * dx + dy * dy
                    if (dd <= lim2 && dd < d2[row + x]) d2[row + x] = dd
                }
            }
        }
        for (i in d2.indices) if (d2[i] != Float.MAX_VALUE) d2[i] = sqrt(d2[i])
        return d2
    }

    // ------------------------------------------------------------------ choosing the source

    /**
     * Where the patch for a heal stroke should come from, as the canvas fraction the stroke's
     * first point samples, the same convention as a clone's source. Null when the picture has no
     * room for a patch that does not overlap the stroke.
     *
     * The search runs on a copy shrunk so the stroke's reach is about eight cells, then the best
     * offset is refined pixel by pixel at full size, so a fine texture still lines up.
     */
    fun findSource(pixels: IntArray, w: Int, h: Int, points: List<Pt>, width: Float, check: () -> Unit = {}): Pt? {
        if (points.isEmpty() || w < 4 || h < 4) return null
        val s = shape(points, width, w, h)
        val f = max(1, floor(s.reach / 8f).toInt())
        val sw = (w + f - 1) / f; val sh = (h + f - 1) / f
        val small = shrink(pixels, w, h, f, sw, sh)
        val cs = s.scaled(1f / f)
        val coarseDiff = { a: Int, b: Int ->
            val dr = small[a * 3] - small[b * 3]; val dg = small[a * 3 + 1] - small[b * 3 + 1]; val db = small[a * 3 + 2] - small[b * 3 + 2]
            dr * dr + dg * dg + db * db
        }
        val coarse = search(coarseDiff, sw, sh, cs, null, (ceil(SEARCH * cs.reach)).toInt(), check) ?: return null
        check()
        // Refine around the coarse winner, one pixel at a time, so a fine texture lines up.
        val fineDiff = { a: Int, b: Int ->
            val ca = pixels[a]; val cb = pixels[b]
            val dr = (Argb.red(ca) - Argb.red(cb)).toFloat(); val dg = (Argb.green(ca) - Argb.green(cb)).toFloat(); val db = (Argb.blue(ca) - Argb.blue(cb)).toFloat()
            dr * dr + dg * dg + db * db
        }
        val fine = search(fineDiff, w, h, s, intArrayOf(coarse[0] * f, coarse[1] * f), f, check) ?: intArrayOf(coarse[0] * f, coarse[1] * f)
        val first = points.first()
        return Pt(first.x + fine[0].toFloat() / w, first.y + fine[1].toFloat() / h)
    }

    /** Box-averaged RGB at 1/[f] scale, three floats per cell. */
    private fun shrink(p: IntArray, w: Int, h: Int, f: Int, sw: Int, sh: Int): FloatArray {
        val out = FloatArray(sw * sh * 3)
        val count = IntArray(sw * sh)
        for (y in 0 until h) {
            val cy = (y / f) * sw
            for (x in 0 until w) {
                val c = p[y * w + x]; val i = cy + x / f
                out[i * 3] += Argb.red(c).toFloat(); out[i * 3 + 1] += Argb.green(c).toFloat(); out[i * 3 + 2] += Argb.blue(c).toFloat()
                count[i]++
            }
        }
        for (i in count.indices) { val n = max(1, count[i]).toFloat(); out[i * 3] /= n; out[i * 3 + 1] /= n; out[i * 3 + 2] /= n }
        return out
    }

    /**
     * The best offset (dx, dy) for [s] on a [w] by [h] image, within [range] of [around] (or of
     * zero when null). [diff] is the squared RGB distance between two pixel indices. The patch's region may not overlap the
     * blemish's region and must lie inside the picture. Score: mean squared difference over the
     * border band, plus a light pull towards nearby patches.
     */
    private fun search(diff: (Int, Int) -> Float, w: Int, h: Int, s: Shape, around: IntArray?, range: Int, check: () -> Unit): IntArray? {
        val reach = s.reach
        val bx0 = floor(s.minX - reach).toInt(); val by0 = floor(s.minY - reach).toInt()
        val bx1 = ceil(s.maxX + reach).toInt(); val by1 = ceil(s.maxY + reach).toInt()
        val ww = bx1 - bx0 + 1; val wh = by1 - by0 + 1
        val dist = distances(s, bx0, by0, ww, wh, reach)
        val region = BooleanArray(ww * wh)
        val bandIdx = ArrayList<Int>(); val coreIdx = ArrayList<Int>()
        for (i in dist.indices) {
            val d = dist[i]
            if (d <= s.outer) { region[i] = true; coreIdx += i }
            else if (d > s.outer && d <= reach) {
                val x = bx0 + i % ww; val y = by0 + i / ww
                if (x in 0 until w && y in 0 until h) bandIdx += i
            }
        }
        if (bandIdx.isEmpty() || coreIdx.isEmpty()) return null
        val band = thin(bandIdx, 6000); val core = thin(coreIdx, 3000)
        val cx = around?.get(0) ?: 0; val cy = around?.get(1) ?: 0
        var best: IntArray? = null
        var bestScore = Double.MAX_VALUE
        val pull = 1.0 / (reach * reach)
        for (p in cy - range..cy + range) {
            check()
            for (o in cx - range..cx + range) {
                // The whole reach of the patch must be inside the picture.
                if (bx0 + o < 0 || by0 + p < 0 || bx1 + o >= w || by1 + p >= h) continue
                if (overlaps(core, region, ww, wh, o, p)) continue
                val penalty = (o.toDouble() * o + p.toDouble() * p) * pull
                if (penalty >= bestScore) continue
                val limit = (bestScore - penalty) * band.size * 3
                var sum = 0.0
                for (i in band) {
                    val x = bx0 + i % ww; val y = by0 + i / ww
                    sum += diff(y * w + x, (y + p) * w + x + o).toDouble()
                    if (sum >= limit) break
                }
                val score = sum / (band.size * 3) + penalty
                if (score < bestScore) { bestScore = score; best = intArrayOf(o, p) }
            }
        }
        return best
    }

    /** Whether any cell of the patch's region, moved by ([o], [p]), lands in the blemish's region. */
    private fun overlaps(core: IntArray, region: BooleanArray, ww: Int, wh: Int, o: Int, p: Int): Boolean {
        if (o >= ww || -o >= ww || p >= wh || -p >= wh) return false
        for (i in core) {
            val x = i % ww + o; val y = i / ww + p
            if (x in 0 until ww && y in 0 until wh && region[y * ww + x]) return true
        }
        return false
    }

    /** At most [max] of [idx], evenly spaced, so a long stroke costs the same as a short one. */
    private fun thin(idx: List<Int>, max: Int): IntArray {
        if (idx.size <= max) return idx.toIntArray()
        val step = idx.size.toDouble() / max
        return IntArray(max) { idx[(it * step).toInt()] }
    }

    // ------------------------------------------------------------------ healing

    /**
     * The part of a [w] by [h] canvas a heal mark reads and writes: the stroke's reach and the
     * patch's reach together, clamped, as x0, y0, x1, y1 (exclusive). Null when it is empty.
     */
    fun window(points: List<Pt>, width: Float, source: Pt, w: Int, h: Int): IntArray? {
        if (points.isEmpty()) return null
        val s = shape(points, width, w, h)
        val (dx, dy) = offset(points, source, w, h)
        val r = s.reach + 1
        val x0 = floor(min(s.minX, s.minX + dx) - r).toInt().coerceIn(0, w); val y0 = floor(min(s.minY, s.minY + dy) - r).toInt().coerceIn(0, h)
        val x1 = ceil(max(s.maxX, s.maxX + dx) + r).toInt().coerceIn(0, w); val y1 = ceil(max(s.maxY, s.maxY + dy) + r).toInt().coerceIn(0, h)
        return if (x1 > x0 && y1 > y0) intArrayOf(x0, y0, x1, y1) else null
    }

    /** Whole pixels from the stroke to its patch on a [w] by [h] canvas. */
    fun offset(points: List<Pt>, source: Pt, w: Int, h: Int): Pair<Int, Int> =
        ((source.x - points.first().x) * w).roundToInt() to ((source.y - points.first().y) * h).roundToInt()

    /**
     * Heals [px], the window ([ox], [oy], [pw] by [ph]) of a [w] by [h] canvas, in place: the
     * stroke's region takes the patch at [source], corrected to its surroundings, feathered, at
     * [opacity]. Patch pixels outside the window are read from its nearest edge.
     */
    fun apply(px: IntArray, pw: Int, ph: Int, ox: Int, oy: Int, w: Int, h: Int, points: List<Pt>, width: Float, source: Pt, opacity: Float = 1f, check: () -> Unit = {}) {
        if (points.isEmpty() || pw <= 0 || ph <= 0) return
        val s = shape(points, width, w, h)
        val (dx, dy) = offset(points, source, w, h)
        val reach = s.reach
        // The target's reach, inside the window.
        val tx0 = max(ox, floor(s.minX - reach).toInt()); val ty0 = max(oy, floor(s.minY - reach).toInt())
        val tx1 = min(ox + pw - 1, ceil(s.maxX + reach).toInt()); val ty1 = min(oy + ph - 1, ceil(s.maxY + reach).toInt())
        if (tx1 < tx0 || ty1 < ty0) return
        val tw = tx1 - tx0 + 1; val th = ty1 - ty0 + 1
        val dist = distances(s, tx0, ty0, tw, th, reach)
        val snap = px.copyOf()
        fun at(x: Int, y: Int): Int = snap[(y - oy).coerceIn(0, ph - 1) * pw + (x - ox).coerceIn(0, pw - 1)]
        check()

        // The low-frequency correction, on a grid of about a tenth of the feathered radius.
        val g = max(1, floor(s.outer / 10f).toInt())
        val gw = tw / g + 2; val gh = th / g + 2
        val num = Array(3) { FloatArray(gw * gh) }
        val den = FloatArray(gw * gh)
        var mr = 0.0; var mg = 0.0; var mb = 0.0; var mn = 0
        for (y in 0 until th) for (x in 0 until tw) {
            val d = dist[y * tw + x]
            if (d <= s.outer || d > reach) continue
            val t = at(tx0 + x, ty0 + y); val p = at(tx0 + x + dx, ty0 + y + dy)
            val r = (Argb.red(t) - Argb.red(p)).toFloat(); val gg = (Argb.green(t) - Argb.green(p)).toFloat(); val b = (Argb.blue(t) - Argb.blue(p)).toFloat()
            val c = (y / g) * gw + x / g
            num[0][c] += r; num[1][c] += gg; num[2][c] += b; den[c] += 1f
            mr += r; mg += gg; mb += b; mn++
        }
        if (mn == 0) return
        val mean = floatArrayOf((mr / mn).toFloat(), (mg / mn).toFloat(), (mb / mn).toFloat())
        val radius = max(1, (s.outer / g * 0.6f).roundToInt())
        val field = Array(3) { FloatArray(gw * gh) }
        val weight = blur(den, gw, gh, radius)
        for (ch in 0..2) {
            val blurred = blur(num[ch], gw, gh, radius)
            for (i in blurred.indices) field[ch][i] = if (weight[i] > 1e-4f) blurred[i] / weight[i] else mean[ch]
        }
        check()

        val feather = s.outer - s.inner
        val alphaScale = opacity.coerceIn(0f, 1f)
        for (y in 0 until th) {
            // Grid cells are sampled at their centres, bilinearly.
            val gy = ((y + 0.5f) / g - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val gy0 = gy.toInt().coerceAtMost(gh - 2).coerceAtLeast(0); val fy = (gy - gy0).coerceIn(0f, 1f)
            for (x in 0 until tw) {
                val d = dist[y * tw + x]
                if (d >= s.outer) continue
                var a = if (d <= s.inner) 1f else { val u = (s.outer - d) / feather; u * u * (3f - 2f * u) }
                a *= alphaScale
                if (a <= 0f) continue
                val gx = ((x + 0.5f) / g - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val gx0 = gx.toInt().coerceAtMost(gw - 2).coerceAtLeast(0); val fx = (gx - gx0).coerceIn(0f, 1f)
                val i00 = gy0 * gw + gx0; val i10 = i00 + 1; val i01 = i00 + gw; val i11 = i01 + 1
                val wx = tx0 + x; val wy = ty0 + y
                val t = at(wx, wy); val p = at(wx + dx, wy + dy)
                val out = IntArray(3)
                for (ch in 0..2) {
                    val fl = field[ch]
                    val corr = (fl[i00] * (1 - fx) + fl[i10] * fx) * (1 - fy) + (fl[i01] * (1 - fx) + fl[i11] * fx) * fy
                    val tv = channel(t, ch).toFloat(); val pv = channel(p, ch) + corr
                    out[ch] = (tv + (pv - tv) * a).roundToInt().coerceIn(0, 255)
                }
                px[(wy - oy) * pw + (wx - ox)] = Argb.pack(Argb.alpha(t), out[0], out[1], out[2])
            }
        }
    }

    private fun channel(c: Int, ch: Int) = when (ch) { 0 -> Argb.red(c); 1 -> Argb.green(c); else -> Argb.blue(c) }

    /** Three passes of a separable box of [r] cells, edges clamped. */
    private fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        var a = src.copyOf(); var b = FloatArray(a.size)
        val span = (2 * r + 1).toFloat()
        repeat(3) {
            for (y in 0 until h) {
                val row = y * w
                var sum = 0f
                for (k in -r..r) sum += a[row + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    b[row + x] = sum / span
                    sum += a[row + (x + r + 1).coerceAtMost(w - 1)] - a[row + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until w) {
                var sum = 0f
                for (k in -r..r) sum += b[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    a[y * w + x] = sum / span
                    sum += b[(y + r + 1).coerceAtMost(h - 1) * w + x] - b[(y - r).coerceAtLeast(0) * w + x]
                }
            }
        }
        return a
    }
}
