package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where each picture goes on a collage, and which part of it shows. Pure arithmetic in
 * fractions: of the canvas for cells, of the canvas's shorter edge for spacing and corners, and
 * of the picture for the part that shows. Nothing here knows a pixel count, so the preview on a
 * phone and the export at twelve megapixels are the same plan drawn at two sizes.
 *
 * Automatic first: [plan] lays the pictures out and [Framing.CENTRED] (or [focus], the picture's
 * busiest region) covers each cell. Then the person steers: [pan], [zoom] and [swap].
 */
object Collage {

    enum class Layout { GRID, COLUMNS, FEATURE, STRIP, POLAROID }

    /** A cell as fractions of the canvas. */
    data class Cell(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
    }

    /**
     * Spacing and look. Each is a fraction of the canvas's shorter edge, so the same number gives
     * the same look on a 360-pixel preview and on the exported file.
     *
     * @param gutter space between two pictures.
     * @param margin space between the pictures and the canvas edge.
     * @param radius corner rounding of each cell.
     */
    data class Style(val gutter: Float = 0.02f, val margin: Float = 0.02f, val radius: Float = 0f) {
        fun clamped(): Style = Style(gutter.coerceIn(GUTTER), margin.coerceIn(MARGIN), radius.coerceIn(RADIUS))

        companion object {
            val GUTTER = 0f..0.08f
            val MARGIN = 0f..0.1f
            val RADIUS = 0f..0.1f
        }
    }

    data class Plan(val canvasAspect: Float, val cells: List<Cell>, val style: Style = Style())

    /**
     * Where a picture sits in its cell: the point of the picture, in fractions of it, that the
     * cell is centred on, and how far it is zoomed in past just covering the cell. Independent of
     * the cell's shape, so a framing survives a change of layout.
     */
    data class Framing(val cx: Float = 0.5f, val cy: Float = 0.5f, val zoom: Float = 1f) {
        companion object {
            const val MAX_ZOOM = 4f
            val CENTRED = Framing()
        }
    }

    /** The part of a picture that shows in a cell, in fractions of the picture. */
    data class Window(val left: Float, val top: Float, val width: Float, val height: Float)

    /** A rectangle in pixels of some drawing surface. */
    data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    // ------------------------------------------------------------------ layout

    /**
     * @param aspects width/height of each picture, in order.
     * @param style spacing and corners; clamped to its ranges.
     */
    fun plan(layout: Layout, aspects: List<Float>, style: Style = Style()): Plan {
        val n = aspects.size
        require(n >= 1)
        val s = style.clamped()
        val cols = ceil(sqrt(n.toFloat())).toInt()
        val rows = ceil(n / cols.toFloat()).toInt()
        val built = when (layout) {
            Layout.GRID -> grid(n, cols, rows, 4f / 3f * cols / rows, s.gutter, s.margin)
            Layout.POLAROID -> grid(n, cols, rows, cols.toFloat() / rows, max(s.gutter, 0.04f), max(s.margin, 0.04f))
            Layout.FEATURE -> feature(n, s.gutter, s.margin)
            Layout.COLUMNS -> settle { short -> columns(aspects, s.gutter * short, s.margin * short) }
            Layout.STRIP -> settle { short -> strip(aspects, s.gutter * short, s.margin * short) }
        }
        val (w, h, rects) = built
        return Plan(w / h, rects.map { Cell(it.left / w, it.top / h, it.width / w, it.height / h) }, s)
    }

    private data class Built(val width: Float, val height: Float, val cells: List<Cell>)

    /**
     * Layouts whose canvas shape follows from the spacing: the spacing is a fraction of the
     * shorter edge, which depends on the spacing. A few rounds settle it to well under a pixel.
     */
    private fun settle(build: (Float) -> Built): Built {
        var short = 1f
        var b = build(short)
        repeat(8) {
            short = min(b.width, b.height)
            b = build(short)
        }
        return b
    }

    /** A canvas [aspect] wide and 1 high; spacing in fractions of its shorter edge. */
    private fun grid(n: Int, cols: Int, rows: Int, aspect: Float, gutter: Float, margin: Float): Built {
        val w = aspect; val h = 1f
        val short = min(w, h)
        val g = gutter * short; val m = margin * short
        val cw = (w - 2 * m - g * (cols - 1)) / cols
        val ch = (h - 2 * m - g * (rows - 1)) / rows
        // A short last row widens to fill it: three pictures make two over one wide, never a blank cell.
        val lastCount = n - (rows - 1) * cols
        val lastW = (w - 2 * m - g * (lastCount - 1)) / lastCount
        val cells = (0 until n).map { i ->
            val c = i % cols; val r = i / cols
            if (r == rows - 1 && lastCount < cols) Cell(m + c * (lastW + g), m + r * (ch + g), lastW, ch)
            else Cell(m + c * (cw + g), m + r * (ch + g), cw, ch)
        }
        return Built(w, h, cells)
    }

