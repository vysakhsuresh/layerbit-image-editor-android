package com.layerbit.still.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A designed frame around the picture. [PLAIN] is the padding, border and corners [Frame] has
 * always had; every other style is drawn here, from nothing but arithmetic: no frame is a
 * bundled image, so each one is sharp at any size and the same shape at every size.
 *
 * [variants] is how many colour or texture choices the style offers, [seeded] says a seed
 * changes it (a different tear, a different grain), [caption] that it has room for words,
 * [paper] that [Frame.borderColor] is its paper or mat, and [showsBackground] that
 * [Frame.background] shows even without padding.
 */
enum class FrameStyle(
    val variants: Int = 1,
    val seeded: Boolean = false,
    val caption: Boolean = false,
    val paper: Boolean = false,
    val showsBackground: Boolean = false
) {
    PLAIN,
    POLAROID(caption = true, paper = true),
    INSTANT(variants = 2, caption = true, paper = true, showsBackground = true),
    FILM(variants = 2, seeded = true, showsBackground = true),
    SLIDE(variants = 3, showsBackground = true),
    GALLERY(variants = 3, paper = true),
    LINE(paper = true),
    MAGAZINE(variants = 2, caption = true, paper = true),
    SHADOW(showsBackground = true),
    FADE(showsBackground = true),
    TORN(seeded = true, paper = true, showsBackground = true),
    BRUSH(seeded = true, paper = true),
    STAMP(paper = true, showsBackground = true),
    NEON(variants = 2, showsBackground = true),
    WOOD(variants = 3, seeded = true),
    MARBLE(variants = 3, seeded = true),
    LINEN(variants = 3, seeded = true)
}

/** A rectangle in output pixels, for the caption the renderer writes. */
data class FrameBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Where everything goes on the framed canvas, in output pixels. [unit] is the picture's shorter
 * edge: every size a style has is a fraction of it, so a frame chosen on the preview is the same
 * frame on the export.
 */
class FrameLayout(
    val width: Int, val height: Int, val unit: Float,
    val cardLeft: Int, val cardTop: Int, val cardRight: Int, val cardBottom: Int,
    val pictureLeft: Int, val pictureTop: Int, val pictureWidth: Int, val pictureHeight: Int,
    /** Where the caption goes, or null for a style without one. */
    val caption: FrameBox?,
    /** Centred under the picture (instant prints), or set left (a magazine). */
    val captionCentred: Boolean
) {
    val size: Geometry2D.Size get() = Geometry2D.Size(width, height)
    val pictureRight: Int get() = pictureLeft + pictureWidth
    val pictureBottom: Int get() = pictureTop + pictureHeight
}

object FrameStyles {

    /** The frame a style starts as when picked from the strip: colours chosen to suit it, the user's aspect and words kept. */
    fun preset(style: FrameStyle, current: Frame = Frame()): Frame {
        val keep = Frame(aspect = current.aspect, caption = current.caption, seed = current.seed)
        fun c(v: Long) = v.toInt()
        return when (style) {
            FrameStyle.PLAIN -> keep.copy(padding = 0.05f, background = c(0xFFFFFFFF))
            FrameStyle.POLAROID -> keep.copy(style = style, borderColor = c(0xFFFBFAF6), background = c(0xFFE7E3DC))
            FrameStyle.INSTANT -> keep.copy(style = style, borderColor = c(0xFFFDFDFB), background = c(0xFFD8D2C7))
            FrameStyle.FILM -> keep.copy(style = style, background = c(0xFFF2F0EA))
            FrameStyle.SLIDE -> keep.copy(style = style, background = c(0xFF1E1E22))
            FrameStyle.GALLERY -> keep.copy(style = style, borderColor = c(0xFFF7F5F0))
            FrameStyle.LINE -> keep.copy(style = style, borderColor = c(0xFFFFFFFF))
            FrameStyle.MAGAZINE -> keep.copy(style = style, borderColor = c(0xFFF8F6F1))
            FrameStyle.SHADOW -> keep.copy(style = style, background = c(0xFFEDEBE6), cornerRadius = 0.04f)
            FrameStyle.FADE -> keep.copy(style = style, background = c(0xFFFFFFFF))
            FrameStyle.TORN -> keep.copy(style = style, borderColor = c(0xFFFBFAF7), background = c(0xFF2A2A2E))
            FrameStyle.BRUSH -> keep.copy(style = style, borderColor = c(0xFFF7F4EE))
            FrameStyle.STAMP -> keep.copy(style = style, borderColor = c(0xFFFFFFFF), background = c(0xFF2E3440))
            FrameStyle.NEON -> keep.copy(style = style, background = c(0xFF0B0B12), borderColor = c(0xFFFF4FA3))
            FrameStyle.WOOD, FrameStyle.MARBLE, FrameStyle.LINEN -> keep.copy(style = style)
        }
    }

    // ------------------------------------------------------------------ layout

    /** Margins left, top, right, bottom and the backdrop around the card, in shorter edges. */
    private fun margins(style: FrameStyle, k: Float, landscape: Boolean): FloatArray = when (style) {
        FrameStyle.POLAROID -> (0.045f + 0.05f * k).let { floatArrayOf(it, it, it, it * 3.4f, 0f) }
        FrameStyle.INSTANT -> (0.055f + 0.04f * k).let { floatArrayOf(it, it, it, it * 2.3f, 0.07f) }
        FrameStyle.FILM -> {
            val band = 0.13f + 0.07f * k; val end = 0.03f
            if (landscape) floatArrayOf(end, band, end, band, 0f) else floatArrayOf(band, end, band, end, 0f)
        }
        FrameStyle.GALLERY -> (0.10f + 0.18f * k).let { floatArrayOf(it, it, it, it * 1.08f, 0f) }
        FrameStyle.LINE -> (0.05f + 0.08f * k).let { floatArrayOf(it, it, it, it, 0f) }
        FrameStyle.MAGAZINE -> (0.07f + 0.05f * k).let { floatArrayOf(it, it * 1.5f, it, it * 2.4f, 0f) }
        FrameStyle.SHADOW -> floatArrayOf(0f, 0f, 0f, 0f, 0.07f + 0.08f * k)
        FrameStyle.FADE -> floatArrayOf(0f, 0f, 0f, 0f, 0f)
        FrameStyle.TORN -> (0.05f + 0.07f * k).let { floatArrayOf(it, it, it, it, 0.015f) }
        FrameStyle.BRUSH -> (0.05f + 0.05f * k).let { floatArrayOf(it, it, it, it, 0f) }
        FrameStyle.STAMP -> (0.05f + 0.04f * k).let { floatArrayOf(it, it, it, it, 0.045f) }
        FrameStyle.NEON -> floatArrayOf(0f, 0f, 0f, 0f, 0.10f + 0.08f * k)
        FrameStyle.WOOD, FrameStyle.MARBLE, FrameStyle.LINEN -> (0.08f + 0.14f * k).let { floatArrayOf(it, it, it, it, 0f) }
        FrameStyle.PLAIN, FrameStyle.SLIDE -> floatArrayOf(0f, 0f, 0f, 0f, 0f)
    }

