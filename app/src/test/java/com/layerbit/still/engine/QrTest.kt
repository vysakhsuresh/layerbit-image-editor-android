package com.layerbit.still.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class QrTest {

    @Test
    fun reedSolomonMatchesTheStandardsWorkedExample() {
        // "HELLO WORLD", version 1-M: the data codewords and the ten EC codewords every QR tutorial prints.
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        assertArrayEquals(intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), Qr.reedSolomon(data, 10))
    }

    @Test
    fun formatAndVersionInformationMatchTheTables() {
        assertEquals(0b101010000010010, Qr.formatBits(0))
        assertEquals(0b101111001111100, Qr.formatBits(2))
        assertEquals(0b000111110010010100, Qr.versionBits(7))
        assertEquals(0b001010010011010011, Qr.versionBits(10))
    }

    @Test
    fun codewordCountsAgreeWithTheBlockTable() {
        val total = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655, 733, 815, 901, 991, 1085)
        for (v in 1..Qr.MAX_VERSION) assertEquals("v$v", total[v - 1], Qr.totalCodewords(v))
        assertEquals(14, Qr.capacity(1))
        assertEquals(412, Qr.capacity(15))
        assertArrayEquals(intArrayOf(6, 26, 48, 70), Qr.alignmentPositions(15))
        assertArrayEquals(intArrayOf(6, 22, 38), Qr.alignmentPositions(7))
    }

    @Test
    fun matchesAnIndependentEncoderModuleForModule() {
        // "still" at version 1-M with mask 3, as segno 1.6.6 draws it (with the standard's padding;
        // segno adds one extra zero byte after an aligned terminator, which readers ignore).
        val expected = listOf(
            "111111101011101111111", "100000101000001000001", "101110100010101011101", "101110101010101011101",
            "101110100101001011101", "100000100101101000001", "111111101010101111111", "000000001101100000000",
            "101101110011101001011", "000000010011111001111", "111100111101000001011", "011010001111001111001",
            "110001110000100100000", "000000001011001001110", "111111101111100101000", "100000101110000111110",
            "101110100100111111111", "101110101111001011110", "101110101010101100100", "100000100010010000001",
            "111111101100010100100"
        )
        val m = Qr.encode("still".toByteArray(), forceMask = 3)
        assertEquals(expected, (0 until m.size).map { y -> (0 until m.size).joinToString("") { x -> if (m[x, y]) "1" else "0" } })
    }

    @Test
    fun everyVersionRoundTrips() {
        for (v in 1..Qr.MAX_VERSION) {
            val n = Qr.capacity(v)
            val payload = ByteArray(n) { ((it * 37 + v) and 0xFF).toByte() }
            val m = Qr.encode(payload)
            assertEquals("v$v", v, m.version)
            assertArrayEquals("v$v", payload, Qr.decode(m))
        }
    }

    @Test
    fun finderAndTimingPatternsAreWhereReadersLook() {
        val m = Qr.encode("still".toByteArray())
        assertEquals(21, m.size)
        for (i in 0 until 7) { assertTrue(m[i, 0]); assertTrue(m[0, i]); assertTrue(m[m.size - 1, i]) }
        assertTrue(m[3, 3]); assertTrue(!m[1, 1]); assertTrue(!m[7, 7])
        for (i in 8 until m.size - 8) assertEquals(i % 2 == 0, m[i, 6])
        assertTrue(m[8, m.size - 8])
    }

    @Test
    fun damagedCodewordsAreCorrected() {
        val payload = ByteArray(200) { (it * 13).toByte() }
        val m = Qr.encode(payload)
        val dark = m.dark.copyOf()
        // Scratch a stripe through the data area: a few codewords in several blocks.
        for (x in 20 until 28) for (y in 30 until 33) dark[y * m.size + x] = !dark[y * m.size + x]
        assertArrayEquals(payload, Qr.decode(Qr.Matrix(m.size, dark)))
    }

    @Test
    fun tooMuchDamageIsRefusedNotMisread() {
        val payload = ByteArray(40) { it.toByte() }
        val m = Qr.encode(payload)
        val dark = m.dark.copyOf()
        val function = Qr.functionMask(m.version)
        var flipped = 0
        for (i in dark.indices) if (!function[i] && i % 3 == 0) { dark[i] = !dark[i]; flipped++ }
        assertTrue(flipped > 100)
        assertNull(Qr.decode(Qr.Matrix(m.size, dark)))
    }

    @Test
    fun reedSolomonCorrectsUpToHalfItsLength() {
        val data = IntArray(30) { (it * 7 + 3) and 0xFF }
        val ec = Qr.reedSolomon(data, 16)
        val block = data + ec
        val broken = block.copyOf()
        for (i in listOf(0, 5, 11, 17, 29, 33, 40, 45)) broken[i] = broken[i] xor 0x5A
        assertTrue(Qr.correct(broken, 16))
        assertArrayEquals(block, broken)
    }

    @Test
    fun aScreenshotOfTheCodeReadsBack() {
        val payload = "STILL1.".toByteArray() + ByteArray(180) { (it * 11 + 1).toByte() }
        val m = Qr.encode(payload)
        val (px, w, h) = render(m, scale = 6, quiet = 4)
        assertArrayEquals(payload, QrScan.read(px, w, h))
    }

    @Test
    fun aTurnedNoisyPhotoReadsBack() {
        val payload = ByteArray(120) { (it * 29).toByte() }
        val m = Qr.encode(payload)
        val (px, w, h) = render(m, scale = 7, quiet = 6, degrees = 17.0, noise = 40, dim = true)
        assertArrayEquals(payload, QrScan.read(px, w, h))
    }

    @Test
    fun aMirroredCodeReadsBack() {
        val payload = "a mirrored look".toByteArray()
        val m = Qr.encode(payload)
        val mirrored = Qr.Matrix(m.size, BooleanArray(m.size * m.size) { i -> m.dark[(i / m.size) * m.size + (m.size - 1 - i % m.size)] })
        val (px, w, h) = render(mirrored, scale = 5, quiet = 4)
        assertArrayEquals(payload, QrScan.read(px, w, h))
    }

    @Test
    fun aPictureWithNoCodeReadsNothing() {
        val w = 200; val h = 150
        val px = IntArray(w * h) { i -> val v = (i % w + i / w) % 256; Argb.pack(255, v, v, v) }
        assertNull(QrScan.read(px, w, h))
    }

    @Test
    fun theLargestLookFitsAndReads() {
        val payload = ByteArray(Qr.capacity(15)) { (it * 3).toByte() }
        val m = Qr.encode(payload)
        assertEquals(15, m.version)
        val (px, w, h) = render(m, scale = 4, quiet = 4)
        assertNotNull(QrScan.read(px, w, h))
    }

    /** The matrix drawn as a picture: [scale] pixels a module, optionally turned, noisy and unevenly lit. */
    private fun render(m: Qr.Matrix, scale: Int, quiet: Int, degrees: Double = 0.0, noise: Int = 0, dim: Boolean = false): Triple<IntArray, Int, Int> {
        val side = (m.size + 2 * quiet) * scale
        val pad = if (degrees != 0.0) side / 3 else 0
        val w = side + 2 * pad; val h = side + 2 * pad
        val a = Math.toRadians(degrees); val c = cos(a); val s = sin(a)
        val cx = w / 2.0; val cy = h / 2.0
        var seed = 12345L
        return Triple(IntArray(w * h) { i ->
            val x = i % w + 0.5 - cx; val y = i / w + 0.5 - cy
            // Inverse turn into the code's own frame.
            val u = (c * x + s * y + side / 2.0) / scale - quiet
            val v = (-s * x + c * y + side / 2.0) / scale - quiet
            val mx = Math.floor(u).toInt(); val my = Math.floor(v).toInt()
            val darkHere = mx in 0 until m.size && my in 0 until m.size && m[mx, my]
            var lum = if (darkHere) 30 else 235
            if (dim) lum = (lum * (0.6 + 0.4 * (i % w) / w)).roundToInt()
            if (noise > 0) { seed = seed * 6364136223846793005L + 1442695040888963407L; lum += ((seed ushr 33) % (2 * noise + 1)).toInt() - noise }
            lum = lum.coerceIn(0, 255)
            Argb.pack(255, lum, lum, lum)
        }, w, h)
    }
}
