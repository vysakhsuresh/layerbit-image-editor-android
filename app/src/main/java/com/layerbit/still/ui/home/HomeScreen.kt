package com.layerbit.still.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.brand.BrandLinks
import com.layerbit.still.files.ImageStore
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ui.components.AmbientBackdrop
import com.layerbit.still.ui.components.LocalLivelyClock
import com.layerbit.still.ui.components.Panel
import com.layerbit.still.ui.components.breathe
import com.layerbit.still.ui.components.drawOn
import com.layerbit.still.ui.components.rise
import com.layerbit.still.ui.components.Pill
import com.layerbit.still.ui.components.RoundIconButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.icons.AnimatedGlyph
import com.layerbit.still.ui.icons.Glyph
import com.layerbit.still.ui.theme.StillColors

/**
 * The front door is one gesture: open pictures. One picture goes to the editor; several go to
 * the batch screen with every step ready to switch on. Under that, the pictures you were in the
 * middle of, and three quiet doors for the people who know what they want.
 */
@Composable
fun HomeScreen(
    projects: List<ImageStore.Project>,
    thumbnail: suspend (ImageStore.Project) -> Bitmap?,
    onOpenPictures: () -> Unit,
    onOpenProject: (ImageStore.Project) -> Unit,
    onForgetProject: (ImageStore.Project) -> Unit,
    onEditOne: () -> Unit,
    onProcessMany: (String?) -> Unit,
    onQuickTool: (ToolId) -> Unit,
    onCollage: () -> Unit,
    onTools: () -> Unit,
    onGuide: () -> Unit,
    onContact: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {
    AmbientBackdrop()
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("homeList"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "hero") { Box(rise(0)) { Hero(onSettings, onAbout) } }
        item(key = "drop") { Box(rise(1)) { DropZone(onOpenPictures) } }
        if (projects.isNotEmpty()) item(key = "projects") {
            Column(rise(2)) {
            SectionHeading(stringResource(R.string.home_pick_up), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects, key = { it.id }) { p -> ProjectCard(p, thumbnail, onOpen = { onOpenProject(p) }, onForget = { onForgetProject(p) }) }
            }
            }
        }
        item(key = "doors") {
            Column(rise(3)) {
            SectionHeading(stringResource(R.string.home_or), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Door(Glyph.EDIT, Color(0xFF5B9DFF), stringResource(R.string.home_door_edit), stringResource(R.string.home_door_edit_body), onEditOne)
            Door(Glyph.RECIPE, Color(0xFF3ED3C0), stringResource(R.string.home_door_batch), stringResource(R.string.home_door_batch_body), { onProcessMany(null) })
            Door(Glyph.COLLAGE, Color(0xFFFF6BA8), stringResource(R.string.home_door_collage), stringResource(R.string.home_door_collage_body), onCollage)
            Door(Glyph.LAYERS, Color(0xFFB08CFF), stringResource(R.string.home_door_tools), stringResource(R.string.home_door_tools_body), onTools)
            }
        }
        item(key = "quick") {
            Column(rise(4)) {
            SectionHeading(stringResource(R.string.home_quick), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            // Each icon acts out its tool. One picture tools ask for the picture first; batch tools open the steps ready.
            val quick = listOf(
                Triple(Glyph.CUTOUT, R.string.home_quick_cutout) { onQuickTool(ToolId.CUTOUT) },
                Triple(Glyph.SCAN, R.string.home_quick_scan) { onQuickTool(ToolId.SCAN) },
                Triple(Glyph.SIGNATURE, R.string.home_quick_signature) { onQuickTool(ToolId.SIGNATURE) },
                Triple(Glyph.RESIZE_KB, R.string.home_quick_under) { onProcessMany("under") },
                Triple(Glyph.PASSPORT, R.string.home_quick_passport) { onProcessMany("passport") },
                Triple(Glyph.STRIP, R.string.home_quick_clean) { onProcessMany("clean") },
                Triple(Glyph.REDEYE, R.string.home_quick_redeye) { onQuickTool(ToolId.RED_EYE) },
                Triple(Glyph.NEUTRAL, R.string.home_quick_neutral) { onQuickTool(ToolId.WHITE_BALANCE) },
                Triple(Glyph.STICKER, R.string.home_quick_sticker) { onQuickTool(ToolId.STICKER) },
                Triple(Glyph.CONVERT, R.string.home_quick_convert) { onProcessMany("convert") },
                Triple(Glyph.WATERMARK, R.string.home_quick_watermark) { onProcessMany("watermark") },
                Triple(Glyph.COLLAGE, R.string.home_quick_collage) { onCollage() }
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("quickTools")) {
                items(quick.size) { i -> val (glyph, label, action) = quick[i]; Quick(glyph, stringResource(label), Modifier.width(96.dp), action) }
            }
            }
        }
        item(key = "footer") {
            Column(modifier = rise(5).fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.home_promise), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, textAlign = TextAlign.Center)
                Row {
                    TextButton(onClick = onGuide) { Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.bodySmall, color = StillColors.Muted) }
                    TextButton(onClick = onContact) { Text(stringResource(R.string.contact_title), style = MaterialTheme.typography.bodySmall, color = StillColors.Muted) }
                    TextButton(onClick = onAbout) { Text(stringResource(R.string.app_about), style = MaterialTheme.typography.bodySmall, color = StillColors.Muted) }
                }
                TextButton(onClick = onAbout) { Text(stringResource(R.string.home_footer, BrandLinks.BRAND_LABEL), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim) }
            }
        }
    }
    }
}