    /** Pictures in two or three columns, each column stretched so all end on the same line. */
    private fun columns(aspects: List<Float>, g: Float, m: Float): Built {
        val n = aspects.size
        val cols = if (n <= 2) n else if (n <= 6) 2 else 3
        val w = 1f
        val cw = (w - 2 * m - g * (cols - 1)) / cols
        val members = List(cols) { ArrayList<Int>() }
        val natural = FloatArray(cols)
        for ((i, a) in aspects.withIndex()) {
            val col = natural.indices.minByOrNull { natural[it] } ?: 0
            members[col] += i
            natural[col] += cw / max(0.2f, a) + g
        }
        val tallest = natural.max() - g
        val h = tallest + 2 * m
        val cells = arrayOfNulls<Cell>(n)
        for (col in 0 until cols) {
            val list = members[col]
            if (list.isEmpty()) continue
            val sum = list.sumOf { (cw / max(0.2f, aspects[it])).toDouble() }.toFloat()
            val available = tallest - g * (list.size - 1)
            var y = m
            for (i in list) {
                val ch = cw / max(0.2f, aspects[i]) * available / sum
                cells[i] = Cell(m + col * (cw + g), y, cw, ch)
                y += ch + g
            }
        }
        return Built(w, h, cells.map { it!! })
    }

    /** One large picture, the rest in rows of up to four beneath it. */
    private fun feature(n: Int, gutter: Float, margin: Float): Built {
        if (n == 1) return grid(1, 1, 1, 0.85f, gutter, margin)
        val w = 0.85f; val h = 1f
        val short = min(w, h)
        val g = gutter * short; val m = margin * short
        val rest = n - 1
        val rows = ceil(rest / 4f).toInt()
        val usable = h - 2 * m - g * rows
        val bigH = usable * if (rows == 1) 0.66f else 0.5f
        val smallH = (usable - bigH) / rows
        val cells = ArrayList<Cell>()
        cells += Cell(m, m, w - 2 * m, bigH)
        for (r in 0 until rows) {
            // Spread the pictures evenly over the rows rather than leaving one alone at the end.
            val inRow = rest / rows + if (r < rest % rows) 1 else 0
            val sw = (w - 2 * m - g * (inRow - 1)) / inRow
            for (i in 0 until inRow) cells += Cell(m + i * (sw + g), m + bigH + g + r * (smallH + g), sw, smallH)
        }
        return Built(w, h, cells)
    }

    /** A single row of pictures at their own aspects, the "before and after" shape. */
    private fun strip(aspects: List<Float>, g: Float, m: Float): Built {
        val h = 1f
        val ch = h - 2 * m
        val cells = ArrayList<Cell>()
        var x = m
        for (a in aspects) {
            val cw = ch * max(0.2f, a)
            cells += Cell(x, m, cw, ch)
            x += cw + g
        }
        return Built(x - g + m, h, cells)
    }

    /** The canvas in pixels: as large as the budget allows, at the plan's aspect. */
    fun canvas(plan: Plan, maxPixels: Long, maxEdge: Int = 4096): Geometry2D.Size {
        val w = sqrt(maxPixels * plan.canvasAspect.toDouble()).roundToInt()
        val h = (w / plan.canvasAspect).roundToInt()
        return Geometry2D.fit(max(1, w), max(1, h), maxPixels, maxEdge)
    }

    /** The cell under a point given in fractions of the canvas, or -1 for spacing. */
    fun cellAt(plan: Plan, x: Float, y: Float): Int = plan.cells.indexOfFirst { it.contains(x, y) }

    /** The list with two places exchanged; anything out of range leaves it as it was. */
    fun <T> swap(list: List<T>, i: Int, j: Int): List<T> {
        if (i == j || i !in list.indices || j !in list.indices) return list
        val out = list.toMutableList()
        out[i] = list[j]; out[j] = list[i]
        return out
    }

    // ------------------------------------------------------------------ drawing at a size

    /** A cell on a surface [width] by [height] pixels. */
    fun cellPx(cell: Cell, width: Float, height: Float): PxRect =
        PxRect(cell.left * width, cell.top * height, cell.right * width, cell.bottom * height)

