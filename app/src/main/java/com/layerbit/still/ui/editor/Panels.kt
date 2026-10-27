package com.layerbit.still.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.Crop
import com.layerbit.still.engine.Curves
import com.layerbit.still.engine.Frame
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.engine.Look
import com.layerbit.still.engine.Looks
import com.layerbit.still.engine.Mark
import com.layerbit.still.engine.MarkKind
import com.layerbit.still.engine.Pt
import com.layerbit.still.engine.Quad
import com.layerbit.still.engine.Recipe
import com.layerbit.still.engine.Tone
import com.layerbit.still.files.ImageStore
import com.layerbit.still.image.Budget
import com.layerbit.still.image.ExportOptions
import com.layerbit.still.image.OutputFormat
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.components.Divider
import com.layerbit.still.ui.components.LabelledValue
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.components.ToggleRow
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.roundToInt

/** What every panel needs: the recipe, how to change it, and the settings that matter. */
class PanelScope(
    val recipe: Recipe,
    val draft: (Recipe) -> Unit,
    val commit: () -> Unit,
    val change: ((Recipe) -> Recipe) -> Unit,
    val haptics: Boolean,
    val source: Geometry2D.Size
) {
    fun tone(block: (Tone) -> Tone) = draft(recipe.copy(tone = block(recipe.tone)))
    fun toneCommit(block: (Tone) -> Tone) = change { it.copy(tone = block(it.tone)) }
}

@Composable
private fun PanelScope.ToneKnob(label: String, value: Float, range: ClosedFloatingPointRange<Float> = -1f..1f, set: (Tone, Float) -> Tone) {
    Knob(label, value, range, onChange = { v -> tone { set(it, v) } }, onDone = commit, onReset = { toneCommit { set(it, 0f) } }, haptics = haptics)
}

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, modifier = Modifier.padding(top = 2.dp))

// ------------------------------------------------------------------ crop

@Composable
fun CropPanel(s: PanelScope, lockAspect: Float, onLockAspect: (Float) -> Unit) {
    val g = s.recipe.geometry
    val turned = Geometry2D.turned(s.source, g.quarterTurns)
    val canvas = if (kotlin.math.abs(g.straighten) < 0.01f) turned else Geometry2D.rotatedBounds(turned.width, turned.height, g.straighten)
    val aspects = listOf(0f to stringResource(R.string.crop_free), -1f to stringResource(R.string.crop_original), 1f to "1:1", 4f / 5f to "4:5", 4f / 3f to "4:3", 3f / 2f to "3:2", 16f / 9f to "16:9", 9f / 16f to "9:16")
    ChipRow(aspects, lockAspect, onSelect = { a ->
        onLockAspect(a)
        val ratio = if (a == -1f) s.source.aspect else a
        if (ratio > 0f) s.change { it.copy(geometry = it.geometry.copy(crop = Geometry2D.aspectCrop(canvas, ratio))) }
    })
    Hint(stringResource(R.string.crop_hint))
    Knob(stringResource(R.string.crop_straighten), g.straighten, -45f..45f, onChange = { v -> s.draft(s.recipe.copy(geometry = g.copy(straighten = v))) }, onDone = s.commit,
        onReset = { s.change { it.copy(geometry = it.geometry.copy(straighten = 0f)) } }, haptics = s.haptics, format = { "${(it * 10).roundToInt() / 10f}°" })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SecondaryButton(stringResource(R.string.crop_turn_left), onClick = { s.change { it.copy(geometry = it.geometry.copy(rotation = (it.geometry.rotation + 270) % 360, crop = Geometry2D.rotateCrop(it.geometry.crop, 270, false, false))) } }, modifier = Modifier.weight(1f), tall = false)
        SecondaryButton(stringResource(R.string.crop_turn_right), onClick = { s.change { it.copy(geometry = it.geometry.copy(rotation = (it.geometry.rotation + 90) % 360, crop = Geometry2D.rotateCrop(it.geometry.crop, 90, false, false))) } }, modifier = Modifier.weight(1f), tall = false)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SecondaryButton(stringResource(R.string.crop_flip_h), onClick = { s.change { it.copy(geometry = it.geometry.copy(flipX = !it.geometry.flipX, crop = Geometry2D.rotateCrop(it.geometry.crop, 0, true, false))) } }, modifier = Modifier.weight(1f), tall = false)
        SecondaryButton(stringResource(R.string.crop_flip_v), onClick = { s.change { it.copy(geometry = it.geometry.copy(flipY = !it.geometry.flipY, crop = Geometry2D.rotateCrop(it.geometry.crop, 0, false, true))) } }, modifier = Modifier.weight(1f), tall = false)
    }
    ResizeRow(s)
    TextButton(onClick = { onLockAspect(0f); s.change { it.copy(geometry = it.geometry.copy(crop = Crop.FULL, straighten = 0f, rotation = 0, flipX = false, flipY = false, width = 0, height = 0)) } }) {
        Text(stringResource(R.string.crop_reset), color = StillColors.Muted)
    }
}

