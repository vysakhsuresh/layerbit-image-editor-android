package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local adjustments: light and colour changed in one part of the picture only.
 *
 * Each [LocalAdjust] is a mask and a handful of sliders. The mask says where (a painted brush,
 * a straight gradient, an ellipse, a control point that follows colour, or the subject, the
 * background or the sky found automatically); the sliders say what. Everything is written down
 * as fractions - points of the canvas after geometry, widths of its shorter edge - so the mask a
 * finger drew on a 900-pixel preview lands on the same place of a 24-megapixel export, and
 * [LocalMasks] draws it at whatever size is asked for.
 */
enum class MaskKind {
    /** Painted strokes, each adding to the mask or erasing from it. */
    BRUSH,
    /** A straight gradient: whole at [LocalMask.a], gone at [LocalMask.b]. */
    LINEAR,
    /** An ellipse around [LocalMask.a], soft for [LocalMask.feather] of its radius. */
    RADIAL,
    /** A control point: what is near [LocalMask.a] and looks like the colour there. */
    POINT,
    /** The subject, as the cut-out finds it. */
    SUBJECT,
    /** Everything but the subject. */
    BACKGROUND,
    /** Bright, blue or white, smooth, and reaching the top edge. */
    SKY
}

/** One brush stroke in a [MaskKind.BRUSH] mask. */
data class BrushStroke(
    val points: List<Pt>,
    /** Brush diameter as a fraction of the shorter edge. */
    val width: Float = 0.08f,
    /** 0 is a hard edge; 1 fades from the middle of the brush out. */
    val softness: Float = 0.6f,
    /** Takes away from the mask instead of adding to it. */
    val erase: Boolean = false
) {
    val valid: Boolean
        get() = points.isNotEmpty() && points.size <= Mark.MAX_POINTS && points.all { it.finite && it.x in 0f..1f && it.y in 0f..1f } &&
            width in 0.004f..0.5f && softness in 0f..1f
}

/** Where a local adjustment applies. Only the fields its [kind] reads matter; the rest keep their defaults. */
data class LocalMask(
    val kind: MaskKind,
    val strokes: List<BrushStroke> = emptyList(),
    /** LINEAR: where the effect is whole. RADIAL and POINT: the centre. */
    val a: Pt = Pt(0.5f, 0.5f),
    /** LINEAR: where the effect has faded to nothing. */
    val b: Pt = Pt(0.5f, 0.75f),
    /** RADIAL: the half-width; POINT: how far it reaches. Fractions of the shorter edge. */
    val radiusX: Float = 0.25f,
    /** RADIAL: the half-height, a fraction of the shorter edge. */
    val radiusY: Float = 0.25f,
    /** RADIAL: degrees, clockwise. */
    val angle: Float = 0f,
    /** RADIAL: how much of the radius the edge fades over, 0..1. */
    val feather: Float = 0.5f,
    /** POINT: how different a colour may be from the one under the point and still be included, 0..1. */
    val range: Float = 0.4f,
    val invert: Boolean = false
) {
    val valid: Boolean
        get() = strokes.size <= MAX_STROKES && strokes.all { it.valid } && inReach(a) && inReach(b) &&
            radiusX.isFinite() && radiusX in 0.01f..2f && radiusY.isFinite() && radiusY in 0.01f..2f &&
            angle.isFinite() && angle in -360f..360f && feather in 0f..1f && range in 0f..1f

    /** Whether the mask is read from the picture rather than drawn from numbers alone. */
    val readsPicture: Boolean get() = kind == MaskKind.POINT || kind == MaskKind.SUBJECT || kind == MaskKind.BACKGROUND || kind == MaskKind.SKY

    private fun inReach(p: Pt) = p.finite && p.x in -1f..2f && p.y in -1f..2f

    companion object {
        const val MAX_STROKES = 200

        /** A sensible first mask of each kind, placed where it is easy to see and to move. */
        fun create(kind: MaskKind): LocalMask = when (kind) {
            MaskKind.LINEAR -> LocalMask(kind, a = Pt(0.5f, 0.15f), b = Pt(0.5f, 0.55f))
            MaskKind.RADIAL -> LocalMask(kind, a = Pt(0.5f, 0.5f), radiusX = 0.3f, radiusY = 0.22f, feather = 0.6f)
            MaskKind.POINT -> LocalMask(kind, a = Pt(0.5f, 0.5f), radiusX = 0.18f, range = 0.4f)
            else -> LocalMask(kind)
        }
    }
}

