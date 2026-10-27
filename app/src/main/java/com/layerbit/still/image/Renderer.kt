package com.layerbit.still.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import com.layerbit.still.engine.Argb
import com.layerbit.still.engine.Frame
import com.layerbit.still.engine.Geometry
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.engine.Mark
import com.layerbit.still.engine.MarkKind
import com.layerbit.still.engine.Recipe
import com.layerbit.still.engine.Backdrop
import com.layerbit.still.engine.Cutout
import com.layerbit.still.engine.Retouch
import com.layerbit.still.engine.ScanMath
import com.layerbit.still.engine.Segment
import com.layerbit.still.engine.Spatial
import com.layerbit.still.engine.TonePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Source bytes and a recipe in, pixels out. The only class that turns a [Recipe] into a Bitmap.
 *
 * Two stages, so the editor can cache the first while a slider moves the second:
 *
 *  - [geometry]: decode within the budget, turn upright, perspective, quarter turns, flips,
 *    straighten, crop, resize. Everything that changes which pixels there are.
 *  - [finish]: tone through the pure [TonePipeline], detail, vignette, grain, the layers, the
 *    frame. Everything that changes what the pixels are, in the same order at every size.
 *
 * Every pure step takes an IntArray, so the preview and the export are the same arithmetic on
 * different sizes - the property [com.layerbit.still.RendererTest] measures.
 */
class Renderer(private val budget: Long = Budget.exportPixels) {

    class Cancelled : CancellationException("Cancelled")

    data class Base(val bitmap: Bitmap, val source: Geometry2D.Size, val scaled: Boolean)

    // ------------------------------------------------------------------ decode

