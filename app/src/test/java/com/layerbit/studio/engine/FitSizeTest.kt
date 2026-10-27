package com.layerbit.studio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitSizeTest {

    /** A codec whose output grows with quality and area, like a real one. */
    private fun fake(w: Int, h: Int, q: Int): ByteArray = ByteArray((w.toLong() * h * (10 + q) / 400).toInt().coerceAtLeast(1))

    @Test
    fun bestQualityIsTheHighestThatFits() {
        var calls = 0
        val shot = FitSize.bestQuality(150_000) { q -> calls++; fake(1000, 800, q) }
        assertTrue(shot.fits)
        assertTrue(shot.bytes.size < 150_000)
        // One quality higher would not fit (within the search's step of 2).
        assertTrue(fake(1000, 800, shot.quality + 3).size >= 150_000)
        assertTrue("few encodes: $calls", calls <= 9)
    }

    @Test
    fun bestQualityReportsWhenNothingFits() {
        val shot = FitSize.bestQuality(10) { q -> fake(1000, 800, q) }
        assertFalse(shot.fits)
        assertEquals(FitSize.LOWEST_QUALITY, shot.quality)
    }

    @Test
    fun bestSizeFindsTheLargestPictureUnderTheLimit() {
        val limit = 5_000
        val scaled = FitSize.bestSize(limit, 2000, 1500) { w, h, q -> fake(w, h, q) }
        assertNotNull(scaled)
        scaled!!
        assertTrue(scaled.shot.fits)
        assertTrue(scaled.shot.bytes.size < limit)
        assertEquals(2000f / 1500f, scaled.width.toFloat() / scaled.height, 0.02f)
        // Within a few percent of the largest side that could fit at the lowest quality.
        val sideThatJustFits = run { var s = 2000; while (fake(s, s * 3 / 4, FitSize.LOWEST_QUALITY).size >= limit) s -= 1; s }
        assertTrue("${scaled.width} close to $sideThatJustFits", scaled.width >= sideThatJustFits * 0.9)
    }

    @Test
    fun bestSizeGivesUpAtTheSmallestSide() {
        assertNull(FitSize.bestSize(1, 2000, 1500) { w, h, q -> fake(w, h, q) + ByteArray(50) })
    }

    @Test
    fun textsNeverRoundUpPastTheLimit() {
        assertEquals("50 KB", FitSize.limitText(50f))
        assertEquals("19.5 KB", FitSize.limitText(19.5f))
        assertEquals("1.2 MB", FitSize.limitText(1200f))
        assertEquals("2 MB", FitSize.limitText(2000f))
        assertEquals("49 KB", FitSize.sizeText(49_999))
        assertEquals("1.2 MB", FitSize.sizeText(1_299_999))
        assertEquals("0.5 KB", FitSize.sizeText(512))
        assertEquals(50_000, FitSize.limitBytes(50f, "KB"))
        assertEquals(1_500_000, FitSize.limitBytes(1.5f, "MB"))
    }
}
