package com.layerbit.still.engine

import java.io.ByteArrayOutputStream

/**
 * A JPEG is a sequence of marker segments followed by the entropy-coded picture. Everything
 * hidden in a photo - EXIF with its GPS fix and camera serial, XMP, IPTC, maker notes,
 * thumbnails, comments - lives in the segments, and the picture lives after them. So the
 * picture can be kept byte for byte while the segments go: no decode, no re-encode, no loss.
 *
 * That is the one thing the "remove location" promise should mean, and the reason this file
 * exists: every competitor's "strip metadata" re-encodes, which quietly costs quality every
 * time. Ported from the same arithmetic Sheaf uses for a photo under a size limit.
 */
object JpegSegments {

    data class Segment(val marker: Int, val offset: Int, val length: Int) {
        val name: String
            get() = when (marker) {
                0xE0 -> "JFIF"; 0xE1 -> "EXIF/XMP"; 0xE2 -> "ICC profile"; 0xED -> "IPTC"; 0xEE -> "Adobe"
                0xFE -> "Comment"; 0xDB -> "Quantisation"; 0xC4 -> "Huffman"; 0xDA -> "Picture"
                in 0xC0..0xCF -> "Frame"; else -> "APP${marker - 0xE0}"
            }
    }

    fun isJpeg(b: ByteArray): Boolean = b.size >= 3 && u(b, 0) == 0xFF && u(b, 1) == 0xD8 && u(b, 2) == 0xFF

    /** Every segment up to and including the start of the picture, or null if not a walkable JPEG. */
    fun segments(b: ByteArray): List<Segment>? {
        if (!isJpeg(b)) return null
        val out = ArrayList<Segment>()
        var i = 2
        while (i + 4 <= b.size) {
            if (u(b, i) != 0xFF) return null
            val marker = u(b, i + 1)
            if (marker == 0xFF) { i += 1; continue }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue } // standalone
            val len = (u(b, i + 2) shl 8) or u(b, i + 3)
            if (len < 2 || i + 2 + len > b.size) return null
            out += Segment(marker, i, len + 2)
            if (marker == 0xDA) return out
            i += len + 2
        }
        return null
    }

    /** Whether the file carries anything a stripped copy would leave out. */
    fun hasHiddenData(b: ByteArray): Boolean = segments(b)?.any { !keep(it, b) } ?: false

    /** The APP1 segment that holds EXIF, or null. */
    fun exifSegment(b: ByteArray): Segment? = segments(b)?.firstOrNull { it.marker == 0xE1 && ascii(b, it.offset + 4, 4) == "Exif" }

    /**
     * Whether the EXIF holds a GPS IFD pointer. Walks the TIFF header for tag 0x8825 only; the
     * full EXIF parse belongs to ExifInterface on the Android side.
     */
    fun hasGps(b: ByteArray): Boolean {
        val seg = exifSegment(b) ?: return false
        val tiff = seg.offset + 10 // FF E1 LL LL 'E' 'x' 'i' 'f' 0 0
        if (tiff + 8 > b.size) return false
        val little = ascii(b, tiff, 2) == "II"
        fun u16(at: Int) = if (at + 2 > b.size) -1 else if (little) u(b, at) or (u(b, at + 1) shl 8) else (u(b, at) shl 8) or u(b, at + 1)
        fun u32(at: Int): Long = if (at + 4 > b.size) -1 else if (little)
            (u(b, at).toLong() or (u(b, at + 1).toLong() shl 8) or (u(b, at + 2).toLong() shl 16) or (u(b, at + 3).toLong() shl 24))
        else ((u(b, at).toLong() shl 24) or (u(b, at + 1).toLong() shl 16) or (u(b, at + 2).toLong() shl 8) or u(b, at + 3).toLong())
        val ifd0 = u32(tiff + 4)
        if (ifd0 < 0 || tiff + ifd0 + 2 > b.size) return false
        val base = (tiff + ifd0).toInt()
        val count = u16(base)
        if (count < 0 || count > 500) return false
        for (k in 0 until count) {
            val entry = base + 2 + k * 12
            if (entry + 12 > b.size || entry + 12 > seg.offset + seg.length) return false
            if (u16(entry) == 0x8825) return true
        }
        return false
    }

    /**
     * The same picture with its hidden segments gone: EXIF and XMP, IPTC, comments, maker data,
     * every APPn but the two that change how the picture looks (JFIF and the ICC profile) and
     * Adobe's colour marker. Null when the file cannot be walked safely - an unusual frame,
     * CMYK, arithmetic coding - so the caller re-encodes instead.
     */
    fun strip(b: ByteArray): ByteArray? {
        val segs = segments(b) ?: return null
        val out = ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        var sawFrame = false
        for (s in segs) {
            if (s.marker in 0xC0..0xCF && s.marker != 0xC4 && s.marker != 0xC8 && s.marker != 0xCC) {
                if (s.marker > 0xC2) return null
                val components = u(b, s.offset + 9)
                if (components != 1 && components != 3) return null
                sawFrame = true
            }
            if (s.marker == 0xDA) {
                if (!sawFrame) return null
                out.write(b, s.offset, b.size - s.offset)
                return out.toByteArray()
            }
            if (keep(s, b)) out.write(b, s.offset, s.length)
        }
        return null
    }

    private fun keep(s: Segment, b: ByteArray): Boolean = when (s.marker) {
        0xE1, in 0xE3..0xED, 0xEF, 0xFE -> false
        0xE2 -> ascii(b, s.offset + 4, 11) == "ICC_PROFILE"
        else -> true
    }

    private fun u(b: ByteArray, i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
    private fun ascii(b: ByteArray, from: Int, len: Int) = if (b.size >= from + len && from >= 0) String(b, from, len, Charsets.US_ASCII) else ""

    /** What the picture is, from its first bytes rather than its name. */
    enum class Kind(val mime: String, val extension: String) {
        JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), GIF("image/gif", "gif"), WEBP("image/webp", "webp"),
        BMP("image/bmp", "bmp"), HEIC("image/heic", "heic"), AVIF("image/avif", "avif")
    }

    fun kindOf(b: ByteArray): Kind? {
        fun at(i: Int) = u(b, i)
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> Kind.JPEG
            at(0) == 0x89 && ascii(b, 1, 3) == "PNG" -> Kind.PNG
            ascii(b, 0, 4) == "GIF8" -> Kind.GIF
            ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP" -> Kind.WEBP
            ascii(b, 0, 2) == "BM" -> Kind.BMP
            ascii(b, 4, 4) == "ftyp" && ascii(b, 8, 4) in setOf("avif", "avis") -> Kind.AVIF
            ascii(b, 4, 4) == "ftyp" && ascii(b, 8, 4) in setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1") -> Kind.HEIC
            else -> null
        }
    }

    /** Whether a PNG carries text or EXIF chunks; a re-encode through the platform drops them. */
    fun pngHasHiddenChunks(b: ByteArray): Boolean {
        if (kindOf(b) != Kind.PNG) return false
        var i = 8
        while (i + 8 <= b.size) {
            val len = ((u(b, i).toLong() shl 24) or (u(b, i + 1).toLong() shl 16) or (u(b, i + 2).toLong() shl 8) or u(b, i + 3).toLong())
            val type = ascii(b, i + 4, 4)
            if (type in setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")) return true
            if (type == "IEND" || len < 0) return false
            i += (12 + len).toInt().coerceAtLeast(12)
        }
        return false
    }
}
