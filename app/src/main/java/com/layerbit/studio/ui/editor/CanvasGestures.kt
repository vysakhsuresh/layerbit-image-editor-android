package com.layerbit.studio.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import com.layerbit.studio.engine.Pt
import kotlin.math.max
import kotlin.math.min

/**
 * Where the picture sits in the canvas box: fitted, then scaled by [zoom] about the centre and
 * moved by [pan]. Overlays are drawn in box coordinates from [rect], so handles keep their
 * size while the picture zooms, and a touch maps to a fraction of the picture through [toPt].
 */
@Stable
class CanvasTransform {
    var zoom by mutableFloatStateOf(1f); private set
    var pan by mutableStateOf(Offset.Zero); private set
    var box by mutableStateOf(IntSize.Zero)
    var image by mutableStateOf(IntSize.Zero)

    val zoomed: Boolean get() = zoom > 1.001f

    val rect: Rect
        get() {
            if (box.width == 0 || image.width == 0 || image.height == 0) return Rect.Zero
            val fit = min(box.width.toFloat() / image.width, box.height.toFloat() / image.height) * zoom
            val w = image.width * fit; val h = image.height * fit
            val left = (box.width - w) / 2 + pan.x; val top = (box.height - h) / 2 + pan.y
            return Rect(left, top, left + w, top + h)
        }

    fun toPt(at: Offset): Pt {
        val r = rect
        if (r.width <= 0f || r.height <= 0f) return Pt(0f, 0f)
        return Pt(((at.x - r.left) / r.width).coerceIn(0f, 1f), ((at.y - r.top) / r.height).coerceIn(0f, 1f))
    }

    fun toOffset(p: Pt): Offset { val r = rect; return Offset(r.left + p.x * r.width, r.top + p.y * r.height) }

    fun inside(at: Offset): Boolean = rect.contains(at)

    fun step(up: Boolean) { set(if (up) min(MAX, zoom * 1.5f) else max(1f, zoom / 1.5f), Offset(box.width / 2f, box.height / 2f), Offset.Zero) }

    fun fit() { zoom = 1f; pan = Offset.Zero }

    /** Scales by [target]/zoom about [centre], keeping the point under it there, then moves by [delta]. */
    fun set(target: Float, centre: Offset, delta: Offset) {
        val z = target.coerceIn(1f, MAX)
        val k = z / zoom
        val c = Offset(box.width / 2f, box.height / 2f)
        // The picture's centre is box centre + pan; scaling about `centre` moves it.
        val moved = (c + pan - centre) * k + centre - c + delta
        zoom = z
        pan = clamp(moved)
    }

    private fun clamp(o: Offset): Offset {
        if (!zoomed) return Offset.Zero
        val r = rect
        val w = r.width; val h = r.height
        val maxX = max(0f, (w - box.width) / 2); val maxY = max(0f, (h - box.height) / 2)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    companion object { const val MAX = 8f }
}

/** What one finger means on the canvas while a panel is open. */
sealed interface TouchMode {
    /** Pan when zoomed, press-and-hold to compare. */
    data object View : TouchMode
    /** A stroke or a rectangle; [onStart], [onMove], [onEnd] in picture fractions. */
    data class Draw(val onStart: (Pt) -> Unit, val onMove: (Pt) -> Unit, val onEnd: () -> Unit, val onCancel: () -> Unit) : TouchMode
    /** A tap places something. */
    data class Tap(val onTap: (Pt) -> Unit) : TouchMode
    /** Drag a handle found by [hit] at the touch, in box coordinates; [onDrag] gets the delta in fractions. */
    data class Handles(val hit: (Offset) -> Int?, val onDrag: (Int, Pt, Offset) -> Unit, val onEnd: () -> Unit, val onTap: ((Pt) -> Unit)? = null) : TouchMode
}

/**
 * One handler for every touch on the canvas, so a pinch is never also a stroke and a hold is
 * never also a pan: two fingers always zoom and move; one finger does what the panel says;
 * a finger that stays still for a moment in [TouchMode.View] holds the original in view.
 */
fun Modifier.canvasGestures(t: CanvasTransform, mode: TouchMode, onCompare: (Boolean) -> Unit, slopPx: Float, holdMs: Long = 220): Modifier =
    pointerInput(t, mode) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            val startTime = down.uptimeMillis
            var multi = false
            var moved = false
            var comparing = false
            var drawing = false
            var handle: Int? = null
            var lastPos = start
            var handleLast = start
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        if (!multi) {
                            multi = true
                            if (drawing) { (mode as? TouchMode.Draw)?.onCancel?.invoke(); drawing = false }
                            if (comparing) { onCompare(false); comparing = false }
                        }
                        t.set(t.zoom * event.calculateZoom(), event.calculateCentroid(useCurrent = true), event.calculatePan())
                        event.changes.forEach { it.consume() }
                    } else if (pressed.size == 1 && !multi) {
                        val change = pressed.first()
                        val pos = change.position
                        val dist = (pos - start).getDistance()
                        if (!moved && dist > slopPx) moved = true
                        when (mode) {
                            is TouchMode.Draw -> {
                                if (!drawing && t.inside(start)) { drawing = true; mode.onStart(t.toPt(start)) }
                                if (drawing && pos != lastPos) mode.onMove(t.toPt(pos))
                                change.consume()
                            }
                            is TouchMode.Handles -> {
                                if (handle == null && !moved) handle = mode.hit(start)
                                val h = handle
                                if (h != null && moved) {
                                    val delta = pos - handleLast
                                    val r = t.rect
                                    mode.onDrag(h, t.toPt(pos), Offset(if (r.width > 0) delta.x / r.width else 0f, if (r.height > 0) delta.y / r.height else 0f))
                                    handleLast = pos
                                    change.consume()
                                } else if (h == null && moved && t.zoomed) {
                                    t.set(t.zoom, pos, pos - lastPos); change.consume()
                                }
                            }
                            is TouchMode.Tap -> Unit
                            TouchMode.View -> {
                                if (moved) {
                                    if (comparing) { onCompare(false); comparing = false }
                                    if (t.zoomed) { t.set(t.zoom, pos, pos - lastPos); change.consume() }
                                } else if (!comparing && change.uptimeMillis - startTime > holdMs) {
                                    comparing = true; onCompare(true)
                                }
                            }
                        }
                        lastPos = pos
                    }
                    if (pressed.isEmpty()) break
                }
            } finally {
                if (drawing) (mode as TouchMode.Draw).onEnd()
                if (comparing) onCompare(false)
                if (handle != null) (mode as TouchMode.Handles).onEnd()
                if (mode is TouchMode.Tap && !moved && !multi && t.inside(start)) mode.onTap(t.toPt(start))
                if (mode is TouchMode.Handles && handle == null && !moved && !multi && t.inside(start)) mode.onTap?.invoke(t.toPt(start))
            }
        }
    }

/** The fitted picture size for a box, for sizing a frame before the preview arrives. */
fun fitted(box: IntSize, image: IntSize): Size {
    if (box.width == 0 || image.width == 0) return Size.Zero
    val fit = min(box.width.toFloat() / image.width, box.height.toFloat() / image.height)
    return Size(image.width * fit, image.height * fit)
}
