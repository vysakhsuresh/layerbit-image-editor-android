package com.layerbit.still.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.Effect
import com.layerbit.still.engine.EffectKind
import com.layerbit.still.engine.Pt
import com.layerbit.still.ui.components.RoundIconButton
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.components.pressScale
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ------------------------------------------------------------------ names

fun effectLabelRes(kind: EffectKind): Int = when (kind) {
    EffectKind.AMBIANCE -> R.string.effect_ambiance
    EffectKind.TONAL_CONTRAST -> R.string.effect_tonal_contrast
    EffectKind.HDR_SCAPE -> R.string.effect_hdr_scape
    EffectKind.DRAMA -> R.string.effect_drama
    EffectKind.BLACK_WHITE -> R.string.effect_black_white
    EffectKind.GLAMOUR_GLOW -> R.string.effect_glamour_glow
    EffectKind.GRUNGE -> R.string.effect_grunge
    EffectKind.RETROLUX -> R.string.effect_retrolux
    EffectKind.GRAINY_FILM -> R.string.effect_grainy_film
    EffectKind.LENS_BLUR -> R.string.effect_lens_blur
    EffectKind.VIGNETTE -> R.string.effect_vignette
}

/** The style names, in the order of the engine's style tables. */
fun effectStyleRes(kind: EffectKind): List<Int> = when (kind) {
    EffectKind.AMBIANCE -> listOf(R.string.effect_style_natural, R.string.effect_style_vivid, R.string.effect_style_soft, R.string.effect_style_deep)
    EffectKind.TONAL_CONTRAST -> listOf(R.string.effect_style_fine_detail, R.string.effect_style_mid_tones, R.string.effect_style_broad, R.string.effect_style_balanced)
    EffectKind.HDR_SCAPE -> listOf(R.string.effect_style_nature, R.string.effect_style_people, R.string.effect_style_fine, R.string.effect_style_strong)
    EffectKind.DRAMA -> listOf(R.string.effect_style_bright, R.string.effect_style_dark, R.string.effect_style_silver, R.string.effect_style_grit)
    EffectKind.BLACK_WHITE -> listOf(R.string.effect_style_neutral, R.string.effect_filter_red, R.string.effect_filter_orange, R.string.effect_filter_yellow, R.string.effect_filter_green, R.string.effect_filter_blue)
    EffectKind.GLAMOUR_GLOW -> listOf(R.string.effect_style_soft, R.string.effect_style_warm, R.string.effect_style_dreamy, R.string.effect_style_bloom)
    EffectKind.GRUNGE -> listOf(R.string.effect_style_dust, R.string.effect_style_rust, R.string.effect_style_concrete, R.string.effect_style_teal)
    EffectKind.RETROLUX -> listOf(R.string.effect_style_summer, R.string.effect_style_amber, R.string.effect_style_faded, R.string.effect_style_rose)
    EffectKind.GRAINY_FILM -> listOf(R.string.effect_style_neutral, R.string.effect_style_warm, R.string.effect_style_cool, R.string.effect_style_mono)
    EffectKind.LENS_BLUR -> listOf(R.string.effect_style_line, R.string.effect_style_round)
    EffectKind.VIGNETTE -> listOf(R.string.effect_style_dark, R.string.effect_style_bright)
}

fun effectExtraRes(kind: EffectKind): Int = when (kind) {
    EffectKind.AMBIANCE, EffectKind.HDR_SCAPE, EffectKind.DRAMA -> R.string.effect_extra_saturation
    EffectKind.TONAL_CONTRAST -> R.string.effect_extra_protect
    EffectKind.BLACK_WHITE -> R.string.effect_extra_contrast
    EffectKind.GLAMOUR_GLOW -> R.string.effect_extra_warmth
    EffectKind.GRUNGE -> R.string.effect_extra_texture
    EffectKind.RETROLUX -> R.string.effect_extra_scratches
    EffectKind.GRAINY_FILM -> R.string.effect_extra_grain_size
    EffectKind.LENS_BLUR -> R.string.effect_extra_transition
    EffectKind.VIGNETTE -> R.string.effect_extra_softness
}

