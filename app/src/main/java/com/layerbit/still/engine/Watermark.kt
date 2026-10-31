package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where a watermark goes: repeated across the picture, or once in a corner or the middle. */
enum class WatermarkPlace {
    TILED, TOP_LEFT, TOP_RIGHT, CENTRE, BOTTOM_LEFT, BOTTOM_RIGHT;

    val single: Boolean get() = this != TILED
}

/**
 * Every number that decides where a watermark's pixels land. The renderer measures the text
 * with its font and asks here for the size, the spot and the grid, so a 900-pixel preview and
 * a 6000-pixel export put the mark in the same place at the same share of the picture.
 */
object WatermarkLayout {

    /** Text height per unit of the mark's width, as the editor has always drawn it. */
    const val TEXT_PER_WIDTH = 3.2f

    /** The gap between a single mark and the picture's edge, as a share of the reference edge. */
    const val MARGIN = 0.03f

    /** Smallest text drawn, in pixels, so a tiny thumbnail still shows something. */
    const val MIN_TEXT_PX = 8f

    /** Copies across the grid stop here, whatever the text, so a one-letter mark cannot stall a render. */
    const val MAX_TILES = 4000

    /** Text size in pixels for a mark of [width] on [canvas]: scaled with the picture. */
    fun textSize(width: Float, canvas: Geometry2D.Size): Float =
        max(MIN_TEXT_PX, width * TEXT_PER_WIDTH * Geometry2D.REFERENCE_EDGE * Geometry2D.resolutionScale(canvas))

    /** Gap to the edge for a single mark, in pixels. */
    fun margin(canvas: Geometry2D.Size): Float = MARGIN * Geometry2D.REFERENCE_EDGE * Geometry2D.resolutionScale(canvas)

    /**
     * One mark: the centre of its text box in canvas pixels, and the factor to shrink the text by
     * so the turned box stays inside the margins (1 when it already fits).
     */
    data class Single(val centreX: Float, val centreY: Float, val scale: Float)

    /** The bounding box of a [w] × [h] box turned by [degrees]. */
    fun turnedBox(w: Float, h: Float, degrees: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees.toDouble())
        val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
        return (w * c + h * s) to (w * s + h * c)
    }

    /**
     * Where a single mark sits. [textWidth] and [textHeight] are the text box measured at
     * [textSize]; the box is turned by [degrees] around its centre.
     */
    fun single(place: WatermarkPlace, canvas: Geometry2D.Size, textWidth: Float, textHeight: Float, degrees: Float): Single {
        val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
        val m = min(margin(canvas), min(w, h) / 4f)
        val (bw, bh) = turnedBox(max(1f, textWidth), max(1f, textHeight), degrees)
        val scale = min(1f, min((w - 2 * m) / bw, (h - 2 * m) / bh)).coerceAtLeast(0.01f)
        val hw = bw * scale / 2f; val hh = bh * scale / 2f
        val x = when (place) {
            WatermarkPlace.TOP_LEFT, WatermarkPlace.BOTTOM_LEFT -> m + hw
            WatermarkPlace.TOP_RIGHT, WatermarkPlace.BOTTOM_RIGHT -> w - m - hw
            else -> w / 2f
        }
        val y = when (place) {
            WatermarkPlace.TOP_LEFT, WatermarkPlace.TOP_RIGHT -> m + hh
            WatermarkPlace.BOTTOM_LEFT, WatermarkPlace.BOTTOM_RIGHT -> h - m - hh
            else -> h / 2f
        }
        return Single(x, y, scale)
    }

    /**
     * The tiled grid: left-baseline origins of each copy, in canvas pixels before the canvas is
     * turned by the mark's angle around its centre. Rows are offset by half a step, like brick,
     * and the grid covers the canvas's diagonal so no corner is left bare after the turn.
     */
    fun tiles(canvas: Geometry2D.Size, textWidth: Float, textSize: Float): List<Pt> {
        val stepX = max(1f, textWidth * 1.6f)
        val stepY = max(1f, textSize * 3.2f)
        val cx = canvas.width / 2f; val cy = canvas.height / 2f
        val diag = hypot(canvas.width.toFloat(), canvas.height.toFloat())
        val out = ArrayList<Pt>()
        var y = cy - diag
        var row = 0
        while (y < cy + diag && out.size < MAX_TILES) {
            var x = cx - diag + (if (row % 2 == 0) 0f else stepX / 2)
            while (x < cx + diag && out.size < MAX_TILES) {
                out += Pt(x, y)
                x += stepX
            }
            y += stepY; row++
        }
        return out
    }
}
