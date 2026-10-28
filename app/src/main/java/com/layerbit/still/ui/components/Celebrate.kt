package com.layerbit.still.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.layerbit.still.ui.theme.Neon
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A burst of the brand's colours for the moment something finished: an export written, a batch
 * done. Fires once each time [key] changes to something, lasts under a second, draws over the
 * screen without taking a touch, and is nothing at all when motion is off.
 */
@Composable
fun Celebrate(key: Any?, modifier: Modifier = Modifier) {
    val clock = LocalLivelyClock.current
    val progress = remember { Animatable(0f) }
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (key == null || clock == null) return@LaunchedEffect
        active = true
        progress.snapTo(0f)
        progress.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
        active = false
    }
    if (!active) return
    val colours = listOf(StillColors.Band, Neon.Lime, Neon.Cyan, Neon.Magenta, Neon.Violet, Neon.Coral)
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        val c = center
        val reach = min(size.width, size.height) * 0.42f
        val n = 30
        for (i in 0 until n) {
            val angle = (i * 2 * PI / n).toFloat() + (i % 3) * 0.07f
            val spread = 0.55f + 0.45f * ((i * 7) % 5) / 4f
            val d = (0.12f + 0.88f * t) * reach * spread
            val drop = t * t * 90.dp.toPx()
            val at = Offset(c.x + cos(angle) * d, c.y + sin(angle) * d + drop)
            val alpha = (1f - t * t).coerceIn(0f, 1f)
            drawCircle(colours[i % colours.size].copy(alpha = alpha), radius = (7f - 4f * t).dp.toPx(), center = at)
        }
    }
}
