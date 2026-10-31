package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class FrameStylesTest {

    private val styles = FrameStyle.entries.filter { it != FrameStyle.PLAIN }

    /** A smooth, colourful picture drawn from continuous coordinates, so it is the same picture at any size. */
    private fun picture(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = (i % w + 0.5f) / w; val y = (i / w + 0.5f) / h
        Argb.pack(255, (40 + 180 * x).toInt(), (60 + 150 * y).toInt(), (200 - 120 * x * y).toInt())
    }

    private fun frame(style: FrameStyle, seed: Int = 3) = FrameStyles.preset(style).copy(seed = seed, caption = "Summer")

    @Test
    fun thereAreAtLeastFourteenDesignedStyles() {
        assertTrue(styles.size >= 14)
        for (s in styles) assertTrue("$s preset", FrameStyles.preset(s).valid && FrameStyles.preset(s).style == s)
    }

    @Test
    fun everyStyleIsDeterministicAndItsSeedMatters() {
        val w = 90; val h = 60; val pic = picture(w, h)
        for (s in styles) {
            val a = FrameStyles.render(pic, w, h, frame(s))
            val b = FrameStyles.render(pic, w, h, frame(s))
            assertArrayEquals("$s repeats", a, b)
            if (s.seeded) {
                val c = FrameStyles.render(pic, w, h, frame(s, seed = 4))
                assertFalse("$s seed changes it", a.contentEquals(c))
            }
        }
    }

    @Test
    fun theCanvasIsWhatTheLayoutPromises() {
        val w = 1000; val h = 800
        for (s in styles) {
            val f = frame(s)
            val l = FrameStyles.layout(w, h, f)
            assertEquals(Geometry2D.Size(l.width, l.height), Geometry2D.framedSize(Geometry2D.Size(w, h), f))
            assertTrue("$s card holds the picture", l.cardLeft <= l.pictureLeft && l.cardTop <= l.pictureTop && l.cardRight >= l.pictureRight && l.cardBottom >= l.pictureBottom)
            assertTrue("$s canvas holds the card", l.cardLeft >= 0 && l.cardTop >= 0 && l.cardRight <= l.width && l.cardBottom <= l.height)
        }
        // Polaroid by hand: sides of (0.045 + 0.05 * 0.5) of the shorter edge, a bottom 3.4 times that.
        val pol = FrameStyles.layout(w, h, Frame(style = FrameStyle.POLAROID))
        val side = (0.07f * 800).roundToInt(); val bottom = (0.07f * 3.4f * 800).roundToInt()
        assertEquals(w + 2 * side, pol.width); assertEquals(h + side + bottom, pol.height)
        assertEquals(side, pol.pictureLeft); assertEquals(side, pol.pictureTop)
        // A slide mount is square whatever the picture.
        val slide = FrameStyles.layout(w, h, Frame(style = FrameStyle.SLIDE))
        assertEquals(slide.cardRight - slide.cardLeft, slide.cardBottom - slide.cardTop)
        // Padding and aspect apply around any style.
        val square = FrameStyles.layout(w, h, Frame(style = FrameStyle.GALLERY, aspect = 1f, padding = 0.05f))
        assertEquals(square.width, square.height)
        val thin = FrameStyles.layout(w, h, Frame(style = FrameStyle.GALLERY, thickness = 0f))
        val thick = FrameStyles.layout(w, h, Frame(style = FrameStyle.GALLERY, thickness = 1f))
        assertTrue(thick.width > thin.width)
    }

    @Test
    fun thePictureIsUntouchedWhereTheStyleSaysSo() {
        val w = 120; val h = 80; val pic = picture(w, h)
        for (s in styles) {
            val f = frame(s)
            val l = FrameStyles.layout(w, h, f)
            val out = FrameStyles.render(pic, w, h, f)
            val inset = FrameStyles.pictureInset(f, l)
            assertTrue("$s inset leaves a picture", inset * 2 < h)
            var changed = 0
            for (y in inset until h - inset) for (x in inset until w - inset) {
                if (out[(l.pictureTop + y) * l.width + l.pictureLeft + x] != pic[y * w + x]) changed++
            }
            assertEquals("$s changed picture pixels", 0, changed)
            // And the frame itself is drawn: something outside the picture differs from the plain background.
            if (s != FrameStyle.FADE) {
                var drawn = 0
                for (y in 0 until l.height) for (x in 0 until l.width) {
                    val inside = x >= l.pictureLeft && x < l.pictureRight && y >= l.pictureTop && y < l.pictureBottom
                    if (!inside && out[y * l.width + x] != f.background) drawn++
                }
                assertTrue("$s draws a frame", drawn > 0)
            }
        }
    }

    @Test
    fun fadeAndBrushSoftenOnlyTheEdge() {
        val w = 120; val h = 80; val pic = picture(w, h)
        for (s in listOf(FrameStyle.FADE, FrameStyle.BRUSH)) {
            val f = frame(s)
            val l = FrameStyles.layout(w, h, f)
            val out = FrameStyles.render(pic, w, h, f)
            val corner = out[l.pictureTop * l.width + l.pictureLeft]
            assertTrue("$s corner gives way", corner != pic[0])
        }
    }

    @Test
    fun everyStyleLooksTheSameAtTwiceTheSize() {
        // What the preview predicts: the large render, halved, is the small render.
        for (s in styles) {
            val f = frame(s)
            val small = FrameStyles.render(picture(120, 80), 120, 80, f)
            val ls = FrameStyles.layout(120, 80, f)
            val large = FrameStyles.render(picture(240, 160), 240, 160, f)
            val ll = FrameStyles.layout(240, 160, f)
            assertTrue("$s width scales", abs(ll.width - 2 * ls.width) <= 4 && abs(ll.height - 2 * ls.height) <= 4)
            var sum = 0L; var n = 0
            // Matched on the picture's corner, so a pixel of rounding in a margin does not count as a difference.
            for (y in 0 until ls.height) for (x in 0 until ls.width) {
                val lx = ll.pictureLeft + 2 * (x - ls.pictureLeft); val ly = ll.pictureTop + 2 * (y - ls.pictureTop)
                if (lx < 0 || ly < 0 || lx + 1 >= ll.width || ly + 1 >= ll.height) continue
                val a = small[y * ls.width + x]
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = large[(ly + dy) * ll.width + lx + dx]
                    r += Argb.red(c); g += Argb.green(c); b += Argb.blue(c)
                }
                sum += abs(Argb.red(a) - r / 4) + abs(Argb.green(a) - g / 4) + abs(Argb.blue(a) - b / 4); n += 3
            }
            val diff = sum.toDouble() / n
            println("frame $s: two sizes differ by $diff")
            assertTrue("$s mean difference $diff", diff < 8.0)
        }
    }

    @Test
    fun neonTakesItsColourFromThePicture() {
        val w = 40; val h = 40
        val red = IntArray(w * h) { 0xFFD02020.toInt() }
        val c = FrameStyles.neonColour(Frame(style = FrameStyle.NEON), red)
        assertTrue(Argb.red(c) > Argb.green(c) && Argb.red(c) > Argb.blue(c))
        assertEquals(0xFF00FF00.toInt(), FrameStyles.neonColour(Frame(style = FrameStyle.NEON, variant = 1, borderColor = 0xFF00FF00.toInt()), red))
    }

    @Test
    fun transparentBackgroundsStayTransparentOutsideTheFrame() {
        val w = 60; val h = 40; val pic = picture(w, h)
        val f = FrameStyles.preset(FrameStyle.SHADOW).copy(background = 0, padding = 0.2f)
        val out = FrameStyles.render(pic, w, h, f)
        assertEquals(0, Argb.alpha(out[0]))
    }

    @Test
    fun captionsSitUnderThePictureInsideTheCard() {
        val l = FrameStyles.layout(300, 200, Frame(style = FrameStyle.POLAROID))
        val box = l.caption!!
        assertTrue(box.top >= l.pictureBottom && box.bottom <= l.cardBottom && box.height > 0f)
        assertEquals(null, FrameStyles.layout(300, 200, Frame(style = FrameStyle.WOOD)).caption)
    }
}
