package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private fun scene(): IntArray {
        val w = 200; val h = 100
        return IntArray(w * h) { i ->
            val x = i % w
            when {
                x < 90 -> Argb.pack(255, 200, 30, 40)    // red
                x < 180 -> Argb.pack(255, 30, 60, 220)   // blue
                else -> Argb.pack(255, 120, 120, 120)    // grey
            }
        }
    }

    @Test
    fun theTwoColoursOfAPictureComeOutRedAndBlue() {
        val colours = Palette.of(scene(), count = 3, step = 1)
        assertEquals(2, colours.size)
        val hues = colours.map { Palette.hsv(Argb.red(it), Argb.green(it), Argb.blue(it))[0] }
        assertTrue("red hue, got $hues", hues.any { it < 15f || it > 345f })
        assertTrue("blue hue, got $hues", hues.any { it in 215f..250f })
        for (c in colours) {
            val hsv = Palette.hsv(Argb.red(c), Argb.green(c), Argb.blue(c))
            assertTrue("lifted to a light", hsv[1] >= 0.6f && hsv[2] >= 0.8f)
        }
    }

    @Test
    fun aGreyPictureGivesNothing() {
        val grey = IntArray(5000) { Argb.pack(255, 90, 90, 90) }
        assertTrue(Palette.of(grey, step = 1).isEmpty())
    }

    @Test
    fun hsvRoundTrips() {
        for ((r, g, b) in listOf(Triple(255, 0, 0), Triple(0, 255, 0), Triple(0, 0, 255), Triple(200, 120, 30), Triple(10, 200, 180))) {
            val hsv = Palette.hsv(r, g, b)
            val back = Palette.rgb(hsv[0], hsv[1], hsv[2])
            assertEquals(r, Argb.red(back)); assertEquals(g, Argb.green(back)); assertEquals(b, Argb.blue(back))
        }
    }
}
