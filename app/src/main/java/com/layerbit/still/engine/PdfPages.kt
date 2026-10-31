package com.layerbit.still.engine

import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The paper a page goes on. [FIT] is a page the picture's own shape, as wide as A4 is narrow. */
enum class Paper(val shortPt: Float, val longPt: Float) {
    A4(595.28f, 841.89f),
    LETTER(612f, 792f),
    FIT(595.28f, 0f)
}

/**
 * The arithmetic of a pages-to-PDF run, and the PDF itself.
 *
 * Every page is a JPEG drawn into a rectangle on a page measured in points (1/72 inch). The
 * JPEG's own bytes go into the file untouched (`/DCTDecode`), so the quality chosen is the
 * quality written and nothing is decoded and encoded a second time.
 */
object PdfPages {

    /** Pixels per inch a page is kept to: sharp for print and reading, small enough to send. */
    const val DPI = 200

    /** The white edge around a picture on A4 or Letter, in points: a quarter of an inch. */
    const val MARGIN_PT = 18f

    data class Page(val width: Float, val height: Float)

    /** Where the picture goes on its page, in points from the top left. */
    data class Place(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * The page for a picture [w] × [h] pixels: A4 or Letter turned to the picture's orientation,
     * or for [Paper.FIT] the picture's own shape with its shorter side as wide as A4.
     */
    fun page(paper: Paper, w: Int, h: Int): Page {
        val landscape = w > h
        if (paper == Paper.FIT) {
            val aspect = max(1, w).toFloat() / max(1, h)
            return if (landscape) Page(paper.shortPt * aspect, paper.shortPt) else Page(paper.shortPt, paper.shortPt / aspect)
        }
        return if (landscape) Page(paper.longPt, paper.shortPt) else Page(paper.shortPt, paper.longPt)
    }

    /** The picture fitted whole inside the page's margins and centred; edge to edge on [Paper.FIT]. */
    fun place(paper: Paper, page: Page, w: Int, h: Int): Place {
        val margin = if (paper == Paper.FIT) 0f else MARGIN_PT
        val boxW = page.width - 2 * margin
        val boxH = page.height - 2 * margin
        val k = min(boxW / max(1, w), boxH / max(1, h))
        val pw = w * k; val ph = h * k
        return Place((page.width - pw) / 2, (page.height - ph) / 2, pw, ph)
    }

    /**
     * The pixels a picture [w] × [h] needs to fill [place] at [dpi]: smaller when it has more,
     * never larger when it has fewer, and always the picture's own shape.
     */
    fun pixels(place: Place, w: Int, h: Int, dpi: Int = DPI): Pair<Int, Int> {
        val wantW = place.width / 72f * dpi
        val k = min(1f, wantW / max(1, w))
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }

    /** The most pixels any page on [paper] will be kept to, so a render can stop there. */
    fun budget(paper: Paper, dpi: Int = DPI): Long {
        val longPt = if (paper == Paper.FIT) paper.shortPt * 3 else paper.longPt
        return (paper.shortPt / 72.0 * dpi * (longPt / 72.0 * dpi)).toLong()
    }

    /** Width, height and colour components from a JPEG's frame header, or null when there is none. */
    fun jpegFrame(b: ByteArray): Triple<Int, Int, Int>? {
        if (b.size < 4 || u(b, 0) != 0xFF || u(b, 1) != 0xD8) return null
        var i = 2
        while (i + 4 <= b.size) {
            if (u(b, i) != 0xFF) { i++; continue }
            val marker = u(b, i + 1)
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            if (marker == 0xD9 || marker == 0xDA) return null
            val len = (u(b, i + 2) shl 8) or u(b, i + 3)
            val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (sof && i + 9 < b.size) {
                val h = (u(b, i + 5) shl 8) or u(b, i + 6)
                val w = (u(b, i + 7) shl 8) or u(b, i + 8)
                return Triple(w, h, u(b, i + 9))
            }
            i += 2 + len
        }
        return null
    }

    /** The pages in a PDF, counted from its page objects: read off the file that was written. */
    fun countPages(pdf: ByteArray): Int = countPages(pdf.inputStream())

    /**
     * The same, streamed, so a hundred-page file is never held whole: every "/Type /Page" not
     * followed by a letter (which would make it "/Pages", the tree, not a page).
     */
    fun countPages(input: InputStream): Int {
        val pattern = PAGE_MARK
        var matched = 0
        var pending = false
        var count = 0
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            for (k in 0 until n) {
                val c = buffer[k].toInt() and 0xFF
                if (pending) {
                    pending = false
                    if (!(c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code)) count++
                }
                // Knuth-Morris-Pratt: on a mismatch fall back to the longest prefix still matched.
                while (matched > 0 && c != (pattern[matched].toInt() and 0xFF)) matched = PAGE_FAIL[matched - 1]
                if (c == (pattern[matched].toInt() and 0xFF)) matched++
                if (matched == pattern.size) { pending = true; matched = PAGE_FAIL[matched - 1] }
            }
        }
        if (pending) count++
        return count
    }

