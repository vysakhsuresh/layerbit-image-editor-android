package com.layerbit.still.ops

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.layerbit.still.R
import com.layerbit.still.engine.AutoEnhance
import com.layerbit.still.engine.AutoWhite
import com.layerbit.still.engine.Collage
import com.layerbit.still.engine.FitSize
import com.layerbit.still.engine.Frame
import com.layerbit.still.engine.Geometry
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.engine.Histogram
import com.layerbit.still.engine.Mark
import com.layerbit.still.engine.MarkKind
import com.layerbit.still.engine.Names
import com.layerbit.still.engine.Pt
import com.layerbit.still.engine.Recipe
import com.layerbit.still.engine.Tone
import com.layerbit.still.engine.WatermarkPlace
import com.layerbit.still.files.StillFile
import com.layerbit.still.image.Budget
import com.layerbit.still.image.Codecs
import com.layerbit.still.image.ExportOptions
import com.layerbit.still.image.Exported
import com.layerbit.still.image.OutputFormat
import com.layerbit.still.text.Strings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The shared tail of every op that renders a recipe and writes a file: render within the
 * budget, encode under the options, name the result by the pattern, describe what happened.
 */
internal suspend fun renderAndExport(
    input: StillFile,
    recipe: Recipe,
    options: ExportOptions,
    context: OpContext,
    step: String,
    pattern: String,
    index: Int
): Pair<StillFile, Exported> {
    val bitmap = context.renderer.render(input.file, recipe, Budget.exportPixels, context.check)
    try {
        val out = context.workspace.newOutput(input.displayName, step, options.format.extension)
        val exported = context.codecs.export(bitmap, options, out, input.file, scaledFromSource = false, check = context.check)
        val name = Names.pattern(pattern, input.displayName, index, exported.width, exported.height, today()) + "." + options.format.extension
        val file = StillFile(out, name, StillFile.Origin.Derived(step), options.format.mime, exported.width, exported.height)
        return file to exported
    } finally {
        bitmap.recycle()
    }
}

internal fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

internal fun sizeChange(before: Long, after: Long): String =
    Strings.get(R.string.engine_size_change, FitSize.sizeText(before), FitSize.sizeText(after))

internal fun dims(w: Int, h: Int) = "$w × $h"

// ------------------------------------------------------------------------------------- resize

/** Exact pixels, a percentage, or a longest edge; the aspect kept unless exact pixels say otherwise. */
class ResizeOp(private val mode: Mode, private val options: ExportOptions, private val pattern: String = "{name}-{w}x{h}") : PerFileOp() {

    sealed interface Mode {
        data class Exact(val width: Int, val height: Int) : Mode
        data class Percent(val percent: Int) : Mode
        data class LongEdge(val pixels: Int) : Mode
    }

    override val tool = ToolId.RESIZE
    override val title: String = when (mode) {
        is Mode.Exact -> Strings.get(R.string.engine_title_resize_exact, mode.width, mode.height)
        is Mode.Percent -> Strings.get(R.string.engine_title_resize_percent, mode.percent)
        is Mode.LongEdge -> Strings.get(R.string.engine_title_resize_edge, mode.pixels)
    }

    override suspend fun one(input: StillFile, context: OpContext): OpOutcome {
        val target = when (mode) {
            is Mode.Exact -> Geometry2D.Size(mode.width, mode.height)
            is Mode.Percent -> Geometry2D.Size(max(1, (input.width * mode.percent / 100f).roundToInt()), max(1, (input.height * mode.percent / 100f).roundToInt()))
            is Mode.LongEdge -> {
                val k = minOf(1f, mode.pixels.toFloat() / max(input.width, input.height))
                Geometry2D.Size(max(1, (input.width * k).roundToInt()), max(1, (input.height * k).roundToInt()))
            }
        }
        val fit = Geometry2D.fit(target.width, target.height, Budget.exportPixels, Budget.MAX_EDGE)
        if (fit != target && mode is Mode.Exact) {
            return OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_resize_over_budget, Budget.exportPixels / 1_000_000))
        }
        if (fit.width == input.width && fit.height == input.height && options.format == OutputFormat.fromMime(input.mimeType)) {
            return OpOutcome.Skipped(input.displayName, Strings.get(R.string.engine_resize_already, dims(input.width, input.height)))
        }
        val recipe = Recipe(geometry = Geometry(width = fit.width, height = fit.height))
        val (file, exported) = renderAndExport(input, recipe, options, context, "resized", pattern, index(input))
        val note = buildString {
            append(Strings.get(R.string.engine_resized_detail, dims(input.width, input.height), dims(exported.width, exported.height)))
            if (fit.width > input.width || fit.height > input.height) append(" ").append(Strings.get(R.string.engine_resize_enlarged))
            if (fit != target) append(" ").append(Strings.get(R.string.engine_resize_capped))
        }
        return OpOutcome.Produced(file, note)
    }

    private var counter = 0
    private fun index(@Suppress("UNUSED_PARAMETER") input: StillFile) = ++counter
}

