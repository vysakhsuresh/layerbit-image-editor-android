package com.layerbit.still.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanMode {
    OFF,
    /** Paper to white, ink and colours kept: a receipt, a form, a whiteboard. */
    COLOUR,
    /** The same, in grey. */
    GREY,
    /** Black ink on white paper only, anti-aliased: the smallest file and the clearest read. */
    INK
}

/**
 * What a scanner app does to a photographed page, done honestly: the uneven light across the
 * page is measured and divided out, so the paper goes white everywhere and the ink keeps its
 * weight. [strength] blends with the untouched picture; [whiten] pushes near-white paper to white.
 */
data class Scan(val mode: ScanMode = ScanMode.OFF, val strength: Float = 1f, val whiten: Float = 0.5f) {
    val isIdentity: Boolean get() = mode == ScanMode.OFF
    val valid: Boolean get() = strength in 0f..1f && whiten in 0f..1f
}

object ScanMath {

    /** [pixels] cleaned under [scan]; a new array. The paper estimate is sized to the picture so the preview predicts the export. */
    fun apply(pixels: IntArray, w: Int, h: Int, scan: Scan, check: () -> Unit = {}): IntArray {
        if (scan.isIdentity || w <= 0 || h <= 0) return pixels
        val paper = paper(pixels, w, h, check)
        val out = IntArray(pixels.size)
        val thr = if (scan.mode == ScanMode.INK) inkThreshold(pixels, paper, w, h) else 0
        val lift = 1f / (1f - 0.18f * scan.whiten)
        Parallel.bands(h) { from, to -> for (i in from * w until to * w) {
            if (i and 0xFFFF == 0) check()
            val c = pixels[i]
            val p = max(24, paper.at(i % w, i / w, w, h))
            // Divide out the light that fell on the paper, then lift what is nearly white to white.
            var r = min(255f, Argb.red(c) * 255f / p * lift)
            var g = min(255f, Argb.green(c) * 255f / p * lift)
            var b = min(255f, Argb.blue(c) * 255f / p * lift)
            when (scan.mode) {
                ScanMode.GREY -> { val l = (r * 0.299f + g * 0.587f + b * 0.114f); r = l; g = l; b = l }
                ScanMode.INK -> {
                    val l = (r * 0.299f + g * 0.587f + b * 0.114f)
                    val v = ((l - (thr - 14)) / 28f).coerceIn(0f, 1f) * 255f
                    r = v; g = v; b = v
                }
                else -> Unit
            }
            val s = scan.strength
            out[i] = Argb.pack(
                Argb.alpha(c),
                (Argb.red(c) + (r - Argb.red(c)) * s).roundToInt().coerceIn(0, 255),
                (Argb.green(c) + (g - Argb.green(c)) * s).roundToInt().coerceIn(0, 255),
                (Argb.blue(c) + (b - Argb.blue(c)) * s).roundToInt().coerceIn(0, 255)
            )
        } }
        return out
    }

    /**
     * The brightness of the paper under every pixel: the brightest channel, taken as the maximum
     * over small blocks so ink never counts, then blurred over about a tenth of the page.
     */
    /** A map far smaller than the picture, read bilinearly: no buffer the size of the picture. */
    class PaperMap(val values: IntArray, val width: Int, val height: Int) {
        fun at(x: Int, y: Int, w: Int, h: Int): Int = Segment.sample(values, width, height, x.toFloat() * (width - 1) / max(1, w - 1), y.toFloat() * (height - 1) / max(1, h - 1))
    }

    fun paper(pixels: IntArray, w: Int, h: Int, check: () -> Unit = {}): PaperMap {
        val block = max(2, (min(w, h) / 60f).roundToInt())
        val sw = max(1, (w + block - 1) / block); val sh = max(1, (h + block - 1) / block)
        val small = IntArray(sw * sh)
        for (y in 0 until h) {
            val row = y / block
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val v = max(Argb.red(c), max(Argb.green(c), Argb.blue(c)))
                val j = row * sw + x / block
                if (v > small[j]) small[j] = v
            }
        }
        check()
        // A wide blur in the block grid: the same fraction of the page at any resolution.
        val radius = max(1, (min(sw, sh) / 8f).roundToInt())
        var a = small; var tmp = IntArray(small.size)
        repeat(2) { pass(a, tmp, sw, sh, radius, true); pass(tmp, a, sw, sh, radius, false) }
        return PaperMap(a, sw, sh)
    }

    private fun pass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        for (o in 0 until lines) {
            fun idx(i: Int) = if (horizontal) o * w + i else i * w + o
            var sum = 0; var count = 0
            for (i in 0..min(length - 1, r)) { sum += input[idx(i)]; count++ }
            for (i in 0 until length) {
                output[idx(i)] = sum / count
                val add = i + r + 1; val drop = i - r
                if (add < length) { sum += input[idx(add)]; count++ }
                if (drop >= 0) { sum -= input[idx(drop)]; count-- }
            }
        }
    }

    /** Otsu on the normalised page, so a grey photograph of white paper still splits ink from paper. */
    private fun inkThreshold(pixels: IntArray, paper: PaperMap, w: Int, h: Int): Int {
        val step = max(1, pixels.size / 200_000)
        val normalised = IntArray(pixels.size / step) { k ->
            val i = k * step; val c = pixels[i]; val p = max(24, paper.at(i % w, i / w, w, h))
            val l = min(255, Argb.luma(c) * 255 / p)
            Argb.pack(255, l, l, l)
        }
        return AutoEnhance.otsu(Histogram.of(normalised, 1)).coerceIn(60, 220)
    }
}
