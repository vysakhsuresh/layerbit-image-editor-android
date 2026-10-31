package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds a QR code in a picture and reads it: what "Scan look" runs on a screenshot or a photo.
 *
 * Built for codes seen straight on - a screenshot of the share screen, a saved code, a phone
 * photo of another phone held square to it - turned any way, at any size the modules are at
 * least about two pixels. It binarises (one global threshold, then a local one), finds the
 * three finder patterns by their 1:1:3:1:1 runs, maps the grid from them (and from the
 * bottom-right alignment pattern when there is one, which takes out mild perspective), samples
 * each module, and hands the grid to [Qr.decode]. A crumpled print or a steep angle is out of
 * scope; the share screen says to hold the code flat and straight.
 */
object QrScan {

    /** The payload of the first readable code in [argb], or null. */
    fun read(argb: IntArray, width: Int, height: Int, check: () -> Unit = {}): ByteArray? {
        require(argb.size == width * height)
        val luma = IntArray(width * height) { i -> val c = argb[i]; ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8 }
        check()
        for (binary in listOf(global(luma), local(luma, width, height))) {
            check()
            read(binary, width, height)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ binarise

    /** Otsu's threshold over the whole picture: right for screenshots and clean prints. */
    private fun global(luma: IntArray): BooleanArray {
        val hist = IntArray(256)
        for (v in luma) hist[v]++
        val total = luma.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var threshold = 128
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return BooleanArray(luma.size) { luma[it] <= threshold }
    }

    /** Darker than the mean around it: for photos with uneven light. */
    private fun local(luma: IntArray, w: Int, h: Int): BooleanArray {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) { row += luma[y * w + x]; integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row }
        }
        val r = max(8, min(w, h) / 12)
        return BooleanArray(w * h) { i ->
            val x = i % w; val y = i / w
            val x0 = max(0, x - r); val y0 = max(0, y - r); val x1 = min(w, x + r + 1); val y1 = min(h, y + r + 1)
            val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
            val mean = s.toDouble() / ((x1 - x0) * (y1 - y0))
            luma[i] < mean - 6
        }
    }

    // ------------------------------------------------------------------ finder patterns

    private class Finder(var x: Double, var y: Double, var module: Double, var count: Int)

    private fun ratioOk(r: IntArray): Boolean {
        val total = r.sum()
        if (total < 7) return false
        val m = total / 7.0
        val tol = m * 0.7
        return abs(r[0] - m) < tol && abs(r[1] - m) < tol && abs(r[2] - 3 * m) < 3 * tol && abs(r[3] - m) < tol && abs(r[4] - m) < tol
    }

    /** Runs through ([cx], [cy]) along ([dx], [dy]); the centre offset along the line and the module size, or null. */
    private fun crossCheck(b: BooleanArray, w: Int, h: Int, cx: Int, cy: Int, dx: Int, dy: Int, maxRun: Int): Pair<Double, Double>? {
        fun dark(k: Int): Boolean? { val x = cx + dx * k; val y = cy + dy * k; return if (x in 0 until w && y in 0 until h) b[y * w + x] else null }
        if (dark(0) != true) return null
        val r = IntArray(5)
        var k = 0
        while (dark(k) == true) { r[2]++; k-- }
        while (dark(k) == false && r[1] <= maxRun) { r[1]++; k-- }
        while (dark(k) == true && r[0] <= maxRun) { r[0]++; k-- }
        k = 1
        while (dark(k) == true) { r[2]++; k++ }
        while (dark(k) == false && r[3] <= maxRun) { r[3]++; k++ }
        while (dark(k) == true && r[4] <= maxRun) { r[4]++; k++ }
        if (!ratioOk(r)) return null
        // Centre of the middle run, measured from (cx, cy).
        val end = k - r[4] - r[3]
        val centre = end - r[2] / 2.0 - 0.5
        return centre to r.sum() / 7.0
    }