// ------------------------------------------------------------------------------------- under a size

/**
 * A photo or signature under the size a form asks for: 20 KB, 50 KB, whatever is typed. The
 * search is the engine's: best quality that fits, then the largest picture that does. A JPEG
 * already under the limit at its own size is only stripped - byte for byte the same picture.
 */
class FitKbOp(private val kb: Float, private val pixels: Pixels = Pixels.Keep, private val keepCamera: Boolean = false, private val pattern: String = "{name}-under") : PerFileOp() {

    sealed interface Pixels {
        data object Keep : Pixels
        /** 3.5 × 4.5 cm at 300 dpi, cut from the middle. */
        data object Passport : Pixels
        data class Exact(val width: Int, val height: Int) : Pixels
    }

    override val tool = ToolId.RESIZE_KB
    override val title: String = Strings.get(R.string.engine_title_under, FitSize.limitText(kb))

    override suspend fun one(input: StillFile, context: OpContext): OpOutcome {
        require(kb >= 2f) { Strings.get(R.string.engine_kb_too_small) }
        val limit = FitSize.limitBytes(kb, "KB")
        val under = FitSize.limitText(kb)
        val before = input.sizeBytes
        // Already small enough and nothing to crop: strip only, never re-encode.
        if (pixels == Pixels.Keep && input.isJpeg && before < limit && !keepCamera) {
            val out = context.workspace.newOutput(input.displayName, "under", "jpg")
            val strip = context.codecs.losslessStrip(input.file, out)
            if (strip != null && strip.after < limit) {
                if (!strip.hadHidden) {
                    out.delete()
                    return OpOutcome.Skipped(input.displayName, Strings.get(R.string.engine_already_under, FitSize.sizeText(before), under))
                }
                val name = Names.pattern(pattern, input.displayName, 1, input.width, input.height, today()) + ".jpg"
                return OpOutcome.Produced(
                    StillFile(out, name, StillFile.Origin.Derived("under"), StillFile.MIME_JPEG, input.width, input.height),
                    Strings.get(R.string.engine_stripped_only, FitSize.sizeText(before), FitSize.sizeText(strip.after), under)
                )
            }
            out.delete()
        }
        val geometry = when (pixels) {
            Pixels.Keep -> Geometry()
            Pixels.Passport -> passportGeometry(input)
            is Pixels.Exact -> {
                val crop = Geometry2D.aspectCrop(Geometry2D.Size(input.width, input.height), pixels.width.toFloat() / pixels.height)
                Geometry(crop = crop, width = pixels.width, height = pixels.height)
            }
        }
        val options = ExportOptions(OutputFormat.JPEG, 92, limit, allowShrink = pixels !is Pixels.Exact, keepCameraFields = keepCamera)
        val (file, exported) = try {
            renderAndExport(input, Recipe(geometry = geometry), options, context, "under", pattern, 1)
        } catch (e: Codecs.TargetUnreachable) {
            return OpOutcome.Failed(input.displayName, e.message ?: under)
        }
        val detail = buildString {
            append(Strings.get(R.string.engine_under_detail, FitSize.sizeText(before), FitSize.sizeText(exported.bytes), under, dims(exported.width, exported.height)))
            if (exported.shrunk) append(" ").append(Strings.get(R.string.engine_made_smaller, dims(input.width, input.height)))
            if (pixels == Pixels.Passport) append(" ").append(Strings.get(R.string.engine_passport_note))
            if (!input.isJpeg) append(" ").append(Strings.get(R.string.engine_now_jpeg))
        }
        return OpOutcome.Produced(file, detail)
    }

