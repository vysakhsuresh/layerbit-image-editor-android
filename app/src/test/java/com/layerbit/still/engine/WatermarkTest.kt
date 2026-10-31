package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkTest {

    private val big = Geometry2D.Size(6000, 4000)
    private val small = Geometry2D.Size(900, 600)

    @Test
    fun textAndMarginScaleWithThePicture() {
        val ratio = small.width.toFloat() / big.width
        assertEquals(WatermarkLayout.textSize(0.03f, big) * ratio, WatermarkLayout.textSize(0.03f, small), 0.01f)
        assertEquals(WatermarkLayout.margin(big) * ratio, WatermarkLayout.margin(small), 0.01f)
        // 0.03 of width at 3.2 per unit is about a tenth of the shorter edge.
        assertEquals(0.096f * 4000, WatermarkLayout.textSize(0.03f, big), 0.5f)
        assertEquals(WatermarkLayout.MIN_TEXT_PX, WatermarkLayout.textSize(0.03f, Geometry2D.Size(20, 20)), 0f)
    }

    @Test
    fun cornersSitInsideTheMargin() {
        val m = WatermarkLayout.margin(big)
        val w = 1200f; val h = 300f
        val tl = WatermarkLayout.single(WatermarkPlace.TOP_LEFT, big, w, h, 0f)
        assertEquals(1f, tl.scale, 0f)
        assertEquals(m + w / 2, tl.centreX, 0.01f); assertEquals(m + h / 2, tl.centreY, 0.01f)
        val br = WatermarkLayout.single(WatermarkPlace.BOTTOM_RIGHT, big, w, h, 0f)
        assertEquals(6000 - m - w / 2, br.centreX, 0.01f); assertEquals(4000 - m - h / 2, br.centreY, 0.01f)
        val tr = WatermarkLayout.single(WatermarkPlace.TOP_RIGHT, big, w, h, 0f)
        assertEquals(br.centreX, tr.centreX, 0f); assertEquals(tl.centreY, tr.centreY, 0f)
        val c = WatermarkLayout.single(WatermarkPlace.CENTRE, big, w, h, 0f)
        assertEquals(3000f, c.centreX, 0f); assertEquals(2000f, c.centreY, 0f)
    }

    @Test
    fun aTurnedMarkStaysWhollyOnThePicture() {
        for (place in WatermarkPlace.entries.filter { it.single }) for (angle in listOf(-90f, -30f, 0f, 45f, 90f)) {
            val w = 2000f; val h = 400f
            val s = WatermarkLayout.single(place, small, w, h, angle)
            val (bw, bh) = WatermarkLayout.turnedBox(w * s.scale, h * s.scale, angle)
            val m = WatermarkLayout.margin(small)
            assertTrue("$place $angle", s.centreX - bw / 2 >= m - 0.01f && s.centreX + bw / 2 <= small.width - m + 0.01f)
            assertTrue("$place $angle", s.centreY - bh / 2 >= m - 0.01f && s.centreY + bh / 2 <= small.height - m + 0.01f)
        }
    }

    @Test
    fun aLongTextShrinksToFitAndAShortOneDoesNot() {
        val long = WatermarkLayout.single(WatermarkPlace.CENTRE, small, 3000f, 60f, 0f)
        assertEquals((900 - 2 * WatermarkLayout.margin(small)) / 3000f, long.scale, 0.001f)
        assertEquals(1f, WatermarkLayout.single(WatermarkPlace.CENTRE, small, 100f, 30f, 0f).scale, 0f)
    }

    @Test
    fun theSamePlaceOnPreviewAndExport() {
        // The renderer measures text in proportion to the canvas, so the fractions agree.
        val k = small.width.toFloat() / big.width
        for (place in WatermarkPlace.entries.filter { it.single }) {
            val a = WatermarkLayout.single(place, big, 1500f, 380f, -30f)
            val b = WatermarkLayout.single(place, small, 1500f * k, 380f * k, -30f)
            assertEquals(a.centreX / big.width, b.centreX / small.width, 0.001f)
            assertEquals(a.centreY / big.height, b.centreY / small.height, 0.001f)
            assertEquals(a.scale, b.scale, 0.001f)
        }
    }

    @Test
    fun tilesCoverTheTurnedCanvasAndStayBounded() {
        val t = WatermarkLayout.tiles(small, 200f, 60f)
        assertTrue(t.size > 20)
        assertTrue(t.any { it.x <= 0f && it.y <= 0f })
        assertTrue(t.any { it.x >= small.width && it.y >= small.height })
        // Odd rows start half a step in, like brick.
        val rows = t.groupBy { it.y }.values.toList()
        assertEquals(160f, rows[1].first().x - rows[0].first().x, 0.01f)
        assertTrue(WatermarkLayout.tiles(big, 0f, 0f).size <= WatermarkLayout.MAX_TILES)
    }

    @Test
    fun placeSurvivesARecipeAndOlderRecipesAreTiled() {
        val r = Recipe(marks = listOf(Mark(MarkKind.WATERMARK, listOf(Pt(0.5f, 0.5f)), text = "© me", place = WatermarkPlace.BOTTOM_RIGHT)))
        assertEquals(r, RecipeJson.read(RecipeJson.write(r)))
        val tiled = Recipe(marks = listOf(Mark(MarkKind.WATERMARK, listOf(Pt(0.5f, 0.5f)), text = "© me")))
        assertFalse(RecipeJson.write(tiled).contains("place"))
        assertEquals(tiled, RecipeJson.read(RecipeJson.write(tiled)))
    }

    @Test
    fun percentChangeRoundsTowardZero() {
        assertEquals(-98, FitSize.percentChange(4_200_000, 48_000))
        assertEquals(0, FitSize.percentChange(1000, 996))
        assertEquals(25, FitSize.percentChange(1000, 1250))
        assertEquals(-99, FitSize.percentChange(1000, 1))
        assertEquals(0, FitSize.percentChange(0, 500))
    }
}