    private fun finders(b: BooleanArray, w: Int, h: Int): List<Finder> {
        val found = ArrayList<Finder>()
        val step = max(1, min(w, h) / 400)
        var y = 0
        while (y < h) {
            val r = IntArray(5); var state = 0
            var x = 0
            fun consider(endX: Int) {
                if (!ratioOk(r)) return
                val cx = (endX - r[4] - r[3] - r[2] / 2.0 - 0.5).roundToInt().coerceIn(0, w - 1)
                val maxRun = r.sum()
                val v = crossCheck(b, w, h, cx, y, 0, 1, maxRun) ?: return
                val fy = y + v.first
                val cy = fy.roundToInt().coerceIn(0, h - 1)
                val hz = crossCheck(b, w, h, cx, cy, 1, 0, maxRun) ?: return
                val fx = cx + hz.first
                val module = (v.second + hz.second) / 2
                val near = found.firstOrNull { abs(it.x - fx) <= module * 2 && abs(it.y - fy) <= module * 2 && abs(it.module - module) <= module * 0.5 }
                if (near != null) {
                    near.x = (near.x * near.count + fx) / (near.count + 1); near.y = (near.y * near.count + fy) / (near.count + 1)
                    near.module = (near.module * near.count + module) / (near.count + 1); near.count++
                } else found.add(Finder(fx, fy, module, 1))
            }
            while (x < w) {
                val d = b[y * w + x]
                when {
                    state % 2 == 0 && d -> r[state]++
                    state % 2 == 1 && !d -> r[state]++
                    state == 4 -> { consider(x); r[0] = r[2]; r[1] = r[3]; r[2] = r[4]; r[3] = 1; r[4] = 0; state = 3 }
                    state == 0 && !d && r[0] == 0 -> Unit // light before any dark
                    else -> { state++; r[state]++ }
                }
                x++
            }
            if (state == 4) consider(w)
            y += step
        }
        return found
    }

    // ------------------------------------------------------------------ grid

    private fun read(b: BooleanArray, w: Int, h: Int): ByteArray? {
        val all = finders(b, w, h).filter { it.count >= 2 }.sortedByDescending { it.count }.take(8)
        if (all.size < 3) return null
        // Try the best-supported triples first.
        for (i in all.indices) for (j in i + 1 until all.size) for (k in j + 1 until all.size) {
            val triple = listOf(all[i], all[j], all[k])
            val ms = triple.map { it.module }
            if (ms.max() > ms.min() * 1.6) continue
            readTriple(b, w, h, triple)?.let { return it }
        }
        return null
    }

    private fun readTriple(b: BooleanArray, w: Int, h: Int, t: List<Finder>): ByteArray? {
        // The corner finder is the one opposite the longest side.
        val d01 = dist(t[0], t[1]); val d12 = dist(t[1], t[2]); val d02 = dist(t[0], t[2])
        val (corner, p, q) = when {
            d12 >= d01 && d12 >= d02 -> Triple(t[0], t[1], t[2])
            d02 >= d01 && d02 >= d12 -> Triple(t[1], t[0], t[2])
            else -> Triple(t[2], t[0], t[1])
        }
        // With y down, top-right then bottom-left turn positively about the top-left.
        val cross = (p.x - corner.x) * (q.y - corner.y) - (p.y - corner.y) * (q.x - corner.x)
        val (tr, bl) = if (cross > 0) p to q else q to p
        val module = (corner.module + tr.module + bl.module) / 3
        val across = (dist(corner, tr) + dist(corner, bl)) / 2 / module
        var estimate = across.roundToInt() + 7
        when (estimate and 3) { 0 -> estimate++; 2 -> estimate--; 3 -> estimate -= 2 }
        for (size in listOf(estimate, estimate + 4, estimate - 4)) {
            if (size < 21 || (size - 17) / 4 > Qr.MAX_VERSION) continue
            val affine = Grid.affine(corner.x, corner.y, tr.x, tr.y, bl.x, bl.y, size)
            val grids = ArrayList<Grid>()
            if (size >= 25) alignment(b, w, h, affine, size, module)?.let { grids.add(it) }
            grids.add(affine)
            for (g in grids) {
                val m = sample(b, w, h, g, size, module)
                Qr.decode(m)?.let { return it }
                // A mirrored picture (a selfie camera) reads transposed.
                Qr.decode(transpose(m))?.let { return it }
            }
        }
        return null
    }

    private fun dist(a: Finder, b: Finder) = hypot(a.x - b.x, a.y - b.y)

    /** Module coordinates to picture coordinates: an affine map, or a perspective one through four points. */
    private class Grid(private val h: DoubleArray) {
        fun map(u: Double, v: Double): Pair<Double, Double> {
            val d = h[6] * u + h[7] * v + 1.0
            return ((h[0] * u + h[1] * v + h[2]) / d) to ((h[3] * u + h[4] * v + h[5]) / d)
        }