// ------------------------------------------------------------------ panel

/** The stack's [i]th effect changed by [block], live during a gesture. */
private fun PanelScope.draftEffect(i: Int, block: (Effect) -> Effect) {
    if (i !in recipe.effects.indices) return
    draft(recipe.copy(effects = recipe.effects.toMutableList().apply { set(i, block(get(i))) }))
}

/** The stack's [i]th effect changed by [block], as one undo step. */
private fun PanelScope.changeEffect(i: Int, block: (Effect) -> Effect) = change { r ->
    if (i !in r.effects.indices) r else r.copy(effects = r.effects.toMutableList().apply { set(i, block(get(i))) })
}

/** Which effect the panel is editing: the chosen one while it exists, else the last added. */
fun effectSelection(effects: List<Effect>, chosen: Int): Int = if (chosen in effects.indices) chosen else effects.lastIndex

/**
 * Every effect as a card on the picture itself; a tap adds it (or opens it, when it is already
 * in the stack). The open effect shows its styles as cards too, its strength and second
 * control, and where it sits in the stack.
 */
@Composable
fun EffectsPanel(
    s: PanelScope,
    chosen: Int,
    onChoose: (Int) -> Unit,
    thumbs: Map<EffectKind, Bitmap>,
    styleThumbs: Map<EffectKind, List<Bitmap>>,
    onFull: () -> Unit
) {
    val effects = s.recipe.effects
    val sel = effectSelection(effects, chosen)
    val open = effects.getOrNull(sel)
    Hint(stringResource(R.string.effects_hint))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp), modifier = Modifier.fillMaxWidth().testTag("effectStrip")) {
        items(EffectKind.entries.size, key = { EffectKind.entries[it].name }) { k ->
            val kind = EffectKind.entries[k]
            ThumbCard(stringResource(effectLabelRes(kind)), thumbs[kind], active = open?.kind == kind, inStack = effects.any { it.kind == kind }) {
                val at = effects.indexOfFirst { it.kind == kind }
                when {
                    at >= 0 -> onChoose(at)
                    effects.size >= Effect.MAX -> onFull()
                    else -> { s.change { it.copy(effects = it.effects + Effect.added(kind)) }; onChoose(effects.size) }
                }
            }
        }
    }
    if (open == null) return
    val kind = open.kind
    SectionHeading(stringResource(effectLabelRes(kind)), modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    val styleNames = effectStyleRes(kind).map { stringResource(it) }
    val styles = styleThumbs[kind]
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp), modifier = Modifier.fillMaxWidth().testTag("effectStyles")) {
        items(styleNames.size, key = { "${kind.name}-$it" }) { i ->
            ThumbCard(styleNames[i], styles?.getOrNull(i), active = open.style == i, inStack = false, small = true) {
                s.changeEffect(sel) { it.copy(style = i) }
            }
        }
    }
    Knob(
        stringResource(R.string.looks_strength), open.strength, kind.strengthRange,
        onChange = { v -> s.draftEffect(sel) { it.copy(strength = v) } }, onDone = s.commit,
        onReset = { s.changeEffect(sel) { it.copy(strength = kind.defaultStrength) } }, haptics = s.haptics, scrub = s.scrub
    )
    Knob(
        stringResource(effectExtraRes(kind)), open.extra, kind.extraRange,
        onChange = { v -> s.draftEffect(sel) { it.copy(extra = v) } }, onDone = s.commit,
        onReset = { s.changeEffect(sel) { it.copy(extra = kind.extraDefault) } }, haptics = s.haptics, scrub = s.scrub
    )
    if (kind == EffectKind.LENS_BLUR) Hint(stringResource(if (open.style == 0) R.string.effects_lens_hint else R.string.effects_lens_round_hint))
    if (kind.seeded) SecondaryButton(stringResource(R.string.effects_shuffle), onClick = { s.changeEffect(sel) { it.copy(seed = (it.seed % 99_991) + 1) } }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), tall = false)

    SectionHeading(stringResource(R.string.effects_stack), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    for ((i, e) in effects.withIndex()) {
        StackRow(
            "${i + 1}. ${stringResource(effectLabelRes(e.kind))} · ${stringResource(effectStyleRes(e.kind)[e.style])}",
            active = i == sel, first = i == 0, last = i == effects.lastIndex,
            onOpen = { onChoose(i) },
            onUp = { s.change { r -> r.copy(effects = r.effects.toMutableList().apply { add(i - 1, removeAt(i)) }) }; onChoose(i - 1) },
            onDown = { s.change { r -> r.copy(effects = r.effects.toMutableList().apply { add(i + 1, removeAt(i)) }) }; onChoose(i + 1) },
            onRemove = { s.change { r -> r.copy(effects = r.effects.filterIndexed { k, _ -> k != i }) }; onChoose(-1) }
        )
    }
    if (effects.size > 1) Hint(stringResource(R.string.effects_order_hint))
}

