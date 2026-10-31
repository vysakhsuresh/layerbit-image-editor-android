package com.layerbit.still.ui.editor

import androidx.compose.runtime.State
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
import com.layerbit.still.engine.Pt
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
    /** Where a finger is dragging a handle, for the magnifier; null when no handle is held. */
    var loupe by mutableStateOf<Offset?>(null)

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
    /**
     * A stroke with [draw], unless the touch starts on a handle found by [hit]: then that handle
     * is dragged and [onDrag] gets the delta in fractions. A tap with no handle under it is a
     * one-point stroke. Heal uses it: paint a spot, or drag a patch's dashed circle.
     */
    data class DrawOrDrag(val draw: Draw, val hit: (Offset) -> Int?, val onDrag: (Int, Offset) -> Unit, val onDragEnd: () -> Unit) : TouchMode
    /** A sideways swipe anywhere on the picture moves the chosen slider; [onDelta] gets the move as a fraction of the width. */
    data class Scrub(val onDelta: (Float) -> Unit, val onEnd: () -> Unit) : TouchMode
}

/**
 * One handler for every touch on the canvas, so a pinch is never also a stroke and a hold is
 * never also a pan: two fingers always zoom and move; one finger does what the panel says;
 * a finger that stays still for a moment in [TouchMode.View] holds the original in view.
 */
fun Modifier.canvasGestures(t: CanvasTransform, modeState: State<TouchMode>, onCompare: (Boolean) -> Unit, slopPx: Float, holdMs: Long = 220): Modifier =
    // Keyed on the kind of touch only. A drag redraws the screen and hands over a fresh mode each
    // frame; restarting on that would cancel the drag after its first step and end it there.
    // The handler reads the latest mode at every event instead.
    pointerInput(t, modeState.value::class) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            val startTime = down.uptimeMillis
            var multi = false
            var moved = false
            var comparing = false
            var drawing = false
            var handle: Int? = null
            var decided = false
            var scrubbing = false
            var lastPos = start
            var handleLast = start
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val mode = modeState.value
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        if (!multi) {
                            multi = true
                            if (drawing) { (mode as? TouchMode.Draw)?.onCancel?.invoke(); (mode as? TouchMode.DrawOrDrag)?.draw?.onCancel?.invoke(); drawing = false }
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
                                // A brush hides what it touches; the magnifier shows it.
                                if (drawing && moved) t.loupe = pos
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
                                    t.loupe = pos
                                    change.consume()
                                } else if (h == null && moved && t.zoomed) {
                                    t.set(t.zoom, pos, pos - lastPos); change.consume()
                                }
                            }
                            is TouchMode.DrawOrDrag -> {
                                if (!decided) {
                                    decided = true
                                    handle = mode.hit(start)
                                    if (handle == null && t.inside(start)) { drawing = true; mode.draw.onStart(t.toPt(start)) }
                                }
                                val h = handle
                                if (h != null) {
                                    if (moved) {
                                        val delta = pos - handleLast
                                        val r = t.rect
                                        mode.onDrag(h, Offset(if (r.width > 0) delta.x / r.width else 0f, if (r.height > 0) delta.y / r.height else 0f))
                                        handleLast = pos
                                        t.loupe = pos
                                    }
                                } else if (drawing) {
                                    if (pos != lastPos) mode.draw.onMove(t.toPt(pos))
                                    if (moved) t.loupe = pos
                                }
                                change.consume()
                            }
                            is TouchMode.Tap -> Unit
                            is TouchMode.Scrub -> {
                                if (moved) {
                                    scrubbing = true
                                    mode.onDelta((pos.x - lastPos.x) / max(1f, t.box.width.toFloat()))
                                    change.consume()
                                }
                            }
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
                val mode = modeState.value
                if (scrubbing) (mode as TouchMode.Scrub).onEnd()
                if (drawing) { (mode as? TouchMode.Draw)?.onEnd?.invoke(); (mode as? TouchMode.DrawOrDrag)?.draw?.onEnd?.invoke() }
                if (comparing) onCompare(false)
                t.loupe = null
                if (handle != null) { (mode as? TouchMode.Handles)?.onEnd?.invoke(); (mode as? TouchMode.DrawOrDrag)?.onDragEnd?.invoke() }
                // A quick tap never reaches the move loop; for a brush it is a dab.
                if (mode is TouchMode.DrawOrDrag && !decided && !multi && t.inside(start) && mode.hit(start) == null) { mode.draw.onStart(t.toPt(start)); mode.draw.onEnd() }
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
