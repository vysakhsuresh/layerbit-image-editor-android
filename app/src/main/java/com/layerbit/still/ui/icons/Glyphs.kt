package com.layerbit.still.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.layerbit.still.ui.components.LocalLivelyClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Still's icons, drawn by hand so each can act out what its tool does.
 *
 * The crop brackets close in, the horizon levels, the sliders settle, the curve bends, the
 * blur spreads, the watermark fades in, the file shrinks to its KB. Each drawing lives on a
 * unit square and is told [x], 0 to 1 over one play of its action; it plays a few times a lap,
 * phase-shifted by the icon's seed so a board of tools takes turns rather than moving in step.
 * With motion off, every drawing shows [REST], a frame chosen so it still says what the tool does.
 */
enum class Glyph {
    EDIT, CROP, PERSPECTIVE, LIGHT, COLOUR, CURVES, LOOKS, DETAIL, MARKUP, RETOUCH, FRAME, WATERMARK,
    RESIZE, RESIZE_KB, COMPRESS, CONVERT, STRIP, DETAILS, ENHANCE, RECIPE, COLLAGE, LAYERS, EXPORT,
    GEAR, STAR, SEARCH, INFO, SHARE, SEND, MAIL, HEART, CHECK, PLUS, LOCK, UNDO, REDO, COMPARE,
    CUTOUT, SIGNATURE, STICKER, SCAN, REDEYE, NEUTRAL, PASSPORT
}

private const val PLAYS = 4f
private const val REST = 0.72f

@Composable
fun AnimatedGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier, seed: Int = 0) {
    val clock = LocalLivelyClock.current
    Canvas(modifier) {
        // Read inside the draw lambda only: the clock ticking redraws the picture, never the card.
        val x = if (clock == null) REST else frac((clock.value + seed * 0.0613f) * PLAYS)
        val lap = if (clock == null) REST else frac(clock.value + seed * 0.0613f)
        drawGlyph(glyph, color, x, lap)
    }
}

fun DrawScope.drawGlyph(glyph: Glyph, c: Color, x: Float, lap: Float = x) {
    when (glyph) {
        Glyph.EDIT -> edit(c, x)
        Glyph.CROP -> crop(c, x)
        Glyph.PERSPECTIVE -> perspective(c, x)
        Glyph.LIGHT -> light(c, x)
        Glyph.COLOUR -> colour(c, x, lap)
        Glyph.CURVES -> curves(c, x)
        Glyph.LOOKS -> looks(c, x)
        Glyph.DETAIL -> detail(c, x)
        Glyph.MARKUP -> markup(c, x)
        Glyph.RETOUCH -> retouch(c, x)
        Glyph.FRAME -> frame(c, x)
        Glyph.WATERMARK -> watermark(c, x)
        Glyph.RESIZE -> resize(c, x)
        Glyph.RESIZE_KB -> resizeKb(c, x)
        Glyph.COMPRESS -> compress(c, x)
        Glyph.CONVERT -> convert(c, x)
        Glyph.STRIP -> strip(c, x)
        Glyph.DETAILS -> details(c, x, lap)
        Glyph.ENHANCE -> enhance(c, x)
        Glyph.RECIPE -> recipe(c, x)
        Glyph.COLLAGE -> collage(c, x)
        Glyph.LAYERS -> layers(c, x)
        Glyph.EXPORT -> export(c, x)
        Glyph.GEAR -> gear(c, lap)
        Glyph.STAR -> star(c, x, lap)
        Glyph.SEARCH -> search(c, x)
        Glyph.INFO -> info(c, x)
        Glyph.SHARE -> share(c, x)
        Glyph.SEND -> send(c, x)
        Glyph.MAIL -> mail(c, x)
        Glyph.HEART -> heart(c, x)
        Glyph.CHECK -> check(c, x)
        Glyph.PLUS -> plus(c, x)
        Glyph.LOCK -> lock(c, x)
        Glyph.UNDO -> undo(c, x, false)
        Glyph.REDO -> undo(c, x, true)
        Glyph.COMPARE -> compare(c, x)
        Glyph.CUTOUT -> cutout(c, x, lap)
        Glyph.SIGNATURE -> signature(c, x)
        Glyph.STICKER -> sticker(c, x)
        Glyph.SCAN -> scan(c, x)
        Glyph.REDEYE -> redEye(c, x)
        Glyph.NEUTRAL -> neutral(c, x)
        Glyph.PASSPORT -> passport(c, x)
    }
}

// ---------------------------------------------------------------- timing and drawing helpers

