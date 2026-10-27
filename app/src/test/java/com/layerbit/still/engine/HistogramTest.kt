package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramTest {

    @Test
    fun percentilesAndClipping() {
        val pixels = IntArray(1000) { Argb.pack(255, it / 4, it / 4, it / 4) } // 0..249
        val h = Histogram.of(pixels, step = 1)
        assertEquals(1000L, h.total)
        assertEquals(0, h.percentile(0f))
        assertTrue(h.percentile(0.5f) in 123..126)
        assertEquals(249, h.percentile(1f))
        assertEquals(0.004f, h.clippedBlack, 1e-6f)
        assertEquals(0f, h.clippedWhite, 0f)
    }

    @Test
    fun autoStretchesAFlatPictureAndLeavesAGoodOneAlone() {
        val flat = IntArray(5000) { Argb.pack(255, 60 + it % 140, 60 + it % 140, 60 + it % 140) }
        val t = AutoEnhance.tone(Histogram.of(flat, 1))
        assertTrue("whites ${t.whites}", t.whites > 0.3f)
        assertTrue("blacks ${t.blacks}", t.blacks < -0.2f)
        assertTrue(t.contrast > 0f)
        assertTrue(AutoEnhance.worthwhile(Histogram.of(flat, 1)))
        // Apply it and check the picture now reaches further towards both ends.
        val p = TonePipeline.build(t, Curves(), Look.NONE, 1f)
        val out = flat.copyOf(); p.apply(out, 100, 0, 50)
        val h = Histogram.of(out, 1)
        assertTrue(h.percentile(0.995f) > 200)
        assertTrue(h.percentile(0.005f) < 40)

        val good = IntArray(5000) { Argb.pack(255, (it * 255 / 4999), (it * 255 / 4999), (it * 255 / 4999)) }
        val g = AutoEnhance.tone(Histogram.of(good, 1))
        assertTrue(kotlin.math.abs(g.whites) < 0.05f && kotlin.math.abs(g.blacks) < 0.05f)
        assertFalse(AutoEnhance.worthwhile(Histogram.of(good, 1)))
    }

    @Test
    fun autoDarkensABrightPictureAndKeepsTheUsersWarmth() {
        val bright = IntArray(2000) { Argb.pack(255, 200 + it % 50, 200 + it % 50, 200 + it % 50) }
        val t = AutoEnhance.tone(Histogram.of(bright, 1), base = Tone(temperature = 0.4f))
        assertTrue(t.exposure < 0f)
        assertEquals(0.4f, t.temperature, 0f)
    }

    @Test
    fun otsuSplitsInkFromPaper() {
        val page = IntArray(4000) { if (it % 10 == 0) Argb.pack(255, 30, 30, 30) else Argb.pack(255, 230, 230, 230) }
        val t = AutoEnhance.otsu(Histogram.of(page, 1))
        assertTrue("$t between ink and paper", t in 31..230)
    }

    @Test
    fun transparentPixelsAreNotCounted() {
        val h = Histogram.of(intArrayOf(0, Argb.pack(255, 10, 10, 10)), 1)
        assertEquals(1L, h.total)
    }
}
