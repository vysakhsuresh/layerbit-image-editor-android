package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScanAndRetouchTest {

    /** A white page lit from one side, from 230 on the left to 110 on the right, with black text lines and a blue heading. */
    private fun page(w: Int = 160, h: Int = 120): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val light = 230 - (x * 120 / w)
        val text = (y % 12) in 4..6 && x in 10 until w - 10 && y > 20
        val heading = y in 6..12 && x in 10..80
        when {
            text -> Argb.pack(255, light / 6, light / 6, light / 6)
            heading -> Argb.pack(255, light / 5, light / 4, light * 3 / 4)
            else -> Argb.pack(255, light, light - 4, light - 12)
        }
    }

    @Test
    fun scanMakesPaperWhiteEverywhereAndKeepsInkDark() {
        val w = 160; val h = 120
        val out = ScanMath.apply(page(w, h), w, h, Scan(ScanMode.COLOUR))
        val paperLeft = out[60 * w + 20]; val paperRight = out[60 * w + 140]
        assertTrue("left paper ${Argb.luma(paperLeft)}", Argb.luma(paperLeft) >= 245)
        assertTrue("right paper ${Argb.luma(paperRight)}", Argb.luma(paperRight) >= 240)
        assertTrue("paper is even now", abs(Argb.luma(paperLeft) - Argb.luma(paperRight)) < 8)
        val inkRight = out[(24 + 5) * w + 140]
        assertTrue("ink stays dark on the dim side: ${Argb.luma(inkRight)}", Argb.luma(inkRight) < 90)
        val head = out[9 * w + 40]
        assertTrue("the heading keeps its blue", Argb.blue(head) > Argb.red(head) + 40)
    }

    @Test
    fun greyAndInkModesDoWhatTheySay() {
        val w = 160; val h = 120
        val grey = ScanMath.apply(page(w, h), w, h, Scan(ScanMode.GREY))
        val g = grey[9 * w + 40]
        assertEquals(Argb.red(g), Argb.blue(g))
        val ink = ScanMath.apply(page(w, h), w, h, Scan(ScanMode.INK))
        assertEquals(255, Argb.luma(ink[60 * w + 140]))
        assertTrue(Argb.luma(ink[(24 + 5) * w + 140]) < 20)
        val half = ScanMath.apply(page(w, h), w, h, Scan(ScanMode.COLOUR, strength = 0.5f))
        val l = Argb.luma(half[60 * w + 140])
        assertTrue("half strength lands between: $l", l in 150..230)
        assertTrue(ScanMath.apply(page(w, h), w, h, Scan()).contentEquals(page(w, h)))
    }

    @Test
    fun redEyeDarkensOnlyTheRedPupil() {
        val w = 40; val h = 40
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val d2 = (x - 20) * (x - 20) + (y - 20) * (y - 20)
            when {
                d2 < 16 -> Argb.pack(255, 220, 40, 40)   // the flash pupil
                d2 < 64 -> Argb.pack(255, 90, 120, 60)   // iris
                else -> Argb.pack(255, 230, 200, 190)    // skin
            }
        }
        val changed = Retouch.redEye(px, w, h, 20f, 20f, 9f)
        assertTrue(changed in 30..60)
        val pupil = px[20 * w + 20]
        assertTrue("pupil is dark now: ${Argb.red(pupil)}", Argb.red(pupil) < 60 && Argb.red(pupil) <= Argb.green(pupil) + 10)
        assertEquals(Argb.pack(255, 90, 120, 60), px[20 * w + 26])
        assertEquals(Argb.pack(255, 230, 200, 190), px[2 * w + 2])
        assertEquals(0, Retouch.redEye(px, w, h, 2f, 2f, 5f))
    }

    @Test
    fun neutraliseWarmsABlueCastAndAddsMagentaToAGreenOne() {
        val blue = Retouch.neutralise(120, 128, 170, Tone())
        assertTrue(blue.temperature > 0.2f)
        assertTrue(abs(blue.tint) < 0.15f)
        val green = Retouch.neutralise(120, 160, 120, Tone(exposure = 0.4f))
        assertTrue(green.tint > 0.2f)
        assertEquals(0.4f, green.exposure, 1e-6f)
        val grey = Retouch.neutralise(128, 128, 128, Tone())
        assertEquals(0f, grey.temperature, 1e-6f); assertEquals(0f, grey.tint, 1e-6f)
        val s = Retouch.sample(intArrayOf(Argb.pack(255, 10, 20, 30), Argb.pack(255, 30, 40, 50)), 2, 1, 0, 0, half = 1)
        assertEquals(20, s[0]); assertEquals(30, s[1]); assertEquals(40, s[2])
    }

    @Test
    fun recipeRoundTripsCutoutAndScan() {
        val r = Recipe(
            scan = Scan(ScanMode.INK, strength = 0.8f, whiten = 0.3f),
            cutout = Cutout(enabled = true, mode = CutoutMode.SUBJECT, scribbles = listOf(Scribble(listOf(Pt(0.1f, 0.2f), Pt(0.3f, 0.4f)), 0.05f, keep = false)), feather = 0.01f, invert = true, backdrop = Backdrop.BLUR, color = 0xFF123456.toInt(), blur = 0.05f)
        )
        val back = RecipeJson.read(RecipeJson.write(r))
        assertEquals(r, back)
        assertEquals(Recipe(), RecipeJson.read(RecipeJson.write(Recipe())))
        assertEquals(Cutout(), r.asPreset().cutout)
        assertEquals(r.scan, r.asPreset().scan)
    }
}
