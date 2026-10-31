package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RedEyesTest {

    private val background = Argb.pack(255, 90, 110, 130)
    private val skin = Argb.pack(255, 225, 172, 140)

    private fun canvas(w: Int, h: Int) = IntArray(w * h) { background }

    /** Paints an axis-aligned ellipse of [c] centred on ([cx], [cy]). */
    private fun ellipse(p: IntArray, w: Int, h: Int, cx: Float, cy: Float, rx: Float, ry: Float, c: Int) {
        for (y in max(0, (cy - ry).toInt())..min(h - 1, (cy + ry).toInt() + 1))
            for (x in max(0, (cx - rx).toInt())..min(w - 1, (cx + rx).toInt() + 1)) {
                val dx = (x - cx) / rx; val dy = (y - cy) / ry
                if (dx * dx + dy * dy <= 1f) p[y * w + x] = c
            }
    }

    /**
     * A face-ish scene scaled by [k] from a 1000 x 800 layout: a skin ellipse, two eyes each with
     * white sclera, a dark iris, a flash-red pupil and a catchlight, and pinkish lips. Returns
     * the pixels and the two pupil centres in pixels.
     */
    private fun face(k: Float = 1f): Triple<IntArray, Int, List<Pair<Float, Float>>> {
        val w = (1000 * k).toInt(); val h = (800 * k).toInt()
        val p = canvas(w, h)
        ellipse(p, w, h, 500 * k, 400 * k, 230 * k, 300 * k, skin)
        val eyes = listOf(420f * k to 350f * k, 580f * k to 350f * k)
        for ((x, y) in eyes) {
            ellipse(p, w, h, x, y, 32 * k, 18 * k, Argb.pack(255, 240, 238, 235))
            ellipse(p, w, h, x, y, 15 * k, 15 * k, Argb.pack(255, 60, 45, 35))
            ellipse(p, w, h, x, y, 9 * k, 9 * k, Argb.pack(255, 215, 40, 45))
            ellipse(p, w, h, x + 3 * k, y - 3 * k, 2 * k, 2 * k, Argb.pack(255, 250, 250, 250))
        }
        ellipse(p, w, h, 500 * k, 560 * k, 70 * k, 16 * k, Argb.pack(255, 200, 70, 80))
        return Triple(p, w, eyes)
    }

    private fun strongRed(c: Int): Boolean {
        val r = Argb.red(c); val e = r - max(Argb.green(c), Argb.blue(c))
        // Retouch.redEye's test, but red enough that warm skin (excess about a quarter of red) is not counted.
        return r >= 70 && e >= 40 && e * 20 >= r * 9
    }

    private fun assertFindsBothEyes(k: Float) {
        val (p, w, eyes) = face(k)
        val h = p.size / w
        val found = RedEyes.find(p, w, h)
        assertEquals("exactly the two pupils, not the lips: $found", 2, found.size)
        for ((ex, ey) in eyes) {
            val hit = found.minByOrNull { abs(it.x * w - ex) + abs(it.y * h - ey) }!!
            assertTrue("centre ${hit.x * w},${hit.y * h} vs $ex,$ey", abs(hit.x * w - ex) <= 3 && abs(hit.y * h - ey) <= 3)
            val rPx = hit.radius * min(w, h)
            assertTrue("radius $rPx covers a pupil of ${9 * k}", rPx >= 9 * k && rPx <= 20 * k)
            assertTrue("a paired eye with catchlight and iris scores high: ${hit.score}", hit.score > 1.5f)
        }
    }

    @Test
    fun findsBothPupilsOfAFace() = assertFindsBothEyes(1f)

    @Test
    fun findsBothPupilsOfALargePictureAndSaysHowLong() {
        // 1600 x 1280, about 2 MP: past the working size, so the downscaled path is exercised.
        val (p, w, _) = face(1.6f)
        val h = p.size / w
        RedEyes.find(p, w, h)  // warm-up
        val t = System.nanoTime()
        val runs = 5
        repeat(runs) { RedEyes.find(p, w, h) }
        println("RedEyes.find on ${w}x$h: ${(System.nanoTime() - t) / runs / 1_000_000} ms")
        assertFindsBothEyes(1.6f)
    }

    @Test
    fun aRedBallAndARedShirtAreNotEyes() {
        val w = 1000; val h = 800
        val p = canvas(w, h)
        ellipse(p, w, h, 250f, 300f, 140f, 140f, Argb.pack(255, 210, 30, 35))
        for (y in 550 until h) for (x in 450 until 950) p[y * w + x] = Argb.pack(255, 190, 25, 40)
        assertEquals(emptyList<RedEyes.Eye>(), RedEyes.find(p, w, h))
    }

    @Test
    fun lipsAreTooWideToBeAnEye() {
        val w = 800; val h = 600
        val p = canvas(w, h)
        ellipse(p, w, h, 400f, 300f, 220f, 280f, skin)
        ellipse(p, w, h, 400f, 420f, 60f, 14f, Argb.pack(255, 200, 70, 80))
        assertEquals(emptyList<RedEyes.Eye>(), RedEyes.find(p, w, h))
    }

    @Test
    fun aRedPatchInsideARedRegionIsNotAnEye() {
        // A small, very red spot on a red jumper: round and small, but it joins the jumper into one blob far too big for a pupil.
        val w = 800; val h = 600
        val p = canvas(w, h)
        for (y in 200 until 500) for (x in 200 until 600) p[y * w + x] = Argb.pack(255, 170, 80, 85)
        ellipse(p, w, h, 400f, 350f, 10f, 10f, Argb.pack(255, 230, 30, 30))
        assertEquals(emptyList<RedEyes.Eye>(), RedEyes.find(p, w, h))
    }

    @Test
    fun noRedNoEyes() {
        val w = 640; val h = 480
        val p = IntArray(w * h) { i -> val v = (i % w) * 255 / w; Argb.pack(255, v, v, 255 - v / 2) }
        assertTrue(RedEyes.find(p, w, h).isEmpty())
        assertTrue(RedEyes.find(IntArray(0), 0, 0).isEmpty())
    }

    @Test
    fun keepsAtMostMax() {
        val (p, w, _) = face()
        assertEquals(1, RedEyes.find(p, w, p.size / w, max = 1).size)
    }

    @Test
    fun correctingEachProposalRemovesTheRed() {
        val (p, w, eyes) = face()
        val h = p.size / w
        val before = p.count { strongRed(it) }
        val lips = (0 until p.size).count { i -> strongRed(p[i]) && i / w > 500 }
        val found = RedEyes.find(p, w, h)
        for (eye in found) {
            // Exactly what the renderer does with a REDEYE mark built from this proposal.
            val m = eye.mark()
            assertEquals(MarkKind.REDEYE, m.kind)
            val r = Geometry2D.px(m.width, Geometry2D.Size(w, h))
            Retouch.redEye(p, w, h, m.points[0].x * w, m.points[0].y * h, r)
        }
        val eyeRedBefore = before - lips
        val eyeRedAfter = (0 until p.size).count { i -> strongRed(p[i]) && i / w < 500 }
        assertTrue("pupils had red: $eyeRedBefore", eyeRedBefore > 400)
        assertTrue("red left in the eyes: $eyeRedAfter of $eyeRedBefore", eyeRedAfter * 10 < eyeRedBefore)
        assertEquals("the lips are untouched", lips, (0 until p.size).count { i -> strongRed(p[i]) && i / w > 500 })
        assertEquals(2, eyes.size)
    }
}