        companion object {
            /** Finder centres sit at module 3.5 from their corners. */
            fun affine(tlx: Double, tly: Double, trx: Double, try_: Double, blx: Double, bly: Double, size: Int): Grid {
                val span = size - 7.0
                val ax = (trx - tlx) / span; val ay = (try_ - tly) / span
                val bx = (blx - tlx) / span; val by = (bly - tly) / span
                return Grid(doubleArrayOf(ax, bx, tlx - 3.5 * (ax + bx), ay, by, tly - 3.5 * (ay + by), 0.0, 0.0))
            }

            /** The homography taking four module points to four picture points, or null when they are degenerate. */
            fun through(src: List<Pair<Double, Double>>, dst: List<Pair<Double, Double>>): Grid? {
                val a = Array(8) { DoubleArray(9) }
                for (i in 0 until 4) {
                    val (u, v) = src[i]; val (x, y) = dst[i]
                    a[2 * i] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
                    a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
                }
                for (c in 0 until 8) {
                    val p = (c until 8).maxBy { abs(a[it][c]) }
                    if (abs(a[p][c]) < 1e-9) return null
                    val tmp = a[c]; a[c] = a[p]; a[p] = tmp
                    for (r in 0 until 8) if (r != c) {
                        val f = a[r][c] / a[c][c]
                        for (k in c..8) a[r][k] -= f * a[c][k]
                    }
                }
                return Grid(DoubleArray(8) { a[it][8] / a[it][it] })
            }
        }
    }

    /**
     * Refines the affine grid with the bottom-right alignment pattern: searched near where the
     * affine map puts it, scored against its 5x5 template, then used as the fourth point.
     */
    private fun alignment(b: BooleanArray, w: Int, h: Int, affine: Grid, size: Int, module: Double): Grid? {
        val au = size - 6.5; val av = size - 6.5
        val (px, py) = affine.map(au, av)
        val reach = (module * 4).toInt()
        var best = -1; var bx = 0.0; var by = 0.0
        val stepPx = max(1, (module / 3).toInt())
        // Template offsets in module units, in the affine frame's directions.
        val (ox, oy) = affine.map(0.0, 0.0)
        val (ux, uy) = affine.map(1.0, 0.0).let { (x, y) -> (x - ox) to (y - oy) }
        val (vx, vy) = affine.map(0.0, 1.0).let { (x, y) -> (x - ox) to (y - oy) }
        var dy = -reach
        while (dy <= reach) {
            var dx = -reach
            while (dx <= reach) {
                val cx = px + dx; val cy = py + dy
                var score = 0
                for (j in -2..2) for (i in -2..2) {
                    val x = (cx + i * ux + j * vx).roundToInt(); val y = (cy + i * uy + j * vy).roundToInt()
                    if (x !in 0 until w || y !in 0 until h) continue
                    val want = max(abs(i), abs(j)) != 1
                    if (b[y * w + x] == want) score++
                }
                if (score > best) { best = score; bx = cx; by = cy }
                dx += stepPx
            }
            dy += stepPx
        }
        if (best < 23) return null
        val src = listOf(3.5 to 3.5, size - 3.5 to 3.5, 3.5 to size - 3.5, au to av)
        val dst = listOf(affine.map(3.5, 3.5), affine.map(size - 3.5, 3.5), affine.map(3.5, size - 3.5), bx to by)
        return Grid.through(src, dst)
    }

    /** Each module by a vote of five points around its centre. */
    private fun sample(b: BooleanArray, w: Int, h: Int, g: Grid, size: Int, module: Double): Qr.Matrix {
        val off = 0.22
        val dark = BooleanArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var votes = 0; var n = 0
            for ((du, dv) in POINTS) {
                val (px, py) = g.map(x + 0.5 + du * off, y + 0.5 + dv * off)
                val ix = px.roundToInt(); val iy = py.roundToInt()
                if (ix in 0 until w && iy in 0 until h) { n++; if (b[iy * w + ix]) votes++ }
            }
            dark[y * size + x] = n > 0 && votes * 2 > n
        }
        return Qr.Matrix(size, dark)
    }

    private val POINTS = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)

    private fun transpose(m: Qr.Matrix): Qr.Matrix {
        val s = m.size
        return Qr.Matrix(s, BooleanArray(s * s) { i -> m.dark[(i % s) * s + i / s] })
    }
}
