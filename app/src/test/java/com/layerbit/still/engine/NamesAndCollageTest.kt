package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesAndCollageTest {

    @Test
    fun namesCannotEscapeAFolder() {
        assertFalse(Names.safe("../../etc/passwd").contains('/'))
        assertEquals("image", Names.safe(".."))
        assertEquals("IMG_2031 (1)", Names.safe("IMG_2031 (1).jpg".substringBeforeLast('.')))
        assertEquals("image", Names.stem(".jpg"))
        assertEquals("holiday", Names.stem("holiday.HEIC"))
    }

    @Test
    fun patternsFillTokensAndStayUnique() {
        assertEquals("beach-web", Names.pattern("{name}-web", "beach.jpg", 3, 800, 600, "2026-10-27"))
        assertEquals("800x600-3", Names.pattern("{w}x{h}", "beach.jpg", 3, 800, 600, "2026-10-27"))
        assertEquals("2026-10-27-7", Names.pattern("{date}", "x.png", 7, 1, 1, "2026-10-27"))
        assertEquals("beach", Names.pattern("", "beach.jpg", 1, 1, 1, ""))
    }

    @Test
    fun everyLayoutPlacesEveryPictureInsideTheCanvas() {
        for (layout in Collage.Layout.entries) {
            for (n in 1..9) {
                val aspects = List(n) { if (it % 2 == 0) 1.5f else 0.75f }
                val plan = Collage.plan(layout, aspects)
                assertEquals(n, plan.cells.size)
                assertTrue(plan.canvasAspect > 0f && plan.canvasAspect.isFinite())
                for (c in plan.cells) {
                    assertTrue("$layout $n: $c", c.left >= -1e-4f && c.top >= -1e-4f && c.left + c.width <= 1.0001f && c.top + c.height <= 1.0001f)
                    assertTrue(c.width > 0f && c.height > 0f)
                }
                // No two cells overlap.
                for (i in plan.cells.indices) for (j in i + 1 until plan.cells.size) {
                    val a = plan.cells[i]; val b = plan.cells[j]
                    val overlap = a.left < b.left + b.width - 1e-4f && b.left < a.left + a.width - 1e-4f && a.top < b.top + b.height - 1e-4f && b.top < a.top + a.height - 1e-4f
                    assertFalse("$layout $n: $i overlaps $j", overlap)
                }
            }
        }
    }

    @Test
    fun canvasStaysInsideTheBudget() {
        val plan = Collage.plan(Collage.Layout.GRID, listOf(1f, 1f, 1f))
        val size = Collage.canvas(plan, 2_000_000)
        assertTrue(size.pixels <= 2_000_000)
        assertEquals(plan.canvasAspect, size.aspect, 0.02f)
    }
}