private const val TAU = (2 * PI).toFloat()
private fun frac(v: Float) = v - floor(v)
private fun seg(x: Float, a: Float, b: Float): Float { val c = ((x - a) / (b - a)).coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
private fun hold(x: Float, a: Float, b: Float, c: Float, d: Float) = seg(x, a, b) * (1f - seg(x, c, d))
private fun bump(v: Float) = sin(v.coerceIn(0f, 1f) * PI.toFloat())
private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

private fun DrawScope.s() = size.minDimension
private fun DrawScope.p(x: Float, y: Float) = Offset(x * s(), y * s())
private fun DrawScope.sz(w: Float, h: Float) = Size(w * s(), h * s())
private fun DrawScope.line() = s() * 0.075f
private fun DrawScope.thin() = s() * 0.055f
private fun DrawScope.stroke(w: Float = line()) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** A photograph: frame, a hill and a sun. The picture every tool here works on. */
private fun DrawScope.photo(c: Color, l: Float, t: Float, w: Float, h: Float, alpha: Float = 1f, sun: Float = 1f) {
    if (alpha <= 0f) return
    drawRoundRect(c.copy(alpha = 0.16f * alpha), p(l, t), sz(w, h), CornerRadius(s() * 0.04f))
    drawRoundRect(c.copy(alpha = alpha), p(l, t), sz(w, h), CornerRadius(s() * 0.04f), style = stroke(thin()))
    clipRect(l * s(), t * s(), (l + w) * s(), (t + h) * s()) {
        val hill = Path().apply {
            moveTo(l * s(), (t + h) * s())
            lineTo((l + w * 0.38f) * s(), (t + h * 0.5f) * s())
            lineTo((l + w * 0.62f) * s(), (t + h * 0.74f) * s())
            lineTo((l + w * 0.8f) * s(), (t + h * 0.6f) * s())
            lineTo((l + w) * s(), (t + h) * s())
            close()
        }
        drawPath(hill, c.copy(alpha = 0.85f * alpha))
        drawCircle(c.copy(alpha = alpha * sun), w * 0.1f * s(), p(l + w * 0.72f, t + h * 0.3f))
    }
}

private fun DrawScope.arrow(c: Color, from: Offset, to: Offset, alpha: Float = 1f, w: Float = thin()) {
    if (alpha <= 0f) return
    drawLine(c.copy(alpha = alpha), from, to, w, StrokeCap.Round)
    val a = kotlin.math.atan2(to.y - from.y, to.x - from.x)
    val h = s() * 0.14f
    for (d in floatArrayOf(-0.55f, 0.55f)) {
        drawLine(c.copy(alpha = alpha), to, Offset(to.x - h * cos(a + d), to.y - h * sin(a + d)), w, StrokeCap.Round)
    }
}

private fun DrawScope.sparkle(c: Color, at: Offset, r: Float) {
    if (r <= 0f) return
    val path = Path().apply {
        moveTo(at.x, at.y - r); quadraticBezierTo(at.x, at.y, at.x + r, at.y); quadraticBezierTo(at.x, at.y, at.x, at.y + r)
        quadraticBezierTo(at.x, at.y, at.x - r, at.y); quadraticBezierTo(at.x, at.y, at.x, at.y - r); close()
    }
    drawPath(path, c)
}

private fun DrawScope.glow(c: Color, at: Offset, r: Float, alpha: Float) {
    if (alpha <= 0f) return
    drawCircle(c.copy(alpha = alpha * 0.35f), r, at)
}

private fun DrawScope.gearShape(c: Color, o: Offset, r: Float) {
    val path = Path()
    val teeth = 8
    for (i in 0 until teeth * 2) {
        val rr = if (i % 2 == 0) r else r * 0.78f
        val a = (i * TAU / (teeth * 2))
        val pt = Offset(o.x + cos(a) * rr, o.y + sin(a) * rr)
        if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
    }
    path.close()
    drawPath(path, c)
    drawCircle(Color.White.copy(alpha = 0.9f), r * 0.3f, o)
}

/** The first [f] of a path, for things that draw themselves. */
private fun partial(path: Path, f: Float): Path {
    if (f >= 1f) return path
    val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
    val out = Path()
    measure.getSegment(0f, measure.length * f.coerceIn(0f, 1f), out, true)
    return out
}

// ---------------------------------------------------------------- tools

private fun DrawScope.edit(c: Color, x: Float) {
    photo(c, 0.1f, 0.14f, 0.7f, 0.56f)
    // A pen writes a short stroke across the corner and lifts.
    val f = seg(x, 0.1f, 0.5f)
    val lift = bump(seg(x, 0.5f, 0.8f)) * 0.06f
    val from = p(0.56f, 0.9f); val to = p(0.56f + 0.3f * f, 0.9f - 0.3f * f)
    drawLine(c, from, to, line(), StrokeCap.Round)
    translate(0f, -lift * s()) { drawLine(c, to, Offset(to.x + s() * 0.1f, to.y - s() * 0.1f), line() * 1.3f, StrokeCap.Round) }
}

private fun DrawScope.crop(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.45f, 0.75f, 0.98f)
    val inset = lerp(0.08f, 0.24f, f)
    photo(c, 0.12f, 0.12f, 0.76f, 0.76f, alpha = 1f - 0.5f * f)
    drawRect(c.copy(alpha = 0.2f), p(inset, inset), sz(1 - 2 * inset, 1 - 2 * inset))
    val k = 0.16f
    for ((cx, cy) in listOf(inset to inset, 1 - inset to inset, inset to 1 - inset, 1 - inset to 1 - inset)) {
        val dx = if (cx < 0.5f) k else -k; val dy = if (cy < 0.5f) k else -k
        drawLine(c, p(cx, cy), p(cx + dx, cy), line() * 1.2f, StrokeCap.Round)
        drawLine(c, p(cx, cy), p(cx, cy + dy), line() * 1.2f, StrokeCap.Round)
    }
}

private fun DrawScope.perspective(c: Color, x: Float) {
    // A leaning page straightens up.
    val f = hold(x, 0.1f, 0.5f, 0.75f, 0.98f)
    val lean = lerp(0.14f, 0f, f)
    val path = Path().apply {
        moveTo((0.2f + lean) * s(), 0.14f * s()); lineTo((0.8f - lean) * s(), 0.14f * s())
        lineTo(0.86f * s(), 0.86f * s()); lineTo(0.14f * s(), 0.86f * s()); close()
    }
    drawPath(path, c.copy(alpha = 0.16f))
    drawPath(path, c, style = stroke(thin()))
    for (i in 0 until 3) {
        val t = 0.36f + i * 0.16f
        val l = lerp(0.2f + lean, 0.14f, (t - 0.14f) / 0.72f) + 0.1f
        val r = lerp(0.8f - lean, 0.86f, (t - 0.14f) / 0.72f) - 0.1f
        drawLine(c.copy(alpha = 0.5f), p(l, t), p(r, t), thin(), StrokeCap.Round)
    }
    for (corner in listOf(p(0.2f + lean, 0.14f), p(0.8f - lean, 0.14f))) drawCircle(c, s() * 0.05f, corner)
}

