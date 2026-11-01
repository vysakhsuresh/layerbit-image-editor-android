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

    @Test
    fun aLoosePatchAwayFromThePersonIsDroppedUnlessKept() {
        val w = 300; val h = 400
        val px = portrait(w, h)
        // A dark sign in the top corner, unlike the wall, touching nothing: a guess at subject that is no person.
        for (y in 30..80) for (x in 220..280) px[y * w + x] = Argb.pack(255, 60, 30, 90)
        val m = Segment.subject(px, w, h, emptyList())
        fun at(fx: Float, fy: Float) = m[(fy * h).toInt() * w + (fx * w).toInt()]
        assertTrue("face", at(0.5f, 0.38f) == 255)
        assertTrue("the sign is not the subject", at(0.83f, 0.14f) == 0)
        // The same sign kept by a stroke stays kept.
        val kept = Segment.subject(px, w, h, listOf(Scribble(listOf(Pt(0.83f, 0.14f)), 0.03f, true)))
        assertTrue("a kept sign stays", kept[(0.14f * h).toInt() * w + (0.83f * w).toInt()] == 255)
    }

    @Test
    fun twoPeopleApartAreBothKept() {
        val w = 600; val h = 400
        val rnd = Random(9)
        // Two small heads and shoulders, apart, against a plain wall.
        fun person(fx: Float, fy: Float, cx: Float): Int? {
            val face = ((fx - cx) / 0.07f).let { it * it } + ((fy - 0.36f) / 0.13f).let { it * it } < 1f
            val hair = ((fx - cx) / 0.085f).let { it * it } + ((fy - 0.29f) / 0.14f).let { it * it } < 1f && fy < 0.33f
            val shirt = fy > 0.52f && kotlin.math.abs(fx - cx) < 0.09f + (fy - 0.52f) * 0.25f
            return when { hair -> Argb.pack(255, 35, 28, 25); face -> Argb.pack(255, 214, 165, 135); shirt -> Argb.pack(255, 40, 70, 140); else -> null }
        }
        val px = IntArray(w * h) { i ->
            val fx = (i % w + 0.5f) / w; val fy = (i / w + 0.5f) / h
            val n = rnd.nextInt(9) - 4
            val c = person(fx, fy, 0.27f) ?: person(fx, fy, 0.73f) ?: Argb.pack(255, 205, 210, 205)
            Argb.pack(255, Argb.red(c) + n, Argb.green(c) + n, Argb.blue(c) + n)
        }
        val m = Segment.subject(px, w, h, emptyList())
        fun at(fx: Float, fy: Float) = m[(fy * h).toInt() * w + (fx * w).toInt()]
        assertTrue("left face", at(0.27f, 0.38f) == 255)
        assertTrue("right face", at(0.73f, 0.38f) == 255)
        assertTrue("wall between", at(0.5f, 0.3f) == 0)
    }
}
