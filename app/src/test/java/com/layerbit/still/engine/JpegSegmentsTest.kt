package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegSegmentsTest {

    /** A JPEG skeleton: real markers, nonsense payloads, and a picture made of [picture]. */
    private fun jpeg(exif: Boolean, gps: Boolean = false, icc: Boolean = false, comment: Boolean = false, picture: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray {
        val out = ByteArrayOutputStream()
        fun seg(marker: Int, payload: ByteArray) { out.write(0xFF); out.write(marker); val len = payload.size + 2; out.write(len shr 8); out.write(len and 0xFF); out.write(payload) }
        out.write(0xFF); out.write(0xD8)
        seg(0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))
        if (exif) {
            // TIFF header, little-endian, IFD0 with one entry: GPS pointer or a Make tag.
            val tiff = ByteArrayOutputStream()
            tiff.write("II".toByteArray()); tiff.write(byteArrayOf(0x2A, 0)); tiff.write(byteArrayOf(8, 0, 0, 0))
            tiff.write(byteArrayOf(1, 0)) // one entry
            val tag = if (gps) 0x8825 else 0x010F
            tiff.write(byteArrayOf((tag and 0xFF).toByte(), (tag shr 8).toByte(), 4, 0, 1, 0, 0, 0, 26, 0, 0, 0))
            tiff.write(byteArrayOf(0, 0, 0, 0))
            seg(0xE1, "Exif\u0000\u0000".toByteArray() + tiff.toByteArray())
        }
        if (icc) seg(0xE2, "ICC_PROFILE\u0000".toByteArray() + ByteArray(20))
        if (comment) seg(0xFE, "made by a phone".toByteArray())
        seg(0xDB, ByteArray(65))
        seg(0xC0, byteArrayOf(8, 0, 16, 0, 16, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))
        seg(0xC4, ByteArray(20))
        seg(0xDA, byteArrayOf(3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0))
        out.write(picture)
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    @Test
    fun walksSegmentsInOrder() {
        val segs = JpegSegments.segments(jpeg(exif = true, icc = true, comment = true))!!
        assertEquals(listOf("JFIF", "EXIF/XMP", "ICC profile", "Comment", "Quantisation", "Frame", "Huffman", "Picture"), segs.map { it.name })
    }

    @Test
    fun stripRemovesHiddenSegmentsAndKeepsThePictureByteForByte() {
        val picture = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1)
        val src = jpeg(exif = true, gps = true, icc = true, comment = true, picture = picture)
        assertTrue(JpegSegments.hasHiddenData(src))
        assertTrue(JpegSegments.hasGps(src))
        val out = JpegSegments.strip(src)!!
        assertTrue(out.size < src.size)
        val names = JpegSegments.segments(out)!!.map { it.name }
        assertEquals(listOf("JFIF", "ICC profile", "Quantisation", "Frame", "Huffman", "Picture"), names)
        assertFalse(JpegSegments.hasHiddenData(out))
        assertFalse(JpegSegments.hasGps(out))
        // The entropy-coded data after SOS is untouched.
        val sos = JpegSegments.segments(out)!!.last()
        val tail = out.copyOfRange(sos.offset + sos.length, out.size)
        assertArrayEquals(picture + byteArrayOf(0xFF.toByte(), 0xD9.toByte()), tail)
        // Idempotent: stripping a clean file changes nothing.
        assertArrayEquals(out, JpegSegments.strip(out))
    }

    @Test
    fun exifWithoutGpsIsReportedAsSuch() {
        val src = jpeg(exif = true, gps = false)
        assertNotNull(JpegSegments.exifSegment(src))
        assertFalse(JpegSegments.hasGps(src))
        assertTrue(JpegSegments.hasHiddenData(src))
    }

    @Test
    fun refusesWhatItCannotWalkSafely() {
        assertNull(JpegSegments.strip("not a jpeg".toByteArray()))
        val truncated = jpeg(exif = true).copyOf(20)
        assertNull(JpegSegments.strip(truncated))
        // A progressive frame (C2) is fine; an unusual one (C3) is refused.
        val weird = jpeg(exif = false).let { b -> val i = b.indexOfFrameMarker(); b[i] = 0xC3.toByte(); b }
        assertNull(JpegSegments.strip(weird))
    }

    private fun ByteArray.indexOfFrameMarker(): Int {
        for (i in 0 until size - 1) if ((this[i].toInt() and 0xFF) == 0xFF && (this[i + 1].toInt() and 0xFF) == 0xC0) return i + 1
        throw IllegalStateException()
    }

    @Test
    fun kindIsReadFromBytes() {
        assertEquals(JpegSegments.Kind.JPEG, JpegSegments.kindOf(jpeg(false)))
        assertEquals(JpegSegments.Kind.PNG, JpegSegments.kindOf(byteArrayOf(0x89.toByte()) + "PNG\r\n".toByteArray()))
        assertEquals(JpegSegments.Kind.WEBP, JpegSegments.kindOf("RIFF....WEBPVP8 ".toByteArray()))
        assertEquals(JpegSegments.Kind.HEIC, JpegSegments.kindOf("....ftypheic".toByteArray()))
        assertNull(JpegSegments.kindOf("hello".toByteArray()))
    }

    @Test
    fun pngTextChunksAreNoticed() {
        fun png(vararg chunks: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            for (c in chunks) { out.write(byteArrayOf(0, 0, 0, 2)); out.write(c.toByteArray()); out.write(byteArrayOf(1, 2)); out.write(ByteArray(4)) }
            out.write(byteArrayOf(0, 0, 0, 0)); out.write("IEND".toByteArray()); out.write(ByteArray(4))
            return out.toByteArray()
        }
        assertTrue(JpegSegments.pngHasHiddenChunks(png("IHDR", "tEXt", "IDAT")))
        assertFalse(JpegSegments.pngHasHiddenChunks(png("IHDR", "IDAT")))
    }
}
