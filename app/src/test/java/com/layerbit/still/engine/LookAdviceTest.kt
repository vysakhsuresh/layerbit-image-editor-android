package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookAdviceTest {

    @Test
    fun aPageAsksForDocument() {
        val page = IntArray(20_000) { i -> if (i % 25 == 0) Argb.pack(255, 30, 30, 34) else Argb.pack(255, 236, 234, 228) }
        assertEquals(Look.DOCUMENT, LookAdvice.suggest(page).first())
    }

    @Test
    fun aFaceAsksForPortrait() {
        val face = IntArray(20_000) { i -> if (i % 3 == 0) Argb.pack(255, 60, 70, 90) else Argb.pack(255, 214, 160, 128) }
        assertTrue(Look.PORTRAIT in LookAdvice.suggest(face))
    }

    @Test
    fun skyAndSunsetAskForTealAndOrange() {
        val beach = IntArray(20_000) { i -> if (i % 2 == 0) Argb.pack(255, 70, 140, 210) else Argb.pack(255, 235, 140, 60) }
        assertTrue(Look.TEAL_ORANGE in LookAdvice.suggest(beach))
    }

    @Test
    fun aGreyPictureAsksForMonoAndNotPortrait() {
        val grey = IntArray(20_000) { i -> val v = 40 + i % 160; Argb.pack(255, v, v, v) }
        val s = LookAdvice.suggest(grey)
        assertTrue(Look.MONO in s || Look.NOIR in s)
        assertFalse(Look.PORTRAIT in s)
    }

    @Test
    fun atMostThreeAndStable() {
        val mixed = IntArray(30_000) { i -> Argb.pack(255, (i * 7) % 256, (i * 13) % 256, (i * 29) % 256) }
        val a = LookAdvice.suggest(mixed)
        assertTrue(a.size <= 3)
        assertEquals(a, LookAdvice.suggest(mixed))
        assertTrue(LookAdvice.suggest(IntArray(0)).isEmpty())
    }
}
