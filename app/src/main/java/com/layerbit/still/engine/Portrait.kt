package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A brushed correction to the automatic skin mask: [add] paints skin in, otherwise out. Canvas fractions. */
data class SkinStroke(val points: List<Pt>, val width: Float, val add: Boolean) {
    val valid: Boolean
        get() = points.isNotEmpty() && points.size <= Mark.MAX_POINTS && points.all { it.finite && it.x in 0f..1f && it.y in 0f..1f } && width in 0.002f..0.3f
}

enum class PortraitTapKind {
    /** Local clarity and a little light in a small circle: an eye. */
    EYE,
    /** Yellow drained and a little light in a small circle, sparing lips and gums: teeth. */
    TEETH
}

/** A tap on an eye or on teeth, with the circle's radius as a fraction of the shorter edge. */
data class PortraitTap(val at: Pt, val kind: PortraitTapKind, val radius: Float = DEFAULT_RADIUS) {
    val valid: Boolean get() = at.finite && at.x in 0f..1f && at.y in 0f..1f && radius in 0.005f..0.2f

    companion object { const val DEFAULT_RADIUS = 0.035f }
}

/**
 * Skin, made kinder. Every number 0 at rest except [texture], which only matters once smoothing
 * is on. The skin is found automatically (see [Portrait.skin]) and steered with [strokes]; eyes
 * and teeth are touched only where the user tapped, never guessed.
 */
data class PortraitEdit(
    /** Skin smoothing, 0..1: the soft band of the skin is flattened. */
    val smooth: Float = 0f,
    /** How much of the fine texture (pores) smoothing keeps, 0..1. */
    val texture: Float = 0.35f,
    /** Even tone, 0..1: blotchy redness pulled toward the skin's own colour. */
    val even: Float = 0f,
    /** Face light, 0..1: a soft lift on the skin, like a reflector held below the face. */
    val light: Float = 0f,
    /** Eye clarity at each eye tap, 0..1. */
    val eyes: Float = 0.6f,
    /** Whitening at each teeth tap, 0..1. */
    val teeth: Float = 0.6f,
    /** Keep the skin mask inside the subject the cut-out would find, so a wooden table behind is left alone. */
    val subjectOnly: Boolean = false,
    val strokes: List<SkinStroke> = emptyList(),
    val taps: List<PortraitTap> = emptyList()
) {
    val isIdentity: Boolean get() = this == PortraitEdit()

    /** Whether anything changes pixels. Strokes alone change nothing until a strength is up. */
    val active: Boolean get() = smooth > 0f || even > 0f || light > 0f || (taps.isNotEmpty() && (eyes > 0f || teeth > 0f))

    /** Whether the skin mask is needed at all. */
    val needsMask: Boolean get() = smooth > 0f || even > 0f || light > 0f

    val valid: Boolean
        get() = smooth in 0f..1f && texture in 0f..1f && even in 0f..1f && light in 0f..1f && eyes in 0f..1f && teeth in 0f..1f &&
            strokes.size <= MAX_STROKES && strokes.all { it.valid } && taps.size <= MAX_TAPS && taps.all { it.valid }

    /** What decides the mask, as a key for a cache. */
    val maskKey: String
        get() = "$subjectOnly|" + strokes.joinToString(";") { s -> (if (s.add) "a" else "r") + s.width + ":" + s.points.joinToString(",") { "${it.x},${it.y}" } }

    /** The strengths without what belongs to one picture: what a preset keeps. */
    fun asPreset(): PortraitEdit = copy(strokes = emptyList(), taps = emptyList())

    companion object {
        const val MAX_STROKES = 200
        const val MAX_TAPS = 40
    }
}

/**
 * Skin smoothing, even tone, face light, eyes and teeth, all decided here, on packed ARGB.
 *
 * Nothing here is a model or a download. The skin is found by colour: a broad YCbCr skin-tone
 * gate first, then the picture's own dominant skin cluster is measured and the gate tightened
 * around it, so a pale face under warm light and a dark face in shade are both followed. A
 * texture gate drops busy surfaces (brick courses, wood grain, hair, eyes, lips' edges) because
 * skin is the smooth part of a face. Known limits, measured in PortraitTest: a smooth, plain,
 * skin-coloured surface (a blonde-wood table, a beige wall, weathered orange brick) is taken for
 * skin; smoothing it changes little because it has little texture to lose, and a remove stroke or
 * "Only on the subject" takes it out.
 *
 * The mask is decided at [Segment.analysisSize] from the geometry stage and read bilinearly at any
 * size; every radius follows [Geometry2D.resolutionScale]. So the preview predicts the export.
 */