    /** The picture's stored dimensions, upright, without decoding it. */
    fun bounds(file: File): Geometry2D.Size? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val swap = orientation(file) in 5..8
        return if (swap) Geometry2D.Size(o.outHeight, o.outWidth) else Geometry2D.Size(o.outWidth, o.outHeight)
    }

    fun orientation(file: File): Int =
        runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1).takeIf { it in 1..8 } ?: 1

    /** Decodes upright, in sRGB, at no more than [maxPixels]. */
    fun decode(file: File, maxPixels: Long, check: () -> Unit = {}): Bitmap {
        require(maxPixels > 0)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        require(b.outWidth > 0 && b.outHeight > 0) { "unsupported" }
        check()
        val sample = Geometry2D.sampleSize(b.outWidth, b.outHeight, maxPixels, Budget.MAX_EDGE)
        // A sampled decode skips pixels (PNG, WebP) or drops DCT terms (JPEG), which is not the
        // filtered shrink the export gets. Where memory allows, decode at half the sampling and
        // halve with the filter, so a preview is the export's own arithmetic at a smaller size.
        val over = sample > 1 && 4 * maxPixels <= OVERSAMPLE_CAP && (max(b.outWidth, b.outHeight) + sample / 2 - 1) / (sample / 2) <= Budget.MAX_EDGE
        val opts = BitmapFactory.Options().apply {
            inSampleSize = if (over) sample / 2 else sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inMutable = true
        }
        var raw = BitmapFactory.decodeFile(file.path, opts) ?: error("undecodable")
        if (over) {
            check()
            val fit = Geometry2D.fit(raw.width, raw.height, maxPixels, Budget.MAX_EDGE)
            val fitted = scale(raw, fit.width, fit.height)
            if (fitted !== raw) raw.recycle()
            raw = fitted
        }
        val m = Matrix()
        when (orientation(file)) {
            2 -> m.setScale(-1f, 1f); 3 -> m.setRotate(180f); 4 -> m.setScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }; 6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }; 8 -> m.setRotate(-90f)
        }
        if (m.isIdentity) return raw
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (upright !== raw) raw.recycle()
        return upright
    }

    // ------------------------------------------------------------------ geometry

    /**
     * The canvas after geometry, at most [maxPixels]. [scaled] says the source was sampled to
     * fit, which the export sheet reports rather than hides.
     */
    fun geometry(file: File, g: Geometry, maxPixels: Long = budget, check: () -> Unit = {}): Base {
        require(g.valid) { "geometry" }
        val source = bounds(file) ?: error("unsupported")
        // Straightening grows the bounding box; reserve for it before decoding.
        val straightenFactor = if (abs(g.straighten) > 0.01f) {
            val r = Geometry2D.rotatedBounds(1000, (1000 / source.aspect).roundToInt().coerceAtLeast(1), g.straighten)
            (r.pixels.toDouble() / (1000.0 * (1000 / source.aspect))).coerceAtLeast(1.0)
        } else 1.0
        val decodeBudget = (maxPixels / straightenFactor).toLong().coerceAtLeast(10_000)
        var b = decode(file, decodeBudget, check)
        val scaled = b.width.toLong() * b.height < source.pixels
        fun replace(next: Bitmap) { if (next !== b) { b.recycle(); b = next } }
        try {
            check()
            if (!g.quad.isIdentity) replace(perspective(b, g))
            check()
            val turn = Matrix().apply {
                postScale(if (g.flipX) -1f else 1f, if (g.flipY) -1f else 1f)
                postRotate(g.quarterTurns * 90f + g.straighten)
            }
            if (!turn.isIdentity) {
                var dims = transformed(b, turn)
                while (dims.pixels > maxPixels || max(dims.width, dims.height) > Budget.MAX_EDGE) {
                    val fit = Geometry2D.fit(dims.width, dims.height, maxPixels, Budget.MAX_EDGE)
                    val factor = min(fit.width.toDouble() / dims.width, fit.height.toDouble() / dims.height) * 0.98
                    replace(scale(b, max(1, (b.width * factor).toInt()), max(1, (b.height * factor).toInt())))
                    check()
                    dims = transformed(b, turn)
                }
                replace(Bitmap.createBitmap(b, 0, 0, b.width, b.height, turn, true))
            }
            check()
            if (!g.crop.isFull) {
                val x = (g.crop.left * b.width).roundToInt().coerceIn(0, b.width - 1)
                val y = (g.crop.top * b.height).roundToInt().coerceIn(0, b.height - 1)
                val w = (g.crop.width * b.width).roundToInt().coerceIn(1, b.width - x)
                val h = (g.crop.height * b.height).roundToInt().coerceIn(1, b.height - y)
                replace(Bitmap.createBitmap(b, x, y, w, h))
            }
            if (g.width > 0 && g.height > 0) {
                val target = if (maxPixels < budget) Geometry2D.fit(g.width, g.height, maxPixels, Budget.MAX_EDGE) else {
                    require(g.width.toLong() * g.height <= budget) { "budget" }
                    Geometry2D.Size(g.width, g.height)
                }
                check()
                replace(scale(b, target.width, target.height))
            }
            if (!b.isMutable || b.config != Bitmap.Config.ARGB_8888) replace(b.copy(Bitmap.Config.ARGB_8888, true))
            b.setHasAlpha(true)
            return Base(b, source, scaled)
        } catch (t: Throwable) {
            b.recycle(); throw t
        }
    }

    private fun transformed(b: Bitmap, m: Matrix): Geometry2D.Size {
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(r)
        return Geometry2D.Size(max(1, Math.ceil(r.width().toDouble()).toInt()), max(1, Math.ceil(r.height().toDouble()).toInt()))
    }

    /** Maps the recipe's quadrilateral onto a rectangle of the quad's own average edge lengths. */
    private fun perspective(b: Bitmap, g: Geometry): Bitmap {
        val q = g.quad
        val w = b.width.toFloat(); val h = b.height.toFloat()
        val src = floatArrayOf(q.tl.x * w, q.tl.y * h, q.tr.x * w, q.tr.y * h, q.br.x * w, q.br.y * h, q.bl.x * w, q.bl.y * h)
        val top = hypot(src[2] - src[0], src[3] - src[1]); val bottom = hypot(src[4] - src[6], src[5] - src[7])
        val left = hypot(src[6] - src[0], src[7] - src[1]); val right = hypot(src[4] - src[2], src[5] - src[3])
        val outW = max(1, ((top + bottom) / 2).roundToInt()); val outH = max(1, ((left + right) / 2).roundToInt())
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val m = Matrix()
        require(m.setPolyToPoly(src, 0, dst, 0, 4)) { "perspective" }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * Halves while the source is at least twice the target, then one filtered scale. Each halving
     * averages four pixels into one, which a straight scale to a twentieth does not do - and is
     * why thin lines survive this and break up in a single-step resize.
     */
    fun scale(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        var cur = src
        while (cur.width >= w * 2 && cur.height >= h * 2) {
            val next = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    // ------------------------------------------------------------------ finish

    /**
     * Tone, detail, layers and frame on a copy of [base]; [base] is left intact for the cache.
     * Suspends so the per-pixel stages can run across the cores.
     */
    suspend fun finish(base: Bitmap, r: Recipe, check: () -> Unit = {}, maskKey: String? = null, cutoutPreview: Boolean = false): Bitmap = withContext(Dispatchers.Default) {
        val w = base.width; val h = base.height
        val pixels = IntArray(w * h)
        base.getPixels(pixels, 0, w, 0, 0, w, h)
        check()
        val tone = com.layerbit.still.engine.Looks.combine(r.tone, r.curves, r.look, r.lookStrength).tone
        val pipeline = TonePipeline.build(r.tone, r.curves, r.look, r.lookStrength)
        if (!pipeline.isIdentity) parallelRows(h) { from, to -> pipeline.apply(pixels, w, from, to) }
        check()
        var working = pixels
        // Paper cleanup before detail, so sharpening works on clean ink.
        if (!r.scan.isIdentity) working = ScanMath.apply(working, w, h, r.scan, check)
        if (tone.clarity != 0f || tone.dehaze > 0f || tone.sharpness > 0f) {
            working = Spatial.detail(working, w, h, tone, check)
        }
        if (tone.vignette != 0f) Spatial.vignette(working, w, h, tone.vignette, check)
        if (tone.grain > 0f) Spatial.grain(working, w, h, tone.grain, check = check)
        check()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(working, 0, w, 0, 0, w, h)
        try {
            for (mark in r.marks) {
                check()
                if (mark.visible && mark.points.isNotEmpty()) drawMark(out, mark, check)
            }
            var picture = out
            if (r.cutout.enabled || cutoutPreview) {
                val cut = cutoutStage(picture, base, r.cutout, maskKey, cutoutPreview, check)
                picture.recycle(); picture = cut
            }
            if (r.frame.isIdentity) picture else frame(picture, r.frame, check).also { picture.recycle() }
        } catch (t: Throwable) {
            out.recycle(); throw t
        }
    }

    // ------------------------------------------------------------------ cut-out

    private class MaskEntry(val key: String, val width: Int, val height: Int, val mask: IntArray)
    private val maskCache = ArrayDeque<MaskEntry>()

    /**
     * The mask decided at the analysis size from the geometry stage [base], remembered under
     * [key] so the export reuses the preview's decision pixel for pixel. The caller's key must
     * change with the picture and its geometry; the cut-out's own parameters are added here.
     */
    private fun maskFor(base: Bitmap, cut: Cutout, key: String?, check: () -> Unit): MaskEntry {
        val size = Segment.analysisSize(base.width, base.height)
        val cacheKey = "${key ?: ""}|${cut.maskKey}|${size.width}x${size.height}"
        synchronized(maskCache) { maskCache.firstOrNull { it.key == cacheKey }?.let { return it } }
        val small = scale(base, size.width, size.height)
        val px = IntArray(size.width * size.height)
        small.getPixels(px, 0, size.width, 0, 0, size.width, size.height)
        if (small !== base) small.recycle()
        check()
        val entry = MaskEntry(cacheKey, size.width, size.height, Segment.mask(px, size.width, size.height, cut, check))
        if (key != null) synchronized(maskCache) { maskCache.removeAll { it.key == cacheKey }; maskCache.addFirst(entry); while (maskCache.size > 3) maskCache.removeLast() }
        return entry
    }

    /** Hard numbers about a cut-out for the panel: how much of the picture is kept. */
    fun cutoutCoverage(base: Bitmap, cut: Cutout, key: String?, check: () -> Unit = {}): Float {
        val e = maskFor(base, cut, key, check)
        return if (cut.invert) 1f - Segment.coverage(e.mask) else Segment.coverage(e.mask)
    }

    /**
     * The picture with its background removed, or shown under a red wash while the user is
     * deciding ([preview]); then transparent, a flat colour, or a blur of the picture behind it.
     */
    private fun cutoutStage(picture: Bitmap, base: Bitmap, cut: Cutout, key: String?, preview: Boolean, check: () -> Unit): Bitmap {
        val w = picture.width; val h = picture.height
        val analysis = maskFor(base, cut, key, check)
        var mask = Segment.resample(analysis.mask, analysis.width, analysis.height, w, h)
        val featherPx = (cut.feather * min(w, h)).roundToInt()
        if (featherPx > 0) mask = Segment.feather(mask, w, h, featherPx)
        check()
        val px = IntArray(w * h)
        picture.getPixels(px, 0, w, 0, 0, w, h)
        if (preview) {
            for (i in px.indices) {
                val m = if (cut.invert) 255 - mask[i] else mask[i]
                val f = (255 - m) * 0.62f / 255f
                if (f > 0f) {
                    val c = px[i]
                    px[i] = Argb.pack(Argb.alpha(c), (Argb.red(c) + (235 - Argb.red(c)) * f).roundToInt(), (Argb.green(c) + (40 - Argb.green(c)) * f).roundToInt(), (Argb.blue(c) + (70 - Argb.blue(c)) * f).roundToInt())
                }
            }
        } else {
            val behind: IntArray? = if (cut.backdrop == Backdrop.BLUR) blurredCopy(picture, cut.blur, check) else null
            Segment.applyAlpha(px, mask, cut.invert)
            if (cut.backdrop != Backdrop.TRANSPARENT) {
                for (i in px.indices) {
                    val top = px[i]; val a = Argb.alpha(top)
                    if (a == 255) continue
                    val under = behind?.get(i) ?: cut.color
                    val ua = Argb.alpha(under)
                    val outA = a + ua * (255 - a) / 255
                    fun ch(t: Int, u: Int) = (t * a + u * (255 - a)) / 255
                    px[i] = Argb.pack(outA, ch(Argb.red(top), Argb.red(under)), ch(Argb.green(top), Argb.green(under)), ch(Argb.blue(top), Argb.blue(under)))
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** The picture blurred by [fraction] of its shorter edge, done small and scaled back, as pixels at full size. */
    private fun blurredCopy(picture: Bitmap, fraction: Float, check: () -> Unit): IntArray {
        val w = picture.width; val h = picture.height
        val small = scale(picture, max(1, w / 4), max(1, h / 4))
        val sp = IntArray(small.width * small.height)
        small.getPixels(sp, 0, small.width, 0, 0, small.width, small.height)
        val blurred = Spatial.boxBlur(sp, small.width, small.height, max(1, (fraction * min(w, h) / 4).roundToInt()), check = check)
        val bb = Bitmap.createBitmap(blurred, small.width, small.height, Bitmap.Config.ARGB_8888)
        if (small !== picture) small.recycle()
        val big = scale(bb, w, h)
        val out = IntArray(w * h)
        big.getPixels(out, 0, w, 0, 0, w, h)
        if (big !== bb) big.recycle()
        bb.recycle()
        return out
    }

    /** A 512 x 512 transparent canvas with the picture fitted inside, as sticker apps want it. */
    fun stickerCanvas(picture: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(STICKER_EDGE, STICKER_EDGE, Bitmap.Config.ARGB_8888)
        val inner = STICKER_EDGE - 2 * STICKER_MARGIN
        val k = min(inner.toFloat() / picture.width, inner.toFloat() / picture.height)
        val w = max(1, (picture.width * k).roundToInt()); val h = max(1, (picture.height * k).roundToInt())
        val fitted = scale(picture, w, h)
        Canvas(out).drawBitmap(fitted, (STICKER_EDGE - w) / 2f, (STICKER_EDGE - h) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (fitted !== picture) fitted.recycle()
        return out
    }

    private suspend fun parallelRows(h: Int, block: (Int, Int) -> Unit) {
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        if (workers == 1 || h < 256) { block(0, h); return }
        val chunk = (h + workers - 1) / workers
        coroutineScope {
            (0 until workers).map { i ->
                async { block(i * chunk, min(h, (i + 1) * chunk)) }
            }.forEach { it.await() }
        }
    }

    // ------------------------------------------------------------------ layers

    private fun drawMark(b: Bitmap, m: Mark, check: () -> Unit) {
        val c = Canvas(b)
        val size = Geometry2D.Size(b.width, b.height)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = m.color
            alpha = (m.opacity * 255).roundToInt()
            strokeWidth = Geometry2D.px(m.width, size)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
        }
        val pts = m.points.map { floatArrayOf(it.x * b.width, it.y * b.height) }
        val start = pts.first(); val end = pts.last()
        val path = Path().apply {
            moveTo(start[0], start[1])
            if (pts.size == 1) lineTo(start[0] + 0.1f, start[1] + 0.1f) else pts.drop(1).forEach { lineTo(it[0], it[1]) }
        }
        val rect = RectF(min(start[0], end[0]), min(start[1], end[1]), max(start[0], end[0]), max(start[1], end[1]))
        when (m.kind) {
            MarkKind.DRAW -> c.drawPath(path, p)
            MarkKind.BOX -> c.drawRect(rect, p)
            MarkKind.ELLIPSE -> c.drawOval(rect, p)
            MarkKind.COVER -> { p.style = Paint.Style.FILL; p.alpha = 255; c.drawRect(rect, p) }
            MarkKind.ARROW -> {
                c.drawLine(start[0], start[1], end[0], end[1], p)
                val angle = atan2(end[1] - start[1], end[0] - start[0])
                val length = p.strokeWidth * 4.5f
                for (offset in floatArrayOf(-0.5f, 0.5f)) {
                    c.drawLine(end[0], end[1], end[0] - length * cos(angle + offset), end[1] - length * sin(angle + offset), p)
                }
            }
            MarkKind.TEXT -> drawText(c, m, start, size, p)
            MarkKind.WATERMARK -> drawWatermark(c, m, size, p)
            MarkKind.REDEYE -> {
                val radius = Geometry2D.px(m.width, size)
                val x0 = (start[0] - radius - 1).toInt().coerceIn(0, b.width - 1); val y0 = (start[1] - radius - 1).toInt().coerceIn(0, b.height - 1)
                val x1 = (start[0] + radius + 1).toInt().coerceIn(0, b.width - 1); val y1 = (start[1] + radius + 1).toInt().coerceIn(0, b.height - 1)
                val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
                val region = IntArray(rw * rh)
                b.getPixels(region, 0, rw, x0, y0, rw, rh)
                Retouch.redEye(region, rw, rh, start[0] - x0, start[1] - y0, radius)
                b.setPixels(region, 0, rw, x0, y0, rw, rh)
            }
            MarkKind.ERASE -> { p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); c.drawPath(path, p) }
            MarkKind.CLONE -> {
                val src = m.source ?: return
                val snap = b.copy(Bitmap.Config.ARGB_8888, false)
                p.shader = BitmapShader(snap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(start[0] - src.x * b.width, start[1] - src.y * b.height) })
                }
                c.drawPath(path, p)
                p.shader = null
                snap.recycle()
            }
            MarkKind.PIXELATE, MarkKind.BLUR -> {
                val x = rect.left.toInt().coerceIn(0, b.width - 1); val y = rect.top.toInt().coerceIn(0, b.height - 1)
                val w = rect.width().toInt().coerceIn(1, b.width - x); val h = rect.height().toInt().coerceIn(1, b.height - y)
                val region = IntArray(w * h)
                b.getPixels(region, 0, w, x, y, w, h)
                val result = if (m.kind == MarkKind.PIXELATE) {
                    Spatial.pixelate(region, w, h, 0, 0, w, h, max(4, Geometry2D.px(m.width * 2.5f, size).roundToInt()))
                    region
                } else {
                    // Radius from the layer's own size, scaled with the picture: the same blur on
                    // the preview and the export, with no cap.
                    Spatial.boxBlur(region, w, h, max(2, Geometry2D.px(m.width * 1.5f, size).roundToInt()), passes = 3, check = check)
                }
                if (m.opacity >= 1f) {
                    b.setPixels(result, 0, w, x, y, w, h)
                } else {
                    val layer = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
                    c.drawBitmap(layer, x.toFloat(), y.toFloat(), Paint().apply { alpha = p.alpha })
                    layer.recycle()
                }
            }
        }
    }

    /** Wrapped at 80% of the canvas, anchored at the tap, turned by the layer's angle. */
    private fun drawText(c: Canvas, m: Mark, at: FloatArray, size: Geometry2D.Size, p: Paint) {
        val tp = TextPaint(p).apply {
            style = Paint.Style.FILL
            textSize = max(8f, Geometry2D.px(m.width, size) * 3.2f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, Color.argb((90 * m.opacity).roundToInt(), 0, 0, 0))
        }
        val maxWidth = max(16, (size.width * 0.8f).roundToInt())
        val layout = StaticLayout.Builder.obtain(m.text.ifBlank { " " }, 0, m.text.ifBlank { " " }.length, tp, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        val width = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        c.save()
        c.translate(at[0], at[1])
        c.rotate(m.angle)
        c.translate(-width / 2f, -layout.height / 2f)
        layout.draw(c)
        c.restore()
    }

    /** The text repeated on a diagonal grid across the whole canvas. */
    private fun drawWatermark(c: Canvas, m: Mark, size: Geometry2D.Size, p: Paint) {
        val tp = TextPaint(p).apply {
            style = Paint.Style.FILL
            textSize = max(8f, Geometry2D.px(m.width, size) * 3.2f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val text = m.text.ifBlank { "©" }
        val tw = tp.measureText(text)
        val stepX = tw * 1.6f
        val stepY = tp.textSize * 3.2f
        val diag = hypot(size.width.toFloat(), size.height.toFloat())
        c.save()
        c.rotate(m.angle, size.width / 2f, size.height / 2f)
        var y = size.height / 2f - diag
        var row = 0
        while (y < size.height / 2f + diag) {
            var x = size.width / 2f - diag + (if (row % 2 == 0) 0f else stepX / 2)
            while (x < size.width / 2f + diag) {
                c.drawText(text, x, y, tp)
                x += stepX
            }
            y += stepY; row++
        }
        c.restore()
    }

    // ------------------------------------------------------------------ frame

    private fun frame(picture: Bitmap, f: Frame, check: () -> Unit): Bitmap {
        val canvas = Geometry2D.Size(picture.width, picture.height)
        val size = Geometry2D.framedSize(canvas, f)
        val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shorter = min(canvas.width, canvas.height)
        val border = (f.border * shorter).roundToInt()
        val left = (size.width - canvas.width) / 2f
        val top = (size.height - canvas.height) / 2f
        if (f.blurredFill) {
            // A blurred copy scaled to cover the whole frame, the way stories pad a photo.
            val cover = max(size.width.toFloat() / picture.width, size.height.toFloat() / picture.height)
            val small = scale(picture, max(1, (picture.width * cover / 8).roundToInt()), max(1, (picture.height * cover / 8).roundToInt()))
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            val blurred = Spatial.boxBlur(px, small.width, small.height, max(2, small.width / 40), check = check)
            val blurBitmap = Bitmap.createBitmap(blurred, small.width, small.height, Bitmap.Config.ARGB_8888)
            c.drawBitmap(blurBitmap, null, RectF(0f, 0f, size.width.toFloat(), size.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawColor(Color.argb(60, 0, 0, 0))
            blurBitmap.recycle(); small.recycle()
        } else {
            c.drawColor(f.background)
        }
        check()
        val radius = f.cornerRadius * shorter
        if (border > 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = f.borderColor }
            c.drawRoundRect(RectF(left - border, top - border, left + canvas.width + border, top + canvas.height + border), radius + border, radius + border, bp)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (radius > 0f) {
            paint.shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(left, top) })
            }
            c.drawRoundRect(RectF(left, top, left + canvas.width, top + canvas.height), radius, radius, paint)
        } else {
            c.drawBitmap(picture, left, top, paint)
        }
        return out
    }

    // ------------------------------------------------------------------ whole pipeline

    /** Geometry then finish, for a one-off render: batch ops, thumbnails of edited projects. */
    suspend fun render(file: File, r: Recipe, maxPixels: Long = budget, check: () -> Unit = {}): Bitmap {
        val base = geometry(file, r.geometry, maxPixels, check)
        try {
            return finish(base.bitmap, r, check, maskKey = file.path + "|" + r.geometry.hashCode())
        } finally {
            base.bitmap.recycle()
        }
    }

    /** Flattens transparency onto [background] for formats without alpha. */
    fun flatten(b: Bitmap, background: Int = Color.WHITE): Bitmap {
        val flat = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(background); drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
        return flat
    }

    /** Mean per-channel difference between two bitmaps of the same size, for tests and compare. */
    fun difference(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height)
        val pa = IntArray(a.width * a.height); val pb = IntArray(pa.size)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height); b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var sum = 0L
        for (i in pa.indices) {
            sum += abs(Argb.red(pa[i]) - Argb.red(pb[i])) + abs(Argb.green(pa[i]) - Argb.green(pb[i])) + abs(Argb.blue(pa[i]) - Argb.blue(pb[i]))
        }
        return sum.toDouble() / (pa.size * 3)
    }

    companion object {
        const val STICKER_EDGE = 512
        const val STICKER_MARGIN = 8
        /** The most a preview decode may oversample to: 24 MB of pixels, passing, on any phone. */
        const val OVERSAMPLE_CAP = 6_000_000L

        /** A thumbnail of [file] at about [maxPixels], or null when it cannot be decoded. */
        fun thumbnail(file: File, maxPixels: Long = Budget.THUMB_PIXELS): Bitmap? =
            runCatching { Renderer().decode(file, maxPixels) }.getOrNull()
    }
}
