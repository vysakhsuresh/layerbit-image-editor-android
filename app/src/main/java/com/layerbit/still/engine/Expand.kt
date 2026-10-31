package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What goes in the new canvas around the picture. */
enum class ExpandFill {
    /** Continues the picture: its colours carried outwards, its texture copied from near the edge. */
    SMART,
    WHITE,
    BLACK,
    /** The picture stretched to cover the canvas and blurred, behind the picture. */
    BLUR
}

/**
 * The canvas grown around the picture, each side as a fraction of the picture's own width or
 * height, so 0.25 on the left adds a quarter of the picture's width there. 0 everywhere is off,
 * which is what a recipe without the field reads as.
 *
 * Applied at the end of the geometry stage - after crop and size, before tone - so every tone,
 * look, detail and frame setting lands on the fill exactly as it lands on the picture, and the
 * seam does not show once a look is applied. Layers are placed on the grown canvas.
 */
data class Expand(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val fill: ExpandFill = ExpandFill.SMART,
    /** Another seed is another equally good fill: the "try again" a user wants when one patch repeats. */
    val seed: Int = 0
) {
    val isIdentity: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
    val valid: Boolean get() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..MAX }

    /** How many times the pixel count grows. */
    val areaFactor: Double get() = (1.0 + left + right) * (1.0 + top + bottom)

    /** Where a [w] by [h] picture sits in the grown canvas, in pixels. */
    fun layout(w: Int, h: Int): Layout {
        val l = (left * w).roundToInt(); val r = (right * w).roundToInt()
        val t = (top * h).roundToInt(); val b = (bottom * h).roundToInt()
        return Layout(w + l + r, h + t + b, l, t, w, h)
    }

    /** The output size for a picture of [size]. */
    fun grow(size: Geometry2D.Size): Geometry2D.Size = if (isIdentity) size else layout(size.width, size.height).let { Geometry2D.Size(it.width, it.height) }

    /** The picture inside the grown canvas, as fractions of the canvas: what the editor draws handles on. */
    val inner: Crop
        get() {
            val w = 1f + left + right; val h = 1f + top + bottom
            return Crop(left / w, top / h, (left + 1f) / w, (top + 1f) / h)
        }

    /**
     * After a drag on the canvas: [handle] numbered as the crop handles are (0-3 corners from
     * top left, 4 top, 5 bottom, 6 left, 7 right, 8 inside), [dx] and [dy] in fractions of the
     * grown canvas. An edge pulled outward grows that side; a drag inside slides the picture
     * across the canvas without changing its size.
     */
    fun dragged(handle: Int, dx: Float, dy: Float): Expand {
        val cw = 1f + left + right; val ch = 1f + top + bottom
        val px = dx * cw; val py = dy * ch
        fun c(v: Float) = v.coerceIn(0f, MAX)
        return when (handle) {
            0 -> copy(left = c(left - px), top = c(top - py))
            1 -> copy(right = c(right + px), top = c(top - py))
            2 -> copy(left = c(left - px), bottom = c(bottom + py))
            3 -> copy(right = c(right + px), bottom = c(bottom + py))
            4 -> copy(top = c(top - py))
            5 -> copy(bottom = c(bottom + py))
            6 -> copy(left = c(left - px))
            7 -> copy(right = c(right + px))
            8 -> {
                val mx = px.coerceIn(-left, right); val my = py.coerceIn(-top, bottom)
                copy(left = left + mx, right = right - mx, top = top + my, bottom = bottom - my)
            }
            else -> this
        }
    }

    data class Layout(val width: Int, val height: Int, val x: Int, val y: Int, val innerWidth: Int, val innerHeight: Int) {
        fun inside(px: Int, py: Int) = px >= x && py >= y && px < x + innerWidth && py < y + innerHeight
    }

    companion object {
        /** Up to one and a half of the picture on each side: four times as wide at most. */
        const val MAX = 1.5f

        /**
         * The smallest even growth that gives a [w] by [h] picture the [aspect] (width / height).
         * A target the picture already has, or one beyond [MAX], comes back as close as allowed.
         */
        fun toAspect(w: Int, h: Int, aspect: Float, fill: ExpandFill = ExpandFill.SMART): Expand {
            require(w > 0 && h > 0 && aspect > 0f)
            val current = w.toFloat() / h
            return if (aspect > current) {
                val side = ((h * aspect - w) / w / 2f).coerceIn(0f, MAX)
                Expand(left = side, right = side, fill = fill)
            } else {
                val side = ((w / aspect - h) / h / 2f).coerceIn(0f, MAX)
                Expand(top = side, bottom = side, fill = fill)
            }
        }

        /** The same fraction on every side. */
        fun all(fraction: Float, fill: ExpandFill = ExpandFill.SMART): Expand {
            val f = fraction.coerceIn(0f, MAX)
            return Expand(f, f, f, f, fill)
        }
    }
}

