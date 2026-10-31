package com.layerbit.still.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.SideEffect
import kotlin.math.min
import kotlin.math.max
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.SliderState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.CurveMath
import com.layerbit.still.engine.Curves
import com.layerbit.still.engine.Histogram
import com.layerbit.still.engine.Pt
import com.layerbit.still.ui.theme.Numerals
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a sideways swipe on the picture moves: the chosen slider, read fresh each time it is drawn. */
data class ScrubTarget(val label: String, val value: Float, val range: ClosedFloatingPointRange<Float>, val onChange: (Float) -> Unit, val onDone: () -> Unit, val format: (Float) -> String)

/** The one slider the canvas is steering, if any. One per editor. */
class ScrubHost { var target by mutableStateOf<ScrubTarget?>(null) }

/**
 * A labelled slider that reports every move for the live preview and the release for undo,
 * resets on a double tap of its label, and ticks as it crosses zero. The value is announced
 * to TalkBack as a signed percentage rather than a float. A tap on the label hands the slider
 * to the canvas: a sideways swipe on the picture then moves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Knob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    haptics: Boolean = true,
    format: (Float) -> String = { v -> if (range.start < 0f) String.format(java.util.Locale.getDefault(), "%+d", (v * 100).roundToInt()) else "${(v * 100).roundToInt()}" },
    scrub: ScrubHost? = null
) {
    val haptic = LocalHapticFeedback.current
    var lastSign by remember { mutableIntStateOf(sign(value)) }
    val shown = format(value)
    val resetLabel = stringResource(R.string.editor_reset_slider, label)
    val bipolar = range.start < 0f && range.endInclusive > 0f
    val live = value != 0f
    val valueFill by animateColorAsState(if (live) StillColors.Band else StillColors.SurfaceDim, label = "knobValue")
    val steering = scrub?.target?.label == label
    val target = ScrubTarget(label, value, range, onChange, onDone, format)
    // The canvas reads the slider's value through its target; keep the target as fresh as the slider.
    if (steering) SideEffect { if (scrub?.target?.label == label && scrub.target != target) scrub.target = target }
    val rowFill by animateColorAsState(if (steering) StillColors.BandDim else Color.Transparent, label = "knobRow")
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(rowFill, RoundedCornerShape(10.dp))
                .pointerInput(onReset, scrub) { detectTapGestures(onDoubleTap = { onReset() }, onTap = { if (scrub != null) scrub.target = if (steering) null else target }) }
                .padding(horizontal = if (steering) 8.dp else 0.dp, vertical = if (steering) 4.dp else 0.dp)
                .semantics { contentDescription = resetLabel }
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = StillColors.Text)
            if (steering) Text(stringResource(R.string.editor_scrub_hint), style = MaterialTheme.typography.labelSmall, color = StillColors.BandBright, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            Text(
                shown, style = Numerals, color = if (live) StillColors.OnBand else StillColors.Dim,
                modifier = Modifier.background(valueFill, RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 2.dp)
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { v ->
                val snapped = if (range.start < 0f && abs(v) < 0.015f) 0f else v
                val s = sign(snapped)
                if (haptics && s != lastSign && snapped == 0f) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastSign = s
                onChange(snapped)
            },
            onValueChangeFinished = onDone,
            valueRange = range,
            thumb = { KnobThumb() },
            track = { state -> KnobTrack(state, bipolar) },
            modifier = Modifier.fillMaxWidth().height(36.dp).semantics { this.contentDescription = label; stateDescription = shown }
        )
    }
}

/** The thumb: a lit disc with a white rim, big enough for a thumb. */
@Composable
private fun KnobThumb() {
    Box(
        Modifier.size(26.dp).shadow(8.dp, CircleShape, clip = false, ambientColor = StillColors.Band.copy(alpha = 0.5f), spotColor = StillColors.Band.copy(alpha = 0.6f))
            .background(Brush.radialGradient(listOf(StillColors.BandBright, StillColors.Band)), CircleShape)
            .border(2.5.dp, Color.White.copy(alpha = 0.92f), CircleShape)
    )
}

/** The track: thick, rounded, filled from zero to the value, with a notch at zero on two-sided ranges. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnobTrack(state: SliderState, bipolar: Boolean) {
    val start = state.valueRange.start; val end = state.valueRange.endInclusive
    val span = (end - start).takeIf { it > 0f } ?: 1f
    val at = ((state.value - start) / span).coerceIn(0f, 1f)
    val zero = if (bipolar) ((0f - start) / span).coerceIn(0f, 1f) else 0f
    val fill = Brush.horizontalGradient(listOf(StillColors.Band, StillColors.BandBright))
    val rail = StillColors.SurfaceDim; val notch = StillColors.BorderStrong
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val r = size.height / 2
        drawRoundRect(rail, cornerRadius = CornerRadius(r, r))
        val a = min(at, zero) * size.width; val b = max(at, zero) * size.width
        if (b - a > 0.5f) drawRoundRect(fill, topLeft = Offset(a, 0f), size = Size(max(b - a, r), size.height), cornerRadius = CornerRadius(r, r))
        if (bipolar) drawCircle(notch, r * 0.4f, Offset(zero * size.width, r))
    }
}

private fun sign(v: Float) = if (v > 0f) 1 else if (v < 0f) -1 else 0

/** A row of colour dots; the chosen one wears a ring. */
@Composable
fun ColourRow(colours: List<Int>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.editor_choose_colour)
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in colours) {
            val active = c == selected
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                    .border(if (active) 3.dp else 1.dp, if (active) StillColors.Band else StillColors.BorderStrong, CircleShape)
                    .clickable(role = Role.RadioButton) { onSelect(c) }
                    .semantics { contentDescription = label }
            )
        }
    }
}

