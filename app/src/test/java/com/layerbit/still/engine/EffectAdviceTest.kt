package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectAdviceTest {
    private fun mix(vararg parts: Pair<Int, Int>): IntArray = parts.flatMap { (c, n) -> List(n) { c } }.toIntArray()

    @Test
    fun blackAndWhiteStartsWithTheFilterThePictureNeeds() {
        val sky = Argb.pack(255, 90, 150, 225); val grass = Argb.pack(255, 70, 140, 50)
        val skin = Argb.pack(255, 220, 170, 140); val grey = Argb.pack(255, 120, 120, 120)
        assertEquals(1, EffectAdvice.style(EffectKind.BLACK_WHITE, EffectAdvice.content(mix(sky to 400, grey to 600), 1)))
        assertEquals(4, EffectAdvice.style(EffectKind.BLACK_WHITE, EffectAdvice.content(mix(grass to 400, grey to 600), 1)))
        assertEquals(2, EffectAdvice.style(EffectKind.BLACK_WHITE, EffectAdvice.content(mix(skin to 300, grey to 700), 1)))
        assertEquals(0, EffectAdvice.style(EffectKind.BLACK_WHITE, EffectAdvice.content(mix(grey to 1000), 1)))
        assertEquals(0, EffectAdvice.style(EffectKind.DRAMA, EffectAdvice.content(mix(sky to 1000), 1)))
    }

    @Test
    fun lensBlurAndVignetteSitOnTheSubject() {
        // A tall subject on the left third of a 300 x 200 canvas.
        val w = 300; val h = 200
        val mask = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 60..120 && y in 40..180) 255 else 0 }
        val s = EffectAdvice.subject(mask, w, h)!!
        assertEquals(0.3f, s.cx, 0.02f); assertEquals(0.55f, s.cy, 0.02f)
        val blur = EffectAdvice.placed(Effect.added(EffectKind.LENS_BLUR), s, w, h)
        assertEquals(1, blur.style)
        assertEquals(0.3f, blur.center.x, 0.02f)
        org.junit.Assert.assertTrue("taller than wide: ${blur.aspect}", blur.aspect > 1.5f)
        org.junit.Assert.assertTrue(blur.valid)
        val vig = EffectAdvice.placed(Effect.added(EffectKind.VIGNETTE), s, w, h)
        assertEquals(0.3f, vig.center.x, 0.02f)
        org.junit.Assert.assertTrue(vig.valid)
        // A subject filling the frame leaves the defaults alone.
        val full = EffectAdvice.subject(IntArray(w * h) { 255 }, w, h)
        assertEquals(Effect.added(EffectKind.VIGNETTE), EffectAdvice.placed(Effect.added(EffectKind.VIGNETTE), full, w, h))
    }
}
