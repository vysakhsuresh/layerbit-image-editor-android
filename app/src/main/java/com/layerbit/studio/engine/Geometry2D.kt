package com.layerbit.studio.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Sizes, crops and the arithmetic of fitting pictures into budgets. No pixels. */
object Geometry2D {

    data class Size(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
        val aspect: Float get() = width.toFloat() / height
    }

    /** The largest size at most [maxPixels] in area and [maxEdge] on a side, keeping the aspect. */
    fun fit(w: Int, h: Int, maxPixels: Long, maxEdge: Int = Geometry.MAX_EDGE): Size {
        require(w > 0 && h > 0 && maxPixels > 0)
        val scale = minOf(1.0, sqrt(maxPixels.toDouble() / w / h), maxEdge.toDouble() / max(w, h))
        val outW = max(1, floor(w * scale).toInt()).coerceAtMost(maxPixels.toInt().coerceAtLeast(1))
        val outH = max(1, floor(h * scale).toInt()).coerceAtMost((maxPixels / outW).toInt().coerceAtLeast(1))
        return Size(outW, outH)
    }

    /**
     * The power-of-two sample step that decodes no more than [maxPixels], taking the orientation
     * swap into account only in area, which is symmetric.
     */
    fun sampleSize(w: Int, h: Int, maxPixels: Long, maxEdge: Int = Geometry.MAX_EDGE): Int {
        var sample = 1
        while (ceilDiv(w, sample).toLong() * ceilDiv(h, sample) > maxPixels || ceilDiv(max(w, h), sample) > maxEdge) sample *= 2
        return sample
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /** The bounding box of a [w]×[h] rectangle turned by [degrees]. */
    fun rotatedBounds(w: Int, h: Int, degrees: Float): Size {
        val r = Math.toRadians(degrees.toDouble())
        val c = abs(cos(r)); val s = abs(sin(r))
        return Size(max(1, ceil(w * c + h * s - 1e-6).toInt()), max(1, ceil(w * s + h * c - 1e-6).toInt()))
    }

    /** The size after quarter turns: odd turns swap the edges. */
    fun turned(size: Size, quarterTurns: Int): Size = if (quarterTurns % 2 == 1) Size(size.height, size.width) else size

    /**
     * The size of the canvas a recipe's geometry produces from an upright source of [source]
     * pixels, before the frame: perspective keeps the size, turns swap it, straightening grows
     * it to the bounding box, the crop takes its fraction, and an explicit size replaces it.
     */
    fun canvasSize(source: Size, g: Geometry): Size {
        if (g.width > 0 && g.height > 0) return Size(g.width, g.height)
        val turned = turned(source, g.quarterTurns)
        val straightened = if (abs(g.straighten) < 0.01f) turned else rotatedBounds(turned.width, turned.height, g.straighten)
        return Size(
            max(1, (straightened.width * g.crop.width).roundToInt()),
            max(1, (straightened.height * g.crop.height).roundToInt())
        )
    }

    /** The canvas plus the frame's padding and border. */
    fun framedSize(canvas: Size, frame: Frame): Size {
        if (frame.isIdentity) return canvas
        val shorter = min(canvas.width, canvas.height)
        val inset = ((frame.padding + frame.border) * shorter).roundToInt()
        var w = canvas.width + 2 * inset
        var h = canvas.height + 2 * inset
        if (frame.aspect > 0f) {
            if (w.toFloat() / h < frame.aspect) w = (h * frame.aspect).roundToInt() else h = (w / frame.aspect).roundToInt()
        }
        return Size(max(1, w), max(1, h))
    }

    /**
     * The largest centred crop with [aspect] (width/height) in a canvas of [canvas] size,
     * where the canvas is what the crop is measured against - so a preset chosen after a
     * quarter turn means what it says.
     */
    fun aspectCrop(canvas: Size, aspect: Float): Crop {
        require(aspect > 0f && aspect.isFinite())
        val current = canvas.aspect
        return if (current > aspect) {
            val f = aspect / current
            Crop((1 - f) / 2, 0f, (1 + f) / 2, 1f)
        } else {
            val f = current / aspect
            Crop(0f, (1 - f) / 2, 1f, (1 + f) / 2)
        }
    }

    /**
     * Keeps a crop inside the unit square and no smaller than the minimum, moving the edge that
     * was dragged rather than the opposite one. [lockAspect] is the width/height to hold, or 0.
     */
    fun constrain(c: Crop, lockAspect: Float = 0f, canvas: Size? = null): Crop {
        var left = c.left.coerceIn(0f, 1f - Crop.MIN)
        var top = c.top.coerceIn(0f, 1f - Crop.MIN)
        var right = c.right.coerceIn(left + Crop.MIN, 1f)
        var bottom = c.bottom.coerceIn(top + Crop.MIN, 1f)
        if (lockAspect > 0f && canvas != null) {
            // Aspect in pixels, converted to fractions of a canvas that is not square.
            val fracAspect = lockAspect / canvas.aspect
            val w = right - left
            val h = bottom - top
            if (w / h > fracAspect) {
                val nw = h * fracAspect
                val cx = (left + right) / 2
                left = (cx - nw / 2).coerceIn(0f, 1f - nw); right = left + nw
            } else {
                val nh = w / fracAspect
                val cy = (top + bottom) / 2
                top = (cy - nh / 2).coerceIn(0f, 1f - nh); bottom = top + nh
            }
        }
        return Crop(left, top, right, bottom)
    }

    /**
     * A crop measured on the source, moved into the frame after [quarterTurns] clockwise and
     * flips, which is where the current model measures it. Used to read 0.1 recipes.
     */
    fun rotateCrop(c: Crop, rotation: Int, flipX: Boolean, flipY: Boolean): Crop {
        var left = c.left; var top = c.top; var right = c.right; var bottom = c.bottom
        if (flipX) { val l = 1 - right; right = 1 - left; left = l }
        if (flipY) { val t = 1 - bottom; bottom = 1 - top; top = t }
        repeat(((rotation % 360) + 360) % 360 / 90) {
            // Clockwise quarter turn: (x, y) -> (1 - y, x).
            val nl = 1 - bottom; val nr = 1 - top
            val nt = left; val nb = right
            left = nl; right = nr; top = nt; bottom = nb
        }
        return Crop(left, top, right, bottom)
    }

    /** A fraction of the shorter edge in pixels, never below 1. */
    fun px(fraction: Float, size: Size): Float = max(1f, fraction * min(size.width, size.height))

    /**
     * How much a kernel radius chosen on a reference canvas of about [REFERENCE_EDGE] pixels
     * scales to [size]: the one number that makes blur, sharpening and clarity look the same on
     * a 900-pixel preview and a 6000-pixel export.
     */
    fun resolutionScale(size: Size): Float = max(0.05f, min(size.width, size.height) / REFERENCE_EDGE)

    const val REFERENCE_EDGE = 1000f
}
