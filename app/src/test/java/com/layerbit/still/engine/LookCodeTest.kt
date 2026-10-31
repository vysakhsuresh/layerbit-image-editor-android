package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LookCodeTest {

    private val rich = Recipe(
        tone = Tone(exposure = 0.3f, contrast = 0.2f, highlights = -0.45f, shadows = 0.35f, saturation = -0.1f, temperature = 0.12f, clarity = 0.25f, vignette = 0.3f, grain = 0.15f),
        curves = Curves(master = listOf(Pt(0f, 0.05f), Pt(0.25f, 0.2f), Pt(0.75f, 0.8f), Pt(1f, 0.95f)), blue = listOf(Pt(0f, 0.1f), Pt(1f, 0.9f))),
        look = Look.FILM, lookStrength = 0.8f,
        mix = ColourMix(hue = List(ColourMix.BANDS) { if (it == 2) 0.2f else 0f }, saturation = ColourMix.ZERO, lightness = ColourMix.ZERO),
        frame = Frame(padding = 0.05f, border = 0.01f, borderColor = 0xFF202020.toInt(), background = 0xFFF5F0E8.toInt()),
        // Everything below belongs to one picture and must not travel.
        geometry = Geometry(crop = Crop(0.1f, 0.1f, 0.9f, 0.8f), rotation = 90, straighten = 3f, width = 1080, height = 1350),
        marks = listOf(Mark(MarkKind.TEXT, listOf(Pt(0.5f, 0.5f)), text = "private note")),
        expand = Expand(left = 0.2f),
        cutout = Cutout(enabled = true)
    )

    @Test
    fun aLookRoundTripsWithoutThePicturesOwnParts() {
        val code = LookCode.encode("Golden hour", rich)
        assertTrue(code.startsWith(LookCode.PREFIX))
        val (name, back) = LookCode.decode(code)
        assertEquals("Golden hour", name)
        assertEquals(LookCode.lookOf(rich), back)
        assertEquals(rich.tone, back.tone)
        assertEquals(rich.curves, back.curves)
        assertEquals(Look.FILM, back.look)
        assertEquals(0.8f, back.lookStrength)
        assertEquals(rich.frame, back.frame)
        assertEquals(rich.mix, back.mix)
        assertEquals(Geometry(), back.geometry)
        assertTrue(back.marks.isEmpty() && back.expand.isIdentity && back.cutout.isIdentity)
        assertTrue(!code.contains("private"))
    }

    @Test
    fun aRichLookFitsAComfortableCode() {
        val code = LookCode.encode("Golden hour", rich)
        val version = Qr.versionFor(code.length)!!
        assertTrue("needs version $version for ${code.length} characters", version <= 10)
        // Even every curve at its sixteen-point limit still fits the largest code the app writes.
        fun busy(seed: Int) = (0 until 16).map { i -> Pt(i / 15f, ((i * 37 + seed * 11) % 100) / 100f) }
        val worst = rich.copy(curves = Curves(busy(1), busy(2), busy(3), busy(4)), mix = ColourMix(List(ColourMix.BANDS) { 0.11f * (it % 3) }, List(ColourMix.BANDS) { -0.07f * (it % 4) }, List(ColourMix.BANDS) { 0.05f * (it % 5) }))
        assertTrue(LookCode.fits(LookCode.encode("A very long preset name for the worst case", worst)))
    }

    @Test
    fun theCodeSurvivesTheQrRoundTrip() {
        val code = LookCode.encode("Teal", rich.copy(look = Look.TEAL_ORANGE))
        val m = Qr.encode(code.toByteArray(Charsets.US_ASCII))
        val text = String(Qr.decode(m)!!, Charsets.US_ASCII)
        assertEquals(Look.TEAL_ORANGE, LookCode.decode(text).second.look)
    }

    @Test
    fun anythingElseIsRefused() {
        for (bad in listOf("", "hello", "https://example.com", LookCode.PREFIX + "!!!", LookCode.PREFIX + "AAAA", LookCode.encode("x", rich).dropLast(6))) {
            try { LookCode.decode(bad); fail("read '$bad'") } catch (e: IllegalArgumentException) { /* refused */ }
        }
    }

    @Test
    fun anIdentityLookIsTiny() {
        assertTrue(LookCode.encode("", Recipe()).length < 60)
        assertEquals(Recipe(), LookCode.decode(LookCode.encode("", Recipe())).second)
    }
}
