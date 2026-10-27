package com.layerbit.studio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.layerbit.studio.R
import com.layerbit.studio.ui.theme.StudioColors

/** The bar every screen wears: a round back button, the title, and round buttons for actions. */
@Composable
fun StudioTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    iconRes: Int? = null,
    accent: Color? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().background(StudioColors.Background).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (onBack != null) RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.app_back), onClick = onBack)
        if (iconRes != null) IconTile(iconRes = iconRes, accent = accent ?: StudioColors.Band, size = 36.dp)
        Column(modifier = Modifier.weight(1f).padding(start = if (onBack == null && iconRes == null) 8.dp else 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = StudioColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StudioColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** A circular icon button on a quiet surface. The label is required: a round button is nothing but an icon. */
@Composable
fun RoundIconButton(
    icon: ImageVector? = null,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    tint: Color = StudioColors.Muted,
    filled: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 40.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.pressScale(interaction, 0.92f).size(size)
            .background(if (filled) StudioColors.Band else StudioColors.Surface, CircleShape)
            .border(1.dp, if (filled) StudioColors.Band else StudioColors.Border, CircleShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        val colour = if (!enabled) StudioColors.Dim else if (filled) StudioColors.OnBand else tint
        if (icon != null) LivelyIcon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(size * 0.55f))
        else if (iconRes != null) LivelyIcon(painterResource(iconRes), contentDescription = null, tint = colour, modifier = Modifier.size(size * 0.55f), iconRes = iconRes)
    }
}