    /** The canvas [f] makes around a [w] x [h] picture, and where the card, picture and caption sit on it. */
    fun layout(w: Int, h: Int, f: Frame): FrameLayout {
        require(w > 0 && h > 0)
        val s = min(w, h).toFloat()
        val k = f.thickness.coerceIn(0f, 1f)
        fun px(v: Float) = (v * s).roundToInt()
        val ml: Int; val mt: Int; val mr: Int; val mb: Int; val outer: Int
        when (f.style) {
            FrameStyle.PLAIN -> { ml = 0; mt = 0; mr = 0; mb = 0; outer = px(f.padding + f.border) }
            FrameStyle.SLIDE -> {
                // A square mount, whatever the picture's shape.
                val c = max(w, h) + 2 * px(0.16f + 0.10f * k)
                ml = (c - w) / 2; mr = c - w - ml; mt = (c - h) / 2; mb = c - h - mt
                outer = px(0.05f) + px(f.padding)
            }
            else -> {
                val m = margins(f.style, k, w >= h)
                ml = px(m[0]); mt = px(m[1]); mr = px(m[2]); mb = px(m[3]); outer = px(m[4]) + px(f.padding)
            }
        }
        val cardW = ml + w + mr; val cardH = mt + h + mb
        var width = cardW + 2 * outer; var height = cardH + 2 * outer
        if (f.aspect > 0f) {
            if (width.toFloat() / height < f.aspect) width = (height * f.aspect).roundToInt() else height = (width / f.aspect).roundToInt()
        }
        width = max(width, cardW); height = max(height, cardH)
        val cl = (width - cardW) / 2; val ct = (height - cardH) / 2
        val pl = cl + ml; val pt = ct + mt
        val caption = when (f.style) {
            FrameStyle.POLAROID, FrameStyle.INSTANT -> FrameBox(pl.toFloat(), pt + h + mb * 0.18f, (pl + w).toFloat(), pt + h + mb * 0.82f)
            FrameStyle.MAGAZINE -> FrameBox(pl.toFloat(), pt + h + mb * 0.16f, (pl + w).toFloat(), pt + h + mb * 0.46f)
            else -> null
        }
        return FrameLayout(width, height, s, cl, ct, cl + cardW, ct + cardH, pl, pt, w, h, caption, f.style != FrameStyle.MAGAZINE)
    }

    /**
     * How far in from the picture's edge a style may change it: the soft edge of Fade and Brush,
     * the rounded corners of Shadow and Slide. Everything further in is the picture, untouched.
     */
    fun pictureInset(f: Frame, l: FrameLayout): Int = when (f.style) {
        FrameStyle.BRUSH -> ceil(brushBand(f) * l.unit).toInt() + 1
        // Twice the band: the fade's rounded corners reach that far in along the diagonal.
        FrameStyle.FADE -> ceil(2f * fadeBand(f) * l.unit).toInt() + 1
        FrameStyle.SHADOW -> ceil(f.cornerRadius * l.unit).toInt() + 1
        FrameStyle.SLIDE -> ceil(SLIDE_WINDOW_RADIUS * l.unit).toInt() + 1
        else -> 0
    }

    /** Ink for the caption: dark on light paper, light on dark. */
    fun captionInk(f: Frame): Int = if (Argb.luma(f.borderColor) > 140) 0xFF2A2724.toInt() else 0xFFF2F0EA.toInt()

    private fun brushBand(f: Frame) = 0.03f + 0.05f * f.thickness.coerceIn(0f, 1f)
    private fun fadeBand(f: Frame) = 0.06f + 0.22f * f.thickness.coerceIn(0f, 1f)
    private const val SLIDE_WINDOW_RADIUS = 0.025f

    // ------------------------------------------------------------------ render

    /**
     * The framed canvas for a [w] x [h] [picture], as [layout] places it. [backdrop], when given,
     * is a [FrameLayout.width] x [FrameLayout.height] fill to use instead of [Frame.background]
     * (the blurred copy of the picture). Deterministic: the same picture, frame and seed give the
     * same pixels.
     */
    fun render(picture: IntArray, w: Int, h: Int, f: Frame, backdrop: IntArray? = null, check: () -> Unit = {}): IntArray {
        require(picture.size == w * h)
        val l = layout(w, h, f)
        val out = if (backdrop != null) {
            require(backdrop.size == l.width * l.height); backdrop.copyOf()
        } else IntArray(l.width * l.height).also { it.fill(f.background) }
        val p = Painter(out, l.width, l.height, l, picture, f)
        check()
        when (f.style) {
            FrameStyle.PLAIN -> p.picture(null)
            FrameStyle.POLAROID -> { p.card(f.borderColor); p.lip(0.006f, 0.10f); p.picture(null) }
            FrameStyle.INSTANT -> instant(p)
            FrameStyle.FILM -> film(p)
            FrameStyle.SLIDE -> slide(p)
            FrameStyle.GALLERY -> gallery(p)
            FrameStyle.LINE -> line(p)
            FrameStyle.MAGAZINE -> magazine(p)
            FrameStyle.SHADOW -> shadowStyle(p)
            FrameStyle.FADE -> fade(p)
            FrameStyle.TORN -> torn(p)
            FrameStyle.BRUSH -> brush(p)
            FrameStyle.STAMP -> stamp(p)
            FrameStyle.NEON -> neon(p)
            FrameStyle.WOOD, FrameStyle.MARBLE, FrameStyle.LINEN -> texturedMat(p)
        }
        check()
        return out
    }

