package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The filter effects: whole-picture treatments that each do one recognisable thing, with a
 * strength, a handful of styles and one second control.
 *
 * Each is pure arithmetic on a packed ARGB array. Every radius, grain cell, scratch and leak is
 * measured in fractions of the shorter edge, or scaled by [Geometry2D.resolutionScale], so the
 * preview predicts the export. Every texture is generated here from a seed, never loaded from
 * a file, so the same recipe gives the same pixels on every phone.
 */
enum class EffectKind(
    /** How many styles the effect has; [Effect.style] is an index below this. */
    val styles: Int,
    val strengthRange: ClosedFloatingPointRange<Float>,
    /** The second control: saturation, protection, warmth, texture, scratches, grain size or transition. */
    val extraRange: ClosedFloatingPointRange<Float>,
    val extraDefault: Float,
    val stage: Effects.Stage,
    /** Whether a new seed changes the picture, so the panel offers "another one". */
    val seeded: Boolean = false
) {
    /** Local contrast that adapts to the picture, and saturation that spares skin. Negative flattens. */
    AMBIANCE(4, -1f..1f, -1f..1f, 0f, Effects.Stage.TONE),
    /** Contrast within the fine, middle or broad detail, with the deep shadows and bright highlights protected. */
    TONAL_CONTRAST(4, 0f..1f, 0f..1f, 0.5f, Effects.Stage.TONE),
    /** Strong local tone mapping: the sky held back, the shadows opened, the detail pulled out. */
    HDR_SCAPE(4, 0f..1f, -1f..1f, 0f, Effects.Stage.TONE),
    /** Heavy local contrast with the colour drained: the gritty look. */
    DRAMA(4, 0f..1f, -1f..1f, 0f, Effects.Stage.TONE),
    /** Black and white through a coloured filter, as on film: red darkens blue skies, green lightens leaves. The extra is contrast. */
    BLACK_WHITE(6, 0f..1f, -1f..1f, 0.15f, Effects.Stage.TONE),
    /** A soft glow that spreads from the highlights, warm or cool. */
    GLAMOUR_GLOW(4, 0f..1f, -1f..1f, 0f, Effects.Stage.TONE),
    /** A generated stained texture and a colour grade over the picture. */
    GRUNGE(4, 0f..1f, 0f..1f, 0.6f, Effects.Stage.SURFACE, seeded = true),
    /** Light leaks, scratches and dust on a vintage colour. */
    RETROLUX(4, 0f..1f, 0f..1f, 0.4f, Effects.Stage.SURFACE, seeded = true),
    /** Film grain of a chosen size on the colour of a film stock. */
    GRAINY_FILM(4, 0f..1f, 0f..1f, 0.35f, Effects.Stage.SURFACE, seeded = true),
    /** Tilt-shift or round focus: sharp where it is pointed, blurred into bright discs elsewhere. */
    LENS_BLUR(2, 0f..1f, 0f..1f, 0.5f, Effects.Stage.LENS),
    /** Darkened (or brightened) surroundings around an oval you place, size and turn; the extra is its softness. */
    VIGNETTE(2, 0f..1f, 0f..1f, 0.5f, Effects.Stage.LENS);

    /** Black and white starts fully grey; the rest start halfway. */
    val defaultStrength: Float get() = if (this == BLACK_WHITE) 1f else 0.5f
}

/**
 * One effect in the recipe's stack. [strength] 0 leaves the picture alone, whatever the rest says.
 *
 * The lens-blur fields ([center], [size], [angle], [aspect]) are fractions: [center] of the
 * canvas, [size] of the shorter edge. Other effects carry their defaults and ignore them.
 */
data class Effect(
    val kind: EffectKind,
    val style: Int = 0,
    val strength: Float = kind.defaultStrength,
    val extra: Float = kind.extraDefault,
    val seed: Int = 1,
    /** Lens blur: the middle of what stays sharp. */
    val center: Pt = Pt(0.5f, 0.5f),
    /** Lens blur: half the sharp band's width, or the focus ellipse's horizontal radius, of the shorter edge. */
    val size: Float = 0.15f,
    /** Lens blur: degrees; the line's direction, or the ellipse's turn. */
    val angle: Float = 0f,
    /** Lens blur, round style: the ellipse's height over its width. */
    val aspect: Float = 1f
) {
    val isIdentity: Boolean get() = strength == 0f

    /** The focus is a band across the picture (tilt-shift); otherwise an oval. */
    val linear: Boolean get() = kind == EffectKind.LENS_BLUR && style == 0


    val valid: Boolean
        get() = style in 0 until kind.styles && strength.isFinite() && strength in kind.strengthRange &&
            extra.isFinite() && extra in kind.extraRange && center.finite && center.x in 0f..1f && center.y in 0f..1f &&
            size.isFinite() && size in MIN_SIZE..MAX_SIZE && angle.isFinite() && angle in -360f..360f &&
            aspect.isFinite() && aspect in MIN_ASPECT..MAX_ASPECT

    companion object {
        /** A new effect as the panel adds it: a vignette starts as a wide oval round the middle. */
        fun added(kind: EffectKind): Effect = if (kind == EffectKind.VIGNETTE) Effect(kind, strength = 0.6f, size = 0.55f, aspect = 1.25f) else Effect(kind)

        const val MIN_SIZE = 0.01f
        const val MAX_SIZE = 1.5f
        const val MIN_ASPECT = 0.15f
        const val MAX_ASPECT = 6f
        /** At most this many effects in one stack; each one costs a pass over the picture. */
        const val MAX = 8
    }
}

object Effects {

    /**
     * Where in the render an effect runs. Tone effects run after colour and before sharpening,
     * so sharpening works on what they made; the lens after detail, so the blur is not
     * sharpened back; surface effects last, so grain, scratches and leaks sit on top of the
     * blur as they do on film. Within a stage the stack's order holds.
     */
    enum class Stage { TONE, LENS, SURFACE }

    /** Runs every effect of [stage] in [effects], in order. Returns [px] itself when none applies. */
    fun apply(px: IntArray, w: Int, h: Int, effects: List<Effect>, stage: Stage, check: () -> Unit = {}): IntArray {
        var out = px
        for (e in effects) {
            if (e.kind.stage != stage || e.isIdentity) continue
            check()
            out = applyOne(out, w, h, e, check)
        }
        return out
    }

