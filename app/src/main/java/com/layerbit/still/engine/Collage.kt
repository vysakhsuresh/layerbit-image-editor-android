package com.layerbit.still.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where each picture goes on a collage. Pure arithmetic in fractions of the canvas. */
object Collage {

    enum class Layout { GRID, COLUMNS, FEATURE, STRIP, POLAROID }

    /** A cell as fractions of the canvas, and the aspect it was planned for. */
    data class Cell(val left: Float, val top: Float, val width: Float, val height: Float)

    data class Plan(val canvasAspect: Float, val cells: List<Cell>)

    /**
     * @param aspects width/height of each picture, in order.
     * @param gutter fraction of the shorter canvas edge between pictures and at the edge.
     */
    fun plan(layout: Layout, aspects: List<Float>, gutter: Float = 0.02f): Plan {
        val n = aspects.size
        require(n >= 1)
        return when (layout) {
            Layout.GRID -> grid(n, gutter, ceil(sqrt(n.toFloat())).toInt())
            Layout.COLUMNS -> columns(aspects, gutter)
            Layout.FEATURE -> feature(n, gutter)
            Layout.STRIP -> strip(aspects, gutter)
            Layout.POLAROID -> grid(n, gutter * 2.5f, ceil(sqrt(n.toFloat())).toInt(), square = true)
        }
    }

    private fun grid(n: Int, gutter: Float, columns: Int, square: Boolean = false): Plan {
        val rows = ceil(n / columns.toFloat()).toInt()
        val g = gutter
        val cw = (1f - g * (columns + 1)) / columns
        val aspect = if (square) columns.toFloat() / rows else 4f / 3f * columns / rows
        val ch = (1f - g * (rows + 1)) / rows
        val cells = (0 until n).map { i ->
            val c = i % columns; val r = i / columns
            Cell(g + c * (cw + g), g + r * (ch + g), cw, ch)
        }
        return Plan(aspect, cells)
    }

    /** Pictures in two or three columns, each column stacked by height like a masonry wall. */
    private fun columns(aspects: List<Float>, gutter: Float): Plan {
        val n = aspects.size
        val columns = if (n <= 2) n else if (n <= 6) 2 else 3
        val g = gutter
        val cw = (1f - g * (columns + 1)) / columns
        val heights = FloatArray(columns)
        val placed = ArrayList<Cell>()
        for (a in aspects) {
            val col = heights.indices.minByOrNull { heights[it] } ?: 0
            val h = cw / max(0.2f, a)
            placed += Cell(g + col * (cw + g), g + heights[col], cw, h)
            heights[col] += h + g
        }
        val total = heights.max()
        // Normalise heights so the tallest column fills the canvas.
        val cells = placed.map { Cell(it.left, it.top / total, it.width, it.height / total) }
        return Plan(1f / total, cells)
    }

    /** One large picture and the rest in a row beneath it. */
    private fun feature(n: Int, gutter: Float): Plan {
        if (n == 1) return grid(1, gutter, 1)
        val g = gutter
        val rest = n - 1
        val bigH = 0.62f
        val smallW = (1f - g * (rest + 1)) / rest
        val smallH = 1f - bigH - 3 * g
        val cells = ArrayList<Cell>()
        cells += Cell(g, g, 1f - 2 * g, bigH)
        for (i in 0 until rest) cells += Cell(g + i * (smallW + g), 2 * g + bigH, smallW, smallH)
        return Plan(0.85f, cells)
    }

    /** A single row of pictures at their own aspects, the "before and after" shape. */
    private fun strip(aspects: List<Float>, gutter: Float): Plan {
        val g = gutter
        val widths = aspects.map { max(0.2f, it) }
        val total = widths.sum()
        val cells = ArrayList<Cell>()
        var x = g
        val usable = 1f - g * (aspects.size + 1)
        for (w in widths) {
            val cw = usable * w / total
            cells += Cell(x, g, cw, 1f - 2 * g)
            x += cw + g
        }
        return Plan(total / (1f - 2 * g) * usable, cells)
    }

    /** The canvas in pixels: as large as the budget allows, at the plan's aspect. */
    fun canvas(plan: Plan, maxPixels: Long, maxEdge: Int = 4096): Geometry2D.Size {
        val w = sqrt(maxPixels * plan.canvasAspect.toDouble()).roundToInt()
        val h = (w / plan.canvasAspect).roundToInt()
        return Geometry2D.fit(max(1, w), max(1, h), maxPixels, maxEdge)
    }
}
