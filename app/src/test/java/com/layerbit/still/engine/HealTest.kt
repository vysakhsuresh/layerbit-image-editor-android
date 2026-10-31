package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class HealTest {

    private fun grey(v: Double): Int { val c = v.toInt().coerceIn(0, 255); return Argb.pack(255, c, c, c) }

    /** A picture from a function of canvas fractions, so the same scene can be drawn at any size. */
    private fun picture(w: Int, h: Int, f: (Double, Double) -> Int): IntArray = IntArray(w * h) { i ->
        f((i % w + 0.5) / w, (i / w + 0.5) / h)
    }

    /** Draws a dark blob of [radius] (fraction of the shorter edge) at ([cx], [cy]). */
    private fun blemish(p: IntArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float, colour: Int = Argb.pack(255, 20, 10, 10)) {
        val r = radius * min(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + 0.5f - cx * w; val dy = y + 0.5f - cy * h
            if (dx * dx + dy * dy <= r * r) p[y * w + x] = colour
        }
    }

    /** Mean and worst per-channel error between [a] and [b] within [radius] pixels of ([cx], [cy]). */
    private fun error(a: IntArray, b: IntArray, w: Int, cx: Float, cy: Float, radius: Float): Pair<Double, Int> {
        var sum = 0L; var n = 0; var worst = 0
        val h = a.size / w
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            if (dx * dx + dy * dy > radius * radius) continue
            val i = y * w + x
            for (e in intArrayOf(Argb.red(a[i]) - Argb.red(b[i]), Argb.green(a[i]) - Argb.green(b[i]), Argb.blue(a[i]) - Argb.blue(b[i]))) {
                sum += abs(e); worst = max(worst, abs(e))
            }
            n += 3
        }
        return sum.toDouble() / n to worst
    }

    private fun heal(p: IntArray, w: Int, h: Int, points: List<Pt>, width: Float, source: Pt): IntArray =
        p.copyOf().also { Heal.apply(it, w, h, 0, 0, w, h, points, width, source) }

    @Test
    fun aDotOnASmoothGradientDisappears() {
        val w = 200; val h = 150
        val clean = picture(w, h) { u, v -> Argb.pack(255, (40 + 160 * u).toInt(), (60 + 120 * v).toInt(), (90 + 50 * u * v).toInt()) }
        val spotted = clean.copyOf()
        blemish(spotted, w, h, 0.5f, 0.5f, 0.03f)
        val stroke = listOf(Pt(0.49f, 0.5f), Pt(0.51f, 0.5f))
        val source = Heal.findSource(spotted, w, h, stroke, 0.1f)
        assertNotNull(source)
        val out = heal(spotted, w, h, stroke, 0.1f, source!!)
        val before = error(spotted, clean, w, 100f, 75f, 12f)
        val (mean, worst) = error(out, clean, w, 100f, 75f, 12f)
        assertTrue("the dot was there: ${before.first}", before.first > 8)
        println("heal: gradient mean $mean worst $worst")
        assertTrue("mean error $mean", mean < 1.5)
        assertTrue("worst error $worst", worst <= 4)
        // Outside the feathered reach nothing changes.
        assertEquals(spotted[10 * w + 10], out[10 * w + 10])
    }

    /** Diagonal stripes on a brightness ramp: the patch must line up with the stripes, the ramp is the correction's job. */
    private fun stripes(w: Int, h: Int, period: Double = 12.0) = picture(w, h) { u, v ->
        val x = u * w; val y = v * h
        grey(70 + 50 * u + 55 * sin(2 * PI * (x + y) / period))
    }

    @Test
    fun onStripesTheHealContinuesTheStripes() {
        val w = 220; val h = 160
        val clean = stripes(w, h)
        val spotted = clean.copyOf()
        blemish(spotted, w, h, 0.5f, 0.5f, 0.035f, Argb.pack(255, 240, 30, 30))
        val stroke = listOf(Pt(0.5f, 0.5f), Pt(0.502f, 0.5f))
        val source = Heal.findSource(spotted, w, h, stroke, 0.1f)!!
        val out = heal(spotted, w, h, stroke, 0.1f, source)
        val (mean, worst) = error(out, clean, w, 110f, 80f, 14f)
        assertTrue("mean error $mean", mean < 3.0)
        assertTrue("worst error $worst", worst < 16)
        // The ring where the patch blends in: no seam either.
        val (ring, _) = error(out, clean, w, 110f, 80f, 24f)
        println("heal: stripes mean $mean worst $worst ring $ring")
        assertTrue("ring error $ring", ring < 3.0)
    }

    @Test
    fun theSearchPrefersAPatchThatMatchesTheBorder() {
        // Stripes on the left, flat grey of the same mean on the right; a spot on the stripes
        // close to the join must be healed from stripes, not from the nearer flat grey.
        val w = 240; val h = 160
        val clean = picture(w, h) { u, v -> if (u < 0.5) grey(128 + 60 * sin(2 * PI * (u * w) / 10)) else grey(128.0) }
        val spotted = clean.copyOf()
        blemish(spotted, w, h, 0.4f, 0.5f, 0.03f)
        val stroke = listOf(Pt(0.4f, 0.5f))
        val source = Heal.findSource(spotted, w, h, stroke, 0.08f)!!
        val s = Heal.shape(stroke, 0.08f, w, h)
        val patchRight = source.x * w + s.outer
        assertTrue("patch reaches x=$patchRight, past the stripes", patchRight < w / 2f)
        val out = heal(spotted, w, h, stroke, 0.08f, source)
        val (mean, _) = error(out, clean, w, 0.4f * w, 80f, 8f)
        assertTrue("mean error $mean", mean < 3.0)
    }

    @Test
    fun theSameInputHealsTheSameWay() {
        val w = 160; val h = 120
        val spotted = stripes(w, h, 9.0)
        blemish(spotted, w, h, 0.3f, 0.6f, 0.04f)
        val stroke = listOf(Pt(0.28f, 0.6f), Pt(0.3f, 0.61f), Pt(0.32f, 0.6f))
        val a = Heal.findSource(spotted, w, h, stroke, 0.09f)
        val b = Heal.findSource(spotted.copyOf(), w, h, stroke, 0.09f)
        assertEquals(a, b)
        assertArrayEquals(heal(spotted, w, h, stroke, 0.09f, a!!), heal(spotted, w, h, stroke, 0.09f, b!!))
    }

    @Test
    fun aHealWorksInAWindowAsOnTheWholePicture() {
        val w = 180; val h = 140
        val spotted = stripes(w, h)
        blemish(spotted, w, h, 0.6f, 0.4f, 0.03f)
        val stroke = listOf(Pt(0.6f, 0.4f))
        val source = Heal.findSource(spotted, w, h, stroke, 0.09f)!!
        val whole = heal(spotted, w, h, stroke, 0.09f, source)
        val win = Heal.window(stroke, 0.09f, source, w, h)!!
        val ww = win[2] - win[0]; val wh = win[3] - win[1]
        val part = IntArray(ww * wh) { spotted[(win[1] + it / ww) * w + win[0] + it % ww] }
        Heal.apply(part, ww, wh, win[0], win[1], w, h, stroke, 0.09f, source)
        val merged = spotted.copyOf()
        for (i in part.indices) merged[(win[1] + i / ww) * w + win[0] + i % ww] = part[i]
        assertArrayEquals(whole, merged)
    }

    @Test
    fun theSameMarkHealsTheSameAtTwoSizes() {
        // A scene in canvas fractions, drawn at 200 x 150 and 400 x 300. The source is chosen on
        // the small one, like the preview, and reused on the large one, like the export.
        val scene = { w: Int, h: Int ->
            picture(w, h) { u, v -> Argb.pack(255, (90 + 60 * sin(2 * PI * (u * 7 + v * 4))).toInt(), (100 + 70 * u).toInt(), (120 + 50 * sin(2 * PI * v * 3)).toInt()) }
                .also { blemish(it, w, h, 0.45f, 0.55f, 0.035f) }
        }
        val stroke = listOf(Pt(0.44f, 0.55f), Pt(0.46f, 0.56f))
        val width = 0.1f
        val small = scene(200, 150)
        val source = Heal.findSource(small, 200, 150, stroke, width)!!
        val healedSmall = heal(small, 200, 150, stroke, width, source)
        val large = scene(400, 300)
        val healedLarge = heal(large, 400, 300, stroke, width, source)
        // Down to the small size by 2 x 2 averages.
        val shrunk = IntArray(200 * 150) { i ->
            val x = (i % 200) * 2; val y = (i / 200) * 2
            val c = intArrayOf(healedLarge[y * 400 + x], healedLarge[y * 400 + x + 1], healedLarge[(y + 1) * 400 + x], healedLarge[(y + 1) * 400 + x + 1])
            Argb.pack(255, c.sumOf { Argb.red(it) } / 4, c.sumOf { Argb.green(it) } / 4, c.sumOf { Argb.blue(it) } / 4)
        }
        val (mean, _) = error(healedSmall, shrunk, 200, 0.45f * 200, 0.55f * 150, 18f)
        println("heal: two sizes differ by $mean")
        assertTrue("mean difference between sizes $mean", mean < 4.0)
        // And both actually healed: no dark blob left.
        val centre = healedSmall[(0.55f * 150).toInt() * 200 + (0.45f * 200).toInt()]
        assertTrue("healed centre ${Argb.luma(centre)}", Argb.luma(centre) > 60)
    }

    @Test
    fun aHealMarkNeedsItsSourceAndRoundTrips() {
        val stroke = listOf(Pt(0.4f, 0.5f), Pt(0.42f, 0.5f))
        assertTrue(!Mark(MarkKind.HEAL, stroke).valid)
        val m = Mark(MarkKind.HEAL, stroke, width = 0.05f, source = Pt(0.3f, 0.5f))
        assertTrue(m.valid)
        val r = Recipe(marks = listOf(m))
        assertEquals(r, RecipeJson.read(RecipeJson.write(r)))
    }
}