/** Output pixels, inside the crop panel: exact, or the natural size. */
@Composable
private fun ResizeRow(s: PanelScope) {
    val g = s.recipe.geometry
    val natural = Geometry2D.canvasSize(s.source, g.copy(width = 0, height = 0))
    var w by rememberSaveable(g.width, natural.width) { mutableStateOf((if (g.width > 0) g.width else natural.width).toString()) }
    var h by rememberSaveable(g.height, natural.height) { mutableStateOf((if (g.height > 0) g.height else natural.height).toString()) }
    var lock by rememberSaveable { mutableStateOf(true) }
    SectionHeading(stringResource(R.string.crop_size_heading), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(w, { v -> w = v.filter { it.isDigit() }.take(5); if (lock) v.toIntOrNull()?.let { h = (it / natural.aspect).roundToInt().coerceAtLeast(1).toString() } }, label = { Text(stringResource(R.string.crop_width)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), colors = fieldColours())
        OutlinedTextField(h, { v -> h = v.filter { it.isDigit() }.take(5); if (lock) v.toIntOrNull()?.let { w = (it * natural.aspect).roundToInt().coerceAtLeast(1).toString() } }, label = { Text(stringResource(R.string.crop_height)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), colors = fieldColours())
    }
    ToggleRow(stringResource(R.string.crop_lock_aspect), lock, { lock = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.crop_apply_size), onClick = {
            val wi = w.toIntOrNull(); val hi = h.toIntOrNull()
            if (wi != null && hi != null && wi in 1..Budget.MAX_EDGE && hi in 1..Budget.MAX_EDGE && wi.toLong() * hi <= Budget.exportPixels) s.change { it.copy(geometry = it.geometry.copy(width = wi, height = hi)) }
        }, modifier = Modifier.weight(1f), tall = false)
        SecondaryButton(stringResource(R.string.crop_natural_size), onClick = { s.change { it.copy(geometry = it.geometry.copy(width = 0, height = 0)) } }, modifier = Modifier.weight(1f), tall = false, enabled = g.width > 0)
    }
    Hint(stringResource(R.string.crop_size_hint, natural.width, natural.height, Budget.exportPixels / 1_000_000))
}

@Composable
fun fieldColours() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = StillColors.Band, unfocusedBorderColor = StillColors.Border, focusedTextColor = StillColors.Text, unfocusedTextColor = StillColors.Text,
    focusedLabelColor = StillColors.Band, unfocusedLabelColor = StillColors.Dim, cursorColor = StillColors.Band
)

// ------------------------------------------------------------------ perspective

