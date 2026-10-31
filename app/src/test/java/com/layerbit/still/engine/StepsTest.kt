package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsTest {

    private val edited = Recipe(
        geometry = Geometry(crop = Geometry2D.aspectCrop(Geometry2D.rotatedBounds(4000, 3000, 1.5f), 4f / 5f), straighten = 1.5f),
        tone = Tone(exposure = 0.3f, shadows = 0.25f, temperature = 0.1f, sharpness = 0.4f),
        look = Look.FILM, lookStrength = 0.8f,
        marks = listOf(Mark(MarkKind.HEAL, listOf(Pt(0.2f, 0.2f)), source = Pt(0.3f, 0.3f)), Mark(MarkKind.TEXT, listOf(Pt(0.5f, 0.5f)), text = "hi")),
        expand = Expand(top = 0.2f, bottom = 0.2f),
        frame = Frame(border = 0.02f)
    )

    @Test
    fun aRecipeReadsAsItsEditsInOrder() {
        val steps = Steps.describe(edited, Geometry2D.Size(4000, 3000))
        assertEquals(
            listOf(StepKind.STRAIGHTEN, StepKind.CROP, StepKind.EXPAND, StepKind.LIGHT, StepKind.COLOUR, StepKind.LOOK, StepKind.DETAIL, StepKind.MARK, StepKind.MARK, StepKind.FRAME),
            steps.map { it.key.kind }
        )
        assertEquals("4:5", steps[1].label)
        assertEquals(listOf("exposure" to 0.3f, "shadows" to 25f), steps[3].values)
        assertEquals("FILM", steps[5].label)
        assertEquals(listOf("strength" to 80f), steps[5].values)
        assertEquals(listOf("top" to 20f, "bottom" to 20f), steps[2].values)
        assertEquals(StepKey(StepKind.MARK, 1), steps[8].key)
        assertEquals("TEXT", steps[8].label)
        assertTrue(Steps.describe(Recipe()).isEmpty())
    }

    @Test
    fun takingAStepOutResetsOnlyThatPart() {
        val noLight = Steps.without(edited, StepKey(StepKind.LIGHT))
        assertEquals(0f, noLight.tone.exposure); assertEquals(0f, noLight.tone.shadows)
        assertEquals(0.1f, noLight.tone.temperature); assertEquals(0.4f, noLight.tone.sharpness)
        assertEquals(edited.geometry, noLight.geometry); assertEquals(edited.look, noLight.look)

        val noHeal = Steps.without(edited, StepKey(StepKind.MARK, 0))
        assertEquals(listOf(MarkKind.TEXT), noHeal.marks.map { it.kind })

        // Every step out leaves the untouched recipe.
        val all = Steps.describe(edited, Geometry2D.Size(4000, 3000)).map { it.key }.toSet()
        assertEquals(Recipe(), Steps.without(edited, all))
        for (k in all) assertTrue("$k", Steps.without(edited, k).valid)
        // A step that is out no longer lists.
        assertTrue(Steps.describe(Steps.without(edited, StepKey(StepKind.EXPAND))).none { it.key.kind == StepKind.EXPAND })
    }

    @Test
    fun hiddenLayersKeepTheirPlaceWhenAnEarlierOneIsDeleted() {
        val hidden = setOf(StepKey(StepKind.MARK, 2), StepKey(StepKind.LIGHT), StepKey(StepKind.MARK, 0))
        assertEquals(setOf(StepKey(StepKind.MARK, 1), StepKey(StepKind.LIGHT)), Steps.afterDelete(hidden, StepKey(StepKind.MARK, 0)))
        assertEquals(setOf(StepKey(StepKind.MARK, 2), StepKey(StepKind.MARK, 0)), Steps.afterDelete(hidden, StepKey(StepKind.LIGHT)))
    }

    @Test
    fun pastedEditsKeepTheTargetsOwnPicture() {
        val target = Recipe(
            geometry = Geometry(crop = Crop(0.2f, 0.2f, 0.8f, 0.8f)),
            marks = listOf(Mark(MarkKind.ARROW, listOf(Pt(0.1f, 0.1f), Pt(0.4f, 0.4f)))),
            tone = Tone(exposure = -1f),
            expand = Expand(left = 0.5f)
        )
        val pasted = Steps.paste(target, edited.asPreset())
        assertEquals(edited.tone, pasted.tone)
        assertEquals(Look.FILM, pasted.look)
        assertEquals(edited.frame, pasted.frame)
        assertEquals(target.geometry, pasted.geometry)
        assertEquals(target.marks, pasted.marks)
        assertEquals(target.expand, pasted.expand)
    }

    @Test
    fun ratiosAreNamedWhenPeopleWouldNameThem() {
        assertEquals("16:9", Steps.ratioName(Geometry2D.Size(1920, 1080)))
        assertEquals("1:1", Steps.ratioName(Geometry2D.Size(500, 501)))
        assertEquals("1.30:1", Steps.ratioName(Geometry2D.Size(1300, 1000)))
    }
}