object Portrait {

    /** The skin found on a picture: a 0..255 mask at the analysis size and the skin's own colour. */
    class Skin(val mask: IntArray, val width: Int, val height: Int, val cb: Float, val cr: Float, val found: Boolean) {
        val coverage: Float get() = Segment.coverage(mask)
    }

    // Generic skin cluster centre and spread in Cb/Cr (0..255), from the usual skin-tone literature.
    private const val CB0 = 106f
    private const val CR0 = 153f
    private const val CB_SPREAD = 20f
    private const val CR_SPREAD = 17f

    /** The YCbCr of a packed colour, 0..255 each (BT.601 full range). */
    fun ycbcr(c: Int): FloatArray {
        val r = Argb.red(c).toFloat(); val g = Argb.green(c).toFloat(); val b = Argb.blue(c).toFloat()
        return floatArrayOf(
            0.299f * r + 0.587f * g + 0.114f * b,
            128f - 0.168736f * r - 0.331264f * g + 0.5f * b,
            128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        )
    }

    private fun rgb(a: Int, y: Float, cb: Float, cr: Float): Int {
        val r = y + 1.402f * (cr - 128f)
        val g = y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)
        val b = y + 1.772f * (cb - 128f)
        return Argb.pack(a, r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 0..1: how much a colour looks like skin to the broad, picture-independent gate. [wide] loosens it for the adapted pass. */
    fun genericSkin(c: Int, wide: Boolean = false, painted: Boolean = false): Float {
        if (painted) return paintedSkin(c, wide)
        val r = Argb.red(c); val g = Argb.green(c); val b = Argb.blue(c)
        // Skin is red-led: red the largest channel, blue the smallest or close to it.
        if (r < g || r <= b || r < 45) return 0f
        val (y, cb, cr) = ycbcr(c).let { Triple(it[0], it[1], it[2]) }
        val dcb = (cb - CB0) / CB_SPREAD; val dcr = (cr - CR0) / CR_SPREAD
        val d = sqrt(dcb * dcb + dcr * dcr)
        val colour = if (wide) 1f - smoothstep(1.2f, 1.7f, d) else 1f - smoothstep(0.75f, 1.2f, d)
        val light = smoothstep(30f, 55f, y) * (1f - smoothstep(245f, 254f, y))
        return colour * light
    }

    /**
     * Skin as painters and colour graders leave it: any warm hue from rosy to amber at any
     * saturation, red leading green leading blue. Far too broad to look for skin with on its own;
     * it is only tried when the ordinary range finds little skin where a person is, and the
     * cluster, texture and subject gates still narrow it to the face.
     */
    private fun paintedSkin(c: Int, wide: Boolean): Float {
        val r = Argb.red(c); val g = Argb.green(c); val b = Argb.blue(c)
        if (r < g || g < b || r < 60) return 0f
        val hsv = Palette.hsv(r, g, b)
        val hue = hsv[0]; val sat = hsv[1]; val v = hsv[2]
        val lo = if (wide) 0f else 4f; val hi = if (wide) 48f else 42f
        val inHue = smoothstep(lo - 4f, lo + 2f, hue) * (1f - smoothstep(hi - 4f, hi + 4f, hue))
        return inHue * smoothstep(0.12f, 0.22f, sat) * smoothstep(0.22f, 0.35f, v) * (1f - smoothstep(0.985f, 1f, v * (1f - sat)))
    }

    /**
     * The skin on [pixels] (at the analysis size): broad gate, the dominant cluster measured and
     * the gate tightened round it, the texture gate, small holes closed, the [subject] mask if
     * given, then the user's [strokes], then a light feather.
     */
    fun skin(pixels: IntArray, w: Int, h: Int, strokes: List<SkinStroke> = emptyList(), subject: IntArray? = null, check: () -> Unit = {}, prior: IntArray? = null): Skin {
        val first = skinOn(pixels, w, h, strokes, subject, check, prior)
        // A strong colour grade (golden hour, a teal-orange look) can push every face out of the
        // range skin is looked for in, so skin is also looked for with the picture's own cast taken
        // out, the way the eye adapts. Only the search sees the neutral copy, never the picture.
        // The answer that finds more skin where a person is likely to be wins.
        val where = prior ?: subject
        var best = first; var bestScore = personal(first, where)
        neutralised(pixels)?.let { neutral ->
            val second = skinOn(neutral, w, h, strokes, subject, check, prior)
            val score = personal(second, where)
            if (score > bestScore * 1.2f) { best = second; bestScore = score }
        }
        // Painted or heavily graded skin (deep amber, almost no blue) is outside any camera's range:
        // only if little skin was found where the person is, look with the painter's range.
        // Only with a person found: the painter's range is too broad to trust anywhere else.
        val person = prior ?: subject
        if (bestScore < 0.12f && person != null && Segment.coverage(person) in 0.03f..0.85f) {
            val third = skinOn(pixels, w, h, strokes, person, check, prior, painted = true)
            val score = personal(third, where)
            if (score > bestScore * 1.5f && score > 0.06f) best = third
        }
        return best
    }

    /** How much skin lies where a person is likely to be: the subject if known, else the middle of the frame. */
    private fun personal(s: Skin, where: IntArray?): Float {
        if (!s.found) return 0f
        var sum = 0.0; var weight = 0.0
        for (i in s.mask.indices) {
            val wgt = if (where != null) where[i] / 255.0 else {
                val dx = ((i % s.width) + 0.5) / s.width - 0.5; val dy = ((i / s.width) + 0.5) / s.height - 0.45
                kotlin.math.exp(-(dx * dx + dy * dy) / (2 * 0.25 * 0.25))
            }
            sum += wgt * s.mask[i] / 255.0; weight += wgt
        }
        return if (weight > 0) (sum / weight).toFloat() else 0f
    }

    /** [pixels] with the cast of what ought to be grey removed, or null when there is none to speak of. */
    internal fun neutralised(pixels: IntArray): IntArray? {
        val e = AutoWhite.estimate(pixels) ?: return null
        val mean = (e.r + e.g + e.b) / 3f
        if (mean < 8f) return null
        val gr = (mean / max(1, e.r)).coerceIn(0.5f, 2.2f); val gg = (mean / max(1, e.g)).coerceIn(0.5f, 2.2f); val gb = (mean / max(1, e.b)).coerceIn(0.5f, 2.2f)
        if (abs(gr - 1f) < 0.06f && abs(gg - 1f) < 0.06f && abs(gb - 1f) < 0.06f) return null
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            Argb.pack(Argb.alpha(c), (Argb.red(c) * gr).roundToInt().coerceIn(0, 255), (Argb.green(c) * gg).roundToInt().coerceIn(0, 255), (Argb.blue(c) * gb).roundToInt().coerceIn(0, 255))
        }
    }