    // ------------------------------------------------------------------ styles

    private fun instant(p: Painter) {
        val l = p.l; val s = l.unit
        val r = 0.015f * s
        p.shadow(l.cardLeft.toFloat(), l.cardTop.toFloat(), l.cardRight.toFloat(), l.cardBottom.toFloat(), r, 0.012f * s, 0.035f * s, 0.30f)
        p.roundCard(p.f.borderColor, r)
        p.lip(0.005f, 0.10f)
        p.picture(null)
        // Two strips of tape across the top corners.
        tape(p, l.cardLeft.toFloat(), l.cardTop.toFloat(), 1f)
        tape(p, l.cardRight.toFloat(), l.cardTop.toFloat(), -1f)
    }

    private fun tape(p: Painter, cx: Float, cy: Float, side: Float) {
        val s = p.l.unit
        val hl = 0.12f * s; val hw = 0.035f * s; val jag = 0.010f * s
        // Along the tape: across the corner, perpendicular to the diagonal.
        val ux = 0.7071f; val uy = -0.7071f * side
        val nx = 0.7071f * side; val ny = 0.7071f
        val clear = p.f.variant == 1
        val colour = if (clear) 0xFFFFFFFF.toInt() else 0xFFDCCB9E.toInt()
        val alpha = if (clear) 0.42f else 0.90f
        val seed = p.f.seed * 31 + if (side > 0) 7 else 11
        val reach = hl + hw + jag + 2f
        p.each(floor(cx - reach).toInt(), floor(cy - reach).toInt(), ceil(cx + reach).toInt(), ceil(cy + reach).toInt()) { x, y, i ->
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            val a = dx * ux + dy * uy; val b = dx * nx + dy * ny
            // Torn ends: the length wanders with the position across the tape.
            val end = hl + jag * (noise(b / s * 60f, if (a > 0) 1f else 5f, seed) - 0.5f) * 2f
            val cov = clamp(min(hw - abs(b), end - abs(a)) + 0.5f)
            if (cov > 0f) {
                val grain = 0.96f + 0.08f * noise(a / s * 40f, b / s * 40f, seed + 3)
                var c = shade(colour, grain)
                if (clear && hw - abs(b) < 0.004f * s) c = 0xFFFFFFFF.toInt()
                p.out[i] = over(p.out[i], c, cov * alpha)
            }
        }
    }

    private fun film(p: Painter) {
        val l = p.l; val f = p.f
        val landscape = l.pictureWidth >= l.pictureHeight
        val base = if (f.variant == 1) 0xFF4A2412.toInt() else 0xFF121110.toInt()
        val ink = if (f.variant == 1) 0xFFF0C080.toInt() else 0xFFD9A441.toInt()
        val band = (if (landscape) l.pictureTop - l.cardTop else l.pictureLeft - l.cardLeft).toFloat()
        val length = (if (landscape) l.cardRight - l.cardLeft else l.cardBottom - l.cardTop).toFloat()
        val holeAlong = band * 0.26f; val holeAcross = band * 0.36f; val holeRadius = band * 0.07f
        val holeAt = band * 0.42f
        val pitch = band * 0.62f
        val count = max(1, floor(length / pitch).toInt())
        val start = (length - count * pitch) / 2f
        p.each(l.cardLeft, l.cardTop, l.cardRight, l.cardBottom) { x, y, i ->
            val px = x + 0.5f; val py = y + 0.5f
            // Along the strip and in from its nearer outer edge.
            val along = if (landscape) px - l.cardLeft else py - l.cardTop
            val inFromA = if (landscape) py - l.cardTop else px - l.cardLeft
            val inFromB = if (landscape) l.cardBottom - py else l.cardRight - px
            val d = min(inFromA, inFromB)
            var cov = 1f
            if (d < band) {
                val n = floor((along - start) / pitch).toInt().coerceIn(0, count - 1)
                val c = start + (n + 0.5f) * pitch
                val hole = roundRect(along, d, c - holeAlong / 2, holeAt - holeAcross / 2, c + holeAlong / 2, holeAt + holeAcross / 2, holeRadius)
                cov = clamp(hole + 0.5f)
            }
            p.out[i] = over(p.out[i], base, cov)
        }
        p.picture(null)
        // Edge print: a frame number with its half-frame and arrow on one edge, the stock's name on the other.
        val g = band * 0.04f
        val number = 1 + Math.floorMod(f.seed, 36)
        val nearInner = band * 0.78f
        fun print(text: String, along: Float, farEdge: Boolean) {
            // The text's middle sits nearInner from the strip's outer edge, between the holes and the picture.
            if (landscape) {
                val y = if (farEdge) l.cardBottom - nearInner - 2.5f * g else l.cardTop + nearInner - 2.5f * g
                p.text(text, l.cardLeft + along, y, g, ink, 0.85f, rotated = false)
            } else {
                val x = if (farEdge) l.cardRight - nearInner - 2.5f * g else l.cardLeft + nearInner - 2.5f * g
                p.text(text, x, l.cardTop + along, g, ink, 0.85f, rotated = true)
            }
        }
        print("$number >", length * 0.12f, true)
        print("${number}A", length * 0.62f, true)
        print("STILL 400", length * 0.22f, false)
    }

