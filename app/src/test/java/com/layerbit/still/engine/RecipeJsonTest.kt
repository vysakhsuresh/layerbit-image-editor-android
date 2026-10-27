package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecipeJsonTest {

    private val full = Recipe(
        geometry = Geometry(
            quad = Quad(Pt(0.05f, 0.02f), Pt(0.98f, 0.03f), Pt(0.97f, 0.96f), Pt(0.04f, 0.95f)),
            rotation = 270, flipX = true, straighten = -3.5f, crop = Crop(0.1f, 0.2f, 0.9f, 0.8f), width = 1200, height = 900
        ),
        tone = Tone(exposure = 0.4f, contrast = 0.2f, highlights = -0.3f, shadows = 0.25f, whites = 0.1f, blacks = -0.1f,
            saturation = 0.1f, vibrance = 0.3f, temperature = 0.2f, tint = -0.1f, hue = 0.05f, fade = 0.2f, clarity = 0.3f,
            dehaze = 0.1f, sharpness = 0.5f, vignette = 0.3f, grain = 0.2f),
        curves = Curves(master = listOf(Pt(0f, 0f), Pt(0.4f, 0.5f), Pt(1f, 1f)), red = listOf(Pt(0f, 0.1f), Pt(1f, 1f))),
        look = Look.FILM, lookStrength = 0.6f,
        marks = listOf(
            Mark(MarkKind.TEXT, listOf(Pt(0.2f, 0.3f)), text = "Layerbit \"Still\"\nline two", width = 0.05f, angle = 12f, opacity = 0.8f),
            Mark(MarkKind.CLONE, listOf(Pt(0.5f, 0.5f), Pt(0.6f, 0.5f)), source = Pt(0.1f, 0.1f)),
            Mark(MarkKind.WATERMARK, listOf(Pt(0.5f, 0.5f)), text = "© me", angle = -30f, opacity = 0.3f)
        ),
        frame = Frame(aspect = 1f, padding = 0.05f, border = 0.01f, borderColor = 0xFF102030.toInt(), background = 0x00000000, cornerRadius = 0.03f, blurredFill = true)
    )

    @Test
    fun roundTripsEveryField() {
        val text = RecipeJson.write(full)
        assertEquals(full, RecipeJson.read(text))
        assertEquals(Recipe(), RecipeJson.read(RecipeJson.write(Recipe())))
    }

    @Test
    fun missingFieldsTakeDefaultsSoOlderAndNewerAppsAgree() {
        val minimal = """{"schema":2,"tone":{"exposure":0.5},"geometry":{"rotation":90}}"""
        val r = RecipeJson.read(minimal)
        assertEquals(0.5f, r.tone.exposure, 0f)
        assertEquals(90, r.geometry.rotation)
        assertEquals(Crop.FULL, r.geometry.crop)
        assertEquals(Look.NONE, r.look)
        val unknownKey = """{"schema":2,"future":{"x":1},"tone":{"glow":9}}"""
        assertEquals(Recipe(), RecipeJson.read(unknownKey))
    }

    @Test
    fun readsThePreviewsSchemaOneAndRotatesItsCrop() {
        val old = """{"version":1,"crop":[0,0,0.5,1],"rotation":90,"straighten":0,"flipX":false,"flipY":false,"width":0,"height":0,
            "filter":"Warm","intensity":0.5,"tone":[0.2,0.1,0,0,0.3,0.1,0,0.5],
            "marks":[{"kind":"BOX","color":-1,"width":0.02,"text":"","opacity":1,"visible":true,"points":[[0.1,0.1],[0.5,0.5]]}]}"""
        val r = RecipeJson.read(old)
        assertEquals(Crop(0f, 0f, 1f, 0.5f), r.geometry.crop)
        assertEquals(90, r.geometry.rotation)
        assertEquals(Look.WARM, r.look)
        assertEquals(0.5f, r.lookStrength, 0f)
        assertEquals(0.3f, r.tone.saturation, 0f)
        assertEquals(1, r.marks.size)
        assertEquals(MarkKind.BOX, r.marks[0].kind)
    }

    @Test
    fun rejectsValuesOutsideTheEditor() {
        for (bad in listOf(
            """{"schema":2,"tone":{"exposure":9}}""",
            """{"schema":2,"geometry":{"crop":[0.9,0,0.1,1]}}""",
            """{"schema":2,"geometry":{"rotation":45}}""",
            """{"schema":2,"marks":[{"kind":"LASER","points":[[0,0]]}]}""",
            """{"schema":2,"marks":[{"kind":"CLONE","points":[[0,0]]}]}""",
            """{"schema":2,"geometry":{"quad":[[1,0],[0,0],[1,1],[0,1]]}}""",
            "not json", "[]", """{"schema":2,"tone":{"exposure":"loud"}}"""
        )) {
            try { RecipeJson.read(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun presetDropsTheLayersAndTheCrop() {
        val p = full.asPreset()
        assertTrue(p.marks.isEmpty())
        assertEquals(Crop.FULL, p.geometry.crop)
        assertEquals(full.tone, p.tone)
        assertFalse(p.geometry.quad != Quad.IDENTITY)
    }

    @Test
    fun jsonCodecHandlesEscapesNumbersAndNesting() {
        val v = jsonOf("s" to "a\"b\\c\n\u0001".json(), "n" to (-1.5e3).json(), "i" to 42.json(), "b" to true.json(),
            "l" to listOf(1.json(), JsonValue.Null, jsonOf()).json())
        val text = Json.write(v)
        val back = Json.parse(text)
        assertEquals(v, back)
        assertEquals("42", Json.write(42.json()))
        assertEquals(1e20, Json.parse("1e20").num()!!, 0.0)
    }
}