@Composable
fun PerspectivePanel(s: PanelScope) {
    Text(stringResource(R.string.perspective_hint), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        SecondaryButton(stringResource(R.string.perspective_inset), onClick = { s.change { it.copy(geometry = it.geometry.copy(quad = Quad(Pt(0.08f, 0.08f), Pt(0.92f, 0.08f), Pt(0.92f, 0.92f), Pt(0.08f, 0.92f)))) } }, modifier = Modifier.weight(1f), tall = false)
        SecondaryButton(stringResource(R.string.perspective_reset), onClick = { s.change { it.copy(geometry = it.geometry.copy(quad = Quad.IDENTITY)) } }, modifier = Modifier.weight(1f), tall = false, enabled = !s.recipe.geometry.quad.isIdentity)
    }
    Hint(stringResource(R.string.perspective_note))
}

// ------------------------------------------------------------------ light and colour

@Composable
fun LightPanel(s: PanelScope, histogram: com.layerbit.still.engine.Histogram?, showHistogram: Boolean, onAuto: () -> Unit) {
    val t = s.recipe.tone
    if (showHistogram) HistogramView(histogram, Modifier.padding(bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        PrimaryButton(stringResource(R.string.light_auto), onClick = onAuto, modifier = Modifier.weight(1f), tall = false, enabled = histogram?.let { com.layerbit.still.engine.AutoEnhance.worthwhile(it) } ?: false)
        SecondaryButton(stringResource(R.string.light_reset), onClick = { s.change { it.copy(tone = it.tone.copy(exposure = 0f, contrast = 0f, highlights = 0f, shadows = 0f, whites = 0f, blacks = 0f)) } }, modifier = Modifier.weight(1f), tall = false)
    }
    Spacer(Modifier.height(6.dp))
    s.ToneKnob(stringResource(R.string.light_exposure), t.exposure, -2f..2f) { a, v -> a.copy(exposure = v) }
    s.ToneKnob(stringResource(R.string.light_contrast), t.contrast) { a, v -> a.copy(contrast = v) }
    s.ToneKnob(stringResource(R.string.light_highlights), t.highlights) { a, v -> a.copy(highlights = v) }
    s.ToneKnob(stringResource(R.string.light_shadows), t.shadows) { a, v -> a.copy(shadows = v) }
    s.ToneKnob(stringResource(R.string.light_whites), t.whites) { a, v -> a.copy(whites = v) }
    s.ToneKnob(stringResource(R.string.light_blacks), t.blacks) { a, v -> a.copy(blacks = v) }
    Hint(stringResource(R.string.light_hint))
}

@Composable
fun ColourPanel(s: PanelScope) {
    val t = s.recipe.tone
    s.ToneKnob(stringResource(R.string.colour_temperature), t.temperature) { a, v -> a.copy(temperature = v) }
    s.ToneKnob(stringResource(R.string.colour_tint), t.tint) { a, v -> a.copy(tint = v) }
    s.ToneKnob(stringResource(R.string.colour_vibrance), t.vibrance) { a, v -> a.copy(vibrance = v) }
    s.ToneKnob(stringResource(R.string.colour_saturation), t.saturation) { a, v -> a.copy(saturation = v) }
    s.ToneKnob(stringResource(R.string.colour_hue), t.hue) { a, v -> a.copy(hue = v) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        SecondaryButton(stringResource(R.string.colour_mono), onClick = { s.toneCommit { it.copy(saturation = -1f) } }, modifier = Modifier.weight(1f), tall = false)
        SecondaryButton(stringResource(R.string.light_reset), onClick = { s.toneCommit { it.copy(temperature = 0f, tint = 0f, vibrance = 0f, saturation = 0f, hue = 0f) } }, modifier = Modifier.weight(1f), tall = false)
    }
}

@Composable
fun CurvesPanel(s: PanelScope) {
    var channel by rememberSaveable { mutableStateOf("master") }
    val c = s.recipe.curves
    ChipRow(listOf("master" to stringResource(R.string.curves_master), "red" to stringResource(R.string.curves_red), "green" to stringResource(R.string.curves_green), "blue" to stringResource(R.string.curves_blue)), channel, { channel = it })
    val points = when (channel) { "red" -> c.red; "green" -> c.green; "blue" -> c.blue; else -> c.master }
    val accent = when (channel) { "red" -> androidx.compose.ui.graphics.Color(0xFFE8705F); "green" -> androidx.compose.ui.graphics.Color(0xFF4CCB8F); "blue" -> androidx.compose.ui.graphics.Color(0xFF5BA9E8); else -> StillColors.Band }
    fun with(p: List<Pt>): Curves = when (channel) { "red" -> c.copy(red = p); "green" -> c.copy(green = p); "blue" -> c.copy(blue = p); else -> c.copy(master = p) }
    CurveEditor(points, onChange = { p -> s.draft(s.recipe.copy(curves = with(p))) }, onDone = s.commit, modifier = Modifier.padding(top = 8.dp), accent = accent)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SecondaryButton(stringResource(R.string.curves_reset_channel), onClick = { s.change { it.copy(curves = with(Curves.IDENTITY_POINTS)) } }, modifier = Modifier.weight(1f), tall = false)
        SecondaryButton(stringResource(R.string.curves_reset_all), onClick = { s.change { it.copy(curves = Curves()) } }, modifier = Modifier.weight(1f), tall = false, enabled = !c.isIdentity)
    }
    Hint(stringResource(R.string.curves_hint))
}