@Composable
private fun ThumbCard(label: String, bitmap: Bitmap?, active: Boolean, inStack: Boolean, small: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val side = if (small) 60.dp else 72.dp
    val frame by animateColorAsState(if (active) StillColors.Band else if (inStack) StillColors.BorderStrong else StillColors.Border, label = "effectFrame")
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(side + 6.dp).pressScale(interaction, 0.95f)
            .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
    ) {
        Box(Modifier.size(side).clip(shape).background(StillColors.SurfaceDim).border(if (active) 2.5.dp else 1.dp, frame, shape), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
            if (inStack) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(9.dp).clip(RoundedCornerShape(999.dp)).background(StillColors.Band))
        }
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = if (active) StillColors.Text else StillColors.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun StackRow(label: String, active: Boolean, first: Boolean, last: Boolean, onOpen: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().clip(shape).background(if (active) StillColors.Band.copy(alpha = 0.14f) else StillColors.SurfaceDim)
            .border(1.dp, if (active) StillColors.Band else StillColors.Border, shape)
            .clickable(role = Role.Button, onClick = onOpen).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (active) StillColors.Text else StillColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        RoundIconButton(icon = Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.effects_up), onClick = onUp, enabled = !first, size = 32.dp)
        RoundIconButton(icon = Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.effects_down), onClick = onDown, enabled = !last, size = 32.dp)
        RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.effects_remove), onClick = onRemove, size = 32.dp)
    }
}

// ------------------------------------------------------------------ lens blur on the canvas

/** The lens blur's handles on screen: the centre, the edge (width), the second edge (round only) and the turn. */
private class LensHandles(val centre: Offset, val edge: Offset, val edge2: Offset?, val turn: Offset, val dir: Offset, val normal: Offset, val short: Float)

private fun lensHandles(e: Effect, t: CanvasTransform): LensHandles? {
    val r = t.rect
    if (r.width <= 0f || r.height <= 0f) return null
    val short = min(r.width, r.height)
    val c = t.toOffset(e.center)
    val a = Math.toRadians(e.angle.toDouble())
    val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
    val normal = Offset(-dir.y, dir.x)
    val size = e.size * short
    return if (e.linear) {
        LensHandles(c, c + normal * size, null, c + dir * (short * 0.32f), dir, normal, short)
    } else {
        LensHandles(c, c + dir * size, c + normal * (size * e.aspect), c - dir * (size + short * 0.1f), dir, normal, short)
    }
}

