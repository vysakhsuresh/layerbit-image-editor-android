package com.layerbit.still.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.BrushStroke
import com.layerbit.still.engine.LocalAdjust
import com.layerbit.still.engine.LocalMask
import com.layerbit.still.engine.LocalPreset
import com.layerbit.still.engine.Mark
import com.layerbit.still.engine.MaskKind
import com.layerbit.still.engine.Pt
import com.layerbit.still.engine.Recipe
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.components.Divider
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.components.ToggleRow
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Local panel: light and colour in one part of the picture. A list of adjustments, each a
 * mask and its sliders; the selected one's mask is edited on the canvas (brush strokes, gradient
 * ends, an ellipse, a control point) and can be shown in red. Every change goes through the
 * recipe, so undo, presets and export treat it as any other edit.
 */

/** What the Local panel keeps between gestures: the brush and the stroke in progress. */
@Stable
class LocalTools {
    var width by mutableStateOf(0.08f)
    var softness by mutableStateOf(0.6f)
    var erase by mutableStateOf(false)
    var stroke by mutableStateOf<List<Pt>>(emptyList())
}

/** The Local panel's canvas overlay: every adjustment's pin, the selected one's handles, and the stroke being painted. */
data class LocalOverlay(val locals: List<LocalAdjust>, val selected: Int, val stroke: List<Pt>, val brushWidth: Float, val erase: Boolean) : Overlay

private const val H_A = 0
private const val H_B = 1
private const val H_RX = 2
private const val H_RY = 3
private const val H_BODY = 4
private const val PIN = 100

/** Where an adjustment's pin sits, in picture fractions; null for the kinds found automatically. */
private fun anchor(m: LocalMask): Pt? = when (m.kind) {
    MaskKind.BRUSH -> m.strokes.firstOrNull()?.points?.firstOrNull()
    MaskKind.LINEAR -> Pt((m.a.x + m.b.x) / 2f, (m.a.y + m.b.y) / 2f)
    MaskKind.RADIAL, MaskKind.POINT -> m.a
    else -> null
}

/** The screen positions of a mask's handles, by handle id. */
private fun handles(m: LocalMask, t: CanvasTransform): Map<Int, Offset> {
    val r = t.rect
    val short = min(r.width, r.height)
    val c = t.toOffset(m.a)
    return when (m.kind) {
        MaskKind.LINEAR -> mapOf(H_A to c, H_B to t.toOffset(m.b), H_BODY to (c + t.toOffset(m.b)) / 2f)
        MaskKind.RADIAL -> {
            val th = Math.toRadians(m.angle.toDouble())
            val ux = Offset(cos(th).toFloat(), sin(th).toFloat()); val uy = Offset(-ux.y, ux.x)
            mapOf(H_A to c, H_RX to c + ux * (m.radiusX * short), H_RY to c + uy * (m.radiusY * short))
        }
        MaskKind.POINT -> mapOf(H_A to c, H_RX to c + Offset(m.radiusX * short, 0f))
        else -> emptyMap()
    }
}

private fun Pt.plus(d: Offset, lo: Float = 0f, hi: Float = 1f) = Pt((x + d.x).coerceIn(lo, hi), (y + d.y).coerceIn(lo, hi))

/** [m] with handle [h] dragged to [to] (by [delta] in fractions). */
private fun drag(m: LocalMask, h: Int, to: Pt, delta: Offset, t: CanvasTransform): LocalMask {
    val r = t.rect
    val short = min(r.width, r.height).coerceAtLeast(1f)
    val v = Offset((to.x - m.a.x) * r.width, (to.y - m.a.y) * r.height)
    return when (m.kind) {
        MaskKind.LINEAR -> when (h) {
            H_A -> m.copy(a = to)
            H_B -> m.copy(b = to)
            else -> m.copy(a = m.a.plus(delta, -1f, 2f), b = m.b.plus(delta, -1f, 2f))
        }
        MaskKind.RADIAL -> when (h) {
            H_RX -> m.copy(radiusX = (v.getDistance() / short).coerceIn(0.02f, 2f), angle = Math.toDegrees(atan2(v.y, v.x).toDouble()).toFloat())
            H_RY -> {
                val th = Math.toRadians(m.angle.toDouble())
                val along = abs(-sin(th).toFloat() * v.x + cos(th).toFloat() * v.y)
                m.copy(radiusY = (along / short).coerceIn(0.02f, 2f))
            }
            else -> m.copy(a = m.a.plus(delta))
        }
        MaskKind.POINT -> when (h) {
            H_RX -> m.copy(radiusX = (v.getDistance() / short).coerceIn(0.03f, 0.8f))
            else -> m.copy(a = to)
        }
        else -> m
    }
}

