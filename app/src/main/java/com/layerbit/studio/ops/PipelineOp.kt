package com.layerbit.studio.ops

import com.layerbit.studio.R
import com.layerbit.studio.engine.AutoEnhance
import com.layerbit.studio.engine.FitSize
import com.layerbit.studio.engine.Frame
import com.layerbit.studio.engine.Geometry
import com.layerbit.studio.engine.Geometry2D
import com.layerbit.studio.engine.Histogram
import com.layerbit.studio.engine.Look
import com.layerbit.studio.engine.Mark
import com.layerbit.studio.engine.MarkKind
import com.layerbit.studio.engine.Pt
import com.layerbit.studio.engine.Recipe
import com.layerbit.studio.files.StudioFile
import com.layerbit.studio.image.Budget
import com.layerbit.studio.image.Codecs
import com.layerbit.studio.image.ExportOptions
import com.layerbit.studio.image.OutputFormat
import com.layerbit.studio.text.Strings
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a batch does to every picture, as steps the user switched on. One screen, one run.
 *
 * Every step is optional and they compose: resize, then get under a size, in a format, after an
 * auto-enhance or a recipe, with a watermark and a frame, and always without hidden data. The
 * order is the renderer's, not the user's, so switching steps on in any order gives one result.
 * With nothing on but "remove hidden data", a JPEG is not rendered at all: its segments are
 * walked and the picture is kept byte for byte.
 */
data class Pipeline(
    val resize: ResizeOp.Mode? = null,
    /** Kilobytes of 1,000 bytes; null for no limit. */
    val underKb: Float? = null,
    val passport: Boolean = false,
    val enhance: Boolean = false,
    val look: Look = Look.NONE,
    val lookStrength: Float = 1f,
    /** A saved recipe, applied under the look. */
    val recipe: Recipe? = null,
    val watermark: Watermark? = null,
    val frame: Frame? = null,
    /** Null keeps each picture's own format where the platform can write it. */
    val format: OutputFormat? = null,
    val quality: Int = 90,
    val keepCamera: Boolean = false,
    val allowShrink: Boolean = true,
    val pattern: String = "{name}"
) {
    data class Watermark(val text: String, val opacity: Float = 0.35f, val angle: Float = -30f, val size: Float = 0.03f, val color: Int = 0xFFFFFFFF.toInt())

    /** Whether anything changes the pixels; otherwise a picture is only cleaned. */
    val changesPixels: Boolean
        get() = resize != null || underKb != null || passport || enhance || look != Look.NONE || recipe != null || watermark != null || frame != null

    /** The steps as a short sentence for the job title and the result list. */
    fun summary(): String = buildList {
        resize?.let { add(when (it) { is ResizeOp.Mode.Exact -> "${it.width}×${it.height}"; is ResizeOp.Mode.Percent -> "${it.percent}%"; is ResizeOp.Mode.LongEdge -> "${it.pixels} px" }) }
        if (passport) add(Strings.get(R.string.step_passport))
        underKb?.let { add(Strings.get(R.string.engine_title_under, FitSize.limitText(it))) }
        if (enhance) add(Strings.get(R.string.step_enhance))
        if (look != Look.NONE) add(look.name.lowercase().replaceFirstChar { c -> c.uppercase() })
        recipe?.let { add(Strings.get(R.string.step_recipe)) }
        watermark?.let { add(Strings.get(R.string.step_watermark)) }
        frame?.let { add(Strings.get(R.string.step_frame)) }
        format?.let { add(it.label) }
        if (isEmpty()) add(Strings.get(R.string.step_clean_only))
    }.joinToString(" · ")
}

class PipelineOp(private val p: Pipeline) : PerFileOp() {

    override val tool = ToolId.PROCESS
    override val title: String = p.summary()
    private var counter = 0