private fun DrawScope.light(c: Color, x: Float) {
    // Three sliders; the knobs settle one after another.
    for (i in 0 until 3) {
        val y = 0.26f + i * 0.24f
        drawLine(c.copy(alpha = 0.3f), p(0.12f, y), p(0.88f, y), thin(), StrokeCap.Round)
        val rest = floatArrayOf(0.62f, 0.38f, 0.7f)[i]
        val f = seg(x, 0.1f + 0.15f * i, 0.5f + 0.15f * i)
        val kx = lerp(0.5f, rest, f)
        drawLine(c, p(0.12f, y), p(kx, y), thin(), StrokeCap.Round)
        drawCircle(c, s() * 0.065f, p(kx, y))
    }
}

private fun DrawScope.colour(c: Color, x: Float, lap: Float) {
    // A colour wheel turns and a drop lands on it.
    val o = p(0.5f, 0.52f)
    rotate(lap * 360f, o) {
        for (i in 0 until 6) {
            val a = i * TAU / 6
            drawArc(c.copy(alpha = 0.25f + 0.1f * i), i * 60f, 60f, true, Offset(o.x - s() * 0.34f, o.y - s() * 0.34f), Size(s() * 0.68f, s() * 0.68f))
            drawLine(c.copy(alpha = 0.6f), o, Offset(o.x + cos(a) * s() * 0.34f, o.y + sin(a) * s() * 0.34f), thin() * 0.6f)
        }
    }
    drawCircle(c, s() * 0.34f, o, style = stroke(thin()))
    val fall = seg(x, 0.1f, 0.4f)
    val drop = Offset(o.x, lerp(0.05f, 0.5f, fall) * s())
    drawCircle(c, s() * 0.07f, drop)
    drawCircle(c.copy(alpha = 0.5f * bump(seg(x, 0.4f, 0.7f))), s() * (0.1f + 0.1f * seg(x, 0.4f, 0.7f)), o, style = stroke(thin()))
}

private fun DrawScope.curves(c: Color, x: Float) {
    drawRect(c.copy(alpha = 0.12f), p(0.12f, 0.12f), sz(0.76f, 0.76f))
    drawLine(c.copy(alpha = 0.3f), p(0.12f, 0.88f), p(0.88f, 0.12f), thin() * 0.7f)
    val bend = 0.22f * bump(seg(x, 0.05f, 0.95f))
    val path = Path().apply {
        moveTo(0.12f * s(), 0.88f * s())
        cubicTo((0.4f - bend) * s(), (0.6f + bend) * s(), (0.6f + bend) * s(), (0.4f - bend) * s(), 0.88f * s(), 0.12f * s())
    }
    drawPath(path, c, style = stroke())
    drawCircle(c, s() * 0.06f, Offset((0.38f - bend * 0.5f) * s(), (0.62f + bend * 0.5f) * s()))
    drawCircle(c, s() * 0.06f, Offset((0.62f + bend * 0.5f) * s(), (0.38f - bend * 0.5f) * s()))
}

private fun DrawScope.looks(c: Color, x: Float) {
    // Three stacked looks fan out like swatches.
    val f = hold(x, 0.1f, 0.45f, 0.7f, 0.95f)
    for (i in 2 downTo 0) {
        val ang = (i - 1) * 22f * f
        rotate(ang, p(0.5f, 0.86f)) {
            photo(c, 0.26f, 0.18f, 0.48f, 0.6f, alpha = 0.4f + 0.3f * i, sun = if (i == 2) 1f else 0f)
        }
    }
}

private fun DrawScope.detail(c: Color, x: Float) {
    // A soft circle sharpens: its edge goes from a fade to a line.
    val f = hold(x, 0.1f, 0.45f, 0.7f, 0.95f)
    val o = p(0.5f, 0.5f)
    for (i in 4 downTo 1) {
        val r = s() * (0.22f + i * 0.045f * (1f - f))
        drawCircle(c.copy(alpha = (0.12f + 0.18f * f) * (if (i == 1) 3f else 1f).coerceAtMost(1f)), r, o)
    }
    drawCircle(c.copy(alpha = f), s() * 0.22f, o, style = stroke(thin()))
    sparkle(c, p(0.82f, 0.2f), s() * 0.1f * bump(seg(x, 0.4f, 0.75f)))
}

private fun DrawScope.markup(c: Color, x: Float) {
    photo(c, 0.1f, 0.1f, 0.8f, 0.8f, sun = 0f)
    val path = Path().apply { moveTo(0.22f * s(), 0.64f * s()); cubicTo(0.32f * s(), 0.3f * s(), 0.5f * s(), 0.8f * s(), 0.74f * s(), 0.36f * s()) }
    drawPath(partial(path, seg(x, 0.05f, 0.6f)), c, style = stroke(line() * 1.2f))
    val a = p(0.62f, 0.7f); val b = p(0.8f, 0.56f)
    arrow(c, a, b, alpha = seg(x, 0.55f, 0.85f))
}

