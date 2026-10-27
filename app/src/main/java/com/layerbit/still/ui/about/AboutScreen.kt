package com.layerbit.still.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layerbit.still.BuildConfig
import com.layerbit.still.R
import com.layerbit.still.brand.BrandLinks
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ui.components.AmbientBackdrop
import com.layerbit.still.ui.components.IconTile
import com.layerbit.still.ui.components.LivelyIcon
import com.layerbit.still.ui.components.Panel
import com.layerbit.still.ui.components.Pill
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.theme.StillColors

/** What the app is, what it refuses to do, and how to reach a person. */
@Composable
fun AboutScreen(onGuide: () -> Unit = {}, onContact: () -> Unit = {}, onTools: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize()) {
    AmbientBackdrop(strength = 0.8f)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Masthead() }
        item {
            Panel(modifier = Modifier.fillMaxWidth(), tint = StillColors.Done) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = R.drawable.ic_lock, accent = StillColors.Done, filled = true)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.about_offline_title), style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                        Text(stringResource(R.string.about_offline_body), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = R.drawable.ic_tool_strip, accent = Color(0xFF5DD48F))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.about_honest_title), style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                        Text(stringResource(R.string.about_honest_body), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = R.drawable.ic_tool_markup, accent = Color(0xFF5B9DFF))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.about_feedback_title), style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                        Text(stringResource(R.string.about_feedback_body), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    PrimaryButton(stringResource(R.string.about_email), icon = Icons.Filled.Email, onClick = { BrandLinks.sendEmail(context, "Still (Layerbit) ${BuildConfig.VERSION_NAME} - feedback", "") }, modifier = Modifier.weight(1f), tall = false)
                    SecondaryButton("WhatsApp", icon = Icons.Filled.Send, onClick = { BrandLinks.openUrl(context, BrandLinks.WHATSAPP_URL) }, modifier = Modifier.weight(1f), tall = false)
                }
            }
        }
        item {
            Panel(modifier = Modifier.fillMaxWidth(), tint = StillColors.Band) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(modifier = Modifier.size(40.dp).background(StillColors.Band.copy(alpha = 0.2f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        LivelyIcon(Icons.Filled.Favorite, contentDescription = null, tint = StillColors.BandBright, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.about_free_title), style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                        Text(stringResource(R.string.about_free_body), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                PrimaryButton(stringResource(R.string.about_coffee), onClick = { BrandLinks.openUrl(context, BrandLinks.COFFEE_URL) }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), tall = false)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(stringResource(R.string.guide_title), onClick = onGuide, modifier = Modifier.weight(1f), tall = false)
                SecondaryButton(stringResource(R.string.contact_title), onClick = onContact, modifier = Modifier.weight(1f), tall = false)
                SecondaryButton(stringResource(R.string.tools_page_title), onClick = onTools, modifier = Modifier.weight(1f), tall = false)
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SecondaryButton(stringResource(R.string.about_rate), icon = Icons.Filled.Star, onClick = { BrandLinks.openUrl(context, BrandLinks.PLAY_LISTING) }, modifier = Modifier.fillMaxWidth(), tall = false)
                SecondaryButton(stringResource(R.string.about_privacy), icon = Icons.Filled.Lock, onClick = { BrandLinks.openUrl(context, BrandLinks.PRIVACY_URL) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), tall = false)
                TextButton(onClick = { BrandLinks.openUrl(context, BrandLinks.WEBSITE_URL) }) {
                    Text(stringResource(R.string.about_made_by, BrandLinks.BRAND_LABEL, BrandLinks.WEBSITE_URL.removePrefix("https://")), color = StillColors.Dim, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.about_licences), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    }
}

@Composable
private fun Masthead() {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(StillColors.Band.copy(alpha = if (StillColors.isDark) 0.2f else 0.12f), Color.Transparent)))
            .padding(vertical = 22.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF0B1220)), contentAlignment = Alignment.Center) {
            Image(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = stringResource(R.string.app_name), modifier = Modifier.size(132.dp))
        }
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = StillColors.Text, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
            Pill(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), StillColors.Muted)
            Pill(pluralStringResource(R.plurals.app_tool_count, ToolId.entries.size, ToolId.entries.size), StillColors.Band)
            Pill(stringResource(R.string.app_pill_no_internet), StillColors.Done)
        }
    }
}
