package com.layerbit.still.engine

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How the second picture meets the first. The formulas are the W3C compositing ones, per channel. */
enum class BlendMode { NORMAL, LIGHTEN, DARKEN, SCREEN, MULTIPLY, OVERLAY, SOFT_LIGHT, ADD }

/**
 * A second picture laid over the first: the classic double exposure.
 *
 * [file] names the app's own private copy of the second picture, kept beside the project's source;
 * empty means none. The placement is resolution-free: [x], [y] are the centre as canvas fractions,
 * [scale] 1 is the second picture just covering the canvas, and [angle] turns it about its centre.
 * [insideSubject] keeps the second picture inside the subject the cut-out finds, which is the
 * silhouette double exposure.
 */
data class DoubleExposure(
    val file: String = "",
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scale: Float = 1f,
    /** Degrees, positive clockwise. */
    val angle: Float = 0f,
    val mode: BlendMode = BlendMode.SCREEN,
    val opacity: Float = 0.8f,
    val insideSubject: Boolean = false
) {
    val isIdentity: Boolean get() = this == DoubleExposure()
    val active: Boolean get() = file.isNotEmpty() && opacity > 0f

    val valid: Boolean
        get() = (file.isEmpty() || safeName(file)) && x.isFinite() && y.isFinite() && x in -1f..2f && y in -1f..2f &&
            scale in MIN_SCALE..MAX_SCALE && angle.isFinite() && angle in -360f..360f && opacity in 0f..1f

    val placement: Blend.Placement get() = Blend.Placement(x, y, scale, angle)

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 8f
        /** A private file name, never a path: letters, digits, dot, dash, underscore, no leading dot. */
        fun safeName(name: String): Boolean = name.length in 1..80 && name[0] != '.' && name.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_' }
    }
}

/** The blend formulas and the placement of a second picture, on packed ARGB. */
object Blend {

    /** One channel of [mode] with base [b] and top [t], both 0..1. */
    fun channel(mode: BlendMode, b: Float, t: Float): Float = when (mode) {
        BlendMode.NORMAL -> t
        BlendMode.LIGHTEN -> max(b, t)
        BlendMode.DARKEN -> min(b, t)
        BlendMode.SCREEN -> b + t - b * t
        BlendMode.MULTIPLY -> b * t
        BlendMode.OVERLAY -> if (b <= 0.5f) 2f * b * t else 1f - 2f * (1f - b) * (1f - t)
        BlendMode.SOFT_LIGHT -> if (t <= 0.5f) b - (1f - 2f * t) * b * (1f - b) else {
            val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b else sqrt(b)
            b + (2f * t - 1f) * (d - b)
        }
        BlendMode.ADD -> min(1f, b + t)
    }

    /** [top] blended onto [base] by [mode], then mixed in by [amount] (0..1); base alpha kept. */
    fun pixel(mode: BlendMode, base: Int, top: Int, amount: Float): Int {
        if (amount <= 0f) return base
        fun ch(shift: Int): Int {
            val b = ((base shr shift) and 0xFF) / 255f; val t = ((top shr shift) and 0xFF) / 255f
            val v = b + (channel(mode, b, t) - b) * amount
            return (v * 255f).roundToInt().coerceIn(0, 255)
        }
        return Argb.pack(Argb.alpha(base), ch(16), ch(8), ch(0))
    }

    /**
     * Where the second picture sits: centre ([cx], [cy]) as canvas fractions, [scale] relative to
     * just covering the canvas, [angle] in degrees clockwise.
     */
    data class Placement(val cx: Float, val cy: Float, val scale: Float, val angle: Float) {

        /** The layer's drawn size in canvas pixels for a [cw] x [ch] canvas and a layer of aspect [aspect]. */
        fun drawn(cw: Int, ch: Int, aspect: Float): FloatArray {
            val coverH = max(ch.toFloat(), cw / aspect)
            return floatArrayOf(coverH * aspect * scale, coverH * scale)
        }