/**
 * The fill itself, on plain ARGB arrays.
 *
 * Smart fill splits the picture into two layers. The broad colour - anything wider than about
 * two percent of the picture - is carried outwards from the edge and softened the further it
 * goes, so a sky keeps its gradient along the edge and fades into its own average rather than
 * into a mirror image. The fine texture - grass blades, plaster, cloud edges, noise - is copied
 * from patches inside the picture, chosen on a grid the way image quilting chooses them: each
 * patch must agree with the picture where it overlaps the edge and with the patches already
 * placed next to it, and must come from a part of the picture of the same broad colour, so sky
 * texture comes from sky and grass from grass. A second pass revisits each choice with all its
 * neighbours known (the propagate-and-search step of PatchMatch). Neighbouring patches are
 * cross-faded with a smoothstep, so there are no seams to hide.
 *
 * Every choice is made once, on a small copy of the picture whose size does not depend on the
 * picture's resolution, and stored in a [Plan] in fractions of that copy. The preview and the
 * export apply the same plan to their own pixels, which is what makes the preview predict the
 * export, and the same seed always gives the same fill.
 */
object Expansion {

    /** The small copy's shorter edge. */
    const val ANALYSIS_EDGE = 192
    private const val STEP = 6
    private const val RANDOM_CANDIDATES = 22

    /** Smart fill's decisions, in analysis pixels: a smooth colour field for the canvas and a source offset per grid node. */
    class Plan(
        val aw: Int, val ah: Int,
        val ax: Int, val ay: Int, val aiw: Int, val aih: Int,
        val smooth: FloatArray,
        val nodesX: Int, val nodesY: Int,
        val offX: IntArray, val offY: IntArray,
        val decided: BooleanArray
    )

    /**
     * The grown canvas for a [w] by [h] picture. [plan] is reused when given (the editor keeps
     * the preview's for the export); otherwise it is made from this picture.
     * @return the canvas pixels; its size is [Expand.layout].
     */
    fun apply(picture: IntArray, w: Int, h: Int, e: Expand, plan: Plan? = null, check: () -> Unit = {}): IntArray {
        require(picture.size == w * h && e.valid)
        val lay = e.layout(w, h)
        val out = IntArray(lay.width * lay.height)
        when (e.fill) {
            ExpandFill.WHITE -> out.fill(0xFFFFFFFF.toInt())
            ExpandFill.BLACK -> out.fill(0xFF000000.toInt())
            ExpandFill.BLUR -> blurFill(picture, w, h, lay, out, check)
            ExpandFill.SMART -> {
                // A soft backdrop the smart fill can settle into far from a busy picture.
                val backdrop = IntArray(out.size)
                blurFill(picture, w, h, lay, backdrop, check)
                smartFill(picture, w, h, lay, out, plan ?: plan(picture, w, h, e, check), check, backdrop)
            }
        }
        for (y in 0 until h) System.arraycopy(picture, y * w, out, (y + lay.y) * lay.width + lay.x, w)
        return out
    }