/** The recipe with local [i] changed by [block]. */
private fun Recipe.withLocal(i: Int, block: (LocalAdjust) -> LocalAdjust): Recipe =
    if (i !in locals.indices) this else copy(locals = locals.toMutableList().apply { set(i, block(get(i))) })

fun localOverlay(recipe: Recipe, selected: Int, tools: LocalTools): Overlay = LocalOverlay(recipe.locals, selected, tools.stroke, tools.width, tools.erase)

/**
 * What a finger does on the picture: paints into the selected brush mask, drags the selected
 * mask's handles, taps the control point to where it lands, and on any other pin picks that
 * adjustment (and drags it, for the kinds that have a place).
 */
fun localTouchMode(recipe: Recipe, selected: Int, tools: LocalTools, t: CanvasTransform, reach: Float, vm: EditorViewModel): TouchMode {
    val locals = recipe.locals
    val current = locals.getOrNull(selected)
    fun pinAt(at: Offset): Int? = locals.indices.lastOrNull { i ->
        i != selected && locals[i].visible && anchor(locals[i].mask)?.let { (t.toOffset(it) - at).getDistance() <= reach } == true
    }
    fun hit(at: Offset): Int? {
        if (current != null) {
            val hs = handles(current.mask, t)
            // The edge handles first: they sit on top of the body.
            for (id in listOf(H_RX, H_RY, H_A, H_B, H_BODY)) { val p = hs[id] ?: continue; if ((p - at).getDistance() <= reach) return id }
        }
        return pinAt(at)?.also { vm.selectLocal(it) }?.let { PIN + it }
    }
    fun onDrag(h: Int, to: Pt, delta: Offset) {
        if (h >= PIN) {
            val i = h - PIN
            vm.draft(recipe.withLocal(i) { l -> l.copy(mask = l.mask.let { m -> if (m.kind == MaskKind.LINEAR) m.copy(a = m.a.plus(delta, -1f, 2f), b = m.b.plus(delta, -1f, 2f)) else if (m.kind == MaskKind.RADIAL || m.kind == MaskKind.POINT) m.copy(a = m.a.plus(delta)) else m }) })
        } else vm.draft(recipe.withLocal(selected) { l -> l.copy(mask = drag(l.mask, h, to, delta, t)) })
    }
    val brushing = current?.takeIf { it.mask.kind == MaskKind.BRUSH }
    if (brushing != null) {
        return TouchMode.DrawOrDrag(
            draw = TouchMode.Draw(
                onStart = { p -> tools.stroke = listOf(p) },
                onMove = { p -> if (tools.stroke.size < Mark.MAX_POINTS) tools.stroke = tools.stroke + p },
                onEnd = {
                    val pts = tools.stroke; tools.stroke = emptyList()
                    val s = BrushStroke(pts, tools.width, tools.softness, tools.erase)
                    if (s.valid && brushing.mask.strokes.size < LocalMask.MAX_STROKES) vm.change { it.withLocal(selected) { l -> l.copy(mask = l.mask.copy(strokes = l.mask.strokes + s)) } }
                },
                onCancel = { tools.stroke = emptyList() }
            ),
            hit = { at -> pinAt(at)?.also { vm.selectLocal(it) }?.let { PIN + it } },
            onDrag = { h, d -> onDrag(h, Pt(0f, 0f), d) },
            onDragEnd = { vm.commit() }
        )
    }
    return TouchMode.Handles(
        hit = ::hit,
        onDrag = ::onDrag,
        onEnd = { vm.commit() },
        // A tap puts the point or the ellipse where it lands.
        onTap = { p ->
            when (current?.mask?.kind) {
                MaskKind.POINT, MaskKind.RADIAL -> vm.change { it.withLocal(selected) { l -> l.copy(mask = l.mask.copy(a = p)) } }
                else -> Unit
            }
        }
    )
}

