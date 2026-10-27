package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentTest {

    /** A picture of a dark blue mug and a red apple on a light, slightly textured table. */
    private fun scene(w: Int = 120, h: Int = 90): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val mug = x in 35..75 && y in 20..70
        val apple = x in 88..108 && y in 48..74
        val tex = ((x * 7 + y * 3) % 9)
        when {
            mug -> Argb.pack(255, 30 + tex, 40, 120 + tex)
            apple -> Argb.pack(255, 200 + tex, 40, 50)
            else -> Argb.pack(255, 225 - tex, 220 - tex, 205 - tex)
        }
    }

    @Test
    fun subjectIsKeptAndTableIsRemovedWithNoStrokes() {
        val w = 120; val h = 90
        val mask = Segment.subject(scene(w, h), w, h, emptyList())
        assertEquals("mug centre kept", 255, mask[45 * w + 55])
        assertEquals("mug corner kept", 255, mask[22 * w + 37])
        assertEquals("apple kept, even the part outside the middle", 255, mask[60 * w + 105])
        assertEquals("table removed", 0, mask[10 * w + 10])
        assertEquals("table beside the mug removed", 0, mask[45 * w + 20])
        assertEquals("table between them removed", 0, mask[60 * w + 82])
        // The edge lands where the colours change, within a pixel or two.
        assertEquals(0, mask[45 * w + 32]); assertEquals(255, mask[45 * w + 37])
        val kept = Segment.coverage(mask)
        assertTrue("about the mug's area, got $kept", kept in 0.2f..0.3f)
    }

    @Test
    fun aRemoveStrokeAndAKeepStrokeOverruleTheGuess() {
        val w = 120; val h = 90
        val px = scene(w, h)
        // Remove the apple with one stroke across it; keep a patch of table at the top left.
        val remove = Scribble(listOf(Pt(0.8f, 0.62f), Pt(0.86f, 0.7f)), width = 0.1f, keep = false)
        val keep = Scribble(listOf(Pt(0.1f, 0.12f), Pt(0.2f, 0.12f)), width = 0.08f, keep = true)
        val mask = Segment.subject(px, w, h, listOf(remove, keep))
        assertEquals("under the remove stroke", 0, mask[58 * w + 98])
        assertEquals("the rest of the apple goes with it", 0, mask[72 * w + 106])
        assertEquals("the mug stays", 255, mask[45 * w + 55])
        assertEquals("under the keep stroke", 255, mask[11 * w + 18])
        assertEquals("table far from any stroke still goes", 0, mask[80 * w + 110])
    }

    @Test
    fun smallIslandsAreDroppedAndSmallHolesFilled() {
        val w = 120; val h = 90
        val px = scene(w, h)
        // A speck of mug colour on the table, and a speck of table colour inside the mug.
        px[10 * w + 100] = Argb.pack(255, 30, 40, 120)
        px[45 * w + 55] = Argb.pack(255, 225, 220, 205)
        val mask = Segment.subject(px, w, h, emptyList())
        assertEquals(0, mask[10 * w + 100])
        assertEquals(255, mask[45 * w + 55])
    }

    @Test
    fun inkKeepsDarkAndColouredMarksOnPaperAndDropsThePaper() {
        val w = 60; val h = 40
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                y in 18..22 && x in 5..40 -> Argb.pack(255, 20, 20, 30)      // a signature line
                y in 8..12 && x in 45..55 -> Argb.pack(255, 210, 40, 40)     // a red stamp, brighter than the ink
                else -> Argb.pack(255, 205 + (x % 3) * 10, 200 + (y % 2) * 12, 190) // paper in uneven light
            }
        }
        val mask = Segment.ink(px, w, h, threshold = 0f)
        assertEquals(255, mask[20 * w + 20])
        assertEquals(255, mask[10 * w + 50])
        assertEquals(0, mask[30 * w + 30])
        assertEquals(0, mask[2 * w + 2])
        // A chosen threshold is honoured.
        val strict = Segment.ink(px, w, h, threshold = 0.03f)
        assertTrue("a strict threshold leaves the signature out: ${strict[20 * w + 20]}", strict[20 * w + 20] < 40)
    }

    @Test
    fun featherSoftensOnlyTheEdgeAndResampleIsSmooth() {
        val w = 40; val h = 40
        val mask = IntArray(w * h) { i -> if (i % w >= 20) 255 else 0 }
        val soft = Segment.feather(mask, w, h, 3)
        assertEquals(0, soft[10 * w + 2]); assertEquals(255, soft[10 * w + 37])
        assertTrue(soft[10 * w + 20] in 1..254)
        assertTrue(soft[10 * w + 19] < soft[10 * w + 20] && soft[10 * w + 20] < soft[10 * w + 21])
        val big = Segment.resample(mask, w, h, 80, 80)
        assertEquals(0, big[20 * 80 + 4]); assertEquals(255, big[20 * 80 + 75])
        assertTrue(big[20 * 80 + 39] in 1..254)
        val size = Segment.analysisSize(4000, 3000)
        assertEquals(size, Segment.analysisSize(1200, 900))
        assertTrue(size.width.toLong() * size.height in 150_000L..170_000L)
    }

    @Test
    fun applyAlphaMultipliesAndInvertFlips() {
        val px = intArrayOf(Argb.pack(255, 10, 20, 30), Argb.pack(255, 10, 20, 30))
        val mask = intArrayOf(255, 0)
        val a = px.copyOf(); Segment.applyAlpha(a, mask, invert = false)
        assertEquals(255, Argb.alpha(a[0])); assertEquals(0, Argb.alpha(a[1]))
        val b = px.copyOf(); Segment.applyAlpha(b, mask, invert = true)
        assertEquals(0, Argb.alpha(b[0])); assertEquals(255, Argb.alpha(b[1]))
    }
}