// ------------------------------------------------------------------ looks and presets

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LooksPanel(s: PanelScope, presets: List<ImageStore.Preset>, onSavePreset: () -> Unit, onApplyPreset: (ImageStore.Preset) -> Unit, onRemovePreset: (ImageStore.Preset) -> Unit) {
    val labels = Look.entries.map { it to lookLabel(it) }
    ChipRow(labels, s.recipe.look, { look -> s.change { it.copy(look = look, lookStrength = if (it.lookStrength == 0f) 1f else it.lookStrength) } })
    if (s.recipe.look != Look.NONE) {
        Knob(stringResource(R.string.looks_strength), s.recipe.lookStrength, 0f..1f, onChange = { v -> s.draft(s.recipe.copy(lookStrength = v)) }, onDone = s.commit, onReset = { s.change { it.copy(lookStrength = 1f) } }, haptics = s.haptics)
        val moved = Looks.recipeFor(s.recipe.look).tone
        Hint(stringResource(R.string.looks_moves, describeTone(moved)))
    }
    SectionHeading(stringResource(R.string.looks_presets), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    if (presets.isEmpty()) Hint(stringResource(R.string.looks_no_presets))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in presets) {
            SecondaryButton(p.name, onClick = { onApplyPreset(p) }, tall = false)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PrimaryButton(stringResource(R.string.looks_save_preset), onClick = onSavePreset, modifier = Modifier.weight(1f), tall = false, enabled = !s.recipe.isIdentity)
        if (presets.isNotEmpty()) {
            var confirm by remember { mutableStateOf(false) }
            SecondaryButton(stringResource(if (confirm) R.string.looks_remove_last_confirm else R.string.looks_remove_last), onClick = { if (confirm) { onRemovePreset(presets.last()); confirm = false } else confirm = true }, modifier = Modifier.weight(1f), tall = false)
        }
    }
}

@Composable
fun lookLabel(look: Look): String = stringResource(
    when (look) {
        Look.NONE -> R.string.look_none; Look.MONO -> R.string.look_mono; Look.NOIR -> R.string.look_noir; Look.SEPIA -> R.string.look_sepia
        Look.WARM -> R.string.look_warm; Look.COOL -> R.string.look_cool; Look.FADE -> R.string.look_fade; Look.MATTE -> R.string.look_matte
        Look.VIVID -> R.string.look_vivid; Look.FILM -> R.string.look_film; Look.CHROME -> R.string.look_chrome
        Look.TEAL_ORANGE -> R.string.look_teal_orange; Look.PORTRAIT -> R.string.look_portrait; Look.DOCUMENT -> R.string.look_document
    }
)