private fun DrawScope.retouch(c: Color, x: Float) {
    photo(c, 0.1f, 0.14f, 0.8f, 0.72f)
    // A blemish fades out under a brush circle.
    val gone = seg(x, 0.3f, 0.7f)
    drawCircle(c.copy(alpha = 0.9f * (1f - gone)), s() * 0.05f, p(0.34f, 0.66f))
    val bx = lerp(0.7f, 0.34f, seg(x, 0.05f, 0.35f))
    drawCircle(c.copy(alpha = 0.9f), s() * 0.12f, p(bx, 0.66f), style = Stroke(thin(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(s() * 0.05f, s() * 0.04f))))
    sparkle(c, p(0.34f, 0.66f), s() * 0.08f * bump(seg(x, 0.6f, 0.9f)))
}

private fun DrawScope.frame(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.45f, 0.75f, 0.98f)
    val pad = lerp(0f, 0.1f, f)
    drawRoundRect(c.copy(alpha = 0.9f), p(0.1f, 0.1f), sz(0.8f, 0.8f), CornerRadius(s() * 0.06f), style = stroke(thin() * (1f + f)))
    photo(c, 0.18f + pad, 0.18f + pad, 0.64f - 2 * pad, 0.64f - 2 * pad)
}

private fun DrawScope.watermark(c: Color, x: Float) {
    photo(c, 0.1f, 0.14f, 0.8f, 0.72f, sun = 0.4f)
    val f = hold(x, 0.1f, 0.4f, 0.7f, 0.95f)
    rotate(-28f, p(0.5f, 0.5f)) {
        for (i in -1..1) {
            val y = 0.5f + i * 0.26f
            drawRoundRect(c.copy(alpha = 0.6f * f), p(0.18f, y - 0.06f), sz(0.64f, 0.12f), CornerRadius(s() * 0.06f), style = stroke(thin()))
        }
    }
}

private fun DrawScope.resize(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.45f, 0.7f, 0.95f)
    val w = lerp(0.74f, 0.46f, f); val h = w * 0.78f
    photo(c, 0.12f, 0.12f, w, h)
    val corner = p(0.12f + w, 0.12f + h)
    drawRect(c.copy(alpha = 0.3f), p(0.12f, 0.12f), sz(0.74f, 0.74f * 0.78f), style = Stroke(thin() * 0.7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s() * 0.04f, s() * 0.04f))))
    drawCircle(c, s() * 0.05f, corner)
    arrow(c, Offset(corner.x + s() * 0.14f, corner.y + s() * 0.14f), Offset(corner.x + s() * 0.03f, corner.y + s() * 0.03f), alpha = bump(seg(x, 0.05f, 0.5f)))
}

private fun DrawScope.resizeKb(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.45f, 0.7f, 0.95f)
    val w = lerp(0.72f, 0.46f, f); val h = w * 0.78f
    photo(c, 0.12f, 0.1f, w, h)
    label("KB", c, p(0.74f, 0.86f), s() * 0.16f, alpha = f)
    drawRoundRect(c.copy(alpha = 0.6f * f), p(0.52f, 0.72f), sz(0.42f, 0.2f), CornerRadius(s() * 0.05f), style = stroke(thin()))
}

private fun DrawScope.compress(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.4f, 0.7f, 0.95f)
    val h = lerp(0.56f, 0.34f, f)
    val top = 0.5f - h / 2
    photo(c, 0.2f, top, 0.6f, h)
    arrow(c, p(0.5f, 0.04f), p(0.5f, top - 0.04f))
    arrow(c, p(0.5f, 0.96f), p(0.5f, top + h + 0.04f))
}

private fun DrawScope.convert(c: Color, x: Float) {
    val f = seg(x, 0.1f, 0.6f)
    photo(c, 0.06f, 0.26f, 0.38f, 0.48f, alpha = 1f - 0.6f * f)
    drawRoundRect(c.copy(alpha = 0.3f + 0.7f * f), p(0.56f, 0.26f), sz(0.38f, 0.48f), CornerRadius(s() * 0.04f), style = stroke(thin()))
    label("WEBP", c, p(0.75f, 0.54f), s() * 0.1f, alpha = f)
    arrow(c, p(0.42f, 0.5f), p(0.58f, 0.5f), alpha = 1f)
    rotate(360f * seg(x, 0.6f, 0.9f), p(0.5f, 0.5f)) { }
}

private fun DrawScope.strip(c: Color, x: Float) {
    photo(c, 0.1f, 0.1f, 0.8f, 0.6f, sun = 0.3f)
    // A location pin lifts off the picture and fades.
    val up = seg(x, 0.15f, 0.6f)
    val fade = 1f - seg(x, 0.5f, 0.85f)
    val o = p(0.5f, lerp(0.5f, 0.1f, up))
    drawCircle(c.copy(alpha = fade), s() * 0.1f, o, style = stroke(thin()))
    drawCircle(c.copy(alpha = fade), s() * 0.035f, o)
    drawLine(c.copy(alpha = fade), Offset(o.x, o.y + s() * 0.1f), Offset(o.x, o.y + s() * 0.2f), thin(), StrokeCap.Round)
    drawLine(c, p(0.18f, 0.84f), p(0.82f, 0.84f), thin(), StrokeCap.Round)
    drawLine(c, p(0.18f, 0.94f), p(0.6f, 0.94f), thin(), StrokeCap.Round)
}

private fun DrawScope.details(c: Color, x: Float, lap: Float) {
    photo(c, 0.08f, 0.08f, 0.6f, 0.5f)
    for (i in 0 until 3) {
        val f = seg(x, 0.08f + 0.18f * i, 0.3f + 0.18f * i) * (1f - seg(x, 0.88f, 1f))
        val y = 0.7f + 0.11f * i
        drawCircle(c, s() * 0.025f, p(0.14f, y))
        drawLine(c.copy(alpha = 0.3f), p(0.2f, y), p(0.6f, y), thin(), StrokeCap.Round)
        if (f > 0f) drawLine(c, p(0.2f, y), p(lerp(0.2f, 0.6f, f), y), thin(), StrokeCap.Round)
    }
    val o = p(0.8f, 0.74f)
    rotate(lap * 360f, o) { gearShape(c, o, s() * 0.16f) }
}