    private fun passportGeometry(input: StillFile): Geometry {
        val crop = Geometry2D.aspectCrop(Geometry2D.Size(input.width, input.height), PASSPORT_W.toFloat() / PASSPORT_H)
        val cw = (crop.width * input.width).roundToInt()
        return if (cw >= PASSPORT_W) Geometry(crop = crop, width = PASSPORT_W, height = PASSPORT_H) else Geometry(crop = crop)
    }

    companion object {
        const val PASSPORT_W = 413
        const val PASSPORT_H = 531
    }
}

// ------------------------------------------------------------------------------------- compress / convert

/** A quality, or a size to get under, in the picture's own format or a chosen one. */
class CompressOp(private val options: ExportOptions, private val tool0: ToolId = ToolId.COMPRESS, private val pattern: String = "{name}") : PerFileOp() {
    override val tool = tool0
    override val title: String = when {
        options.targetBytes > 0 -> Strings.get(R.string.engine_title_under, FitSize.sizeText(options.targetBytes.toLong()))
        tool0 == ToolId.CONVERT -> Strings.get(R.string.engine_title_convert, options.format.label)
        else -> Strings.get(R.string.engine_title_compress, options.quality)
    }

    override suspend fun one(input: StillFile, context: OpContext): OpOutcome {
        val (file, exported) = try {
            renderAndExport(input, Recipe(), options, context, if (tool0 == ToolId.CONVERT) "converted" else "compressed", pattern, 1)
        } catch (e: Codecs.TargetUnreachable) {
            return OpOutcome.Failed(input.displayName, e.message ?: "")
        }
        if (tool0 == ToolId.COMPRESS && exported.bytes >= input.sizeBytes && options.targetBytes == 0) {
            file.file.delete()
            return OpOutcome.Skipped(input.displayName, Strings.get(R.string.engine_nothing_to_gain, FitSize.sizeText(input.sizeBytes)))
        }
        return OpOutcome.Produced(file, sizeChange(input.sizeBytes, exported.bytes) + " · " + dims(exported.width, exported.height), before = input.sizeBytes)
    }
}

// ------------------------------------------------------------------------------------- strip

/**
 * Removes everything hidden in a picture. A JPEG keeps its pixels byte for byte; a PNG is
 * re-encoded losslessly without its text chunks; anything else becomes a lossless WebP (or a
 * PNG below Android 11), and the detail says which happened.
 */
class StripMetadataOp(private val pattern: String = "{name}-clean") : PerFileOp() {
    override val tool = ToolId.STRIP_METADATA
    override val title: String = Strings.get(R.string.engine_title_strip)

    override suspend fun one(input: StillFile, context: OpContext): OpOutcome {
        if (input.isJpeg) {
            val out = context.workspace.newOutput(input.displayName, "clean", "jpg")
            val strip = context.codecs.losslessStrip(input.file, out)
            if (strip != null) {
                if (!strip.hadHidden) {
                    out.delete()
                    return OpOutcome.Skipped(input.displayName, Strings.get(R.string.engine_nothing_hidden))
                }
                val name = Names.pattern(pattern, input.displayName, 1, input.width, input.height, today()) + ".jpg"
                val what = Strings.get(if (strip.hadGps) R.string.engine_stripped_location else R.string.engine_stripped_camera)
                return OpOutcome.Produced(
                    StillFile(out, name, StillFile.Origin.Derived("clean"), StillFile.MIME_JPEG, input.width, input.height),
                    Strings.get(R.string.engine_strip_lossless_detail, what, FitSize.sizeText(strip.before), FitSize.sizeText(strip.after))
                )
            }
            out.delete()
        }
        val format = when {
            input.mimeType == StillFile.MIME_PNG -> OutputFormat.PNG
            OutputFormat.WEBP_LOSSLESS.available -> OutputFormat.WEBP_LOSSLESS
            else -> OutputFormat.PNG
        }
        val (file, exported) = renderAndExport(input, Recipe(), ExportOptions(format, 100), context, "clean", pattern, 1)
        return OpOutcome.Produced(file, Strings.get(R.string.engine_strip_reencoded_detail, format.label, FitSize.sizeText(exported.bytes)))
    }
}

