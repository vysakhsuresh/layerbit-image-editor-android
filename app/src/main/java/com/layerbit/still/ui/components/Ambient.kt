package com.layerbit.still.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.layerbit.still.ui.theme.StillColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What makes a screen feel alive without getting in the way: soft colour drifting behind the
 * page, things arriving rather than appearing, the front door breathing, a sheen passing over
 * the one coloured button. Every one of these reads the lively clock at draw time only, so
 * nothing recomposes, and every one is still when motion is off.
 */

private const val TAU = (2 * PI).toFloat()

/** Four soft pools of the brand's colours, drifting slowly behind the content. */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier, strength: Float = 1f, colours: List<Color> = emptyList()) {
    val clock = LocalLivelyClock.current
    val dark = StillColors.isDark
    // The pools wear the picture's own colours when there are any, so the editor glows with what it holds.
    val band = colours.getOrNull(0) ?: StillColors.Band
    val teal = colours.getOrNull(1) ?: Color(0xFF3ED3C0)
    val pink = colours.getOrNull(2) ?: Color(0xFFFF6BA8)
    val blue = colours.getOrNull(1) ?: Color(0xFF5B9DFF)
    Canvas(modifier.fillMaxSize()) {
        val t = (clock?.value ?: 0.37f) * TAU
        val w = size.width; val h = size.height
        val k = (if (dark) 1f else 0.8f) * strength
        fun pool(c: Color, cx: Float, cy: Float, r: Float, a: Float) {
            val centre = Offset(cx, cy)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a * k), c.copy(alpha = 0f)), center = centre, radius = r), r, centre)
        }
        // One drift a lap, each pool on its own phase, so the loop joins without a jump.
        pool(band, w * (0.88f + 0.06f * sin(t)), h * (0.06f + 0.04f * cos(t + 1.2f)), w * 0.6f, 0.30f)
        pool(teal, w * (0.08f + 0.05f * cos(t + 2.4f)), h * (0.42f + 0.05f * sin(t + 0.6f)), w * 0.55f, 0.20f)
        pool(pink, w * (0.72f + 0.05f * sin(t + 4.1f)), h * (0.78f + 0.04f * cos(t + 3.3f)), w * 0.5f, 0.16f)
        pool(blue, w * (0.18f + 0.04f * sin(t + 5.2f)), h * (0.98f + 0.03f * cos(t + 1.9f)), w * 0.45f, 0.12f)
    }
}

/** Arrives from a little below, after its neighbours: the [index]th thing on a screen. */
@Composable
fun rise(index: Int): Modifier {
    if (LocalLivelyClock.current == null) return Modifier
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(45L * index.coerceIn(0, 10))
        p.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    return Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 22.dp.toPx() }
}

/** A slow breath, a few percent of scale, about every four seconds. */
@Composable
fun breathe(amount: Float = 0.04f): Modifier {
    val clock = LocalLivelyClock.current ?: return Modifier
    return Modifier.graphicsLayer {
        val s = 1f + amount * (0.5f + 0.5f * sin(clock.value * TAU * 3f))
        scaleX = s; scaleY = s
    }
}

/** Draws itself from the left once, the way a rule is drawn under a title. */
@Composable
fun drawOn(delayMs: Long = 150): Modifier {
    if (LocalLivelyClock.current == null) return Modifier
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs); p.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    return Modifier.graphicsLayer { scaleX = p.value; transformOrigin = TransformOrigin(0f, 0.5f) }
}

/** A soft highlight that crosses the content once in a while; for the coloured button. */
fun ContentDrawScope.sheen(progress: Float?, enabled: Boolean) {
    drawContent()
    if (progress == null || !enabled) return
    // Three passes a lap, each over the first third of its window.
    val local = (progress * 3f) % 1f
    if (local > 0.35f) return
    val f = local / 0.35f
    val band = size.width * 0.45f
    val x = -band + (size.width + 2 * band) * f
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
            start = Offset(x, 0f), end = Offset(x + band, size.height)
        )
    )
}
