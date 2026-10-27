package com.layerbit.studio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class SpatialTest {

    private fun noise(w: Int, h: Int, seed: Long = 3): IntArray {
        val r = Random(seed)
        return IntArray(w * h) { Argb.pack(255, r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
    }

    @Test
    fun blurOfFlatColourIsTheSameColour() {
        val flat = IntArray(40 * 30) { Argb.pack(255, 10, 200, 90) }
        val out = Spatial.boxBlur(flat, 40, 30, 5)
        assertTrue(out.all { it == flat[0] })
    }

    @Test
    fun blurReducesContrastAndKeepsMean() {
        val src = noise(64, 48)
        val out = Spatial.boxBlur(src, 64, 48, 4)
        fun spread(a: IntArray): Double { val m = a.map { Argb.red(it) }.average(); return a.map { (Argb.red(it) - m) * (Argb.red(it) - m) }.average() }
        assertTrue(spread(out) < spread(src) / 4)
        assertTrue(abs(src.map { Argb.red(it) }.average() - out.map { Argb.red(it) }.average()) < 4)
    }

    @Test
    fun blurDoesNotBleedIntoTransparency() {
        val w = 20; val h = 4
        val src = IntArray(w * h) { if (it % w < 10) Argb.pack(255, 255, 0, 0) else 0 }
        val out = Spatial.boxBlur(src, w, h, 3)
        // Transparent side stays transparent; red side stays red where it was opaque.
        assertEquals(0, Argb.alpha(out[w - 1]))
        assertEquals(255, Argb.red(out[0]))
    }

    @Test
    fun unsharpOfFlatIsFlatAndOfEdgeIsSharper() {
        val flat = IntArray(30 * 30) { Argb.pack(255, 120, 120, 120) }
        assertTrue(Spatial.unsharp(flat, 30, 30, 2, 1f).all { it == flat[0] })
        val w = 40
        val edge = IntArray(w * 10) { if (it % w < 20) Argb.pack(255, 60, 60, 60) else Argb.pack(255, 190, 190, 190) }
        val out = Spatial.unsharp(edge, w, 10, 2, 1f)
        // Just inside the dark side the value drops; just inside the bright side it rises.
        assertTrue(Argb.red(out[5 * w + 19]) < 60)
        assertTrue(Argb.red(out[5 * w + 20]) > 190)
    }

    @Test
    fun detailScalesWithResolutionSoPreviewPredictsExport() {
        // The same scene at two sizes: a soft edge. After sharpening, downsampling the large
        // result should agree with the small result to within a few levels.
        fun scene(w: Int, h: Int) = IntArray(w * h) { i ->
            val x = (i % w) / w.toFloat()
            val v = (255 * (1f / (1f + Math.exp((-(x - 0.5) * 40).toDouble())))).toInt()
            Argb.pack(255, v, v, v)
        }
        val tone = Tone(sharpness = 1f, clarity = 0.5f)
        val small = Spatial.detail(scene(200, 60), 200, 60, tone)
        val large = Spatial.detail(scene(800, 240), 800, 240, tone)
        var worst = 0
        for (y in 0 until 60) for (x in 0 until 200) {
            var sum = 0
            for (dy in 0 until 4) for (dx in 0 until 4) sum += Argb.red(large[(y * 4 + dy) * 800 + x * 4 + dx])
            val d = abs(sum / 16 - Argb.red(small[y * 200 + x]))
            if (d > worst) worst = d
        }
        assertTrue("preview and export agree, worst $worst", worst <= 24)
    }

    @Test
    fun vignetteDarkensCornersNotCentre() {
        val w = 50; val h = 40
        val p = IntArray(w * h) { Argb.pack(255, 200, 200, 200) }
        Spatial.vignette(p, w, h, 1f)
        assertEquals(200, Argb.red(p[(h / 2) * w + w / 2]))
        assertTrue(Argb.red(p[0]) < 120)
        val q = IntArray(w * h) { Argb.pack(255, 100, 100, 100) }
        Spatial.vignette(q, w, h, -1f)
        assertTrue(Argb.red(q[0]) > 100)
    }

    @Test
    fun grainIsDeterministicAndKeepsMean() {
        val w = 80; val h = 60
        val a = IntArray(w * h) { Argb.pack(255, 128, 128, 128) }
        val b = a.copyOf()
        Spatial.grain(a, w, h, 0.8f); Spatial.grain(b, w, h, 0.8f)
        assertTrue(a.contentEquals(b))
        assertNotEquals(128, Argb.red(a[0]) + Argb.red(a[1]) + Argb.red(a[2]) - 256) // moved something
        assertTrue(abs(a.map { Argb.red(it) }.average() - 128) < 3)
    }

    @Test
    fun pixelateAveragesBlocks() {
        val w = 8; val h = 4
        val p = IntArray(w * h) { if (it % 2 == 0) Argb.pack(255, 0, 0, 0) else Argb.pack(255, 200, 200, 200) }
        Spatial.pixelate(p, w, h, 0, 0, w, h, 4)
        assertTrue(p.all { Argb.red(it) == 100 })
    }
}
