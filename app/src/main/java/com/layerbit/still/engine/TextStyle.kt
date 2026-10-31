package com.layerbit.still.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The typefaces a text layer may use: all bundled, none downloaded. */
enum class TextFont {
    /** The phone's own sans-serif: what every text layer used before styles. */
    SYSTEM,
    /** Space Grotesk, the app's own face. */
    GROTESK,
    /** DM Serif Display. */
    SERIF,
    /** Dancing Script, a handwritten script. */
    SCRIPT,
    /** Fira Code, a typewriter-like monospace. */
    MONO
}

enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * How a text layer looks. Every length is in ems of the layer's text size, and the text size is
 * itself a fraction of the canvas short edge ([TextLayout.textSizePx]), so a style draws the
 * same on a 900-pixel preview and a 6000-pixel export.
 *
 * The defaults are exactly the look text had before styles existed, so a recipe without a style
 * reads as it always did.
 */
data class TextStyle(
    val font: TextFont = TextFont.SYSTEM,
    /** 100..900, as in CSS. Faces with fewer weights take the nearest they have. */
    val weight: Int = 700,
    val italic: Boolean = false,
    /** Shown in capitals; the layer keeps the text as typed. */
    val caps: Boolean = false,
    val align: TextAlign = TextAlign.LEFT,
    /** Extra space between letters, in ems. */
    val letterSpacing: Float = 0f,
    /** Times the face's own line spacing. */
    val lineHeight: Float = 1f,
    /** Outline drawn outside each letter, in ems; 0 is none. */
    val outline: Float = 0f,
    val outlineColor: Int = BLACK,
    /** Shadow softness and offset in ems; a transparent colour is no shadow. */
    val shadowBlur: Float = 0.08f,
    val shadowX: Float = 0f,
    val shadowY: Float = 0.04f,
    val shadowColor: Int = 0x5A000000,
    /** A box behind the text: its colour, how opaque (0 is none), padding and corner radius in ems. */
    val boxColor: Int = BLACK,
    val boxOpacity: Float = 0f,
    val boxPadding: Float = 0.3f,
    val boxRadius: Float = 0.2f,
    /** One box per line, like a highlighter, instead of one around the whole block. */
    val boxPerLine: Boolean = false,
    /**
     * Bends the text along a circle: the fraction of a full turn the line sweeps, -1..1. Positive
     * arches up, negative sags like a smile, 0 is straight. The radius follows from the line's
     * width, so it too is a fraction of the short edge. Curved text is one line.
     */
    val arc: Float = 0f
) {
    val valid: Boolean
        get() = weight in 100..900 && letterSpacing in -0.2f..1f && lineHeight in 0.5f..3f && outline in 0f..0.5f &&
            shadowBlur in 0f..1f && shadowX in -1f..1f && shadowY in -1f..1f && boxOpacity in 0f..1f &&
            boxPadding in 0f..2f && boxRadius in 0f..5f && arc in -1f..1f

    val hasBox: Boolean get() = boxOpacity > 0f && (boxColor ushr 24) > 0
    val hasShadow: Boolean get() = (shadowColor ushr 24) > 0
    val curved: Boolean get() = abs(arc) >= MIN_ARC

    /** Any value a file may hold, brought into range. */
    fun clamped(): TextStyle = copy(
        weight = weight.coerceIn(100, 900), letterSpacing = letterSpacing.finiteOr(0f).coerceIn(-0.2f, 1f),
        lineHeight = lineHeight.finiteOr(1f).coerceIn(0.5f, 3f), outline = outline.finiteOr(0f).coerceIn(0f, 0.5f),
        shadowBlur = shadowBlur.finiteOr(0f).coerceIn(0f, 1f), shadowX = shadowX.finiteOr(0f).coerceIn(-1f, 1f),
        shadowY = shadowY.finiteOr(0f).coerceIn(-1f, 1f), boxOpacity = boxOpacity.finiteOr(0f).coerceIn(0f, 1f),
        boxPadding = boxPadding.finiteOr(0.3f).coerceIn(0f, 2f), boxRadius = boxRadius.finiteOr(0.2f).coerceIn(0f, 5f),
        arc = arc.finiteOr(0f).coerceIn(-1f, 1f)
    )

    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        val DEFAULT = TextStyle()
        /** Below this a curve would be invisible; the line stays straight. */
        const val MIN_ARC = 0.005f
        private fun Float.finiteOr(d: Float) = if (isFinite()) this else d
    }
}