/** Ready-made settings for a new local adjustment. */
enum class LocalPreset { DODGE, BURN, POP }

/**
 * One local adjustment: a mask and what it does there. Every slider is 0 at rest, and the
 * ranges are the global sliders' ranges, so a local change is never stronger than the user
 * could make it across the whole picture.
 */
data class LocalAdjust(
    val mask: LocalMask,
    /** Stops, -2..2. */
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    /** Structure: local contrast at a large radius, -1..1. */
    val clarity: Float = 0f,
    /** Positive sharpens, negative blurs, -1..1. */
    val sharpness: Float = 0f,
    val visible: Boolean = true
) {
    val valid: Boolean
        get() = mask.valid && exposure in -2f..2f && contrast in -1f..1f && highlights in -1f..1f && shadows in -1f..1f &&
            temperature in -1f..1f && tint in -1f..1f && saturation in -1f..1f && clarity in -1f..1f && sharpness in -1f..1f

    /** True when the sliders are all at rest: the mask exists but changes nothing yet. */
    val adjustsNothing: Boolean
        get() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f && temperature == 0f && tint == 0f &&
            saturation == 0f && clarity == 0f && sharpness == 0f

    /** The per-pixel part, as a tone the shared pipeline already knows how to apply. */
    fun pointTone(): Tone = Tone(exposure = exposure, contrast = contrast, highlights = highlights, shadows = shadows, temperature = temperature, tint = tint, saturation = saturation)

    /** The sliders set to a preset, the mask kept. */
    fun preset(preset: LocalPreset): LocalAdjust {
        val rest = LocalAdjust(mask, visible = visible)
        return when (preset) {
            LocalPreset.DODGE -> rest.copy(exposure = 0.5f, shadows = 0.15f)
            LocalPreset.BURN -> rest.copy(exposure = -0.5f, highlights = -0.15f)
            LocalPreset.POP -> rest.copy(contrast = 0.25f, saturation = 0.25f, clarity = 0.45f)
        }
    }

    companion object {
        const val MAX = 32
    }
}

/**
 * Masks drawn from a [LocalMask] at any size, values 0..1.
 *
 * The drawn kinds (brush, linear, radial) are evaluated per pixel of a grid as large as the
 * picture up to [GRID_PIXELS]; the kinds read from the picture (point, subject, background,
 * sky) are decided on the fixed-size analysis picture, as the cut-out is, and resampled. Either
 * way the grid is drawn from fractions alone, so the preview's mask is the export's mask.
 */
object LocalMasks {

    /** The largest mask grid; above this the mask is drawn smaller and read bilinearly, its edges being soft anyway. */
    const val GRID_PIXELS = 2_500_000L

    /** A mask at some size, read at any other. */
    class Grid(val values: FloatArray, val width: Int, val height: Int) {
        /** The value under pixel ([x], [y]) of a [w] x [h] picture. */
        fun at(x: Int, y: Int, w: Int, h: Int): Float {
            if (w == width && h == height) return values[y * width + x]
            val fx = ((x + 0.5f) * width / w - 0.5f).coerceIn(0f, (width - 1).toFloat())
            val fy = ((y + 0.5f) * height / h - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(width - 1, x0 + 1); val y1 = min(height - 1, y0 + 1)
            val wx = fx - x0; val wy = fy - y0
            val top = values[y0 * width + x0] * (1 - wx) + values[y0 * width + x1] * wx
            val bottom = values[y1 * width + x0] * (1 - wx) + values[y1 * width + x1] * wx
            return top * (1 - wy) + bottom * wy
        }

        /** The share of the picture the mask covers, 0..1. */
        val coverage: Float get() = if (values.isEmpty()) 0f else (values.sumOf { it.toDouble() } / values.size).toFloat()
        val empty: Boolean get() = values.all { it <= 0f }
    }