/** "contrast +20, saturation -100": what a look moved, so it is never a secret. */
fun describeTone(t: Tone): String {
    val parts = ArrayList<String>()
    fun add(name: String, v: Float) { if (v != 0f) parts += "$name ${if (v > 0) "+" else ""}${(v * 100).roundToInt()}" }
    add("exposure", t.exposure); add("contrast", t.contrast); add("highlights", t.highlights); add("shadows", t.shadows)
    add("whites", t.whites); add("blacks", t.blacks); add("saturation", t.saturation); add("vibrance", t.vibrance)
    add("temperature", t.temperature); add("tint", t.tint); add("fade", t.fade); add("clarity", t.clarity); add("dehaze", t.dehaze)
    add("sharpness", t.sharpness); add("vignette", t.vignette); add("grain", t.grain)
    return parts.joinToString(", ")
}

// ------------------------------------------------------------------ detail

@Composable
fun DetailPanel(s: PanelScope) {
    val t = s.recipe.tone
    s.ToneKnob(stringResource(R.string.detail_sharpness), t.sharpness, 0f..1f) { a, v -> a.copy(sharpness = v) }
    s.ToneKnob(stringResource(R.string.detail_clarity), t.clarity) { a, v -> a.copy(clarity = v) }
    s.ToneKnob(stringResource(R.string.detail_dehaze), t.dehaze, 0f..1f) { a, v -> a.copy(dehaze = v) }
    s.ToneKnob(stringResource(R.string.detail_fade), t.fade, 0f..1f) { a, v -> a.copy(fade = v) }
    s.ToneKnob(stringResource(R.string.detail_vignette), t.vignette) { a, v -> a.copy(vignette = v) }
    s.ToneKnob(stringResource(R.string.detail_grain), t.grain, 0f..1f) { a, v -> a.copy(grain = v) }
    Hint(stringResource(R.string.detail_hint))
}

// ------------------------------------------------------------------ markup and retouch

/** The tool a stroke makes, with its colour, size and text. Kept by the screen across panels. */
data class Brush(val kind: MarkKind = MarkKind.DRAW, val color: Int = Mark.DEFAULT_COLOR, val width: Float = 0.02f, val text: String = "", val cloneSource: Pt? = null, val pickingSource: Boolean = false)

val InkColours = listOf(0xFFE62E80.toInt(), 0xFFF2A531.toInt(), 0xFF4CCB8F.toInt(), 0xFF5BA9E8.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt())

@Composable
fun MarkupPanel(brush: Brush, onBrush: (Brush) -> Unit, onPlaceCentre: () -> Unit, haptics: Boolean) {
    val kinds = listOf(MarkKind.DRAW to stringResource(R.string.mark_draw), MarkKind.TEXT to stringResource(R.string.mark_text), MarkKind.ARROW to stringResource(R.string.mark_arrow), MarkKind.BOX to stringResource(R.string.mark_box), MarkKind.ELLIPSE to stringResource(R.string.mark_ellipse))
    ChipRow(kinds, brush.kind, { onBrush(brush.copy(kind = it)) })
    ColourRow(InkColours, brush.color, { onBrush(brush.copy(color = it)) }, Modifier.padding(top = 10.dp))
    Knob(stringResource(if (brush.kind == MarkKind.TEXT) R.string.mark_text_size else R.string.mark_size), brush.width, 0.004f..0.12f, onChange = { onBrush(brush.copy(width = it)) }, onDone = {}, onReset = { onBrush(brush.copy(width = 0.02f)) }, haptics = haptics, format = { "${(it * 1000).roundToInt()}" })
    if (brush.kind == MarkKind.TEXT) {
        OutlinedTextField(brush.text, { onBrush(brush.copy(text = it.take(Mark.MAX_TEXT))) }, label = { Text(stringResource(R.string.mark_text_label)) }, modifier = Modifier.fillMaxWidth(), colors = fieldColours(), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            SecondaryButton(stringResource(R.string.mark_place_centre), onClick = onPlaceCentre, tall = false, enabled = brush.text.isNotBlank())
        }
        Hint(stringResource(R.string.mark_text_hint))
    } else Hint(stringResource(R.string.mark_hint))
}