@Composable
private fun Hero(onSettings: () -> Unit, onAbout: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(stringResource(R.string.app_name_short), style = MaterialTheme.typography.displayMedium, color = StillColors.Text)
            Box(drawOn().padding(top = 2.dp, bottom = 6.dp).height(4.dp).width(64.dp).background(Brush.horizontalGradient(listOf(StillColors.Band, StillColors.BandBright)), RoundedCornerShape(2.dp)))
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted)
        }
        RoundIconButton(icon = Icons.Filled.Settings, contentDescription = stringResource(R.string.app_settings), onClick = onSettings)
        Spacer(Modifier.width(8.dp))
        RoundIconButton(icon = Icons.Filled.Info, contentDescription = stringResource(R.string.app_about), onClick = onAbout)
    }
}

/** The big one: a dashed field that opens the picker. A drop from another window lands here too. */
@Composable
private fun DropZone(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val border = StillColors.Band
    val clock = LocalLivelyClock.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(StillColors.Band.copy(alpha = if (StillColors.isDark) 0.22f else 0.14f), StillColors.Surface)))
            .drawBehind {
                // The dashes walk round the door, so it looks like it is waiting for something.
                val phase = -(clock?.value ?: 0f) * 360f
                drawRoundRect(border.copy(alpha = 0.75f), cornerRadius = CornerRadius(24.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), phase)))
            }
            .clickable(role = Role.Button, onClick = onOpen).testTag("openPhotos").padding(vertical = 34.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(breathe().size(64.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(StillColors.BandBright, StillColors.Band))), contentAlignment = Alignment.Center) {
            AnimatedGlyph(Glyph.PLUS, StillColors.OnBand, Modifier.size(34.dp), seed = 3)
        }
        Text(stringResource(R.string.home_open_pictures), style = MaterialTheme.typography.headlineSmall, color = StillColors.Text, modifier = Modifier.padding(top = 14.dp), textAlign = TextAlign.Center)
        Text(stringResource(R.string.home_open_pictures_body), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 14.dp)) {
            Pill(stringResource(R.string.app_pill_free), StillColors.Done)
            Pill(stringResource(R.string.app_pill_no_internet), StillColors.Running)
            Pill(stringResource(R.string.app_pill_originals), StillColors.Band)
        }
    }
}

@Composable
private fun Door(glyph: Glyph, accent: Color, title: String, body: String, onClick: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick, inset = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                AnimatedGlyph(glyph, accent, Modifier.size(28.dp), seed = glyph.ordinal * 7)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = StillColors.Dim)
            }
        }
    }
}

@Composable
private fun Quick(glyph: Glyph, label: String, modifier: Modifier, onClick: () -> Unit) {
    Panel(modifier = modifier, onClick = onClick, inset = 10.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            AnimatedGlyph(glyph, StillColors.Band, Modifier.size(34.dp), seed = glyph.ordinal * 11)
            Text(label, style = MaterialTheme.typography.labelSmall, color = StillColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
        }
    }
}

@Composable
private fun ProjectCard(p: ImageStore.Project, thumbnail: suspend (ImageStore.Project) -> Bitmap?, onOpen: () -> Unit, onForget: () -> Unit) {
    val image by produceState<Bitmap?>(null, p.id) { value = thumbnail(p) }
    Panel(modifier = Modifier.width(180.dp), onClick = onOpen, inset = 10.dp) {
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(StillColors.Canvas)) {
            image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Crop) }
            RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.home_remove_project), onClick = onForget, size = 26.dp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(p.name, style = MaterialTheme.typography.bodyMedium, color = StillColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text("${p.file.width} × ${p.file.height}" + if (!p.recipe.isIdentity) " · " + stringResource(R.string.home_edited) else "", style = MaterialTheme.typography.labelSmall, color = StillColors.Dim)
    }
}