    /**
     * The picture at the analysis size, before any tone, for the kinds that read it. [subject]
     * gives the cut-out's subject mask (0..255) at the same size, asked for only when needed.
     */
    class Analysis(val pixels: IntArray, val width: Int, val height: Int, subject: () -> IntArray) {
        val subject: IntArray by lazy(subject)
    }

    /** The grid size for a [w] x [h] picture: the picture itself, or the same aspect at [GRID_PIXELS]. */
    fun gridSize(w: Int, h: Int): Geometry2D.Size {
        if (w.toLong() * h <= GRID_PIXELS) return Geometry2D.Size(w, h)
        val k = sqrt(GRID_PIXELS.toDouble() / (w.toDouble() * h))
        return Geometry2D.Size(max(1, (w * k).toInt()), max(1, (h * k).toInt()))
    }

    /** [mask] drawn for a [w] x [h] picture, with inversion applied. [analysis] is needed only when [LocalMask.readsPicture]. */
    fun render(mask: LocalMask, w: Int, h: Int, analysis: Analysis?, check: () -> Unit = {}): Grid {
        val size = gridSize(w, h)
        val gw = size.width; val gh = size.height
        val values = when (mask.kind) {
            MaskKind.BRUSH -> brush(mask.strokes, gw, gh, check)
            MaskKind.LINEAR -> linear(mask.a, mask.b, gw, gh)
            MaskKind.RADIAL -> radial(mask, gw, gh)
            MaskKind.POINT, MaskKind.SUBJECT, MaskKind.BACKGROUND, MaskKind.SKY -> {
                val an = requireNotNull(analysis) { "this mask reads the picture" }
                val small = when (mask.kind) {
                    MaskKind.POINT -> point(an.pixels, an.width, an.height, mask.a, mask.radiusX, mask.range)
                    MaskKind.SKY -> sky(an.pixels, an.width, an.height, check)
                    // The cut-out's edge is hard; a light feather keeps a change from drawing a line round the subject.
                    MaskKind.SUBJECT -> blur(FloatArray(an.subject.size) { an.subject[it] / 255f }, an.width, an.height, max(1, (0.006f * min(an.width, an.height)).roundToInt()))
                    else -> blur(FloatArray(an.subject.size) { 1f - an.subject[it] / 255f }, an.width, an.height, max(1, (0.006f * min(an.width, an.height)).roundToInt()))
                }
                upsample(small, an.width, an.height, gw, gh)
            }
        }
        if (mask.invert) for (i in values.indices) values[i] = 1f - values[i]
        return Grid(values, gw, gh)
    }

    // ------------------------------------------------------------------ drawn masks