    // ------------------------------------------------------------------ analysis

    /** The analysis size of a [w] by [h] picture: shorter edge [ANALYSIS_EDGE], never larger than the picture. */
    fun analysisSize(w: Int, h: Int): Pair<Int, Int> {
        val s = min(1.0, ANALYSIS_EDGE.toDouble() / min(w, h))
        return max(8, (w * s).roundToInt()).coerceAtMost(w) to max(8, (h * s).roundToInt()).coerceAtMost(h)
    }

    /** Area-averaged shrink to [tw] by [th], three float channels per pixel. */
    fun shrink(px: IntArray, w: Int, h: Int, tw: Int, th: Int): FloatArray {
        val out = FloatArray(tw * th * 3)
        for (ty in 0 until th) {
            val y0 = ty.toDouble() * h / th; val y1 = (ty + 1).toDouble() * h / th
            for (tx in 0 until tw) {
                val x0 = tx.toDouble() * w / tw; val x1 = (tx + 1).toDouble() * w / tw
                var r = 0.0; var g = 0.0; var b = 0.0; var wsum = 0.0
                var y = y0.toInt()
                while (y < y1 && y < h) {
                    val wy = min(y + 1.0, y1) - max(y.toDouble(), y0)
                    var x = x0.toInt()
                    while (x < x1 && x < w) {
                        val wx = min(x + 1.0, x1) - max(x.toDouble(), x0)
                        val c = px[y * w + x]; val k = wx * wy
                        r += (c shr 16 and 0xFF) * k; g += (c shr 8 and 0xFF) * k; b += (c and 0xFF) * k; wsum += k
                        x++
                    }
                    y++
                }
                val i = (ty * tw + tx) * 3
                out[i] = (r / wsum).toFloat(); out[i + 1] = (g / wsum).toFloat(); out[i + 2] = (b / wsum).toFloat()
            }
        }
        return out
    }

    /**
     * Three passes of a box blur of [radius]: close to a Gaussian, linear in the pixel count.
     * Beyond the edge the picture is continued by an odd reflection, which keeps a straight
     * gradient straight, so a sky is not pulled towards its middle at the top of the canvas.
     */
    fun blur(src: FloatArray, w: Int, h: Int, radius: Int, odd: Boolean = true): FloatArray {
        if (radius <= 0) return src.copyOf()
        val a = src.copyOf(); val b = FloatArray(src.size)
        repeat(3) {
            boxPass(a, b, w, h, radius, horizontal = true, odd = odd)
            boxPass(b, a, w, h, radius, horizontal = false, odd = odd)
        }
        return a
    }

    private fun boxPass(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean, odd: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val norm = 1f / (2 * r + 1)
        for (line in 0 until lines) {
            for (c in 0 until 3) {
                fun raw(p: Int): Float = src[(if (horizontal) line * w + p else p * w + line) * 3 + c]
                fun at(k: Int): Float = when {
                    k < 0 -> if (odd) 2 * raw(0) - raw(min(-k, len - 1)) else raw(0)
                    k >= len -> if (odd) 2 * raw(len - 1) - raw(max(2 * (len - 1) - k, 0)) else raw(len - 1)
                    else -> raw(k)
                }
                var sum = 0f
                for (k in -r..r) sum += at(k)
                for (k in 0 until len) {
                    dst[(if (horizontal) line * w + k else k * w + line) * 3 + c] = sum * norm
                    sum += at(k + r + 1) - at(k - r)
                }
            }
        }
    }

