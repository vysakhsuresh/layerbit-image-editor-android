package com.layerbit.still.ui.home

import androidx.compose.foundation.interaction.MutableInteractionSource
import com.layerbit.still.ui.components.pressScale
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import com.layerbit.still.ui.theme.Neon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
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
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "hero") { Box(rise(0)) { Hero(onSettings, onAbout) } }
        item(key = "drop") { Box(rise(1)) { DropZone(onOpenPictures) } }
        if (projects.isNotEmpty()) item(key = "projects") {
            Column(rise(2)) {
                SectionHeading(stringResource(R.string.home_pick_up), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(projects, key = { it.id }) { p -> ProjectCard(p, thumbnail, onOpen = { onOpenProject(p) }, onForget = { onForgetProject(p) }) }
                }
            }
        }
        item(key = "doors") {
            Column(rise(3)) {
                SectionHeading(stringResource(R.string.home_or), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                // Four doors as a bento: two rows of two, each wearing its own colour.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Door(Glyph.EDIT, Neon.Sky, stringResource(R.string.home_door_edit), stringResource(R.string.home_door_edit_body), onEditOne, Modifier.weight(1f))
                    Door(Glyph.RECIPE, Neon.Mint, stringResource(R.string.home_door_batch), stringResource(R.string.home_door_batch_body), { onProcessMany(null) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Door(Glyph.COLLAGE, Neon.Magenta, stringResource(R.string.home_door_collage), stringResource(R.string.home_door_collage_body), onCollage, Modifier.weight(1f))
                    Door(Glyph.LAYERS, Neon.Violet, stringResource(R.string.home_door_tools), stringResource(R.string.home_door_tools_body), onTools, Modifier.weight(1f))
                }
            }
        }
        item(key = "quick") {
            Column(rise(4)) {
                SectionHeading(stringResource(R.string.home_quick), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
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
                val accents = listOf(Neon.Coral, Neon.Cyan, Neon.Lime, Neon.Mint, Neon.Sky, Neon.Violet, Neon.Magenta, Neon.Lime, Neon.Coral, Neon.Cyan, Neon.Violet, Neon.Magenta)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().testTag("quickTools")) {
                    for (row in quick.indices step 4) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            for (i in row until minOf(row + 4, quick.size)) {
                                val (glyph, label, action) = quick[i]
                                Quick(glyph, accents[i % accents.size], stringResource(label), Modifier.weight(1f), action)
                            }
                        }
                    }
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
        Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
            // The name in the loud face, lit from amber into magenta.
            Text(
                stringResource(R.string.app_name_short),
                style = MaterialTheme.typography.displayLarge.copy(brush = Brush.horizontalGradient(listOf(StillColors.BandBright, StillColors.Band, Neon.Magenta)))
            )
            Box(drawOn().padding(top = 4.dp, bottom = 8.dp).height(5.dp).width(84.dp).background(Brush.horizontalGradient(listOf(StillColors.Band, Neon.Magenta)), RoundedCornerShape(3.dp)))
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyLarge, color = StillColors.Muted)
        }
        RoundIconButton(icon = Icons.Filled.Settings, contentDescription = stringResource(R.string.app_settings), onClick = onSettings)
        Spacer(Modifier.width(8.dp))
        RoundIconButton(icon = Icons.Filled.Info, contentDescription = stringResource(R.string.app_about), onClick = onAbout)
    }
}

/** The big one: a lit field that opens the picker. A drop from another window lands here too. */
@Composable
private fun DropZone(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    val border = StillColors.Band
    val clock = LocalLivelyClock.current
    val dark = StillColors.isDark
    Column(
        modifier = Modifier.fillMaxWidth()
            .shadow(if (dark) 18.dp else 8.dp, shape, clip = false, ambientColor = StillColors.Band.copy(alpha = 0.35f), spotColor = StillColors.Band.copy(alpha = 0.45f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(StillColors.Band.copy(alpha = if (dark) 0.34f else 0.20f), Neon.Magenta.copy(alpha = if (dark) 0.16f else 0.08f), StillColors.Surface)))
            .drawBehind {
                // The dashes walk round the door, so it looks like it is waiting for something.
                val phase = -(clock?.value ?: 0f) * 360f
                drawRoundRect(border.copy(alpha = 0.8f), cornerRadius = CornerRadius(30.dp.toPx()), style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f), phase)))
            }
            .clickable(role = Role.Button, onClick = onOpen).testTag("openPhotos").padding(vertical = 36.dp, horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            breathe().size(84.dp)
                .shadow(14.dp, RoundedCornerShape(26.dp), clip = false, ambientColor = StillColors.Band.copy(alpha = 0.5f), spotColor = StillColors.Band.copy(alpha = 0.6f))
                .clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(StillColors.BandBright, StillColors.Band, Neon.Coral))),
            contentAlignment = Alignment.Center
        ) {
            AnimatedGlyph(Glyph.PLUS, StillColors.OnBand, Modifier.size(44.dp), seed = 3)
        }
        Text(stringResource(R.string.home_open_pictures), style = MaterialTheme.typography.headlineMedium, color = StillColors.Text, modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
        Text(stringResource(R.string.home_open_pictures_body), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 16.dp)) {
            Pill(stringResource(R.string.app_pill_free), StillColors.Done)
            Pill(stringResource(R.string.app_pill_no_internet), StillColors.Running)
            Pill(stringResource(R.string.app_pill_originals), StillColors.Band)
        }
    }
}

/** A bento tile: the colour of the door, its moving glyph large, the name set in the loud face. */
@Composable
private fun Door(glyph: Glyph, accent: Color, title: String, body: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    val interaction = remember { MutableInteractionSource() }
    val dark = StillColors.isDark
    Column(
        modifier = modifier.heightIn(min = 158.dp)
            .pressScale(interaction, 0.965f)
            .shadow(if (dark) 10.dp else 6.dp, shape, clip = false, ambientColor = accent.copy(alpha = 0.4f), spotColor = accent.copy(alpha = 0.5f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = if (dark) 0.95f else 1f), accent.copy(alpha = if (dark) 0.62f else 0.78f))))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(14.dp)
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            AnimatedGlyph(glyph, Color.White, Modifier.size(32.dp), seed = glyph.ordinal * 7)
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(top = 14.dp), maxLines = 2)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f), modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A quick tool: its glyph in a coloured bubble, its name under it. */
@Composable
private fun Quick(glyph: Glyph, accent: Color, label: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.pressScale(interaction, 0.93f).clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.14f))))
                .border(1.5.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AnimatedGlyph(glyph, accent, Modifier.size(32.dp), seed = glyph.ordinal * 11)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = StillColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp), maxLines = 2, minLines = 2)
    }
}

@Composable
private fun ProjectCard(p: ImageStore.Project, thumbnail: suspend (ImageStore.Project) -> Bitmap?, onOpen: () -> Unit, onForget: () -> Unit) {
    val image by produceState<Bitmap?>(null, p.id) { value = thumbnail(p) }
    val shape = RoundedCornerShape(22.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.width(196.dp).height(150.dp).pressScale(interaction, 0.97f).clip(shape).background(StillColors.Canvas)
            .border(1.dp, StillColors.Border, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
    ) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // The name on a dark glass strip over the bottom of the picture.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))).padding(10.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.file.width} × ${p.file.height}" + if (!p.recipe.isIdentity) " · " + stringResource(R.string.home_edited) else "", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
        }
        RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.home_remove_project), onClick = onForget, size = 26.dp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}