    /**
     * Strokes in order: each adds its coverage to the mask (a soft union) or takes it away.
     * Within one stroke the coverage is the nearest segment's, so a stroke that crosses itself
     * is no darker where it crosses.
     */
    fun brush(strokes: List<BrushStroke>, gw: Int, gh: Int, check: () -> Unit = {}): FloatArray {
        val m = FloatArray(gw * gh)
        if (strokes.isEmpty()) return m
        val short = min(gw, gh).toFloat()
        val buf = FloatArray(gw * gh)
        for (s in strokes) {
            check()
            val r = max(0.75f, s.width * short / 2f)
            val core = r * (1f - s.softness)
            // Points closer than a quarter of the radius add nothing a segment does not already cover.
            val pts = ArrayList<Float>(s.points.size * 2)
            for ((index, p) in s.points.withIndex()) {
                val x = p.x * gw; val y = p.y * gh
                if (pts.size >= 2 && index < s.points.lastIndex) {
                    val dx = x - pts[pts.size - 2]; val dy = y - pts[pts.size - 1]
                    if (dx * dx + dy * dy < (r * 0.25f) * (r * 0.25f)) continue
                }
                pts.add(x); pts.add(y)
            }
            val n = pts.size / 2
            // The stroke's own box, cleared once: the buffer holds only this stroke.
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (k in 0 until n) { minX = min(minX, pts[2 * k]); maxX = max(maxX, pts[2 * k]); minY = min(minY, pts[2 * k + 1]); maxY = max(maxY, pts[2 * k + 1]) }
            val bx0 = max(0, (minX - r - 1).toInt()); val bx1 = min(gw - 1, (maxX + r + 1).toInt())
            val by0 = max(0, (minY - r - 1).toInt()); val by1 = min(gh - 1, (maxY + r + 1).toInt())
            if (bx0 > bx1 || by0 > by1) continue
            for (y in by0..by1) java.util.Arrays.fill(buf, y * gw + bx0, y * gw + bx1 + 1, 0f)
            for (k in 0 until max(1, n - 1)) {
                val ax = pts[2 * k]; val ay = pts[2 * k + 1]
                val cx = if (n > 1) pts[2 * k + 2] else ax; val cy = if (n > 1) pts[2 * k + 3] else ay
                val x0 = max(0, (min(ax, cx) - r - 1).toInt()); val x1 = min(gw - 1, (max(ax, cx) + r + 1).toInt())
                val y0 = max(0, (min(ay, cy) - r - 1).toInt()); val y1 = min(gh - 1, (max(ay, cy) + r + 1).toInt())
                if (x0 > x1 || y0 > y1) continue
                val sx = cx - ax; val sy = cy - ay
                val len2 = sx * sx + sy * sy
                for (y in y0..y1) {
                    val py = y + 0.5f
                    for (x in x0..x1) {
                        val px = x + 0.5f
                        var t = if (len2 > 1e-6f) ((px - ax) * sx + (py - ay) * sy) / len2 else 0f
                        t = t.coerceIn(0f, 1f)
                        val dx = px - (ax + sx * t); val dy = py - (ay + sy * t)
                        val d = sqrt(dx * dx + dy * dy)
                        val c = when {
                            d <= core -> 1f
                            d >= r -> 0f
                            else -> 1f - smooth((d - core) / (r - core))
                        }
                        val i = y * gw + x
                        if (c > buf[i]) buf[i] = c
                    }
                }
            }
            for (y in by0..by1) for (x in bx0..bx1) {
                val i = y * gw + x
                val c = buf[i]
                if (c <= 0f) continue
                m[i] = if (s.erase) m[i] * (1f - c) else m[i] + (1f - m[i]) * c
            }
        }
        return m
    }

    /** Whole on the [a] side of the line through [a], fading to nothing at the line through [b], both across the a-b direction. */
    fun linear(a: Pt, b: Pt, gw: Int, gh: Int): FloatArray {
        val m = FloatArray(gw * gh)
        val ax = a.x * gw; val ay = a.y * gh
        val dx = b.x * gw - ax; val dy = b.y * gh - ay
        val len2 = dx * dx + dy * dy
        for (y in 0 until gh) {
            val py = y + 0.5f - ay
            for (x in 0 until gw) {
                val px = x + 0.5f - ax
                m[y * gw + x] = if (len2 < 1e-6f) (if (px * dx + py * dy <= 0f) 1f else 0f)
                else 1f - smooth(((px * dx + py * dy) / len2).coerceIn(0f, 1f))
            }
        }
        return m
    }