/** Draws [o]: pins for every adjustment with a place, the selected mask's shape and handles, and the stroke being painted. */
fun DrawScope.drawLocalOverlay(o: LocalOverlay, t: CanvasTransform) {
    val r = t.rect
    val short = min(r.width, r.height)
    val handleR = 9.dp.toPx()
    val thin = 1.5.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))
    val shade = Color.Black.copy(alpha = 0.45f)
    fun handle(at: Offset, strong: Boolean = true) {
        drawCircle(shade, handleR + 2f, at)
        drawCircle(Color.White, handleR, at)
        if (strong) drawCircle(StillColors.Band, handleR * 0.55f, at)
    }
    val selected = o.locals.getOrNull(o.selected)
    for ((i, l) in o.locals.withIndex()) {
        if (i == o.selected || !l.visible) continue
        val p = anchor(l.mask) ?: continue
        val at = t.toOffset(p)
        drawCircle(shade, handleR * 0.85f, at)
        drawCircle(Color.White, handleR * 0.75f, at, style = Stroke(thin * 1.4f))
    }
    if (selected != null) {
        val m = selected.mask
        val hs = handles(m, t)
        clipRect(r.left, r.top, r.right, r.bottom) {
            when (m.kind) {
                MaskKind.LINEAR -> {
                    val a = hs.getValue(H_A); val b = hs.getValue(H_B)
                    val dir = (b - a).let { d -> val len = d.getDistance(); if (len < 1f) Offset(0f, 1f) else d / len }
                    val across = Offset(-dir.y, dir.x) * (r.width + r.height)
                    drawLine(shade, a - across, a + across, thin * 3f); drawLine(Color.White, a - across, a + across, thin * 1.4f)
                    drawLine(shade, b - across, b + across, thin * 3f); drawLine(Color.White, b - across, b + across, thin * 1.4f, pathEffect = dash)
                    drawLine(Color.White.copy(alpha = 0.6f), a, b, thin)
                    handle(a); handle(b, strong = false); handle(hs.getValue(H_BODY), strong = false)
                }
                MaskKind.RADIAL -> {
                    val c = hs.getValue(H_A)
                    val rx = m.radiusX * short; val ry = m.radiusY * short
                    rotate(m.angle, c) {
                        drawOval(shade, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), style = Stroke(thin * 3f))
                        drawOval(Color.White, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), style = Stroke(thin * 1.4f))
                        val k = (1f - m.feather).coerceIn(0.02f, 1f)
                        drawOval(Color.White.copy(alpha = 0.7f), Offset(c.x - rx * k, c.y - ry * k), Size(rx * 2 * k, ry * 2 * k), style = Stroke(thin, pathEffect = dash))
                    }
                    handle(c); handle(hs.getValue(H_RX), strong = false); handle(hs.getValue(H_RY), strong = false)
                }
                MaskKind.POINT -> {
                    val c = hs.getValue(H_A)
                    val reach = m.radiusX * short
                    drawCircle(shade, reach, c, style = Stroke(thin * 3f))
                    drawCircle(Color.White, reach, c, style = Stroke(thin * 1.4f, pathEffect = dash))
                    handle(c); handle(hs.getValue(H_RX), strong = false)
                }
                else -> Unit
            }
        }
    }
    val pts = o.stroke.map { t.toOffset(it) }
    if (pts.isNotEmpty()) {
        val colour = if (o.erase) Color(0xFFE63946) else Color(0xFF4CCB8F)
        val width = max(2f, o.brushWidth * short)
        if (pts.size == 1) drawCircle(colour.copy(alpha = 0.5f), width / 2, pts[0])
        else {
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y) }
            drawPath(path, colour.copy(alpha = 0.5f), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun maskLabel(kind: MaskKind): String = stringResource(
    when (kind) {
        MaskKind.BRUSH -> R.string.local_kind_brush; MaskKind.LINEAR -> R.string.local_kind_linear; MaskKind.RADIAL -> R.string.local_kind_radial
        MaskKind.POINT -> R.string.local_kind_point; MaskKind.SUBJECT -> R.string.local_kind_subject; MaskKind.SKY -> R.string.local_kind_sky
        MaskKind.BACKGROUND -> R.string.local_kind_background
    }
)

/** The sliders that are not at rest, briefly: "Exposure +50 · Saturation -20". */
@Composable
private fun summary(l: LocalAdjust): String {
    val parts = listOf(
        stringResource(R.string.light_exposure) to l.exposure, stringResource(R.string.light_contrast) to l.contrast,
        stringResource(R.string.light_highlights) to l.highlights, stringResource(R.string.light_shadows) to l.shadows,
        stringResource(R.string.colour_temperature) to l.temperature, stringResource(R.string.colour_tint) to l.tint,
        stringResource(R.string.colour_saturation) to l.saturation, stringResource(R.string.local_structure) to l.clarity,
        stringResource(R.string.local_sharpness) to l.sharpness
    ).filter { it.second != 0f }
    if (parts.isEmpty()) return stringResource(R.string.local_unchanged)
    return parts.joinToString(" · ") { (name, v) -> name + " " + String.format(java.util.Locale.getDefault(), "%+d", (v * 100).roundToInt()) }
}

@Composable
fun LocalPanel(
    s: PanelScope, selected: Int, onSelect: (Int) -> Unit, showMask: Boolean, onShowMask: (Boolean) -> Unit, coverage: Float?,
    tools: LocalTools, onAdd: (MaskKind) -> Unit, onDelete: (Int) -> Unit, onDuplicate: (Int) -> Unit
) {
    val locals = s.recipe.locals
    SectionHeading(stringResource(R.string.local_add), modifier = Modifier.padding(bottom = 6.dp))
    ChipRow(
        listOf<Pair<MaskKind?, String>>(
            MaskKind.BRUSH to maskLabel(MaskKind.BRUSH), MaskKind.LINEAR to maskLabel(MaskKind.LINEAR), MaskKind.RADIAL to maskLabel(MaskKind.RADIAL),
            MaskKind.POINT to maskLabel(MaskKind.POINT), MaskKind.SUBJECT to maskLabel(MaskKind.SUBJECT), MaskKind.SKY to maskLabel(MaskKind.SKY),
            MaskKind.BACKGROUND to maskLabel(MaskKind.BACKGROUND)
        ),
        selected = null, onSelect = { k -> if (k != null) onAdd(k) }
    )
    if (locals.isEmpty()) { Hint(stringResource(R.string.local_none)); return }
    Hint(stringResource(R.string.local_pick_hint))
    for ((i, l) in locals.withIndex()) {
        val active = i == selected
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(if (active) StillColors.BandDim else Color.Transparent)
                .border(1.dp, if (active) StillColors.Band else StillColors.Border, RoundedCornerShape(14.dp))
                .clickable { onSelect(if (active) -1 else i) }.padding(end = 10.dp)
        ) {
            Checkbox(l.visible, { v -> s.change { it.withLocal(i) { x -> x.copy(visible = v) } } })
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(stringResource(R.string.local_item, i + 1, maskLabel(l.mask.kind)), style = MaterialTheme.typography.bodyMedium, color = StillColors.Text)
                Text(if (l.visible) summary(l) else stringResource(R.string.local_hidden), style = MaterialTheme.typography.labelSmall, color = StillColors.Dim, maxLines = 1)
            }
        }
    }
    val l = locals.getOrNull(selected) ?: return
    val m = l.mask
    fun mask(block: (LocalMask) -> LocalMask) = s.draft(s.recipe.withLocal(selected) { it.copy(mask = block(it.mask)) })
    fun maskNow(block: (LocalMask) -> LocalMask) = s.change { r -> r.withLocal(selected) { it.copy(mask = block(it.mask)) } }
    Divider()
    ToggleRow(
        stringResource(R.string.local_show_mask), showMask, onShowMask, modifier = Modifier.padding(top = 4.dp),
        detail = coverage?.let { stringResource(R.string.local_coverage, (it * 100).roundToInt()) } ?: stringResource(R.string.local_show_mask_detail)
    )
    when (m.kind) {
        MaskKind.BRUSH -> {
            ChipRow(listOf(false to stringResource(R.string.local_paint), true to stringResource(R.string.local_erase)), tools.erase, { tools.erase = it })
            Knob(stringResource(R.string.local_brush_size), tools.width, 0.01f..0.3f, onChange = { tools.width = it }, onDone = {}, onReset = { tools.width = 0.08f }, haptics = s.haptics, format = { "${(it * 1000).roundToInt()}" })
            Knob(stringResource(R.string.local_softness), tools.softness, 0f..1f, onChange = { tools.softness = it }, onDone = {}, onReset = { tools.softness = 0.6f }, haptics = s.haptics)
            Hint(stringResource(R.string.local_brush_hint))
            if (m.strokes.isNotEmpty()) TextButton(onClick = { maskNow { it.copy(strokes = emptyList()) } }) { Text(stringResource(R.string.local_clear_strokes), color = StillColors.Muted) }
        }
        MaskKind.LINEAR -> Hint(stringResource(R.string.local_linear_hint))
        MaskKind.RADIAL -> {
            Knob(stringResource(R.string.local_feather), m.feather, 0f..1f, onChange = { v -> mask { it.copy(feather = v) } }, onDone = s.commit, onReset = { maskNow { it.copy(feather = 0.6f) } }, haptics = s.haptics)
            Hint(stringResource(R.string.local_radial_hint))
        }
        MaskKind.POINT -> {
            Knob(stringResource(R.string.local_point_size), m.radiusX, 0.03f..0.8f, onChange = { v -> mask { it.copy(radiusX = v) } }, onDone = s.commit, onReset = { maskNow { it.copy(radiusX = 0.18f) } }, haptics = s.haptics, format = { "${(it * 100).roundToInt()}" })
            Knob(stringResource(R.string.local_point_range), m.range, 0f..1f, onChange = { v -> mask { it.copy(range = v) } }, onDone = s.commit, onReset = { maskNow { it.copy(range = 0.4f) } }, haptics = s.haptics)
            Hint(stringResource(R.string.local_point_hint))
        }
        MaskKind.SUBJECT, MaskKind.BACKGROUND -> Hint(stringResource(R.string.local_subject_hint))
        MaskKind.SKY -> Hint(stringResource(if (coverage != null && coverage < 0.01f && !m.invert) R.string.local_sky_none else R.string.local_sky_hint))
    }
    ToggleRow(stringResource(R.string.local_invert), m.invert, { v -> maskNow { it.copy(invert = v) } }, detail = stringResource(R.string.local_invert_detail))
    SectionHeading(stringResource(R.string.local_presets), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    ChipRow(
        listOf<Pair<LocalPreset?, String>>(LocalPreset.DODGE to stringResource(R.string.local_dodge), LocalPreset.BURN to stringResource(R.string.local_burn), LocalPreset.POP to stringResource(R.string.local_pop)),
        selected = null, onSelect = { p -> if (p != null) s.change { r -> r.withLocal(selected) { it.preset(p) } } }
    )
    @Composable fun knob(label: String, value: Float, range: ClosedFloatingPointRange<Float> = -1f..1f, set: (LocalAdjust, Float) -> LocalAdjust) =
        Knob(label, value, range, onChange = { v -> s.draft(s.recipe.withLocal(selected) { set(it, v) }) }, onDone = s.commit, onReset = { s.change { r -> r.withLocal(selected) { set(it, 0f) } } }, haptics = s.haptics, scrub = s.scrub)
    knob(stringResource(R.string.light_exposure), l.exposure, -2f..2f) { a, v -> a.copy(exposure = v) }
    knob(stringResource(R.string.light_contrast), l.contrast) { a, v -> a.copy(contrast = v) }
    knob(stringResource(R.string.light_highlights), l.highlights) { a, v -> a.copy(highlights = v) }
    knob(stringResource(R.string.light_shadows), l.shadows) { a, v -> a.copy(shadows = v) }
    knob(stringResource(R.string.colour_temperature), l.temperature) { a, v -> a.copy(temperature = v) }
    knob(stringResource(R.string.colour_tint), l.tint) { a, v -> a.copy(tint = v) }
    knob(stringResource(R.string.colour_saturation), l.saturation) { a, v -> a.copy(saturation = v) }
    knob(stringResource(R.string.local_structure), l.clarity) { a, v -> a.copy(clarity = v) }
    knob(stringResource(R.string.local_sharpness), l.sharpness) { a, v -> a.copy(sharpness = v) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SecondaryButton(stringResource(R.string.local_duplicate), onClick = { onDuplicate(selected) }, modifier = Modifier.weight(1f), tall = false)
        SecondaryButton(stringResource(R.string.local_delete), onClick = { onDelete(selected) }, modifier = Modifier.weight(1f), tall = false)
    }
}