// ------------------------------------------------------------------------------------- enhance

/** Auto levels and exposure from each picture's own histogram, then export. */
class EnhanceOp(private val options: ExportOptions, private val pattern: String = "{name}-enhanced") : PerFileOp() {
    override val tool = ToolId.ENHANCE
    override val title: String = Strings.get(R.string.engine_title_enhance)

    override suspend fun one(input: StillFile, context: OpContext): OpOutcome {
        val preview = context.renderer.decode(input.file, Budget.PREVIEW_PIXELS, context.check)
        val px = try {
            IntArray(preview.width * preview.height).also { preview.getPixels(it, 0, preview.width, 0, 0, preview.width, preview.height) }
        } finally { preview.recycle() }
        val histogram = Histogram.of(px)
        // Light from the histogram, then the colour cast from what ought to be grey.
        val light = if (AutoEnhance.worthwhile(histogram)) AutoEnhance.tone(histogram) else Tone()
        val tone = AutoWhite.tone(px, light)
        if (tone == Tone()) return OpOutcome.Skipped(input.displayName, Strings.get(R.string.engine_enhance_nothing))
        val (file, _) = renderAndExport(input, Recipe(tone = tone), options, context, "enhanced", pattern, 1)
        return OpOutcome.Produced(file, Strings.get(R.string.engine_enhance_detail, (tone.exposure * 100).roundToInt() / 100f, (tone.whites * 100).roundToInt(), (tone.blacks * 100).roundToInt(), (tone.temperature * 100).roundToInt()))
    }
}

// ------------------------------------------------------------------------------------- recipe

/** A saved recipe over every picture. The one place a preset earns its name. */
class RecipeOp(private val recipe: Recipe, private val name: String, private val options: ExportOptions, private val pattern: String = "{name}-{n}") : PerFileOp() {
    override val tool = ToolId.APPLY_RECIPE
    override val title: String = Strings.get(R.string.engine_title_recipe, name)
    private var counter = 0

    override suspend fun one(input: StillFile, context: OpContext): OpOutcome {
        val (file, exported) = renderAndExport(input, recipe.asPreset(), options, context, "recipe", pattern, ++counter)
        return OpOutcome.Produced(file, dims(exported.width, exported.height) + " · " + FitSize.sizeText(exported.bytes))
    }
}

// ------------------------------------------------------------------------------------- watermark

class WatermarkOp(private val text: String, private val opacity: Float, private val angle: Float, private val size: Float, private val color: Int, private val options: ExportOptions, private val pattern: String = "{name}-marked", private val place: WatermarkPlace = WatermarkPlace.TILED) : PerFileOp() {
    override val tool = ToolId.WATERMARK
    override val title: String = Strings.get(R.string.engine_title_watermark, text.take(20))
    private var counter = 0

    override suspend fun one(input: StillFile, context: OpContext): OpOutcome {
        val mark = Mark(MarkKind.WATERMARK, listOf(Pt(0.5f, 0.5f)), color = color, width = size, text = text, opacity = opacity, angle = angle, place = place)
        val (file, exported) = renderAndExport(input, Recipe(marks = listOf(mark)), options, context, "marked", pattern, ++counter)
        return OpOutcome.Produced(file, dims(exported.width, exported.height))
    }
}

// ------------------------------------------------------------------------------------- collage

/**
 * Several pictures on one canvas, laid out by the engine's plan. Each picture shows the part
 * [Collage.window] picks for its cell from the person's [framings] (keyed by the input's file
 * path; a picture with none is centred), so the export is the preview at a larger size.
 */
