package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TextStyleTest {

    /** A pretend face: every letter half an em, a space a quarter. */
    private val measure: (String) -> Float = { s -> s.sumOf { if (it == ' ') 0.25 else 0.5 }.toFloat() }

    @Test
    fun wrapsAtWordsKeepsTypedBreaksAndCutsLongWords() {
        assertEquals(listOf("one two", "three"), TextLayout.lines("one two three", 4f, measure))
        assertEquals(listOf("a", "", "b c"), TextLayout.lines("a\n\nb c", 10f, measure))
        // A word of 12 letters (6 ems) in a 2-em line is cut into four-letter pieces.
        assertEquals(listOf("abcd", "efgh", "ijkl"), TextLayout.lines("abcdefghijkl", 2f, measure))
        // Never an empty line from a cut and never less than one letter a line.
        assertEquals(listOf("a", "b"), TextLayout.lines("ab", 0.1f, measure))
    }

    @Test
    fun theSameLayoutAtAnyTextSize() {
        // The renderer measures at one fixed size and lays out in ems; only the wrap width, a
        // fraction of the canvas, enters from outside, and in ems it is the same at every size.
        val text = "Preview predicts export, at every size"
        val small = Geometry2D.Size(300, 200); val large = Geometry2D.Size(3000, 2000)
        val ems = { size: Geometry2D.Size -> TextLayout.wrapEms(size, TextLayout.textSizePx(0.05f, size)) }
        assertEquals(ems(small), ems(large), 1e-4f)
        val a = TextLayout.layout(text, TextStyle(boxOpacity = 1f), ems(small), 0.9f, 0.25f, measure)
        val b = TextLayout.layout(text, TextStyle(boxOpacity = 1f), ems(large), 0.9f, 0.25f, measure)
        assertEquals(a, b)
        assertTrue(a.lines.size > 1)
    }

    @Test
    fun linesAlignAndTheBlockIsCentredOnTheAnchor() {
        val lines = TextLayout.lines("aaaa\nbb", 100f, measure)
        fun xs(align: TextAlign) = TextLayout.layout("aaaa\nbb", TextStyle(align = align), 100f, 0.8f, 0.2f, measure).lines.map { it.x }
        assertEquals(2, lines.size)
        assertEquals(listOf(-1f, -1f), xs(TextAlign.LEFT))
        assertEquals(listOf(-1f, -0.5f), xs(TextAlign.CENTER))
        assertEquals(listOf(-1f, 0f), xs(TextAlign.RIGHT))
        val block = TextLayout.layout("aaaa\nbb", TextStyle(lineHeight = 1.5f, shadowColor = 0), 100f, 0.8f, 0.2f, measure)
        // Two lines one em tall, 1.5 em apart: 2.5 ems in all, centred.
        assertEquals(-0.45f, block.lines[0].baseline, 1e-5f)
        assertEquals(1.05f, block.lines[1].baseline, 1e-5f)
        assertEquals(-1.25f, block.bounds.top, 1e-5f)
        assertEquals(1.25f, block.bounds.bottom, 1e-5f)
    }

    @Test
    fun boxesPadTheBlockOrEachLineAndRoundNoMoreThanAPill() {
        val style = TextStyle(boxOpacity = 1f, boxPadding = 0.5f, boxRadius = 5f, shadowColor = 0)
        val one = TextLayout.layout("aaaa\nbb", style, 100f, 0.8f, 0.2f, measure)
        assertEquals(1, one.boxes.size)
        val box = one.boxes[0]
        assertEquals(-1.5f, box.left, 1e-5f); assertEquals(1.5f, box.right, 1e-5f)
        assertEquals(-1.5f, box.top, 1e-5f); assertEquals(1.5f, box.bottom, 1e-5f)
        assertEquals(1.5f, one.boxRadius, 1e-5f)
        val per = TextLayout.layout("aaaa\nbb", style.copy(boxPerLine = true), 100f, 0.8f, 0.2f, measure)
        assertEquals(2, per.boxes.size)
        assertEquals(per.lines[1].x - 0.5f, per.boxes[1].left, 1e-5f)
        assertEquals(per.lines[1].x + 1f + 0.5f, per.boxes[1].right, 1e-5f)
        // No box at zero opacity, and the bounds then hold only the text.
        assertTrue(TextLayout.layout("aa", TextStyle(shadowColor = 0), 100f, 0.8f, 0.2f, measure).boxes.isEmpty())
    }

    @Test
    fun curvedTextFollowsACircleWhoseRadiusComesFromTheSweep() {
        val text = "abcdefgh"
        val arch = TextLayout.layout(text, TextStyle(arc = 0.25f), 100f, 0.8f, 0.2f, measure)
        assertEquals(8, arch.glyphs.size)
        assertTrue(arch.lines.isEmpty())
        // Width 4 ems over a quarter turn: radius 4 / (pi / 2).
        val r = 4f / (Math.PI / 2).toFloat()
        // Every glyph is on one circle: the same distance from a common centre.
        val first = arch.glyphs.first(); val last = arch.glyphs.last(); val mid = arch.glyphs[3]
        assertEquals(-first.angle, last.angle, 1e-3f)
        assertTrue("arches up: the ends sit lower", first.y > mid.y && last.y > mid.y)
        val cy = arch.glyphs.map { g -> g.y + r * kotlin.math.cos(Math.toRadians(g.angle.toDouble())).toFloat() }
        val cx = arch.glyphs.map { g -> g.x - r * kotlin.math.sin(Math.toRadians(g.angle.toDouble())).toFloat() }
        for (i in cy.indices) { assertEquals(cy[0], cy[i], 1e-3f); assertEquals(cx[0], cx[i], 1e-3f) }
        val smile = TextLayout.layout(text, TextStyle(arc = -0.25f), 100f, 0.8f, 0.2f, measure)
        assertTrue("a smile lifts its ends", smile.glyphs.first().y < smile.glyphs[3].y)
        // The arc is centred on the anchor, and stays one line even if typed as two.
        assertEquals(0f, (arch.bounds.left + arch.bounds.right) / 2f, 1e-3f)
        assertEquals(9, TextLayout.layout("abcd\nefgh", TextStyle(arc = 0.2f), 100f, 0.8f, 0.2f, measure).glyphs.size)
        // A tiny arc is straight.
        assertTrue(TextLayout.layout(text, TextStyle(arc = 0.001f), 100f, 0.8f, 0.2f, measure).glyphs.isEmpty())
        // Glyph spacing along the curve keeps the measured widths.
        val gap = hypot(arch.glyphs[1].x - arch.glyphs[0].x, arch.glyphs[1].y - arch.glyphs[0].y)
        assertTrue(abs(gap - 0.5f) < 0.01f)
    }

    @Test
    fun clustersKeepAccentsAndEmojiTogether() {
        assertEquals(listOf("é", "a"), TextLayout.clusters("éa"))
        assertEquals(1, TextLayout.clusters("👍🏽").size)
        assertEquals(1, TextLayout.clusters("👩‍💻").size)
        assertEquals(3, TextLayout.clusters("abc").size)
    }

    @Test
    fun presetsAreValidDistinctAndFollowTheInk() {
        val ink = 0xFF5BA9E8.toInt()
        val styles = TextPreset.entries.map { it.apply(ink) }
        assertTrue(TextPreset.entries.size >= 12)
        assertTrue(styles.all { it.second.valid })
        assertEquals(styles.size, styles.toSet().size)
        assertEquals(ink to TextStyle.DEFAULT, TextPreset.CLASSIC.apply(ink))
        assertEquals(ink, TextPreset.BADGE.apply(ink).second.boxColor)
        assertEquals(TextPreset.contrastOn(ink), TextPreset.BADGE.apply(ink).first)
        assertEquals(TextPreset.BADGE, TextPreset.matching(ink, TextPreset.BADGE.apply(ink).first, TextPreset.BADGE.apply(ink).second))
        assertNull(TextPreset.matching(ink, ink, TextStyle(weight = 300)))
        // The ink survives a preset that hides it in a box or a glow, so presets never drift.
        for (p in TextPreset.entries) { val (c, st) = p.apply(ink); if (p != TextPreset.CAPTION && p != TextPreset.OUTLINE && p != TextPreset.TYPEWRITER) assertEquals(p.name, ink, TextPreset.inkOf(c, st)) }
        assertEquals(0xFF111111.toInt(), TextPreset.contrastOn(0xFFFFFFFF.toInt()))
        assertEquals(TextStyle.WHITE, TextPreset.contrastOn(0xFF111111.toInt()))
    }

    @Test
    fun stylesRoundTripAndOldRecipesKeepTheOldLook() {
        val style = TextStyle(
            font = TextFont.SCRIPT, weight = 600, italic = true, caps = true, align = TextAlign.RIGHT, letterSpacing = 0.1f, lineHeight = 1.3f,
            outline = 0.05f, outlineColor = 0xFF102030.toInt(), shadowBlur = 0.2f, shadowX = -0.1f, shadowY = 0.15f, shadowColor = 0x80FF0000.toInt(),
            boxColor = 0xFFF4EBD9.toInt(), boxOpacity = 0.7f, boxPadding = 0.4f, boxRadius = 0.25f, boxPerLine = true, arc = -0.3f
        )
        val r = Recipe(marks = listOf(Mark(MarkKind.TEXT, listOf(Pt(0.5f, 0.5f)), text = "Hi", style = style), Mark(MarkKind.TEXT, listOf(Pt(0.2f, 0.2f)), text = "plain")))
        val json = RecipeJson.write(r)
        assertEquals(r, RecipeJson.read(json))
        // A default style writes nothing, so an older app reads the same JSON it always did.
        assertFalse(RecipeJson.write(Recipe(marks = listOf(Mark(MarkKind.TEXT, listOf(Pt(0.2f, 0.2f)), text = "plain")))).contains("style"))
        // A recipe from before styles: its text is the default look.
        val old = """{"schema":2,"marks":[{"kind":"TEXT","points":[[0.5,0.5]],"color":-1,"width":0.05,"text":"Old"}]}"""
        assertEquals(TextStyle.DEFAULT, RecipeJson.read(old).marks.single().style)
        // A newer app's unknown face and out-of-range values read as something this app can draw.
        val odd = """{"schema":2,"marks":[{"kind":"TEXT","points":[[0.5,0.5]],"text":"x","style":{"font":"COMIC","weight":2000,"arc":9,"lineHeight":0}}]}"""
        val read = RecipeJson.read(odd).marks.single().style
        assertEquals(TextFont.SYSTEM, read.font)
        assertEquals(900, read.weight)
        assertEquals(1f, read.arc, 0f)
        assertEquals(0.5f, read.lineHeight, 0f)
        assertTrue(read.valid)
        assertEquals(style, RecipeJson.readTextStyle(Json.parse(Json.write(RecipeJson.textStyle(style)))))
    }
}