/** What the canvas draws for the lens blur: the sharp zone, where the blur is full, and the handles. */
fun lensOverlay(e: Effect): Overlay = Overlay.Custom(e, draw@{ t ->
    val h = lensHandles(e, t) ?: return@draw
    val r = t.rect
    val thin = 1.5.dp.toPx()
    val dot = 9.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))
    val feather = (0.04f + 0.6f * e.extra) * h.short
    clipRect(r.left, r.top, r.right, r.bottom) {
        if (e.linear) {
            val far = h.dir * (r.width + r.height)
            for (sign in listOf(-1f, 1f)) {
                val sharp = h.centre + h.normal * (e.size * h.short * sign)
                val soft = h.centre + h.normal * ((e.size * h.short + feather) * sign)
                drawLine(Color.Black.copy(alpha = 0.35f), sharp - far, sharp + far, thin * 2.6f)
                drawLine(Color.White, sharp - far, sharp + far, thin * 1.3f)
                drawLine(Color.White.copy(alpha = 0.55f), soft - far, soft + far, thin, pathEffect = dash)
            }
        } else {
            val rx = e.size * h.short; val ry = rx * e.aspect
            rotate(e.angle, pivot = h.centre) {
                drawOval(Color.Black.copy(alpha = 0.35f), Offset(h.centre.x - rx, h.centre.y - ry), Size(rx * 2, ry * 2), style = Stroke(thin * 2.6f))
                drawOval(Color.White, Offset(h.centre.x - rx, h.centre.y - ry), Size(rx * 2, ry * 2), style = Stroke(thin * 1.3f))
                drawOval(Color.White.copy(alpha = 0.55f), Offset(h.centre.x - rx - feather, h.centre.y - ry - feather), Size((rx + feather) * 2, (ry + feather) * 2), style = Stroke(thin, pathEffect = dash))
            }
        }
        drawLine(Color.White.copy(alpha = 0.6f), h.centre, h.turn, thin, pathEffect = dash)
    }
    for (p in listOfNotNull(h.edge, h.edge2, h.turn)) {
        drawCircle(Color.White, dot * 0.8f, p)
        drawCircle(StillColors.Band, dot * 0.8f, p, style = Stroke(thin * 1.3f))
    }
    drawCircle(Color.White, dot, h.centre)
    drawCircle(StillColors.Band, dot * 0.55f, h.centre)
})

/**
 * Dragging the lens blur's handles: the centre moves the focus, the edge sets its width (and,
 * round, the second edge its height), the outer dot turns it. Each drag is one undo step.
 */
fun lensTouch(
    effect: () -> Effect?,
    t: CanvasTransform,
    reach: Float,
    update: (Effect) -> Unit,
    commit: () -> Unit
): TouchMode = TouchMode.Handles(
    hit = hit@{ at ->
        val e = effect() ?: return@hit null
        val h = lensHandles(e, t) ?: return@hit null
        when {
            (at - h.turn).getDistance() <= reach -> 3
            (at - h.edge).getDistance() <= reach -> 1
            h.edge2 != null && (at - h.edge2).getDistance() <= reach -> 2
            (at - h.centre).getDistance() <= reach * 1.6f -> 0
            else -> null
        }
    },
    onDrag = { handle, pt, delta ->
        val e = effect()
        val h = e?.let { lensHandles(it, t) }
        if (e != null && h != null) {
            val finger = t.toOffset(pt) - h.centre
            fun along(axis: Offset) = abs(finger.x * axis.x + finger.y * axis.y) / h.short
            val next = when (handle) {
                0 -> e.copy(center = Pt((e.center.x + delta.x).coerceIn(0f, 1f), (e.center.y + delta.y).coerceIn(0f, 1f)))
                1 -> e.copy(size = (if (e.linear) along(h.normal) else along(h.dir)).coerceIn(Effect.MIN_SIZE, Effect.MAX_SIZE))
                2 -> e.copy(aspect = (along(h.normal) / e.size).coerceIn(Effect.MIN_ASPECT, Effect.MAX_ASPECT))
                else -> {
                    // The turn dot sits ahead of the line, or behind the ellipse's width.
                    var deg = Math.toDegrees(atan2(finger.y.toDouble(), finger.x.toDouble())).toFloat()
                    if (!e.linear) deg += 180f
                    while (deg > 180f) deg -= 360f
                    while (deg < -180f) deg += 360f
                    e.copy(angle = deg)
                }
            }
            if (next.valid) update(next)
        }
    },
    onEnd = commit
)