@Composable
fun RetouchPanel(brush: Brush, onBrush: (Brush) -> Unit, haptics: Boolean) {
    val kinds = listOf(MarkKind.BLUR to stringResource(R.string.retouch_blur), MarkKind.PIXELATE to stringResource(R.string.retouch_pixelate), MarkKind.COVER to stringResource(R.string.retouch_cover), MarkKind.ERASE to stringResource(R.string.retouch_erase), MarkKind.CLONE to stringResource(R.string.retouch_clone))
    val kind = if (brush.kind in kinds.map { it.first }) brush.kind else MarkKind.BLUR
    ChipRow(kinds, kind, { onBrush(brush.copy(kind = it, pickingSource = it == MarkKind.CLONE && brush.cloneSource == null)) })
    if (kind == MarkKind.COVER) ColourRow(InkColours, brush.color, { onBrush(brush.copy(color = it)) }, Modifier.padding(top = 10.dp))
    Knob(stringResource(R.string.mark_size), brush.width, 0.004f..0.2f, onChange = { onBrush(brush.copy(width = it)) }, onDone = {}, onReset = { onBrush(brush.copy(width = 0.03f)) }, haptics = haptics, format = { "${(it * 1000).roundToInt()}" })
    when (kind) {
        MarkKind.CLONE -> {
            SecondaryButton(stringResource(if (brush.cloneSource == null) R.string.retouch_pick_source else R.string.retouch_change_source), onClick = { onBrush(brush.copy(pickingSource = true)) }, tall = false)
            Hint(stringResource(if (brush.pickingSource) R.string.retouch_tap_source else R.string.retouch_clone_hint))
        }
        MarkKind.ERASE -> Hint(stringResource(R.string.retouch_erase_hint))
        MarkKind.COVER -> Hint(stringResource(R.string.retouch_cover_hint))
        else -> Hint(stringResource(R.string.retouch_blur_hint))
    }
}

// ------------------------------------------------------------------ layers

@Composable
fun LayersPanel(s: PanelScope, onEditText: (Int) -> Unit) {
    val marks = s.recipe.marks
    if (marks.isEmpty()) { Hint(stringResource(R.string.layers_none)); return }
    Hint(stringResource(R.string.layers_hint))
    for (index in marks.indices.reversed()) {
        val m = marks[index]
        Column(Modifier.padding(top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(m.visible, { v -> s.change { it.copy(marks = it.marks.toMutableList().apply { set(index, m.copy(visible = v)) }) } })
                Text("${index + 1}. ${markLabel(m.kind)}${if (m.text.isNotBlank()) " · " + m.text.take(18) else ""}", style = MaterialTheme.typography.bodyMedium, color = StillColors.Text, modifier = Modifier.weight(1f))
                if (m.kind == MarkKind.TEXT || m.kind == MarkKind.WATERMARK) TextButton(onClick = { onEditText(index) }) { Text(stringResource(R.string.layers_edit), color = StillColors.BandBright) }
                TextButton(onClick = { s.change { it.copy(marks = it.marks.filterIndexed { i, _ -> i != index }) } }) { Text(stringResource(R.string.app_delete), color = StillColors.Failed) }
            }
            if (m.kind != MarkKind.COVER) {
                Knob(stringResource(R.string.layers_opacity), m.opacity, 0f..1f, onChange = { v -> s.draft(s.recipe.copy(marks = s.recipe.marks.toMutableList().apply { set(index, m.copy(opacity = v)) })) }, onDone = s.commit,
                    onReset = { s.change { it.copy(marks = it.marks.toMutableList().apply { set(index, m.copy(opacity = 1f)) }) } }, haptics = s.haptics)
            }
            Row {
                TextButton(enabled = index < marks.lastIndex, onClick = { s.change { it.copy(marks = it.marks.toMutableList().apply { val x = removeAt(index); add(index + 1, x) }) } }) { Text(stringResource(R.string.layers_up), color = StillColors.Muted) }
                TextButton(enabled = index > 0, onClick = { s.change { it.copy(marks = it.marks.toMutableList().apply { val x = removeAt(index); add(index - 1, x) }) } }) { Text(stringResource(R.string.layers_down), color = StillColors.Muted) }
            }
            Divider()
        }
    }
}