    /** Whole inside the ellipse's inner part, fading to nothing at its edge. */
    fun radial(mask: LocalMask, gw: Int, gh: Int): FloatArray {
        val m = FloatArray(gw * gh)
        val short = min(gw, gh).toFloat()
        val cx = mask.a.x * gw; val cy = mask.a.y * gh
        val rx = max(0.5f, mask.radiusX * short); val ry = max(0.5f, mask.radiusY * short)
        val rad = Math.toRadians(mask.angle.toDouble())
        val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        val inner = (1f - mask.feather).coerceIn(0f, 1f)
        // Half a pixel of fade even at feather 0, so a hard ellipse is not a jagged one.
        val aa = 0.5f / min(rx, ry)
        for (y in 0 until gh) {
            val py = y + 0.5f - cy
            for (x in 0 until gw) {
                val px = x + 0.5f - cx
                val u = (px * c + py * s) / rx
                val v = (-px * s + py * c) / ry
                val d = sqrt(u * u + v * v)
                val lo = min(inner, 1f - aa); val hi = 1f + if (inner >= 1f - aa) aa else 0f
                m[y * gw + x] = when {
                    d <= lo -> 1f
                    d >= hi -> 0f
                    else -> 1f - smooth((d - lo) / (hi - lo))
                }
            }
        }
        return m
    }

    // ------------------------------------------------------------------ masks read from the picture

    /**
     * A control point: the pixels within [reach] of [at] whose colour is close to the colour
     * under it. Distance fades smoothly; colour is judged on lightness and two colour axes, so
     * a point on a face takes the skin and leaves the shirt and the wall behind it. Edges come
     * from the colour test itself, which is why this follows a jawline a circle would cross.
     */
    fun point(px: IntArray, w: Int, h: Int, at: Pt, reach: Float, range: Float): FloatArray {
        val short = min(w, h).toFloat()
        val cx = at.x * w; val cy = at.y * h
        val r = max(1f, reach * short)
        // The colour under the point: a small average, so one noisy pixel does not decide it.
        val half = max(1, (short / 150f).roundToInt())
        val ix = cx.toInt().coerceIn(0, w - 1); val iy = cy.toInt().coerceIn(0, h - 1)
        var sl = 0f; var sa = 0f; var sb = 0f; var count = 0
        for (y in max(0, iy - half)..min(h - 1, iy + half)) for (x in max(0, ix - half)..min(w - 1, ix + half)) {
            val c = px[y * w + x]
            sl += lightness(c); sa += axisA(c); sb += axisB(c); count++
        }
        val rl = sl / count; val ra = sa / count; val rb = sb / count
        val tol = 0.05f + range.coerceIn(0f, 1f) * 0.4f
        val inv = 1f / (tol * tol)
        val m = FloatArray(w * h)
        val x0 = max(0, (cx - r).toInt() - 1); val x1 = min(w - 1, (cx + r).toInt() + 1)
        val y0 = max(0, (cy - r).toInt() - 1); val y1 = min(h - 1, (cy + r).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            val d = sqrt(dx * dx + dy * dy) / r
            if (d >= 1f) continue
            val space = 1f - smooth(((d - 0.3f) / 0.7f).coerceIn(0f, 1f))
            val c = px[y * w + x]
            val dl = lightness(c) - rl; val da = axisA(c) - ra; val db = axisB(c) - rb
            val dist2 = dl * dl + 1.5f * (da * da + db * db)
            m[y * w + x] = space * exp(-dist2 * inv)
        }
        // One pixel of smoothing at the analysis size: the colour test is per pixel, and noise is too.
        return blur(m, w, h, 1)
    }