    override suspend fun one(input: StudioFile, context: OpContext): OpOutcome {
        val index = ++counter
        val format = p.format ?: formatFor(input)
        // Nothing touches the pixels: clean the file as it is, losslessly, and say so.
        if (!p.changesPixels && (p.format == null || p.format == OutputFormat.fromMime(input.mimeType))) {
            return StripMetadataOp(p.pattern.ifBlank { "{name}-clean" }).one(input, context)
        }
        val recipe = recipeFor(input, context)
        val limit = p.underKb?.let { FitSize.limitBytes(it, "KB") } ?: 0
        val lossy = if (limit > 0 && !format.lossy) OutputFormat.JPEG else format
        val options = ExportOptions(lossy, p.quality, limit, p.allowShrink && !p.passport && p.resize !is ResizeOp.Mode.Exact, p.keepCamera)
        val (file, exported) = try {
            renderAndExport(input, recipe, options, context, "processed", p.pattern.ifBlank { "{name}" }, index)
        } catch (e: Codecs.TargetUnreachable) {
            return OpOutcome.Failed(input.displayName, e.message ?: "")
        }
        val detail = buildString {
            append(dims(input.width, input.height)).append(" → ").append(dims(exported.width, exported.height))
            append(" · ").append(sizeChange(input.sizeBytes, exported.bytes))
            if (exported.shrunk) append(" · ").append(Strings.get(R.string.engine_made_smaller, dims(input.width, input.height)))
            if (lossy != format) append(" · ").append(Strings.get(R.string.engine_now_jpeg))
        }
        return OpOutcome.Produced(file, detail)
    }

    /** The picture's own format, or JPEG for anything the platform cannot write back. */
    private fun formatFor(input: StudioFile): OutputFormat = when (input.mimeType) {
        StudioFile.MIME_PNG -> OutputFormat.PNG
        StudioFile.MIME_WEBP -> OutputFormat.WEBP
        else -> OutputFormat.JPEG
    }

    private suspend fun recipeFor(input: StudioFile, context: OpContext): Recipe {
        var r = p.recipe?.asPreset() ?: Recipe()
        if (p.look != Look.NONE) r = r.copy(look = p.look, lookStrength = p.lookStrength)
        if (p.enhance) {
            val preview = context.renderer.decode(input.file, Budget.PREVIEW_PIXELS, context.check)
            val histogram = try {
                val px = IntArray(preview.width * preview.height)
                preview.getPixels(px, 0, preview.width, 0, 0, preview.width, preview.height)
                Histogram.of(px)
            } finally { preview.recycle() }
            if (AutoEnhance.worthwhile(histogram)) r = r.copy(tone = AutoEnhance.tone(histogram, r.tone))
        }
        var geometry = r.geometry
        if (p.passport) {
            val crop = Geometry2D.aspectCrop(Geometry2D.Size(input.width, input.height), FitKbOp.PASSPORT_W.toFloat() / FitKbOp.PASSPORT_H)
            val cw = (crop.width * input.width).roundToInt()
            geometry = if (cw >= FitKbOp.PASSPORT_W) Geometry(crop = crop, width = FitKbOp.PASSPORT_W, height = FitKbOp.PASSPORT_H) else Geometry(crop = crop)
        } else p.resize?.let { mode ->
            val target = when (mode) {
                is ResizeOp.Mode.Exact -> Geometry2D.Size(mode.width, mode.height)
                is ResizeOp.Mode.Percent -> Geometry2D.Size(max(1, (input.width * mode.percent / 100f).roundToInt()), max(1, (input.height * mode.percent / 100f).roundToInt()))
                is ResizeOp.Mode.LongEdge -> {
                    val k = mode.pixels.toFloat() / max(input.width, input.height)
                    Geometry2D.Size(max(1, (input.width * k).roundToInt()), max(1, (input.height * k).roundToInt()))
                }
            }
            val fit = Geometry2D.fit(target.width, target.height, Budget.exportPixels, Budget.MAX_EDGE)
            geometry = geometry.copy(width = fit.width, height = fit.height)
        }
        r = r.copy(geometry = geometry)
        p.watermark?.let { w ->
            r = r.copy(marks = r.marks + Mark(MarkKind.WATERMARK, listOf(Pt(0.5f, 0.5f)), color = w.color, width = w.size, text = w.text, opacity = w.opacity, angle = w.angle))
        }
        p.frame?.let { r = r.copy(frame = it) }
        return r
    }
}
