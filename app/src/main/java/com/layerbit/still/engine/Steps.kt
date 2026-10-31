package com.layerbit.still.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/** The parts of a recipe the History panel lists, in the order the renderer applies them. */
enum class StepKind { PERSPECTIVE, TURN, STRAIGHTEN, CROP, SIZE, EXPAND, LIGHT, COLOUR, CURVES, LOOK, MIX, SCAN, DETAIL, LOCAL, EFFECT, PORTRAIT, DOUBLE, MARK, CUTOUT, FRAME }

/** One step: its kind and, for layers, which one. */
data class StepKey(val kind: StepKind, val index: Int = 0)

/**
 * One line of the History panel, as data: the screen turns [values] and [label] into words in
 * the user's language. [values] are what the sliders show (exposure in stops, the rest in
 * hundredths), only the ones that moved.
 */
data class Step(val key: StepKey, val label: String = "", val values: List<Pair<String, Float>> = emptyList())

/**
 * The recipe read as a list of edits, and the recipe with any of them taken out.
 *
 * Derived from the recipe itself, not from the undo stack, so the list is the same after a
 * restart, after undo, and on a project made by a batch: whatever is in the recipe is listed,
 * and nothing that is not. Taking a step out resets just that part to its default, so hiding
 * Light leaves the crop and the look exactly as they were.
 */
object Steps {

    val LIGHT_FIELDS = listOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks")
    val COLOUR_FIELDS = listOf("temperature", "tint", "vibrance", "saturation", "hue")
    val DETAIL_FIELDS = listOf("noise", "sharpness", "clarity", "dehaze", "fade", "vignette", "grain")

    private fun field(t: Tone, name: String): Float = when (name) {
        "exposure" -> t.exposure; "contrast" -> t.contrast; "highlights" -> t.highlights; "shadows" -> t.shadows
        "whites" -> t.whites; "blacks" -> t.blacks; "temperature" -> t.temperature; "tint" -> t.tint
        "vibrance" -> t.vibrance; "saturation" -> t.saturation; "hue" -> t.hue; "noise" -> t.noise
        "sharpness" -> t.sharpness; "clarity" -> t.clarity; "dehaze" -> t.dehaze; "fade" -> t.fade
        "vignette" -> t.vignette; "grain" -> t.grain
        else -> throw IllegalArgumentException(name)
    }

    /** Every edit in [r], in the order it is applied. [source] (the upright picture) lets a crop be named by its ratio. */
    fun describe(r: Recipe, source: Geometry2D.Size? = null): List<Step> {
        val out = ArrayList<Step>()
        val g = r.geometry
        if (!g.quad.isIdentity) out.add(Step(StepKey(StepKind.PERSPECTIVE)))
        if (g.quarterTurns != 0 || g.flipX || g.flipY) out.add(Step(StepKey(StepKind.TURN), values = listOfNotNull(
            ("rotation" to g.quarterTurns * 90f).takeIf { g.quarterTurns != 0 }, ("flipX" to 1f).takeIf { g.flipX }, ("flipY" to 1f).takeIf { g.flipY }
        )))
        if (abs(g.straighten) >= 0.05f) out.add(Step(StepKey(StepKind.STRAIGHTEN), values = listOf("degrees" to (g.straighten * 10).roundToInt() / 10f)))
        if (!g.crop.isFull) out.add(Step(StepKey(StepKind.CROP), label = source?.let { ratioName(Geometry2D.canvasSize(it, g.copy(width = 0, height = 0))) } ?: ""))
        if (g.width > 0 && g.height > 0) out.add(Step(StepKey(StepKind.SIZE), label = "${g.width} × ${g.height}"))
        if (!r.expand.isIdentity) out.add(Step(StepKey(StepKind.EXPAND), label = r.expand.fill.name, values = listOf(
            "left" to r.expand.left, "top" to r.expand.top, "right" to r.expand.right, "bottom" to r.expand.bottom
        ).filter { it.second > 0f }.map { it.first to (it.second * 100).roundToInt().toFloat() }))
        toneStep(r.tone, StepKind.LIGHT, LIGHT_FIELDS)?.let { out.add(it) }
        toneStep(r.tone, StepKind.COLOUR, COLOUR_FIELDS)?.let { out.add(it) }
        if (!r.curves.isIdentity) out.add(Step(StepKey(StepKind.CURVES)))
        if (r.look != Look.NONE && r.lookStrength > 0f) out.add(Step(StepKey(StepKind.LOOK), label = r.look.name, values = listOf("strength" to (r.lookStrength * 100).roundToInt().toFloat())))
        if (!r.mix.isIdentity) out.add(Step(StepKey(StepKind.MIX)))
        if (!r.scan.isIdentity) out.add(Step(StepKey(StepKind.SCAN), label = r.scan.mode.name))
        toneStep(r.tone, StepKind.DETAIL, DETAIL_FIELDS)?.let { out.add(it) }
        r.locals.forEachIndexed { i, l -> out.add(Step(StepKey(StepKind.LOCAL, i), label = l.mask.kind.name)) }
        r.effects.forEachIndexed { i, e -> out.add(Step(StepKey(StepKind.EFFECT, i), label = e.kind.name, values = listOf("strength" to (e.strength * 100).roundToInt().toFloat()))) }
        if (!r.portrait.isIdentity) out.add(Step(StepKey(StepKind.PORTRAIT)))
        if (!r.double.isIdentity) out.add(Step(StepKey(StepKind.DOUBLE), label = r.double.mode.name))
        r.marks.forEachIndexed { i, m -> out.add(Step(StepKey(StepKind.MARK, i), label = m.kind.name, values = emptyList())) }
        if (!r.cutout.isIdentity) out.add(Step(StepKey(StepKind.CUTOUT), label = r.cutout.mode.name))
        if (!r.frame.isIdentity) out.add(Step(StepKey(StepKind.FRAME)))
        return out
    }

