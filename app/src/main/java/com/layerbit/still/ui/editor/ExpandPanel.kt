package com.layerbit.still.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.Crop
import com.layerbit.still.engine.Expand
import com.layerbit.still.engine.ExpandFill
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Expand: grow the canvas around the picture and fill what is new. Shapes and amounts as
 * one-tap presets, each side as its own slider, the canvas edges as handles on the picture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpandPanel(s: PanelScope) {
    val e = s.recipe.expand
    val before = Geometry2D.canvasSize(s.source, s.recipe.geometry)
    val after = e.grow(before)
    Hint(stringResource(R.string.expand_hint))

    SectionHeading(stringResource(R.string.expand_shape), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, aspect) in SHAPES) QuickChip(label) {
            s.change { it.copy(expand = Expand.toAspect(before.width, before.height, aspect, e.fill).copy(seed = e.seed)) }
        }
        for (f in listOf(0.1f, 0.25f, 0.5f)) QuickChip("+${(f * 100).roundToInt()}%") {
            s.change { it.copy(expand = Expand.all(f, e.fill).copy(seed = e.seed)) }
        }
    }

    SectionHeading(stringResource(R.string.expand_fill), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    ChipRow(
        options = listOf(
            ExpandFill.SMART to stringResource(R.string.expand_fill_smart), ExpandFill.BLUR to stringResource(R.string.expand_fill_blur),
            ExpandFill.WHITE to stringResource(R.string.expand_fill_white), ExpandFill.BLACK to stringResource(R.string.expand_fill_black)
        ),
        selected = e.fill,
        onSelect = { f -> s.change { it.copy(expand = it.expand.copy(fill = f)) } }
    )
    if (e.fill == ExpandFill.SMART) Hint(stringResource(R.string.expand_smart_hint))

    if (!e.isIdentity) {
        val pct: (Float) -> String = { "+${(it * 100).roundToInt()}%" }
        for ((label, value, set) in listOf<Triple<Int, Float, (Expand, Float) -> Expand>>(
            Triple(R.string.expand_left, e.left) { x, v -> x.copy(left = v) },
            Triple(R.string.expand_top, e.top) { x, v -> x.copy(top = v) },
            Triple(R.string.expand_right, e.right) { x, v -> x.copy(right = v) },
            Triple(R.string.expand_bottom, e.bottom) { x, v -> x.copy(bottom = v) }
        )) {
            Knob(
                stringResource(label), value, 0f..Expand.MAX,
                onChange = { v -> s.draft(s.recipe.copy(expand = set(s.recipe.expand, v))) }, onDone = s.commit,
                onReset = { s.change { it.copy(expand = set(it.expand, 0f)) } }, haptics = s.haptics, format = pct, scrub = s.scrub
            )
        }
        Hint(stringResource(R.string.expand_size, after.width, after.height))
        if (e.fill == ExpandFill.SMART) SecondaryButton(
            stringResource(R.string.expand_try_again), onClick = { s.change { it.copy(expand = it.expand.copy(seed = it.expand.seed + 1)) } },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), tall = false
        )
        SecondaryButton(
            stringResource(R.string.expand_reset), onClick = { s.change { it.copy(expand = Expand()) } },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), tall = false
        )
    }
}

private val SHAPES = listOf("1:1" to 1f, "4:5" to 4f / 5f, "9:16" to 9f / 16f, "16:9" to 16f / 9f, "3:2" to 3f / 2f)

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label, style = MaterialTheme.typography.labelMedium, color = StillColors.Text, maxLines = 1,
        modifier = Modifier.clip(shape).background(StillColors.SurfaceDim).border(1.dp, StillColors.BorderStrong, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

/** The picture outlined inside its canvas, and handles on the canvas edges to pull outwards. */
internal fun DrawScope.drawExpandOverlay(inner: Crop, r: Rect) {
    val thin = 1.5.dp.toPx()
    val ir = Rect(r.left + inner.left * r.width, r.top + inner.top * r.height, r.left + inner.right * r.width, r.top + inner.bottom * r.height)
    drawRect(Color.White.copy(alpha = 0.9f), Offset(ir.left, ir.top), ir.size, style = Stroke(thin, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))))
    drawRect(Color.White.copy(alpha = 0.55f), Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(thin))
    val k = min(22.dp.toPx(), min(r.width, r.height) / 4)
    val w = 3.dp.toPx()
    for ((cx, cy) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
        val dx = if (cx == r.left) k else -k; val dy = if (cy == r.top) k else -k
        drawLine(Color.White, Offset(cx, cy), Offset(cx + dx, cy), w, StrokeCap.Round)
        drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + dy), w, StrokeCap.Round)
    }
    val mid = r.center
    for ((px, py) in listOf(mid.x to r.top, mid.x to r.bottom, r.left to mid.y, r.right to mid.y)) {
        val horizontal = py == r.top || py == r.bottom
        drawLine(Color.White, Offset(px - if (horizontal) k / 2 else 0f, py - if (horizontal) 0f else k / 2), Offset(px + if (horizontal) k / 2 else 0f, py + if (horizontal) 0f else k / 2), w, StrokeCap.Round)
    }
}
