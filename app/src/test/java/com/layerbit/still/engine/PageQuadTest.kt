package com.layerbit.still.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class PageQuadTest {

    /** Inside the convex quad [q] (fractions), with y down and corners in reading order. */
    private fun inside(q: Quad, x: Float, y: Float): Boolean {
        val p = q.points
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < 0f) return false
        }
        return true
    }

    /** A slightly warm page with grey text lines, lying at [q] on whatever [background] paints. */
    private fun photo(w: Int, h: Int, q: Quad, seed: Int = 1, background: (Int, Int, Random) -> Int): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val fx = (x + 0.5f) / w; val fy = (y + 0.5f) / h
            if (inside(q, fx, fy)) {
                // Text: short dark runs in every third band of six rows, inside the page only.
                val text = (y / 6) % 3 == 0 && (x / 9) % 5 != 0 && rnd.nextInt(4) != 0
                val l = if (text) 60 + rnd.nextInt(30) else 225 + rnd.nextInt(20) - (x * 15 / w)
                Argb.pack(255, l, l - 3, l - 10)
            } else background(x, y, rnd)
        }
    }

    /** Dark wood: grain bands plus pixel noise, never brighter than the page's darkest paper. */
    private val darkWood: (Int, Int, Random) -> Int = { x, y, rnd ->
        val l = (55 + 25 * sin(x * 0.05 + y * 0.013 + sin(y * 0.02) * 2) + rnd.nextInt(-25, 26)).toInt().coerceIn(0, 255)
        Argb.pack(255, l + 20, l + 8, l)
    }

    private fun assertCorners(truth: Quad, found: PageQuad.Found?, tol: Float = 0.03f) {
        assertNotNull("a page was there", found)
        truth.points.zip(found!!.quad.points).forEachIndexed { i, (t, f) ->
            assertTrue("corner $i: wanted $t, found $f", abs(t.x - f.x) <= tol && abs(t.y - f.y) <= tol)
        }
        assertTrue("found quad is a valid perspective quad", found.quad.valid)
    }

    @Test
    fun perspectivePageOnDarkWoodIsFound() {
        val w = 800; val h = 600
        val truth = Quad(Pt(0.22f, 0.12f), Pt(0.80f, 0.18f), Pt(0.88f, 0.90f), Pt(0.14f, 0.84f))
        val found = PageQuad.find(photo(w, h, truth, background = darkWood), w, h)
        assertCorners(truth, found)
        assertTrue("confident: ${found!!.confidence}", found.confidence > 0.6f)
    }

    @Test
    fun rotatedPageOnDarkWoodIsFoundInReadingOrder() {
        val w = 600; val h = 800
        // Turned about 20 degrees clockwise: the top-left corner is the highest-left one.
        val truth = Quad(Pt(0.30f, 0.10f), Pt(0.88f, 0.28f), Pt(0.70f, 0.90f), Pt(0.12f, 0.72f))
        assertCorners(truth, PageQuad.find(photo(w, h, truth, seed = 7, background = darkWood), w, h))
    }

    @Test
    fun pageOnMidGreyDeskIsFound() {
        val w = 640; val h = 480
        val truth = Quad(Pt(0.18f, 0.20f), Pt(0.76f, 0.15f), Pt(0.82f, 0.86f), Pt(0.25f, 0.92f))
        val grey: (Int, Int, Random) -> Int = { _, _, rnd -> val l = 128 + rnd.nextInt(-12, 13); Argb.pack(255, l, l, l + 4) }
        assertCorners(truth, PageQuad.find(photo(w, h, truth, seed = 3, background = grey), w, h))
    }

    @Test
    fun pageNextToADarkObjectOnGreyIsStillFound() {
        // The whole-picture Otsu split puts desk and page together; the brighter-half split must find the page.
        val w = 640; val h = 480
        val truth = Quad(Pt(0.40f, 0.15f), Pt(0.90f, 0.20f), Pt(0.86f, 0.88f), Pt(0.36f, 0.82f))
        val desk: (Int, Int, Random) -> Int = { x, y, rnd ->
            val l = if (x < w * 0.3 && y in (h * 0.1).toInt()..(h * 0.9).toInt()) 15 + rnd.nextInt(10) else 135 + rnd.nextInt(-10, 11)
            Argb.pack(255, l, l, l)
        }
        assertCorners(truth, PageQuad.find(photo(w, h, truth, seed = 5, background = desk), w, h))
    }

    @Test
    fun noiseHasNoPage() {
        val w = 640; val h = 480
        val rnd = Random(11)
        val noise = IntArray(w * h) { val l = rnd.nextInt(256); Argb.pack(255, l, l, l) }
        assertNull(PageQuad.find(noise, w, h))
        val flat = IntArray(w * h) { Argb.pack(255, 120, 120, 120) }
        assertNull(PageQuad.find(flat, w, h))
    }

    @Test
    fun aRoundPlateIsNotAPage() {
        val w = 640; val h = 480
        val img = IntArray(w * h) { i ->
            val dx = i % w - w / 2f; val dy = i / w - h / 2f
            if (dx * dx + dy * dy < 200f * 200f) Argb.pack(255, 235, 235, 235) else Argb.pack(255, 40, 40, 40)
        }
        assertNull(PageQuad.find(img, w, h))
    }

    @Test
    fun pageFillingTheFrameIsNotReportedAsAPageInsideIt() {
        val w = 640; val h = 480
        val full = photo(w, h, Quad.IDENTITY, seed = 2) { _, _, _ -> 0 }
        // The paper touches the whole edge, so it is the picture, not a page in it: the handles stay on the frame.
        assertNull(PageQuad.find(full, w, h))
    }

    @Test
    fun tinyOrEmptyInputIsNull() {
        assertNull(PageQuad.find(IntArray(0), 0, 0))
        assertNull(PageQuad.find(IntArray(8 * 8), 8, 8))
    }

    @Test
    fun timingOnA2MegapixelPreview() {
        val w = 1600; val h = 1200
        val truth = Quad(Pt(0.22f, 0.12f), Pt(0.80f, 0.18f), Pt(0.88f, 0.90f), Pt(0.14f, 0.84f))
        val img = photo(w, h, truth, background = darkWood)
        repeat(3) { PageQuad.find(img, w, h) } // warm the JIT, as a running app would be
        val runs = 10
        var worst = 0L; var total = 0L
        repeat(runs) {
            val t0 = System.nanoTime()
            val found = PageQuad.find(img, w, h)
            val ns = System.nanoTime() - t0
            total += ns; worst = max(worst, ns)
            assertCorners(truth, found)
        }
        println("PageQuad.find on ${w}x$h: mean ${total / runs / 1_000_000.0} ms, worst ${worst / 1_000_000.0} ms")
        // The mean, not the worst run: a busy build machine stalls single runs, which says
        // nothing about the code. Typical is ~25 ms; this only catches a real regression.
        assertTrue("well under a frame budget on the JVM", total / runs < 200_000_000L)
    }
}
