package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class CollageTest {

    private val styles = listOf(Collage.Style(), Collage.Style(0f, 0f, 0f), Collage.Style(0.08f, 0.1f, 0.1f), Collage.Style(0.05f, 0f, 0.03f))
    private fun aspects(n: Int) = List(n) { listOf(1.5f, 0.75f, 1f, 1.78f)[it % 4] }

    @Test
    fun spacingIsTheSameAcrossAndDownAndAFractionOfTheShortEdge() {
        val style = Collage.Style(gutter = 0.05f, margin = 0.08f)
        for (layout in listOf(Collage.Layout.GRID, Collage.Layout.FEATURE, Collage.Layout.COLUMNS, Collage.Layout.STRIP)) {
            val plan = Collage.plan(layout, aspects(4), style)
            val w = 3000f; val h = w / plan.canvasAspect
            val short = min(w, h)
            val cells = plan.cells.map { Collage.cellPx(it, w, h) }
            // Margins: the outermost cells sit the margin away from every edge.
            assertEquals("$layout left", 0.08f * short, cells.minOf { it.left }, 1f)
            assertEquals("$layout top", 0.08f * short, cells.minOf { it.top }, 1f)
            assertEquals("$layout right", 0.08f * short, w - cells.maxOf { it.right }, 1f)
            assertEquals("$layout bottom", 0.08f * short, h - cells.maxOf { it.bottom }, 1f)
            // Every gap between neighbours, across or down, is the gutter.
            for (a in cells) for (b in cells) {
                val overlapY = min(a.bottom, b.bottom) - maxOf(a.top, b.top) > 1f
                val overlapX = min(a.right, b.right) - maxOf(a.left, b.left) > 1f
                val gx = b.left - a.right; val gy = b.top - a.bottom
                if (overlapY && gx > 0f && gx < 0.2f * short) assertEquals("$layout across", 0.05f * short, gx, 1f)
                if (overlapX && gy > 0f && gy < 0.2f * short) assertEquals("$layout down", 0.05f * short, gy, 1f)
            }
        }
    }

    @Test
    fun previewPredictsExport() {
        val pics = listOf(4032f to 3024f, 3024f to 4032f, 1000f to 1000f, 1920f to 1080f, 800f to 1200f)
        for (layout in Collage.Layout.entries) for (style in styles) for (n in 2..5) {
            val plan = Collage.plan(layout, pics.take(n).map { it.first / it.second }, style)
            val export = Collage.canvas(plan, 12_000_000L)
            val ew = export.width.toFloat(); val eh = export.height.toFloat()
            val pw = 360f; val ph = pw / plan.canvasAspect
            for ((i, cell) in plan.cells.withIndex()) {
                val e = Collage.cellPx(cell, ew, eh); val p = Collage.cellPx(cell, pw, ph)
                // Same place, as a fraction of each surface.
                assertEquals(e.left / ew, p.left / pw, 1e-4f)
                assertEquals(e.width / ew, p.width / pw, 1e-4f)
                assertEquals(e.top / eh, p.top / ph, 2e-3f)
                assertEquals(e.height / eh, p.height / ph, 2e-3f)
                // Corners: the same fraction of the shorter edge.
                val ec = Collage.cornerPx(plan.style, ew, eh, e) / min(ew, eh)
                val pc = Collage.cornerPx(plan.style, pw, ph, p) / min(pw, ph)
                assertEquals("$layout $style corner", ec, pc, 3e-3f)
                assertEquals(Collage.polaroidBorderPx(e) / ew, Collage.polaroidBorderPx(p) / pw, 2e-3f)
                // The same part of the picture shows, framed or not.
                for (f in listOf(Collage.Framing.CENTRED, Collage.Framing(0.2f, 0.9f, 2.2f))) {
                    val (picW, picH) = pics[i]
                    val we = Collage.window(picW, picH, e.width, e.height, f)
                    val wp = Collage.window(picW, picH, p.width, p.height, f)
                    assertEquals(we.left, wp.left, 0.01f); assertEquals(we.top, wp.top, 0.01f)
                    assertEquals(we.width, wp.width, 0.01f); assertEquals(we.height, wp.height, 0.01f)
                }
            }
        }
    }

    @Test
    fun windowCoversTheCellInsideThePicture() {
        for (f in listOf(Collage.Framing.CENTRED, Collage.Framing(0f, 1f, 1f), Collage.Framing(0.9f, 0.1f, 3f), Collage.Framing(0.5f, 0.5f, 99f))) {
            for ((cw, ch) in listOf(100f to 100f, 300f to 100f, 100f to 400f)) {
                val w = Collage.window(4000f, 3000f, cw, ch, f)
                assertTrue("$f $w", w.left >= -1e-5f && w.top >= -1e-5f && w.left + w.width <= 1.00001f && w.top + w.height <= 1.00001f)
                // The part shown has the cell's shape, so the picture covers the cell without stretching.
                assertEquals(cw / ch, (w.width * 4000f) / (w.height * 3000f), 1e-3f)
                assertTrue(w.width >= 1f / Collage.Framing.MAX_ZOOM - 1e-5f || w.height >= 1f / Collage.Framing.MAX_ZOOM - 1e-5f)
            }
        }
        // Centred and unzoomed is the old centred cover: a landscape picture in a square cell loses its sides equally.
        val c = Collage.window(4000f, 3000f, 100f, 100f, Collage.Framing.CENTRED)
        assertEquals(0.75f, c.width, 1e-5f); assertEquals(0.125f, c.left, 1e-5f); assertEquals(1f, c.height, 1e-5f)
    }

    @Test
    fun panFollowsTheFingerAndStopsAtTheEdge() {
        val start = Collage.Framing.CENTRED
        val before = Collage.window(4000f, 3000f, 100f, 100f, start)
        val right = Collage.pan(4000f, 3000f, 100f, 100f, start, 0.1f, 0f)
        val after = Collage.window(4000f, 3000f, 100f, 100f, right)
        // The finger moved right, the picture moved right, so more of its left side shows.
        assertTrue(after.left < before.left)
        assertEquals(before.left - 0.1f * before.width, after.left, 1e-5f)
        // Too far: pinned at the edge, and the stored point is where it stopped, so coming back is immediate.
        val far = Collage.pan(4000f, 3000f, 100f, 100f, start, 5f, 5f)
        assertEquals(0f, Collage.window(4000f, 3000f, 100f, 100f, far).left, 1e-5f)
        val back = Collage.pan(4000f, 3000f, 100f, 100f, far, -0.1f, 0f)
        assertTrue(Collage.window(4000f, 3000f, 100f, 100f, back).left > 0f)
        // Vertically there is nothing to move in a landscape picture in a square cell.
        assertEquals(0f, Collage.window(4000f, 3000f, 100f, 100f, far).top, 1e-5f)
    }

    @Test
    fun zoomNeverUncoversTheCell() {
        val z = Collage.zoom(4000f, 3000f, 100f, 100f, Collage.Framing.CENTRED, 0.3f)
        assertEquals(1f, z.zoom, 0f)
        val big = Collage.zoom(4000f, 3000f, 100f, 100f, Collage.Framing.CENTRED, 100f)
        assertEquals(Collage.Framing.MAX_ZOOM, big.zoom, 0f)
        val twice = Collage.zoom(4000f, 3000f, 100f, 100f, Collage.Framing(0f, 0f), 2f)
        val w = Collage.window(4000f, 3000f, 100f, 100f, twice)
        assertEquals(0.375f, w.width, 1e-5f); assertEquals(0f, w.left, 1e-5f)
    }

    @Test
    fun swapExchangesTwoPlacesAndNothingElse() {
        assertEquals(listOf("c", "b", "a", "d"), Collage.swap(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("a", "b"), Collage.swap(listOf("a", "b"), 0, 5))
        assertEquals(listOf("a", "b"), Collage.swap(listOf("a", "b"), 1, 1))
    }

    @Test
    fun aPointFindsItsCellAndTheSpacingFindsNone() {
        val plan = Collage.plan(Collage.Layout.GRID, aspects(4), Collage.Style(gutter = 0.06f))
        for ((i, c) in plan.cells.withIndex()) assertEquals(i, Collage.cellAt(plan, c.left + c.width / 2, c.top + c.height / 2))
        val a = plan.cells[0]; val b = plan.cells[1]
        assertEquals(-1, Collage.cellAt(plan, (a.right + b.left) / 2, a.top + a.height / 2))
        assertEquals(-1, Collage.cellAt(plan, 0.001f, 0.001f))
    }

    @Test
    fun focusLeansTowardsDetailAndLeavesFlatPicturesCentred() {
        val w = 120; val h = 80
        val flat = IntArray(w * h) { Argb.pack(255, 120, 130, 140) }
        assertEquals(Collage.Framing.CENTRED, Collage.focus(flat, w, h))
        // Busy at the bottom right, calm elsewhere.
        val busy = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = if (x > w * 2 / 3 && y > h / 2 && (x / 2 + y / 2) % 2 == 0) 230 else 40
            Argb.pack(255, v, v, v)
        }
        val f = Collage.focus(busy, w, h)
        assertTrue("$f", f.cx > 0.6f && f.cy > 0.55f)
        assertTrue("half-way, not all the way: $f", f.cx < 0.75f)
        // An evenly busy picture stays near the middle.
        val even = IntArray(w * h) { i -> val v = if ((i % w / 2 + i / w / 2) % 2 == 0) 220 else 30; Argb.pack(255, v, v, v) }
        val e = Collage.focus(even, w, h)
        assertTrue("$e", abs(e.cx - 0.5f) < 0.05f && abs(e.cy - 0.5f) < 0.05f)
    }

    @Test
    fun manyPicturesStillGetRoomWithTheWidestSpacing() {
        val wide = Collage.Style(Collage.Style.GUTTER.endInclusive, Collage.Style.MARGIN.endInclusive, Collage.Style.RADIUS.endInclusive)
        for (layout in Collage.Layout.entries) for (n in 1..12) {
            val plan = Collage.plan(layout, aspects(n), wide)
            assertEquals(n, plan.cells.size)
            for (c in plan.cells) assertTrue("$layout $n $c", c.width > 0.02f && c.height > 0.02f && c.right <= 1.0001f && c.bottom <= 1.0001f)
        }
        // Out-of-range styles are brought into range rather than producing nonsense.
        val clamped = Collage.plan(Collage.Layout.GRID, aspects(3), Collage.Style(5f, -1f, 9f)).style
        assertEquals(Collage.Style(0.08f, 0f, 0.1f), clamped)
        assertNotEquals(0, Collage.plan(Collage.Layout.STRIP, aspects(12)).cells.size)
    }
}