    /**
     * The sky, by heuristic: bright and blue, or bright and nearly white; smooth, because sky
     * has no texture; and reaching the top edge through other such pixels, because a blue shirt
     * at the bottom of the frame is not sky. The edge is then softened and kept to the sky's
     * own colours, so leaves against it stay out.
     */
    fun sky(px: IntArray, w: Int, h: Int, check: () -> Unit = {}): FloatArray {
        val n = w * h
        val lum = FloatArray(n); val colour = FloatArray(n)
        // A sky is brighter than most of what is under it: the picture's own middle and upper brightness.
        val sorted = FloatArray(n) { val c = px[it]; 0.2126f * Argb.red(c) / 255f + 0.7152f * Argb.green(c) / 255f + 0.0722f * Argb.blue(c) / 255f }.also { it.sort() }
        val mid = sorted[n / 2]; val upper = sorted[(n * 3) / 4]
        for (i in 0 until n) {
            val c = px[i]
            val r = Argb.red(c) / 255f; val g = Argb.green(c) / 255f; val b = Argb.blue(c) / 255f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx <= 0f) 0f else (mx - mn) / mx
            lum[i] = l
            val blue = step(0.02f, 0.14f, b - r) * step(0.18f, 0.42f, l) * (if (b >= g * 0.9f) 1f else 0.5f)
            val white = step(0.6f, 0.82f, l) * (1f - step(0.08f, 0.22f, sat))
            // Sunset and golden-hour glow: bright, of any warm hue. Overcast and mist: a mid grey.
            // Both only ever count through the flood from the top, so a bright wall below is safe.
            val glow = step(0.55f, 0.75f, l) * step(upper - 0.02f, upper + 0.06f, l) * 0.85f
            val grey = step(0.3f, 0.45f, l) * step(mid + 0.04f, mid + 0.12f, l) * (1f - step(0.12f, 0.26f, sat)) * 0.85f
            colour[i] = max(max(blue, white), max(glow, grey))
        }
        check()
        // Texture: the brightness gradient, averaged over a few pixels.
        val grad = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val l = lum[y * w + min(w - 1, x + 1)] - lum[y * w + max(0, x - 1)]
            val u = lum[min(h - 1, y + 1) * w + x] - lum[max(0, y - 1) * w + x]
            grad[y * w + x] = abs(l) + abs(u)
        }
        val tex = blur(grad, w, h, max(1, min(w, h) / 200))
        // Smooth for this picture: a sharpened or detailed picture has more texture everywhere,
        // its sky included, so the gate follows the picture's own smoothest quarter.
        val calm = tex.copyOf().also { it.sort() }[n / 4]
        val texLo = max(0.025f, calm * 1.3f); val texHi = max(0.09f, calm * 3.2f)
        val score = FloatArray(n) { colour[it] * (1f - step(texLo, texHi, tex[it])) }
        // Reaching the top: a flood from the top row through sky-like pixels.
        val region = FloatArray(n)
        val queue = IntArray(n); var head = 0; var tail = 0
        // Seeds in the top few rows, not only the first: a branch or a spire often owns the very top.
        val seedRows = max(1, (h * 0.06f).roundToInt())
        // A row seeds only where sky spans a fair part of it: a sky is wide, a shirt or a sign is not.
        for (y in 0 until min(h, seedRows)) {
            var count = 0
            for (x in 0 until w) if (score[y * w + x] > 0.45f) count++
            if (y > 0 && count < w * 0.25f) continue
            for (x in 0 until w) { val i = y * w + x; if (score[i] > 0.45f && region[i] == 0f) { region[i] = 1f; queue[tail++] = i } }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            fun visit(j: Int) { if (region[j] == 0f && score[j] > 0.25f) { region[j] = 1f; queue[tail++] = j } }
            if (x > 0) visit(i - 1); if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w); if (y < h - 1) visit(i + w)
        }
        check()
        // A sky stays up. A bright, plain region that also spreads across the bottom of the frame
        // is a wall, a ceiling seen from below or a sheet, unless it fills nearly the whole picture
        // (a photo of the sky alone).
        val bandFrom = h - max(1, (h * 0.12f).roundToInt())
        var low = 0; var all = 0
        for (i in 0 until n) if (region[i] > 0f) { all++; if (i / w >= bandFrom) low++ }
        if (all < n * 0.9f && low > (h - bandFrom) * w * 0.3f) return FloatArray(n)
        // A sky sits above the land. Plain bright colour holding much of the lower half (a white
        // wall down a corridor, a kitchen's tiles) is a wall, unless it is truly blue: a sky
        // reflected in water or seen down to a low horizon still reads blue.
        var lowerHalf = 0; var blueCount = 0
        for (i in 0 until n) if (region[i] > 0f) {
            if (i / w >= h / 2) lowerHalf++
            val c = px[i]
            if (Argb.blue(c) - Argb.red(c) > 18) blueCount++
        }
        var topRow = 0
        for (y in 0 until max(1, h / 50)) for (x in 0 until w) if (region[y * w + x] > 0f) topRow++
        val topShare = topRow.toFloat() / (w * max(1, h / 50))
        if (all < n * 0.9f && lowerHalf > all * 0.3f && blueCount < all * 0.5f) return FloatArray(n)
        // An overcast, misty or sunset sky (no blue to vouch for it) must also hold most of the top
        // edge and stay up: an indoor wall, cabinets or a lit corridor reach the top in patches.
        if (all < n * 0.9f && blueCount < all * 0.15f && (topShare < 0.5f || lowerHalf > all * 0.2f)) return FloatArray(n)
        val soft = blur(region, w, h, max(1, (0.006f * min(w, h)).roundToInt()))
        for (i in 0 until n) soft[i] = (soft[i] * step(0.1f, 0.45f, colour[i]) * 1.25f).coerceIn(0f, 1f)
        return soft
    }

    // ------------------------------------------------------------------ helpers

    /** Bilinear, corner-aligned like [Segment.resample]: a small map drawn large. */
    fun upsample(src: FloatArray, sw: Int, sh: Int, outW: Int, outH: Int): FloatArray {
        if (sw == outW && sh == outH) return src.copyOf()
        val out = FloatArray(outW * outH)
        val sx = (sw - 1).toFloat() / max(1, outW - 1); val sy = (sh - 1).toFloat() / max(1, outH - 1)
        for (y in 0 until outH) {
            val fy = y * sy; val y0 = fy.toInt().coerceIn(0, sh - 1); val y1 = min(sh - 1, y0 + 1); val wy = fy - y0
            for (x in 0 until outW) {
                val fx = x * sx; val x0 = fx.toInt().coerceIn(0, sw - 1); val x1 = min(sw - 1, x0 + 1); val wx = fx - x0
                val top = src[y0 * sw + x0] * (1 - wx) + src[y0 * sw + x1] * wx
                val bottom = src[y1 * sw + x0] * (1 - wx) + src[y1 * sw + x1] * wx
                out[y * outW + x] = top * (1 - wy) + bottom * wy
            }
        }
        return out
    }

    /** Two passes of a separable box: close enough to a Gaussian for a mask. */
    fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        var a = src.copyOf(); var t = FloatArray(src.size)
        repeat(2) {
            boxPass(a, t, w, h, r, true); boxPass(t, a, w, h, r, false)
        }
        return a
    }

    private fun boxPass(input: FloatArray, output: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        for (o in 0 until lines) {
            fun idx(i: Int) = if (horizontal) o * w + i else i * w + o
            var sum = 0f; var count = 0
            for (i in 0..min(length - 1, r)) { sum += input[idx(i)]; count++ }
            for (i in 0 until length) {
                output[idx(i)] = sum / count
                val add = i + r + 1; val drop = i - r
                if (add < length) { sum += input[idx(add)]; count++ }
                if (drop >= 0) { sum -= input[idx(drop)]; count-- }
            }
        }
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)
    private fun step(lo: Float, hi: Float, v: Float): Float = smooth(((v - lo) / (hi - lo)).coerceIn(0f, 1f))

    private fun lightness(c: Int) = (0.2126f * Argb.red(c) + 0.7152f * Argb.green(c) + 0.0722f * Argb.blue(c)) / 255f
    private fun axisA(c: Int) = (Argb.red(c) - Argb.green(c)) / 255f * 0.5f
    private fun axisB(c: Int) = ((Argb.red(c) + Argb.green(c)) * 0.5f - Argb.blue(c)) / 255f * 0.5f

    /** The mask over [pixels] in red, for "Show mask": a wash as strong as the mask. */
    fun paint(pixels: IntArray, w: Int, h: Int, grid: Grid) {
        for (y in 0 until h) for (x in 0 until w) {
            val m = grid.at(x, y, w, h) * 0.6f
            if (m <= 0.004f) continue
            val i = y * w + x
            val c = pixels[i]
            pixels[i] = Argb.pack(Argb.alpha(c), (Argb.red(c) + (240 - Argb.red(c)) * m).roundToInt().coerceIn(0, 255),
                (Argb.green(c) + (30 - Argb.green(c)) * m).roundToInt().coerceIn(0, 255), (Argb.blue(c) + (50 - Argb.blue(c)) * m).roundToInt().coerceIn(0, 255))
        }
    }
}