    /** One effect on a copy of [px]; [px] is never written. */
    fun applyOne(px: IntArray, w: Int, h: Int, e: Effect, check: () -> Unit = {}): IntArray {
        if (e.isIdentity || w <= 0 || h <= 0) return px.copyOf()
        val scale = Geometry2D.resolutionScale(Geometry2D.Size(w, h))
        return when (e.kind) {
            EffectKind.AMBIANCE -> ambiance(px, w, h, e, scale, check)
            EffectKind.TONAL_CONTRAST -> tonalContrast(px, w, h, e, scale, check)
            EffectKind.HDR_SCAPE -> hdr(px, w, h, e, scale, check)
            EffectKind.DRAMA -> drama(px, w, h, e, scale, check)
            EffectKind.BLACK_WHITE -> blackWhite(px, w, h, e)
            EffectKind.GLAMOUR_GLOW -> glow(px, w, h, e, scale, check)
            EffectKind.GRUNGE -> grunge(px, w, h, e, scale, check)
            EffectKind.RETROLUX -> retrolux(px, w, h, e, scale, check)
            EffectKind.GRAINY_FILM -> grainyFilm(px, w, h, e, scale, check)
            EffectKind.LENS_BLUR -> lensBlur(px, w, h, e, scale, check)
            EffectKind.VIGNETTE -> vignette(px, w, h, e, check)
        }
    }

    // ------------------------------------------------------------------ local contrast family

    private class Ambiance(val compress: Float, val detail: Float, val sat: Float)
    private val AMBIANCE_STYLES = arrayOf(
        Ambiance(0.35f, 0.9f, 0.35f),  // natural
        Ambiance(0.3f, 0.8f, 0.7f),    // vivid
        Ambiance(0.5f, 0.45f, 0.25f),  // soft
        Ambiance(0.2f, 1.4f, 0.3f)     // deep
    )

    private fun ambiance(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val st = AMBIANCE_STYLES[e.style]
        val s = e.strength
        val l = Planes.luma(px)
        val base = Planes.guided(l, w, h, max(1, (30f * scale).roundToInt()), 0.012f, check)
        check()
        val satAmount = s * st.sat + e.extra * 0.6f
        val out = IntArray(px.size)
        Parallel.bands(h) { from, to ->
            for (i in from * w until to * w) {
                val b = base[i]; val d = l[i] - b
                val nb = b + s * st.compress * (0.5f - b) * (1f - abs(0.5f - b))
                val nl = nb + d * (1f + st.detail * s)
                out[i] = finish(px[i], nl - l[i], smartSaturation(px[i], satAmount))
            }
        }
        return out
    }

    /** Gains on the fine, middle and broad detail for the four tonal-contrast styles. */
    private val TONAL_GAINS = arrayOf(
        floatArrayOf(2.2f, 0.7f, 0.3f), // high tones: texture
        floatArrayOf(0.6f, 2.2f, 0.6f), // mid
        floatArrayOf(0.3f, 0.7f, 2.0f), // low: broad shapes
        floatArrayOf(1.3f, 1.3f, 1.0f)  // balanced
    )

    private fun tonalContrast(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val g = TONAL_GAINS[e.style]
        val s = e.strength
        val l = Planes.luma(px)
        val b1 = Planes.blur(l, w, h, max(1, (2f * scale).roundToInt()), 2, check)
        val b2 = Planes.blur(l, w, h, max(1, (8f * scale).roundToInt()), 2, check)
        val b3 = Planes.blur(l, w, h, max(1, (32f * scale).roundToInt()), 2, check)
        check()
        val protect = e.extra
        val out = IntArray(px.size)
        Parallel.bands(h) { from, to ->
            for (i in from * w until to * w) {
                val li = l[i]
                val d = g[0] * (li - b1[i]) + g[1] * (b1[i] - b2[i]) + g[2] * (b2[i] - b3[i])
                val tone = b3[i]
                val guard = (1f - protect * (smooth(0.32f, 0.02f, tone) + smooth(0.68f, 0.98f, tone))).coerceIn(0f, 1f)
                val delta = (s * d * guard).coerceIn(-0.45f, 0.45f)
                out[i] = finish(px[i], delta, 1f)
            }
        }
        return out
    }

    private class Hdr(val radius: Float, val compress: Float, val detail: Float, val sat: Float, val skin: Boolean)
    private val HDR_STYLES = arrayOf(
        Hdr(40f, 0.55f, 1.6f, 0.25f, false), // nature
        Hdr(40f, 0.4f, 0.7f, 0.05f, true),   // people
        Hdr(10f, 0.3f, 2.2f, 0.15f, false),  // fine
        Hdr(40f, 0.75f, 2.6f, 0.35f, false)  // strong
    )

    private fun hdr(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val st = HDR_STYLES[e.style]
        val s = e.strength
        val l = Planes.luma(px)
        val base = Planes.guided(l, w, h, max(1, (st.radius * scale).roundToInt()), 0.006f, check)
        check()
        val sat = s * st.sat + e.extra * 0.6f
        val out = IntArray(px.size)
        Parallel.bands(h) { from, to ->
            for (i in from * w until to * w) {
                val b = base[i]; val d = l[i] - b
                // The broad tones pulled towards the middle, the shadows opened a little more than the highlights fall.
                var nb = 0.5f + (b - 0.5f) * (1f - st.compress * s)
                nb += s * 0.06f * (1f - b)
                val gain = if (st.skin) 1f + st.detail * s * (1f - 0.7f * skinWeight(px[i])) else 1f + st.detail * s
                val nl = nb + d * gain
                out[i] = finish(px[i], nl - l[i], smartSaturation(px[i], sat))
            }
        }
        return out
    }

    private class Drama(val radius: Float, val gain: Float, val lift: Float, val darken: Float, val desat: Float, val fine: Float)
    private val DRAMA_STYLES = arrayOf(
        Drama(24f, 2.2f, 0.06f, 0f, 0.3f, 0f),     // bright
        Drama(24f, 2.5f, 0f, 0.22f, 0.4f, 0f),     // dark
        Drama(24f, 2.3f, 0f, 0.05f, 1f, 0f),       // silver: no colour
        Drama(24f, 1.2f, 0f, 0.08f, 0.25f, 3.0f)   // grit: the fine detail too
    )