    private fun slide(p: Painter) {
        val l = p.l; val s = l.unit
        val card = when (p.f.variant) { 1 -> 0xFFD8C7A6.toInt(); 2 -> 0xFF1C1C1E.toInt(); else -> 0xFFF4F3EF.toInt() }
        val cr = 0.05f * s
        p.shadow(l.cardLeft.toFloat(), l.cardTop.toFloat(), l.cardRight.toFloat(), l.cardBottom.toFloat(), cr, 0.012f * s, 0.03f * s, 0.35f)
        p.roundCard(card, cr)
        // The window's raised rim, shaded where the mount bends down to the film.
        val rw = SLIDE_WINDOW_RADIUS * s; val rim = 0.02f * s
        val wl = l.pictureLeft.toFloat(); val wt = l.pictureTop.toFloat(); val wr = l.pictureRight.toFloat(); val wb = l.pictureBottom.toFloat()
        p.each(l.pictureLeft - rim.toInt() - 2, l.pictureTop - rim.toInt() - 2, l.pictureRight + rim.toInt() + 2, l.pictureBottom + rim.toInt() + 2) { x, y, i ->
            val d = roundRect(x + 0.5f, y + 0.5f, wl, wt, wr, wb, rw)
            if (d in 0f..rim) p.out[i] = shade(p.out[i], 0.82f + 0.18f * (d / rim))
        }
        p.picture { x, y -> clamp(0.5f - roundRect(x + 0.5f, y + 0.5f, 0f, 0f, l.pictureWidth.toFloat(), l.pictureHeight.toFloat(), rw)) }
        val ink = if (Argb.luma(card) > 128) 0xFF77736B.toInt() else 0xFFB8B4AC.toInt()
        val g = 0.012f * s
        val y = (l.pictureBottom + l.cardBottom) / 2f - 2.5f * g
        p.text("STILL", l.cardLeft + (l.cardRight - l.cardLeft) * 0.14f, y, g, ink, 1f, rotated = false)
        p.text("${1 + Math.floorMod(p.f.seed, 36)}", l.cardRight - (l.cardRight - l.cardLeft) * 0.14f - 8 * g, y, g, ink, 1f, rotated = false)
    }

    private fun gallery(p: Painter) {
        val l = p.l; val s = l.unit
        val mat = p.f.borderColor
        val accent = when (p.f.variant) {
            1 -> Palette.matched(p.picture).getOrNull(1) ?: 0xFF2B3A4A.toInt()
            2 -> 0xFFB8963E.toInt()
            else -> 0xFF1A1A1A.toInt()
        }
        val a = max(1, (0.014f * s).roundToInt()); val bevel = max(1, (0.007f * s).roundToInt())
        val light = mix(mat, 0xFFFFFFFF.toInt(), 0.6f); val dark = shade(mat, 0.84f)
        p.each(l.cardLeft, l.cardTop, l.cardRight, l.cardBottom) { x, y, i ->
            val dx = max(l.pictureLeft - x, x - (l.pictureRight - 1)); val dy = max(l.pictureTop - y, y - (l.pictureBottom - 1))
            val d = max(dx, dy)
            p.out[i] = when {
                d <= 0 -> p.out[i]
                d <= a -> accent
                d <= a + bevel -> if ((dy >= dx && y < l.pictureTop) || (dx > dy && x < l.pictureLeft)) light else dark
                else -> mat
            }
        }
        p.picture(null)
    }

    private fun line(p: Painter) {
        val l = p.l; val s = l.unit
        p.card(p.f.borderColor)
        val margin = l.pictureLeft - l.cardLeft
        val gap = margin * 0.42f; val width = max(0.6f, 0.004f * s)
        val ink = if (Argb.luma(p.f.borderColor) > 140) 0xFF2B2926.toInt() else 0xFFEDEBE6.toInt()
        p.ring(gap, width, ink, 0.85f)
        p.picture(null)
    }

    private fun magazine(p: Painter) {
        val l = p.l; val s = l.unit
        p.card(p.f.borderColor)
        val ink = if (p.f.variant == 1) Palette.matched(p.picture).getOrNull(1) ?: 0xFF1C1B19.toInt()
            else if (Argb.luma(p.f.borderColor) > 140) 0xFF1C1B19.toInt() else 0xFFEDEBE6.toInt()
        val thin = max(1f, 0.003f * s)
        val top = l.pictureTop - (l.pictureTop - l.cardTop) * 0.45f
        p.rect(l.pictureLeft.toFloat(), top, l.pictureRight.toFloat(), top + thin, ink, 1f)
        // A short heavy rule over the left end, the way a section starts.
        p.rect(l.pictureLeft.toFloat(), top - 3f * thin, l.pictureLeft + 0.12f * s, top, ink, 1f)
        p.picture(null)
    }

    private fun shadowStyle(p: Painter) {
        val l = p.l; val s = l.unit
        val r = p.f.cornerRadius * s
        val pl = l.pictureLeft.toFloat(); val pt = l.pictureTop.toFloat(); val pr = l.pictureRight.toFloat(); val pb = l.pictureBottom.toFloat()
        p.shadow(pl, pt, pr, pb, r, 0.022f * s, 0.05f * s, 0.32f)
        p.shadow(pl, pt, pr, pb, r, 0.004f * s, 0.008f * s, 0.18f)
        p.picture { x, y -> clamp(0.5f - roundRect(x + 0.5f, y + 0.5f, 0f, 0f, l.pictureWidth.toFloat(), l.pictureHeight.toFloat(), r)) }
    }

    private fun fade(p: Painter) {
        val l = p.l
        val band = fadeBand(p.f) * l.unit
        val w = l.pictureWidth.toFloat(); val h = l.pictureHeight.toFloat()
        val inner = min(band, min(w, h) / 2f - 1f).coerceAtLeast(1f)
        p.picture { x, y ->
            val sd = roundRect(x + 0.5f, y + 0.5f, inner, inner, w - inner, h - inner, inner)
            if (sd <= 0f) 1f else { val q = clamp(sd / inner); 1f - q * q * q * (q * (q * 6f - 15f) + 10f) }
        }
    }