    private fun skinOn(pixels: IntArray, w: Int, h: Int, strokes: List<SkinStroke>, subject: IntArray?, check: () -> Unit, prior: IntArray?, painted: Boolean = false): Skin {
        val n = w * h
        val shorter = min(w, h)
        val ys = FloatArray(n); val cbs = FloatArray(n); val crs = FloatArray(n); val generic = FloatArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            val ycc = ycbcr(c)
            ys[i] = ycc[0]; cbs[i] = ycc[1]; crs[i] = ycc[2]
            generic[i] = genericSkin(c, painted = painted)
        }
        check()
        // Painted and sharpened skin carries more shading per pixel than a camera's: a looser gate.
        val texture = if (painted) textureGate(ys, w, h, max(1, (0.005f * shorter).roundToInt()), 16f, 34f)
        else textureGate(ys, w, h, max(1, (0.005f * shorter).roundToInt()))
        // The dominant skin cluster: a Cb/Cr histogram of confident, smooth skin, its peak, and the
        // mean and spread of the pixels near that peak.
        val bins = 32
        val hist = FloatArray(bins * bins)
        var confident = 0
        // Where a person is likely to be: the subject, when one is known, else the middle of the
        // frame. Only the choice of skin colour leans on it - a golden sky or a tiger that
        // outnumbers the skin must not decide what skin looks like - not the mask itself.
        val (bias, guide) = prior?.let { p -> p to subject } ?: (subject to subject)
        val lean = FloatArray(n) { i ->
            val from = bias ?: guide
            if (from != null) 0.15f + 0.85f * from[i] / 255f
            else {
                val dx = ((i % w) + 0.5f) / w - 0.5f; val dy = ((i / w) + 0.5f) / h - 0.45f
                0.25f + 0.75f * kotlin.math.exp(-(dx * dx + dy * dy) / (2f * 0.3f * 0.3f))
            }
        }
        for (i in 0 until n) {
            val wgt = generic[i] * texture[i]
            if (wgt < 0.5f) continue
            confident++
            val bx = (cbs[i] / 8f).toInt().coerceIn(0, bins - 1); val by = (crs[i] / 8f).toInt().coerceIn(0, bins - 1)
            hist[by * bins + bx] += wgt * lean[i]
        }
        var found = confident >= max(16, n / 400)
        val clusterless = painted
        var mCb = CB0; var mCr = CR0; var sCb = CB_SPREAD; var sCr = CR_SPREAD
        if (found) {
            // Smoothed peak, so one noisy bin does not win.
            var best = -1f; var bi = 0
            for (by in 0 until bins) for (bx in 0 until bins) {
                var s = 0f
                for (dy in -1..1) for (dx in -1..1) { val x = bx + dx; val y = by + dy; if (x in 0 until bins && y in 0 until bins) s += hist[y * bins + x] }
                if (s > best) { best = s; bi = by * bins + bx }
            }
            val pcb = (bi % bins) * 8f + 4f; val pcr = (bi / bins) * 8f + 4f
            var sw = 0.0; var a = 0.0; var b = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 0 until n) {
                val wgt = generic[i] * texture[i]
                if (wgt < 0.5f) continue
                val dcb = cbs[i] - pcb; val dcr = crs[i] - pcr
                if (dcb * dcb + dcr * dcr > 14f * 14f) continue
                val lw = wgt * lean[i]
                sw += lw; a += lw * cbs[i]; b += lw * crs[i]; aa += lw * cbs[i] * cbs[i]; bb += lw * crs[i] * crs[i]
            }
            if (sw > 8.0) {
                mCb = (a / sw).toFloat(); mCr = (b / sw).toFloat()
                // Inflated so shading stays in, and wider still toward red so blush and blotches are skin to be evened, not holes.
                sCb = (sqrt(max(0.0, aa / sw - (a / sw) * (a / sw))).toFloat() * 2.6f).coerceIn(8f, 22f)
                sCr = (sqrt(max(0.0, bb / sw - (b / sw) * (b / sw))).toFloat() * 2.6f).coerceIn(8f, 22f)
            } else found = false
        }
        check()
        val raw = IntArray(n)
        for (i in 0 until n) {
            val adapted = if (found && !clusterless) {
                val dcb = (cbs[i] - mCb) / sCb; val dcr = (crs[i] - mCr) / (if (crs[i] > mCr) sCr * 1.8f else sCr)
                1f - smoothstep(1.4f, 2.2f, sqrt(dcb * dcb + dcr * dcr))
            } else 1f
            val g = if (found) genericSkin(pixels[i], wide = true, painted = painted) else generic[i]
            raw[i] = (min(g, adapted) * 255f).roundToInt()
        }
        // Close small holes (a highlight on a cheek, a freckle) before the texture gate cuts the busy parts back out.
        val r = max(1, (0.004f * shorter).roundToInt())
        val closed = Segment.shrink(Segment.grow(raw, w, h, r), w, h, r)
        for (i in 0 until n) {
            var m = closed[i] * texture[i]
            if (subject != null) m = m * subject[i] / 255f
            closed[i] = m.roundToInt().coerceIn(0, 255)
        }
        var mask = Segment.feather(closed, w, h, max(1, (0.003f * shorter).roundToInt()))
        if (strokes.isNotEmpty()) mask = applyStrokes(mask, w, h, strokes)
        return Skin(mask, w, h, mCb, mCr, found)
    }

    /** 1 where the luminance is smooth, falling to 0 where its local spread is wide: busy surfaces are not skin. */
    private fun textureGate(y: FloatArray, w: Int, h: Int, r: Int, lo: Float = 8f, hi: Float = 16f): FloatArray {
        val mean = boxMean(y, w, h, r)
        val sq = FloatArray(y.size) { y[it] * y[it] }
        val meanSq = boxMean(sq, w, h, r)
        return FloatArray(y.size) { i ->
            val sd = sqrt((meanSq[i] - mean[i] * mean[i]).coerceAtLeast(0f))
            1f - smoothstep(lo, hi, sd)
        }
    }

    /** The mask with each stroke painted in (add) or out, soft-edged discs along the path. */
    fun applyStrokes(mask: IntArray, w: Int, h: Int, strokes: List<SkinStroke>): IntArray {
        val out = mask.copyOf()
        val shorter = min(w, h).toFloat()
        for (s in strokes) {
            val radius = max(1f, s.width * shorter / 2f)
            val pts = s.points
            fun disc(cx: Float, cy: Float) {
                val x0 = (cx - radius - 1).toInt().coerceIn(0, w - 1); val x1 = (cx + radius + 1).toInt().coerceIn(0, w - 1)
                val y0 = (cy - radius - 1).toInt().coerceIn(0, h - 1); val y1 = (cy + radius + 1).toInt().coerceIn(0, h - 1)
                for (y in y0..y1) for (x in x0..x1) {
                    val dx = x - cx; val dy = y - cy
                    val d = sqrt(dx * dx + dy * dy)
                    // Solid to 70% of the radius, soft to the rim.
                    val k = 1f - smoothstep(radius * 0.7f, radius, d)
                    if (k <= 0f) continue
                    val i = y * w + x
                    val v = (k * 255f).roundToInt()
                    out[i] = if (s.add) max(out[i], v) else min(out[i], 255 - v)
                }
            }
            val step = max(0.5f, radius * 0.4f)
            for (j in pts.indices) {
                val ax = pts[j].x * (w - 1); val ay = pts[j].y * (h - 1)
                if (j == 0) { disc(ax, ay); continue }
                val bx = pts[j - 1].x * (w - 1); val by = pts[j - 1].y * (h - 1)
                val len = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
                val steps = max(1, (len / step).toInt())
                for (k in 1..steps) { val t = k.toFloat() / steps; disc(bx + (ax - bx) * t, by + (ay - by) * t) }
            }
        }
        return out
    }

    /** The skin mask painted over [pixels] (same size) as a green wash, for "Show skin mask". */
    fun paintMask(pixels: IntArray, w: Int, h: Int, skin: Skin) {
        val sx = (skin.width - 1).toFloat() / max(1, w - 1); val sy = (skin.height - 1).toFloat() / max(1, h - 1)
        Parallel.bands(h) { from, to -> for (y in from until to) for (x in 0 until w) {
            val m = Segment.sample(skin.mask, skin.width, skin.height, x * sx, y * sy)
            val f = m * 0.55f / 255f
            if (f <= 0f) continue
            val i = y * w + x; val c = pixels[i]
            pixels[i] = Argb.pack(Argb.alpha(c), (Argb.red(c) + (60 - Argb.red(c)) * f).roundToInt(), (Argb.green(c) + (220 - Argb.green(c)) * f).roundToInt(), (Argb.blue(c) + (140 - Argb.blue(c)) * f).roundToInt())
        } }
    }

    /**
     * Applies [p] to [pixels] (w x h, any size) using [skin] decided at the analysis size. Returns
     * a new array; [pixels] is not changed. Strength 0 everywhere returns the input unchanged.
     */
    fun apply(pixels: IntArray, w: Int, h: Int, p: PortraitEdit, skin: Skin?, check: () -> Unit = {}): IntArray {
        if (!p.active || w < 2 || h < 2) return pixels
        val out = pixels.copyOf()
        if (p.needsMask && skin != null) skinPass(out, w, h, p, skin, check)
        for (t in p.taps) {
            check()
            when (t.kind) {
                PortraitTapKind.EYE -> if (p.eyes > 0f) eye(out, w, h, t, p.eyes)
                PortraitTapKind.TEETH -> if (p.teeth > 0f) teeth(out, w, h, t, p.teeth)
            }
        }
        return out
    }

    private fun skinPass(px: IntArray, w: Int, h: Int, p: PortraitEdit, skin: Skin, check: () -> Unit) {
        val mw = skin.width; val mh = skin.height
        val scale = Geometry2D.resolutionScale(Geometry2D.Size(w, h))
        val smoothR = max(1, (5f * scale).roundToInt())
        val evenR = max(2, (30f * scale).roundToInt())
        // Face light reads a much softer mask: a glow on the face, not a cut-out of it.
        val glow = if (p.light > 0f) Segment.feather(skin.mask, mw, mh, max(1, (0.03f * min(mw, mh)).roundToInt())) else null
        // Work only inside the skin's bounds and a margin as wide as the widest filter.
        val bounds = Segment.bounds(skin.mask, mw, mh, margin = 0f, threshold = 8) ?: return
        val margin = 3 * max(smoothR, evenR) + 2
        val x0 = ((bounds[0] * w).toInt() - margin).coerceIn(0, w - 1); val y0 = ((bounds[1] * h).toInt() - margin).coerceIn(0, h - 1)
        val x1 = ((bounds[2] * w).roundToInt() + margin).coerceIn(x0 + 1, w); val y1 = ((bounds[3] * h).roundToInt() + margin).coerceIn(y0 + 1, h)
        val ww = x1 - x0; val wh = y1 - y0; val n = ww * wh
        val ys = FloatArray(n); val cbs = FloatArray(n); val crs = FloatArray(n)
        for (y in 0 until wh) for (x in 0 until ww) {
            val ycc = ycbcr(px[(y + y0) * w + x + x0])
            val i = y * ww + x
            ys[i] = ycc[0]; cbs[i] = ycc[1]; crs[i] = ycc[2]
        }
        check()
        // Frequency separation: a self-guided filter is the low band (edges kept, soft wobble flattened),
        // what it took away is the high band, and [texture] of the high band goes back on.
        var low: FloatArray? = null
        if (p.smooth > 0f) {
            val eps = (6f + 22f * p.smooth).let { it * it }
            val mean = boxMean(ys, ww, wh, smoothR)
            val meanSq = boxMean(FloatArray(n) { ys[it] * ys[it] }, ww, wh, smoothR)
            val ak = FloatArray(n); val bk = FloatArray(n)
            for (i in 0 until n) {
                val v = (meanSq[i] - mean[i] * mean[i]).coerceAtLeast(0f)
                val k = v / (v + eps); ak[i] = k; bk[i] = mean[i] - k * mean[i]
            }
            val am = boxMean(ak, ww, wh, smoothR); val bm = boxMean(bk, ww, wh, smoothR)
            low = FloatArray(n) { am[it] * ys[it] + bm[it] }
            check()
        }
        val cbWide = if (p.even > 0f) boxMean(cbs, ww, wh, evenR) else null
        val crWide = if (p.even > 0f) boxMean(crs, ww, wh, evenR) else null
        val cbNear = if (p.smooth > 0f) boxMean(cbs, ww, wh, smoothR) else null
        val crNear = if (p.smooth > 0f) boxMean(crs, ww, wh, smoothR) else null
        check()
        val sx = (mw - 1).toFloat() / max(1, w - 1); val sy = (mh - 1).toFloat() / max(1, h - 1)
        Parallel.bands(wh) { from, to -> for (y in from until to) for (x in 0 until ww) {
            val gx = x + x0; val gy = y + y0
            val m = Segment.sample(skin.mask, mw, mh, gx * sx, gy * sy) / 255f
            val g = glow?.let { Segment.sample(it, mw, mh, gx * sx, gy * sy) / 255f } ?: 0f
            if (m <= 0f && g <= 0f) continue
            val i = y * ww + x
            var yv = ys[i]; var cb = cbs[i]; var cr = crs[i]
            if (low != null && m > 0f) {
                val smoothed = low[i] + (yv - low[i]) * p.texture
                val k = m * p.smooth
                yv += (smoothed - yv) * k
                // Colour mottling at the same scale goes with it, a little.
                cb += (cbNear!![i] - cb) * k * 0.6f; cr += (crNear!![i] - cr) * k * 0.6f
            }
            if (crWide != null && m > 0f) {
                val k = m * p.even
                cb += (cbWide!![i] - cb) * k; cr += (crWide[i] - cr) * k
                // Redder than the skin's own colour: pulled back toward it.
                val excess = cr - skin.cr
                if (excess > 0f) cr -= excess * 0.8f * k
            }
            if (g > 0f) {
                val k = g * p.light * 0.32f
                yv += (255f - yv) * k * (0.35f + 0.65f * yv / 255f)
            }
            val gi = gy * w + gx
            px[gi] = rgb(Argb.alpha(px[gi]), yv, cb, cr)
        } }
    }

    /** Pixel window, radius and soft weight for a tap: (x0, y0, x1, y1, radius px). */
    private fun tapWindow(t: PortraitTap, w: Int, h: Int): FloatArray {
        val r = max(2f, t.radius * min(w, h))
        val cx = t.at.x * (w - 1); val cy = t.at.y * (h - 1)
        return floatArrayOf(cx, cy, r)
    }

    private fun tapWeight(dx: Float, dy: Float, r: Float): Float = 1f - smoothstep(r * 0.55f, r, sqrt(dx * dx + dy * dy))

    /** Local clarity and a little light in the circle: an iris that reads, a catchlight that sparkles. */
    fun eye(px: IntArray, w: Int, h: Int, t: PortraitTap, strength: Float) {
        val (cx, cy, r) = tapWindow(t, w, h).let { Triple(it[0], it[1], it[2]) }
        val x0 = (cx - r - 1).toInt().coerceIn(0, w - 1); val x1 = (cx + r + 1).toInt().coerceIn(0, w - 1)
        val y0 = (cy - r - 1).toInt().coerceIn(0, h - 1); val y1 = (cy + r + 1).toInt().coerceIn(0, h - 1)
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val region = IntArray(rw * rh) { px[(it / rw + y0) * w + it % rw + x0] }
        val blur = Spatial.boxBlur(region, rw, rh, max(1, (r / 5f).roundToInt()), passes = 2)
        for (y in 0 until rh) for (x in 0 until rw) {
            val k = tapWeight(x + x0 - cx, y + y0 - cy, r) * strength
            if (k <= 0f) continue
            val i = y * rw + x; val c = region[i]; val b = blur[i]
            fun ch(v: Int, bv: Int): Int {
                val sharp = v + (v - bv) * 1.6f * k
                return (sharp + (255f - sharp) * 0.10f * k * (sharp / 255f)).roundToInt().coerceIn(0, 255)
            }
            px[(y + y0) * w + x + x0] = Argb.pack(Argb.alpha(c), ch(Argb.red(c), Argb.red(b)), ch(Argb.green(c), Argb.green(b)), ch(Argb.blue(c), Argb.blue(b)))
        }
    }

    /** Yellow drained and a little light in the circle, only on bright pixels and never on red lips or gums. */
    fun teeth(px: IntArray, w: Int, h: Int, t: PortraitTap, strength: Float) {
        val (cx, cy, r) = tapWindow(t, w, h).let { Triple(it[0], it[1], it[2]) }
        val x0 = (cx - r - 1).toInt().coerceIn(0, w - 1); val x1 = (cx + r + 1).toInt().coerceIn(0, w - 1)
        val y0 = (cy - r - 1).toInt().coerceIn(0, h - 1); val y1 = (cy + r + 1).toInt().coerceIn(0, h - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val base = tapWeight(x - cx, y - cy, r) * strength
            if (base <= 0f) continue
            val i = y * w + x; val c = px[i]
            val (yv, cb, cr) = ycbcr(c).let { Triple(it[0], it[1], it[2]) }
            val k = base * smoothstep(80f, 130f, yv) * (1f - smoothstep(146f, 162f, cr))
            if (k <= 0f) continue
            // Yellow is low Cb; it is brought back toward neutral, and any warmth in Cr with it.
            val nb = if (cb < 128f) cb + (128f - cb) * 0.85f * k else cb
            val nr = if (cr > 128f) cr - (cr - 128f) * 0.5f * k else cr
            val ny = yv + (255f - yv) * 0.18f * k
            px[i] = rgb(Argb.alpha(c), ny, nb, nr)
        }
    }

    /** A box mean of radius [r] with clamped edges, in two separable running-sum passes. */
    fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size); val out = FloatArray(src.size)
        val win = (2 * r + 1).toFloat()
        Parallel.bands(h) { from, to -> for (y in from until to) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / win
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        } }
        Parallel.bands(w) { from, to -> for (x in from until to) {
            var sum = 0f
            for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / win
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        } }
        return out
    }

    /** Variance of the luminance's high band (pixel minus a small blur) over the pixels where [where] is true: a texture measure for tests and the panel. */
    fun highFrequencyVariance(pixels: IntArray, w: Int, h: Int, where: (Int, Int) -> Boolean): Double {
        val y = FloatArray(pixels.size) { ycbcr(pixels[it])[0] }
        val m = boxMean(y, w, h, 1)
        var s = 0.0; var ss = 0.0; var n = 0
        for (yy in 0 until h) for (x in 0 until w) {
            if (!where(x, yy)) continue
            val d = (y[yy * w + x] - m[yy * w + x]).toDouble()
            s += d; ss += d * d; n++
        }
        if (n == 0) return 0.0
        return ss / n - (s / n) * (s / n)
    }
}
