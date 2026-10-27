package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneTest {

    private fun grey(v: Int) = Argb.pack(255, v, v, v)

    @Test
    fun restingRecipeChangesNothing() {
        val p = TonePipeline.build(Tone(), Curves(), Look.NONE, 1f)
        assertTrue(p.isIdentity)
        for (v in 0..255 step 5) assertEquals(grey(v), p.apply(grey(v)))
        val colour = Argb.pack(200, 12, 200, 99)
        assertEquals(colour, p.apply(colour))
    }

    @Test
    fun exposureOfOneStopDoublesMidtones() {
        val p = TonePipeline.build(Tone(exposure = 1f), Curves(), Look.NONE, 1f)
        assertEquals(grey(200), p.apply(grey(100)))
        assertEquals(grey(255), p.apply(grey(200)))
    }

    @Test
    fun contrastKeepsEndsAndMidGrey() {
        for (c in listOf(-1f, -0.5f, 0.5f, 1f)) {
            val p = TonePipeline.build(Tone(contrast = c), Curves(), Look.NONE, 1f)
            assertEquals(grey(0), p.apply(grey(0)))
            assertEquals(grey(255), p.apply(grey(255)))
            val mid = Argb.red(p.apply(grey(128)))
            assertTrue("mid stays near grey at $c: $mid", abs(mid - 128) <= 2)
            // Monotone: a curve that reverses would invert parts of the picture.
            var last = -1
            for (v in 0..255) { val o = Argb.red(p.apply(grey(v))); assertTrue(o >= last); last = o }
        }
        val up = Argb.red(TonePipeline.build(Tone(contrast = 0.8f), Curves(), Look.NONE, 1f).apply(grey(200)))
        val down = Argb.red(TonePipeline.build(Tone(contrast = -0.8f), Curves(), Look.NONE, 1f).apply(grey(200)))
        assertTrue(up > 200 && down < 200)
    }

    @Test
    fun saturationMinusOneIsGreyscale() {
        val p = TonePipeline.build(Tone(saturation = -1f), Curves(), Look.NONE, 1f)
        val out = p.apply(Argb.pack(255, 250, 20, 20))
        assertEquals(Argb.red(out), Argb.green(out))
        assertEquals(Argb.green(out), Argb.blue(out))
    }

    @Test
    fun vibranceSparesSaturatedColours() {
        val p = TonePipeline.build(Tone(vibrance = 0.8f), Curves(), Look.NONE, 1f)
        val pale = Argb.pack(255, 150, 130, 130)
        val vivid = Argb.pack(255, 250, 10, 10)
        val paleOut = p.apply(pale); val vividOut = p.apply(vivid)
        val paleGain = (Argb.red(paleOut) - Argb.green(paleOut)) - (150 - 130)
        val vividGain = (Argb.red(vividOut) - Argb.green(vividOut)) - (250 - 10)
        assertTrue("pale gains more than vivid: $paleGain vs $vividGain", paleGain > vividGain)
    }

    @Test
    fun temperatureWarmsWithoutChangingGreyLuminance() {
        val p = TonePipeline.build(Tone(temperature = 1f), Curves(), Look.NONE, 1f)
        val out = p.apply(grey(128))
        assertTrue(Argb.red(out) > Argb.blue(out))
        val l = Argb.luma(out)
        assertTrue("luminance held: $l", abs(l - 127) <= 3)
    }

    @Test
    fun hueHalfTurnSwapsOpposites() {
        val p = TonePipeline.build(Tone(hue = 1f), Curves(), Look.NONE, 1f)
        val out = p.apply(Argb.pack(255, 255, 0, 0))
        // Luminance-preserving, so the cyan is as dim as the red was dark: (0, 109, 109).
        assertTrue("red becomes cyan: ${Argb.red(out)},${Argb.green(out)},${Argb.blue(out)}", Argb.red(out) < 40 && Argb.green(out) > 80 && Argb.blue(out) > 80)
    }

    @Test
    fun whitesAndBlacksMoveTheEndPoints() {
        val whites = TonePipeline.build(Tone(whites = 1f), Curves(), Look.NONE, 1f)
        assertTrue(Argb.red(whites.apply(grey(200))) > 230)
        assertEquals(grey(0), whites.apply(grey(0)))
        val liftBlacks = TonePipeline.build(Tone(blacks = 1f), Curves(), Look.NONE, 1f)
        assertTrue(Argb.red(liftBlacks.apply(grey(0))) > 50)
        assertEquals(grey(255), liftBlacks.apply(grey(255)))
        val crushBlacks = TonePipeline.build(Tone(blacks = -1f), Curves(), Look.NONE, 1f)
        assertEquals(grey(0), crushBlacks.apply(grey(80)))
    }

    @Test
    fun shadowsAndHighlightsMoveOnlyTheirEnd() {
        val s = TonePipeline.build(Tone(shadows = 1f), Curves(), Look.NONE, 1f)
        assertTrue(Argb.red(s.apply(grey(40))) > 40)
        assertTrue(abs(Argb.red(s.apply(grey(240))) - 240) <= 3)
        val h = TonePipeline.build(Tone(highlights = -1f), Curves(), Look.NONE, 1f)
        assertTrue(Argb.red(h.apply(grey(220))) < 220)
        assertTrue(abs(Argb.red(h.apply(grey(20))) - 20) <= 3)
    }

    @Test
    fun fadeLiftsBlack() {
        val p = TonePipeline.build(Tone(fade = 1f), Curves(), Look.NONE, 1f)
        assertTrue(Argb.red(p.apply(grey(0))) > 30)
    }

    @Test
    fun curvesFollowControlPoints() {
        val c = Curves(master = listOf(Pt(0f, 0f), Pt(0.5f, 0.75f), Pt(1f, 1f)))
        val p = TonePipeline.build(Tone(), c, Look.NONE, 1f)
        assertTrue(abs(Argb.red(p.apply(grey(128))) - 191) <= 2)
        assertEquals(grey(0), p.apply(grey(0)))
        assertEquals(grey(255), p.apply(grey(255)))
        // Monotone between points: no ringing.
        val table = CurveMath.table(listOf(Pt(0f, 0f), Pt(0.2f, 0.9f), Pt(1f, 1f)))
        var last = -1f
        for (v in table) { assertTrue(v >= last - 1e-6f); last = v }
    }

    @Test
    fun lookAtZeroStrengthIsTheUsersOwnTone() {
        val user = Tone(exposure = 0.3f)
        val a = TonePipeline.build(user, Curves(), Look.NONE, 1f)
        val b = TonePipeline.build(user, Curves(), Look.TEAL_ORANGE, 0f)
        for (v in 0..255 step 17) assertEquals(a.apply(grey(v)), b.apply(grey(v)))
    }

    @Test
    fun everyLookIsVisibleAndStaysInRange() {
        for (look in Look.entries) {
            val effective = Looks.combine(Tone(), Curves(), look, 1f)
            assertTrue("$look tone valid", effective.tone.valid)
            assertTrue("$look curves valid", effective.curves.valid)
            val p = TonePipeline.build(Tone(), Curves(), look, 1f)
            if (look == Look.NONE) assertTrue(p.isIdentity) else assertFalse("$look does something", p.isIdentity)
        }
    }

    @Test
    fun transparentPixelsAreLeftAlone() {
        val p = TonePipeline.build(Tone(exposure = 2f), Curves(), Look.NONE, 1f)
        assertEquals(0, p.apply(0))
    }

    @Test
    fun rowRangeAppliesOnlyThoseRows() {
        val p = TonePipeline.build(Tone(exposure = 1f), Curves(), Look.NONE, 1f)
        val pixels = IntArray(6) { grey(100) }
        p.apply(pixels, 2, 1, 2)
        assertEquals(grey(100), pixels[0]); assertEquals(grey(100), pixels[1])
        assertEquals(grey(200), pixels[2]); assertEquals(grey(200), pixels[3])
        assertEquals(grey(100), pixels[4])
    }
}