    private val PAGE_MARK = "/Type /Page".toByteArray(Charsets.ISO_8859_1)
    private val PAGE_FAIL = IntArray(PAGE_MARK.size).also { f ->
        var k = 0
        for (i in 1 until PAGE_MARK.size) {
            while (k > 0 && PAGE_MARK[i] != PAGE_MARK[k]) k = f[k - 1]
            if (PAGE_MARK[i] == PAGE_MARK[k]) k++
            f[i] = k
        }
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** Points written with two decimals at most, as PDF readers expect: "595.28", "18". */
    internal fun num(v: Float): String {
        val r = (v * 100).roundToInt()
        val whole = r / 100; val frac = kotlin.math.abs(r % 100)
        return if (frac == 0) "$whole" else if (frac % 10 == 0) "$whole.${frac / 10}" else "$whole.${frac.toString().padStart(2, '0')}"
    }

    /**
     * A PDF written straight to [out] one page at a time, so only one page's JPEG is ever held.
     * Objects 1 and 2 are the catalogue and the page tree, written last with the count.
     */
    class Writer(private val out: OutputStream) {
        private var offset = 0L
        private val offsets = HashMap<Int, Long>()
        private val pages = ArrayList<Int>()
        private var next = 3
        private var finished = false

        init { raw("%PDF-1.4\n%âãÏÓ\n") }

        val pageCount: Int get() = pages.size

        /** Adds one page holding [jpeg] in [place] on [page]. */
        fun addPage(jpeg: ByteArray, page: Page, place: Place) {
            check(!finished)
            val (w, h, components) = jpegFrame(jpeg) ?: throw IllegalArgumentException("not a JPEG")
            val colour = when (components) { 1 -> "/DeviceGray"; 4 -> "/DeviceCMYK"; else -> "/DeviceRGB" }
            val image = next++; val content = next++; val pageObj = next++
            begin(image)
            raw("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace $colour /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
            bytes(jpeg)
            raw("\nendstream\nendobj\n")
            // PDF counts from the bottom left; the place is from the top left.
            val y = page.height - place.top - place.height
            val draw = "q ${num(place.width)} 0 0 ${num(place.height)} ${num(place.left)} ${num(y)} cm /Im0 Do Q\n"
            begin(content)
            raw("<< /Length ${draw.length} >>\nstream\n$draw\nendstream\nendobj\n")
            begin(pageObj)
            raw("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(page.width)} ${num(page.height)}] /Resources << /XObject << /Im0 $image 0 R >> >> /Contents $content 0 R >>\nendobj\n")
            pages += pageObj
        }

        /** The page tree, the catalogue, the cross-reference table and the trailer. Does not close [out]. */
        fun finish() {
            check(!finished); finished = true
            begin(2)
            raw("<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>\nendobj\n")
            begin(1)
            raw("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            val xref = offset
            val sb = StringBuilder("xref\n0 $next\n0000000000 65535 f \n")
            for (n in 1 until next) sb.append(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[n] ?: 0L))
            sb.append("trailer\n<< /Size $next /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            raw(sb.toString())
            out.flush()
        }

        private fun begin(n: Int) { offsets[n] = offset; raw("$n 0 obj\n") }
        private fun raw(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))
        private fun bytes(b: ByteArray) { out.write(b); offset += b.size }
    }
}
