package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class LevelTest {

    private fun grey(v: Double): Int { val c = v.toInt().coerceIn(0, 255); return (0xFF shl 24) or (c shl 16) or (c shl 8) or c }

    private fun gauss(r: Random): Double { var s = 0.0; repeat(6) { s += r.nextDouble() }; return (s - 3.0) * sqrt2 }
    private val sqrt2 = 1.41421356

    /**
     * Sky over ground, the horizon through the centre leaning [tilt] degrees clockwise on
     * screen (y down), with sensor noise and some diagonal clutter that must not vote.
     */
    private fun horizon(w: Int, h: Int, tilt: Double, seed: Int = 1): IntArray {
        val r = Random(seed)
        val t = Math.toRadians(tilt); val nx = -sin(t); val ny = cos(t)
        val cx = w / 2.0; val cy = h / 2.0
        return IntArray(w * h) { i ->
            val x = i % w + 0.5; val y = i / w + 0.5
            val d = (x - cx) * nx + (y - cy) * ny // > 0 below the horizon
            val ground = (0.5 + d).coerceIn(0.0, 1.0)
            var v = 200 - 130 * ground
            // Diagonal furrows in the ground, at 45 degrees: outside the band that votes.
            if (d > 40 && ((x + y).toInt() / 37) % 2 == 0) v -= 25
            grey(v + 12 * gauss(r))
        }
    }

    /** Vertical stripes, a building's edges, leaning [tilt] degrees clockwise on screen. */
    private fun building(w: Int, h: Int, tilt: Double, seed: Int = 2): IntArray {
        val r = Random(seed)
        val t = Math.toRadians(tilt)
        val cx = w / 2.0; val cy = h / 2.0
        return IntArray(w * h) { i ->
            val x = i % w + 0.5; val y = i / w + 0.5
            // Distance across the turned vertical direction (-sin t, cos t).
            val u = (x - cx) * cos(t) + (y - cy) * sin(t) + 10_000
            val f = u - floor(u / 150) * 150
            val e = min(min(f, abs(75 - f)), 150 - f).coerceAtMost(0.5)
            val lit = if (f < 75) 0.5 + e else 0.5 - e
            grey(70 + 120 * lit + 10 * gauss(r))
        }
    }

    /**
     * The test of the sign: turn the found line's ends the way the renderer turns the picture
     * (android.graphics.Matrix.postRotate, y down, positive clockwise) and see that it ends up
     * level, or plumb.
     */
    private fun leanAfterApplying(s: Level.Suggestion, w: Int, h: Int): Double {
        val a = Math.toRadians(s.degrees.toDouble())
        fun turn(x: Double, y: Double) = doubleArrayOf(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
        val p = turn(s.x0 * w.toDouble(), s.y0 * h.toDouble())
        val q = turn(s.x1 * w.toDouble(), s.y1 * h.toDouble())
        var deg = Math.toDegrees(atan2(q[1] - p[1], q[0] - p[0]))
        while (deg > 45) deg -= 90.0
        while (deg <= -45) deg += 90.0
        return deg
    }

    private fun checkHorizon(tilt: Double) {
        val w = 1600; val h = 1200
        val s = Level.suggest(horizon(w, h, tilt), w, h)
        assertNotNull("tilt $tilt", s)
        s!!
        println("level: horizon at $tilt -> ${s.degrees} (confidence ${s.confidence}) line (${s.x0},${s.y0})-(${s.x1},${s.y1})")
        assertEquals("tilt $tilt", -tilt, s.degrees.toDouble(), 0.5)
        assertEquals("applying it levels the line", 0.0, leanAfterApplying(s, w, h), 0.5)
        assertTrue(s.confidence in 0.2f..1f)
        // The ends sit on the drawn horizon and span most of the picture.
        val t = Math.toRadians(tilt)
        for ((x, y) in listOf(s.x0 to s.y0, s.x1 to s.y1)) {
            val d = (x * w - w / 2.0) * -sin(t) + (y * h - h / 2.0) * cos(t)
            assertTrue("end on the line: $d px", abs(d) < 8)
        }
        assertTrue("spans the picture", abs(s.x1 - s.x0) > 0.6f)
    }

    @Test fun horizonLeaningAnticlockwise() = checkHorizon(-7.0)
    @Test fun horizonLeaningSlightly() = checkHorizon(3.5)
    @Test fun horizonLeaningClockwise() = checkHorizon(12.0)
    @Test fun horizonNearTheLimit() = checkHorizon(-18.0)

    @Test fun levelPictureNeedsNoNudge() {
        val s = Level.suggest(horizon(1600, 1200, 0.0), 1600, 1200)
        assertTrue("level: $s", s == null || abs(s.degrees) < 0.3f)
    }

    @Test fun aRoofIsNotAHorizon() {
        val s = Level.suggest(horizon(1600, 1200, 30.0), 1600, 1200)
        assertTrue("30 degrees: $s", s == null || abs(s.degrees) <= Level.LIMIT)
        assertNull(s)
    }

    @Test fun pureNoiseSuggestsNothing() {
        val r = Random(3)
        val w = 1600; val h = 1200
        assertNull(Level.suggest(IntArray(w * h) { grey(r.nextDouble() * 255) }, w, h))
        // Blotchy noise has strong edges, but in every direction at once.
        val gw = 41; val gh = 31
        val grid = DoubleArray(gw * gh) { r.nextDouble() * 255 }
        val blotches = IntArray(w * h) { i ->
            val fx = (i % w) / 40.0; val fy = (i / w) / 40.0
            val x0 = fx.toInt(); val y0 = fy.toInt(); val ax = fx - x0; val ay = fy - y0
            val top = grid[y0 * gw + x0] * (1 - ax) + grid[y0 * gw + x0 + 1] * ax
            val bottom = grid[(y0 + 1) * gw + x0] * (1 - ax) + grid[(y0 + 1) * gw + x0 + 1] * ax
            grey(top * (1 - ay) + bottom * ay)
        }
        assertNull(Level.suggest(blotches, w, h))
    }

    @Test fun flatPictureSuggestsNothing() {
        assertNull(Level.suggest(IntArray(800 * 600) { grey(128.0) }, 800, 600))
    }

    @Test fun leaningBuildingIsStoodUp() {
        val w = 1600; val h = 1200
        val s = Level.suggest(building(w, h, 4.0), w, h)
        assertNotNull(s)
        s!!
        println("level: verticals at 4.0 -> ${s.degrees} (confidence ${s.confidence})")
        assertEquals(-4.0, s.degrees.toDouble(), 0.5)
        assertEquals("applying it makes the edge plumb", 0.0, leanAfterApplying(s, w, h), 0.5)
        assertTrue("found a vertical", abs(s.y1 - s.y0) > abs(s.x1 - s.x0))
    }

    @Test fun portraitAndOddSizesWork() {
        val w = 777; val h = 1301
        val s = Level.suggest(horizon(w, h, 5.0), w, h)
        assertNotNull(s)
        assertEquals(-5.0, s!!.degrees.toDouble(), 0.5)
    }

    @Test fun tinyPicturesAreLeftAlone() {
        assertNull(Level.suggest(IntArray(10 * 10), 10, 10))
    }

    @Test fun timing() {
        val w = 1600; val h = 1200
        val px = horizon(w, h, 6.0)
        Level.suggest(px, w, h) // warm up
        var best = Long.MAX_VALUE
        repeat(5) {
            val t0 = System.nanoTime(); Level.suggest(px, w, h); best = min(best, System.nanoTime() - t0)
        }
        println("level: ${w}x$h suggest ${best / 1_000_000.0} ms (best of 5)")
        assertTrue(max(0L, best) < 2_000_000_000L)
    }
}