private fun DrawScope.enhance(c: Color, x: Float) {
    val f = seg(x, 0.1f, 0.5f)
    photo(c, 0.1f, 0.14f, 0.8f, 0.72f, alpha = 0.45f + 0.55f * f)
    sparkle(c, p(0.26f, 0.3f), s() * 0.1f * bump(seg(x, 0.2f, 0.6f)))
    sparkle(c, p(0.78f, 0.22f), s() * 0.13f * bump(seg(x, 0.35f, 0.75f)))
    sparkle(c, p(0.7f, 0.74f), s() * 0.08f * bump(seg(x, 0.5f, 0.9f)))
}

private fun DrawScope.recipe(c: Color, x: Float) {
    drawRoundRect(c.copy(alpha = 0.16f), p(0.14f, 0.1f), sz(0.72f, 0.8f), CornerRadius(s() * 0.05f))
    drawRoundRect(c, p(0.14f, 0.1f), sz(0.72f, 0.8f), CornerRadius(s() * 0.05f), style = stroke(thin()))
    for (i in 0 until 3) {
        val y = 0.3f + i * 0.2f
        val on = seg(x, 0.1f + 0.2f * i, 0.3f + 0.2f * i) * (1f - seg(x, 0.9f, 1f))
        drawCircle(c.copy(alpha = 0.3f + 0.7f * on), s() * 0.05f, p(0.28f, y))
        drawLine(c.copy(alpha = 0.3f + 0.5f * on), p(0.4f, y), p(0.72f, y), thin(), StrokeCap.Round)
    }
}

private fun DrawScope.collage(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.5f, 0.75f, 0.98f)
    val g = lerp(0.14f, 0.04f, f)
    val cells = listOf(floatArrayOf(0.1f, 0.1f, 0.44f, 0.44f), floatArrayOf(0.56f, 0.1f, 0.34f, 0.44f), floatArrayOf(0.1f, 0.56f, 0.34f, 0.34f), floatArrayOf(0.46f, 0.56f, 0.44f, 0.34f))
    for (cell in cells) {
        val cx = cell[0] + cell[2] / 2; val cy = cell[1] + cell[3] / 2
        val sx = (cx - 0.5f) * g * 2; val sy = (cy - 0.5f) * g * 2
        photo(c, cell[0] + sx, cell[1] + sy, cell[2] - g * 0.6f, cell[3] - g * 0.6f, sun = if (cell[2] > 0.4f) 1f else 0f)
    }
}

private fun DrawScope.layers(c: Color, x: Float) {
    val lift = 0.06f * bump(seg(x, 0.1f, 0.6f))
    for (i in 2 downTo 0) {
        val y = 0.2f + i * 0.22f - (if (i == 0) lift else 0f)
        val a = if (i == 0) 1f else 0.45f
        val path = Path().apply {
            moveTo(0.5f * s(), (y - 0.14f) * s()); lineTo(0.9f * s(), y * s()); lineTo(0.5f * s(), (y + 0.14f) * s()); lineTo(0.1f * s(), y * s()); close()
        }
        drawPath(path, c.copy(alpha = 0.15f * a))
        drawPath(path, c.copy(alpha = a), style = stroke(thin()))
    }
}

private fun DrawScope.export(c: Color, x: Float) {
    drawLine(c, p(0.14f, 0.6f), p(0.14f, 0.86f), thin(), StrokeCap.Round)
    drawLine(c, p(0.14f, 0.86f), p(0.86f, 0.86f), thin(), StrokeCap.Round)
    drawLine(c, p(0.86f, 0.86f), p(0.86f, 0.6f), thin(), StrokeCap.Round)
    val f = bump(seg(x, 0.05f, 0.6f))
    val y = 0.62f - 0.12f * f
    arrow(c, p(0.5f, y), p(0.5f, 0.14f), w = line())
    photo(c, 0.32f, 0.3f, 0.36f, 0.3f, alpha = 0.5f)
}

// ---------------------------------------------------------------- everyday

private fun DrawScope.gear(c: Color, lap: Float) {
    val o = p(0.5f, 0.5f)
    rotate(lap * 180f, o) { gearShape(c, o, s() * 0.44f) }
}

private fun DrawScope.star(c: Color, x: Float, lap: Float) {
    val o = p(0.5f, 0.52f)
    val pulse = bump(x)
    glow(c, o, s() * 0.5f, 0.3f * pulse)
    rotate(lap * 72f, o) {
        val r = s() * (0.4f + 0.03f * pulse)
        val path = Path()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.42f
            val a = (-90f + i * 36f) * PI.toFloat() / 180f
            if (i == 0) path.moveTo(o.x + cos(a) * rr, o.y + sin(a) * rr) else path.lineTo(o.x + cos(a) * rr, o.y + sin(a) * rr)
        }
        path.close()
        drawPath(path, c)
    }
    sparkle(c, p(0.88f, 0.12f), s() * 0.1f * bump(seg(x, 0f, 0.5f)))
}

private fun DrawScope.search(c: Color, x: Float) {
    val dx = 0.08f * sin(TAU * x)
    val o = p(0.42f + dx, 0.42f)
    val r = s() * 0.24f
    drawCircle(c.copy(alpha = 0.15f), r, o)
    drawCircle(c, r, o, style = stroke())
    drawLine(c, Offset(o.x + r * 0.72f, o.y + r * 0.72f), Offset(o.x + r * 1.6f, o.y + r * 1.6f), line() * 1.3f, StrokeCap.Round)
}

