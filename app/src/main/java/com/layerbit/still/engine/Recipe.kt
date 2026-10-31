package com.layerbit.still.engine

/**
 * Everything the editor knows about an edit, and nothing about the pixels.
 *
 * A recipe is what the user did, written down as numbers. The source bytes never change; the
 * renderer reads the source and the recipe and produces pixels. That is what makes every edit
 * undoable, every preset reusable on another picture, and a batch the same thing as a single
 * edit handed more inputs. It is also why this file has no Android import: a recipe is data,
 * and data is tested on the JVM in seconds.
 *
 * Coordinates are fractions. A [Pt] of (0.5, 0.5) is the middle of whatever it is measured
 * against, so a recipe written on a 900-pixel preview lands on the same spot of a 24-megapixel
 * export.
 */
data class Pt(val x: Float, val y: Float) {
    val finite: Boolean get() = x.isFinite() && y.isFinite()
    fun clamped() = Pt(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/**
 * The part of the straightened picture that is kept, as fractions of its bounding box.
 *
 * Measured in the straightened frame rather than the source, so turning the straighten dial
 * moves the picture under a crop that stays where the user put it - which is what every editor
 * people are used to does, and what makes an aspect preset still mean what it says after a
 * quarter turn.
 */
data class Crop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = this == FULL

    /** At least [MIN] of the picture in each direction, inside the unit square. */
    val valid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
            width >= MIN - EPS && height >= MIN - EPS

    companion object {
        const val MIN = 0.02f
        private const val EPS = 1e-4f
        val FULL = Crop()
    }
}

/**
 * Four source corners for a perspective correction, as fractions of the upright source. The
 * identity is the unit square. Dragging a corner inwards says "this is where the document's
 * corner is", and the renderer maps that quadrilateral onto a rectangle.
 */
data class Quad(
    val tl: Pt = Pt(0f, 0f),
    val tr: Pt = Pt(1f, 0f),
    val br: Pt = Pt(1f, 1f),
    val bl: Pt = Pt(0f, 1f)
) {
    val isIdentity: Boolean get() = this == IDENTITY
    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    /** Convex, the right way round, and not collapsed: the only quads a perspective can map. */
    val valid: Boolean
        get() {
            if (points.any { !it.finite }) return false
            val p = points
            var sign = 0
            for (i in 0 until 4) {
                val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
                val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
                val s = if (cross > 1e-5f) 1 else if (cross < -1e-5f) -1 else 0
                if (s == 0) return false
                if (sign == 0) sign = s else if (s != sign) return false
            }
            // Screen y points down, so a quad in reading order has a positive turn.
            return sign > 0
        }

    companion object {
        val IDENTITY = Quad()
    }
}

/**
 * The shape of the output: perspective, then quarter turns and flips, then straightening, then
 * the crop, then the size. Applied in that order by the renderer, which is the order the
 * controls sit in on screen.
 */
data class Geometry(
    val quad: Quad = Quad.IDENTITY,
    /** 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    /** Degrees, -45 to 45, positive clockwise. */
    val straighten: Float = 0f,
    val crop: Crop = Crop.FULL,
    /** Requested output pixels; 0 means the natural size after everything above. */
    val width: Int = 0,
    val height: Int = 0
) {
    val isIdentity: Boolean get() = this == Geometry()
    val quarterTurns: Int get() = ((rotation % 360) + 360) % 360 / 90
    val valid: Boolean
        get() = rotation % 90 == 0 && straighten.isFinite() && straighten in -45f..45f && crop.valid && quad.valid &&
            width in 0..MAX_EDGE && height in 0..MAX_EDGE && (width == 0) == (height == 0)

    companion object {
        /** The longest edge any output may have, whatever the heap allows in area. */
        const val MAX_EDGE = 8192
    }
}

/**
 * Light and colour. Every field is 0 at rest; the user sees -1..1 sliders except where noted.
 *
 * The names are the ones photographers use, and each does one thing: exposure scales the
 * whole picture, contrast pivots it about mid-grey, highlights and shadows move the bright and
 * dark ends alone, whites and blacks set where the ends clip, and so on. Two that overlap in
 * cheaper editors - brightness and exposure - are deliberately one control here.
 */
data class Tone(
    /** Stops, -2..2. +1 doubles the light. */
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val saturation: Float = 0f,
    /** Saturation that spares what is already saturated, so skin does not go orange first. */
    val vibrance: Float = 0f,
    /** Warm (positive) to cool (negative). */
    val temperature: Float = 0f,
    /** Magenta (positive) to green (negative). */
    val tint: Float = 0f,
    /** Half turns of the hue wheel, -1..1 → -180°..180°. */
    val hue: Float = 0f,
    /** Lifts the black point, the "matte" look. 0..1. */
    val fade: Float = 0f,
    /** Local contrast at a large radius. -1..1. */
    val clarity: Float = 0f,
    /** Contrast in the shadows with a small lift of the floor; what haze takes away. 0..1. */
    val dehaze: Float = 0f,
    /** Unsharp mask amount, 0..1. */
    val sharpness: Float = 0f,
    /** Darkened corners, 0..1; negative brightens them. */
    val vignette: Float = 0f,
    /** Film grain, 0..1. */
    val grain: Float = 0f,
    /** Noise reduction, 0..1: colour blotches cleaned hard, brightness grain gently, edges kept. */
    val noise: Float = 0f
) {
    val isIdentity: Boolean get() = this == Tone()

    /** Everything that is decided per pixel from its own value: no neighbours, no position. */
    val pointIdentity: Boolean
        get() = copy(clarity = 0f, dehaze = 0f, sharpness = 0f, vignette = 0f, grain = 0f, noise = 0f) == Tone()

    /** The values a user may enter. Any slider outside its range is a corrupt recipe, not a taste. */
    val valid: Boolean
        get() = exposure in -2f..2f && contrast in -1f..1f && highlights in -1f..1f && shadows in -1f..1f &&
            whites in -1f..1f && blacks in -1f..1f && saturation in -1f..1f && vibrance in -1f..1f &&
            temperature in -1f..1f && tint in -1f..1f && hue in -1f..1f && fade in 0f..1f &&
            clarity in -1f..1f && dehaze in 0f..1f && sharpness in 0f..1f && vignette in -1f..1f && grain in 0f..1f && noise in 0f..1f
}

/**
 * Tone curves: control points from (0,0) to (1,1), master and per channel. Two points is a
 * straight line, which is the identity.
 */
data class Curves(
    val master: List<Pt> = IDENTITY_POINTS,
    val red: List<Pt> = IDENTITY_POINTS,
    val green: List<Pt> = IDENTITY_POINTS,
    val blue: List<Pt> = IDENTITY_POINTS
) {
    val isIdentity: Boolean get() = this == Curves()
    val valid: Boolean get() = listOf(master, red, green, blue).all { ok(it) }

    companion object {
        val IDENTITY_POINTS = listOf(Pt(0f, 0f), Pt(1f, 1f))
        fun ok(points: List<Pt>): Boolean =
            points.size in 2..16 && points.all { it.finite && it.x in 0f..1f && it.y in 0f..1f } &&
                points.zipWithNext().all { (a, b) -> b.x > a.x }
    }
}

/**
 * A named look. Each is a tone and a set of curves, applied at a strength, so a look is never a
 * secret: Settings can show exactly which sliders it moved, and a user can start from one and
 * keep editing.
 */
enum class Look { NONE, MONO, NOIR, SEPIA, WARM, COOL, FADE, MATTE, VIVID, FILM, CHROME, TEAL_ORANGE, PORTRAIT, DOCUMENT }

enum class MarkKind {
    DRAW, TEXT, ARROW, BOX, ELLIPSE, COVER, PIXELATE, BLUR, ERASE, CLONE,
    /** Text repeated across the whole picture at an angle. */
    WATERMARK,
    /** A tap on a flash-red pupil: the point and a radius. */
    REDEYE
}

/**
 * One layer drawn on top of the picture. Points are fractions of the final canvas (after
 * geometry, before the frame), so a mark follows the picture through a resize and a frame.
 */
data class Mark(
    val kind: MarkKind,
    val points: List<Pt>,
    val color: Int = DEFAULT_COLOR,
    /** Stroke or text size as a fraction of the shorter canvas edge. */
    val width: Float = 0.018f,
    val text: String = "",
    val opacity: Float = 1f,
    val visible: Boolean = true,
    /** Where a clone stroke samples from. */
    val source: Pt? = null,
    /** Degrees, for text and watermarks. */
    val angle: Float = 0f,
    /** Where a watermark goes; other kinds ignore it. */
    val place: WatermarkPlace = WatermarkPlace.TILED
) {
    val valid: Boolean
        get() = points.isNotEmpty() && points.size <= MAX_POINTS && points.all { it.finite && it.x in 0f..1f && it.y in 0f..1f } &&
            width in 0.002f..0.3f && opacity in 0f..1f && text.length <= MAX_TEXT && angle.isFinite() &&
            (source?.finite ?: true) && (kind != MarkKind.CLONE || source != null)

    companion object {
        const val DEFAULT_COLOR = 0xFFE62E80.toInt()
        const val MAX_POINTS = 4000
        const val MAX_TEXT = 300
    }
}

/**
 * What surrounds the picture: padding to an aspect, a border, rounded corners, and the colour
 * behind anything the picture does not cover. 0 everywhere means none.
 */
data class Frame(
    /** Pad the canvas to this aspect (width / height); 0 keeps the picture's own. */
    val aspect: Float = 0f,
    /** Extra margin on every side, as a fraction of the shorter edge. */
    val padding: Float = 0f,
    /** Border inside the padding, as a fraction of the shorter edge. */
    val border: Float = 0f,
    val borderColor: Int = 0xFFFFFFFF.toInt(),
    /** Behind padding and transparent corners. Alpha 0 keeps transparency for PNG and WebP. */
    val background: Int = 0xFFFFFFFF.toInt(),
    /** Rounded picture corners, as a fraction of the shorter edge. */
    val cornerRadius: Float = 0f,
    /** Fill the padding with a blurred copy of the picture instead of a flat colour. */
    val blurredFill: Boolean = false
) {
    val isIdentity: Boolean get() = this == Frame()
    val valid: Boolean
        get() = aspect.isFinite() && aspect >= 0f && aspect <= 20f && padding in 0f..0.5f && border in 0f..0.25f &&
            cornerRadius in 0f..0.5f
}

data class Recipe(
    val geometry: Geometry = Geometry(),
    val tone: Tone = Tone(),
    val curves: Curves = Curves(),
    val look: Look = Look.NONE,
    /** 0..1; how much of the look is applied over the user's own tone. */
    val lookStrength: Float = 1f,
    val marks: List<Mark> = emptyList(),
    val frame: Frame = Frame(),
    /** Paper cleanup, applied after tone and before detail. */
    val scan: Scan = Scan(),
    /** The subject or the ink lifted off its surroundings, applied after the layers and before the frame. */
    val cutout: Cutout = Cutout(),
    /** Hue, saturation and lightness per family of colour, after the tone. */
    val mix: ColourMix = ColourMix()
) {
    val isIdentity: Boolean get() = this == Recipe()
    val valid: Boolean
        get() = geometry.valid && tone.valid && curves.valid && lookStrength in 0f..1f && marks.size <= MAX_MARKS &&
            marks.all { it.valid } && frame.valid && scan.valid && cutout.valid && mix.valid

    /** The recipe without what belongs to one particular picture: what a preset keeps. */
    fun asPreset(): Recipe = copy(marks = emptyList(), geometry = geometry.copy(quad = Quad.IDENTITY, crop = Crop.FULL), cutout = Cutout())

    companion object {
        const val MAX_MARKS = 400
        /** Bumped only when an older app could not read what a newer one writes. */
        const val SCHEMA = 2
    }
}