class CollageOp(
    private val layout: Collage.Layout,
    private val background: Int,
    private val options: ExportOptions,
    private val style: Collage.Style = Collage.Style(),
    private val framings: Map<String, Collage.Framing> = emptyMap()
) : Op {
    override val tool = ToolId.COLLAGE
    override val title: String = Strings.get(R.string.engine_title_collage, Strings.get(layoutLabel(layout)))
    override val arity: Op.Arity = Op.Arity.Together(2)

    override suspend fun run(inputs: List<StillFile>, context: OpContext, onProgress: (Progress) -> Unit): List<OpOutcome> {
        require(inputs.size in 2..MAX) { Strings.get(R.string.engine_collage_count, MAX) }
        val plan = Collage.plan(layout, inputs.map { it.width.toFloat() / max(1, it.height) }, style)
        val canvas = Collage.canvas(plan, min(Budget.exportPixels, 12_000_000L))
        val bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(if (options.format.alpha) background else (background or 0xFF000000.toInt()))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cw = canvas.width.toFloat(); val ch = canvas.height.toFloat()
        try {
            val perCell = canvas.pixels / inputs.size
            for ((i, input) in inputs.withIndex()) {
                context.checkCancelled()
                onProgress(Progress(i, inputs.size + 1, input.displayName))
                val cell = Collage.cellPx(plan.cells[i], cw, ch)
                val picture = context.renderer.decode(input.file, max(perCell, 400_000L), context.check)
                try {
                    // The window is worked out on the file's own shape, as the preview does.
                    val w = Collage.window(input.width.toFloat(), input.height.toFloat(), cell.width, cell.height, framings[input.file.path] ?: Collage.Framing.CENTRED)
                    val src = Rect(
                        (w.left * picture.width).roundToInt(), (w.top * picture.height).roundToInt(),
                        ((w.left + w.width) * picture.width).roundToInt().coerceAtMost(picture.width), ((w.top + w.height) * picture.height).roundToInt().coerceAtMost(picture.height)
                    )
                    val dst = RectF(cell.left, cell.top, cell.right, cell.bottom)
                    val radius = Collage.cornerPx(plan.style, cw, ch, cell)
                    c.save()
                    if (radius > 0f) c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }) else c.clipRect(dst)
                    c.drawBitmap(picture, src, dst, paint)
                    if (layout == Collage.Layout.POLAROID) {
                        // A white border inside the cell: the stroke is centred on the edge, and the clip keeps its inner half.
                        val border = Collage.polaroidBorderPx(cell)
                        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = border * 2 }
                        white.style = Paint.Style.STROKE
                        c.drawRoundRect(dst, radius, radius, white)
                    }
                    c.restore()
                } finally { picture.recycle() }
            }
            onProgress(Progress(inputs.size, inputs.size + 1, Strings.get(R.string.engine_encoding)))
            val out = context.workspace.newOutput("collage", "collage", options.format.extension)
            val exported = context.codecs.export(bitmap, options, out, null, scaledFromSource = false, check = context.check)
            val name = "collage-${today()}.${options.format.extension}"
            onProgress(Progress(inputs.size + 1, inputs.size + 1, ""))
            return listOf(OpOutcome.Produced(StillFile(out, name, StillFile.Origin.Derived("collage"), options.format.mime, exported.width, exported.height), dims(exported.width, exported.height) + " · " + FitSize.sizeText(exported.bytes)))
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val MAX = 12
        fun layoutLabel(layout: Collage.Layout): Int = when (layout) {
            Collage.Layout.GRID -> R.string.collage_grid
            Collage.Layout.COLUMNS -> R.string.collage_columns
            Collage.Layout.FEATURE -> R.string.collage_feature
            Collage.Layout.STRIP -> R.string.collage_strip
            Collage.Layout.POLAROID -> R.string.collage_polaroid
        }
    }
}

// ------------------------------------------------------------------------------------- frame (batch)

/** A frame on every picture, for the batch path; the editor applies the same [Frame] live. */
class FrameOp(private val frame: Frame, private val options: ExportOptions, private val pattern: String = "{name}-framed") : PerFileOp() {
    override val tool = ToolId.FRAME
    override val title: String = Strings.get(R.string.engine_title_frame)
    private var counter = 0
    override suspend fun one(input: StillFile, context: OpContext): OpOutcome {
        val (file, exported) = renderAndExport(input, Recipe(frame = frame), options, context, "framed", pattern, ++counter)
        return OpOutcome.Produced(file, dims(exported.width, exported.height))
    }
}