private fun DrawScope.info(c: Color, x: Float) {
    val o = p(0.5f, 0.5f)
    val ring = seg(x, 0f, 0.8f)
    drawCircle(c.copy(alpha = 0.5f * (1f - ring)), s() * (0.36f + 0.12f * ring), o, style = stroke(thin()))
    drawCircle(c, s() * 0.36f, o)
    drawCircle(Color.White, s() * 0.05f, p(0.5f, 0.32f - 0.03f * bump(seg(x, 0.1f, 0.4f))))
    drawLine(Color.White, p(0.5f, 0.45f), p(0.5f, 0.68f), line(), StrokeCap.Round)
}

private fun DrawScope.share(c: Color, x: Float) {
    val a = p(0.24f, 0.5f); val b = p(0.76f, 0.22f); val d = p(0.76f, 0.78f)
    drawLine(c, a, b, thin()); drawLine(c, a, d, thin())
    val f = seg(x, 0.05f, 0.6f)
    for (to in listOf(b, d)) glow(c, Offset(lerp(a.x, to.x, f), lerp(a.y, to.y, f)), s() * 0.12f, 0.9f * bump(f))
    for (n in listOf(a, b, d)) drawCircle(c, s() * 0.12f, n)
}

private fun DrawScope.send(c: Color, x: Float) {
    val f = seg(x, 0.1f, 0.5f); val back = seg(x, 0.6f, 0.95f)
    val dx = if (back > 0f) lerp(-0.6f, 0f, back) else 0.6f * f
    val dy = if (back > 0f) lerp(0.3f, 0f, back) else -0.3f * f
    translate(dx * s(), dy * s()) {
        val plane = Path().apply { moveTo(0.14f * s(), 0.5f * s()); lineTo(0.86f * s(), 0.2f * s()); lineTo(0.6f * s(), 0.82f * s()); lineTo(0.46f * s(), 0.56f * s()); close() }
        drawPath(plane, c)
        drawLine(Color.White.copy(alpha = 0.7f), p(0.46f, 0.56f), p(0.86f, 0.2f), thin())
    }
}

private fun DrawScope.mail(c: Color, x: Float) {
    val open = hold(x, 0.1f, 0.35f, 0.65f, 0.9f)
    val letterUp = 0.16f * open
    clipRect(bottom = 0.8f * s()) {
        drawRoundRect(Color.White.copy(alpha = 0.9f), p(0.24f, 0.32f - letterUp), sz(0.52f, 0.36f), CornerRadius(s() * 0.03f))
    }
    drawRoundRect(c, p(0.12f, 0.36f), sz(0.76f, 0.46f), CornerRadius(s() * 0.06f))
    val tip = lerp(0.62f, 0.12f, open)
    val flap = Path().apply { moveTo(0.12f * s(), 0.38f * s()); lineTo(0.5f * s(), tip * s()); lineTo(0.88f * s(), 0.38f * s()) }
    drawPath(flap, Color.White.copy(alpha = 0.85f), style = stroke(thin()))
}

private fun DrawScope.heart(c: Color, x: Float) {
    val beat = 1f + 0.16f * bump(seg(x, 0f, 0.12f)) + 0.1f * bump(seg(x, 0.16f, 0.28f))
    scale(beat, p(0.5f, 0.55f)) {
        val path = Path().apply {
            moveTo(0.5f * s(), 0.86f * s())
            cubicTo(-0.05f * s(), 0.52f * s(), 0.12f * s(), 0.04f * s(), 0.5f * s(), 0.3f * s())
            cubicTo(0.88f * s(), 0.04f * s(), 1.05f * s(), 0.52f * s(), 0.5f * s(), 0.86f * s())
            close()
        }
        drawPath(path, c)
    }
}

private fun DrawScope.check(c: Color, x: Float) {
    val tick = Path().apply { moveTo(0.18f * s(), 0.52f * s()); lineTo(0.42f * s(), 0.76f * s()); lineTo(0.84f * s(), 0.26f * s()) }
    drawPath(partial(tick, lerp(0.35f, 1f, seg(x, 0.05f, 0.4f))), c, style = stroke(line() * 1.4f))
}

private fun DrawScope.plus(c: Color, x: Float) {
    rotate(90f * seg(x, 0.1f, 0.4f), p(0.5f, 0.5f)) {
        drawLine(c, p(0.2f, 0.5f), p(0.8f, 0.5f), line() * 1.4f, StrokeCap.Round)
        drawLine(c, p(0.5f, 0.2f), p(0.5f, 0.8f), line() * 1.4f, StrokeCap.Round)
    }
}

private fun DrawScope.lock(c: Color, x: Float) {
    val close = seg(x, 0.1f, 0.3f) * (1f - seg(x, 0.85f, 1f))
    val lift = 0.12f * (1f - close)
    drawArc(c, 180f, 180f, false, p(0.3f, 0.12f - lift), sz(0.4f, 0.44f), style = stroke(line() * 1.2f))
    drawLine(c, p(0.3f, 0.34f - lift), p(0.3f, 0.48f), line() * 1.2f, StrokeCap.Round)
    drawLine(c, p(0.7f, 0.34f - lift), p(0.7f, 0.48f - 0.14f * (1f - close)), line() * 1.2f, StrokeCap.Round)
    drawRoundRect(c, p(0.2f, 0.46f), sz(0.6f, 0.44f), CornerRadius(s() * 0.08f))
    drawCircle(Color.White, s() * 0.06f, p(0.5f, 0.64f))
}