    private fun torn(p: Painter) {
        val l = p.l; val f = p.f; val s = l.unit
        val amp = 0.035f * s
        fun depth(side: Int, n: Int, origin: Int): FloatArray = FloatArray(n) { i ->
            val a = (i + 0.5f - origin) / s
            amp * (0.6f * lodNoise(a * 6f, side * 7.3f, f.seed, s / 6f) + 0.28f * lodNoise(a * 24f, side * 3.1f, f.seed + 1, s / 24f) +
                0.12f * lodNoise(a * 80f, side * 5.7f, f.seed + 2, s / 80f))
        }
        val top = depth(0, l.width, l.cardLeft); val bottom = depth(1, l.width, l.cardLeft)
        val left = depth(2, l.height, l.cardTop); val right = depth(3, l.height, l.cardTop)
        val dy = 0.008f * s; val blur = 0.02f * s; val rim = 0.007f * s
        val paper = f.borderColor; val fibre = mix(paper, 0xFFFFFFFF.toInt(), 0.7f)
        fun edge(px: Float, py: Float, x: Int, y: Int): Float {
            val yi = y.coerceIn(0, l.height - 1)
            return min(min(py - l.cardTop - top[x], l.cardBottom - py - bottom[x]), min(px - l.cardLeft - left[yi], l.cardRight - px - right[yi]))
        }
        p.around(0, 0, l.width, l.height, l.pictureLeft + 3, l.pictureTop + 3, l.pictureRight - 2, l.pictureBottom - 2) { x, y, i ->
            val px = x + 0.5f; val py = y + 0.5f
            val e = edge(px, py, x, y)
            if (e < 0.5f) {
                // The paper's shadow, a little below it.
                val es = edge(px, py - dy, x, (py - dy).toInt())
                val a = 0.30f * (1f - smooth(-blur, blur * 0.4f, -es))
                if (a > 0.003f) p.out[i] = over(p.out[i], 0xFF000000.toInt(), a)
            }
            val cov = clamp(e + 0.5f)
            if (cov > 0f) {
                // Torn paper shows its lighter, fibrous core along the tear.
                val c = if (e < rim) mix(fibre, paper, clamp(e / rim)) else paper
                p.out[i] = over(p.out[i], c, cov * if (e < rim) 0.92f else 1f)
            }
        }
        p.picture(null)
    }

    private fun brush(p: Painter) {
        val l = p.l; val f = p.f; val s = l.unit
        p.card(f.borderColor)
        val band = brushBand(f) * s
        val soft = max(1f, band * 0.08f)
        fun threshold(side: Int, n: Int): FloatArray = FloatArray(n) { i ->
            val a = (i + 0.5f) / s
            band * (0.10f + 0.78f * (0.55f * lodNoise(a * 5f, side * 4.1f, f.seed, s / 5f) + 0.30f * lodNoise(a * 40f, side * 2.3f, f.seed + 1, s / 40f) +
                0.15f * lodNoise(a * 160f, side * 6.7f, f.seed + 2, s / 160f)))
        }
        val w = l.pictureWidth; val h = l.pictureHeight
        val tl = threshold(0, h); val tr = threshold(1, h); val tt = threshold(2, w); val tb = threshold(3, w)
        p.picture { x, y ->
            val px = x + 0.5f; val py = y + 0.5f
            if (px > band && py > band && w - px > band && h - py > band) 1f
            else min(
                min(clamp((px - tl[y]) / soft + 0.5f), clamp((w - px - tr[y]) / soft + 0.5f)),
                min(clamp((py - tt[x]) / soft + 0.5f), clamp((h - py - tb[x]) / soft + 0.5f))
            )
        }
    }

    private fun stamp(p: Painter) {
        val l = p.l; val s = l.unit
        val r = 0.02f * s
        val cl = l.cardLeft.toFloat(); val ct = l.cardTop.toFloat(); val cr = l.cardRight.toFloat(); val cb = l.cardBottom.toFloat()
        p.shadow(cl, ct, cr, cb, 0f, 0.008f * s, 0.02f * s, 0.30f)
        fun pitch(length: Float) = length / max(3, (length / (2.6f * r)).roundToInt())
        val px = pitch(cr - cl); val py = pitch(cb - ct)
        fun hole(along: Float, pitch: Float, across: Float): Float {
            val c = (floor(along / pitch) + 0.5f) * pitch
            val dx = along - c
            return sqrt(dx * dx + across * across) - r
        }
        p.each(l.cardLeft, l.cardTop, l.cardRight, l.cardBottom) { x, y, i ->
            val fx = x + 0.5f; val fy = y + 0.5f
            var d = min(min(fx - cl, cr - fx), min(fy - ct, cb - fy)) + 0.5f
            if (d < r + 2f) {
                d = min(d, min(min(hole(fx - cl, px, fy - ct), hole(fx - cl, px, cb - fy)), min(hole(fy - ct, py, fx - cl), hole(fy - ct, py, cr - fx))))
            }
            p.out[i] = over(p.out[i], p.f.borderColor, clamp(d + 0.5f))
        }
        p.picture(null)
    }

    /** The colour of a neon tube: the picture's strongest colour, lit; or the one the user picked. */
    fun neonColour(f: Frame, picture: IntArray): Int {
        if (f.variant == 1) return f.borderColor or 0xFF000000.toInt()
        val c = Palette.of(picture, 1).firstOrNull() ?: return 0xFFFF4FA3.toInt()
        val hsv = Palette.hsv(Argb.red(c), Argb.green(c), Argb.blue(c))
        return Palette.rgb(hsv[0], 0.78f, 1f)
    }

    private fun neon(p: Painter) {
        val l = p.l; val s = l.unit
        val colour = neonColour(p.f, p.picture)
        val core = mix(colour, 0xFFFFFFFF.toInt(), 0.55f)
        val gap = (0.025f + 0.02f * p.f.thickness.coerceIn(0f, 1f)) * s
        val tube = max(1f, 0.007f * s); val near = 0.012f * s; val far = 0.05f * s
        val tl = l.pictureLeft - gap; val tt = l.pictureTop - gap; val tr = l.pictureRight + gap; val tb = l.pictureBottom + gap
        val radius = 0.035f * s
        p.around(0, 0, l.width, l.height, l.pictureLeft + 1, l.pictureTop + 1, l.pictureRight - 1, l.pictureBottom - 1) { x, y, i ->
            val d = abs(roundRect(x + 0.5f, y + 0.5f, tl, tt, tr, tb, radius))
            val qn = d / near; val qf = d / far
            val glow = 0.6f * exp(-qn * qn) + 0.35f * exp(-qf * qf)
            if (glow > 0.003f) p.out[i] = over(p.out[i], colour, min(1f, glow))
            val cov = clamp(tube / 2f - d + 0.5f)
            if (cov > 0f) p.out[i] = over(p.out[i], core, cov)
        }
        p.picture(null)
    }

