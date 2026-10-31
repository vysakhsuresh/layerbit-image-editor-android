package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfPagesTest {

    /** The smallest thing [PdfPages.jpegFrame] reads as a JPEG: SOI, an APP0, a baseline frame header. */
    private fun fakeJpeg(w: Int, h: Int, components: Int = 3): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
        0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), components.toByte(), 1, 0x11, 0,
        0xFF.toByte(), 0xD9.toByte()
    )

    @Test
    fun a4TurnsToThePicture() {
        val portrait = PdfPages.page(Paper.A4, 1000, 1400)
        assertEquals(595.28f, portrait.width, 0.01f); assertEquals(841.89f, portrait.height, 0.01f)
        val landscape = PdfPages.page(Paper.LETTER, 1400, 1000)
        assertEquals(792f, landscape.width, 0.01f); assertEquals(612f, landscape.height, 0.01f)
    }

    @Test
    fun fitIsThePicturesOwnShape() {
        val p = PdfPages.page(Paper.FIT, 1000, 3000)
        assertEquals(595.28f, p.width, 0.01f); assertEquals(595.28f * 3, p.height, 0.05f)
        val place = PdfPages.place(Paper.FIT, p, 1000, 3000)
        assertEquals(0f, place.left, 0.01f); assertEquals(p.width, place.width, 0.01f); assertEquals(p.height, place.height, 0.05f)
    }

    @Test
    fun placeFitsInsideTheMarginsCentredAndKeepsTheShape() {
        val page = PdfPages.page(Paper.A4, 1000, 1000)
        val place = PdfPages.place(Paper.A4, page, 1000, 1000)
        assertEquals(page.width - 2 * PdfPages.MARGIN_PT, place.width, 0.01f)
        assertEquals(place.width, place.height, 0.01f)
        assertEquals(PdfPages.MARGIN_PT, place.left, 0.01f)
        assertEquals((page.height - place.height) / 2, place.top, 0.01f)
    }

    @Test
    fun pixelsShrinkToTheDpiAndNeverGrow() {
        val page = PdfPages.page(Paper.A4, 4000, 5600)
        val place = PdfPages.place(Paper.A4, page, 4000, 5600)
        val (w, h) = PdfPages.pixels(place, 4000, 5600, dpi = 200)
        // About 7.8 inches wide at 200 dpi.
        assertEquals((place.width / 72f * 200).toInt().toFloat(), w.toFloat(), 1f)
        assertEquals(w * 1.4f, h.toFloat(), 2f)
        assertEquals(500 to 700, PdfPages.pixels(place, 500, 700, dpi = 200))
    }

    @Test
    fun budgetCoversAnA4PageAt200Dpi() {
        val b = PdfPages.budget(Paper.A4, 200)
        assertTrue(b in 3_800_000L..3_900_000L)
    }

    @Test
    fun readsTheFrameHeader() {
        assertEquals(Triple(640, 480, 3), PdfPages.jpegFrame(fakeJpeg(640, 480)))
        assertEquals(Triple(10, 20, 1), PdfPages.jpegFrame(fakeJpeg(10, 20, 1)))
        assertNull(PdfPages.jpegFrame(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun writesOnePagePerPictureWithAWorkingCrossReference() {
        val out = ByteArrayOutputStream()
        val w = PdfPages.Writer(out)
        for ((pw, ph) in listOf(100 to 140, 140 to 100, 50 to 50)) {
            val page = PdfPages.page(Paper.A4, pw, ph)
            w.addPage(fakeJpeg(pw, ph), page, PdfPages.place(Paper.A4, page, pw, ph))
        }
        w.finish()
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertEquals(3, PdfPages.countPages(bytes))
        assertTrue(text.contains("/Count 3"))
        assertTrue(text.contains("/MediaBox [0 0 841.89 595.28]"))
        // Every offset in the table points at its object.
        val start = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref", start))
        val rows = text.substring(start).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(11, rows.size)
        for ((i, row) in rows.withIndex()) assertTrue(row, text.startsWith("${i + 1} 0 obj", row.substring(0, 10).toInt()))
    }

    @Test
    fun countsPagesNotThePageTree() {
        val t = "<< /Type /Pages /Count 2 >> << /Type /Page /Parent 2 0 R >> /Type /Type /Page>> /Type /Page"
        assertEquals(3, PdfPages.countPages(t.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(0, PdfPages.countPages(ByteArray(0)))
    }

    @Test
    fun numbersAreShortAndExact() {
        assertEquals("18", PdfPages.num(18f))
        assertEquals("595.28", PdfPages.num(595.28f))
        assertEquals("0.5", PdfPages.num(0.5f))
        assertEquals("12.05", PdfPages.num(12.05f))
    }
}
