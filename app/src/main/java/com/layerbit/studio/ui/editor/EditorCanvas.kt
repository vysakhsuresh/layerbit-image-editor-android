package com.layerbit.studio.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.layerbit.studio.R
import com.layerbit.studio.engine.Crop
import com.layerbit.studio.engine.Mark
import com.layerbit.studio.engine.MarkKind
import com.layerbit.studio.engine.Pt
import com.layerbit.studio.engine.Quad
import com.layerbit.studio.ui.theme.StudioColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the canvas draws over the picture for the open panel. */
sealed interface Overlay {
    data object None : Overlay
    data class CropOverlay(val crop: Crop, val dragging: Boolean) : Overlay
    data class QuadOverlay(val quad: Quad) : Overlay
    /** The stroke in progress and the source marker for clone. */
    data class Stroke(val kind: MarkKind, val points: List<Pt>, val color: Int, val width: Float, val cloneSource: Pt?) : Overlay
    /** Movable text layers, highlighted. */
    data class Texts(val marks: List<Mark>) : Overlay
}

/**
 * The picture, fitted and zoomable, with the overlay the panel asks for and the gestures the
 * panel needs. Chequerboard beneath, so transparency reads as transparency.
 */
@Composable
fun EditorCanvas(
    bitmap: Bitmap?,
    original: Bitmap?,
    comparing: Boolean,
    transform: CanvasTransform,
    overlay: Overlay,
    mode: TouchMode,
    onCompare: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val slop = with(density) { 6.dp.toPx() }
    val shown = if (comparing && original != null) original else bitmap
    val image = remember(shown) { shown?.asImageBitmap() }
    val label = stringResource(R.string.editor_canvas_description)
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(StudioColors.Canvas)
            .onSizeChanged { transform.box = it }
            .canvasGestures(transform, mode, onCompare, slop)
            .semantics { contentDescription = label }
    ) {
        if (shown != null) transform.image = IntSize(shown.width, shown.height)
        Canvas(Modifier.fillMaxSize()) {
            // Chequerboard: a flat colour would hide a transparent corner.
            val cell = 14.dp.toPx()
            val cols = (size.width / cell).toInt() + 1; val rows = (size.height / cell).toInt() + 1
            for (y in 0 until rows) for (x in 0 until cols) if ((x + y) % 2 == 0) {
                drawRect(Color.White.copy(alpha = 0.035f), Offset(x * cell, y * cell), Size(cell, cell))
            }
            if (image == null) return@Canvas
            val r = transform.rect
            drawImage(
                image, dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                dstSize = IntSize(max(1, r.width.toInt()), max(1, r.height.toInt())),
                filterQuality = if (transform.zoomed) FilterQuality.High else FilterQuality.Medium
            )
            if (!comparing) drawOverlay(overlay, transform, this)
        }
        if (comparing) {
            Text(
                stringResource(R.string.editor_showing_original), style = MaterialTheme.typography.labelSmall, color = StudioColors.Text,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(StudioColors.Surface.copy(alpha = 0.85f), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

private fun DrawScope.drawOverlay(overlay: Overlay, t: CanvasTransform, scope: DrawScope) {
    val r = t.rect
    val handleR = 9.dp.toPx()
    val thin = 1.5.dp.toPx()
    when (overlay) {
        Overlay.None -> Unit
        is Overlay.CropOverlay -> {
            val c = overlay.crop
            val cr = Rect(r.left + c.left * r.width, r.top + c.top * r.height, r.left + c.right * r.width, r.top + c.bottom * r.height)
            // Dim everything outside the crop.
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(r.left, r.top), Size(r.width, cr.top - r.top))
            drawRect(dim, Offset(r.left, cr.bottom), Size(r.width, r.bottom - cr.bottom))
            drawRect(dim, Offset(r.left, cr.top), Size(cr.left - r.left, cr.height))
            drawRect(dim, Offset(cr.right, cr.top), Size(r.right - cr.right, cr.height))
            // Thirds while dragging, the frame always.
            if (overlay.dragging) {
                for (i in 1..2) {
                    drawLine(Color.White.copy(alpha = 0.45f), Offset(cr.left + cr.width * i / 3, cr.top), Offset(cr.left + cr.width * i / 3, cr.bottom), thin * 0.7f)
                    drawLine(Color.White.copy(alpha = 0.45f), Offset(cr.left, cr.top + cr.height * i / 3), Offset(cr.right, cr.top + cr.height * i / 3), thin * 0.7f)
                }
            }
            drawRect(Color.White, Offset(cr.left, cr.top), cr.size, style = Stroke(thin))
            // Corner brackets and edge pips.
            val k = min(22.dp.toPx(), min(cr.width, cr.height) / 3)
            val w = 3.dp.toPx()
            for ((cx, cy) in listOf(cr.left to cr.top, cr.right to cr.top, cr.left to cr.bottom, cr.right to cr.bottom)) {
                val dx = if (cx == cr.left) k else -k; val dy = if (cy == cr.top) k else -k
                drawLine(Color.White, Offset(cx, cy), Offset(cx + dx, cy), w, StrokeCap.Round)
                drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + dy), w, StrokeCap.Round)
            }
            val mid = Offset(cr.center.x, cr.center.y)
            for ((px, py) in listOf(mid.x to cr.top, mid.x to cr.bottom, cr.left to mid.y, cr.right to mid.y)) {
                val horizontal = py == cr.top || py == cr.bottom
                drawLine(Color.White, Offset(px - if (horizontal) k / 2 else 0f, py - if (horizontal) 0f else k / 2), Offset(px + if (horizontal) k / 2 else 0f, py + if (horizontal) 0f else k / 2), w, StrokeCap.Round)
            }
        }
        is Overlay.QuadOverlay -> {
            val q = overlay.quad
            val pts = q.points.map { t.toOffset(it) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until 4) lineTo(pts[i].x, pts[i].y); close() }
            drawPath(path, Color.Black.copy(alpha = 0.25f))
            drawPath(path, StudioColors.Band, style = Stroke(thin * 1.3f))
            for (p in pts) {
                drawCircle(Color.White, handleR, p)
                drawCircle(StudioColors.Band, handleR, p, style = Stroke(thin * 1.3f))
            }
        }
        is Overlay.Stroke -> {
            val pts = overlay.points.map { t.toOffset(it) }
            val colour = Color(overlay.color)
            if (pts.isNotEmpty()) {
                val start = pts.first(); val end = pts.last()
                when (overlay.kind) {
                    MarkKind.BOX, MarkKind.COVER, MarkKind.BLUR, MarkKind.PIXELATE, MarkKind.ELLIPSE -> {
                        val rect = Rect(min(start.x, end.x), min(start.y, end.y), max(start.x, end.x), max(start.y, end.y))
                        if (overlay.kind == MarkKind.ELLIPSE) drawOval(colour, rect.topLeft, rect.size, style = Stroke(thin * 1.3f))
                        else drawRect(colour, rect.topLeft, rect.size, style = Stroke(thin * 1.3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                    }
                    MarkKind.ARROW -> drawLine(colour, start, end, max(2f, overlay.width * min(r.width, r.height)), StrokeCap.Round)
                    else -> {
                        val path = Path().apply { moveTo(start.x, start.y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                        val alpha = if (overlay.kind == MarkKind.ERASE) 0.5f else 1f
                        drawPath(path, colour.copy(alpha = alpha), style = Stroke(max(2f, overlay.width * min(r.width, r.height)), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                }
            }
            overlay.cloneSource?.let { src ->
                val at = t.toOffset(src)
                drawCircle(Color.White, handleR * 1.2f, at, style = Stroke(thin * 1.4f))
                drawLine(Color.White, Offset(at.x - handleR * 1.7f, at.y), Offset(at.x + handleR * 1.7f, at.y), thin)
                drawLine(Color.White, Offset(at.x, at.y - handleR * 1.7f), Offset(at.x, at.y + handleR * 1.7f), thin)
            }
        }
        is Overlay.Texts -> {
            for (m in overlay.marks) {
                val at = t.toOffset(m.points.first())
                val half = max(handleR * 2, m.width * min(r.width, r.height) * 2.5f)
                drawRect(StudioColors.Band.copy(alpha = 0.7f), Offset(at.x - half * 2, at.y - half), Size(half * 4, half * 2), style = Stroke(thin, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") scope
}

/** Which crop handle is under [at], if any: 0..3 corners TL TR BL BR, 4..7 edges T B L R, 8 inside. */
fun cropHandleAt(at: Offset, crop: Crop, t: CanvasTransform, reachPx: Float): Int? {
    val r = t.rect
    if (r.width <= 0f) return null
    val cr = Rect(r.left + crop.left * r.width, r.top + crop.top * r.height, r.left + crop.right * r.width, r.top + crop.bottom * r.height)
    val corners = listOf(Offset(cr.left, cr.top), Offset(cr.right, cr.top), Offset(cr.left, cr.bottom), Offset(cr.right, cr.bottom))
    corners.forEachIndexed { i, c -> if ((at - c).getDistance() <= reachPx) return i }
    if (abs(at.y - cr.top) <= reachPx && at.x in cr.left..cr.right) return 4
    if (abs(at.y - cr.bottom) <= reachPx && at.x in cr.left..cr.right) return 5
    if (abs(at.x - cr.left) <= reachPx && at.y in cr.top..cr.bottom) return 6
    if (abs(at.x - cr.right) <= reachPx && at.y in cr.top..cr.bottom) return 7
    if (cr.contains(at)) return 8
    return null
}

/** The crop after dragging [handle] by [delta] (fractions), before constraints. */
fun dragCrop(c: Crop, handle: Int, delta: Offset): Crop = when (handle) {
    0 -> c.copy(left = c.left + delta.x, top = c.top + delta.y)
    1 -> c.copy(right = c.right + delta.x, top = c.top + delta.y)
    2 -> c.copy(left = c.left + delta.x, bottom = c.bottom + delta.y)
    3 -> c.copy(right = c.right + delta.x, bottom = c.bottom + delta.y)
    4 -> c.copy(top = c.top + delta.y)
    5 -> c.copy(bottom = c.bottom + delta.y)
    6 -> c.copy(left = c.left + delta.x)
    7 -> c.copy(right = c.right + delta.x)
    else -> {
        val dx = delta.x.coerceIn(-c.left, 1f - c.right); val dy = delta.y.coerceIn(-c.top, 1f - c.bottom)
        Crop(c.left + dx, c.top + dy, c.right + dx, c.bottom + dy)
    }
}

/** Which quad corner is under [at], if any. */
fun quadCornerAt(at: Offset, quad: Quad, t: CanvasTransform, reachPx: Float): Int? {
    quad.points.forEachIndexed { i, p -> if ((at - t.toOffset(p)).getDistance() <= reachPx) return i }
    return null
}

fun moveCorner(q: Quad, i: Int, to: Pt): Quad = when (i) {
    0 -> q.copy(tl = to.clamped()); 1 -> q.copy(tr = to.clamped()); 2 -> q.copy(br = to.clamped()); else -> q.copy(bl = to.clamped())
}