private fun DrawScope.undo(c: Color, x: Float, redo: Boolean) {
    val turn = -40f * bump(seg(x, 0.1f, 0.5f)) * (if (redo) -1f else 1f)
    scale(if (redo) -1f else 1f, 1f, p(0.5f, 0.5f)) {
        rotate(turn, p(0.5f, 0.5f)) {
            drawArc(c, -30f, 240f, false, p(0.2f, 0.2f), sz(0.6f, 0.6f), style = stroke())
            val tip = p(0.2f + 0.3f * (1 + cos(Math.toRadians(210.0)).toFloat()), 0.2f + 0.3f * (1 + sin(Math.toRadians(210.0)).toFloat()))
            drawLine(c, tip, Offset(tip.x + s() * 0.16f, tip.y), line(), StrokeCap.Round)
            drawLine(c, tip, Offset(tip.x, tip.y - s() * 0.16f), line(), StrokeCap.Round)
        }
    }
}

private fun DrawScope.compare(c: Color, x: Float) {
    photo(c, 0.1f, 0.14f, 0.8f, 0.72f)
    val split = lerp(0.3f, 0.7f, bump(seg(x, 0.05f, 0.95f)))
    clipRect(right = split * s()) { drawRect(c.copy(alpha = 0.35f), p(0.1f, 0.14f), sz(0.8f, 0.72f)) }
    drawLine(c, p(split, 0.08f), p(split, 0.92f), line(), StrokeCap.Round)
}

/** Short capital text centred on a point, for the few icons that say a word. */
private fun DrawScope.label(text: String, c: Color, at: Offset, size: Float, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = c.copy(alpha = alpha).toArgb()
        textSize = size
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y + size * 0.35f, paint)
}

// ---------------------------------------------------------------- the newer tools

/** The subject's outline marches round it and the background fades away behind. */
private fun DrawScope.cutout(c: Color, x: Float, lap: Float) {
    val f = hold(x, 0.12f, 0.45f, 0.75f, 0.98f)
    val l = 0.1f; val t = 0.14f; val w = 0.8f; val h = 0.64f
    // The frame and sky go first; the hill and sun stay.
    drawRoundRect(c.copy(alpha = 0.16f * (1f - f)), p(l, t), sz(w, h), CornerRadius(s() * 0.04f))
    drawRoundRect(c.copy(alpha = 1f - 0.85f * f), p(l, t), sz(w, h), CornerRadius(s() * 0.04f), style = stroke(thin()))
    val hill = Path().apply {
        moveTo(l * s(), (t + h) * s()); lineTo((l + w * 0.38f) * s(), (t + h * 0.5f) * s()); lineTo((l + w * 0.62f) * s(), (t + h * 0.74f) * s())
        lineTo((l + w * 0.8f) * s(), (t + h * 0.6f) * s()); lineTo((l + w) * s(), (t + h) * s()); close()
    }
    drawPath(hill, c.copy(alpha = 0.85f))
    drawCircle(c, w * 0.1f * s(), p(l + w * 0.72f, t + h * 0.3f))
    val dash = s() * 0.07f
    drawPath(hill, c.copy(alpha = 0.35f + 0.65f * f), style = Stroke(thin(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f), -lap * dash * 18f)))
    drawLine(c, p(0.14f, 0.9f), p(0.86f, 0.9f), thin(), StrokeCap.Round)
}

/** A pen signs its flourish, then the paper fades and only the ink is left. */
private fun DrawScope.signature(c: Color, x: Float) {
    val write = seg(x, 0.05f, 0.55f)
    val lift = seg(x, 0.6f, 0.9f)
    drawRoundRect(c.copy(alpha = 0.16f * (1f - 0.9f * lift)), p(0.08f, 0.2f), sz(0.84f, 0.6f), CornerRadius(s() * 0.04f))
    drawRoundRect(c.copy(alpha = 1f - 0.85f * lift), p(0.08f, 0.2f), sz(0.84f, 0.6f), CornerRadius(s() * 0.04f), style = stroke(thin()))
    val flourish = Path().apply {
        moveTo(0.16f * s(), 0.6f * s())
        cubicTo(0.26f * s(), 0.3f * s(), 0.34f * s(), 0.3f * s(), 0.34f * s(), 0.5f * s())
        cubicTo(0.34f * s(), 0.68f * s(), 0.44f * s(), 0.66f * s(), 0.5f * s(), 0.42f * s())
        cubicTo(0.54f * s(), 0.28f * s(), 0.6f * s(), 0.3f * s(), 0.6f * s(), 0.5f * s())
        cubicTo(0.6f * s(), 0.66f * s(), 0.7f * s(), 0.64f * s(), 0.78f * s(), 0.4f * s())
        cubicTo(0.82f * s(), 0.3f * s(), 0.86f * s(), 0.36f * s(), 0.88f * s(), 0.44f * s())
    }
    drawPath(partial(flourish, write), c, style = stroke(line()))
}

/** A sticker peels up at the corner and shows its white backing. */
private fun DrawScope.sticker(c: Color, x: Float) {
    val peel = bump(seg(x, 0.1f, 0.9f)) * 0.14f
    drawRoundRect(c.copy(alpha = 0.18f), p(0.14f, 0.14f), sz(0.72f, 0.72f), CornerRadius(s() * 0.1f))
    drawRoundRect(c, p(0.14f, 0.14f), sz(0.72f, 0.72f), CornerRadius(s() * 0.1f), style = stroke(thin()))
    // The face: two eyes and a smile that widens as it peels.
    drawCircle(c, s() * 0.035f, p(0.38f, 0.42f)); drawCircle(c, s() * 0.035f, p(0.62f, 0.42f))
    val smile = Path().apply { moveTo(0.36f * s(), 0.58f * s()); quadraticBezierTo(0.5f * s(), (0.64f + peel * 0.8f) * s(), 0.64f * s(), 0.58f * s()) }
    drawPath(smile, c, style = stroke(thin()))
    // The lifted corner, white underneath.
    val k = 0.2f + peel
    val corner = Path().apply { moveTo((0.86f - k) * s(), 0.86f * s()); lineTo(0.86f * s(), (0.86f - k) * s()); lineTo((0.86f - k * 0.1f) * s(), (0.86f - k * 0.1f) * s()); close() }
    drawPath(corner, Color.White.copy(alpha = 0.9f))
    drawPath(corner, c, style = stroke(thin()))
}

