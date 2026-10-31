package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test
    fun fitStaysInsideBudgetAndKeepsAspect() {
        val s = Geometry2D.fit(30000, 20000, 4_000_000)
        assertTrue(s.pixels <= 4_000_000)
        assertEquals(1.5, s.aspect.toDouble(), 0.002)
        val tiny = Geometry2D.fit(1, 100000, 100)
        assertEquals(1, tiny.width); assertTrue(tiny.height <= 100)
        assertEquals(Geometry2D.Size(8192, 4096), Geometry2D.fit(16384, 8192, 100_000_000))
    }

    @Test
    fun sampleSizeIsThePowerOfTwoThatFits() {
        assertEquals(1, Geometry2D.sampleSize(1000, 1000, 1_000_000))
        assertEquals(2, Geometry2D.sampleSize(1001, 1000, 1_000_000))
        assertEquals(4, Geometry2D.sampleSize(8000, 6000, 4_000_000))
        assertEquals(2, Geometry2D.sampleSize(10000, 10, 1_000_000)) // edge limit
    }

    @Test
    fun rotatedBoundsGrowWithStraighten() {
        assertEquals(Geometry2D.Size(100, 50), Geometry2D.rotatedBounds(100, 50, 0f))
        val b = Geometry2D.rotatedBounds(100, 50, 45f)
        assertTrue(b.width > 100 && b.height > 50)
        assertEquals(Geometry2D.Size(50, 100), Geometry2D.rotatedBounds(100, 50, 90f))
    }

    @Test
    fun canvasSizeFollowsTheOrderOfOperations() {
        val src = Geometry2D.Size(4000, 3000)
        assertEquals(src, Geometry2D.canvasSize(src, Geometry()))
        assertEquals(Geometry2D.Size(3000, 4000), Geometry2D.canvasSize(src, Geometry(rotation = 90)))
        assertEquals(Geometry2D.Size(2000, 3000), Geometry2D.canvasSize(src, Geometry(crop = Crop(0.25f, 0f, 0.75f, 1f))))
        assertEquals(Geometry2D.Size(800, 600), Geometry2D.canvasSize(src, Geometry(width = 800, height = 600)))
        // Crop measured after the turn: half the width of the turned (3000-wide) canvas.
        assertEquals(Geometry2D.Size(1500, 4000), Geometry2D.canvasSize(src, Geometry(rotation = 90, crop = Crop(0f, 0f, 0.5f, 1f))))
    }

    @Test
    fun aspectPresetMeansWhatItSaysAfterAQuarterTurn() {
        val src = Geometry2D.Size(4000, 3000)
        val turned = Geometry2D.turned(src, 1)
        val c = Geometry2D.aspectCrop(turned, 16f / 9f)
        val out = Geometry2D.canvasSize(src, Geometry(rotation = 90, crop = c))
        assertEquals(16f / 9f, out.aspect, 0.01f)
    }

    @Test
    fun constrainKeepsCropInsideAndLocksAspect() {
        val c = Geometry2D.constrain(Crop(-0.2f, 0.5f, 0.51f, 0.505f))
        assertTrue(c.valid)
        assertEquals(0f, c.left, 0f)
        val locked = Geometry2D.constrain(Crop(0.1f, 0.1f, 0.9f, 0.9f), lockAspect = 1f, canvas = Geometry2D.Size(2000, 1000))
        assertTrue(locked.valid)
        assertEquals(1f, locked.width * 2000 / (locked.height * 1000), 0.01f)
    }

    @Test
    fun oldSourceCropRotatesIntoTheNewFrame() {
        // Keep the left half of the source; after a clockwise quarter turn that is the top half.
        val c = Geometry2D.rotateCrop(Crop(0f, 0f, 0.5f, 1f), 90, false, false)
        assertEquals(Crop(0f, 0f, 1f, 0.5f), c)
        assertEquals(Crop(0.5f, 0f, 1f, 1f), Geometry2D.rotateCrop(Crop(0f, 0f, 0.5f, 1f), 0, true, false))
        assertEquals(Crop(0f, 0f, 0.5f, 1f), Geometry2D.rotateCrop(Crop(0f, 0f, 0.5f, 1f), 360, false, false))
    }

    @Test
    fun framedSizeAddsPaddingAndAspect() {
        val canvas = Geometry2D.Size(1000, 500)
        assertEquals(canvas, Geometry2D.framedSize(canvas, Frame()))
        val padded = Geometry2D.framedSize(canvas, Frame(padding = 0.1f))
        assertEquals(Geometry2D.Size(1100, 600), padded)
        val square = Geometry2D.framedSize(canvas, Frame(aspect = 1f))
        assertEquals(square.width, square.height)
        assertTrue(square.width >= 1000)
    }

    @Test
    fun quadValidityRejectsTwistsAndCollapses() {
        assertTrue(Quad.IDENTITY.valid)
        assertTrue(Quad(Pt(0.1f, 0.1f), Pt(0.9f, 0.05f), Pt(0.95f, 0.9f), Pt(0.05f, 0.85f)).valid)
        assertFalse(Quad(Pt(0.9f, 0f), Pt(0f, 0f), Pt(1f, 1f), Pt(0f, 1f)).valid) // twisted
        assertFalse(Quad(Pt(0.5f, 0.5f), Pt(0.5f, 0.5f), Pt(0.5f, 0.5f), Pt(0.5f, 0.5f)).valid)
    }

    @Test
    fun resolutionScaleIsOneAtTheReferenceEdge() {
        assertEquals(1f, Geometry2D.resolutionScale(Geometry2D.Size(1000, 2000)), 1e-6f)
        assertEquals(0.5f, Geometry2D.resolutionScale(Geometry2D.Size(500, 2000)), 1e-6f)
    }

    @Test
    fun innerCropHasNoEmptyCornersAndKeepsTheShape() {
        assertEquals(Crop.FULL, Geometry2D.innerCrop(1600, 1000, 0f))
        for (deg in listOf(-12f, -6f, 3f, 6f, 20f)) {
            val c = Geometry2D.innerCrop(1600, 1000, deg)
            assertTrue(c.valid)
            val b = Geometry2D.rotatedBounds(1600, 1000, deg)
            // Same shape as the picture.
            assertEquals(1.6f, c.width * b.width / (c.height * b.height), 0.02f)
            // Every corner of the crop lies inside the turned picture: rotate it back and check.
            val r = Math.toRadians(deg.toDouble())
            for ((fx, fy) in listOf(c.left to c.top, c.right to c.top, c.right to c.bottom, c.left to c.bottom)) {
                val x = (fx - 0.5f) * b.width; val y = (fy - 0.5f) * b.height
                val ux = x * kotlin.math.cos(-r) - y * kotlin.math.sin(-r); val uy = x * kotlin.math.sin(-r) + y * kotlin.math.cos(-r)
                assertTrue("deg $deg corner $ux,$uy", kotlin.math.abs(ux) <= 800.5 && kotlin.math.abs(uy) <= 500.5)
            }
        }
    }
}
