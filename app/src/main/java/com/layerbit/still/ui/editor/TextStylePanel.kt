package com.layerbit.still.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.TextAlign
import com.layerbit.still.engine.TextFont
import com.layerbit.still.engine.TextPreset
import com.layerbit.still.engine.TextStyle
import com.layerbit.still.image.TextPainter
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Colours for outlines, shadows and boxes: black and white first, then the ink colours and paper. */
private val StyleColours = listOf(TextStyle.BLACK, TextStyle.WHITE, 0xFFE62E80.toInt(), 0xFFF2A531.toInt(), 0xFF4CCB8F.toInt(), 0xFF5BA9E8.toInt(), 0xFFF4EBD9.toInt())

@Composable
fun presetLabel(p: TextPreset): String = stringResource(
    when (p) {
        TextPreset.CLASSIC -> R.string.text_preset_classic; TextPreset.POSTER -> R.string.text_preset_poster
        TextPreset.CAPTION -> R.string.text_preset_caption; TextPreset.NEON -> R.string.text_preset_neon
        TextPreset.OUTLINE -> R.string.text_preset_outline; TextPreset.HANDWRITTEN -> R.string.text_preset_handwritten
        TextPreset.ELEGANT -> R.string.text_preset_elegant; TextPreset.TYPEWRITER -> R.string.text_preset_typewriter
        TextPreset.MARKER -> R.string.text_preset_marker; TextPreset.SHADOWED -> R.string.text_preset_shadowed
        TextPreset.BADGE -> R.string.text_preset_badge; TextPreset.QUOTE -> R.string.text_preset_quote
        TextPreset.STICKER -> R.string.text_preset_sticker; TextPreset.ARCHED -> R.string.text_preset_arched
        TextPreset.SMILE -> R.string.text_preset_smile; TextPreset.LABEL -> R.string.text_preset_label
    }
)

/**
 * The Styles strip: every preset drawn live with the user's own words and colour, by the same
 * painter the export uses. A tap applies it.
 */