/** A photographed page squares up and a scan line whitens it as it passes. */
private fun DrawScope.scan(c: Color, x: Float) {
    val square = seg(x, 0.08f, 0.4f)
    val sweep = seg(x, 0.42f, 0.86f)
    val skew = 0.1f * (1f - square)
    val page = Path().apply {
        moveTo((0.22f + skew) * s(), 0.1f * s()); lineTo((0.78f - skew) * s(), 0.1f * s()); lineTo(0.84f * s(), 0.9f * s()); lineTo(0.16f * s(), 0.9f * s()); close()
    }
    drawPath(page, c.copy(alpha = 0.12f + 0.1f * sweep))
    drawPath(page, c, style = stroke(thin()))
    for (i in 0 until 4) {
        val y = 0.28f + i * 0.16f
        val lit = if (sweep > 0f && y < 0.12f + sweep * 0.8f) 1f else 0.45f
        val inset = 0.3f + skew * (1f - i / 3f) * 0.5f
        drawLine(c.copy(alpha = lit), p(inset, y), p(1f - inset + 0.04f * (i % 2), y), thin(), StrokeCap.Round)
    }
    if (sweep > 0f && sweep < 1f) drawLine(c, p(0.1f, 0.12f + sweep * 0.8f), p(0.9f, 0.12f + sweep * 0.8f), line(), StrokeCap.Round)
}

/** An eye whose flash-red pupil goes back to black. */
private fun DrawScope.redEye(c: Color, x: Float) {
    val fix = seg(x, 0.3f, 0.7f)
    val eye = Path().apply {
        moveTo(0.06f * s(), 0.5f * s()); cubicTo(0.26f * s(), 0.16f * s(), 0.74f * s(), 0.16f * s(), 0.94f * s(), 0.5f * s())
        cubicTo(0.74f * s(), 0.84f * s(), 0.26f * s(), 0.84f * s(), 0.06f * s(), 0.5f * s()); close()
    }
    drawPath(eye, c.copy(alpha = 0.12f))
    drawPath(eye, c, style = stroke(thin()))
    drawCircle(c, s() * 0.2f, p(0.5f, 0.5f), style = stroke(thin()))
    val red = Color(0xFFE63946)
    drawCircle(red.copy(alpha = 1f - fix), s() * 0.1f, p(0.5f, 0.5f))
    drawCircle(c.copy(alpha = fix), s() * 0.1f, p(0.5f, 0.5f))
    drawCircle(Color.White.copy(alpha = 0.9f), s() * 0.035f, p(0.56f, 0.44f))
}

/** An eyedropper taps the picture and the cast drains out of the frame. */
private fun DrawScope.neutral(c: Color, x: Float) {
    val tap = bump(seg(x, 0.2f, 0.5f))
    val drain = seg(x, 0.45f, 0.85f)
    photo(c, 0.08f, 0.2f, 0.56f, 0.46f, alpha = 0.6f + 0.4f * drain, sun = 0.5f)
    val dy = -0.06f * tap
    val dropper = Path().apply {
        moveTo(0.58f * s(), (0.7f + dy) * s()); lineTo(0.76f * s(), (0.52f + dy) * s()); lineTo(0.86f * s(), (0.62f + dy) * s()); lineTo(0.68f * s(), (0.8f + dy) * s()); close()
    }
    drawPath(dropper, c)
    drawLine(c, p(0.86f, 0.62f + dy), p(0.92f, 0.56f + dy), line(), StrokeCap.Round)
    drawLine(c, p(0.58f, 0.7f + dy), p(0.5f, 0.78f + dy), thin(), StrokeCap.Round)
    drawCircle(c.copy(alpha = 0.5f * tap), s() * 0.12f * (0.4f + tap), p(0.44f, 0.52f), style = stroke(thin()))
}

/** A portrait frame snaps to the passport size and its dimensions are ticked off. */
private fun DrawScope.passport(c: Color, x: Float) {
    val snap = seg(x, 0.1f, 0.4f)
    val tick = seg(x, 0.5f, 0.75f)
    val inset = lerp(0.04f, 0.16f, snap)
    drawRect(c.copy(alpha = 0.12f), p(inset, 0.08f), sz(1f - 2 * inset, 0.84f))
    drawRect(c, p(inset, 0.08f), sz(1f - 2 * inset, 0.84f), style = stroke(thin()))
    drawCircle(c, s() * 0.14f, p(0.5f, 0.4f), style = stroke(line()))
    val shoulders = Path().apply { moveTo((inset + 0.08f) * s(), 0.86f * s()); quadraticBezierTo(0.5f * s(), 0.56f * s(), (1f - inset - 0.08f) * s(), 0.86f * s()) }
    drawPath(shoulders, c, style = stroke(line()))
    if (tick > 0f) {
        val check = Path().apply { moveTo(0.74f * s(), 0.16f * s()); lineTo(0.8f * s(), 0.22f * s()); lineTo(0.9f * s(), 0.1f * s()) }
        drawPath(partial(check, tick), c, style = stroke(line()))
    }
}