/**
 * Where every line, box and letter of a text layer goes, in ems around the layer's anchor. The
 * renderer only measures strings (at one fixed size, so a measurement is the same at every
 * canvas size) and multiplies these numbers by the text size.
 */
object TextLayout {
    /** Text size per unit of [Mark.width], times the short edge. */
    const val SIZE_PER_WIDTH = 3.2f
    const val MIN_TEXT_PX = 8f
    /** Text wraps at this fraction of the canvas width. */
    const val WRAP = 0.8f

    fun textSizePx(width: Float, canvas: Geometry2D.Size): Float = max(MIN_TEXT_PX, Geometry2D.px(width, canvas) * SIZE_PER_WIDTH)

    /** The wrap width in ems of a text size of [textPx] pixels. */
    fun wrapEms(canvas: Geometry2D.Size, textPx: Float): Float = WRAP * canvas.width / textPx

    /** What is drawn: capitals if the style asks, one line if it curves. */
    fun shown(text: String, style: TextStyle): String {
        val t = if (style.caps) text.uppercase() else text
        return if (style.curved) t.replace('\n', ' ') else t
    }

    /**
     * Greedy word wrap at [maxEms]. Typed line breaks are kept, spaces at a break vanish, and a
     * word longer than a line is broken between letters. [measure] gives a string's width in ems.
     */
    fun lines(text: String, maxEms: Float, measure: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) { out += ""; continue }
            var current = ""
            for (token in words(para)) {
                val candidate = current + token
                if (current.isEmpty() || measure(candidate.trimEnd()) <= maxEms) {
                    current = candidate
                } else {
                    out += current.trimEnd()
                    current = token
                }
                // A single word wider than the line is cut between letters.
                while (measure(current.trimEnd()) > maxEms && clusters(current.trimEnd()).size > 1) {
                    val parts = clusters(current)
                    var fit = 1
                    while (fit < parts.size && measure(parts.subList(0, fit + 1).joinToString("").trimEnd()) <= maxEms) fit++
                    out += parts.subList(0, fit).joinToString("").trimEnd()
                    current = parts.subList(fit, parts.size).joinToString("").trimStart()
                }
            }
            out += current.trimEnd()
        }
        return out
    }

    /** A paragraph cut after each run of spaces, so a word keeps its following space. */
    private fun words(para: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < para.length) {
            if (para[i] == ' ') {
                while (i < para.length && para[i] == ' ') i++
                out += para.substring(start, i); start = i
            } else i++
        }
        if (start < para.length) out += para.substring(start)
        return out
    }

    /**
     * The text cut into what a reader sees as single characters: a letter with its accents, an
     * emoji with its modifiers and joins. Curved text places these one by one.
     */
    fun clusters(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var joinNext = false
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val piece = text.substring(i, i + n)
            val type = Character.getType(cp)
            val attaches = out.isNotEmpty() && (joinNext || cp == ZWJ || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF ||
                type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt())
            if (attaches) out[out.lastIndex] = out.last() + piece else out += piece
            joinNext = cp == ZWJ
            i += n
        }
        return out
    }

    private const val ZWJ = 0x200D

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** One line: its text, where it starts and its baseline, in ems from the anchor. */
    data class Line(val text: String, val x: Float, val baseline: Float, val width: Float)

    /** A letter on a curve: its centre on the baseline and its turn in degrees. */
    data class Glyph(val text: String, val x: Float, val y: Float, val width: Float, val angle: Float)

    /** Everything to draw, in ems around the anchor; [bounds] holds the text and its boxes. */
    data class Block(val lines: List<Line>, val glyphs: List<Glyph>, val boxes: List<Rect>, val bounds: Rect, val boxRadius: Float)

    /**
     * Lays out [text] for [style]. [ascent] and [descent] are the face's, in ems (both positive).
     * The block is centred on the anchor, as text always was.
     */
    fun layout(text: String, style: TextStyle, maxEms: Float, ascent: Float, descent: Float, measure: (String) -> Float): Block {
        val shown = shown(text, style).ifEmpty { " " }
        return if (style.curved) curve(shown, style, ascent, descent, measure) else straight(shown, style, maxEms, ascent, descent, measure)
    }

    private fun straight(text: String, style: TextStyle, maxEms: Float, ascent: Float, descent: Float, measure: (String) -> Float): Block {
        val texts = lines(text, max(0.5f, maxEms), measure)
        val widths = texts.map { if (it.isEmpty()) 0f else measure(it) }
        val blockWidth = widths.maxOrNull() ?: 0f
        val glyphHeight = ascent + descent
        val pitch = glyphHeight * style.lineHeight
        val height = (texts.size - 1) * pitch + glyphHeight
        val top = -height / 2f
        val lines = texts.mapIndexed { i, t ->
            val w = widths[i]
            val x = when (style.align) {
                TextAlign.LEFT -> -blockWidth / 2f
                TextAlign.CENTER -> -w / 2f
                TextAlign.RIGHT -> blockWidth / 2f - w
            }
            Line(t, x, top + i * pitch + ascent, w)
        }
        val pad = style.boxPadding
        val boxes = when {
            !style.hasBox -> emptyList()
            style.boxPerLine -> lines.filter { it.width > 0f }.map { l ->
                Rect(l.x - pad, l.baseline - ascent - pad * 0.5f, l.x + l.width + pad, l.baseline + descent + pad * 0.5f)
            }
            else -> listOf(Rect(-blockWidth / 2f - pad, top - pad, blockWidth / 2f + pad, top + height + pad))
        }
        val text0 = Rect(-blockWidth / 2f, top, blockWidth / 2f, top + height)
        return Block(lines, emptyList(), boxes, union(text0, boxes, style), radius(style, boxes))
    }

    /**
     * One line along a circle. The sweep is [TextStyle.arc] of a full turn and the radius is the
     * line's width over that sweep, so the curve looks the same however big the canvas is.
     */
    private fun curve(text: String, style: TextStyle, ascent: Float, descent: Float, measure: (String) -> Float): Block {
        val parts = clusters(text)
        val edges = FloatArray(parts.size + 1)
        val sb = StringBuilder()
        for (i in parts.indices) { sb.append(parts[i]); edges[i + 1] = measure(sb.toString()) }
        val total = edges.last()
        val sweep = style.arc * 2.0 * PI
        val radius = if (total <= 0f) 1.0 else total / abs(sweep)
        val up = style.arc > 0f
        val raw = parts.indices.map { i ->
            val w = edges[i + 1] - edges[i]
            val s = (edges[i] + edges[i + 1]) / 2.0 - total / 2.0
            val phi = s / radius
            val x = radius * sin(phi)
            val drop = radius - radius * cos(phi)
            Glyph(parts[i], x.toFloat(), (if (up) drop else -drop).toFloat(), w, Math.toDegrees(if (up) phi else -phi).toFloat())
        }
        // Each letter reaches from ascent above its baseline to descent below, turned with it.
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (g in raw) {
            val a = Math.toRadians(g.angle.toDouble())
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            for ((dx, dy) in listOf(-g.width / 2 to -ascent, g.width / 2 to -ascent, -g.width / 2 to descent, g.width / 2 to descent)) {
                val px = g.x + dx * c - dy * s; val py = g.y + dx * s + dy * c
                l = min(l, px); r = max(r, px); t = min(t, py); b = max(b, py)
            }
        }
        if (raw.isEmpty()) { l = 0f; r = 0f; t = -ascent; b = descent }
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val glyphs = raw.map { it.copy(x = it.x - cx, y = it.y - cy) }
        val text0 = Rect(l - cx, t - cy, r - cx, b - cy)
        val pad = style.boxPadding
        val boxes = if (style.hasBox) listOf(Rect(text0.left - pad, text0.top - pad, text0.right + pad, text0.bottom + pad)) else emptyList()
        return Block(emptyList(), glyphs, boxes, union(text0, boxes, style), radius(style, boxes))
    }

    /** A corner radius never larger than half the smallest box: a pill, not a blob. */
    private fun radius(style: TextStyle, boxes: List<Rect>): Float =
        if (boxes.isEmpty()) 0f else min(style.boxRadius, boxes.minOf { min(it.width, it.height) } / 2f)

    /** The text, its boxes, its outline and its shadow: everything the layer may touch. */
    private fun union(text: Rect, boxes: List<Rect>, style: TextStyle): Rect {
        var l = text.left - style.outline; var t = text.top - style.outline
        var r = text.right + style.outline; var b = text.bottom + style.outline
        if (style.hasShadow) {
            val reach = style.shadowBlur * 2f
            l = min(l, l + style.shadowX - reach); r = max(r, r + style.shadowX + reach)
            t = min(t, t + style.shadowY - reach); b = max(b, b + style.shadowY + reach)
        }
        for (x in boxes) { l = min(l, x.left); t = min(t, x.top); r = max(r, x.right); b = max(b, x.bottom) }
        return Rect(l, t, r, b)
    }
}