@Composable
fun TextStyleStrip(text: String, color: Int, style: TextStyle, onPick: (Int, TextStyle) -> Unit, modifier: Modifier = Modifier) {
    val sample = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(14) ?: stringResource(R.string.text_style_sample)
    // Presets follow the user's colour, kept in a badge's box or a neon glow when one made the layer.
    val ink = TextPreset.inkOf(color, style)
    val current = TextPreset.matching(ink, color, style)
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in TextPreset.entries) {
            val (c, s) = p.apply(ink)
            val on = p == current
            val name = presetLabel(p)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                Canvas(
                    Modifier.size(96.dp, 60.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF6E7A88), Color(0xFF2E3440))))
                        .border(if (on) 2.dp else 1.dp, if (on) StillColors.BandBright else StillColors.Border, RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { onPick(c, s) }
                        .semantics { contentDescription = name; selected = on }
                ) {
                    // One line, fitted inside the tile whatever the style's box, curve or shadow.
                    val block = TextPainter.layout(sample, s, 1000f)
                    val b = block.bounds
                    val px = min(size.height * 0.34f, min(size.width * 0.86f / b.width.coerceAtLeast(0.1f), size.height * 0.82f / b.height.coerceAtLeast(0.1f)))
                    val cx = size.width / 2f - (b.left + b.right) / 2f * px
                    val cy = size.height / 2f - (b.top + b.bottom) / 2f * px
                    drawIntoCanvas { TextPainter.draw(it.nativeCanvas, sample, c, 1f, s, px, 1000f * px, cx, cy, 0f) }
                }
                Text(name, style = MaterialTheme.typography.labelSmall, color = if (on) StillColors.BandBright else StillColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/**
 * The Styles strip and, below it, every control a style is made of. [onDraft] follows a slider
 * as it moves and [onDone] ends that move; [onSet] is a single change such as a chip or a preset.
 */
@Composable
fun TextStyleEditor(
    text: String, color: Int, style: TextStyle, haptics: Boolean,
    onSet: (Int, TextStyle) -> Unit, onDraft: (Int, TextStyle) -> Unit = onSet, onDone: () -> Unit = {}
) {
    Text(stringResource(R.string.text_styles), style = MaterialTheme.typography.titleSmall, color = StillColors.Text, modifier = Modifier.padding(top = 8.dp))
    TextStyleStrip(text, color, style, onSet)
    var section by rememberSaveable { mutableIntStateOf(0) }
    val sections = listOf(0 to stringResource(R.string.text_section_font), 1 to stringResource(R.string.text_section_spacing), 2 to stringResource(R.string.text_section_outline),
        3 to stringResource(R.string.text_section_shadow), 4 to stringResource(R.string.text_section_box), 5 to stringResource(R.string.text_section_curve))
    ChipRow(sections, section, { section = it }, Modifier.padding(top = 4.dp))
    fun set(s: TextStyle) = onSet(color, s)
    fun draft(s: TextStyle) = onDraft(color, s)
    val d = TextStyle.DEFAULT
    when (section) {
        0 -> {
            ChipRow(listOf(TextFont.SYSTEM to stringResource(R.string.text_font_system), TextFont.GROTESK to stringResource(R.string.text_font_grotesk), TextFont.SERIF to stringResource(R.string.text_font_serif),
                TextFont.SCRIPT to stringResource(R.string.text_font_script), TextFont.MONO to stringResource(R.string.text_font_mono)), style.font, { set(style.copy(font = it)) })
            val weight = listOf(400, 500, 600, 700).minBy { kotlin.math.abs(it - style.weight) }
            ChipRow(listOf(400 to stringResource(R.string.text_weight_regular), 500 to stringResource(R.string.text_weight_medium), 600 to stringResource(R.string.text_weight_semibold), 700 to stringResource(R.string.text_weight_bold)),
                weight, { set(style.copy(weight = it)) })
            ChipRow(listOf(TextAlign.LEFT to stringResource(R.string.text_align_left), TextAlign.CENTER to stringResource(R.string.text_align_centre), TextAlign.RIGHT to stringResource(R.string.text_align_right)),
                style.align, { set(style.copy(align = it)) })
            ChipRow(listOf(false to stringResource(R.string.text_case_typed), true to stringResource(R.string.text_case_caps)), style.caps, { set(style.copy(caps = it)) })
            ChipRow(listOf(false to stringResource(R.string.text_upright), true to stringResource(R.string.text_slant)), style.italic, { set(style.copy(italic = it)) })
        }
        1 -> {
            Knob(stringResource(R.string.text_letter_spacing), style.letterSpacing, -0.1f..0.5f, onChange = { draft(style.copy(letterSpacing = it)) }, onDone = onDone, onReset = { set(style.copy(letterSpacing = d.letterSpacing)) }, haptics = haptics)
            Knob(stringResource(R.string.text_line_height), style.lineHeight, 0.7f..2f, onChange = { draft(style.copy(lineHeight = it)) }, onDone = onDone, onReset = { set(style.copy(lineHeight = d.lineHeight)) }, haptics = haptics,
                format = { "×" + String.format(java.util.Locale.getDefault(), "%.2f", it) })
        }
        2 -> {
            Knob(stringResource(R.string.text_outline_width), style.outline, 0f..0.2f, onChange = { draft(style.copy(outline = it)) }, onDone = onDone, onReset = { set(style.copy(outline = 0f)) }, haptics = haptics, format = { "${(it * 500).roundToInt()}" })
            ColourRow(StyleColours, style.outlineColor, { set(style.copy(outlineColor = it, outline = if (style.outline > 0f) style.outline else 0.05f)) }, Modifier.padding(top = 4.dp))
        }
        3 -> {
            val distance = hypot(style.shadowX, style.shadowY)
            val direction = if (distance > 0f) Math.toDegrees(atan2(style.shadowY, style.shadowX).toDouble()).toFloat() else 90f
            fun offset(dist: Float, deg: Float) = style.copy(shadowX = (dist * cos(Math.toRadians(deg.toDouble()))).toFloat(), shadowY = (dist * sin(Math.toRadians(deg.toDouble()))).toFloat())
            val alpha = (style.shadowColor ushr 24) / 255f
            Knob(stringResource(R.string.text_shadow_strength), alpha, 0f..1f, onChange = { draft(style.copy(shadowColor = withAlpha(style.shadowColor, it))) }, onDone = onDone, onReset = { set(style.copy(shadowColor = d.shadowColor)) }, haptics = haptics)
            Knob(stringResource(R.string.text_shadow_blur), style.shadowBlur, 0f..0.6f, onChange = { draft(style.copy(shadowBlur = it)) }, onDone = onDone, onReset = { set(style.copy(shadowBlur = d.shadowBlur)) }, haptics = haptics, format = { "${(it * 100).roundToInt()}" })
            Knob(stringResource(R.string.text_shadow_distance), distance, 0f..0.3f, onChange = { draft(offset(it, direction)) }, onDone = onDone, onReset = { set(style.copy(shadowX = d.shadowX, shadowY = d.shadowY)) }, haptics = haptics, format = { "${(it * 100).roundToInt()}" })
            Knob(stringResource(R.string.text_shadow_direction), direction, -180f..180f, onChange = { draft(offset(distance, it)) }, onDone = onDone, onReset = { set(offset(distance, 90f)) }, haptics = haptics, format = { "${it.roundToInt()}°" })
            ColourRow(StyleColours, style.shadowColor or (0xFF shl 24), { set(style.copy(shadowColor = withAlpha(it, if (alpha > 0f) alpha else 0.6f))) }, Modifier.padding(top = 4.dp))
        }
        4 -> {
            Knob(stringResource(R.string.text_box_opacity), style.boxOpacity, 0f..1f, onChange = { draft(style.copy(boxOpacity = it)) }, onDone = onDone, onReset = { set(style.copy(boxOpacity = 0f)) }, haptics = haptics)
            Knob(stringResource(R.string.text_box_padding), style.boxPadding, 0f..1f, onChange = { draft(style.copy(boxPadding = it)) }, onDone = onDone, onReset = { set(style.copy(boxPadding = d.boxPadding)) }, haptics = haptics)
            Knob(stringResource(R.string.text_box_round), style.boxRadius, 0f..1.5f, onChange = { draft(style.copy(boxRadius = it)) }, onDone = onDone, onReset = { set(style.copy(boxRadius = d.boxRadius)) }, haptics = haptics)
            ColourRow(StyleColours, style.boxColor, { set(style.copy(boxColor = it, boxOpacity = if (style.boxOpacity > 0f) style.boxOpacity else 0.8f)) }, Modifier.padding(top = 4.dp))
            ChipRow(listOf(false to stringResource(R.string.text_box_block), true to stringResource(R.string.text_box_lines)), style.boxPerLine, { set(style.copy(boxPerLine = it)) }, Modifier.padding(top = 4.dp))
        }
        else -> {
            Knob(stringResource(R.string.text_curve), style.arc, -0.5f..0.5f, onChange = { draft(style.copy(arc = it)) }, onDone = onDone, onReset = { set(style.copy(arc = 0f)) }, haptics = haptics)
            Hint(stringResource(R.string.text_curve_hint))
        }
    }
}

private fun withAlpha(c: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (c and 0xFFFFFF)
