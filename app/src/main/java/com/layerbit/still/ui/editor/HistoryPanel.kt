package com.layerbit.still.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.engine.Look
import com.layerbit.still.engine.MarkKind
import com.layerbit.still.engine.Recipe
import com.layerbit.still.engine.Step
import com.layerbit.still.engine.StepKey
import com.layerbit.still.engine.StepKind
import com.layerbit.still.engine.Steps
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.roundToInt

/**
 * Edits copied from one picture, waiting to be pasted on another. Kept for as long as the app
 * runs; nothing leaves the app, and nothing touches the system clipboard.
 */
object EditClipboard {
    var recipe by mutableStateOf<Recipe?>(null)
        private set
    var from by mutableStateOf<String?>(null)
        private set

    fun copy(r: Recipe, source: String?) { recipe = r; from = source }
}

/**
 * History: every edit in the recipe as a line, in the order the picture gets them. Each can be
 * hidden for a look without it, opened in its panel, or deleted; and the whole look copied to
 * paste on another picture.
 */
@Composable
fun HistoryPanel(
    recipe: Recipe, source: Geometry2D.Size, hidden: Set<StepKey>,
    onToggle: (StepKey) -> Unit, onDelete: (StepKey) -> Unit, onEdit: (StepKey) -> Unit,
    onCopy: () -> Unit, onPaste: () -> Unit
) {
    val steps = Steps.describe(recipe, source)
    if (steps.isEmpty()) Hint(stringResource(R.string.history_empty)) else Hint(stringResource(R.string.history_hint))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp).testTag("historyList")) {
        for (step in steps) {
            val off = step.key in hidden
            val shape = RoundedCornerShape(16.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(shape).background(StillColors.SurfaceDim).border(1.dp, StillColors.Border, shape).padding(start = 12.dp)
            ) {
                Text(
                    stepTitle(step, recipe), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (off) StillColors.Dim else StillColors.Text, textDecoration = if (off) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onToggle(step.key) }) { Text(stringResource(if (off) R.string.history_show else R.string.history_hide), color = StillColors.Muted) }
                TextButton(onClick = { onEdit(step.key) }) { Text(stringResource(R.string.history_edit), color = StillColors.BandBright) }
                TextButton(onClick = { onDelete(step.key) }) { Text(stringResource(R.string.app_delete), color = StillColors.Failed) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        SecondaryButton(stringResource(R.string.history_copy), onClick = onCopy, modifier = Modifier.weight(1f), tall = false, enabled = steps.isNotEmpty())
        SecondaryButton(stringResource(R.string.history_paste), onClick = onPaste, modifier = Modifier.weight(1f), tall = false, enabled = EditClipboard.recipe != null)
    }
    EditClipboard.from?.let { Hint(stringResource(R.string.history_paste_from, it)) }
}

/** The panel where a step is changed. */
fun panelFor(step: StepKey): ToolId.Panel = when (step.kind) {
    StepKind.PERSPECTIVE -> ToolId.Panel.PERSPECTIVE
    StepKind.TURN, StepKind.STRAIGHTEN, StepKind.CROP, StepKind.SIZE -> ToolId.Panel.CROP
    StepKind.EXPAND -> ToolId.Panel.EXPAND
    StepKind.LIGHT -> ToolId.Panel.LIGHT
    StepKind.COLOUR, StepKind.MIX -> ToolId.Panel.COLOUR
    StepKind.CURVES -> ToolId.Panel.CURVES
    StepKind.LOOK -> ToolId.Panel.LOOKS
    StepKind.SCAN -> ToolId.Panel.SCAN
    StepKind.DETAIL -> ToolId.Panel.DETAIL
    StepKind.LOCAL -> ToolId.Panel.LOCAL
    StepKind.EFFECT -> ToolId.Panel.EFFECTS
    StepKind.PORTRAIT -> ToolId.Panel.PORTRAIT
    StepKind.DOUBLE -> ToolId.Panel.DOUBLE
    StepKind.MARK -> ToolId.Panel.LAYERS
    StepKind.CUTOUT -> ToolId.Panel.CUTOUT
    StepKind.FRAME -> ToolId.Panel.FRAME
}

@Composable
private fun stepTitle(step: Step, recipe: Recipe): String {
    val locale = java.util.Locale.getDefault()
    fun signed(v: Float) = String.format(locale, "%+d", v.roundToInt())
    return when (step.key.kind) {
        StepKind.PERSPECTIVE -> stringResource(R.string.panel_perspective)
        StepKind.TURN -> {
            val parts = ArrayList<String>()
            step.values.firstOrNull { it.first == "rotation" }?.let { parts.add(stringResource(R.string.history_turn, it.second.roundToInt())) }
            if (step.values.any { it.first == "flipX" || it.first == "flipY" }) parts.add(stringResource(R.string.history_flip))
            parts.joinToString(", ")
        }
        StepKind.STRAIGHTEN -> stringResource(R.string.history_straighten, String.format(locale, "%+.1f°", step.values.first().second))
        StepKind.CROP -> if (step.label.isEmpty()) stringResource(R.string.panel_crop) else stringResource(R.string.history_crop, step.label)
        StepKind.SIZE -> stringResource(R.string.history_size, step.label)
        StepKind.EXPAND -> {
            val fill = stringResource(when (recipe.expand.fill) {
                com.layerbit.still.engine.ExpandFill.SMART -> R.string.expand_fill_smart
                com.layerbit.still.engine.ExpandFill.BLUR -> R.string.expand_fill_blur
                com.layerbit.still.engine.ExpandFill.WHITE -> R.string.expand_fill_white
                com.layerbit.still.engine.ExpandFill.BLACK -> R.string.expand_fill_black
            })
            val sides = step.values.map { (side, v) ->
                val name = stringResource(when (side) { "left" -> R.string.expand_left; "top" -> R.string.expand_top; "right" -> R.string.expand_right; else -> R.string.expand_bottom })
                "$name +${v.roundToInt()}%"
            }
            stringResource(R.string.history_values, stringResource(R.string.panel_expand), (listOf(fill) + sides).joinToString(", "))
        }
        StepKind.LIGHT, StepKind.COLOUR, StepKind.DETAIL -> {
            val panel = stringResource(when (step.key.kind) { StepKind.LIGHT -> R.string.panel_light; StepKind.COLOUR -> R.string.panel_colour; else -> R.string.panel_detail })
            val values = step.values.map { (name, v) ->
                val label = stringResource(TONE_LABELS.getValue(name)).lowercase(locale)
                if (name == "exposure") "$label ${String.format(locale, "%+.2f", v)}" else "$label ${signed(v)}"
            }
            stringResource(R.string.history_values, panel, values.joinToString(", "))
        }
        StepKind.CURVES -> stringResource(R.string.panel_curves)
        StepKind.LOOK -> stringResource(R.string.history_look, lookLabel(Look.entries.firstOrNull { it.name == step.label } ?: Look.NONE), step.values.first().second.roundToInt())
        StepKind.MIX -> stringResource(R.string.history_mix)
        StepKind.SCAN -> stringResource(R.string.panel_scan)
        StepKind.MARK -> {
            val m = recipe.marks.getOrNull(step.key.index)
            val kind = markLabel(m?.kind ?: MarkKind.DRAW)
            if (m != null && m.text.isNotBlank() && (m.kind == MarkKind.TEXT || m.kind == MarkKind.WATERMARK)) "$kind “${m.text.take(24)}”" else kind
        }
        StepKind.CUTOUT -> stringResource(R.string.panel_cutout)
        StepKind.FRAME -> stringResource(R.string.panel_frame)
        StepKind.LOCAL -> stringResource(R.string.panel_local) + ": " + maskLabel(com.layerbit.still.engine.MaskKind.entries.firstOrNull { it.name == step.label } ?: com.layerbit.still.engine.MaskKind.BRUSH)
        StepKind.EFFECT -> stringResource(effectLabelRes(com.layerbit.still.engine.EffectKind.entries.firstOrNull { it.name == step.label } ?: com.layerbit.still.engine.EffectKind.AMBIANCE)) + " " + step.values.firstOrNull()?.second?.roundToInt() + "%"
        StepKind.PORTRAIT -> stringResource(R.string.panel_portrait)
        StepKind.DOUBLE -> stringResource(R.string.panel_double)
    }
}

private val TONE_LABELS = mapOf(
    "exposure" to R.string.light_exposure, "contrast" to R.string.light_contrast, "highlights" to R.string.light_highlights,
    "shadows" to R.string.light_shadows, "whites" to R.string.light_whites, "blacks" to R.string.light_blacks,
    "temperature" to R.string.colour_temperature, "tint" to R.string.colour_tint, "vibrance" to R.string.colour_vibrance,
    "saturation" to R.string.colour_saturation, "hue" to R.string.colour_hue, "noise" to R.string.detail_noise,
    "sharpness" to R.string.detail_sharpness, "clarity" to R.string.detail_clarity, "dehaze" to R.string.detail_dehaze,
    "fade" to R.string.detail_fade, "vignette" to R.string.detail_vignette, "grain" to R.string.detail_grain
)
