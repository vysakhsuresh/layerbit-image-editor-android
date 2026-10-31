package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ExpandTest {

    private fun grey(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3.0

    private fun picture(w: Int, h: Int, f: (Int, Int) -> Int): IntArray = IntArray(w * h) { i -> val v = f(i % w, i / w).coerceIn(0, 255); Argb.pack(255, v, v, v) }

    @Test
    fun layoutGrowsEachSideByItsFraction() {
        val e = Expand(left = 0.25f, top = 0.1f, right = 0.5f, bottom = 0f)
        val l = e.layout(400, 300)
        assertEquals(Expand.Layout(700, 330, 100, 30, 400, 300), l)
        assertEquals(Geometry2D.Size(700, 330), e.grow(Geometry2D.Size(400, 300)))
        val inner = e.inner
        assertEquals(0.25f / 1.75f, inner.left, 1e-6f)
        assertEquals(1.25f / 1.75f, inner.right, 1e-6f)
    }

    @Test
    fun draggingTheCanvasEdgesGrowsAndSlides() {
        val e = Expand(left = 0.5f, right = 0.5f)
        // The right edge pulled out by a tenth of the canvas (two picture widths wide) adds a fifth of the picture.
        assertEquals(0.7f, e.dragged(7, 0.1f, 0f).right, 1e-5f)
        // Pushed past the picture it stops at zero, never negative.
        assertEquals(0f, e.dragged(6, 0.9f, 0f).left)
        // A drag inside slides the picture: one side gives what the other gains.
        val slid = e.dragged(8, 0.1f, 0.3f)
        assertEquals(0.7f, slid.left, 1e-5f); assertEquals(0.3f, slid.right, 1e-5f)
        assertEquals(0f, slid.top); assertEquals(0f, slid.bottom)
        assertEquals(Expand.MAX, Expand().dragged(4, 0f, -5f).top)
    }

    @Test
    fun aspectPresetsReachTheirAspect() {
        for ((w, h) in listOf(400 to 300, 300 to 400, 1000 to 1000)) {
            for (aspect in listOf(1f, 4f / 5f, 9f / 16f, 16f / 9f)) {
                val e = Expand.toAspect(w, h, aspect)
                val l = e.layout(w, h)
                assertEquals("$w x $h to $aspect", aspect.toDouble(), l.width.toDouble() / l.height, 0.01)
                assertTrue(e.left == e.right && e.top == e.bottom)
            }
        }
        assertTrue(Expand.toAspect(500, 500, 1f).isIdentity)
    }

    @Test
    fun recipesWithoutTheFieldReadAsOff() {
        val r = RecipeJson.read("""{"schema":2,"tone":{"exposure":0.5}}""")
        assertTrue(r.expand.isIdentity)
        val e = Expand(0.1f, 0.2f, 0.3f, 0.4f, ExpandFill.BLUR, 7)
        assertEquals(e, RecipeJson.read(RecipeJson.write(Recipe(expand = e))).expand)
        assertTrue(!Recipe(expand = Expand(left = 2f)).valid)
    }

    @Test
    fun theFlatFillsAreFlatAndThePictureIsUntouched() {
        val w = 40; val h = 30
        val px = picture(w, h) { x, y -> x * 5 + y }
        for ((fill, colour) in listOf(ExpandFill.WHITE to 0xFFFFFFFF.toInt(), ExpandFill.BLACK to 0xFF000000.toInt())) {
            val e = Expand(0.5f, 0.5f, 0.5f, 0.5f, fill)
            val l = e.layout(w, h)
            val out = Expansion.apply(px, w, h, e)
            assertEquals(colour, out[0]); assertEquals(colour, out[out.size - 1])
            for (y in 0 until h) for (x in 0 until w) assertEquals(px[y * w + x], out[(y + l.y) * l.width + x + l.x])
        }
        for (fill in listOf(ExpandFill.SMART, ExpandFill.BLUR)) {
            val e = Expand(0.3f, 0f, 0.2f, 0.4f, fill)
            val l = e.layout(w, h)
            val out = Expansion.apply(px, w, h, e)
            for (y in 0 until h) for (x in 0 until w) assertEquals(px[y * w + x], out[(y + l.y) * l.width + x + l.x])
        }
    }

    @Test
    fun smartFillContinuesAGradientAlongTheEdge() {
        // A sky: dark at the top, light at the bottom, a little grain. Grown to the right, each row keeps its own brightness.
        val w = 240; val h = 200
        var s = 1L
        val px = picture(w, h) { _, y -> s = s * 6364136223846793005L + 1; 40 + y + ((s ushr 40) % 7).toInt() - 3 }
        val e = Expand(right = 0.6f)
        val l = e.layout(w, h)
        val out = Expansion.apply(px, w, h, e)
        var worst = 0.0
        for (y in 0 until h step 10) {
            var sum = 0.0
            for (x in w until l.width) sum += grey(out[y * l.width + x])
            val mean = sum / (l.width - w)
            worst = maxOf(worst, abs(mean - (40 + y)))
        }
        assertTrue("rows drifted by $worst", worst < 8.0)
        // And grown upwards, the fill stays near the top row's brightness rather than mirroring back to the bottom's.
        val up = Expand(top = 0.5f)
        val lu = up.layout(w, h)
        val o2 = Expansion.apply(px, w, h, up)
        var top = 0.0
        for (x in 0 until w) top += grey(o2[(lu.y / 2) * lu.width + x])
        assertTrue("upward fill ${top / w}", abs(top / w - 40) < 25)
    }

    @Test
    fun smartFillContinuesATexture() {
        // Stripes with a period of 8 pixels on a mid grey: the fill should carry stripes, not a smear.
        val w = 192; val h = 192
        val px = picture(w, h) { x, _ -> if ((x / 4) % 2 == 0) 80 else 170 }
        val e = Expand(top = 0.4f, bottom = 0.4f)
        val l = e.layout(w, h)
        val out = Expansion.apply(px, w, h, e)
        val row = l.y / 2
        val values = DoubleArray(l.width) { grey(out[row * l.width + it]) }
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        assertTrue("fill mean $mean", abs(mean - 125) < 20)
        assertTrue("fill contrast $sd of 45", sd > 25)
        // The period survives: values 8 apart agree, values 4 apart disagree.
        var same = 0.0; var opposite = 0.0
        for (x in 0 until l.width - 8) { same += abs(values[x] - values[x + 8]); opposite += abs(values[x] - values[x + 4]) }
        assertTrue("period lost: $same vs $opposite", same < opposite * 0.5)
    }

    @Test
    fun aTinyPictureStillExpands() {
        for ((w, h) in listOf(1 to 1, 3 to 9, 12 to 5)) {
            val px = picture(w, h) { x, y -> x * 20 + y * 10 }
            val e = Expand(1f, 1f, 1f, 1f)
            val out = Expansion.apply(px, w, h, e)
            assertEquals(e.layout(w, h).width * e.layout(w, h).height, out.size)
        }
    }

    @Test
    fun smartFillIsDeterministicAndTheSeedMatters() {
        val w = 120; val h = 90
        var s = 7L
        val px = picture(w, h) { x, y -> s = s * 6364136223846793005L + 1; (x + y) / 2 + ((s ushr 40) % 60).toInt() }
        val e = Expand(0.3f, 0.3f, 0.3f, 0.3f)
        val a = Expansion.apply(px, w, h, e)
        val b = Expansion.apply(px, w, h, e)
        assertArrayEquals(a, b)
        val c = Expansion.apply(px, w, h, e.copy(seed = 1))
        assertTrue(!a.contentEquals(c))
    }

    @Test
    fun theSamePlanAtTwiceTheSizeLooksTheSame() {
        // A picture and the same picture at twice the size: the export of a preview.
        val w = 200; val h = 150
        var s = 3L
        val small = picture(w, h) { x, y -> s = s * 6364136223846793005L + 1; 60 + x / 3 + y / 4 + ((s ushr 40) % 30).toInt() }
        val big = IntArray(4 * w * h) { i -> val x = i % (2 * w); val y = i / (2 * w); small[(y / 2) * w + x / 2] }
        for (fill in listOf(ExpandFill.SMART, ExpandFill.BLUR)) {
            val e = Expand(0.25f, 0.2f, 0.35f, 0.1f, fill)
            val plan = Expansion.plan(small, w, h, e)
            val a = Expansion.apply(small, w, h, e, plan)
            val b = Expansion.apply(big, 2 * w, 2 * h, e, plan)
            val la = e.layout(w, h); val lb = e.layout(2 * w, 2 * h)
            assertEquals(la.width * 2, lb.width); assertEquals(la.height * 2, lb.height)
            var diff = 0.0; var n = 0
            for (y in 0 until la.height) for (x in 0 until la.width) {
                if (la.inside(x, y)) continue
                val down = (grey(b[(2 * y) * lb.width + 2 * x]) + grey(b[(2 * y) * lb.width + 2 * x + 1]) + grey(b[(2 * y + 1) * lb.width + 2 * x]) + grey(b[(2 * y + 1) * lb.width + 2 * x + 1])) / 4
                diff += abs(down - grey(a[y * la.width + x])); n++
            }
            assertTrue("$fill differs by ${diff / n} on average", diff / n < 6.0)
        }
    }

    @Test
    fun aPictureOfThingsSettlesFarOutInsteadOfRepeatingThem() {
        // A calm ground with a few hard-edged objects: far from the edge the fill must not tile them.
        val w = 192; val h = 192
        val px = picture(w, h) { x, y -> if ((x / 24 + y / 24) % 5 == 0 && (x % 24) in 4..16 && (y % 24) in 4..16) 230 else 70 + (x + y) % 6 }
        val e = Expand(right = 1.0f)
        val l = e.layout(w, h)
        val out = Expansion.apply(px, w, h, e)
        fun spread(x0: Int, x1: Int): Double {
            val v = ArrayList<Double>()
            for (y in 0 until l.height) for (x in x0 until x1) v += grey(out[y * l.width + x])
            val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        }
        // No copy of a bright object anywhere in the far third, and the ground there is calm.
        var brightest = 0.0
        for (y in 0 until l.height) for (x in l.width - l.width / 3 until l.width) brightest = maxOf(brightest, grey(out[y * l.width + x]))
        assertTrue("an object was copied far out: $brightest", brightest < 160)
        assertTrue("far ground not calm: ${spread(l.width - 30, l.width)}", spread(l.width - 30, l.width) < 10)
    }
}
