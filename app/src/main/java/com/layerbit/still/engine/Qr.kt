package com.layerbit.still.engine

/**
 * QR codes, written and read with no library: byte mode, error correction level M, versions 1
 * to [MAX_VERSION]. That is all a shared look needs - a few hundred bytes that survive a
 * screenshot, a phone photo of a screen, or a print - and it keeps the dependency list short.
 *
 * The symbol follows ISO/IEC 18004: finder, timing and alignment patterns, format and version
 * information with their BCH codes, Reed-Solomon over GF(256) with the 0x11D polynomial, blocks
 * interleaved, the zig-zag placement and the eight masks with the standard penalty rules. Any
 * phone camera reads what [encode] makes; [decode] reads a module grid back, correcting up to
 * the code's capacity of wrong codewords, and [QrScan] finds that grid in a picture.
 */
object Qr {

    const val MAX_VERSION = 20

    /** A square of modules; true is dark. Indexed [y * size + x]. */
    class Matrix(val size: Int, val dark: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
        val version: Int get() = (size - 17) / 4
    }

    // ------------------------------------------------------------------ tables (level M)

    /** Per version 1..20: EC codewords per block, then (blocks, data codewords) for group 1 and group 2. */
    private val BLOCKS_M = arrayOf(
        intArrayOf(10, 1, 16, 0, 0), intArrayOf(16, 1, 28, 0, 0), intArrayOf(26, 1, 44, 0, 0), intArrayOf(18, 2, 32, 0, 0),
        intArrayOf(24, 2, 43, 0, 0), intArrayOf(16, 4, 27, 0, 0), intArrayOf(18, 4, 31, 0, 0), intArrayOf(22, 2, 38, 2, 39),
        intArrayOf(22, 3, 36, 2, 37), intArrayOf(26, 4, 43, 1, 44), intArrayOf(30, 1, 50, 4, 51), intArrayOf(22, 6, 36, 2, 37),
        intArrayOf(22, 8, 37, 1, 38), intArrayOf(24, 4, 40, 5, 41), intArrayOf(24, 5, 41, 5, 42), intArrayOf(28, 7, 45, 3, 46),
        intArrayOf(28, 10, 46, 1, 47), intArrayOf(26, 9, 43, 4, 44), intArrayOf(26, 3, 44, 11, 45), intArrayOf(26, 3, 41, 13, 42)
    )

    /** The data codewords a version holds at level M. */
    fun dataCodewords(version: Int): Int = BLOCKS_M[version - 1].let { it[1] * it[2] + it[3] * it[4] }

    /** Every codeword a version holds, data and error correction, from its module count. */
    fun totalCodewords(version: Int): Int {
        var modules = (16 * version + 128) * version + 64
        if (version >= 2) {
            val align = version / 7 + 2
            modules -= (25 * align - 10) * align - 55
            if (version >= 7) modules -= 36
        }
        return modules / 8
    }

    /** The longest byte payload a version carries at level M. */
    fun capacity(version: Int): Int {
        val countBits = if (version < 10) 8 else 16
        return (dataCodewords(version) * 8 - 4 - countBits) / 8
    }

    /** The smallest version that holds [bytes] bytes, or null when even [MAX_VERSION] cannot. */
    fun versionFor(bytes: Int): Int? = (1..MAX_VERSION).firstOrNull { capacity(it) >= bytes }