    private fun texturedMat(p: Painter) {
        val l = p.l; val f = p.f; val s = l.unit
        val v = f.variant.coerceIn(0, 2)
        val margin = (l.pictureLeft - l.cardLeft).toFloat().coerceAtLeast(1f)
        val lip = 0.012f * s
        p.around(l.cardLeft, l.cardTop, l.cardRight, l.cardBottom, l.pictureLeft, l.pictureTop, l.pictureRight, l.pictureBottom) { x, y, i ->
            val px = x + 0.5f; val py = y + 0.5f
            val dl = px - l.cardLeft; val dr = l.cardRight - px; val dt = py - l.cardTop; val db = l.cardBottom - py
            // Mitred like a real frame: each side's grain runs along that side.
            val side = when (min(min(dl, dr), min(dt, db))) { dt -> 0; db -> 1; dl -> 2; else -> 3 }
            val horizontal = side < 2
            val u = (px - l.cardLeft) / s; val w = (py - l.cardTop) / s
            val along = if (horizontal) u else w
            val across = if (horizontal) w else u
            val seed = f.seed * 97 + side * 13
            var c = when (f.style) {
                FrameStyle.WOOD -> wood(along, across, seed, s, v)
                FrameStyle.MARBLE -> marble(u, w, f.seed, s, v)
                else -> linen(u, w, f.seed, s, v)
            }
            // A moulding profile: a soft rise across the mat, a lip down to the picture.
            val dEdge = min(min(dl, dr), min(dt, db))
            val q = clamp(dEdge / margin)
            var light = 1f + 0.07f * sin(q * PI.toFloat()) - 0.10f * smooth(0.0f, 0.04f, 0.04f - q)
            val dx = max(l.pictureLeft - px, px - l.pictureRight); val dy = max(l.pictureTop - py, py - l.pictureBottom)
            val dPic = max(dx, dy)
            if (dPic < lip) { val t = 1f - dPic / lip; light -= 0.30f * t * t }
            if (f.style == FrameStyle.WOOD) {
                // The mitre's seam.
                val sorted = floatArrayOf(dl, dr, dt, db).also { it.sort() }
                if (sorted[1] - sorted[0] < 0.9f && dPic > 0.5f) light *= 0.78f
            }
            c = shade(c, light)
            p.out[i] = over(p.out[i], c, 1f)
        }
        p.picture(null)
    }

    private val WOOD = arrayOf(intArrayOf(0xFFC89B6A.toInt(), 0xFF8A5A33.toInt()), intArrayOf(0xFF7A5034.toInt(), 0xFF3E2716.toInt()), intArrayOf(0xFFE8DED0.toInt(), 0xFFB9A992.toInt()))
    private val MARBLE = arrayOf(intArrayOf(0xFFF1EFEB.toInt(), 0xFF8E8C8A.toInt()), intArrayOf(0xFF1B1B1D.toInt(), 0xFFD8D6D0.toInt()), intArrayOf(0xFF1F4D3D.toInt(), 0xFFE0E6DA.toInt()))
    private val LINEN = intArrayOf(0xFFD8CDB8.toInt(), 0xFFB7B5B0.toInt(), 0xFF2C3A55.toInt())

    /** Wood grain: rings bent by a slow wander, fine fibres along the grain. */
    fun wood(along: Float, across: Float, seed: Int, unit: Float, variant: Int): Int {
        val warp = fbm(along * 1.2f, across * 6f, seed, 3, unit / 6f)
        val ringPhase = across * 28f + warp * 3.5f + along * 0.15f
        val keep = lod(unit / 28f)
        val ring = 0.5f + keep * 0.5f * sin(2f * PI.toFloat() * ringPhase)
        val fibre = fbm(along * 2f, across * 90f, seed + 5, 2, unit / 90f)
        val t = 0.65f * ring * sqrt(ring) + 0.35f * fibre
        val c = WOOD[variant.coerceIn(0, 2)]
        return mix(c[1], c[0], t.coerceIn(0f, 1f))
    }

    /** Marble: veins where a turbulent sine crosses zero, over a faint cloud. */
    fun marble(u: Float, v: Float, seed: Int, unit: Float, variant: Int): Int {
        val turb = fbm(u * 1.6f, v * 1.6f, seed, 3, unit / 1.6f)
        val s1 = abs(sin(PI.toFloat() * (u * 1.3f + v * 0.8f + turb * 3.2f)))
        var vein = (1f - s1).let { val q = it * it; q * q * q }
        val turb2 = fbm(u * 4f + 7f, v * 4f, seed + 3, 4, unit / 4f)
        val s2 = abs(sin(PI.toFloat() * (u * 4f - v * 3f + turb2 * 5f)))
        vein = max(vein, 0.25f * (1f - s2).let { val q = it * it * it * it; q * q * q * q * q })
        val cloud = fbm(u * 5f, v * 5f, seed + 9, 3, unit / 5f)
        val c = MARBLE[variant.coerceIn(0, 2)]
        val base = mix(c[0], c[1], 0.12f * cloud)
        return mix(base, c[1], (vein * 0.6f).coerceIn(0f, 1f))
    }

    /** Linen: a fine plain weave with the slubs of real thread. */
    fun linen(u: Float, v: Float, seed: Int, unit: Float, variant: Int): Int {
        val f = 110f
        val keep = lod(unit / f)
        val wx = 0.5f + 0.5f * sin(2f * PI.toFloat() * (u * f + 0.6f * noise(v * 3f, u * 0.5f, seed)))
        val wy = 0.5f + 0.5f * sin(2f * PI.toFloat() * (v * f + 0.6f * noise(u * 3f, v * 0.5f, seed + 1)))
        val weave = keep * (0.5f * (wx + wy) - 0.5f) + 0.5f
        val slub = 0.5f * (fbm(u * 3f, v * 60f, seed + 2, 2, unit / 60f) + fbm(u * 60f, v * 3f, seed + 4, 2, unit / 60f))
        // A slow mottle, so even a small copy of the cloth is not a flat colour.
        val mottle = fbm(u * 4f, v * 4f, seed + 6, 2, unit / 4f)
        val light = 0.90f + 0.12f * weave + 0.10f * (slub - 0.5f) + 0.08f * (mottle - 0.5f)
        return shade(LINEN[variant.coerceIn(0, 2)], light)
    }