/** The luminance histogram as a filled shape with red, green and blue outlines, and clipping marks. */
@Composable
fun HistogramView(h: Histogram?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(StillColors.SurfaceDim)) {
        if (h == null) return@Canvas
        fun shape(values: FloatArray): Path = Path().apply {
            moveTo(0f, size.height)
            for (i in 0..255) lineTo(size.width * i / 255f, size.height * (1f - values[i]))
            lineTo(size.width, size.height); close()
        }
        drawPath(shape(h.normalised(h.luma)), StillColors.Muted.copy(alpha = 0.5f))
        drawPath(shape(h.normalised(h.red)), Color(0xFFE8705F).copy(alpha = 0.85f), style = Stroke(1.5f))
        drawPath(shape(h.normalised(h.green)), Color(0xFF4CCB8F).copy(alpha = 0.85f), style = Stroke(1.5f))
        drawPath(shape(h.normalised(h.blue)), Color(0xFF5BA9E8).copy(alpha = 0.85f), style = Stroke(1.5f))
        if (h.clippedBlack > 0.002f) drawRect(StillColors.Failed, Offset(0f, 0f), androidx.compose.ui.geometry.Size(4f, size.height))
        if (h.clippedWhite > 0.002f) drawRect(StillColors.Failed, Offset(size.width - 4f, 0f), androidx.compose.ui.geometry.Size(4f, size.height))
    }
}

/**
 * A curve with draggable points. Tap adds a point, drag moves one, drag off the top or bottom
 * edge removes it. The curve is the same monotone spline the engine tabulates.
 */
@Composable
fun CurveEditor(points: List<Pt>, onChange: (List<Pt>) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, accent: Color = StillColors.Band) {
    var dragging by remember { mutableIntStateOf(-1) }
    var local by remember(points) { mutableStateOf(points) }
    val description = stringResource(R.string.editor_curve_description)
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(10.dp)).background(StillColors.SurfaceDim).border(1.dp, StillColors.Border, RoundedCornerShape(10.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    val x = (at.x / size.width).coerceIn(0.02f, 0.98f); val y = (1f - at.y / size.height).coerceIn(0f, 1f)
                    if (local.any { abs(it.x - x) < 0.04f }) return@detectTapGestures
                    val next = (local + Pt(x, y)).sortedBy { it.x }
                    if (Curves.ok(next)) { local = next; onChange(next); onDone() }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { at ->
                        val x = at.x / size.width; val y = 1f - at.y / size.height
                        dragging = local.indices.minByOrNull { (local[it].x - x) * (local[it].x - x) + (local[it].y - y) * (local[it].y - y) }
                            ?.takeIf { i -> abs(local[i].x - x) < 0.08f && abs(local[i].y - y) < 0.12f } ?: -1
                    },
                    onDragEnd = { dragging = -1; onDone() },
                    onDragCancel = { dragging = -1 },
                    onDrag = { change, _ ->
                        val i = dragging; if (i < 0) return@detectDragGestures
                        change.consume()
                        val x = change.position.x / size.width; val y = 1f - change.position.y / size.height
                        val isEnd = i == 0 || i == local.lastIndex
                        if (!isEnd && (y < -0.15f || y > 1.15f) && local.size > 2) {
                            local = local.filterIndexed { k, _ -> k != i }; dragging = -1; onChange(local); return@detectDragGestures
                        }
                        val lo = if (i == 0) 0f else local[i - 1].x + 0.02f
                        val hi = if (i == local.lastIndex) 1f else local[i + 1].x - 0.02f
                        val nx = if (isEnd) local[i].x else x.coerceIn(lo, hi)
                        val next = local.toMutableList().apply { set(i, Pt(nx, y.coerceIn(0f, 1f))) }
                        if (Curves.ok(next)) { local = next; onChange(next) }
                    }
                )
            }
    ) {
        for (i in 1..3) {
            drawLine(StillColors.Border, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), 1f)
            drawLine(StillColors.Border, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f)
        }
        drawLine(StillColors.Dim.copy(alpha = 0.5f), Offset(0f, size.height), Offset(size.width, 0f), 1f)
        val table = CurveMath.table(local)
        val path = Path().apply {
            moveTo(0f, size.height * (1f - table[0]))
            for (i in 1..64) { val x = i / 64f; lineTo(size.width * x, size.height * (1f - CurveMath.at(table, x))) }
        }
        drawPath(path, accent, style = Stroke(3f))
        for ((i, p) in local.withIndex()) {
            val at = Offset(size.width * p.x, size.height * (1f - p.y))
            drawCircle(StillColors.Surface, 9f, at)
            drawCircle(if (i == dragging) StillColors.BandBright else accent, 9f, at, style = Stroke(2.5f))
        }
    }
}

/** A little swatch with a label, for picking background and border colours. */
@Composable
fun Swatch(colour: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(role = Role.RadioButton, onClick = onClick).padding(4.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (colour ushr 24 == 0) StillColors.Canvas else Color(colour)).border(if (selected) 2.dp else 1.dp, if (selected) StillColors.Band else StillColors.Border, RoundedCornerShape(8.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) StillColors.Text else StillColors.Dim, modifier = Modifier.padding(top = 2.dp))
    }
}
