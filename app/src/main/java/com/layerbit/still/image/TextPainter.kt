package com.layerbit.still.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.layerbit.still.R
import com.layerbit.still.engine.TextFont
import com.layerbit.still.engine.TextLayout
import com.layerbit.still.engine.TextStyle
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The bundled faces for text layers, read from the APK once and kept. Nothing is downloaded:
 * a face that cannot be read falls back to the phone's own sans-serif.
 */
object TextFonts {
    @Volatile private var context: Context? = null
    private val cache = ConcurrentHashMap<Int, Typeface>()

    fun install(c: Context) { context = c.applicationContext }

    private fun res(id: Int): Typeface? = cache[id] ?: context?.let { c -> runCatching { ResourcesCompat.getFont(c, id) }.getOrNull() }?.also { cache[id] = it }

    fun typeface(font: TextFont, weight: Int): Typeface = when (font) {
        TextFont.SYSTEM -> null
        TextFont.GROTESK -> res(
            when {
                weight < 450 -> R.font.space_grotesk_regular
                weight < 550 -> R.font.space_grotesk_medium
                weight < 650 -> R.font.space_grotesk_semibold
                else -> R.font.space_grotesk_bold
            }
        )
        TextFont.SERIF -> res(R.font.dm_serif_display_regular)
        TextFont.SCRIPT -> res(R.font.dancing_script_variable)
        TextFont.MONO -> res(R.font.fira_code_variable)
    } ?: system(weight)

    private fun system(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weight.coerceIn(1, 1000), false)
        else Typeface.create(Typeface.SANS_SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)

    /** The weight axis for the variable faces, inside the range each one has. */
    fun variation(font: TextFont, weight: Int): String? = when (font) {
        TextFont.SCRIPT -> "'wght' ${weight.coerceIn(400, 700)}"
        TextFont.MONO -> "'wght' ${weight.coerceIn(300, 700)}"
        else -> null
    }
}

/**
 * Draws a text layer. [TextLayout] decides every position in ems from strings measured at one
 * fixed size; this only measures and multiplies, so the preview, the export and the little
 * previews in the Styles strip all lay the text out the same way.
 */
object TextPainter {
    /** Strings are measured at this size and divided by it: a measurement in ems. */
    private const val MEASURE_PX = 256f

    fun paint(style: TextStyle, px: Float): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = TextFonts.typeface(style.font, style.weight)
        TextFonts.variation(style.font, style.weight)?.let { runCatching { fontVariationSettings = it } }
        // The serif has one weight; a heavy choice thickens it rather than doing nothing.
        isFakeBoldText = style.font == TextFont.SERIF && style.weight >= 650
        textSize = px
        letterSpacing = style.letterSpacing
        textSkewX = if (style.italic) -0.22f else 0f
        hinting = Paint.HINTING_OFF
    }

    /** Where everything goes, in ems, for a layer wrapped at [wrapEms]. */
    fun layout(text: String, style: TextStyle, wrapEms: Float): TextLayout.Block {
        val m = paint(style, MEASURE_PX)
        val fm = m.fontMetrics
        return TextLayout.layout(text, style, wrapEms, -fm.ascent / MEASURE_PX, fm.descent / MEASURE_PX) { s -> m.measureText(s) / MEASURE_PX }
    }

    /**
     * Draws [text] centred on ([cx], [cy]), turned by [angle] degrees, [px] pixels tall, wrapped
     * at [wrapPx]. [opacity] fades the whole layer: box, shadow, outline and letters together.
     */
    fun draw(c: Canvas, text: String, color: Int, opacity: Float, style: TextStyle, px: Float, wrapPx: Float, cx: Float, cy: Float, angle: Float) {
        val block = layout(text, style, wrapPx / px)
        c.save()
        c.translate(cx, cy)
        c.rotate(angle)
        val faded = opacity < 1f
        if (faded) {
            val b = block.bounds
            c.saveLayerAlpha(RectF(b.left * px - 2, b.top * px - 2, b.right * px + 2, b.bottom * px + 2), (opacity.coerceIn(0f, 1f) * 255).roundToInt())
        }
        if (block.boxes.isNotEmpty()) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = style.boxColor
                alpha = (Color.alpha(style.boxColor) * style.boxOpacity).roundToInt().coerceIn(0, 255)
            }
            val r = block.boxRadius * px
            for (b in block.boxes) c.drawRoundRect(RectF(b.left * px, b.top * px, b.right * px, b.bottom * px), r, r, bp)
        }
        val outlined = style.outline > 0f
        val shadow = { p: Paint ->
            if (style.hasShadow) p.setShadowLayer(max(0.5f, style.shadowBlur * px), style.shadowX * px, style.shadowY * px, style.shadowColor)
        }
        if (outlined) {
            val op = paint(style, px).apply {
                this.color = style.outlineColor
                this.style = Paint.Style.FILL_AND_STROKE
                strokeWidth = style.outline * 2f * px
                strokeJoin = Paint.Join.ROUND
            }
            shadow(op)
            drawText(c, block, op, px)
        }
        val fp = paint(style, px).apply { this.color = color }
        if (!outlined) shadow(fp)
        drawText(c, block, fp, px)
        if (faded) c.restore()
        c.restore()
    }

    private fun drawText(c: Canvas, block: TextLayout.Block, p: Paint, px: Float) {
        for (l in block.lines) if (l.text.isNotEmpty()) c.drawText(l.text, l.x * px, l.baseline * px, p)
        for (g in block.glyphs) {
            c.save()
            c.translate(g.x * px, g.y * px)
            c.rotate(g.angle)
            c.drawText(g.text, -g.width / 2f * px, 0f, p)
            c.restore()
        }
    }
}
