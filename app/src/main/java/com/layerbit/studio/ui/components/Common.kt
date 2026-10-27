package com.layerbit.studio.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.studio.ui.icons.AnimatedGlyph
import com.layerbit.studio.ui.icons.glyphFor
import com.layerbit.studio.ui.icons.glyphSeed
import com.layerbit.studio.ui.theme.StudioColors
import java.util.Locale

/**
 * The handful of shapes every screen is built from. One definition of a panel, a heading, a
 * button and a chip, so the whole app changes character when one of these does.
 */

val PanelShape = RoundedCornerShape(18.dp)
val ButtonShape = RoundedCornerShape(14.dp)

/** A press that gives a little, which is most of what makes a surface feel like a button. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, amount: Float = 0.975f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) amount else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    return this.scale(scale)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    tint: Color? = null,
    inset: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val surface = tint?.let { it.copy(alpha = if (StudioColors.isDark) 0.14f else 0.10f) } ?: StudioColors.Surface
    val border = when {
        onClick != null && focused -> StudioColors.Band
        onClick != null && hovered -> StudioColors.BorderStrong
        highlighted -> StudioColors.Band.copy(alpha = 0.6f)
        tint != null -> tint.copy(alpha = 0.35f)
        else -> StudioColors.Border
    }
    var base = modifier
    if (onClick != null) base = base.pressScale(interaction)
    base = base.clip(PanelShape).background(StudioColors.Surface)
        .then(if (tint != null) Modifier.background(surface) else Modifier)
        .border(if (onClick != null && focused) 2.dp else 1.dp, border, PanelShape)
    val tappable = when {
        onClick == null -> base
        onLongClick == null -> base.clickable(interactionSource = interaction, indication = null, onClick = onClick)
        else -> base.combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
    }
    Column(modifier = tappable.padding(inset), content = content)
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (accent != null) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Box(Modifier.size(8.dp))
        }
        Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = StudioColors.Dim, letterSpacing = 1.4.sp)
    }
}

@Composable
fun LabelledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionHeading(label)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = StudioColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RowBetween(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, content = content)
}

/** The one coloured button on a screen: what the screen is for. */
@Composable
fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, iconRes: Int? = null, tall: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val brush = if (enabled) Brush.horizontalGradient(listOf(StudioColors.Band, StudioColors.BandBright))
    else Brush.horizontalGradient(listOf(StudioColors.SurfaceDim, StudioColors.SurfaceDim))
    Row(
        modifier = modifier.pressScale(interaction).height(if (tall) 52.dp else 40.dp).clip(ButtonShape).background(brush)
            .then(if (!enabled) Modifier.border(1.dp, StudioColors.Border, ButtonShape) else Modifier)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (enabled) StudioColors.OnBand else StudioColors.Dim
        if (icon != null) {
            LivelyIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)); Box(Modifier.size(8.dp))
        } else if (iconRes != null) {
            LivelyIcon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp), iconRes = iconRes); Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1)
    }
}

/** The quieter sibling: outlined, for the second-best thing to do. */
@Composable
fun SecondaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, iconRes: Int? = null, tall: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val tint = if (enabled) StudioColors.Text else StudioColors.Dim
    Row(
        modifier = modifier.pressScale(interaction).height(if (tall) 52.dp else 40.dp).clip(ButtonShape).background(StudioColors.Surface)
            .border(1.dp, StudioColors.BorderStrong, ButtonShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            LivelyIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)); Box(Modifier.size(8.dp))
        } else if (iconRes != null) {
            LivelyIcon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp), iconRes = iconRes); Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1)
    }
}

/** A small coloured square with an icon in it: the mark every tool carries. */
@Composable
fun IconTile(iconRes: Int, accent: Color, modifier: Modifier = Modifier, size: Dp = 40.dp, filled: Boolean = false, lively: Boolean = true) {
    val glyph = if (lively) glyphFor(iconRes) else null
    val shape = RoundedCornerShape(size * 0.3f)
    val ink = if (filled) Color.White else accent
    Box(
        modifier = modifier.size(size).clip(shape).background(
            if (filled) Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.75f)))
            else Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.12f))),
            shape
        ),
        contentAlignment = Alignment.Center
    ) {
        if (glyph != null) AnimatedGlyph(glyph, ink, Modifier.size(size * 0.68f), glyphSeed(iconRes))
        else Icon(painter = painterResource(iconRes), contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.5f))
    }
}

/** A word in a capsule: a state, a file type, a count. */
@Composable
fun Pill(text: String, colour: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = if (filled) Color.White else colour,
        modifier = modifier.background(if (filled) colour else colour.copy(alpha = 0.16f), RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

/** Human file sizes, base 1024 as Android's own file managers show them. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/** A row of mutually exclusive choices, as chips. One definition for the whole app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            val active = value == selected
            val shape = RoundedCornerShape(999.dp)
            val fill by animateColorAsState(if (active) StudioColors.Band else StudioColors.SurfaceDim, label = "chip")
            val ink by animateColorAsState(if (active) StudioColors.OnBand else StudioColors.Muted, label = "chipInk")
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier.pressScale(interaction, 0.95f).background(fill, shape)
                    .border(1.dp, if (active) StudioColors.Band else StudioColors.Border, shape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = ink)
            }
        }
    }
}

/** A setting that is on or off, with the sentence that says what it costs. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    Row(modifier = modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = StudioColors.Text)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = StudioColors.Dim, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = StudioColors.OnBand, checkedTrackColor = StudioColors.Band,
                uncheckedThumbColor = StudioColors.Muted, uncheckedTrackColor = StudioColors.SurfaceDim, uncheckedBorderColor = StudioColors.Border
            )
        )
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(StudioColors.Border))
}