@Composable
fun markLabel(kind: MarkKind): String = stringResource(
    when (kind) {
        MarkKind.DRAW -> R.string.mark_draw; MarkKind.TEXT -> R.string.mark_text; MarkKind.ARROW -> R.string.mark_arrow; MarkKind.BOX -> R.string.mark_box
        MarkKind.ELLIPSE -> R.string.mark_ellipse; MarkKind.COVER -> R.string.retouch_cover; MarkKind.PIXELATE -> R.string.retouch_pixelate
        MarkKind.BLUR -> R.string.retouch_blur; MarkKind.ERASE -> R.string.retouch_erase; MarkKind.CLONE -> R.string.retouch_clone; MarkKind.WATERMARK -> R.string.tool_watermark_title
    }
)

// ------------------------------------------------------------------ frame

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FramePanel(s: PanelScope) {
    val f = s.recipe.frame
    fun set(block: (Frame) -> Frame) = s.change { it.copy(frame = block(it.frame)) }
    fun live(block: (Frame) -> Frame) = s.draft(s.recipe.copy(frame = block(s.recipe.frame)))
    val aspects = listOf(0f to stringResource(R.string.crop_original), 1f to "1:1", 4f / 5f to "4:5", 16f / 9f to "16:9", 9f / 16f to "9:16", 3f / 2f to "3:2")
    SectionHeading(stringResource(R.string.frame_pad_to), modifier = Modifier.padding(bottom = 6.dp))
    ChipRow(aspects, f.aspect, { a -> set { it.copy(aspect = a) } })
    Knob(stringResource(R.string.frame_padding), f.padding, 0f..0.3f, onChange = { v -> live { it.copy(padding = v) } }, onDone = s.commit, onReset = { set { it.copy(padding = 0f) } }, haptics = s.haptics)
    Knob(stringResource(R.string.frame_border), f.border, 0f..0.1f, onChange = { v -> live { it.copy(border = v) } }, onDone = s.commit, onReset = { set { it.copy(border = 0f) } }, haptics = s.haptics)
    Knob(stringResource(R.string.frame_corners), f.cornerRadius, 0f..0.3f, onChange = { v -> live { it.copy(cornerRadius = v) } }, onDone = s.commit, onReset = { set { it.copy(cornerRadius = 0f) } }, haptics = s.haptics)
    SectionHeading(stringResource(R.string.frame_background), modifier = Modifier.padding(top = 6.dp))
    FlowRow {
        Swatch(0xFFFFFFFF.toInt(), stringResource(R.string.frame_white), f.background == 0xFFFFFFFF.toInt() && !f.blurredFill) { set { it.copy(background = 0xFFFFFFFF.toInt(), blurredFill = false) } }
        Swatch(0xFF111111.toInt(), stringResource(R.string.frame_black), f.background == 0xFF111111.toInt() && !f.blurredFill) { set { it.copy(background = 0xFF111111.toInt(), blurredFill = false) } }
        Swatch(0xFFF5F4EF.toInt(), stringResource(R.string.frame_paper), f.background == 0xFFF5F4EF.toInt() && !f.blurredFill) { set { it.copy(background = 0xFFF5F4EF.toInt(), blurredFill = false) } }
        Swatch(0xFF0B1220.toInt(), stringResource(R.string.frame_ink), f.background == 0xFF0B1220.toInt() && !f.blurredFill) { set { it.copy(background = 0xFF0B1220.toInt(), blurredFill = false) } }
        Swatch(0, stringResource(R.string.frame_transparent), f.background == 0 && !f.blurredFill) { set { it.copy(background = 0, blurredFill = false) } }
        Swatch(0xFF7A8699.toInt(), stringResource(R.string.frame_blurred), f.blurredFill) { set { it.copy(blurredFill = true) } }
    }
    SectionHeading(stringResource(R.string.frame_border_colour), modifier = Modifier.padding(top = 6.dp))
    ColourRow(InkColours, f.borderColor, { c -> set { it.copy(borderColor = c) } }, Modifier.padding(top = 4.dp))
    Hint(stringResource(R.string.frame_hint))
}