    /**
     * Corner radius in pixels on a surface [width] by [height]: the style's fraction of the
     * shorter edge, by way of [Geometry2D.resolutionScale] like every resolution-dependent size,
     * and never more than half the cell.
     */
    fun cornerPx(style: Style, width: Float, height: Float, cell: PxRect): Float {
        val scale = Geometry2D.resolutionScale(Geometry2D.Size(max(1, width.roundToInt()), max(1, height.roundToInt())))
        return min(style.radius * Geometry2D.REFERENCE_EDGE * scale, min(cell.width, cell.height) / 2f)
    }

    /** The white border inside a polaroid cell, in pixels. */
    fun polaroidBorderPx(cell: PxRect): Float = min(cell.width, cell.height) * 0.05f

    // ------------------------------------------------------------------ framing

    /**
     * The part of a picture [picW] by [picH] that shows in a cell [cellW] by [cellH]: just
     * covering the cell, divided by the zoom, centred on the framing's point but never past the
     * picture's edge. The same for any pixel size of picture and cell at the same shapes.
     */
    fun window(picW: Float, picH: Float, cellW: Float, cellH: Float, framing: Framing): Window {
        val pa = max(1e-3f, picW / max(1e-3f, picH))
        val ca = max(1e-3f, cellW / max(1e-3f, cellH))
        var ww = 1f; var wh = 1f
        if (pa > ca) ww = ca / pa else wh = pa / ca
        val z = framing.zoom.coerceIn(1f, Framing.MAX_ZOOM)
        ww /= z; wh /= z
        val cx = framing.cx.coerceIn(ww / 2f, 1f - ww / 2f)
        val cy = framing.cy.coerceIn(wh / 2f, 1f - wh / 2f)
        return Window(cx - ww / 2f, cy - wh / 2f, ww, wh)
    }

    /** The framing with its point pulled back to where [window] can actually centre. */
    fun settle(picW: Float, picH: Float, cellW: Float, cellH: Float, framing: Framing): Framing {
        val z = framing.zoom.coerceIn(1f, Framing.MAX_ZOOM)
        val w = window(picW, picH, cellW, cellH, framing.copy(zoom = z))
        return Framing(w.left + w.width / 2f, w.top + w.height / 2f, z)
    }

    /**
     * A finger moved [dx], [dy] in fractions of the cell: the picture follows it, so the part
     * that shows moves the other way.
     */
    fun pan(picW: Float, picH: Float, cellW: Float, cellH: Float, framing: Framing, dx: Float, dy: Float): Framing {
        val start = settle(picW, picH, cellW, cellH, framing)
        val w = window(picW, picH, cellW, cellH, start)
        return settle(picW, picH, cellW, cellH, start.copy(cx = start.cx - dx * w.width, cy = start.cy - dy * w.height))
    }

    /** Zoom by [factor] about the cell's centre, never below just covering the cell. */
    fun zoom(picW: Float, picH: Float, cellW: Float, cellH: Float, framing: Framing, factor: Float): Framing =
        settle(picW, picH, cellW, cellH, framing.copy(zoom = (framing.zoom * factor).coerceIn(1f, Framing.MAX_ZOOM)))

    /**
     * The automatic framing: centred on where the picture has the most detail, from a small
     * ARGB sample [width] by [height]. Edge strength above the picture's own average pulls the
     * point; a flat or evenly busy picture stays centred. Half-way to the centre, so a picture is
     * nudged towards its subject and not cropped to it.
     */
    fun focus(argb: IntArray, width: Int, height: Int): Framing {
        if (width < 3 || height < 3 || argb.size < width * height) return Framing.CENTRED
        val step = max(1, sqrt(width.toDouble() * height / 40_000.0).toInt())
        fun luma(x: Int, y: Int): Float {
            val c = argb[y * width + x]
            return 0.299f * Argb.red(c) + 0.587f * Argb.green(c) + 0.114f * Argb.blue(c)
        }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val es = ArrayList<Float>()
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val e = abs(luma(x + 1, y) - luma(x - 1, y)) + abs(luma(x, y + 1) - luma(x, y - 1))
                xs += (x + 0.5f) / width; ys += (y + 0.5f) / height; es += e
                x += step
            }
            y += step
        }
        if (es.isEmpty()) return Framing.CENTRED
        val mean = es.average().toFloat()
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (i in es.indices) {
            val w = es[i] - mean
            if (w <= 0f) continue
            sw += w; sx += w * xs[i]; sy += w * ys[i]
        }
        // Too little contrast to say anything: stay centred.
        if (sw <= 0.0 || mean < 2f) return Framing.CENTRED
        val cx = (sx / sw).toFloat(); val cy = (sy / sw).toFloat()
        return Framing(0.5f + (cx - 0.5f) * 0.5f, 0.5f + (cy - 0.5f) * 0.5f)
    }
}
