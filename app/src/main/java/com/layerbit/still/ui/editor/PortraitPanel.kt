package com.layerbit.still.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.PortraitEdit
import com.layerbit.still.engine.PortraitTapKind
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.components.Pill
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.components.ToggleRow
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.roundToInt

/** What a finger does on the picture while the Portrait panel is open. */
enum class PortraitTool { NONE, BRUSH, EYE, TEETH }

/** The strengths a first press of "Retouch skin" sets: gentle, and every one of them a slider afterwards. */
val PortraitStart = PortraitEdit(smooth = 0.5f, texture = 0.35f, even = 0.4f, light = 0.25f)

/**
 * Skin smoothing, even tone, face light, then eyes and teeth by tap. The skin is found by itself;
 * "Show skin" paints what it found, and the brush adds or takes away.
 */
@Composable
fun PortraitPanel(
    s: PanelScope, tool: PortraitTool, onTool: (PortraitTool) -> Unit, add: Boolean, onAdd: (Boolean) -> Unit,
    width: Float, onWidth: (Float) -> Unit, showSkin: Boolean, onShowSkin: (Boolean) -> Unit, kept: Float?, onClearStrokes: () -> Unit
) {
    val p = s.recipe.portrait
    fun set(block: (PortraitEdit) -> PortraitEdit) = s.draft(s.recipe.copy(portrait = block(s.recipe.portrait)))
    fun setNow(block: (PortraitEdit) -> PortraitEdit) = s.change { it.copy(portrait = block(it.portrait)) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(stringResource(R.string.portrait_title), style = MaterialTheme.typography.titleMedium, color = StillColors.Text, modifier = Modifier.weight(1f))
        if (kept != null) Pill(stringResource(R.string.portrait_skin_value, (kept * 100).roundToInt()), StillColors.Band, filled = kept > 0.005f)
    }
    if (kept != null && kept <= 0.005f) Hint(stringResource(R.string.portrait_no_skin))
    if (!p.needsMask) {
        PrimaryButton(stringResource(R.string.portrait_start), onClick = { setNow { PortraitStart.copy(strokes = it.strokes, taps = it.taps, eyes = it.eyes, teeth = it.teeth, subjectOnly = it.subjectOnly) } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("portraitStart"), iconRes = R.drawable.ic_tool_portrait)
    }
    Knob(stringResource(R.string.portrait_smooth), p.smooth, 0f..1f, onChange = { v -> set { it.copy(smooth = v) } }, onDone = s.commit, onReset = { setNow { it.copy(smooth = 0f) } }, haptics = s.haptics, scrub = s.scrub)
    if (p.smooth > 0f) {
        Knob(stringResource(R.string.portrait_texture), p.texture, 0f..1f, onChange = { v -> set { it.copy(texture = v) } }, onDone = s.commit, onReset = { setNow { it.copy(texture = 0.35f) } }, haptics = s.haptics, scrub = s.scrub)
        Hint(stringResource(R.string.portrait_texture_hint))
    }
    Knob(stringResource(R.string.portrait_even), p.even, 0f..1f, onChange = { v -> set { it.copy(even = v) } }, onDone = s.commit, onReset = { setNow { it.copy(even = 0f) } }, haptics = s.haptics, scrub = s.scrub)
    Knob(stringResource(R.string.portrait_light), p.light, 0f..1f, onChange = { v -> set { it.copy(light = v) } }, onDone = s.commit, onReset = { setNow { it.copy(light = 0f) } }, haptics = s.haptics, scrub = s.scrub)

    SectionHeading(stringResource(R.string.portrait_skin_heading), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    ToggleRow(stringResource(R.string.portrait_show_skin), showSkin, onShowSkin, detail = stringResource(R.string.portrait_show_skin_detail))
    ToggleRow(stringResource(R.string.portrait_subject_only), p.subjectOnly, { v -> setNow { it.copy(subjectOnly = v) } }, detail = stringResource(R.string.portrait_subject_only_detail))
    ChipRow(
        listOf(PortraitTool.NONE to stringResource(R.string.portrait_tool_look), PortraitTool.BRUSH to stringResource(R.string.portrait_tool_brush), PortraitTool.EYE to stringResource(R.string.portrait_tool_eye), PortraitTool.TEETH to stringResource(R.string.portrait_tool_teeth)),
        tool, onTool, modifier = Modifier.padding(top = 6.dp)
    )
    when (tool) {
        PortraitTool.BRUSH -> {
            ChipRow(listOf(true to stringResource(R.string.portrait_brush_add), false to stringResource(R.string.portrait_brush_remove)), add, onAdd, modifier = Modifier.padding(top = 6.dp))
            Knob(stringResource(R.string.mark_size), width, 0.01f..0.2f, onChange = onWidth, onDone = {}, onReset = { onWidth(0.05f) }, haptics = s.haptics, format = { "${(it * 1000).roundToInt()}" })
            Hint(stringResource(R.string.portrait_brush_hint, p.strokes.size))
            if (p.strokes.isNotEmpty()) TextButton(onClick = onClearStrokes) { Text(stringResource(R.string.cutout_clear_strokes), color = StillColors.Muted) }
        }
        PortraitTool.EYE -> {
            Hint(stringResource(R.string.portrait_eye_hint, p.taps.count { it.kind == PortraitTapKind.EYE }))
            Knob(stringResource(R.string.portrait_eyes), p.eyes, 0f..1f, onChange = { v -> set { it.copy(eyes = v) } }, onDone = s.commit, onReset = { setNow { it.copy(eyes = 0.6f) } }, haptics = s.haptics)
        }
        PortraitTool.TEETH -> {
            Hint(stringResource(R.string.portrait_teeth_hint, p.taps.count { it.kind == PortraitTapKind.TEETH }))
            Knob(stringResource(R.string.portrait_teeth), p.teeth, 0f..1f, onChange = { v -> set { it.copy(teeth = v) } }, onDone = s.commit, onReset = { setNow { it.copy(teeth = 0.6f) } }, haptics = s.haptics)
        }
        PortraitTool.NONE -> Hint(stringResource(R.string.portrait_look_hint))
    }
    if (!p.isIdentity) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { setNow { PortraitEdit() } }) { Text(stringResource(R.string.portrait_reset), color = StillColors.Muted) }
        }
    }
}
