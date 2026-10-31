package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColourMixTest {

    private val sky = Argb.pack(255, 90, 140, 220)
    private val skin = Argb.pack(255, 214, 160, 128)
    private val grey = Argb.pack(255, 128, 128, 128)

    @Test
    fun darkeningBlueLeavesSkinAndGreyAlone() {
        val mix = ColourMix().with(ColourMix.Kind.LIGHTNESS, 5, -1f).compile()!!
        val darker = mix.apply(sky)
        assertTrue("sky darker", Argb.luma(darker) < Argb.luma(sky) - 15)
        val s = mix.apply(skin)
        assertTrue("skin kept", abs(Argb.red(s) - Argb.red(skin)) <= 2 && abs(Argb.blue(s) - Argb.blue(skin)) <= 2)
        assertEquals(grey, mix.apply(grey))
    }

    @Test
    fun desaturatingOrangeCalmsSkin() {
        val mix = ColourMix().with(ColourMix.Kind.SATURATION, 1, -1f).compile()!!
        val s = mix.apply(skin)
        assertTrue((Argb.red(s) - Argb.blue(s)) < (Argb.red(skin) - Argb.blue(skin)) - 20)
    }

    @Test
    fun hueShiftMovesTheColour() {
        val mix = ColourMix().with(ColourMix.Kind.HUE, 3, 1f).compile()!!  // greens towards aqua
        val green = Argb.pack(255, 60, 180, 60)
        val out = mix.apply(green)
        assertTrue("more blue ${Argb.blue(out)}", Argb.blue(out) > Argb.blue(green) + 20)
    }

    @Test
    fun identityIsNullAndRoundTripsAreExact() {
        assertNull(ColourMix().compile())
        // A tiny move leaves a colour within a step of itself.
        val tiny = ColourMix().with(ColourMix.Kind.SATURATION, 0, 0.0001f).compile()!!
        val c = Argb.pack(255, 200, 40, 50)
        val o = tiny.apply(c)
        assertTrue(abs(Argb.red(o) - 200) <= 1 && abs(Argb.green(o) - 40) <= 1 && abs(Argb.blue(o) - 50) <= 1)
        val (band, t) = ColourMix.locate(350f)
        assertEquals(7, band); assertTrue(t in 0f..1f)
        assertTrue(ColourMix().with(ColourMix.Kind.HUE, 2, 5f).valid)
    }
}