/**
 * Applying local adjustments, in the order they were added, each over what the ones before it
 * made. For each: the whole picture is adjusted once (the per-pixel sliders through the same
 * [TonePipeline] the global sliders use, then structure and sharpness through [Spatial] with
 * its resolution-scaled radii), and the result is mixed into the picture by the mask.
 */
object LocalAdjustments {

    /** Applies [locals] to [pixels] in place. [maskFor] draws a mask for this picture size (the renderer caches them). */
    fun apply(pixels: IntArray, w: Int, h: Int, locals: List<LocalAdjust>, maskFor: (LocalMask) -> LocalMasks.Grid, check: () -> Unit = {}) {
        for (local in locals) {
            if (!local.visible || local.adjustsNothing) continue
            check()
            val grid = maskFor(local.mask)
            if (grid.empty) continue
            val adjusted = adjust(pixels, w, h, local, check)
            check()
            Parallel.bands(h) { from, to -> for (y in from until to) {
                for (x in 0 until w) {
                    val m = grid.at(x, y, w, h)
                    if (m <= 0f) continue
                    val i = y * w + x
                    val p = pixels[i]; val q = adjusted[i]
                    if (m >= 1f) { pixels[i] = (p and 0xFF000000.toInt()) or (q and 0x00FFFFFF); continue }
                    fun ch(shift: Int): Int { val a = (p shr shift) and 0xFF; val b = (q shr shift) and 0xFF; return (a + (b - a) * m + 0.5f).toInt().coerceIn(0, 255) }
                    pixels[i] = (p and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            } }
        }
    }

    /** The whole picture with [local]'s sliders applied everywhere. */
    fun adjust(pixels: IntArray, w: Int, h: Int, local: LocalAdjust, check: () -> Unit = {}): IntArray {
        var out = pixels.copyOf()
        val pipeline = TonePipeline.build(local.pointTone(), Curves(), Look.NONE, 0f)
        if (!pipeline.isIdentity) Parallel.bands(h) { from, to -> pipeline.apply(out, w, from, to) }
        if (local.clarity != 0f || local.sharpness > 0f) {
            out = Spatial.detail(out, w, h, Tone(clarity = local.clarity, sharpness = max(0f, local.sharpness)), check)
        }
        if (local.sharpness < 0f) {
            val scale = Geometry2D.resolutionScale(Geometry2D.Size(w, h))
            val radius = max(1, (-local.sharpness * 12f * scale).roundToInt())
            out = Spatial.boxBlur(out, w, h, radius, passes = 2, check = check)
        }
        return out
    }
}
