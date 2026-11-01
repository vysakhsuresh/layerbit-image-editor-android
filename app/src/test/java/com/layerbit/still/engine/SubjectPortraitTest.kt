package com.layerbit.still.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SubjectPortraitTest {
    /** Head and shoulders against a plain wall: a face, dark hair above it, a shirt running off the bottom edge. */
    private fun portrait(w: Int, h: Int): IntArray {
        val rnd = Random(3)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val fx = (x + 0.5f) / w; val fy = (y + 0.5f) / h
            val n = rnd.nextInt(9) - 4
            val face = ((fx - 0.5f) / 0.14f).let { it * it } + ((fy - 0.36f) / 0.13f).let { it * it } < 1f
            val hair = ((fx - 0.5f) / 0.17f).let { it * it } + ((fy - 0.29f) / 0.14f).let { it * it } < 1f && fy < 0.33f
            val neck = kotlin.math.abs(fx - 0.5f) < 0.06f && fy in 0.45f..0.56f
            val shirt = fy > 0.55f && kotlin.math.abs(fx - 0.5f) < 0.18f + (fy - 0.55f) * 0.9f
            when {
                hair -> Argb.pack(255, 35 + n, 28 + n, 25 + n)
                face || neck -> Argb.pack(255, 214 + n, 165 + n, 135 + n)
                shirt -> Argb.pack(255, 40 + n, 70 + n, 140 + n)
                else -> Argb.pack(255, 205 + n, 210 + n, 205 + n)
            }
        }
    }

    @Test
    fun aPersonsShouldersRunningOffTheBottomStayInTheSubject() {
        val w = 300; val h = 400
        val px = portrait(w, h)
        val m = Segment.subject(px, w, h, emptyList())
        fun at(fx: Float, fy: Float) = m[(fy * h).toInt() * w + (fx * w).toInt()]
        assertTrue("face", at(0.5f, 0.38f) == 255)
        assertTrue("shirt at the bottom edge", at(0.5f, 0.97f) == 255)
        assertTrue("shirt middle", at(0.5f, 0.75f) == 255)
        assertTrue("wall corner", at(0.05f, 0.05f) == 0)
        assertTrue("wall beside the shirt", at(0.06f, 0.7f) == 0)
    }
}
