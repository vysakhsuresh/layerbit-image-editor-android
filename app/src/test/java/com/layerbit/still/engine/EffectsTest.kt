package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class EffectsTest {

    /** A scene with gradients, edges, a bright window, a skin-coloured patch and a little noise. */
    private fun scene(w: Int, h: Int, seed: Long = 3): IntArray {
        val rng = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val fx = x.toFloat() / w; val fy = y.toFloat() / h
            var r = 40 + 150 * fx; var g = 60 + 120 * fy; var b = 140 - 80 * fx
            // Stripes of texture whose width follows the picture, so every size holds the same scene.
            if (((fx * 24).toInt() + (fy * 16).toInt()) % 2 == 0) { r -= 25; g -= 25; b -= 25 }
            if (fx in 0.6f..0.8f && fy in 0.1f..0.3f) { r = 248f; g = 246f; b = 240f }
            if (fx in 0.1f..0.3f && fy in 0.55f..0.85f) { r = 224f; g = 172f; b = 140f }
            val n = rng.nextInt(9) - 4
            Argb.pack(255, (r + n).toInt().coerceIn(0, 255), (g + n).toInt().coerceIn(0, 255), (b + n).toInt().coerceIn(0, 255))
        }
    }

    private fun luma(px: IntArray) = FloatArray(px.size) { Argb.luma(px[it]).toFloat() }

    /** Mean of the absolute difference between each pixel and its 5×5 neighbourhood mean: local contrast. */
    private fun localContrast(px: IntArray, w: Int, h: Int, x0: Int = 0, y0: Int = 0, x1: Int = w, y1: Int = h): Double {
        val l = luma(px)
        var sum = 0.0; var n = 0
        for (y in maxOf(2, y0) until minOf(h - 2, y1)) for (x in maxOf(2, x0) until minOf(w - 2, x1)) {
            var m = 0f
            for (dy in -2..2) for (dx in -2..2) m += l[(y + dy) * w + x + dx]
            sum += abs(l[y * w + x] - m / 25f); n++
        }
        return sum / n
    }

    private fun meanLuma(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var s = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { s += Argb.luma(px[y * w + x]); n++ }
        return s / n
    }

    private fun shrink(px: IntArray, w: Int, h: Int, f: Int): IntArray {
        val sw = w / f; val sh = h / f
        return IntArray(sw * sh) { i ->
            val sx = i % sw; val sy = i / sw
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until f) for (dx in 0 until f) {
                val c = px[(sy * f + dy) * w + sx * f + dx]
                r += Argb.red(c); g += Argb.green(c); b += Argb.blue(c)
            }
            val k = f * f
            Argb.pack(255, (r + k / 2) / k, (g + k / 2) / k, (b + k / 2) / k)
        }
    }

    private fun meanDiff(a: IntArray, b: IntArray): Double {
        var s = 0.0
        for (i in a.indices) s += abs(Argb.red(a[i]) - Argb.red(b[i])) + abs(Argb.green(a[i]) - Argb.green(b[i])) + abs(Argb.blue(a[i]) - Argb.blue(b[i]))
        return s / (a.size * 3)
    }

    private fun softened(px: IntArray, w: Int, h: Int) = Spatial.boxBlur(px, w, h, 2, passes = 2)

    @Test
    fun strengthZeroIsIdentityForEveryEffect() {
        val w = 120; val h = 90
        val src = scene(w, h)
        for (kind in EffectKind.entries) for (style in 0 until kind.styles) {
            val e = Effect(kind, style = style, strength = 0f)
            assertArrayEquals("$kind/$style", src, Effects.applyOne(src, w, h, e))
            assertArrayEquals(src, Effects.apply(src, w, h, listOf(e), kind.stage))
        }
    }

    @Test
    fun everyStyleChangesThePictureAndIsDeterministic() {
        val w = 160; val h = 120
        val src = scene(w, h)
        for (kind in EffectKind.entries) for (style in 0 until kind.styles) {
            val e = Effect(kind, style = style, strength = 0.8f, seed = 9)
            val a = Effects.applyOne(src, w, h, e)
            val b = Effects.applyOne(src, w, h, e)
            assertArrayEquals("$kind/$style deterministic", a, b)
            assertTrue("$kind/$style changes pixels", meanDiff(a, src) > 0.5)
            assertTrue(e.valid)
        }
    }

    @Test
    fun seedsChooseTheTexture() {
        val w = 160; val h = 120
        val src = scene(w, h)
        for (kind in EffectKind.entries.filter { it.seeded }) {
            val a = Effects.applyOne(src, w, h, Effect(kind, strength = 0.8f, seed = 1))
            val b = Effects.applyOne(src, w, h, Effect(kind, strength = 0.8f, seed = 2))
            assertTrue("$kind differs by seed", meanDiff(a, b) > 0.2)
            assertArrayEquals(a, Effects.applyOne(src, w, h, Effect(kind, strength = 0.8f, seed = 1)))
        }
    }

    @Test
    fun tonalContrastRaisesLocalContrastAndProtectsTheEnds() {
        val w = 200; val h = 150
        val src = scene(w, h)
        val before = localContrast(src, w, h)
        for (style in 0 until 4) {
            val out = Effects.applyOne(src, w, h, Effect(EffectKind.TONAL_CONTRAST, style = style, strength = 1f))
            val after = localContrast(out, w, h)
            assertTrue("style $style: $before -> $after", after > before * 1.08)
        }
        // A protected highlight barely moves; unprotected, it moves more.
        val x0 = (0.62f * w).toInt(); val x1 = (0.78f * w).toInt(); val y0 = (0.12f * h).toInt(); val y1 = (0.28f * h).toInt()
        val prot = Effects.applyOne(src, w, h, Effect(EffectKind.TONAL_CONTRAST, style = 2, strength = 1f, extra = 1f))
        val open = Effects.applyOne(src, w, h, Effect(EffectKind.TONAL_CONTRAST, style = 2, strength = 1f, extra = 0f))
        val base = meanLuma(src, w, x0, y0, x1, y1)
        assertTrue(abs(meanLuma(prot, w, x0, y0, x1, y1) - base) <= abs(meanLuma(open, w, x0, y0, x1, y1) - base) + 0.01)
    }

    @Test
    fun ambianceHdrAndDramaAddLocalContrast_negativeAmbianceFlattens() {
        val w = 200; val h = 150
        val src = scene(w, h)
        val before = localContrast(src, w, h)
        for (kind in listOf(EffectKind.AMBIANCE, EffectKind.HDR_SCAPE, EffectKind.DRAMA)) {
            val out = Effects.applyOne(src, w, h, Effect(kind, strength = 1f))
            assertTrue("$kind", localContrast(out, w, h) > before * 1.05)
        }
        val flat = Effects.applyOne(src, w, h, Effect(EffectKind.AMBIANCE, strength = -1f))
        assertTrue(localContrast(flat, w, h) < before)
    }

    @Test
    fun ambianceSaturationSparesSkin() {
        val w = 200; val h = 150
        val src = scene(w, h)
        val out = Effects.applyOne(src, w, h, Effect(EffectKind.AMBIANCE, style = 1, strength = 1f, extra = 1f))
        fun sat(px: IntArray, x0: Float, y0: Float, x1: Float, y1: Float): Double {
            var s = 0.0; var n = 0
            for (y in (y0 * h).toInt() until (y1 * h).toInt()) for (x in (x0 * w).toInt() until (x1 * w).toInt()) {
                val c = px[y * w + x]
                val mx = maxOf(Argb.red(c), Argb.green(c), Argb.blue(c)); val mn = minOf(Argb.red(c), Argb.green(c), Argb.blue(c))
                s += (mx - mn).toDouble() / maxOf(1, mx); n++
            }
            return s / n
        }
        assertTrue(Effects.skinWeight(Argb.pack(255, 224, 172, 140)) > 0.5f)
        assertTrue(Effects.skinWeight(Argb.pack(255, 60, 140, 200)) == 0f)
        val skinGain = sat(out, 0.15f, 0.6f, 0.25f, 0.8f) / sat(src, 0.15f, 0.6f, 0.25f, 0.8f)
        val skyGain = sat(out, 0.35f, 0.05f, 0.5f, 0.2f) / sat(src, 0.35f, 0.05f, 0.5f, 0.2f)
        assertTrue("skin $skinGain sky $skyGain", skinGain < skyGain)
    }

    @Test
    fun glowBrightensAroundHighlightsAndNeverDarkens() {
        val w = 200; val h = 150
        val src = scene(w, h)
        val out = Effects.applyOne(src, w, h, Effect(EffectKind.GLAMOUR_GLOW, strength = 1f))
        // Just outside the bright window, the glow has spread.
        val x0 = (0.82f * w).toInt(); val x1 = (0.86f * w).toInt(); val y0 = (0.12f * h).toInt(); val y1 = (0.28f * h).toInt()
        val near = meanLuma(out, w, x0, y0, x1, y1) - meanLuma(src, w, x0, y0, x1, y1)
        val far = meanLuma(out, w, 0, (0.9f * h).toInt(), (0.1f * w).toInt(), h) - meanLuma(src, w, 0, (0.9f * h).toInt(), (0.1f * w).toInt(), h)
        assertTrue("near $near far $far", near > 4 && near > far * 2)
        for (i in src.indices) assertTrue(Argb.luma(out[i]) >= Argb.luma(src[i]) - 3)
    }

    @Test
    fun lensBlurKeepsTheFocusBandSharpAndBlursFarRows() {
        val w = 240; val h = 180
        val src = scene(w, h)
        val e = Effect(EffectKind.LENS_BLUR, style = 0, strength = 1f, center = Pt(0.5f, 0.5f), size = 0.1f, extra = 0.2f)
        val out = Effects.applyOne(src, w, h, e)
        // The middle rows are untouched; the top and bottom have lost their texture.
        for (y in (h * 0.46f).toInt() until (h * 0.54f).toInt()) for (x in 0 until w) assertEquals(src[y * w + x], out[y * w + x])
        val farBefore = localContrast(src, w, h, y1 = (h * 0.15f).toInt())
        val farAfter = localContrast(out, w, h, y1 = (h * 0.15f).toInt())
        assertTrue("far $farBefore -> $farAfter", farAfter < farBefore * 0.5)
        assertEquals(0f, Effects.focusWeight(e, w / 2f, h / 2f, w, h), 0f)
        assertEquals(1f, Effects.focusWeight(e, w / 2f, 0f, w, h), 0f)
        // Round focus: the centre sharp, the corners blurred.
        val round = Effect(EffectKind.LENS_BLUR, style = 1, strength = 1f, size = 0.2f, aspect = 1.5f)
        assertEquals(0f, Effects.focusWeight(round, w / 2f, h / 2f, w, h), 0f)
        assertEquals(1f, Effects.focusWeight(round, 0f, 0f, w, h), 0f)
        // Turned a quarter, the band runs down the picture instead.
        val turned = e.copy(angle = 90f)
        assertEquals(0f, Effects.focusWeight(turned, w / 2f, 0f, w, h), 0f)
        assertEquals(1f, Effects.focusWeight(turned, 0f, h / 2f, w, h), 0f)
    }

    @Test
    fun lensBlurSpreadsBrightPointsIntoDiscs() {
        val w = 120; val h = 120
        val src = IntArray(w * h) { Argb.pack(255, 30, 30, 30) }
        src[60 * w + 60] = Argb.pack(255, 255, 255, 255)
        val out = Effects.applyOne(src, w, h, Effect(EffectKind.LENS_BLUR, style = 1, strength = 1f, size = 0.01f, extra = 0f))
        // A ring of pixels around the point is lit, and evenly: a disc, not a peak.
        val a = Argb.luma(out[60 * w + 62]); val b = Argb.luma(out[62 * w + 60])
        assertTrue("$a $b", a > 30 && b > 30 && abs(a - b) <= 1)
    }

    @Test
    fun grainyFilmAddsGrainWithoutMovingTheMean() {
        val w = 200; val h = 150
        val flat = IntArray(w * h) { Argb.pack(255, 128, 128, 128) }
        val out = Effects.applyOne(flat, w, h, Effect(EffectKind.GRAINY_FILM, style = 0, strength = 1f))
        val l = luma(out)
        val mean = l.average()
        val sd = sqrt(l.map { (it - mean) * (it - mean) }.average())
        assertTrue("sd $sd", sd > 4)
        assertTrue("mean $mean", abs(mean - 128) < 6)
        val mono = Effects.applyOne(scene(w, h), w, h, Effect(EffectKind.GRAINY_FILM, style = 3, strength = 1f))
        for (c in mono) assertTrue(abs(Argb.red(c) - Argb.blue(c)) <= 2)
    }

    @Test
    fun retroluxAndGrungeStayInsideThePictureAndChangeItsColour() {
        val w = 200; val h = 150
        val src = scene(w, h)
        val retro = Effects.applyOne(src, w, h, Effect(EffectKind.RETROLUX, strength = 1f, extra = 1f, seed = 4))
        assertTrue(meanDiff(retro, src) > 8)
        val grunge = Effects.applyOne(src, w, h, Effect(EffectKind.GRUNGE, style = 2, strength = 1f, seed = 4))
        assertTrue(meanDiff(grunge, src) > 8)
        for (c in grunge) assertEquals(255, Argb.alpha(c))
    }

    @Test
    fun previewPredictsExportForEveryEffect() {
        // The same effect on a picture and on a quarter-size copy agree once the large result is shrunk.
        val w = 800; val h = 600; val f = 4
        val big = scene(w, h)
        val small = shrink(big, w, h, f)
        val sw = w / f; val sh = h / f
        for (kind in EffectKind.entries) for (style in 0 until kind.styles) {
            val e = Effect(kind, style = style, strength = 0.8f, seed = 5, center = Pt(0.4f, 0.55f), size = 0.12f, angle = 20f)
            val outBig = shrink(Effects.applyOne(big, w, h, e), w, h, f)
            val outSmall = Effects.applyOne(small, sw, sh, e)
            val diff = meanDiff(softened(outBig, sw, sh), softened(outSmall, sw, sh))
            val base = meanDiff(softened(small, sw, sh), softened(outSmall, sw, sh))
            assertTrue("$kind/$style preview vs export $diff (effect moved $base)", diff < 5.0)
        }
        // A second pair of sizes, so agreement is not a coincidence of one ratio.
        val mid = shrink(big, w, h, 2)
        for (kind in EffectKind.entries) {
            val e = Effect(kind, strength = 0.8f, seed = 5)
            val a = shrink(Effects.applyOne(mid, w / 2, h / 2, e), w / 2, h / 2, 2)
            val b = Effects.applyOne(small, sw, sh, e)
            assertTrue("$kind 2x", meanDiff(softened(a, sw, sh), softened(b, sw, sh)) < 5.0)
        }
    }

    @Test
    fun fastEnoughOnAPreview() {
        val w = 1200; val h = 1000
        val src = scene(w, h)
        val report = StringBuilder()
        for (kind in EffectKind.entries) {
            val e = Effect(kind, strength = 0.8f)
            Effects.applyOne(src, w, h, e) // warm up the JIT
            var best = Long.MAX_VALUE
            repeat(5) {
                val t0 = System.nanoTime(); Effects.applyOne(src, w, h, e); best = minOf(best, System.nanoTime() - t0)
            }
            val ms = best / 1_000_000
            report.append("$kind ${ms}ms  ")
            // Only a guard against a lost order of magnitude: build machines run several Gradle
            // daemons at once. The target, met on a quiet machine, is under 150 ms; see the printout.
            assertTrue("$kind took ${ms}ms", ms < 3000)
        }
        println("effects on ${w}x$h: $report")
    }

    @Test
    fun recipesKeepTheirEffectsAndOldRecipesHaveNone() {
        val r = Recipe(effects = listOf(
            Effect(EffectKind.HDR_SCAPE, style = 3, strength = 0.7f, extra = -0.2f),
            Effect(EffectKind.LENS_BLUR, style = 1, strength = 0.6f, extra = 0.3f, center = Pt(0.3f, 0.6f), size = 0.2f, angle = 15f, aspect = 1.4f),
            Effect(EffectKind.GRUNGE, style = 1, strength = 0.5f, seed = 42)
        ))
        assertTrue(r.valid)
        assertEquals(r, RecipeJson.read(RecipeJson.write(r)))
        assertFalse(RecipeJson.write(Recipe()).contains("effects"))
        assertEquals(emptyList<Effect>(), RecipeJson.read("""{"schema":2,"tone":{"exposure":0.1}}""").effects)
        // An effect from a newer app is skipped; the rest of the recipe still opens.
        val future = """{"schema":2,"effects":[{"kind":"HOLOGRAM","strength":0.5},{"kind":"DRAMA","strength":0.4}]}"""
        assertEquals(listOf(Effect(EffectKind.DRAMA, strength = 0.4f)), RecipeJson.read(future).effects)
        assertFalse(Recipe(effects = listOf(Effect(EffectKind.DRAMA, style = 9))).valid)
        assertFalse(Recipe(effects = listOf(Effect(EffectKind.DRAMA, strength = 2f))).valid)
        assertFalse(Recipe(effects = List(Effect.MAX + 1) { Effect(EffectKind.DRAMA) }).valid)
    }

    @Test
    fun stagesRunOnlyTheirOwnEffects() {
        val w = 80; val h = 60
        val src = scene(w, h)
        val list = listOf(Effect(EffectKind.DRAMA, strength = 1f), Effect(EffectKind.GRAINY_FILM, strength = 1f))
        val tone = Effects.apply(src, w, h, list, Effects.Stage.TONE)
        assertArrayEquals(Effects.applyOne(src, w, h, list[0]), tone)
        assertArrayEquals(src, Effects.apply(src, w, h, list, Effects.Stage.LENS))
    }
}
