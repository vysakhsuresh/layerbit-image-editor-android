package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UprightTest {
    /** Dark vertical lines on a light wall; at the top each sits [squeeze] of the way towards the middle. */
    private fun building(w: Int, h: Int, squeeze: Float, lean: Float = 0f): IntArray {
        val px = IntArray(w * h) { Argb.pack(255, 225, 222, 215) }
        val bottoms = (0..10).map { w * (0.04f + 0.092f * it) }
        for (y in 0 until h) {
            val up = 1f - y.toFloat() / (h - 1)
            for (x0 in bottoms) {
                val x = x0 + (w / 2f - x0) * squeeze * up + lean * (h - 1 - y)
                for (dx in -2..2) {
                    val xi = (x + dx).toInt()
                    if (xi in 0 until w) px[y * w + xi] = Argb.pack(255, 40, 40, 45)
                }
            }
        }
        return px
    }

    @Test
    fun linesLeaningInAtTheTopGetTheTopCornersPulledIn() {
        val w = 600; val h = 800
        val q = Upright.suggest(building(w, h, 0.2f), w, h)
        assertNotNull(q); q!!
        // An edge line moves a tenth of the width in over the height; corrected at STRENGTH.
        assertEquals(0.1f * Upright.STRENGTH, q.tl.x, 0.02f)
        assertEquals(1f - 0.1f * Upright.STRENGTH, q.tr.x, 0.02f)
        assertEquals(0f, q.bl.x, 1e-6f); assertEquals(1f, q.br.x, 1e-6f)
        assertTrue(q.valid)
    }

    @Test
    fun linesLeaningOutGetTheBottomPulledIn() {
        val w = 600; val h = 800
        val q = Upright.suggest(building(w, h, -0.15f), w, h)!!
        assertTrue("$q", q.bl.x > 0.03f && q.br.x < 0.97f)
        assertEquals(0f, q.tl.x, 1e-6f)
    }

    @Test
    fun straightOrMerelyTiltedLinesNeedNothing() {
        val w = 600; val h = 800
        assertNull(Upright.suggest(building(w, h, 0f), w, h))
        // All lines leaning the same way is a rotation, which Straighten handles.
        assertNull(Upright.suggest(building(w, h, 0f, lean = 0.05f), w, h))
        // A flat wall with nothing on it.
        assertNull(Upright.suggest(IntArray(w * h) { Argb.pack(255, 200, 200, 200) }, w, h))
    }

    @Test
    fun aQuarterTurnedPictureIsReadAcross() {
        val w = 600; val h = 800
        val upright = building(w, h, 0.2f)
        // Stored turned: what was a column is now a row.
        val stored = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) stored[x * h + y] = upright[y * w + x]
        val q = Upright.suggestTransposed(stored, h, w)
        assertNotNull(q)
        assertTrue("$q", q!!.valid)
        assertTrue("$q", abs(q.tl.y - 0.085f) < 0.02f)
    }
}