/**
 * The designed starting points in the Styles strip. Each takes the colour the user chose (the
 * ink) and returns the layer's colour and style, so a preset follows the user's colour rather
 * than replacing it with one of its own.
 */
enum class TextPreset {
    CLASSIC, POSTER, CAPTION, NEON, OUTLINE, HANDWRITTEN, ELEGANT, TYPEWRITER, MARKER, SHADOWED, BADGE, QUOTE, STICKER, ARCHED, SMILE, LABEL;

    /** The layer colour and style this preset gives with [ink] as the user's colour. */
    fun apply(ink: Int): Pair<Int, TextStyle> {
        val d = TextStyle.DEFAULT
        val none = 0
        val contrast = contrastOn(ink)
        return when (this) {
            CLASSIC -> ink to d
            POSTER -> ink to d.copy(font = TextFont.GROTESK, weight = 700, caps = true, align = TextAlign.CENTER, letterSpacing = 0.02f, lineHeight = 0.9f, shadowBlur = 0.04f, shadowY = 0.03f, shadowColor = 0x66000000)
            CAPTION -> TextStyle.WHITE to d.copy(weight = 500, align = TextAlign.CENTER, shadowColor = none, boxColor = TextStyle.BLACK, boxOpacity = 0.62f, boxPadding = 0.35f, boxRadius = 0.12f)
            NEON -> mix(ink, TextStyle.WHITE, 0.6f) to d.copy(font = TextFont.GROTESK, weight = 500, align = TextAlign.CENTER, letterSpacing = 0.04f, shadowBlur = 0.32f, shadowX = 0f, shadowY = 0f, shadowColor = ink or (0xFF shl 24))
            OUTLINE -> TextStyle.WHITE to d.copy(font = TextFont.GROTESK, weight = 700, caps = true, align = TextAlign.CENTER, outline = 0.07f, outlineColor = TextStyle.BLACK, shadowColor = none)
            HANDWRITTEN -> ink to d.copy(font = TextFont.SCRIPT, weight = 600, align = TextAlign.CENTER, lineHeight = 1.05f, shadowBlur = 0.06f, shadowY = 0.03f, shadowColor = 0x50000000)
            ELEGANT -> ink to d.copy(font = TextFont.SERIF, weight = 400, align = TextAlign.CENTER, letterSpacing = 0.03f, lineHeight = 1.1f, shadowBlur = 0.1f, shadowY = 0.02f, shadowColor = 0x40000000)
            TYPEWRITER -> 0xFF222222.toInt() to d.copy(font = TextFont.MONO, weight = 400, shadowColor = none, boxColor = 0xFFF4EBD9.toInt(), boxOpacity = 0.94f, boxPadding = 0.3f, boxRadius = 0.03f)
            MARKER -> contrast to d.copy(weight = 700, shadowColor = none, boxColor = ink, boxOpacity = 0.9f, boxPadding = 0.16f, boxRadius = 0.05f, boxPerLine = true, lineHeight = 1.15f)
            SHADOWED -> ink to d.copy(font = TextFont.GROTESK, weight = 700, shadowBlur = 0f, shadowX = 0.06f, shadowY = 0.06f, shadowColor = 0xCC000000.toInt())
            BADGE -> contrast to d.copy(font = TextFont.GROTESK, weight = 600, caps = true, align = TextAlign.CENTER, letterSpacing = 0.08f, shadowColor = none, boxColor = ink, boxOpacity = 1f, boxPadding = 0.5f, boxRadius = 5f)
            QUOTE -> ink to d.copy(font = TextFont.SERIF, weight = 400, italic = true, align = TextAlign.CENTER, lineHeight = 1.2f, shadowBlur = 0.12f, shadowY = 0.03f, shadowColor = 0x48000000)
            STICKER -> ink to d.copy(font = TextFont.GROTESK, weight = 700, align = TextAlign.CENTER, outline = 0.12f, outlineColor = TextStyle.WHITE, shadowBlur = 0.08f, shadowY = 0.06f, shadowColor = 0x55000000)
            ARCHED -> ink to d.copy(font = TextFont.GROTESK, weight = 700, caps = true, align = TextAlign.CENTER, letterSpacing = 0.06f, arc = 0.22f)
            SMILE -> ink to d.copy(font = TextFont.SCRIPT, weight = 700, align = TextAlign.CENTER, arc = -0.18f, shadowBlur = 0.06f, shadowY = 0.03f, shadowColor = 0x50000000)
            LABEL -> ink to d.copy(font = TextFont.MONO, weight = 500, caps = true, align = TextAlign.CENTER, letterSpacing = 0.18f, shadowColor = none, outline = 0f, boxColor = ink, boxOpacity = 0.18f, boxPadding = 0.4f, boxRadius = 0.08f)
        }
    }

    companion object {
        /** Black on a light colour, white on a dark one, by perceived lightness. */
        fun contrastOn(c: Int): Int {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return if (0.299 * r + 0.587 * g + 0.114 * b > 150) 0xFF111111.toInt() else TextStyle.WHITE
        }

        /** [a] moved [t] of the way to [b], opaque. */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).roundToInt()).coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /**
         * The user's colour behind a layer a preset made. A badge keeps it in its box and neon in
         * its glow, so choosing another preset starts from that colour, not from the letters'.
         */
        fun inkOf(color: Int, style: TextStyle): Int {
            val candidates = listOf(color, style.shadowColor or (0xFF shl 24), style.boxColor)
            return candidates.firstOrNull { ink -> entries.any { it.apply(ink) == (color to style) } } ?: color
        }

        /** The preset that gives exactly this colour and style for [ink], if any. */
        fun matching(ink: Int, color: Int, style: TextStyle): TextPreset? =
            entries.firstOrNull { it.apply(ink) == (color to style) } ?: entries.firstOrNull { it.apply(ink).second == style }
    }
}
