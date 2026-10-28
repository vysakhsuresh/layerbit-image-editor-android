package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * The banded stages give the sequential answer pixel for pixel, and the bands are faster on a
 * machine with more than one core. The timings are printed, not asserted: a CI runner's speed is
 * not a promise, the equality is.
 */
class ParallelTest {
    private val w = 1200; private val h = 1000

    private fun picture(): IntArray {
        val rnd = Random(11)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val r = (x * 255 / w + rnd.nextInt(24)).coerceIn(0, 255)
            val g = (y * 255 / h + rnd.nextInt(24)).coerceIn(0, 255)
            val b = ((x + y) * 255 / (w + h) + rnd.nextInt(24)).coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun stages(px: IntArray): IntArray {
        val tone = Tone(clarity = 0.5f, sharpness = 0.6f, vignette = 0.5f, grain = 0.4f)
        val out = Spatial.detail(px, w, h, tone)
        Spatial.vignette(out, w, h, tone.vignette)
        Spatial.grain(out, w, h, tone.grain)
        return ScanMath.apply(out, w, h, Scan(mode = ScanMode.COLOUR, strength = 0.6f))
    }

    @Test
    fun bandsGiveTheSequentialAnswer() {
        val px = picture()
        Parallel.enabled = false
        val t0 = System.nanoTime(); val sequential = stages(px); val t1 = System.nanoTime()
        Parallel.enabled = true
        val t2 = System.nanoTime(); val banded = stages(px); val t3 = System.nanoTime()
        assertArrayEquals(sequential, banded)
        println("parallel: ${w}x$h detail+vignette+grain+scan sequential ${(t1 - t0) / 1_000_000} ms, ${Parallel.workers} bands ${(t3 - t2) / 1_000_000} ms")
    }

    @Test
    fun bandsCoverEveryRowOnce() {
        val seen = IntArray(1000)
        Parallel.bands(1000, minimum = 1) { from, to -> for (i in from until to) seen[i] += 1 }
        assertEquals(1000, seen.count { it == 1 })
    }

    @Test
    fun aBandInsideABandRunsInline() {
        var innerRan = 0
        Parallel.bands(1000, minimum = 1) { _, _ -> Parallel.bands(10, minimum = 1) { from, to -> synchronized(this) { innerRan += to - from } } }
        assertEquals(10 * Parallel.workers.coerceAtMost(1000), innerRan)
    }

    @Test(expected = IllegalStateException::class)
    fun aFailureInABandIsRethrown() {
        Parallel.bands(1000, minimum = 1) { from, _ -> if (from == 0) error("band") }
    }
}
