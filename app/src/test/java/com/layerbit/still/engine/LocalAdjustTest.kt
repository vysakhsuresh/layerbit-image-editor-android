package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class LocalAdjustTest {

    private fun grey(v: Int) = Argb.pack(255, v, v, v)

    /** Mean of a mask over a box of fractions. */
    private fun mean(g: LocalMasks.Grid, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        var s = 0.0; var n = 0
        for (y in (y0 * g.height).toInt() until (y1 * g.height).toInt()) for (x in (x0 * g.width).toInt() until (x1 * g.width).toInt()) { s += g.values[y * g.width + x]; n++ }
        return (s / n).toFloat()
    }

    /** A big grid averaged down by [k] in each direction, to compare with a small one. */
    private fun shrink(g: LocalMasks.Grid, k: Int): FloatArray {
        val w = g.width / k; val h = g.height / k
        return FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            var s = 0f
            for (dy in 0 until k) for (dx in 0 until k) s += g.values[(y * k + dy) * g.width + x * k + dx]
            s / (k * k)
        }
    }

    private fun meanAbs(a: FloatArray, b: FloatArray): Float { var s = 0.0; for (i in a.indices) s += abs(a[i] - b[i]); return (s / a.size).toFloat() }

    private fun analysisOf(px: IntArray, w: Int, h: Int, subject: IntArray = IntArray(w * h)) = LocalMasks.Analysis(px, w, h) { subject }

    @Test
    fun aBrushStrokeCoversItsPathAndAnEraseTakesItBack() {
        val stroke = BrushStroke(listOf(Pt(0.1f, 0.5f), Pt(0.9f, 0.5f)), width = 0.2f, softness = 0.5f)
        val g = LocalMasks.render(LocalMask(MaskKind.BRUSH, strokes = listOf(stroke)), 200, 100, null)
        assertEquals(1f, g.at(100, 50, 200, 100), 1e-4f)
        assertEquals(0f, g.at(100, 5, 200, 100), 1e-4f)
        // Soft: between the hard core and the edge the brush fades.
        val edge = g.at(100, 58, 200, 100)
        assertTrue("soft edge $edge", edge in 0.05f..0.95f)
        val erased = LocalMasks.render(LocalMask(MaskKind.BRUSH, strokes = listOf(stroke, BrushStroke(listOf(Pt(0.5f, 0.5f)), width = 0.2f, softness = 0f, erase = true))), 200, 100, null)
        assertEquals(0f, erased.at(100, 50, 200, 100), 1e-4f)
        assertEquals(1f, erased.at(30, 50, 200, 100), 1e-4f)
        // Painting twice over the same place never goes past whole.
        val twice = LocalMasks.render(LocalMask(MaskKind.BRUSH, strokes = listOf(stroke, stroke)), 200, 100, null)
        assertTrue(twice.values.all { it in 0f..1f })
    }

    @Test
    fun aLinearGradientIsWholeAtItsStartAndGoneAtItsEnd() {
        val g = LocalMasks.render(LocalMask(MaskKind.LINEAR, a = Pt(0.5f, 0.2f), b = Pt(0.5f, 0.6f)), 100, 100, null)
        assertEquals(1f, mean(g, 0f, 0f, 1f, 0.15f), 1e-4f)
        assertEquals(0f, mean(g, 0f, 0.65f, 1f, 1f), 1e-4f)
        var last = 2f
        for (y in 0 until 100) { val v = g.at(50, y, 100, 100); assertTrue(v <= last + 1e-6f); last = v }
        // Across the gradient's direction nothing changes.
        assertEquals(g.at(3, 40, 100, 100), g.at(96, 40, 100, 100), 1e-5f)
    }

    @Test
    fun aRadialMaskIsAnEllipseThatTurnsAndInverts() {
        val wide = LocalMask(MaskKind.RADIAL, a = Pt(0.5f, 0.5f), radiusX = 0.4f, radiusY = 0.1f, feather = 0.2f)
        val g = LocalMasks.render(wide, 200, 200, null)
        assertEquals(1f, g.at(100, 100, 200, 200), 1e-4f)
        assertTrue(g.at(165, 100, 200, 200) > 0.9f)
        assertEquals(0f, g.at(100, 165, 200, 200), 1e-4f)
        val turned = LocalMasks.render(wide.copy(angle = 90f), 200, 200, null)
        assertEquals(0f, turned.at(165, 100, 200, 200), 1e-4f)
        assertTrue(turned.at(100, 165, 200, 200) > 0.9f)
        val inverted = LocalMasks.render(wide.copy(invert = true), 200, 200, null)
        for (i in g.values.indices) assertEquals(1f - g.values[i], inverted.values[i], 1e-5f)
        // A radius is a fraction of the shorter edge: a circle stays a circle on a wide picture.
        val circle = LocalMasks.render(LocalMask(MaskKind.RADIAL, radiusX = 0.25f, radiusY = 0.25f, feather = 0f), 400, 200, null)
        assertTrue(circle.at(200 + 45, 100, 400, 200) > 0.5f && circle.at(200, 100 + 45, 400, 200) > 0.5f)
        assertEquals(0f, circle.at(200 + 60, 100, 400, 200), 1e-4f)
    }

    @Test
    fun aControlPointTakesTheColourItSitsOnAndStopsAtAnotherColour() {
        val w = 200; val h = 100
        val rng = Random(3)
        // Red on the left, blue on the right, both a little noisy.
        val px = IntArray(w * h) { i -> val x = i % w; val n = rng.nextInt(10); if (x < 100) Argb.pack(255, 200 + n, 40 + n, 40) else Argb.pack(255, 40, 60 + n, 200 + n) }
        val m = LocalMasks.render(LocalMask(MaskKind.POINT, a = Pt(0.45f, 0.5f), radiusX = 0.6f, range = 0.3f), w, h, analysisOf(px, w, h))
        assertTrue("on the red near the point", m.at(85, 50, w, h) > 0.7f)
        assertTrue("on the blue across the edge, within reach", m.at(110, 50, w, h) < 0.05f)
        assertTrue("on the red, out of reach", m.at(5, 50, w, h) < 0.3f)
        // A wider range takes in more colour.
        val wider = LocalMasks.render(LocalMask(MaskKind.POINT, a = Pt(0.45f, 0.5f), radiusX = 0.6f, range = 1f), w, h, analysisOf(px, w, h))
        assertTrue(wider.coverage >= m.coverage)
    }

    /** A blue sky over textured green ground, with a smooth blue box (a shirt) standing on the ground. */
    private fun landscape(w: Int, h: Int): IntArray {
        val rng = Random(5)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                y < h * 2 / 5 -> Argb.pack(255, 90 + y / 4, 150 + y / 4, 235)
                x in w / 3 until w / 2 && y in h * 3 / 4 until h - h / 20 -> Argb.pack(255, 60, 110, 220)
                else -> { val n = rng.nextInt(90); Argb.pack(255, 30 + n / 2, 90 + n, 30 + n / 3) }
            }
        }
    }

    @Test
    fun theSkyIsFoundAtTheTopAndTheBlueShirtBelowIsNot() {
        val w = 300; val h = 200
        val px = landscape(w, h)
        val m = LocalMasks.render(LocalMask(MaskKind.SKY), w, h, analysisOf(px, w, h))
        assertTrue("sky ${mean(m, 0f, 0f, 1f, 0.3f)}", mean(m, 0f, 0f, 1f, 0.3f) > 0.85f)
        assertTrue("ground ${mean(m, 0f, 0.55f, 1f, 1f)}", mean(m, 0f, 0.55f, 1f, 1f) < 0.05f)
        assertTrue("shirt", mean(m, 0.36f, 0.8f, 0.48f, 0.94f) < 0.05f)
        // No sky at the top: nothing is called sky.
        val upside = IntArray(w * h) { px[(h - 1 - it / w) * w + it % w] }
        assertTrue(LocalMasks.render(LocalMask(MaskKind.SKY), w, h, analysisOf(upside, w, h)).coverage < 0.02f)
    }

    @Test
    fun subjectAndBackgroundAreTheCutoutsMaskAndItsOpposite() {
        val w = 40; val h = 30
        val subject = IntArray(w * h) { i -> if (i % w in 10 until 30) 255 else 0 }
        val an = analysisOf(IntArray(w * h) { grey(128) }, w, h, subject)
        val s = LocalMasks.render(LocalMask(MaskKind.SUBJECT), 80, 60, an)
        val b = LocalMasks.render(LocalMask(MaskKind.BACKGROUND), 80, 60, an)
        assertEquals(1f, s.at(40, 30, 80, 60), 1e-4f); assertEquals(0f, s.at(2, 30, 80, 60), 1e-4f)
        for (i in s.values.indices) assertEquals(1f, s.values[i] + b.values[i], 1e-5f)
    }

    @Test
    fun aMaskDrawnSmallIsTheSameMaskDrawnLarge() {
        // The preview is a quarter the size of the export in each direction; the masks agree once the export's is shrunk.
        val masks = listOf(
            LocalMask(MaskKind.BRUSH, strokes = listOf(BrushStroke(listOf(Pt(0.2f, 0.3f), Pt(0.5f, 0.6f), Pt(0.8f, 0.4f)), width = 0.1f, softness = 0.4f), BrushStroke(listOf(Pt(0.5f, 0.6f)), width = 0.05f, erase = true))),
            LocalMask(MaskKind.LINEAR, a = Pt(0.1f, 0.2f), b = Pt(0.7f, 0.8f)),
            LocalMask(MaskKind.RADIAL, a = Pt(0.4f, 0.5f), radiusX = 0.3f, radiusY = 0.2f, angle = 30f, feather = 0.5f, invert = true)
        )
        for (mask in masks) {
            val small = LocalMasks.render(mask, 150, 100, null)
            val large = LocalMasks.render(mask, 600, 400, null)
            val d = meanAbs(small.values, shrink(large, 4))
            assertTrue("${mask.kind} differs by $d", d < 0.01f)
        }
        // The kinds read from the picture are decided at the analysis size: the same at any output size.
        val px = landscape(120, 80)
        val an = analysisOf(px, 120, 80)
        for (mask in listOf(LocalMask(MaskKind.SKY), LocalMask(MaskKind.POINT, a = Pt(0.5f, 0.2f), radiusX = 0.4f))) {
            val small = LocalMasks.render(mask, 150, 100, an)
            val large = LocalMasks.render(mask, 600, 400, an)
            val d = meanAbs(small.values, shrink(large, 4))
            assertTrue("${mask.kind} differs by $d", d < 0.02f)
        }
    }

    @Test
    fun aDodgeBrightensInsideItsMaskAndLeavesTheRestAlone() {
        val w = 120; val h = 80
        val px = IntArray(w * h) { grey(100) }
        val local = LocalAdjust(LocalMask(MaskKind.RADIAL, radiusX = 0.3f, radiusY = 0.3f, feather = 0.3f)).preset(LocalPreset.DODGE)
        assertTrue(local.exposure > 0f)
        val out = px.copyOf()
        LocalAdjustments.apply(out, w, h, listOf(local), { LocalMasks.render(it, w, h, null) })
        assertTrue(Argb.red(out[40 * w + 60]) > 115)
        assertEquals(px[2], out[2])
        // Hidden or empty adjustments change nothing.
        val same = px.copyOf()
        LocalAdjustments.apply(same, w, h, listOf(local.copy(visible = false), LocalAdjust(LocalMask(MaskKind.RADIAL))), { LocalMasks.render(it, w, h, null) })
        assertTrue(same.contentEquals(px))
        // A burn darkens; inverted, it darkens the corners instead.
        val burn = local.preset(LocalPreset.BURN).let { it.copy(mask = it.mask.copy(invert = true)) }
        val dark = px.copyOf()
        LocalAdjustments.apply(dark, w, h, listOf(burn), { LocalMasks.render(it, w, h, null) })
        assertTrue(Argb.red(dark[2]) < 90)
        assertEquals(px[40 * w + 60], dark[40 * w + 60])
    }

    @Test
    fun negativeSharpnessBlursAndPopAddsContrastInsideTheMask() {
        val w = 100; val h = 100
        val px = IntArray(w * h) { i -> if ((i % w / 4 + i / w / 4) % 2 == 0) grey(60) else grey(190) }
        fun spread(a: IntArray, x0: Int, x1: Int): Int { var lo = 255; var hi = 0; for (y in 40 until 60) for (x in x0 until x1) { val v = Argb.red(a[y * w + x]); lo = minOf(lo, v); hi = maxOf(hi, v) }; return hi - lo }
        val mask = LocalMask(MaskKind.LINEAR, a = Pt(0f, 0.5f), b = Pt(0.01f, 0.5f), invert = true) // the right side, nearly hard
        val soft = px.copyOf()
        LocalAdjustments.apply(soft, w, h, listOf(LocalAdjust(mask, sharpness = -1f)), { LocalMasks.render(it, w, h, null) })
        assertTrue(spread(soft, 60, 90) < spread(px, 60, 90))
        val pop = px.copyOf()
        LocalAdjustments.apply(pop, w, h, listOf(LocalAdjust(mask).preset(LocalPreset.POP)), { LocalMasks.render(it, w, h, null) })
        assertTrue(spread(pop, 60, 90) > spread(px, 60, 90))
    }

    @Test
    fun theAdjustmentLooksTheSameAtPreviewAndExportSize() {
        fun scene(w: Int, h: Int) = IntArray(w * h) { i -> val x = i % w; val y = i / w; Argb.pack(255, x * 255 / w, y * 255 / h, if (x > w / 2) 200 else 60) }
        val locals = listOf(
            LocalAdjust(LocalMask(MaskKind.RADIAL, a = Pt(0.4f, 0.5f), radiusX = 0.3f, radiusY = 0.25f), exposure = 0.6f, clarity = 0.5f, saturation = 0.3f),
            LocalAdjust(LocalMask(MaskKind.LINEAR, a = Pt(0.5f, 0f), b = Pt(0.5f, 0.5f)), temperature = -0.4f, sharpness = -0.6f)
        )
        val sw = 150; val sh = 100; val k = 4
        val small = scene(sw, sh); LocalAdjustments.apply(small, sw, sh, locals, { LocalMasks.render(it, sw, sh, null) })
        val lw = sw * k; val lh = sh * k
        val large = scene(lw, lh); LocalAdjustments.apply(large, lw, lh, locals, { LocalMasks.render(it, lw, lh, null) })
        var diff = 0.0
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0
            for (dy in 0 until k) for (dx in 0 until k) s += Argb.green(large[(y * k + dy) * lw + x * k + dx])
            diff += abs(s / (k * k) - Argb.green(small[y * sw + x]))
        }
        val mean = diff / (sw * sh)
        assertTrue("mean difference $mean", mean < 4.0)
    }

    @Test
    fun valuesOutsideTheSlidersAreRefused() {
        val ok = LocalAdjust(LocalMask.create(MaskKind.RADIAL), exposure = 1f)
        assertTrue(ok.valid)
        assertFalse(ok.copy(exposure = 3f).valid)
        assertFalse(ok.copy(sharpness = -1.5f).valid)
        assertFalse(ok.copy(mask = ok.mask.copy(radiusX = 0f)).valid)
        assertFalse(ok.copy(mask = ok.mask.copy(a = Pt(Float.NaN, 0f))).valid)
        assertFalse(LocalMask(MaskKind.BRUSH, strokes = listOf(BrushStroke(emptyList()))).valid)
        assertTrue(Recipe(locals = listOf(ok)).valid)
        assertFalse(Recipe(locals = List(LocalAdjust.MAX + 1) { ok }).valid)
        // A preset keeps the picture's own masks out: they belong to this picture.
        assertTrue(Recipe(locals = listOf(ok)).asPreset().locals.isEmpty())
        for (kind in MaskKind.entries) assertTrue(LocalMask.create(kind).valid)
    }
}