    private fun toneStep(t: Tone, kind: StepKind, fields: List<String>): Step? {
        val moved = fields.mapNotNull { name ->
            val v = field(t, name)
            if (v == 0f) null else name to if (name == "exposure") (v * 100).roundToInt() / 100f else (v * 100).roundToInt().toFloat()
        }
        return if (moved.isEmpty()) null else Step(StepKey(kind), values = moved)
    }

    /** "4:5" when the picture's shape is a ratio people name, otherwise the shape to two places, "1.37:1". */
    fun ratioName(size: Geometry2D.Size): String {
        val a = size.width.toDouble() / size.height
        for ((w, h) in NAMED) if (abs(a - w.toDouble() / h) < 0.01 * a) return "$w:$h"
        return if (a >= 1) String.format(java.util.Locale.ROOT, "%.2f:1", a) else String.format(java.util.Locale.ROOT, "1:%.2f", 1 / a)
    }

    private val NAMED = listOf(1 to 1, 4 to 5, 5 to 4, 3 to 4, 4 to 3, 2 to 3, 3 to 2, 9 to 16, 16 to 9, 5 to 7, 7 to 5, 21 to 9)

    /** [r] with every step in [hidden] reset to its default; layers are taken out by index. */
    fun without(r: Recipe, hidden: Set<StepKey>): Recipe {
        if (hidden.isEmpty()) return r
        var out = r
        var g = out.geometry
        var t = out.tone
        for (k in hidden) when (k.kind) {
            StepKind.PERSPECTIVE -> g = g.copy(quad = Quad.IDENTITY)
            StepKind.TURN -> g = g.copy(rotation = 0, flipX = false, flipY = false)
            StepKind.STRAIGHTEN -> g = g.copy(straighten = 0f)
            StepKind.CROP -> g = g.copy(crop = Crop.FULL)
            StepKind.SIZE -> g = g.copy(width = 0, height = 0)
            StepKind.EXPAND -> out = out.copy(expand = Expand())
            StepKind.LIGHT -> t = t.copy(exposure = 0f, contrast = 0f, highlights = 0f, shadows = 0f, whites = 0f, blacks = 0f)
            StepKind.COLOUR -> t = t.copy(temperature = 0f, tint = 0f, vibrance = 0f, saturation = 0f, hue = 0f)
            StepKind.DETAIL -> t = t.copy(noise = 0f, sharpness = 0f, clarity = 0f, dehaze = 0f, fade = 0f, vignette = 0f, grain = 0f)
            StepKind.CURVES -> out = out.copy(curves = Curves())
            StepKind.LOOK -> out = out.copy(look = Look.NONE, lookStrength = 1f)
            StepKind.MIX -> out = out.copy(mix = ColourMix())
            StepKind.SCAN -> out = out.copy(scan = Scan())
            StepKind.PORTRAIT -> out = out.copy(portrait = PortraitEdit())
            StepKind.DOUBLE -> out = out.copy(double = DoubleExposure())
            StepKind.MARK, StepKind.LOCAL, StepKind.EFFECT -> Unit
            StepKind.CUTOUT -> out = out.copy(cutout = Cutout())
            StepKind.FRAME -> out = out.copy(frame = Frame())
        }
        fun gone(kind: StepKind) = hidden.filter { it.kind == kind }.map { it.index }.toSet()
        val goneMarks = gone(StepKind.MARK); val goneLocals = gone(StepKind.LOCAL); val goneEffects = gone(StepKind.EFFECT)
        return out.copy(
            geometry = g, tone = t,
            marks = if (goneMarks.isEmpty()) out.marks else out.marks.filterIndexed { i, _ -> i !in goneMarks },
            locals = if (goneLocals.isEmpty()) out.locals else out.locals.filterIndexed { i, _ -> i !in goneLocals },
            effects = if (goneEffects.isEmpty()) out.effects else out.effects.filterIndexed { i, _ -> i !in goneEffects }
        )
    }

    fun without(r: Recipe, step: StepKey): Recipe = without(r, setOf(step))

    /** Steps listed one per item, so deleting one moves the later ones up. */
    private val INDEXED = setOf(StepKind.MARK, StepKind.LOCAL, StepKind.EFFECT)

    /**
     * After deleting the layer at [deleted], the hidden set with later layers' indices moved up
     * one, so what the user hid stays hidden.
     */
    fun afterDelete(hidden: Set<StepKey>, deleted: StepKey): Set<StepKey> = hidden.mapNotNull { k ->
        when {
            k == deleted -> null
            deleted.kind in INDEXED && k.kind == deleted.kind && k.index > deleted.index -> k.copy(index = k.index - 1)
            else -> k
        }
    }.toSet()

    /**
     * "Paste edits": the copied recipe's look of the picture - tone, curves, look, colour mix,
     * scan and frame - over [target], which keeps everything that belongs to its own picture:
     * its geometry, its layers, its cut-out and its expanded canvas.
     */
    fun paste(target: Recipe, copied: Recipe): Recipe = target.copy(
        tone = copied.tone, curves = copied.curves, look = copied.look, lookStrength = copied.lookStrength,
        mix = copied.mix, scan = copied.scan, frame = copied.frame, effects = copied.effects,
        portrait = copied.portrait.copy(strokes = target.portrait.strokes, taps = target.portrait.taps)
    )
}
