package com.layerbit.still.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralTest {
    private fun rendered(c: IntArray, t: Tone): IntArray {
        val px = intArrayOf(Argb.pack(255, c[0], c[1], c[2]))
        TonePipeline.build(t, Curves(), Look.NONE, 1f).apply(px, 1, 0, 1)
        return intArrayOf(Argb.red(px[0]), Argb.green(px[0]), Argb.blue(px[0]))
    }

    @Test
    fun theTappedColourComesOutGrey() {
        val casts = listOf(intArrayOf(190, 172, 150), intArrayOf(125, 135, 165), intArrayOf(150, 175, 140), intArrayOf(175, 150, 168), intArrayOf(90, 80, 60))
        for (c in casts) for (base in listOf(Tone(), Tone(exposure = 0.3f, saturation = 0.4f))) {
            val t = Retouch.neutralise(c[0], c[1], c[2], base)
            val o = rendered(c, t)
            val spread = maxOf(o[0], o[1], o[2]) - minOf(o[0], o[1], o[2])
            assertTrue("${c.toList()} -> ${o.toList()} at ${t.temperature}, ${t.tint}", spread <= 3)
        }
        // A cast beyond the sliders' reach still comes out as close to grey as they allow.
        val tungsten = rendered(intArrayOf(200, 170, 130), Retouch.neutralise(200, 170, 130, Tone()))
        assertTrue(tungsten.toList().toString(), maxOf(tungsten[0], tungsten[1], tungsten[2]) - minOf(tungsten[0], tungsten[1], tungsten[2]) <= 20)
    }
}