        /** Canvas pixel ([px], [py]) to layer fractions (u, v); inside the layer when both are in 0..1. */
        fun toLayer(px: Float, py: Float, cw: Int, ch: Int, aspect: Float): FloatArray {
            val d = drawn(cw, ch, aspect)
            val dx = px - cx * cw; val dy = py - cy * ch
            val a = Math.toRadians(angle.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            // Undo the clockwise turn.
            val rx = dx * c + dy * s; val ry = -dx * s + dy * c
            return floatArrayOf(rx / d[0] + 0.5f, ry / d[1] + 0.5f)
        }

        /** Layer fractions (u, v) to canvas pixels: the inverse of [toLayer]. */
        fun toCanvas(u: Float, v: Float, cw: Int, ch: Int, aspect: Float): FloatArray {
            val d = drawn(cw, ch, aspect)
            val rx = (u - 0.5f) * d[0]; val ry = (v - 0.5f) * d[1]
            val a = Math.toRadians(angle.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            return floatArrayOf(rx * c - ry * s + cx * cw, rx * s + ry * c + cy * ch)
        }
    }

    /**
     * Lays [layer] ([lw] x [lh], ARGB) over [pixels] ([w] x [h]) in place. The layer is read
     * bilinearly at its placed position, its own alpha respected, its edge anti-aliased over one
     * canvas pixel. With [mask] ([mw] x [mh], 0..255) the layer shows only where the mask is.
     */
    fun composite(
        pixels: IntArray, w: Int, h: Int, layer: IntArray, lw: Int, lh: Int,
        d: DoubleExposure, mask: IntArray? = null, mw: Int = 0, mh: Int = 0, check: () -> Unit = {}
    ) {
        if (lw <= 0 || lh <= 0 || d.opacity <= 0f) return
        val p = d.placement
        val aspect = lw.toFloat() / lh
        val size = p.drawn(w, h, aspect)
        // One canvas pixel in layer fractions, for the soft edge.
        val edgeU = 1f / max(1f, size[0]); val edgeV = 1f / max(1f, size[1])
        val sx = if (mask != null) (mw - 1).toFloat() / max(1, w - 1) else 0f
        val sy = if (mask != null) (mh - 1).toFloat() / max(1, h - 1) else 0f
        Parallel.bands(h) { from, to -> for (y in from until to) {
            if (y and 0x3F == 0) check()
            for (x in 0 until w) {
                val uv = p.toLayer(x + 0.5f, y + 0.5f, w, h, aspect)
                val u = uv[0]; val v = uv[1]
                if (u < -edgeU || u > 1f + edgeU || v < -edgeV || v > 1f + edgeV) continue
                val cover = min(min((u + edgeU) / (2 * edgeU), (1f + edgeU - u) / (2 * edgeU)), min((v + edgeV) / (2 * edgeV), (1f + edgeV - v) / (2 * edgeV))).coerceIn(0f, 1f)
                if (cover <= 0f) continue
                val top = sampleArgb(layer, lw, lh, u * lw - 0.5f, v * lh - 0.5f)
                var amount = d.opacity * cover * Argb.alpha(top) / 255f
                if (mask != null) amount *= Segment.sample(mask, mw, mh, x * sx, y * sy) / 255f
                if (amount <= 0f) continue
                val i = y * w + x
                pixels[i] = pixel(d.mode, pixels[i], top, amount)
            }
        } }
    }

    /** Bilinear ARGB at a pixel-centre position, clamped at the edges. */
    fun sampleArgb(map: IntArray, mw: Int, mh: Int, fx: Float, fy: Float): Int {
        val cx = fx.coerceIn(0f, (mw - 1).toFloat()); val cy = fy.coerceIn(0f, (mh - 1).toFloat())
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val x1 = min(mw - 1, x0 + 1); val y1 = min(mh - 1, y0 + 1)
        val wx = cx - x0; val wy = cy - y0
        val a = map[y0 * mw + x0]; val b = map[y0 * mw + x1]; val c = map[y1 * mw + x0]; val e = map[y1 * mw + x1]
        fun ch(shift: Int): Int {
            val top = ((a ushr shift) and 0xFF) * (1 - wx) + ((b ushr shift) and 0xFF) * wx
            val bottom = ((c ushr shift) and 0xFF) * (1 - wx) + ((e ushr shift) and 0xFF) * wx
            return (top * (1 - wy) + bottom * wy).roundToInt().coerceIn(0, 255)
        }
        return Argb.pack(ch(24), ch(16), ch(8), ch(0))
    }

    /** How many layer pixels to decode for a [cw] x [ch] canvas: as many as it covers there, so it is never softer than the canvas. */
    fun layerPixels(cw: Int, ch: Int, p: Placement, aspect: Float, cap: Long): Long {
        val d = p.drawn(cw, ch, aspect)
        return (d[0].toDouble() * d[1]).toLong().coerceIn(10_000L, max(10_000L, cap))
    }
}