    // ------------------------------------------------------------------ painting

    fun interface Mask { fun at(x: Int, y: Int): Float }
    fun interface Pixel { fun at(x: Int, y: Int, i: Int) }

    private class Painter(val out: IntArray, val width: Int, val height: Int, val l: FrameLayout, val picture: IntArray, val f: Frame) {

        /** Calls [block] for every pixel of [x0, x1) x [y0, y1) on the canvas, in row bands across the cores. */
        fun each(x0: Int, y0: Int, x1: Int, y1: Int, block: Pixel) = around(x0, y0, x1, y1, 0, 0, 0, 0, block)

        /**
         * [each], skipping the hole [hx0, hx1) x [hy0, hy1): the inside of what is drawn over
         * opaquely afterwards, so an export does not shade millions of pixels nobody sees.
         */
        fun around(x0: Int, y0: Int, x1: Int, y1: Int, hx0: Int, hy0: Int, hx1: Int, hy1: Int, block: Pixel) {
            val ax = max(0, x0); val bx = min(width, x1); val ay = max(0, y0); val by = min(height, y1)
            if (ax >= bx || ay >= by) return
            val holed = hx1 > hx0 && hy1 > hy0
            Parallel.bands(by - ay) { from, to ->
                for (y in ay + from until ay + to) {
                    var x = ax
                    val skip = holed && y >= hy0 && y < hy1
                    while (x < bx) {
                        if (skip && x == max(ax, hx0)) { x = max(x, hx1); if (x >= bx) break }
                        block.at(x, y, y * width + x); x++
                    }
                }
            }
        }

        fun card(colour: Int) = each(l.cardLeft, l.cardTop, l.cardRight, l.cardBottom) { _, _, i -> out[i] = over(out[i], colour, 1f) }

        fun roundCard(colour: Int, radius: Float) {
            val cl = l.cardLeft.toFloat(); val ct = l.cardTop.toFloat(); val cr = l.cardRight.toFloat(); val cb = l.cardBottom.toFloat()
            each(l.cardLeft, l.cardTop, l.cardRight, l.cardBottom) { x, y, i ->
                out[i] = over(out[i], colour, clamp(0.5f - roundRect(x + 0.5f, y + 0.5f, cl, ct, cr, cb, radius)))
            }
        }

        /** The picture at its place, through [mask] in picture pixels when given. */
        fun picture(mask: Mask?) {
            val w = l.pictureWidth
            each(l.pictureLeft, l.pictureTop, l.pictureRight, l.pictureBottom) { x, y, i ->
                val px = x - l.pictureLeft; val py = y - l.pictureTop
                val src = picture[py * w + px]
                out[i] = if (mask == null) over(out[i], src, 1f) else over(out[i], src, mask.at(px, py))
            }
        }

        /** A soft drop shadow of a rounded rectangle, [dy] below it. */
        fun shadow(l0: Float, t0: Float, r0: Float, b0: Float, radius: Float, dy: Float, blur: Float, opacity: Float) {
            val reach = blur + 2f
            // Deep inside, the shape itself covers its shadow.
            val inset = ceil(radius + blur + abs(dy)).toInt() + 2
            around(floor(l0 - reach).toInt(), floor(t0 + dy - reach).toInt(), ceil(r0 + reach).toInt(), ceil(b0 + dy + reach).toInt(),
                l0.toInt() + inset, t0.toInt() + inset, r0.toInt() - inset, b0.toInt() - inset) { x, y, i ->
                val sd = roundRect(x + 0.5f, y + 0.5f - dy, l0, t0, r0, b0, radius)
                val a = opacity * (1f - smooth(-blur, blur, sd))
                if (a > 0.003f) out[i] = over(out[i], 0xFF000000.toInt(), a)
            }
        }

        /** Darkens the card just outside the picture, as if the print sat a little below it. */
        fun lip(width: Float, strength: Float) {
            val wpx = width * l.unit
            val reach = ceil(wpx).toInt() + 1
            each(l.pictureLeft - reach, l.pictureTop - reach, l.pictureRight + reach, l.pictureBottom + reach) { x, y, i ->
                val px = x + 0.5f; val py = y + 0.5f
                val d = max(max(l.pictureLeft - px, px - l.pictureRight), max(l.pictureTop - py, py - l.pictureBottom))
                if (d in 0f..wpx) { val t = 1f - d / wpx; out[i] = shade(out[i], 1f - strength * t * t) }
            }
        }

        /** A line [width] pixels wide, [gap] pixels outside the picture all round, with exact coverage at any width. */
        fun ring(gap: Float, width: Float, colour: Int, alpha: Float) {
            val reach = ceil(gap + width).toInt() + 1
            each(l.pictureLeft - reach, l.pictureTop - reach, l.pictureRight + reach, l.pictureBottom + reach) { x, y, i ->
                val px = x + 0.5f; val py = y + 0.5f
                val d = max(max(l.pictureLeft - px, px - l.pictureRight), max(l.pictureTop - py, py - l.pictureBottom))
                val cov = min(d + 0.5f, gap + width) - max(d - 0.5f, gap)
                if (cov > 0f) out[i] = over(out[i], colour, alpha * min(1f, cov))
            }
        }

        /** An axis-aligned rectangle with exact pixel coverage at its edges. */
        fun rect(x0: Float, y0: Float, x1: Float, y1: Float, colour: Int, alpha: Float) {
            each(floor(x0).toInt(), floor(y0).toInt(), ceil(x1).toInt(), ceil(y1).toInt()) { x, y, i ->
                val cx = min(x + 1f, x1) - max(x.toFloat(), x0); val cy = min(y + 1f, y1) - max(y.toFloat(), y0)
                if (cx > 0f && cy > 0f) out[i] = over(out[i], colour, alpha * cx * cy)
            }
        }