    /** tanh read from a table: within 1e-5 of the real one, and many times faster on a phone than Math.tanh. */
    private const val TANH_STEPS = 256
    private const val TANH_RANGE = 4
    private val TANH = FloatArray(2 * TANH_RANGE * TANH_STEPS + 1) { kotlin.math.tanh((it - TANH_RANGE * TANH_STEPS).toDouble() / TANH_STEPS).toFloat() }

    internal fun softTanh(x: Float): Float {
        val p = (x + TANH_RANGE) * TANH_STEPS
        if (p <= 0f) return -1f
        val last = TANH.size - 1
        if (p >= last) return 1f
        val i = p.toInt(); val f = p - i
        return TANH[i] + (TANH[i + 1] - TANH[i]) * f
    }

    private fun drama(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val st = DRAMA_STYLES[e.style]
        val s = e.strength
        val l = Planes.luma(px)
        val broad = Planes.blur(l, w, h, max(1, (st.radius * scale).roundToInt()), 2, check)
        val fine = if (st.fine > 0f) Planes.blur(l, w, h, max(1, (3f * scale).roundToInt()), 2, check) else null
        check()
        val satFactor = (1f - st.desat * s + e.extra * 0.6f).coerceIn(0f, 2f)
        val out = IntArray(px.size)
        Parallel.bands(h) { from, to ->
            for (i in from * w until to * w) {
                val li = l[i]; val b = broad[i]
                var d = (li - b) * st.gain
                if (fine != null) d += (li - fine[i]) * st.fine
                var nb = b + st.lift * s * (1f - b) - st.darken * s * b
                nb = nb.coerceIn(0f, 1f)
                // A soft knee so a strong push compresses rather than clips.
                val nl = nb + 0.6f * softTanh(d * s / 0.6f)
                out[i] = finish(px[i], nl - li, satFactor, plainSaturation = true)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ black and white

    /**
     * How much red, green and blue make the grey, per filter: neutral, then red, orange,
     * yellow, green and blue glass in front of the lens. Each row adds up to one, so a grey
     * picture stays exactly as bright whichever filter is on.
     */
    private val BW_FILTERS = arrayOf(
        floatArrayOf(0.299f, 0.587f, 0.114f),  // neutral
        floatArrayOf(0.80f, 0.30f, -0.10f),    // red: skies go dark, skin goes pale
        floatArrayOf(0.60f, 0.45f, -0.05f),    // orange
        floatArrayOf(0.45f, 0.55f, 0.00f),     // yellow
        floatArrayOf(0.15f, 0.80f, 0.05f),     // green: leaves go light
        floatArrayOf(0.05f, 0.30f, 0.65f)      // blue
    )

    private fun blackWhite(px: IntArray, w: Int, h: Int, e: Effect): IntArray {
        val f = BW_FILTERS[e.style]
        val s = e.strength; val k = e.extra
        val out = IntArray(px.size)
        Parallel.bands(h) { from, to ->
            for (i in from * w until to * w) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                var grey = (f[0] * r + f[1] * g + f[2] * b).coerceIn(0f, 1f)
                grey = if (k >= 0f) sCurve(grey, k * 0.5f) else grey + (grey - 0.5f) * k * 0.5f
                out[i] = pack(c, r + (grey - r) * s, g + (grey - g) * s, b + (grey - b) * s)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ glow

    private class Glow(val radius: Float, val amount: Float, val warm: Float, val soft: Float)
    private val GLOW_STYLES = arrayOf(
        Glow(20f, 0.65f, 0f, 0f),     // soft
        Glow(22f, 0.65f, 0.6f, 0.05f), // warm
        Glow(16f, 0.5f, 0.15f, 0.45f), // dreamy: the whole picture softened too
        Glow(42f, 1f, 0.1f, 0f)       // bloom
    )

    private fun glow(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val st = GLOW_STYLES[e.style]
        val s = e.strength
        val n = px.size
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        Parallel.bands(h) { from, to ->
            for (i in from * w until to * w) {
                val c = px[i]
                val rr = ((c shr 16) and 0xFF) / 255f; val gg = ((c shr 8) and 0xFF) / 255f; val bb = (c and 0xFF) / 255f
                val weight = smooth(0.42f, 0.96f, 0.299f * rr + 0.587f * gg + 0.114f * bb)
                r[i] = rr * weight; g[i] = gg * weight; b[i] = bb * weight
            }
        }
        val radius = max(1, (st.radius * scale).roundToInt())
        val gr = Planes.blur(r, w, h, radius, 3, check); val gg = Planes.blur(g, w, h, radius, 3, check); val gb = Planes.blur(b, w, h, radius, 3, check)
        check()
        val softPlanes = if (st.soft > 0f) Planes.channels(px).let { (pr, pg, pb) ->
            val sr = max(1, (5f * scale).roundToInt())
            Triple(Planes.blur(pr, w, h, sr, 2, check), Planes.blur(pg, w, h, sr, 2, check), Planes.blur(pb, w, h, sr, 2, check))
        } else null
        val warm = (st.warm + e.extra).coerceIn(-1f, 1.2f)
        val tr = 1f + 0.22f * warm; val tb = 1f - 0.22f * warm
        val k = st.amount * s * 1.6f
        val soft = st.soft * s
        val out = IntArray(n)
        Parallel.bands(h) { from, to ->
            for (i in from * w until to * w) {
                val c = px[i]
                var rr = ((c shr 16) and 0xFF) / 255f; var gg2 = ((c shr 8) and 0xFF) / 255f; var bb = (c and 0xFF) / 255f
                if (softPlanes != null) {
                    rr += (softPlanes.first[i] - rr) * soft; gg2 += (softPlanes.second[i] - gg2) * soft; bb += (softPlanes.third[i] - bb) * soft
                }
                // Screen: a glow adds light and never darkens.
                rr = 1f - (1f - rr) * (1f - (k * gr[i] * tr).coerceIn(0f, 1f))
                gg2 = 1f - (1f - gg2) * (1f - (k * gg[i]).coerceIn(0f, 1f))
                bb = 1f - (1f - bb) * (1f - (k * gb[i] * tb).coerceIn(0f, 1f))
                // A whisper of the warmth across the whole picture, so the glow belongs to it.
                rr *= 1f + 0.04f * warm * s; bb *= 1f - 0.04f * warm * s
                out[i] = pack(c, rr, gg2, bb)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ grunge

    /** Multiply tints for the grunge grades: dust, rust, concrete, teal. */
    private val GRUNGE_TINTS = arrayOf(
        floatArrayOf(1.0f, 0.86f, 0.66f, 0.15f),
        floatArrayOf(1.05f, 0.7f, 0.45f, 0.1f),
        floatArrayOf(0.92f, 0.93f, 0.95f, 0.7f),
        floatArrayOf(0.62f, 0.9f, 0.95f, 0.2f)
    )

    private fun grunge(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val tint = GRUNGE_TINTS[e.style]
        val s = e.strength
        val tex = e.extra
        val short = min(w, h).toFloat()
        val seed = e.seed
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val rmax = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        // The noise is smooth at a few hundredths of the edge, so it is worked out on a grid a few
        // pixels apart and read back bilinearly: the same texture for a fraction of the cost.
        val step = (short / 250f).toInt().coerceIn(1, 4)
        val invStep = 1f / step
        val gw = (w - 1) / step + 2; val gh = (h - 1) / step + 2
        val stains = FloatArray(gw * gh); val texture = FloatArray(gw * gh)
        Parallel.bands(gh, minimum = 32) { from, to ->
            for (gy in from until to) {
                val v = gy * step / short
                for (gx in 0 until gw) {
                    val u = gx * step / short
                    val stain = Noise.fbm(u * 1.6f, v * 1.6f, seed, 3)
                    val mottle = Noise.fbm(u * 7f, v * 7f, seed + 101, 4)
                    stains[gy * gw + gx] = stain
                    texture[gy * gw + gx] = 0.5f + (stain - 0.5f) * 0.9f + (mottle - 0.5f) * 0.6f
                }
            }
        }
        check()
        val out = IntArray(px.size)
        Parallel.bands(h) { from, to ->
            for (y in from until to) {
                if (y and 63 == 0) check()
                val v = y / short
                for (x in 0 until w) {
                    val u = x / short
                    val i = y * w + x
                    val c = px[i]
                    var rr = ((c shr 16) and 0xFF) / 255f; var gg = ((c shr 8) and 0xFF) / 255f; var bb = (c and 0xFF) / 255f
                    val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    // The grade: towards the tint, partly drained of colour.
                    val desat = tint[3] * s
                    rr += (l - rr) * desat; gg += (l - gg) * desat; bb += (l - bb) * desat
                    val grade = 0.55f * s
                    rr += (rr * tint[0] - rr) * grade; gg += (gg * tint[1] - gg) * grade; bb += (bb * tint[2] - bb) * grade
                    // The texture: broad stains and mottling, all from one seed, read off the coarse grid.
                    val stain = Planes.sample(stains, gw, gh, x * invStep, y * invStep)
                    val t = Planes.sample(texture, gw, gh, x * invStep, y * invStep)
                    val o = 0.5f + (t - 0.5f) * tex * s * 1.4f
                    rr = overlay(rr, o); gg = overlay(gg, o); bb = overlay(bb, o)
                    // Specks: rare dark and pale flecks on a grid a few hundredths of the edge wide.
                    val sx = (u * 260f).toInt(); val sy = (v * 260f).toInt()
                    val hs = Noise.hash(sx, sy, seed + 7)
                    if (hs > 0.993f) {
                        val dark = Noise.hash(sx, sy, seed + 13) < 0.6f
                        val f = tex * s * 0.6f
                        if (dark) { rr *= 1f - f; gg *= 1f - f; bb *= 1f - f } else { rr += (1f - rr) * f; gg += (1f - gg) * f; bb += (1f - bb) * f }
                    }
                    // Burnt edges.
                    val dx = (x - cx) / rmax; val dy = (y - cy) / rmax
                    val edge = smooth(0.45f, 1.05f, sqrt(dx * dx + dy * dy))
                    val k = 1f - 0.4f * s * edge * (0.6f + 0.8f * stain)
                    out[i] = pack(c, rr * k, gg * k, bb * k)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ retrolux

    /** Per channel lift (black) and gain (white), then saturation: summer, amber, faded, rose. */
    private val RETRO_GRADES = arrayOf(
        floatArrayOf(0.08f, 0.05f, 0.02f, 1.0f, 0.96f, 0.82f, 0.9f),
        floatArrayOf(0.1f, 0.05f, 0.0f, 1.0f, 0.88f, 0.62f, 0.85f),
        floatArrayOf(0.1f, 0.12f, 0.14f, 0.92f, 0.92f, 0.88f, 0.6f),
        floatArrayOf(0.1f, 0.04f, 0.08f, 1.0f, 0.88f, 0.9f, 0.85f)
    )
    private val LEAK_COLOURS = arrayOf(
        floatArrayOf(1f, 0.45f, 0.1f), floatArrayOf(1f, 0.2f, 0.12f), floatArrayOf(1f, 0.78f, 0.3f), floatArrayOf(1f, 0.35f, 0.55f)
    )

    private class Leak(val x: Float, val y: Float, val radius: Float, val colour: FloatArray, val power: Float)

    private fun retrolux(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val grade = RETRO_GRADES[e.style]
        val s = e.strength
        val short = min(w, h).toFloat()
        val aspectW = w / short; val aspectH = h / short
        val rng = Seeded(e.seed)
        // Two or three leaks, each entering from an edge.
        val leaks = List(2 + rng.int(2)) {
            val side = rng.int(4)
            val along = rng.next()
            val (lx, ly) = when (side) { 0 -> 0f to along * aspectH; 1 -> aspectW to along * aspectH; 2 -> along * aspectW to 0f; else -> along * aspectW to aspectH }
            Leak(lx, ly, 0.35f + 0.45f * rng.next(), LEAK_COLOURS[(rng.int(LEAK_COLOURS.size) + e.style) % LEAK_COLOURS.size], 0.6f + 0.4f * rng.next())
        }
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val rmax = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        val out = IntArray(px.size)
        Parallel.bands(h) { from, to ->
            for (y in from until to) {
                if (y and 63 == 0) check()
                val v = y / short
                for (x in 0 until w) {
                    val u = x / short
                    val i = y * w + x
                    val c = px[i]
                    var rr = ((c shr 16) and 0xFF) / 255f; var gg = ((c shr 8) and 0xFF) / 255f; var bb = (c and 0xFF) / 255f
                    val nr = grade[0] + rr * (grade[3] - grade[0]); val ng = grade[1] + gg * (grade[4] - grade[1]); val nb = grade[2] + bb * (grade[5] - grade[2])
                    val l = 0.299f * nr + 0.587f * ng + 0.114f * nb
                    val sr = l + (nr - l) * grade[6]; val sg = l + (ng - l) * grade[6]; val sb = l + (nb - l) * grade[6]
                    rr += (sr - rr) * s; gg += (sg - gg) * s; bb += (sb - bb) * s
                    for (leak in leaks) {
                        val dx = u - leak.x; val dy = v - leak.y
                        val d = sqrt(dx * dx + dy * dy) / leak.radius
                        if (d >= 1f) continue
                        val f = (1f - d) * (1f - d) * leak.power * s * 0.85f
                        rr = 1f - (1f - rr) * (1f - leak.colour[0] * f)
                        gg = 1f - (1f - gg) * (1f - leak.colour[1] * f)
                        bb = 1f - (1f - bb) * (1f - leak.colour[2] * f)
                    }
                    val dx = (x - cx) / rmax; val dy = (y - cy) / rmax
                    val k = 1f - 0.3f * s * smooth(0.5f, 1.1f, sqrt(dx * dx + dy * dy))
                    out[i] = pack(c, rr * k, gg * k, bb * k)
                }
            }
        }
        check()
        scratches(out, w, h, e, scale, short)
        return out
    }

    /** Thin, nearly vertical scratches and round dust, drawn with coverage so they are smooth at any size. */
    private fun scratches(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, short: Float) {
        val amount = e.extra * e.strength
        if (amount <= 0f) return
        val rng = Seeded(e.seed * 31 + 5)
        val count = (amount * 36f).roundToInt()
        val half = max(0.5f, 0.7f * scale)
        repeat(count) {
            val x0 = rng.next() * w
            val y0 = rng.next() * h
            val len = (0.15f + 0.7f * rng.next()) * h
            val slope = (rng.next() - 0.5f) * 0.06f
            val bright = rng.next() < 0.7f
            val opacity = (0.25f + 0.45f * rng.next()) * amount.coerceAtMost(1f)
            val y1 = min(h.toFloat(), y0 + len)
            var y = y0.toInt()
            while (y < y1) {
                val cxp = x0 + slope * (y - y0) + sin(y / short * 40f + x0) * 0.6f * scale
                val fade = sin(((y - y0) / len).coerceIn(0f, 1f) * Math.PI.toFloat())
                val a = opacity * fade
                for (x in (cxp - half - 1).toInt()..(cxp + half + 1).toInt()) {
                    if (x < 0 || x >= w) continue
                    val cov = (half + 0.5f - abs(x + 0.5f - cxp)).coerceIn(0f, 1f)
                    if (cov > 0f) px[y * w + x] = blendTo(px[y * w + x], if (bright) 1f else 0.05f, a * cov)
                }
                y++
            }
        }
        val dust = (amount * 160f).roundToInt()
        repeat(dust) {
            val dx = rng.next() * w; val dy = rng.next() * h
            val r = (0.6f + 1.6f * rng.next()) * max(0.6f, scale)
            val bright = rng.next() < 0.5f
            val a = (0.3f + 0.5f * rng.next()) * amount.coerceAtMost(1f)
            for (y in (dy - r - 1).toInt()..(dy + r + 1).toInt()) {
                if (y < 0 || y >= h) continue
                for (x in (dx - r - 1).toInt()..(dx + r + 1).toInt()) {
                    if (x < 0 || x >= w) continue
                    val d = sqrt((x + 0.5f - dx) * (x + 0.5f - dx) + (y + 0.5f - dy) * (y + 0.5f - dy))
                    val cov = (r + 0.5f - d).coerceIn(0f, 1f)
                    if (cov > 0f) px[y * w + x] = blendTo(px[y * w + x], if (bright) 0.95f else 0.08f, a * cov)
                }
            }
        }
    }

    // ------------------------------------------------------------------ grainy film

    /** Film stocks: lift and gain per channel, saturation, contrast. Neutral, warm, cool, mono. */
    private val STOCKS = arrayOf(
        floatArrayOf(0.02f, 0.02f, 0.02f, 1f, 1f, 1f, 1f, 0.12f),
        floatArrayOf(0.05f, 0.03f, 0.01f, 1f, 0.97f, 0.88f, 0.92f, 0.06f),
        floatArrayOf(0.01f, 0.04f, 0.06f, 0.95f, 1f, 1f, 0.95f, 0.1f),
        floatArrayOf(0.03f, 0.03f, 0.03f, 1f, 1f, 1f, 0f, 0.28f)
    )

    private fun grainyFilm(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val stock = STOCKS[e.style]
        val s = e.strength
        // The grain cell, in pixels at the reference size: 0.8 for fine grain up to 3.2 for coarse.
        val cell = (0.8f + 2.4f * e.extra) * scale.coerceAtLeast(0.05f)
        val inv = 1f / cell
        val amp = 0.17f * s * (0.8f + 0.4f * e.extra)
        val seed = e.seed
        val out = IntArray(px.size)
        Parallel.bands(h) { from, to ->
            for (y in from until to) {
                if (y and 63 == 0) check()
                val gy = y * inv
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = px[i]
                    var rr = ((c shr 16) and 0xFF) / 255f; var gg = ((c shr 8) and 0xFF) / 255f; var bb = (c and 0xFF) / 255f
                    val nr = stock[0] + rr * (stock[3] - stock[0]); val ng = stock[1] + gg * (stock[4] - stock[1]); val nb = stock[2] + bb * (stock[5] - stock[2])
                    val l = 0.299f * nr + 0.587f * ng + 0.114f * nb
                    var sr = l + (nr - l) * stock[6]; var sg = l + (ng - l) * stock[6]; var sb = l + (nb - l) * stock[6]
                    val k = stock[7]
                    sr = sCurve(sr, k); sg = sCurve(sg, k); sb = sCurve(sb, k)
                    rr += (sr - rr) * s; gg += (sg - gg) * s; bb += (sb - bb) * s
                    // Two octaves of smooth noise: clumps of grain, not a pixel pattern.
                    val gx = x * inv
                    val n = Noise.value(gx, gy, seed) * 0.65f + Noise.value(gx * 2.1f + 17f, gy * 2.1f + 5f, seed + 3) * 0.35f - 0.5f
                    val lum = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    val mid = 1f - abs(lum - 0.45f) * 1.3f
                    val d = n * 2f * amp * (0.35f + 0.65f * mid.coerceIn(0f, 1f))
                    out[i] = pack(c, rr + d, gg + d, bb + d)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ lens blur

    /**
     * How blurred a pixel is, 0 sharp to 1 fully, for [e] at canvas [w]×[h]. Pure geometry, used
     * by the renderer and by the tests that check the focus band stays sharp.
     */
    fun focusWeight(e: Effect, x: Float, y: Float, w: Int, h: Int): Float = Focus(e, w, h).weight(x, y)

    /** The focus shape worked out once per picture, so the per-pixel test is a few multiplies. */
    private class Focus(e: Effect, w: Int, h: Int) {
        private val short = min(w, h).toFloat()
        private val cx = e.center.x * w; private val cy = e.center.y * h
        private val ca: Float; private val sa: Float
        init { val a = Math.toRadians(e.angle.toDouble()); ca = cos(a).toFloat(); sa = sin(a).toFloat() }
        private val linear = e.linear
        private val size = e.size
        private val ry = e.size * e.aspect
        private val feather = 0.04f + 0.6f * e.extra

        fun weight(x: Float, y: Float): Float {
            val px = (x - cx) / short; val py = (y - cy) / short
            val d = if (linear) abs(-sa * px + ca * py) - size else {
                // Turned into the ellipse's frame, then scaled so its edge is at 1.
                val ex = (ca * px + sa * py) / size
                val ey = (-sa * px + ca * py) / ry
                (sqrt(ex * ex + ey * ey) - 1f) * size
            }
            return smooth(0f, feather, d)
        }
    }

    /**
     * Snapseed's vignette, placed: everything outside the oval is darkened (style 0) or brightened
     * towards white (style 1), fading in over the softness. Only position decides it, so the
     * same oval lands in the same place at any size.
     */
    private fun vignette(px: IntArray, w: Int, h: Int, e: Effect, check: () -> Unit): IntArray {
        val focus = Focus(e.copy(extra = 0.1f + 0.9f * e.extra), w, h)
        val out = IntArray(px.size)
        val k = 0.85f * e.strength
        val bright = e.style == 1
        Parallel.bands(h) { from, to ->
            for (y in from until to) {
                if (y and 0x3F == 0) check()
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = px[i]
                    val a = focus.weight(x + 0.5f, y + 0.5f) * k
                    if (a <= 0f) { out[i] = c; continue }
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    out[i] = if (bright) Argb.pack(Argb.alpha(c), (r + (255 - r) * a).roundToInt(), (g + (255 - g) * a).roundToInt(), (b + (255 - b) * a).roundToInt())
                    else Argb.pack(Argb.alpha(c), (r * (1f - a)).roundToInt(), (g * (1f - a)).roundToInt(), (b * (1f - a)).roundToInt())
                }
            }
        }
        return out
    }

    private fun lensBlur(px: IntArray, w: Int, h: Int, e: Effect, scale: Float, check: () -> Unit): IntArray {
        val maxRadius = 30f * scale * e.strength
        if (maxRadius < 0.75f) return px.copyOf()
        val n = px.size
        val (r, g, b) = Planes.channels(px)
        // Bright points are pushed above white before the blur so they spread into bright discs, the way light does.
        Parallel.bands(h) { from, to -> for (i in from * w until to * w) { r[i] = bokehUp(r[i]); g[i] = bokehUp(g[i]); b[i] = bokehUp(b[i]) } }
        // A wide blur is worked at half size, once for the whole pyramid, and read back bilinearly.
        val half = maxRadius >= 8f && w >= 32 && h >= 32
        val lw = if (half) (w + 1) / 2 else w; val lh = if (half) (h + 1) / 2 else h
        val sr = if (half) Planes.halve(r, w, h) else r
        val sg = if (half) Planes.halve(g, w, h) else g
        val sb = if (half) Planes.halve(b, w, h) else b
        val k = if (half) 0.5f else 1f
        val levels = floatArrayOf(0.25f, 0.5f, 1f).map { f ->
            val rad = maxRadius * f * k
            val ri = max(1, rad.roundToInt())
            // One flat box pass for the disc's even fill, then a small one to round its corners.
            val round = max(1, (rad * 0.35f).roundToInt())
            check()
            arrayOf(Planes.box(Planes.box(sr, lw, lh, ri), lw, lh, round),
                Planes.box(Planes.box(sg, lw, lh, ri), lw, lh, round),
                Planes.box(Planes.box(sb, lw, lh, ri), lw, lh, round))
        }
        check()
        val focus = Focus(e, w, h)
        val fx = lw.toFloat() / w; val fy = lh.toFloat() / h
        val out = IntArray(n)
        Parallel.bands(h) { from, to ->
            val rgb = FloatArray(6)
            for (y in from until to) {
                val sy = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (lh - 1).toFloat())
                val y0 = sy.toInt(); val y1 = min(y0 + 1, lh - 1); val ty = sy - y0
                for (x in 0 until w) {
                    val i = y * w + x
                    val t = focus.weight(x + 0.5f, y + 0.5f)
                    if (t <= 0f) { out[i] = px[i]; continue }
                    val sx = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (lw - 1).toFloat())
                    val x0 = sx.toInt(); val x1 = min(x0 + 1, lw - 1); val tx = sx - x0
                    val i00 = y0 * lw + x0; val i01 = y0 * lw + x1; val i10 = y1 * lw + x0; val i11 = y1 * lw + x1
                    // 0..3 across sharp, quarter, half and full blur.
                    val f = t * 3f
                    val lo = min(2, f.toInt()); val kk = f - lo
                    for (c in 0 until 3) {
                        for (j in 0 until 2) {
                            val level = lo + j
                            rgb[c * 2 + j] = if (level == 0) when (c) { 0 -> r[i]; 1 -> g[i]; else -> b[i] } else {
                                val p = levels[level - 1][c]
                                val top = p[i00] + (p[i01] - p[i00]) * tx
                                val bottom = p[i10] + (p[i11] - p[i10]) * tx
                                top + (bottom - top) * ty
                            }
                        }
                    }
                    out[i] = pack(px[i], bokehDown(rgb[0] + (rgb[1] - rgb[0]) * kk), bokehDown(rgb[2] + (rgb[3] - rgb[2]) * kk), bokehDown(rgb[4] + (rgb[5] - rgb[4]) * kk))
                }
            }
        }
        return out
    }

    private const val KNEE = 0.72f
    private const val BOKEH = 14f
    private fun bokehUp(v: Float): Float = if (v <= KNEE) v else { val u = v - KNEE; v + BOKEH * u * u }
    /** The exact inverse of [bokehUp], so a pixel the blur leaves alone comes back as it went in. */
    private fun bokehDown(x: Float): Float = if (x <= KNEE) x else (KNEE + (-1f + sqrt(1f + 4f * BOKEH * (x - KNEE))) / (2f * BOKEH))

    // ------------------------------------------------------------------ shared

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun overlay(base: Float, top: Float): Float =
        if (base < 0.5f) 2f * base * top else 1f - 2f * (1f - base) * (1f - top)

    private fun sCurve(v: Float, k: Float): Float {
        val c = v.coerceIn(0f, 1f)
        val s = c * c * (3f - 2f * c)
        return c + (s - c) * k * 2f
    }

    private fun blendTo(c: Int, level: Float, a: Float): Int {
        val rr = ((c shr 16) and 0xFF) / 255f; val gg = ((c shr 8) and 0xFF) / 255f; val bb = (c and 0xFF) / 255f
        return pack(c, rr + (level - rr) * a, gg + (level - gg) * a, bb + (level - bb) * a)
    }

    /** How much a colour looks like skin, 0..1: a warm hue, moderate saturation, not too dark. */
    fun skinWeight(c: Int): Float {
        val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        if (r <= g || g < b) return 0f
        val mx = r; val mn = b
        val sat = (mx - mn) / mx.coerceAtLeast(1e-4f)
        val hue = 60f * (g - b) / (mx - mn).coerceAtLeast(1e-4f) // 0..60 in this branch
        return smooth(2f, 12f, hue) * smooth(52f, 38f, hue) * smooth(0.12f, 0.22f, sat) * smooth(0.7f, 0.55f, sat) * smooth(0.18f, 0.32f, mx)
    }

    /** The saturation factor for an amount: more for muted colours, much less for skin. */
    private fun smartSaturation(c: Int, amount: Float): Float {
        if (amount == 0f) return 1f
        if (amount < 0f) return 1f + amount
        val r = ((c shr 16) and 0xFF); val g = ((c shr 8) and 0xFF); val b = c and 0xFF
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val sat = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
        return 1f + amount * (1f - sat) * (1f - 0.75f * skinWeight(c))
    }

    /** Adds [delta] to the brightness of [c], then scales its colour about its grey by [sat]. */
    private fun finish(c: Int, delta: Float, sat: Float, plainSaturation: Boolean = false): Int {
        var rr = ((c shr 16) and 0xFF) / 255f + delta
        var gg = ((c shr 8) and 0xFF) / 255f + delta
        var bb = (c and 0xFF) / 255f + delta
        if (sat != 1f || plainSaturation) {
            val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
            rr = l + (rr - l) * sat; gg = l + (gg - l) * sat; bb = l + (bb - l) * sat
        }
        return pack(c, rr, gg, bb)
    }

    private fun pack(c: Int, r: Float, g: Float, b: Float): Int =
        (c and 0xFF000000.toInt()) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)

    private fun to8(v: Float): Int = if (v <= 0f) 0 else if (v >= 1f) 255 else (v * 255f + 0.5f).toInt()

    /** A small, fixed random sequence: the same seed draws the same leaks and scratches on every phone. */
    private class Seeded(seed: Int) {
        private var state = seed.toLong() * 6364136223846793005L + 1442695040888963407L
        fun next(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 40) and 0xFFFFFF).toFloat() / 16777216f
        }
        fun int(bound: Int): Int = min(bound - 1, (next() * bound).toInt())
    }
}

/** Value noise from a hash of the lattice: smooth, seedable, and the same at any resolution. */
internal object Noise {
    fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    fun value(x: Float, y: Float, seed: Int): Float {
        val fx = kotlin.math.floor(x); val fy = kotlin.math.floor(y)
        val ix = fx.toInt(); val iy = fy.toInt()
        val tx = x - fx; val ty = y - fy
        val sx = tx * tx * (3f - 2f * tx); val sy = ty * ty * (3f - 2f * ty)
        val a = hash(ix, iy, seed); val b = hash(ix + 1, iy, seed)
        val c = hash(ix, iy + 1, seed); val d = hash(ix + 1, iy + 1, seed)
        val top = a + (b - a) * sx; val bottom = c + (d - c) * sx
        return top + (bottom - top) * sy
    }

    /** [octaves] of [value], each twice the frequency and half the weight; 0..1. */
    fun fbm(x: Float, y: Float, seed: Int, octaves: Int): Float {
        var sum = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        for (o in 0 until octaves) {
            sum += value(x * f + o * 19.1f, y * f + o * 7.7f, seed + o * 1013) * amp
            norm += amp; amp *= 0.5f; f *= 2.03f
        }
        return sum / norm
    }
}

/** Single-channel float planes and the blurs on them: cheaper than packed pixels for a channel at a time. */
internal object Planes {
    fun luma(px: IntArray): FloatArray {
        val out = FloatArray(px.size)
        val n = px.size; val step = 4096
        Parallel.bands((n + step - 1) / step, minimum = 8) { from, to ->
            for (i in from * step until min(n, to * step)) {
                val c = px[i]
                out[i] = (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
            }
        }
        return out
    }

    /** Bilinear read of a plane at a fractional position, clamped to its edges. */
    fun sample(p: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (w - 1).toFloat()); val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt(); val y0 = cy.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val tx = cx - x0; val ty = cy - y0
        val a = p[y0 * w + x0] + (p[y0 * w + x1] - p[y0 * w + x0]) * tx
        val b = p[y1 * w + x0] + (p[y1 * w + x1] - p[y1 * w + x0]) * tx
        return a + (b - a) * ty
    }

    fun channels(px: IntArray): Triple<FloatArray, FloatArray, FloatArray> {
        val r = FloatArray(px.size); val g = FloatArray(px.size); val b = FloatArray(px.size)
        val n = px.size; val step = 4096
        Parallel.bands((n + step - 1) / step, minimum = 8) { from, to ->
            for (i in from * step until min(n, to * step)) {
                val c = px[i]
                r[i] = ((c shr 16) and 0xFF) / 255f; g[i] = ((c shr 8) and 0xFF) / 255f; b[i] = (c and 0xFF) / 255f
            }
        }
        return Triple(r, g, b)
    }

    /**
     * [passes] of a separable box of [radius] with clamped edges, rows and columns across the
     * cores. A wide blur is done at half size and read back bilinearly: at a radius of eight or
     * more the difference is below what 8 bits hold, and it costs a quarter.
     */
    fun blur(src: FloatArray, w: Int, h: Int, radius: Int, passes: Int = 2, check: () -> Unit = {}): FloatArray {
        if (radius <= 0) return src.copyOf()
        if (radius >= HALF_FROM && w >= 32 && h >= 32) {
            val hw = (w + 1) / 2; val hh = (h + 1) / 2
            return upsample(full(halve(src, w, h), hw, hh, (radius + 1) / 2, passes, check), hw, hh, w, h)
        }
        return full(src, w, h, radius, passes, check)
    }

    /** One box pass at full size, whatever the radius. */
    fun box(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray = full(src, w, h, radius, 1) {}

    /** Each 2×2 block averaged into one: the plane at half size, rounded up. */
    fun halve(src: FloatArray, w: Int, h: Int): FloatArray {
        val hw = (w + 1) / 2; val hh = (h + 1) / 2
        val small = FloatArray(hw * hh)
        Parallel.bands(hh) { from, to ->
            for (y in from until to) {
                val y0 = 2 * y; val y1 = min(2 * y + 1, h - 1)
                for (x in 0 until hw) {
                    val x0 = 2 * x; val x1 = min(2 * x + 1, w - 1)
                    small[y * hw + x] = 0.25f * (src[y0 * w + x0] + src[y0 * w + x1] + src[y1 * w + x0] + src[y1 * w + x1])
                }
            }
        }
        return small
    }

    private const val HALF_FROM = 8

    /** Bilinear, with pixel centres lined up: half-size pixel i covers full-size pixels 2i and 2i+1. */
    fun upsample(small: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val fx = sw.toFloat() / w; val fy = sh.toFloat() / h
        Parallel.bands(h) { from, to ->
            for (y in from until to) {
                val sy = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                val y0 = sy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = sy - y0
                for (x in 0 until w) {
                    val sx = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                    val x0 = sx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = sx - x0
                    val a = small[y0 * sw + x0] + (small[y0 * sw + x1] - small[y0 * sw + x0]) * tx
                    val b = small[y1 * sw + x0] + (small[y1 * sw + x1] - small[y1 * sw + x0]) * tx
                    out[y * w + x] = a + (b - a) * ty
                }
            }
        }
        return out
    }

    private fun full(src: FloatArray, w: Int, h: Int, radius: Int, passes: Int, check: () -> Unit): FloatArray {
        val a = src.copyOf()
        val tmp = FloatArray(src.size)
        repeat(passes) {
            check()
            rows(a, tmp, w, h, radius)
            columns(tmp, a, w, h, radius)
        }
        return a
    }

    private fun rows(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        Parallel.bands(h) { from, to ->
            for (y in from until to) {
                val row = y * w
                var sum = 0f
                for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    dst[row + x] = sum * inv
                    sum += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
                }
            }
        }
    }

    /** Running sums down a band of columns at a time, reading whole rows: kind to the cache. */
    private fun columns(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        Parallel.bands(w, minimum = 64) { from, to ->
            val span = to - from
            val sums = FloatArray(span)
            for (k in -r..r) { val row = k.coerceIn(0, h - 1) * w + from; for (j in 0 until span) sums[j] += src[row + j] }
            for (y in 0 until h) {
                val o = y * w + from
                for (j in 0 until span) dst[o + j] = sums[j] * inv
                val add = min(y + r + 1, h - 1) * w + from; val sub = max(y - r, 0) * w + from
                for (j in 0 until span) sums[j] += src[add + j] - src[sub + j]
            }
        }
    }

    /**
     * The self-guided filter: a blur that stops at edges, because where the local variance is
     * well above [eps] the output follows the input. The base layer for local tone mapping
     * without halos round every edge.
     */
    /**
     * The guided filter: smooth where the picture is flat, untouched across edges. From a radius
     * of eight its coefficients are worked at half size and read back up (the "fast guided
     * filter"): they vary slowly by construction, so the result differs below what 8 bits hold
     * and it costs about a quarter. Every full-size loop runs across the cores.
     */
    fun guided(src: FloatArray, w: Int, h: Int, radius: Int, eps: Float, check: () -> Unit = {}): FloatArray {
        val half = radius >= HALF_FROM && w >= 32 && h >= 32
        val gw = if (half) (w + 1) / 2 else w; val gh = if (half) (h + 1) / 2 else h
        val g = if (half) halve(src, w, h) else src
        val r = if (half) (radius + 1) / 2 else radius
        val n = gw * gh
        val mean = full(g, gw, gh, r, 1, check)
        val sq = FloatArray(n)
        Parallel.bands(gh) { from, to -> for (i in from * gw until to * gw) sq[i] = g[i] * g[i] }
        val meanSq = full(sq, gw, gh, r, 1, check)
        val a = FloatArray(n); val b = sq // reuse
        Parallel.bands(gh) { from, to ->
            for (i in from * gw until to * gw) {
                val v = (meanSq[i] - mean[i] * mean[i]).coerceAtLeast(0f)
                val k = v / (v + eps)
                a[i] = k; b[i] = mean[i] - k * mean[i]
            }
        }
        var am = full(a, gw, gh, r, 1, check); var bm = full(b, gw, gh, r, 1, check)
        if (half) { am = upsample(am, gw, gh, w, h); bm = upsample(bm, gw, gh, w, h) }
        val out = FloatArray(w * h)
        Parallel.bands(h) { from, to -> for (i in from * w until to * w) out[i] = am[i] * src[i] + bm[i] }
        return out
    }

}
