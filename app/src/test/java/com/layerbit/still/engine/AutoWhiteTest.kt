package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoWhiteTest {

    /** A scene: grey wall in [cast], plus saturated patches that must not sway the answer. */
    private fun scene(r: Int, g: Int, b: Int): IntArray = IntArray(10_000) { i ->
        when (i % 10) {
            0 -> Argb.pack(255, 220, 30, 40)   // red sign
            1 -> Argb.pack(255, 40, 180, 60)   // grass
            else -> { val v = (i % 7) * 12; Argb.pack(255, (r + v).coerceAtMost(240), (g + v).coerceAtMost(240), (b + v).coerceAtMost(240)) }
        }
    }

    @Test
    fun aBlueCastAsksForWarmth() {
        val t = AutoWhite.tone(scene(120, 130, 165))
        assertTrue("temperature ${t.temperature}", t.temperature > 0.15f)
        assertTrue(AutoWhite.estimate(scene(120, 130, 165))!!.fromNeutrals)
    }

    @Test
    fun aGreenCastAsksForMagenta() {
        val t = AutoWhite.tone(scene(120, 150, 120))
        assertTrue("tint ${t.tint}", t.tint > 0.1f) // positive tint is magenta
    }

    @Test
    fun aNeutralPictureIsLeftAlone() {
        val t = AutoWhite.tone(scene(130, 130, 130))
        assertEquals(0f, t.temperature, 0f)
        assertEquals(0f, t.tint, 0f)
        assertFalse(AutoWhite.worthwhile(scene(130, 130, 130)))
    }

    @Test
    fun anAllOneColourSceneIsOnlyHalfCorrected() {
        val sunset = IntArray(4000) { Argb.pack(255, 230, 120 + it % 20, 40) }
        val e = AutoWhite.estimate(sunset)!!
        assertFalse(e.fromNeutrals)
        val t = AutoWhite.tone(sunset)
        assertTrue(t.temperature < 0f)
        assertTrue("held back ${t.temperature}", t.temperature >= -AutoWhite.LIMIT)
    }

    @Test
    fun theOtherSlidersAreKept() {
        val t = AutoWhite.tone(scene(120, 130, 165), Tone(exposure = 0.5f, clarity = 0.3f))
        assertEquals(0.5f, t.exposure, 0f)
        assertEquals(0.3f, t.clarity, 0f)
    }

    @Test
    fun strengthAddsTheDifferenceAndKeepsLaterMoves() {
        val before = Tone(exposure = 0.1f)
        val after = Tone(exposure = 0.5f, whites = 0.4f)
        val step = ToneStep.between(before, after)
        // The user then moves contrast by hand.
        val moved = after.copy(contrast = 0.3f)
        val half = step.rescale(moved, 0.5f)
        assertEquals(0.3f, half.exposure, 1e-5f)
        assertEquals(0.2f, half.whites, 1e-5f)
        assertEquals(0.3f, half.contrast, 0f)
        val off = step.copy(amount = 0.5f).rescale(half, 0f)
        assertEquals(0.1f, off.exposure, 1e-5f)
        assertEquals(0f, off.whites, 1e-5f)
    }
}