        /** [text] in the 3 x 5 cell face, cells [g] pixels square from (x, y); [rotated] reads top to bottom. */
        fun text(text: String, x: Float, y: Float, g: Float, colour: Int, alpha: Float, rotated: Boolean) {
            if (g < 0.35f) return
            var at = 0f
            for (ch in text) {
                val glyph = GLYPHS[ch] ?: GLYPHS.getValue(' ')
                for (r in 0 until 5) for (c in 0 until 3) {
                    if (glyph[r * 3 + c] != '1') continue
                    if (!rotated) rect(x + at + c * g, y + r * g, x + at + (c + 1) * g, y + (r + 1) * g, colour, alpha)
                    else rect(x + (4 - r) * g, y + at + c * g, x + (5 - r) * g, y + at + (c + 1) * g, colour, alpha)
                }
                at += 4 * g
            }
        }
    }

    private val GLYPHS = mapOf(
        '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111", '3' to "111001111001111",
        '4' to "101101111001001", '5' to "111100111001111", '6' to "111100111101111", '7' to "111001010010010",
        '8' to "111101111101111", '9' to "111101111001111", 'A' to "010101111101101", 'S' to "011100010001110",
        'T' to "111010010010010", 'I' to "111010010010111", 'L' to "100100100100111", '>' to "100110111110100",
        ' ' to "000000000000000"
    )

    // ------------------------------------------------------------------ arithmetic

    /** Signed distance from a point to a rounded rectangle; negative inside. */
    fun roundRect(px: Float, py: Float, l: Float, t: Float, r: Float, b: Float, radius: Float): Float {
        val hx = (r - l) / 2f; val hy = (b - t) / 2f
        val rr = min(radius, min(hx, hy)).coerceAtLeast(0f)
        val qx = abs(px - (l + r) / 2f) - hx + rr; val qy = abs(py - (t + b) / 2f) - hy + rr
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - rr
    }

    /** Source over destination, [cov] times the source's own alpha, unpremultiplied in and out. */
    fun over(dst: Int, src: Int, cov: Float): Int {
        val sa = (src ushr 24) / 255f * cov
        if (sa <= 0f) return dst
        if (sa >= 0.999f) return src or 0xFF000000.toInt()
        val da = (dst ushr 24) / 255f
        val oa = sa + da * (1f - sa)
        if (oa <= 0f) return 0
        val ks = sa / oa; val kd = 1f - ks
        return Argb.pack(
            (oa * 255f + 0.5f).toInt().coerceIn(0, 255),
            (Argb.red(src) * ks + Argb.red(dst) * kd + 0.5f).toInt().coerceIn(0, 255),
            (Argb.green(src) * ks + Argb.green(dst) * kd + 0.5f).toInt().coerceIn(0, 255),
            (Argb.blue(src) * ks + Argb.blue(dst) * kd + 0.5f).toInt().coerceIn(0, 255)
        )
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * k + 0.5f).toInt().coerceIn(0, 255)
        return Argb.pack(ch(Argb.alpha(a), Argb.alpha(b)), ch(Argb.red(a), Argb.red(b)), ch(Argb.green(a), Argb.green(b)), ch(Argb.blue(a), Argb.blue(b)))
    }

    /** Colour times [k], alpha kept. */
    fun shade(c: Int, k: Float): Int = Argb.pack(
        Argb.alpha(c), (Argb.red(c) * k + 0.5f).toInt().coerceIn(0, 255), (Argb.green(c) * k + 0.5f).toInt().coerceIn(0, 255), (Argb.blue(c) * k + 0.5f).toInt().coerceIn(0, 255)
    )

    private fun clamp(v: Float) = if (v <= 0f) 0f else if (v >= 1f) 1f else v
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = clamp((x - e0) / (e1 - e0)); return t * t * (3f - 2f * t) }

    /** How much of a pattern with a cell of [pixelsPerCell] survives at this resolution: none below 1.5 pixels, so no moiré. */
    private fun lod(pixelsPerCell: Float): Float = clamp((pixelsPerCell - 1.5f) / 1.5f)

    /** 0..1 from integer lattice coordinates and a seed. */
    fun hash(seed: Int, x: Int, y: Int): Float {
        var h = seed * -0x61c88647 + x * 0x27d4eb2d + y * 0x165667b1
        h = (h xor (h ushr 15)) * 0x2c1b3c6d
        h = (h xor (h ushr 12)) * 0x297a2d39
        h = h xor (h ushr 15)
        return (h ushr 8) * (1f / 16777216f)
    }

    /** Smooth value noise, 0..1, continuous in both coordinates. */
    fun noise(x: Float, y: Float, seed: Int): Float {
        val xf = floor(x); val yf = floor(y)
        val ix = xf.toInt(); val iy = yf.toInt()
        val fx = x - xf; val fy = y - yf
        val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy)
        val a = hash(seed, ix, iy); val b = hash(seed, ix + 1, iy); val c = hash(seed, ix, iy + 1); val d = hash(seed, ix + 1, iy + 1)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    /** Noise that settles to its mean where its cells would be smaller than a pixel or two. */
    private fun lodNoise(x: Float, y: Float, seed: Int, pixelsPerCell: Float): Float {
        val k = lod(pixelsPerCell)
        return if (k <= 0f) 0.5f else 0.5f + k * (noise(x, y, seed) - 0.5f)
    }

    /** Fractal noise, 0..1; [pixelsPerCell] is the first octave's cell in output pixels. */
    fun fbm(x: Float, y: Float, seed: Int, octaves: Int, pixelsPerCell: Float): Float {
        var sum = 0f; var amp = 0.5f; var norm = 0f; var freq = 1f
        for (o in 0 until octaves) {
            sum += amp * lodNoise(x * freq, y * freq, seed + o * 1013, pixelsPerCell / freq)
            norm += amp; amp *= 0.5f; freq *= 2f
        }
        return sum / norm
    }
}