// ------------------------------------------------------------------ details

@Composable
fun DetailsPanel(lines: List<Pair<String, String>>, onStrip: () -> Unit) {
    for ((k, v) in lines) LabelledValue(k, v, Modifier.padding(vertical = 4.dp))
    Hint(stringResource(R.string.details_note))
    SecondaryButton(stringResource(R.string.details_strip_now), onClick = onStrip, modifier = Modifier.padding(top = 8.dp), tall = false)
}

// ------------------------------------------------------------------ export

@Composable
fun ExportPanel(options: ExportOptions, onOptions: (ExportOptions) -> Unit, canvas: Geometry2D.Size?, onPrepare: () -> Unit, haptics: Boolean) {
    val formats = OutputFormat.entries.filter { it.available }.map { it to it.label }
    ChipRow(formats, options.format, { f -> onOptions(options.copy(format = f, targetBytes = if (f.lossy) options.targetBytes else 0)) })
    Hint(stringResource(when (options.format) { OutputFormat.JPEG -> R.string.export_jpeg_note; OutputFormat.PNG -> R.string.export_png_note; OutputFormat.WEBP -> R.string.export_webp_note; OutputFormat.WEBP_LOSSLESS -> R.string.export_webp_lossless_note }))
    if (options.format.lossy) {
        Knob(stringResource(R.string.export_quality), options.quality / 100f, 0.4f..1f, onChange = { onOptions(options.copy(quality = (it * 100).roundToInt())) }, onDone = {}, onReset = { onOptions(options.copy(quality = 92)) }, haptics = haptics, format = { "${(it * 100).roundToInt()}" })
        var target by rememberSaveable { mutableStateOf(if (options.targetBytes > 0) (options.targetBytes / 1000).toString() else "") }
        var unit by rememberSaveable { mutableStateOf("KB") }
        fun push(t: String, u: String) {
            val n = t.toFloatOrNull()
            onOptions(options.copy(targetBytes = if (n != null && n > 0f && n < 100_000f) com.layerbit.still.engine.FitSize.limitBytes(n, u) else 0))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedTextField(target, { v -> target = v.filter { it.isDigit() || it == '.' }.take(7); push(target, unit) }, label = { Text(stringResource(R.string.export_target)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), colors = fieldColours())
            Spacer(Modifier.width(4.dp))
            ChipRow(listOf("KB" to "KB", "MB" to "MB"), unit, { unit = it; push(target, it) }, Modifier.width(130.dp))
        }
        ToggleRow(stringResource(R.string.export_allow_shrink), options.allowShrink, { onOptions(options.copy(allowShrink = it)) }, detail = stringResource(R.string.export_allow_shrink_detail))
    }
    if (options.format == OutputFormat.JPEG) ToggleRow(stringResource(R.string.export_keep_camera), options.keepCameraFields, { onOptions(options.copy(keepCameraFields = it)) }, detail = stringResource(R.string.export_keep_camera_detail))
    if (canvas != null) Hint(stringResource(R.string.export_expected, canvas.width, canvas.height))
    PrimaryButton(stringResource(R.string.export_prepare), onClick = onPrepare, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = options.valid)
    Hint(stringResource(R.string.export_note))
}
