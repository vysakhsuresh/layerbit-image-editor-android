package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class PortraitTest {

    private fun px(r: Int, g: Int, b: Int) = Argb.pack(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    /** Where the synthetic face is, in fractions: an ellipse in the middle. */
    private fun inFace(fx: Float, fy: Float): Float { val dx = (fx - 0.5f) / 0.26f; val dy = (fy - 0.48f) / 0.33f; return dx * dx + dy * dy }
    private fun inEye(fx: Float, fy: Float): Boolean = listOf(0.4f, 0.6f).any { ex -> val dx = (fx - ex) / 0.045f; val dy = (fy - 0.42f) / 0.025f; dx * dx + dy * dy < 1f }
    private fun inMouth(fx: Float, fy: Float): Boolean { val dx = (fx - 0.5f) / 0.08f; val dy = (fy - 0.64f) / 0.025f; return dx * dx + dy * dy < 1f }

    /**
     * A synthetic portrait: a skin-coloured oval with fine pore texture and soft shading, two
     * dark eyes, red lips, on a cool background with a hard black-and-white stripe in a corner.
     * Defined in fractions, so it can be drawn at any size.
     */
    private fun face(w: Int, h: Int, skin: Triple<Int, Int, Int> = Triple(214, 164, 134), seed: Long = 5, blotch: Boolean = false): IntArray {
        val rng = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val fx = (x + 0.5f) / w; val fy = (y + 0.5f) / h
            val d = inFace(fx, fy)
            when {
                // The hard edge, far from the face: must come through untouched.
                fx < 0.12f && fy < 0.3f -> if (((fx * 60).toInt() and 1) == 0) px(10, 10, 10) else px(245, 245, 245)
                d < 1f -> when {
                    inEye(fx, fy) -> px(35, 28, 24)
                    inMouth(fx, fy) -> px(165, 62, 74)
                    else -> {
                        // Pores: fine, small-amplitude luminance texture; shading toward the rim.
                        val pore = (rng.nextGaussian() * 5.0).toFloat() + 3f * sin(fx * 900f) * sin(fy * 700f)
                        val shade = 1f - 0.12f * d
                        val red = if (blotch && ((fx - 0.43f) * (fx - 0.43f) + (fy - 0.55f) * (fy - 0.55f)) < 0.003f) 24 else 0
                        px((skin.first * shade + pore).roundToInt() + red, (skin.second * shade + pore).roundToInt() - red / 3, (skin.third * shade + pore).roundToInt() - red / 3)
                    }
                }
                else -> px(70 + (40 * fy).toInt() + rng.nextInt(6), 95 + rng.nextInt(6), 140 + rng.nextInt(6))
            }
        }
    }

    private fun meanMask(s: Portrait.Skin, where: (Float, Float) -> Boolean): Float {
        var sum = 0.0; var n = 0
        for (y in 0 until s.height) for (x in 0 until s.width) {
            if (!where((x + 0.5f) / s.width, (y + 0.5f) / s.height)) continue
            sum += s.mask[y * s.width + x]; n++
        }
        return (sum / n / 255.0).toFloat()
    }

    private fun cheeks(fx: Float, fy: Float) = inFace(fx, fy) < 0.6f && !inEye(fx, fy) && !inMouth(fx, fy) && abs(fy - 0.42f) > 0.06f && abs(fy - 0.64f) > 0.05f

    @Test
    fun findsSkinOnLightAndDarkFacesAndNotOnTheBackground() {
        for (tone in listOf(Triple(214, 164, 134), Triple(236, 198, 176), Triple(126, 80, 54), Triple(92, 58, 42))) {
            val w = 400; val h = 400
            val s = Portrait.skin(face(w, h, tone), w, h)
            assertTrue("skin found for $tone", s.found)
            val onFace = meanMask(s, ::cheeks)
            val off = meanMask(s) { fx, fy -> inFace(fx, fy) > 1.6f }
            assertTrue("face $tone: $onFace", onFace > 0.8f)
            assertTrue("background $tone: $off", off < 0.03f)
            // The eyes are not skin.
            assertTrue("eyes $tone", meanMask(s) { fx, fy -> inEye(fx, fy) } < 0.35f)
        }
    }

    @Test
    fun aRedBrickWallAndGrainedWoodAreNotSkin() {
        val w = 300; val h = 300
        val rng = Random(3)
        // Red brick courses with pale mortar.
        val brick = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val row = y / 20
            val mortar = y % 20 < 3 || (x + (row % 2) * 22) % 44 < 3
            if (mortar) px(196 + rng.nextInt(8), 190 + rng.nextInt(8), 180 + rng.nextInt(8)) else px(172 + rng.nextInt(14), 62 + rng.nextInt(10), 46 + rng.nextInt(10))
        }
        val b = Portrait.skin(brick, w, h)
        assertTrue("brick ${b.coverage}", b.coverage < 0.1f)
        // Wood: a skin-like tan with strong grain. The grain, not the colour, rules it out.
        val wood = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val grain = 30f * sin((x + 6f * sin(y / 23f)) * 1.1f)
            px((165 + grain).roundToInt(), (115 + grain * 0.8f).roundToInt(), (70 + grain * 0.5f).roundToInt())
        }
        val wd = Portrait.skin(wood, w, h)
        assertTrue("wood ${wd.coverage}", wd.coverage < 0.15f)
    }

    @Test
    fun knownLimitAPlainTanSurfaceIsTakenForSkinAndAStrokeTakesItOut() {
        // Documented: colour alone cannot tell a smooth beige wall from a cheek.
        val w = 200; val h = 200
        val plain = IntArray(w * h) { px(205, 160, 128) }
        val s = Portrait.skin(plain, w, h)
        assertTrue(s.coverage > 0.8f)
        val removed = Portrait.skin(plain, w, h, strokes = listOf(SkinStroke(listOf(Pt(0f, 0.5f), Pt(1f, 0.5f)), 0.3f, add = false)))
        assertEquals(0, removed.mask[100 * w + 100])
        assertTrue(removed.coverage < s.coverage - 0.15f)
    }

    @Test
    fun strokesAddAndRemoveSkinAndTheSubjectMaskRestrictsIt() {
        val w = 300; val h = 300
        val picture = face(w, h)
        val added = Portrait.skin(picture, w, h, strokes = listOf(SkinStroke(listOf(Pt(0.9f, 0.9f)), 0.06f, add = true)))
        assertEquals(255, added.mask[(0.9f * (h - 1)).roundToInt() * w + (0.9f * (w - 1)).roundToInt()])
        val removed = Portrait.skin(picture, w, h, strokes = listOf(SkinStroke(listOf(Pt(0.5f, 0.55f)), 0.08f, add = false)))
        assertEquals(0, removed.mask[(0.55f * (h - 1)).roundToInt() * w + (0.5f * (w - 1)).roundToInt()])
        // A subject mask that keeps only the left half leaves no skin on the right.
        val subject = IntArray(w * h) { if (it % w < w / 2) 255 else 0 }
        val left = Portrait.skin(picture, w, h, subject = subject)
        assertEquals(0, left.mask[150 * w + 200])
        assertTrue(left.mask[150 * w + 110] > 200)
    }

    @Test
    fun strengthZeroIsTheIdentity() {
        val w = 200; val h = 200
        val picture = face(w, h)
        val s = Portrait.skin(picture, w, h)
        assertArrayEquals(picture, Portrait.apply(picture, w, h, PortraitEdit(), s))
        // Strokes and taps with every strength at zero change nothing either.
        val idle = PortraitEdit(eyes = 0f, teeth = 0f, taps = listOf(PortraitTap(Pt(0.4f, 0.42f), PortraitTapKind.EYE)), strokes = listOf(SkinStroke(listOf(Pt(0.5f, 0.5f)), 0.1f, true)))
        assertArrayEquals(picture, Portrait.apply(picture, w, h, idle, s))
    }

    @Test
    fun smoothingQuietensPoresOnSkinAndLeavesEverythingElseAlone() {
        val w = 400; val h = 400
        val picture = face(w, h)
        val s = Portrait.skin(picture, w, h)
        val out = Portrait.apply(picture, w, h, PortraitEdit(smooth = 1f, texture = 0.3f), s)
        val before = Portrait.highFrequencyVariance(picture, w, h) { x, y -> cheeks((x + 0.5f) / w, (y + 0.5f) / h) }
        val after = Portrait.highFrequencyVariance(out, w, h) { x, y -> cheeks((x + 0.5f) / w, (y + 0.5f) / h) }
        assertTrue("pores $before -> $after", after < before * 0.5)
        // Texture kept: not a plastic face.
        assertTrue("some texture remains $after", after > before * 0.03)
        // Outside the skin, including the hard stripe, not one pixel moves.
        for (y in 0 until h) for (x in 0 until w) {
            val fx = (x + 0.5f) / w; val fy = (y + 0.5f) / h
            if (inFace(fx, fy) > 1.6f) assertEquals("($x,$y)", picture[y * w + x], out[y * w + x])
        }
        // More texture kept, more pore variance left.
        val textured = Portrait.apply(picture, w, h, PortraitEdit(smooth = 1f, texture = 0.8f), s)
        assertTrue(Portrait.highFrequencyVariance(textured, w, h) { x, y -> cheeks((x + 0.5f) / w, (y + 0.5f) / h) } > after)
    }

    @Test
    fun evenTonePullsABlotchBackTowardTheSkin() {
        val w = 400; val h = 400
        val picture = face(w, h, blotch = true)
        val s = Portrait.skin(picture, w, h)
        // The blotch's redness over the skin around it, before and after.
        fun redness(p: IntArray): Double {
            var inSum = 0.0; var inN = 0; var outSum = 0.0; var outN = 0
            for (y in 0 until h) for (x in 0 until w) {
                val fx = (x + 0.5f) / w; val fy = (y + 0.5f) / h
                val d2 = (fx - 0.43f) * (fx - 0.43f) + (fy - 0.55f) * (fy - 0.55f)
                val cr = Portrait.ycbcr(p[y * w + x])[2].toDouble()
                if (d2 < 0.002f) { inSum += cr; inN++ } else if (d2 in 0.005f..0.012f && cheeks(fx, fy)) { outSum += cr; outN++ }
            }
            return inSum / inN - outSum / outN
        }
        val out = Portrait.apply(picture, w, h, PortraitEdit(even = 1f), s)
        assertTrue("blotch redness ${redness(picture)} -> ${redness(out)}", redness(out) < redness(picture) * 0.5)
        assertTrue(redness(picture) > 8.0)
    }

    @Test
    fun faceLightBrightensSkinOnly() {
        val w = 300; val h = 300
        val picture = face(w, h)
        val s = Portrait.skin(picture, w, h)
        val out = Portrait.apply(picture, w, h, PortraitEdit(light = 1f), s)
        fun luma(p: IntArray, where: (Float, Float) -> Boolean): Double {
            var sum = 0.0; var n = 0
            for (y in 0 until h) for (x in 0 until w) if (where((x + 0.5f) / w, (y + 0.5f) / h)) { sum += Argb.luma(p[y * w + x]); n++ }
            return sum / n
        }
        assertTrue(luma(out, ::cheeks) > luma(picture, ::cheeks) + 8)
        assertEquals(luma(picture) { fx, fy -> inFace(fx, fy) > 2.5f }, luma(out) { fx, fy -> inFace(fx, fy) > 2.5f }, 0.01)
    }

    @Test
    fun teethLoseTheirYellowAndLipsStayRed() {
        val w = 200; val h = 200
        val picture = IntArray(w * h) { i -> if ((i % w) < 100) px(222, 200, 140) else px(165, 62, 74) }
        val tap = PortraitTap(Pt(0.5f, 0.5f), PortraitTapKind.TEETH, radius = 0.2f)
        val out = Portrait.apply(picture, w, h, PortraitEdit(teeth = 1f, taps = listOf(tap)), null)
        val tooth = 100 * w + 95; val lip = 100 * w + 105
        val cbBefore = Portrait.ycbcr(picture[tooth])[1]; val cbAfter = Portrait.ycbcr(out[tooth])[1]
        assertTrue("yellow $cbBefore -> $cbAfter", 128f - cbAfter < (128f - cbBefore) * 0.4f)
        assertTrue(Argb.luma(out[tooth]) > Argb.luma(picture[tooth]))
        assertTrue(abs(Argb.red(out[lip]) - Argb.red(picture[lip])) <= 2)
        // Outside the circle nothing moves.
        assertEquals(picture[5], out[5])
    }

    @Test
    fun anEyeTapAddsLocalContrastInsideItsCircle() {
        val w = 200; val h = 200
        val picture = face(w, h)
        val tap = PortraitTap(Pt(0.4f, 0.42f), PortraitTapKind.EYE, radius = 0.06f)
        val out = Portrait.apply(picture, w, h, PortraitEdit(eyes = 1f, taps = listOf(tap)), null)
        val near: (Int, Int) -> Boolean = { x, y -> val dx = x - 0.4f * (w - 1); val dy = y - 0.42f * (h - 1); dx * dx + dy * dy < 64f }
        assertTrue(Portrait.highFrequencyVariance(out, w, h, near) > Portrait.highFrequencyVariance(picture, w, h, near) * 1.2)
        assertEquals(picture[0], out[0])
    }

    @Test
    fun theSmallRenderPredictsTheLargeOne() {
        // The same face at 300 and 1200 pixels, the mask decided once at the analysis size, smoothed and lit:
        // the large result shrunk to the small one must agree closely.
        fun smoothFace(w: Int, h: Int) = IntArray(w * h) { i ->
            val fx = (i % w + 0.5f) / w; val fy = (i / w + 0.5f) / h
            val d = inFace(fx, fy)
            if (d < 1f) {
                val pore = 6f * sin(fx * 140f) * sin(fy * 120f)
                px((214 * (1 - 0.12f * d) + pore).roundToInt(), (164 * (1 - 0.12f * d) + pore).roundToInt(), (134 * (1 - 0.12f * d) + pore).roundToInt())
            } else px(70, 95, 140)
        }
        val analysis = Segment.analysisSize(300, 300)
        val skin = Portrait.skin(smoothFace(analysis.width, analysis.height), analysis.width, analysis.height)
        val edit = PortraitEdit(smooth = 0.8f, even = 0.5f, light = 0.6f)
        val small = Portrait.apply(smoothFace(300, 300), 300, 300, edit, skin)
        val large = Portrait.apply(smoothFace(1200, 1200), 1200, 1200, edit, skin)
        var diff = 0.0
        for (y in 0 until 300) for (x in 0 until 300) {
            var r = 0; var g = 0; var b = 0
            for (k in 0 until 16) { val c = large[(y * 4 + k / 4) * 1200 + x * 4 + k % 4]; r += Argb.red(c); g += Argb.green(c); b += Argb.blue(c) }
            val c = small[y * 300 + x]
            diff += (abs(r / 16 - Argb.red(c)) + abs(g / 16 - Argb.green(c)) + abs(b / 16 - Argb.blue(c))) / 3.0
        }
        diff /= 300 * 300
        assertTrue("mean difference $diff", diff < 2.5)
    }

    @Test
    fun presetKeepsStrengthsAndDropsStrokesAndTaps() {
        val p = PortraitEdit(smooth = 0.5f, strokes = listOf(SkinStroke(listOf(Pt(0.1f, 0.1f)), 0.05f, true)), taps = listOf(PortraitTap(Pt(0.5f, 0.5f), PortraitTapKind.EYE)))
        val r = Recipe(portrait = p).asPreset()
        assertEquals(0.5f, r.portrait.smooth, 0f)
        assertTrue(r.portrait.strokes.isEmpty() && r.portrait.taps.isEmpty())
    }

    @Test
    fun skinIsTakenFromThePersonNotFromAGoldenBackgroundThatOutnumbersIt() {
        // A small warm face in the middle; most of the picture is a golden, skin-like sky.
        val w = 160; val h = 200
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val dx = (x - 80) / 28f; val dy = (y - 95) / 36f
            if (dx * dx + dy * dy <= 1f) Argb.pack(255, 196, 132, 104) else Argb.pack(255, 236, 196, 120)
        }
        val s = Portrait.skin(px, w, h)
        val face = s.mask[95 * w + 80]; val sky = s.mask[20 * w + 20]
        assertTrue("face $face sky $sky", face > 150 && sky < 60)
    }

    @Test
    fun paintedAmberSkinIsFoundOnThePersonNotOnTheGoldAroundIt() {
        // Stylised art: deep amber skin with almost no blue, on a gold background of the same hue
        // and a grey misty sky. The subject mask says where the person is.
        val w = 160; val h = 240
        val person = IntArray(w * h); val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val inPerson = (x - 80) * (x - 80) / (40f * 40f) + (y - 130) * (y - 130) / (90f * 90f) <= 1f
            person[i] = if (inPerson) 255 else 0
            val shade = ((x * 7 + y * 3) % 9) - 4
            px[i] = when {
                y < 50 -> Argb.pack(255, 150 + shade, 150 + shade, 160 + shade)                // misty sky
                inPerson -> Argb.pack(255, 235 + shade, 150 + shade, 56 + shade)              // painted skin
                else -> Argb.pack(255, 200 + shade, 130 + shade, 20 + shade)                  // gold
            }
        }
        val s = Portrait.skin(px, w, h, prior = person)
        assertTrue("person ${s.mask[130 * w + 80]}", s.mask[130 * w + 80] > 150)
        assertTrue("gold ${s.mask[200 * w + 10]}", s.mask[200 * w + 10] < 30)
        assertTrue("sky ${s.mask[20 * w + 80]}", s.mask[20 * w + 80] < 30)
        // With no person known the painter's range is never used: gold alone is not skin.
        val none = Portrait.skin(px, w, h)
        assertTrue("gold without a person ${none.mask[200 * w + 10]}", none.mask[200 * w + 10] < 100)
    }

    @Test
    fun clearSpotsLiftsSmallDarkMarksOnSkinAndLeavesThePicture() {
        val w = 400; val h = 400
        val clean = face(w, h)
        // Dark spots, a few pixels across, on the cheeks.
        val spotted = clean.copyOf()
        val centres = listOf(0.38f to 0.55f, 0.62f to 0.56f, 0.44f to 0.5f)
        for ((cx, cy) in centres) for (dy in -3..3) for (dx in -3..3) if (dx * dx + dy * dy <= 9) {
            val x = (cx * w).toInt() + dx; val y = (cy * h).toInt() + dy
            val c = spotted[y * w + x]
            spotted[y * w + x] = Argb.pack(255, Argb.red(c) - 55, Argb.green(c) - 45, Argb.blue(c) - 40)
        }
        val s = Portrait.skin(spotted, w, h)
        val out = Portrait.apply(spotted, w, h, PortraitEdit(spots = 1f), s)
        for ((cx, cy) in centres) {
            val i = (cy * h).toInt() * w + (cx * w).toInt()
            val before = Argb.luma(clean[i]) - Argb.luma(spotted[i])
            val after = Argb.luma(clean[i]) - Argb.luma(out[i])
            assertTrue("spot at $cx,$cy: $before -> $after", after < before * 0.5)
        }
        // Away from the face nothing moves.
        for (y in 0 until h) for (x in 0 until w) {
            val fx = (x + 0.5f) / w; val fy = (y + 0.5f) / h
            if (inFace(fx, fy) > 1.6f) assertEquals("($x,$y)", spotted[y * w + x], out[y * w + x])
        }
        // A clean face stays nearly as it was: pores are not spots.
        val cleanOut = Portrait.apply(clean, w, h, PortraitEdit(spots = 1f), Portrait.skin(clean, w, h))
        var moved = 0L; var n = 0
        for (y in 0 until h) for (x in 0 until w) if (cheeks((x + 0.5f) / w, (y + 0.5f) / h)) { moved += abs(Argb.luma(cleanOut[y * w + x]) - Argb.luma(clean[y * w + x])); n++ }
        assertTrue("clean skin moved ${moved.toFloat() / n}", moved.toFloat() / n < 2.5f)
    }
}