    fun alignmentPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val size = version * 4 + 17
        val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
        val out = IntArray(count)
        out[0] = 6
        var pos = size - 7
        for (i in count - 1 downTo 1) { out[i] = pos; pos -= step }
        return out
    }

    /** The 15 format bits for level M and [mask], BCH-protected and XOR-masked as the standard says. */
    fun formatBits(mask: Int): Int {
        val data = (0 shl 3) or mask // level M is 00
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        return ((data shl 10) or rem) xor 0x5412
    }

    /** The 18 version bits, BCH-protected, for versions 7 and up. */
    fun versionBits(version: Int): Int {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        return (version shl 12) or rem
    }

    // ------------------------------------------------------------------ GF(256) and Reed-Solomon

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x; LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    private fun div(a: Int, b: Int): Int { require(b != 0); return if (a == 0) 0 else EXP[(LOG[a] + 255 - LOG[b]) % 255] }
    private fun inv(a: Int): Int = EXP[255 - LOG[a]]

    /** The generator polynomial of [degree], highest power first, leading 1 dropped. */
    private fun generator(degree: Int): IntArray {
        val g = IntArray(degree); g[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                g[j] = mul(g[j], root)
                if (j + 1 < degree) g[j] = g[j] xor g[j + 1]
            }
            root = mul(root, 0x02)
        }
        return g
    }

    /** The error-correction codewords for [data]. */
    fun reedSolomon(data: IntArray, degree: Int): IntArray {
        val g = generator(degree)
        val r = IntArray(degree)
        for (b in data) {
            val factor = b xor r[0]
            System.arraycopy(r, 1, r, 0, degree - 1); r[degree - 1] = 0
            for (i in 0 until degree) r[i] = r[i] xor mul(g[i], factor)
        }
        return r
    }

    /**
     * Corrects [block] (data then EC codewords) in place; false when the errors outnumber what
     * [ecLength] codewords can fix. Berlekamp-Massey finds the error locator, a Chien search its
     * roots and Forney the magnitudes, with the generator's first root at alpha^0.
     */
    fun correct(block: IntArray, ecLength: Int): Boolean {
        val n = block.size
        val syn = IntArray(ecLength)
        var clean = true
        for (j in 0 until ecLength) {
            var s = 0
            val a = EXP[j]
            for (c in block) s = mul(s, a) xor c
            syn[j] = s
            if (s != 0) clean = false
        }
        if (clean) return true
        // Berlekamp-Massey; polynomials lowest power first.
        var lambda = IntArray(ecLength + 1).also { it[0] = 1 }
        var prev = IntArray(ecLength + 1).also { it[0] = 1 }
        var l = 0; var m = 1; var bScale = 1
        for (k in 0 until ecLength) {
            var d = syn[k]
            for (i in 1..l) d = d xor mul(lambda[i], syn[k - i])
            if (d == 0) { m++; continue }
            val coef = div(d, bScale)
            val next = lambda.copyOf()
            for (i in 0..ecLength - m) next[i + m] = next[i + m] xor mul(coef, prev[i])
            if (2 * l <= k) { l = k + 1 - l; prev = lambda; bScale = d; m = 1 } else m++
            lambda = next
        }
        if (2 * l > ecLength) return false
        // Chien search: a root at alpha^-p means an error at power p, byte index n-1-p.
        val positions = ArrayList<Int>()
        for (p in 0 until n) {
            val xinv = EXP[(255 - p % 255) % 255]
            var v = 0
            for (i in l downTo 0) v = mul(v, xinv) xor lambda[i]
            if (v == 0) positions.add(p)
        }
        if (positions.size != l) return false
        // Omega = S(x) * Lambda(x) mod x^ecLength.
        val omega = IntArray(ecLength)
        for (i in 0 until ecLength) {
            var v = 0
            for (j in 0..minOf(i, l)) v = v xor mul(syn[i - j], lambda[j])
            omega[i] = v
        }
        for (p in positions) {
            val x = EXP[p % 255]
            val xinv = inv(x)
            var om = 0
            for (i in ecLength - 1 downTo 0) om = mul(om, xinv) xor omega[i]
            // Lambda'(x): the odd terms, each dropping a power.
            var dl = 0
            var i = 1
            var pow = 1
            while (i <= l) {
                dl = dl xor mul(lambda[i], pow)
                pow = mul(pow, mul(xinv, xinv))
                i += 2
            }
            if (dl == 0) return false
            val magnitude = mul(x, div(om, dl))
            block[n - 1 - p] = block[n - 1 - p] xor magnitude
        }
        // Whatever was found must leave a codeword.
        for (j in 0 until ecLength) {
            var s = 0
            val a = EXP[j]
            for (c in block) s = mul(s, a) xor c
            if (s != 0) return false
        }
        return true
    }

    // ------------------------------------------------------------------ encode

    /**
     * [payload] as a QR code at level M in the smallest version that holds it, with the mask the
     * penalty rules prefer (or [forceMask]).
     * @throws IllegalArgumentException when it does not fit version [MAX_VERSION].
     */
    fun encode(payload: ByteArray, minVersion: Int = 1, forceMask: Int = -1): Matrix {
        val version = (maxOf(1, minVersion)..MAX_VERSION).firstOrNull { capacity(it) >= payload.size }
            ?: throw IllegalArgumentException("Too long for a QR code: ${payload.size} bytes")
        val codewords = interleave(version, dataStream(payload, version))
        val size = version * 4 + 17
        val function = functionMask(version)
        val base = BooleanArray(size * size)
        drawFunction(version, base)
        place(size, function, codewords, base)
        val masks = if (forceMask in 0..7) listOf(forceMask) else (0..7).toList()
        var best: BooleanArray? = null
        var bestScore = Int.MAX_VALUE
        for (mask in masks) {
            val m = base.copyOf()
            applyMask(size, function, m, mask)
            drawFormat(size, m, formatBits(mask))
            val score = penalty(size, m)
            if (score < bestScore) { bestScore = score; best = m }
        }
        return Matrix(size, best!!)
    }

    private fun dataStream(payload: ByteArray, version: Int): IntArray {
        val capacityBits = dataCodewords(version) * 8
        val bits = BitWriter()
        bits.put(0b0100, 4)
        bits.put(payload.size, if (version < 10) 8 else 16)
        for (b in payload) bits.put(b.toInt() and 0xFF, 8)
        bits.put(0, minOf(4, capacityBits - bits.length))
        bits.put(0, (8 - bits.length % 8) % 8)
        val out = IntArray(dataCodewords(version))
        val bytes = bits.bytes()
        for (i in out.indices) out[i] = if (i < bytes.size) bytes[i] else if ((i - bytes.size) % 2 == 0) 0xEC else 0x11
        return out
    }

    private class BitWriter {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size
        fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 == 1) }
        fun bytes(): IntArray = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or (if (bits[i * 8 + k]) 1 else 0) } }
    }

    private class Block(val data: IntArray, val ec: IntArray)

    private fun blocks(version: Int, data: IntArray): List<Block> {
        val t = BLOCKS_M[version - 1]
        val out = ArrayList<Block>()
        var at = 0
        repeat(t[1]) { val d = data.copyOfRange(at, at + t[2]); at += t[2]; out.add(Block(d, reedSolomon(d, t[0]))) }
        repeat(t[3]) { val d = data.copyOfRange(at, at + t[4]); at += t[4]; out.add(Block(d, reedSolomon(d, t[0]))) }
        return out
    }

    private fun interleave(version: Int, data: IntArray): IntArray {
        val bs = blocks(version, data)
        val out = ArrayList<Int>(totalCodewords(version))
        val longest = bs.maxOf { it.data.size }
        for (i in 0 until longest) for (b in bs) if (i < b.data.size) out.add(b.data[i])
        for (i in 0 until bs[0].ec.size) for (b in bs) out.add(b.ec[i])
        return out.toIntArray()
    }

    /** Which modules are patterns or format and version information, not data. */
    fun functionMask(version: Int): BooleanArray {
        val size = version * 4 + 17
        val f = BooleanArray(size * size)
        fun mark(x: Int, y: Int) { if (x in 0 until size && y in 0 until size) f[y * size + x] = true }
        for (i in 0 until size) { mark(6, i); mark(i, 6) }
        for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) for (dy in -4..4) for (dx in -4..4) mark(cx + dx, cy + dy)
        val al = alignmentPositions(version)
        for (i in al.indices) for (j in al.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == al.size - 1) || (i == al.size - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) mark(al[i] + dx, al[j] + dy)
        }
        // Format areas and the dark module.
        for (i in 0..8) { mark(8, i); mark(i, 8) }
        for (i in 0 until 8) { mark(size - 1 - i, 8); mark(8, size - 1 - i) }
        if (version >= 7) for (i in 0 until 18) { mark(size - 11 + i % 3, i / 3); mark(i / 3, size - 11 + i % 3) }
        return f
    }

    private fun drawFunction(version: Int, m: BooleanArray) {
        val size = version * 4 + 17
        fun set(x: Int, y: Int, v: Boolean) { if (x in 0 until size && y in 0 until size) m[y * size + x] = v }
        for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
        for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) for (dy in -4..4) for (dx in -4..4) {
            val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            set(cx + dx, cy + dy, d != 2 && d != 4)
        }
        val al = alignmentPositions(version)
        for (i in al.indices) for (j in al.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == al.size - 1) || (i == al.size - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) set(al[i] + dx, al[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
        if (version >= 7) {
            val v = versionBits(version)
            for (i in 0 until 18) {
                val bit = (v ushr i) and 1 == 1
                set(size - 11 + i % 3, i / 3, bit); set(i / 3, size - 11 + i % 3, bit)
            }
        }
    }

    private fun drawFormat(size: Int, m: BooleanArray, bits: Int) {
        fun set(x: Int, y: Int, v: Boolean) { m[y * size + x] = v }
        fun bit(i: Int) = (bits ushr i) and 1 == 1
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }

    /** Visits data modules in the standard zig-zag order. */
    private inline fun zigzag(size: Int, function: BooleanArray, visit: (Int) -> Unit) {
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = (right + 1) and 2 == 0
                val y = if (upward) size - 1 - vert else vert
                if (!function[y * size + x]) visit(y * size + x)
            }
            right -= 2
        }
    }

    private fun place(size: Int, function: BooleanArray, codewords: IntArray, m: BooleanArray) {
        var i = 0
        val total = codewords.size * 8
        zigzag(size, function) { at ->
            if (i < total) m[at] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
            i++
        }
    }

    private fun maskAt(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    private fun applyMask(size: Int, function: BooleanArray, m: BooleanArray, mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val at = y * size + x
            if (!function[at] && maskAt(mask, x, y)) m[at] = !m[at]
        }
    }

    /** The standard's four penalty rules: runs, blocks, finder look-alikes, and dark balance. */
    private fun penalty(size: Int, m: BooleanArray): Int {
        var score = 0
        fun at(x: Int, y: Int) = m[y * size + x]
        for (pass in 0..1) {
            for (a in 0 until size) {
                var run = 0; var colour = false
                for (b in 0 until size) {
                    val v = if (pass == 0) at(b, a) else at(a, b)
                    if (b > 0 && v == colour) run++ else { if (run >= 5) score += 3 + run - 5; run = 1; colour = v }
                }
                if (run >= 5) score += 3 + run - 5
                // 1:1:3:1:1 with four light modules on either side.
                for (b in 0..size - 7) {
                    fun c(k: Int) = if (pass == 0) at(b + k, a) else at(a, b + k)
                    if (c(0) && !c(1) && c(2) && c(3) && c(4) && !c(5) && c(6)) {
                        fun light(from: Int, to: Int): Boolean { for (k in from until to) { val p = b + k; if (p in 0 until size && (if (pass == 0) at(p, a) else at(a, p))) return false }; return true }
                        if (light(-4, 0) || light(7, 11)) score += 40
                    }
                }
            }
        }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = at(x, y)
            if (c == at(x + 1, y) && c == at(x, y + 1) && c == at(x + 1, y + 1)) score += 3
        }
        val dark = m.count { it }
        val total = size * size
        score += ((kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10
        return score
    }

    // ------------------------------------------------------------------ decode

    /**
     * The payload in [matrix], or null when it is not a level-M byte-mode code this reader can
     * correct. Reads either copy of the format information, unmasks, de-interleaves, corrects
     * each block, and parses byte segments (other modes are refused, not guessed).
     */
    fun decode(matrix: Matrix): ByteArray? {
        val size = matrix.size
        if (size < 21 || (size - 17) % 4 != 0) return null
        val version = (size - 17) / 4
        if (version > MAX_VERSION) return null
        val mask = readFormat(matrix) ?: return null
        val function = functionMask(version)
        val total = totalCodewords(version)
        val raw = IntArray(total)
        var i = 0
        zigzag(size, function) { at ->
            if (i < total * 8) {
                val x = at % size; val y = at / size
                val bit = matrix.dark[at] xor maskAt(mask, x, y)
                if (bit) raw[i ushr 3] = raw[i ushr 3] or (1 shl (7 - (i and 7)))
            }
            i++
        }
        // De-interleave into blocks.
        val t = BLOCKS_M[version - 1]
        val ec = t[0]
        val sizes = IntArray(t[1]) { t[2] } + IntArray(t[3]) { t[4] }
        val blocks = sizes.map { IntArray(it + ec) }
        var at = 0
        val longest = sizes.max()
        for (k in 0 until longest) for ((b, len) in sizes.withIndex()) if (k < len) blocks[b][k] = raw[at++]
        for (k in 0 until ec) for ((b, len) in sizes.withIndex()) blocks[b][len + k] = raw[at++]
        val data = ArrayList<Int>()
        for ((b, len) in sizes.withIndex()) {
            if (!correct(blocks[b], ec)) return null
            for (k in 0 until len) data.add(blocks[b][k])
        }
        return parse(data, version)
    }

    /** The mask from whichever format copy is nearest a valid level-M word, within three bits. */
    private fun readFormat(m: Matrix): Int? {
        val size = m.size
        var a = 0
        for (i in 0..5) if (m[8, i]) a = a or (1 shl i)
        if (m[8, 7]) a = a or (1 shl 6); if (m[8, 8]) a = a or (1 shl 7); if (m[7, 8]) a = a or (1 shl 8)
        for (i in 9 until 15) if (m[14 - i, 8]) a = a or (1 shl i)
        var b = 0
        for (i in 0 until 8) if (m[size - 1 - i, 8]) b = b or (1 shl i)
        for (i in 8 until 15) if (m[8, size - 15 + i]) b = b or (1 shl i)
        var best = -1; var bestDistance = 99
        for (mask in 0..7) {
            val f = formatBits(mask)
            val d = minOf(Integer.bitCount(f xor a), Integer.bitCount(f xor b))
            if (d < bestDistance) { bestDistance = d; best = mask }
        }
        return if (bestDistance <= 3) best else null
    }

    private fun parse(data: List<Int>, version: Int): ByteArray? {
        var bit = 0
        val total = data.size * 8
        fun read(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or ((data[bit ushr 3] ushr (7 - (bit and 7))) and 1); bit++ }
            return v
        }
        val out = java.io.ByteArrayOutputStream()
        while (total - bit >= 4) {
            val mode = read(4)
            if (mode == 0) break
            if (mode != 0b0100) return null
            val countBits = if (version < 10) 8 else 16
            if (total - bit < countBits) return null
            val count = read(countBits)
            if (total - bit < count * 8) return null
            repeat(count) { out.write(read(8)) }
        }
        return out.toByteArray()
    }
}
