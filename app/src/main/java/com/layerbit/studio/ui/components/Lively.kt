package com.layerbit.studio.ui.components

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.layerbit.studio.ui.icons.AnimatedGlyph
import com.layerbit.studio.ui.icons.glyphFor
import com.layerbit.studio.ui.icons.glyphSeed

/**
 * The one clock every moving icon reads. A single slow transition provided once at the root;
 * icons read it only while drawing, so it ticking never recomposes a card or a list. Null when
 * motion is off: Android's animations off, or Lively icons off in Settings.
 */
val LocalLivelyClock = staticCompositionLocalOf<State<Float>?> { null }

private const val LAP_MS = 12_000

@Composable
fun ProvideLivelyClock(enabled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemAllows = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
    if (!enabled || !systemAllows) {
        CompositionLocalProvider(LocalLivelyClock provides null, content = content)
        return
    }
    val clock = rememberInfiniteTransition(label = "lively").animateFloat(
        initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(LAP_MS, easing = LinearEasing)), label = "lively-clock"
    )
    CompositionLocalProvider(LocalLivelyClock provides clock, content = content)
}

/** A Material icon, or its drawn and moving counterpart where there is one. */
@Composable
fun LivelyIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val glyph = glyphFor(imageVector)
    if (glyph == null) Icon(imageVector, contentDescription, modifier, tint)
    else AnimatedGlyph(glyph, tint, modifier.described(contentDescription), glyphSeed(imageVector.name))
}

@Composable
fun LivelyIcon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, @DrawableRes iconRes: Int? = null) {
    val glyph = iconRes?.let(::glyphFor)
    if (glyph == null) Icon(painter, contentDescription, modifier, tint)
    else AnimatedGlyph(glyph, tint, modifier.described(contentDescription), glyphSeed(iconRes))
}

private fun Modifier.described(text: String?): Modifier = if (text == null) this else semantics { contentDescription = text }
