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

class SegmentEdgeTest {
    /** A red square on a blue ground with a 10-pixel band where the two mix linearly, as a real edge does. */
    private fun mixed(w: Int = 160, h: Int = 120): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val inside = x in 50..110 && y in 30..90
        val edge = x in 40..49 && y in 30..90
        when {
            inside -> Argb.pack(255, 220, 30, 30)
            edge -> { val t = (x - 39) / 11f; Argb.pack(255, (30 + 190 * t).toInt(), 30, (200 - 170 * t).toInt()) }
            else -> Argb.pack(255, 30, 30, 200)
        }
    }

    @Test
    fun growAndShrinkMoveTheEdgeByTheRadius() {
        val w = 50; val h = 40
        val mask = IntArray(w * h) { i -> if (i % w in 20..29 && i / w in 15..24) 255 else 0 }
        val grown = Segment.grow(mask, w, h, 3)
        assertEquals(255, grown[20 * w + 17]); assertEquals(0, grown[20 * w + 16])
        val shrunk = Segment.shrink(mask, w, h, 3)
        assertEquals(0, shrunk[20 * w + 22]); assertEquals(255, shrunk[20 * w + 24])
        assertEquals("shrink undoes grow on a block", mask.toList(), Segment.shrink(grown, w, h, 3).toList())
    }

    @Test
    fun boundsWrapTheKeptAreaWithAMargin() {
        val w = 100; val h = 50
        val mask = IntArray(w * h) { i -> if (i % w in 30..59 && i / w in 10..29 ) 255 else 0 }
        val b = Segment.bounds(mask, w, h, margin = 0f)!!
        assertEquals(0.30f, b[0], 0.001f); assertEquals(0.20f, b[1], 0.001f); assertEquals(0.60f, b[2], 0.001f); assertEquals(0.60f, b[3], 0.001f)
        assertEquals(null, Segment.bounds(IntArray(w * h), w, h))
    }

    @Test
    fun theColoursDecideTheEdgeAndTheFringeComesOff() {
        val fg = Argb.pack(255, 220, 30, 30); val bg = Argb.pack(255, 30, 30, 200)
        assertEquals(255, Segment.colourAlpha(fg, fg, bg)); assertEquals(0, Segment.colourAlpha(bg, fg, bg))
        val half = Argb.pack(255, 125, 30, 115)
        val a = Segment.colourAlpha(half, fg, bg)
        assertTrue("half way, got $a", a in 115..140)
        val clean = Segment.decontaminate(half, bg, a)
        assertTrue("the background's blue is taken out: red ${Argb.red(clean)} blue ${Argb.blue(clean)}", Argb.red(clean) > 190 && Argb.blue(clean) < 70)
        assertEquals("alike colours say nothing", -1, Segment.colourAlpha(half, fg, Argb.pack(255, 215, 35, 35)))
    }

    @Test
    fun localModelsReadTheSubjectAndTheGroundNearTheEdge() {
        val w = 160; val h = 120
        val px = mixed(w, h)
        val mask = IntArray(w * h) { i -> if (i % w in 45..110 && i / w in 30..90) 255 else 0 }
        val (fg, bg) = Segment.localModels(px, w, h, mask, radiusPx = 8)
        val at = 60 * w + 45
        assertTrue("subject model is red: ${Argb.red(fg[at])}", Argb.red(fg[at]) > 180 && Argb.blue(fg[at]) < 80)
        assertTrue("ground model is blue: ${Argb.blue(bg[at])}", Argb.blue(bg[at]) > 150 && Argb.red(bg[at]) < 80)
        // Across the mixed band the judged alpha climbs with the mix.
        var last = -1
        for (x in 40..49) { val a = Segment.colourAlpha(px[60 * w + x], fg[60 * w + x], bg[60 * w + x]); assertTrue("climbs at $x: $a after $last", a >= last); last = a }
    }

    @Test
    fun theSubjectDecisionIsTheSameInBandsAndPrintsItsTime() {
        val w = 462; val h = 346
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val tex = ((x * 7 + y * 3) % 9)
            if ((x - 231) * (x - 231) + (y - 173) * (y - 173) < 100 * 100) Argb.pack(255, 30 + tex, 40, 120 + tex) else Argb.pack(255, 225 - tex, 220 - tex, 205 - tex)
        }
        Parallel.enabled = false
        val t0 = System.nanoTime(); val one = Segment.subject(px, w, h, emptyList()); val t1 = System.nanoTime()
        Parallel.enabled = true
        val t2 = System.nanoTime(); val two = Segment.subject(px, w, h, emptyList()); val t3 = System.nanoTime()
        assertEquals(one.toList(), two.toList())
        println("segment: ${w}x$h subject sequential ${(t1 - t0) / 1_000_000} ms, side by side ${(t3 - t2) / 1_000_000} ms")
        val mask = two
        val tm0 = System.nanoTime(); Segment.localModels(px, w, h, mask, 20); Segment.grow(mask, w, h, 8); val tm1 = System.nanoTime()
        println("segment: models and grow ${(tm1 - tm0) / 1_000_000} ms")
    }
}
