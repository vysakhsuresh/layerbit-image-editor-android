package com.layerbit.still.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.BlendMode
import com.layerbit.still.engine.DoubleExposure
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.components.ToggleRow
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.roundToInt

@Composable
fun blendLabel(m: BlendMode): String = stringResource(
    when (m) {
        BlendMode.NORMAL -> R.string.blend_normal; BlendMode.LIGHTEN -> R.string.blend_lighten; BlendMode.DARKEN -> R.string.blend_darken
        BlendMode.SCREEN -> R.string.blend_screen; BlendMode.MULTIPLY -> R.string.blend_multiply; BlendMode.OVERLAY -> R.string.blend_overlay
        BlendMode.SOFT_LIGHT -> R.string.blend_soft_light; BlendMode.ADD -> R.string.blend_add
    }
)

/**
 * A second picture over the first: pick it, then drag, pinch and turn it on the picture, choose how
 * the two meet, and keep it inside the subject for the silhouette look.
 */
@Composable
fun DoublePanel(s: PanelScope, onPick: () -> Unit) {
    val d = s.recipe.double
    fun set(block: (DoubleExposure) -> DoubleExposure) = s.draft(s.recipe.copy(double = block(s.recipe.double)))
    fun setNow(block: (DoubleExposure) -> DoubleExposure) = s.change { it.copy(double = block(it.double)) }

    if (d.file.isEmpty()) {
        Text(stringResource(R.string.double_title), style = MaterialTheme.typography.headlineSmall, color = StillColors.Text, modifier = Modifier.padding(top = 8.dp))
        Hint(stringResource(R.string.double_intro))
        PrimaryButton(stringResource(R.string.double_add), onClick = onPick, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("doubleAdd"), iconRes = R.drawable.ic_tool_double)
        Hint(stringResource(R.string.double_private))
        return
    }
    Hint(stringResource(R.string.double_gesture_hint))
    SectionHeading(stringResource(R.string.double_blend), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    ChipRow(BlendMode.entries.map { it to blendLabel(it) }, d.mode, { m -> setNow { it.copy(mode = m) } })
    Knob(stringResource(R.string.double_opacity), d.opacity, 0f..1f, onChange = { v -> set { it.copy(opacity = v) } }, onDone = s.commit, onReset = { setNow { it.copy(opacity = 0.8f) } }, haptics = s.haptics, scrub = s.scrub, modifier = Modifier.padding(top = 6.dp))
    ToggleRow(stringResource(R.string.double_inside_subject), d.insideSubject, { v -> setNow { it.copy(insideSubject = v) } }, detail = stringResource(R.string.double_inside_subject_detail))
    SectionHeading(stringResource(R.string.double_place), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    Knob(stringResource(R.string.double_size), d.scale, DoubleExposure.MIN_SCALE..3f, onChange = { v -> set { it.copy(scale = v) } }, onDone = s.commit, onReset = { setNow { it.copy(scale = 1f) } }, haptics = s.haptics, format = { "${(it * 100).roundToInt()}%" })
    Knob(stringResource(R.string.double_turn), d.angle, -180f..180f, onChange = { v -> set { it.copy(angle = v) } }, onDone = s.commit, onReset = { setNow { it.copy(angle = 0f) } }, haptics = s.haptics, format = { "${it.roundToInt()}°" })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SecondaryButton(stringResource(R.string.double_centre), onClick = { setNow { it.copy(x = 0.5f, y = 0.5f, scale = 1f, angle = 0f) } }, modifier = Modifier.weight(1f), tall = false)
        SecondaryButton(stringResource(R.string.double_replace), onClick = onPick, modifier = Modifier.weight(1f), tall = false)
    }
    SecondaryButton(stringResource(R.string.double_remove), onClick = { setNow { DoubleExposure() } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), tall = false)
}
