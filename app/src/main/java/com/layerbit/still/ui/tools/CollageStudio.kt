package com.layerbit.still.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.Collage
import com.layerbit.still.files.StillFile
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.editor.Hint
import com.layerbit.still.ui.editor.Knob
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.max
import kotlin.math.roundToInt

/** What the collage preview needs from its view model. */
class CollageHooks(
    val picture: suspend (StillFile) -> Bitmap?,
    val framing: (StillFile) -> Collage.Framing,
    val setFraming: (StillFile, Collage.Framing) -> Unit,
    val resetFraming: (StillFile) -> Unit,
    val swap: (Int, Int) -> Unit,
    /** Backgrounds in the pictures' own colours. */
    val matched: List<Int>
)

private const val PENDING = 0
private const val FRAMING = 1
private const val SWAPPING = 2

/**
 * The collage as it will be written, drawn from the engine's plan at the size of the screen:
 * the same cells, spacing, corners and framing the export uses, so what is seen is what is
 * saved. Drag inside a cell to move its picture, pinch to zoom, hold and drag onto another cell
 * to swap, tap to pick one for the controls beneath.
 */
@Composable
fun CollageStudio(files: List<StillFile>, config: ToolConfig, hooks: CollageHooks, enabled: Boolean) {
    val plan = remember(files, config.layout, config.collageStyle) {
        Collage.plan(config.layout, files.map { it.width.toFloat() / max(1, it.height) }, config.collageStyle)
    }
    val pictures: List<ImageBitmap?> = files.map { f ->
        key(f.file.path) { produceState<ImageBitmap?>(null, f.file.path) { value = hooks.picture(f)?.asImageBitmap() }.value }
    }
    var selected by remember { mutableIntStateOf(-1) }
    var lifted by remember { mutableIntStateOf(-1) }
    var dragAt by remember { mutableStateOf(Offset.Unspecified) }
    val picked = selected.takeIf { it in files.indices } ?: -1
    val haptic = LocalHapticFeedback.current
    val latestPlan by rememberUpdatedState(plan)
    val latestFiles by rememberUpdatedState(files)
    val latestHooks by rememberUpdatedState(hooks)
    val background = Color(config.collageBackground or 0xFF000000.toInt())
    val accent = StillColors.Band
    val placeholder = StillColors.SurfaceDim
    val description = stringResource(R.string.collage_preview_description)

    Column(Modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.collage_preview), modifier = Modifier.padding(bottom = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val a = plan.canvasAspect
            val tallest = 440.dp
            val (w, h) = if (maxWidth / a <= tallest) maxWidth to maxWidth / a else tallest * a to tallest
            Canvas(
                Modifier.size(w, h).clip(RoundedCornerShape(4.dp)).border(1.dp, StillColors.Border, RoundedCornerShape(4.dp))
                    .semantics { contentDescription = description }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val sw = size.width.toFloat(); val sh = size.height.toFloat()
                            val gesturePlan = latestPlan
                            val index = Collage.cellAt(gesturePlan, down.position.x / sw, down.position.y / sh)
                            val list = latestFiles
                            if (index !in list.indices || index !in gesturePlan.cells.indices) return@awaitEachGesture
                            val file = list[index]
                            val cell = Collage.cellPx(gesturePlan.cells[index], sw, sh)
                            val pw = file.width.toFloat(); val ph = file.height.toFloat()
                            var mode = PENDING
                            var travel = Offset.Zero
                            val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                            var now = down.uptimeMillis
                            while (true) {
                                val event = if (mode == PENDING) withTimeoutOrNull(max(1L, deadline - now)) { awaitPointerEvent() } else awaitPointerEvent()
                                if (event == null) {
                                    // Held still: lift the picture to swap it.
                                    mode = SWAPPING; lifted = index; dragAt = down.position
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    continue
                                }
                                now = event.changes.firstOrNull()?.uptimeMillis ?: now
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    when (mode) {
                                        PENDING -> selected = if (selected == index) -1 else index
                                        SWAPPING -> {
                                            val target = Collage.cellAt(latestPlan, dragAt.x / sw, dragAt.y / sh)
                                            if (target >= 0 && target != index && target in latestFiles.indices) {
                                                latestHooks.swap(index, target)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                if (selected == index) selected = target else if (selected == target) selected = index
                                            }
                                            lifted = -1; dragAt = Offset.Unspecified
                                        }
                                    }
                                    break
                                }
                                when (mode) {
                                    PENDING -> {
                                        travel += pressed.first().positionChange()
                                        if (pressed.size > 1 || travel.getDistance() > viewConfiguration.touchSlop) {
                                            mode = FRAMING; selected = index
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                    FRAMING -> {
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        var f = latestHooks.framing(file)
                                        if (zoom != 1f) f = Collage.zoom(pw, ph, cell.width, cell.height, f, zoom)
                                        f = Collage.pan(pw, ph, cell.width, cell.height, f, pan.x / cell.width, pan.y / cell.height)
                                        latestHooks.setFraming(file, f)
                                        event.changes.forEach { it.consume() }
                                    }
                                    SWAPPING -> {
                                        dragAt = pressed.first().position
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    }
            ) {
                drawRect(background)
                for ((i, c) in plan.cells.withIndex()) {
                    val f = files.getOrNull(i) ?: continue
                    val r = Collage.cellPx(c, size.width, size.height)
                    val radius = Collage.cornerPx(plan.style, size.width, size.height, r)
                    val path = Path().apply { addRoundRect(RoundRect(r.left, r.top, r.right, r.bottom, CornerRadius(radius))) }
                    clipPath(path) {
                        drawPicture(pictures.getOrNull(i), f, hooks.framing(f), r, placeholder)
                        if (config.layout == Collage.Layout.POLAROID) {
                            val border = Collage.polaroidBorderPx(r)
                            drawRoundRect(Color.White, Offset(r.left, r.top), Size(r.width, r.height), CornerRadius(radius), style = Stroke(border * 2))
                        }
                        if (i == lifted) drawRect(Color.Black.copy(alpha = 0.4f), Offset(r.left, r.top), Size(r.width, r.height))
                    }
                    val target = lifted >= 0 && dragAt != Offset.Unspecified && i != lifted && Collage.cellAt(plan, dragAt.x / size.width, dragAt.y / size.height) == i
                    if (i == picked || target) {
                        val stroke = (if (target) 4.dp else 2.dp).toPx()
                        drawRoundRect(accent, Offset(r.left + stroke / 2, r.top + stroke / 2), Size(r.width - stroke, r.height - stroke), CornerRadius(radius), style = Stroke(stroke))
                    }
                }
                // The lifted picture follows the finger, smaller, so the cell beneath it shows.
                val held = files.getOrNull(lifted)
                if (held != null && dragAt != Offset.Unspecified && lifted in plan.cells.indices) {
                    val r = Collage.cellPx(plan.cells[lifted], size.width, size.height)
                    val gw = r.width * 0.6f; val gh = r.height * 0.6f
                    val ghost = Collage.PxRect(dragAt.x - gw / 2, dragAt.y - gh / 2, dragAt.x + gw / 2, dragAt.y + gh / 2)
                    drawPicture(pictures.getOrNull(lifted), held, hooks.framing(held), ghost, placeholder, alpha = 0.85f)
                    drawRect(accent, Offset(ghost.left, ghost.top), Size(ghost.width, ghost.height), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Hint(stringResource(R.string.collage_steer_hint))

        if (picked >= 0) {
            val f = files[picked]
            val framing = hooks.framing(f)
            val cell = plan.cells[picked]
            // The cell's shape is all the framing needs; any size at that shape gives the same window.
            val cw = cell.width * plan.canvasAspect; val ch = cell.height
            Text(stringResource(R.string.collage_picked, picked + 1, f.displayName), style = MaterialTheme.typography.bodyMedium, color = StillColors.Text, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            Knob(
                stringResource(R.string.collage_zoom), framing.zoom, 1f..Collage.Framing.MAX_ZOOM,
                onChange = { hooks.setFraming(f, Collage.settle(f.width.toFloat(), f.height.toFloat(), cw, ch, framing.copy(zoom = it))) },
                onDone = {}, onReset = { hooks.resetFraming(f) },
                format = { String.format(java.util.Locale.getDefault(), "%.1f×", it) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                SecondaryButton(stringResource(R.string.collage_earlier), onClick = { hooks.swap(picked, picked - 1); selected = picked - 1 }, modifier = Modifier.weight(1f), tall = false, enabled = enabled && picked > 0)
                SecondaryButton(stringResource(R.string.collage_later), onClick = { hooks.swap(picked, picked + 1); selected = picked + 1 }, modifier = Modifier.weight(1f), tall = false, enabled = enabled && picked < files.size - 1)
                SecondaryButton(stringResource(R.string.collage_recentre), onClick = { hooks.resetFraming(f) }, modifier = Modifier.weight(1f), tall = false, enabled = enabled)
            }
        }
    }
}

/** One picture into a rectangle, showing the part the engine's window picks for that shape. */
private fun DrawScope.drawPicture(image: ImageBitmap?, file: StillFile, framing: Collage.Framing, r: Collage.PxRect, placeholder: Color, alpha: Float = 1f) {
    if (image == null) {
        drawRect(placeholder, Offset(r.left, r.top), Size(r.width, r.height), alpha = alpha)
        return
    }
    val w = Collage.window(file.width.toFloat(), file.height.toFloat(), r.width, r.height, framing)
    val sx = (w.left * image.width).roundToInt().coerceIn(0, image.width - 1)
    val sy = (w.top * image.height).roundToInt().coerceIn(0, image.height - 1)
    val sw = (w.width * image.width).roundToInt().coerceIn(1, image.width - sx)
    val sh = (w.height * image.height).roundToInt().coerceIn(1, image.height - sy)
    drawImage(
        image, IntOffset(sx, sy), IntSize(sw, sh),
        IntOffset(r.left.roundToInt(), r.top.roundToInt()), IntSize(max(1, r.width.roundToInt()), max(1, r.height.roundToInt())),
        alpha = alpha, filterQuality = FilterQuality.Medium
    )
}