    private fun bilinear(f: FloatArray, w: Int, h: Int, x: Double, y: Double, c: Int): Float {
        val cx = x.coerceIn(0.0, w - 1.0); val cy = y.coerceIn(0.0, h - 1.0)
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = (cx - x0).toFloat(); val fy = (cy - y0).toFloat()
        val a = f[(y0 * w + x0) * 3 + c]; val b = f[(y0 * w + x1) * 3 + c]
        val d = f[(y1 * w + x0) * 3 + c]; val e = f[(y1 * w + x1) * 3 + c]
        return (a + (b - a) * fx) + ((d + (e - d) * fx) - (a + (b - a) * fx)) * fy
    }

    private fun smoothstep(t: Double): Double = t * t * (3 - 2 * t)

    // ------------------------------------------------------------------ blur fill

    private fun blurFill(px: IntArray, w: Int, h: Int, lay: Expand.Layout, out: IntArray, check: () -> Unit) {
        val (aiw, aih) = analysisSize(w, h)
        val small = shrink(px, w, h, aiw, aih)
        val scale = aiw.toDouble() / w
        val aw = max(1, (lay.width * scale).roundToInt()); val ah = max(1, (lay.height * scale).roundToInt())
        // The picture stretched to cover the whole canvas, centred, then blurred.
        val cover = max(aw.toDouble() / aiw, ah.toDouble() / aih)
        val stretched = FloatArray(aw * ah * 3)
        for (y in 0 until ah) for (x in 0 until aw) for (c in 0 until 3) {
            val sx = (x + 0.5 - aw / 2.0) / cover + aiw / 2.0 - 0.5
            val sy = (y + 0.5 - ah / 2.0) / cover + aih / 2.0 - 0.5
            stretched[(y * aw + x) * 3 + c] = bilinear(small, aiw, aih, sx, sy, c)
        }
        check()
        val blurred = blur(stretched, aw, ah, max(2, (min(aw, ah) * 0.035).roundToInt()), odd = false)
        check()
        val sx = aw.toDouble() / lay.width; val sy = ah.toDouble() / lay.height
        for (y in 0 until lay.height) {
            if (y and 0x3F == 0) check()
            val fy = (y + 0.5) * sy - 0.5
            for (x in 0 until lay.width) {
                if (lay.inside(x, y)) continue
                val fx = (x + 0.5) * sx - 0.5
                out[y * lay.width + x] = pack(bilinear(blurred, aw, ah, fx, fy, 0), bilinear(blurred, aw, ah, fx, fy, 1), bilinear(blurred, aw, ah, fx, fy, 2))
            }
        }
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    // ------------------------------------------------------------------ smart fill: plan

    /** The scale below which a feature is texture (copied) rather than colour (carried): about two percent of the picture. */
    private fun lowRadius(aiw: Int, aih: Int) = max(1, (min(aiw, aih) * 0.012).roundToInt())

    fun plan(picture: IntArray, w: Int, h: Int, e: Expand, check: () -> Unit = {}): Plan {
        val (aiw, aih) = analysisSize(w, h)
        val small = shrink(picture, w, h, aiw, aih)
        check()
        val lay = e.layout(aiw, aih)
        val aw = lay.width; val ah = lay.height; val ax = lay.x; val ay = lay.y
        val low = blur(small, aiw, aih, lowRadius(aiw, aih))
        val detail = FloatArray(small.size) { small[it] - low[it] }
        check()
        // The broad colour carried out from the nearest edge, softened with distance.
        val carried = FloatArray(aw * ah * 3)
        for (y in 0 until ah) for (x in 0 until aw) {
            val sx = (x - ax).coerceIn(0, aiw - 1); val sy = (y - ay).coerceIn(0, aih - 1)
            for (c in 0 until 3) carried[(y * aw + x) * 3 + c] = low[(sy * aiw + sx) * 3 + c]
        }
        val short = min(aiw, aih)
        val near = blur(carried, aw, ah, max(1, short / 64))
        val far = blur(carried, aw, ah, max(2, short / 7))
        check()
        val smooth = FloatArray(aw * ah * 3)
        for (y in 0 until ah) for (x in 0 until aw) {
            val i = (y * aw + x) * 3
            val inX = x - ax in 0 until aiw; val inY = y - ay in 0 until aih
            if (inX && inY) { for (c in 0 until 3) smooth[i + c] = low[((y - ay) * aiw + (x - ax)) * 3 + c]; continue }
            val dx = if (inX) 0 else if (x < ax) ax - x else x - (ax + aiw - 1)
            val dy = if (inY) 0 else if (y < ay) ay - y else y - (ay + aih - 1)
            val t = smoothstep((sqrt((dx * dx + dy * dy).toDouble()) / (short * 0.35)).coerceIn(0.0, 1.0)).toFloat()
            for (c in 0 until 3) smooth[i + c] = near[i + c] + (far[i + c] - near[i + c]) * t
        }
        check()

        // The grid: node (i, j) sits at canvas analysis pixel (i * STEP, j * STEP) and reaches STEP either side.
        val nx = aw / STEP + 2; val ny = ah / STEP + 2
        val needed = BooleanArray(nx * ny)
        for (j in 0 until ny) for (i in 0 until nx) {
            val x0 = i * STEP - STEP; val x1 = i * STEP + STEP; val y0 = j * STEP - STEP; val y1 = j * STEP + STEP
            // Needed when its reach touches any canvas pixel outside the picture.
            val touchesCanvas = x1 >= 0 && y1 >= 0 && x0 < aw && y0 < ah
            val allInside = x0 >= ax && y0 >= ay && x1 < ax + aiw && y1 < ay + aih
            needed[j * nx + i] = touchesCanvas && !allInside
        }
        val order = (0 until nx * ny).filter { needed[it] }.sortedWith(compareBy({ outside(it % nx * STEP, it / nx * STEP, ax, ay, aiw, aih) }, { it }))
        val offX = IntArray(nx * ny); val offY = IntArray(nx * ny)
        val decided = BooleanArray(nx * ny)
        val ctx = Ctx(aiw, aih, ax, ay, aw, ah, nx, small, detail, low, smooth, offX, offY, decided)
        // Source patches stay a node's reach inside the picture; a picture smaller than that uses its middle.
        val loX = min(STEP, (aiw - 1) / 2); val hiX = max(loX, aiw - 1 - STEP)
        val loY = min(STEP, (aih - 1) / 2); val hiY = max(loY, aih - 1 - STEP)
        var rng = (e.seed.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor 0x5DEECE66DL
        fun next(bound: Int): Int { rng = rng xor (rng shl 13); rng = rng xor (rng ushr 7); rng = rng xor (rng shl 17); return ((rng ushr 1) % bound).toInt() }

        for ((n, node) in order.withIndex()) {
            if (n and 0x3F == 0) check()
            val cx = node % nx * STEP - ax; val cy = node / nx * STEP - ay // node centre in picture analysis coordinates
            val candidates = ArrayList<Long>()
            fun add(sx: Int, sy: Int) {
                val px = sx.coerceIn(loX, hiX); val py = sy.coerceIn(loY, hiY)
                candidates.add(((px - cx).toLong() shl 32) or ((py - cy).toLong() and 0xFFFFFFFFL))
            }
            // Mirror across the nearest edges, then the neighbours' choices carried on, then random draws from the band near this node.
            add(mirror(cx, aiw), mirror(cy, aih))
            for (d in NEIGHBOURS) {
                val m = node + d.second * nx + d.first
                if (node % nx + d.first !in 0 until nx || m !in 0 until nx * ny || !decided[m]) continue
                add(cx + offX[m], cy + offY[m])
            }
            val bandX = max(aiw / 3, STEP * 4); val bandY = max(aih / 3, STEP * 4)
            val nearX = cx.coerceIn(0, aiw - 1); val nearY = cy.coerceIn(0, aih - 1)
            repeat(RANDOM_CANDIDATES) { k ->
                if (k % 3 == 2) add(next(aiw), next(aih))
                else add(nearX + next(2 * bandX + 1) - bandX, nearY + next(2 * bandY + 1) - bandY)
            }
            choose(ctx, node, candidates)
        }
        check()
        // Second pass: every node again, now that all its neighbours are known; keep a choice unless another is better.
        for ((n, node) in order.withIndex()) {
            if (n and 0x3F == 0) check()
            val cx = node % nx * STEP - ax; val cy = node / nx * STEP - ay
            val candidates = ArrayList<Long>()
            fun add(sx: Int, sy: Int) {
                val px = sx.coerceIn(loX, hiX); val py = sy.coerceIn(loY, hiY)
                candidates.add(((px - cx).toLong() shl 32) or ((py - cy).toLong() and 0xFFFFFFFFL))
            }
            add(cx + offX[node], cy + offY[node])
            for (d in NEIGHBOURS) {
                val m = node + d.second * nx + d.first
                if (node % nx + d.first !in 0 until nx || m !in 0 until nx * ny || !decided[m]) continue
                add(cx + offX[m], cy + offY[m])
            }
            var radius = max(aiw, aih) / 4
            while (radius >= 2) {
                add(cx + offX[node] + next(2 * radius + 1) - radius, cy + offY[node] + next(2 * radius + 1) - radius)
                radius /= 2
            }
            choose(ctx, node, candidates)
        }
        return Plan(aw, ah, ax, ay, aiw, aih, smooth, nx, ny, offX, offY, decided)
    }

    private val NEIGHBOURS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to -1, -1 to 1, 1 to 1)

    /** Reflects a coordinate outside 0 until n back inside, as a mirror at the edge would. */
    private fun mirror(v: Int, n: Int): Int = when { v < 0 -> -v; v >= n -> 2 * (n - 1) - v; else -> v }

    /** Distance of a canvas analysis point outside the picture; negative inside, so edge nodes go first. */
    private fun outside(x: Int, y: Int, ax: Int, ay: Int, aiw: Int, aih: Int): Int {
        val dx = max(ax - x, x - (ax + aiw - 1)); val dy = max(ay - y, y - (ay + aih - 1))
        return max(dx, dy)
    }

    private class Ctx(
        val aiw: Int, val aih: Int, val ax: Int, val ay: Int, val aw: Int, val ah: Int, val nx: Int,
        val small: FloatArray, val detail: FloatArray, val low: FloatArray, val smooth: FloatArray,
        val offX: IntArray, val offY: IntArray, val decided: BooleanArray
    )

    /** Scores each candidate offset for [node] and keeps the cheapest. */
    private fun choose(c: Ctx, node: Int, candidates: List<Long>) {
        var best = Double.MAX_VALUE; var bx = 0; var by = 0
        val seen = HashSet<Long>()
        for (cand in candidates) {
            if (!seen.add(cand)) continue
            val ox = (cand shr 32).toInt(); val oy = cand.toInt()
            val cost = cost(c, node, ox, oy, best)
            if (cost < best) { best = cost; bx = ox; by = oy }
        }
        c.offX[node] = bx; c.offY[node] = by; c.decided[node] = true
    }

    private fun cost(c: Ctx, node: Int, ox: Int, oy: Int, bound: Double): Double {
        val gx = node % c.nx * STEP; val gy = node / c.nx * STEP
        var total = 0.0
        var dy = -STEP + 1
        while (dy < STEP) {
            val y = gy + dy
            var dx = -STEP + 1
            while (dx < STEP) {
                val x = gx + dx
                dx += 2
                if (x < 0 || y < 0 || x >= c.aw || y >= c.ah) continue
                val wt = (1.0 - abs(x - gx).toDouble() / STEP) * (1.0 - abs(y - gy).toDouble() / STEP)
                val px = x - c.ax; val py = y - c.ay
                val sx = px + ox; val sy = py + oy
                if (sx !in 0 until c.aiw || sy !in 0 until c.aih) { total += wt * 4e4; continue }
                val si = (sy * c.aiw + sx) * 3
                // Broad colour of the source against what the canvas carries here.
                val ci = (y * c.aw + x) * 3
                var colour = 0.0
                for (k in 0 until 3) { val d = c.low[si + k] - c.smooth[ci + k]; colour += d * d }
                total += wt * 0.35 * colour
                // Texture against the picture itself, where this node overlaps it.
                if (px in 0 until c.aiw && py in 0 until c.aih) {
                    val pi = (py * c.aiw + px) * 3
                    var d2 = 0.0
                    for (k in 0 until 3) { val d = c.detail[si + k] - c.detail[pi + k]; d2 += d * d }
                    total += wt * 2.0 * d2
                } else {
                    // Texture against what the decided neighbours put here.
                    val ni = (x + STEP / 2) / STEP; val nj = (y + STEP / 2) / STEP
                    for (j in nj - 1..nj) for (i in ni - 1..ni) {
                        if (i !in 0 until c.nx) continue
                        val m = j * c.nx + i
                        if (m == node || m !in c.decided.indices || !c.decided[m]) continue
                        val mx = px + c.offX[m]; val my = py + c.offY[m]
                        if (mx !in 0 until c.aiw || my !in 0 until c.aih) continue
                        val mw = (1.0 - abs(x - i * STEP).toDouble() / STEP).coerceAtLeast(0.0) * (1.0 - abs(y - j * STEP).toDouble() / STEP).coerceAtLeast(0.0)
                        if (mw <= 0.0) continue
                        val mi = (my * c.aiw + mx) * 3
                        var d2 = 0.0
                        for (k in 0 until 3) { val d = c.detail[si + k] - c.detail[mi + k]; d2 += d * d }
                        total += wt * mw * d2
                    }
                }
                if (total >= bound) return total
            }
            dy += 2
        }
        return total
    }

    // ------------------------------------------------------------------ smart fill: render

    /** Detail above about this many levels is an object, not texture, and is softened. */
    private const val TEXTURE_KNEE = 70.0

    private fun smartFill(px: IntArray, w: Int, h: Int, lay: Expand.Layout, out: IntArray, plan: Plan, check: () -> Unit, backdrop: IntArray? = null) {
        // This picture's own split into colour and texture, at the plan's analysis size.
        val small = shrink(px, w, h, plan.aiw, plan.aih)
        val low = blur(small, plan.aiw, plan.aih, lowRadius(plan.aiw, plan.aih))
        check()
        // Full pixels per analysis pixel, from the picture (the canvas rounds the same way).
        val kx = w.toDouble() / plan.aiw; val ky = h.toDouble() / plan.aih
        // How evenly the picture's detail is spread: a texture (stripes, grass) has strong detail
        // everywhere and keeps it far out; a picture of things has it in a few places, and those
        // are softened as the fill moves away, so no recognisable piece repeats.
        val mags = FloatArray(plan.aiw * plan.aih) { i -> (abs(small[i * 3] - low[i * 3]) + abs(small[i * 3 + 1] - low[i * 3 + 1]) + abs(small[i * 3 + 2] - low[i * 3 + 2])) / 3f }
        mags.sort()
        val typical = mags[mags.size / 2].toDouble()
        // Strong detail in places: a picture of things, which is what can repeat. A smooth picture has none.
        val things = ((mags[(mags.size * 9) / 10].toDouble() - 12.0) / 30.0).coerceIn(0.0, 1.0)
        val even = typical / (typical + 12.0)
        val knee = TEXTURE_KNEE + 4.0 * typical
        val floor = 0.3 + 0.65 * even
        Parallel.bands(lay.height) { from, to ->
            for (y in from until to) {
                if (y and 0x1F == 0) check()
                // Canvas analysis coordinate of this row.
                val v = (y - lay.y + 0.5) / ky - 0.5 + plan.ay
                val j0 = Math.floor(v / STEP).toInt()
                val ty = smoothstep((v / STEP - j0).coerceIn(0.0, 1.0))
                for (x in 0 until lay.width) {
                    if (lay.inside(x, y)) continue
                    val u = (x - lay.x + 0.5) / kx - 0.5 + plan.ax
                    val i0 = Math.floor(u / STEP).toInt()
                    val tx = smoothstep((u / STEP - i0).coerceIn(0.0, 1.0))
                    var r = bilinear(plan.smooth, plan.aw, plan.ah, u, v, 0).toDouble()
                    var g = bilinear(plan.smooth, plan.aw, plan.ah, u, v, 1).toDouble()
                    var b = bilinear(plan.smooth, plan.aw, plan.ah, u, v, 2).toDouble()
                    var dr = 0.0; var dg = 0.0; var db = 0.0; var wsum = 0.0
                    for (dj in 0..1) for (di in 0..1) {
                        val i = i0 + di; val j = j0 + dj
                        if (i !in 0 until plan.nodesX || j !in 0 until plan.nodesY) continue
                        val n = j * plan.nodesX + i
                        if (!plan.decided[n]) continue
                        val wt = (if (di == 0) 1 - tx else tx) * (if (dj == 0) 1 - ty else ty)
                        if (wt <= 0.0) continue
                        // The source: this pixel's analysis position in the picture, moved by the node's offset.
                        val su = u - plan.ax + plan.offX[n]; val sv = v - plan.ay + plan.offY[n]
                        val sx = ((su + 0.5) * kx - 0.5).roundToInt().coerceIn(0, w - 1)
                        val sy = ((sv + 0.5) * ky - 0.5).roundToInt().coerceIn(0, h - 1)
                        val c = px[sy * w + sx]
                        dr += wt * ((c shr 16 and 0xFF) - bilinear(low, plan.aiw, plan.aih, su, sv, 0))
                        dg += wt * ((c shr 8 and 0xFF) - bilinear(low, plan.aiw, plan.aih, su, sv, 1))
                        db += wt * ((c and 0xFF) - bilinear(low, plan.aiw, plan.aih, su, sv, 2))
                        wsum += wt
                    }
                    if (wsum > 0.0) {
                        // Texture fades with distance from the picture, and strong detail (a face,
                        // a stripe) is softened more than fine grain: near the edge the fill
                        // continues the picture, further out it settles into its colours instead
                        // of repeating recognisable pieces.
                        val ddx = max(0, max(lay.x - x, x - (lay.x + lay.innerWidth - 1))).toDouble()
                        val ddy = max(0, max(lay.y - y, y - (lay.y + lay.innerHeight - 1))).toDouble()
                        val reach = Math.hypot(ddx, ddy) / max(1, min(lay.innerWidth, lay.innerHeight))
                        val fade = 1.0 - (1.0 - floor) * smoothstep(((reach - 0.03) / 0.3).coerceIn(0.0, 1.0))
                        fun soft(d: Double): Double = d / (1.0 + Math.abs(d) / knee) * fade
                        r += soft(dr / wsum); g += soft(dg / wsum); b += soft(db / wsum)
                        // A picture of things, far out: settle into the soft backdrop, as a lens
                        // would, rather than tile what is not there. A texture never does.
                        if (backdrop != null) {
                            val m = (1.0 - even) * things * smoothstep(((reach - 0.1) / 0.35).coerceIn(0.0, 1.0))
                            if (m > 0.0) {
                                val c = backdrop[y * lay.width + x]
                                r += ((c shr 16 and 0xFF) - r) * m; g += ((c shr 8 and 0xFF) - g) * m; b += ((c and 0xFF) - b) * m
                            }
                        }
                    }
                    out[y * lay.width + x] = pack(r.toFloat(), g.toFloat(), b.toFloat())
                }
            }
        }
    }
}
