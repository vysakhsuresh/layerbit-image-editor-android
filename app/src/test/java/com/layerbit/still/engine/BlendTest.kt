package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class BlendTest {

    private val e = 1e-6f

    @Test
    fun everyModeMatchesItsFormula() {
        val values = listOf(0f, 0.1f, 0.25f, 0.4f, 0.5f, 0.64f, 0.75f, 0.9f, 1f)
        for (b in values) for (t in values) {
            assertEquals(t, Blend.channel(BlendMode.NORMAL, b, t), e)
            assertEquals(max(b, t), Blend.channel(BlendMode.LIGHTEN, b, t), e)
            assertEquals(min(b, t), Blend.channel(BlendMode.DARKEN, b, t), e)
            assertEquals(1f - (1f - b) * (1f - t), Blend.channel(BlendMode.SCREEN, b, t), e)
            assertEquals(b * t, Blend.channel(BlendMode.MULTIPLY, b, t), e)
            assertEquals(min(1f, b + t), Blend.channel(BlendMode.ADD, b, t), e)
            // Overlay is hard light with the layers swapped.
            val overlay = if (b < 0.5f) 2 * b * t else if (b > 0.5f) 1 - 2 * (1 - b) * (1 - t) else t
            assertEquals(overlay, Blend.channel(BlendMode.OVERLAY, b, t), 1e-5f)
        }
        // Soft light, W3C, at points worked by hand.
        assertEquals(0.5f, Blend.channel(BlendMode.SOFT_LIGHT, 0.5f, 0.5f), e)
        assertEquals(0.25f, Blend.channel(BlendMode.SOFT_LIGHT, 0.5f, 0f), e)
        assertEquals(0.5f, Blend.channel(BlendMode.SOFT_LIGHT, 0.25f, 1f), e)
        assertEquals(0.8f, Blend.channel(BlendMode.SOFT_LIGHT, 0.64f, 1f), 1e-5f)
        assertEquals(0f, Blend.channel(BlendMode.SOFT_LIGHT, 0f, 0.3f), e)
        assertEquals(1f, Blend.channel(BlendMode.SOFT_LIGHT, 1f, 0.7f), e)
    }

    @Test
    fun pixelsBlendPerChannelAndOpacityMixes() {
        val base = Argb.pack(255, 64, 128, 200); val top = Argb.pack(255, 128, 128, 10)
        val screen = Blend.pixel(BlendMode.SCREEN, base, top, 1f)
        assertEquals(Math.round((1 - (1 - 64 / 255f) * (1 - 128 / 255f)) * 255), Argb.red(screen))
        assertEquals(base, Blend.pixel(BlendMode.MULTIPLY, base, top, 0f))
        assertEquals(top, Blend.pixel(BlendMode.NORMAL, base, top, 1f))
        val half = Blend.pixel(BlendMode.NORMAL, base, top, 0.5f)
        assertEquals(96, Argb.red(half)); assertEquals(105, Argb.blue(half))
        // The base keeps its own alpha.
        assertEquals(0x80, Argb.alpha(Blend.pixel(BlendMode.SCREEN, Argb.withAlpha(base, 0x80), top, 1f)))
    }

    @Test
    fun placementRoundTripsAndCoversTheCanvasAtScaleOne() {
        val rng = Random(9)
        repeat(200) {
            val p = Blend.Placement(rng.nextFloat(), rng.nextFloat(), 0.2f + rng.nextFloat() * 3f, rng.nextFloat() * 720f - 360f)
            val cw = 100 + rng.nextInt(2000); val ch = 100 + rng.nextInt(2000); val aspect = 0.3f + rng.nextFloat() * 3f
            val u = rng.nextFloat(); val v = rng.nextFloat()
            val c = p.toCanvas(u, v, cw, ch, aspect)
            val back = p.toLayer(c[0], c[1], cw, ch, aspect)
            assertEquals(u, back[0], 1e-3f); assertEquals(v, back[1], 1e-3f)
        }
        // Centred, unturned, scale 1: the layer just covers the canvas, whatever the two aspects.
        val p = Blend.Placement(0.5f, 0.5f, 1f, 0f)
        for (aspect in listOf(0.5f, 1f, 1.5f, 3f)) {
            val tl = p.toLayer(0f, 0f, 1200, 800, aspect); val br = p.toLayer(1200f, 800f, 1200, 800, aspect)
            assertTrue(tl[0] >= -1e-4f && tl[1] >= -1e-4f && br[0] <= 1.0001f && br[1] <= 1.0001f)
            assertTrue(abs(tl[0]) < 1e-4f || abs(tl[1]) < 1e-4f)
        }
        val centre = p.toLayer(600f, 400f, 1200, 800, 1.5f)
        assertEquals(0.5f, centre[0], 1e-5f); assertEquals(0.5f, centre[1], 1e-5f)
        // A quarter turn clockwise: the layer's top edge comes down the right side.
        val turned = Blend.Placement(0.5f, 0.5f, 1f, 90f)
        val top = turned.toCanvas(0.5f, 0f, 1000, 1000, 1f)
        assertEquals(1000f, top[0], 0.01f); assertEquals(500f, top[1], 0.01f)
    }

    private fun gradient(w: Int, h: Int) = IntArray(w * h) { i -> Argb.pack(255, (i % w) * 255 / (w - 1), (i / w) * 255 / (h - 1), 90) }

    @Test
    fun compositeIsTheFormulaWhereTheLayerLiesAndNothingElse() {
        val w = 80; val h = 60
        val base = IntArray(w * h) { Argb.pack(255, 100, 150, 200) }
        val layer = IntArray(40 * 40) { Argb.pack(255, 200, 50, 128) }
        val d = DoubleExposure(file = "layer-a", scale = 0.4f, mode = BlendMode.MULTIPLY, opacity = 1f)
        val out = base.copyOf()
        Blend.composite(out, w, h, layer, 40, 40, d)
        val centre = out[30 * w + 40]
        assertEquals(Blend.pixel(BlendMode.MULTIPLY, base[0], layer[0], 1f), centre)
        // Outside the placed square, untouched.
        assertEquals(base[0], out[0]); assertEquals(base[0], out[59 * w + 79])
        // Inside the subject only: a mask with nothing kept changes nothing.
        val masked = base.copyOf()
        Blend.composite(masked, w, h, layer, 40, 40, d.copy(insideSubject = true), IntArray(16 * 12), 16, 12)
        assertArrayEquals(base, masked)
        // A transparent layer changes nothing.
        val clear = base.copyOf()
        Blend.composite(clear, w, h, IntArray(40 * 40), 40, 40, d)
        assertArrayEquals(base, clear)
    }

    @Test
    fun theSmallCompositePredictsTheLargeOne() {
        val d = DoubleExposure(file = "x", x = 0.45f, y = 0.55f, scale = 0.9f, angle = 17f, mode = BlendMode.SCREEN, opacity = 0.8f)
        fun run(w: Int, h: Int): IntArray {
            val base = IntArray(w * h) { i -> Argb.pack(255, 40, (i / w) * 200 / h, 60) }
            Blend.composite(base, w, h, gradient(w, w), w, w, d)
            return base
        }
        val small = run(300, 200); val large = run(1200, 800)
        var diff = 0.0
        for (y in 0 until 200) for (x in 0 until 300) {
            var r = 0; var g = 0; var b = 0
            for (k in 0 until 16) { val c = large[(y * 4 + k / 4) * 1200 + x * 4 + k % 4]; r += Argb.red(c); g += Argb.green(c); b += Argb.blue(c) }
            val c = small[y * 300 + x]
            diff += (abs(r / 16 - Argb.red(c)) + abs(g / 16 - Argb.green(c)) + abs(b / 16 - Argb.blue(c))) / 3.0
        }
        assertTrue("mean difference ${diff / 60000}", diff / 60000 < 2.0)
    }

    @Test
    fun namesArePrivateFileNamesNeverPaths() {
        assertTrue(DoubleExposure.safeName("layer-1a2b.img"))
        for (bad in listOf("", "../source", "a/b", "..", ".hidden", "c:\\x", "x".repeat(81), "naïve")) assertFalse(bad, DoubleExposure.safeName(bad))
        assertFalse(Recipe(double = DoubleExposure(file = "../x")).valid)
        assertFalse(Recipe(double = DoubleExposure(file = "a", scale = 0f)).valid)
        assertTrue(Recipe(double = DoubleExposure(file = "a")).valid)
    }

    @Test
    fun portraitAndDoubleRoundTripThroughJsonAndAbsentIsOff() {
        val r = Recipe(
            portrait = PortraitEdit(smooth = 0.7f, texture = 0.2f, even = 0.4f, light = 0.3f, eyes = 0.9f, teeth = 0.1f, subjectOnly = true,
                strokes = listOf(SkinStroke(listOf(Pt(0.1f, 0.2f), Pt(0.3f, 0.4f)), 0.05f, add = false)),
                taps = listOf(PortraitTap(Pt(0.4f, 0.42f), PortraitTapKind.EYE, 0.03f), PortraitTap(Pt(0.5f, 0.64f), PortraitTapKind.TEETH, 0.05f))),
            double = DoubleExposure(file = "layer-abc", x = 0.3f, y = 0.6f, scale = 1.7f, angle = -22.5f, mode = BlendMode.SOFT_LIGHT, opacity = 0.55f, insideSubject = true)
        )
        assertTrue(r.valid)
        assertEquals(r, RecipeJson.read(RecipeJson.write(r)))
        val old = RecipeJson.read("""{"schema":2,"tone":{"exposure":0.1}}""")
        assertEquals(PortraitEdit(), old.portrait); assertEquals(DoubleExposure(), old.double)
        assertFalse(RecipeJson.write(Recipe()).contains("portrait"))
        // A path in place of a name is refused, not followed.
        try { RecipeJson.read("""{"schema":2,"double":{"file":"../../shared_prefs/x"}}"""); fail() } catch (_: IllegalArgumentException) {}
        // An unknown mode falls back to screen; an unknown tap kind is dropped.
        val lenient = RecipeJson.read("""{"schema":2,"double":{"file":"a","mode":"HUE"},"portrait":{"smooth":0.5,"taps":[{"at":[0.5,0.5],"kind":"NOSE"}]}}""")
        assertEquals(BlendMode.SCREEN, lenient.double.mode)
        assertTrue(lenient.portrait.taps.isEmpty())
        assertEquals(0.5f, lenient.portrait.smooth, 0f)
        // A preset never carries another picture's file.
        assertEquals(DoubleExposure(), r.asPreset().double)
    }

    @Suppress("unused") private fun len(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1])
}
