package com.layerbit.still.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
        item(key = "quick") {
            Column(rise(2)) {
                SectionHeading(stringResource(R.string.home_quick), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                // One sideways strip, always above the fold: each icon acts out its tool. One picture tools ask for the picture first; batch tools open the steps ready.
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
                // Each tile is a pair of neighbouring neons, so the strip reads as one sweep of colour.
                val accents = listOf(
                    Neon.Coral to Neon.Magenta, Neon.Cyan to Neon.Sky, Neon.Lime to Neon.Mint, Neon.Mint to Neon.Cyan,
                    Neon.Sky to Neon.Violet, Neon.Violet to Neon.Magenta, Neon.Magenta to Neon.Coral, Neon.Lime to Neon.Cyan,
                    Neon.Coral to Neon.Violet, Neon.Cyan to Neon.Mint, Neon.Violet to Neon.Sky, Neon.Magenta to Neon.Violet
                )
                val quickState = rememberLazyListState()
                val canScrollRight = quickState.canScrollForward
                val canScrollLeft = quickState.canScrollBackward
                val rightFade by animateFloatAsState(if (canScrollRight) 1f else 0f, label = "quickFadeRight")
                val leftFade by animateFloatAsState(if (canScrollLeft) 1f else 0f, label = "quickFadeLeft")
                LazyRow(
                    state = quickState,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    // Room for each tile's glow, which would otherwise be clipped by the strip.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth().testTag("quickTools")
                        // Fade the edges where more tools are hidden, so the strip reads as scrollable.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val edge = 36.dp.toPx()
                            if (rightFade > 0f) drawRect(
                                Brush.horizontalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1f - rightFade)), startX = size.width - edge, endX = size.width),
                                topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height), blendMode = BlendMode.DstIn
                            )
                            if (leftFade > 0f) drawRect(
                                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 1f - leftFade), Color.Black), startX = 0f, endX = edge),
                                topLeft = Offset.Zero, size = Size(edge, size.height), blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    items(quick.size) { i ->
                        val (glyph, label, action) = quick[i]
                        Quick(glyph, accents[i % accents.size], stringResource(label), i, Modifier.width(84.dp), action)
                    }
                }
                QuickScrollIndicator(quickState, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
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
        if (projects.isNotEmpty()) item(key = "projects") {
            Column(rise(4)) {
                SectionHeading(stringResource(R.string.home_pick_up), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(projects, key = { it.id }) { p -> ProjectCard(p, thumbnail, onOpen = { onOpenProject(p) }, onForget = { onForgetProject(p) }) }
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
                style = MaterialTheme.typography.displayMedium.copy(brush = Brush.horizontalGradient(listOf(StillColors.BandBright, StillColors.Band, Neon.Magenta)))
            )
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
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
            .clickable(role = Role.Button, onClick = onOpen).testTag("openPhotos").padding(vertical = 18.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The door lies across: the plus beside the words, so it is wide, not tall, and the tools stay in view under it.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                breathe().size(68.dp)
                    .shadow(12.dp, RoundedCornerShape(22.dp), clip = false, ambientColor = StillColors.Band.copy(alpha = 0.5f), spotColor = StillColors.Band.copy(alpha = 0.6f))
                    .clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(StillColors.BandBright, StillColors.Band, Neon.Coral))),
                contentAlignment = Alignment.Center
            ) {
                AnimatedGlyph(Glyph.PLUS, StillColors.OnBand, Modifier.size(36.dp), seed = 3)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(stringResource(R.string.home_open_pictures), style = MaterialTheme.typography.headlineSmall, color = StillColors.Text)
                Text(stringResource(R.string.home_open_pictures_body), style = MaterialTheme.typography.bodySmall, color = StillColors.Muted, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
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
        modifier = modifier.heightIn(min = 128.dp)
            .pressScale(interaction, 0.965f)
            .shadow(if (dark) 10.dp else 6.dp, shape, clip = false, ambientColor = accent.copy(alpha = 0.4f), spotColor = accent.copy(alpha = 0.5f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = if (dark) 0.95f else 1f), accent.copy(alpha = if (dark) 0.62f else 0.78f))))
            // A light on the top-left corner, as on anything glossy.
            .drawBehind { drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.26f), Color.Transparent), center = Offset(size.width * 0.18f, size.height * 0.12f), radius = size.width * 0.7f), size.width * 0.7f, Offset(size.width * 0.18f, size.height * 0.12f)) }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(14.dp)
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            AnimatedGlyph(glyph, Color.White, Modifier.size(28.dp), seed = glyph.ordinal * 7)
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(top = 10.dp), maxLines = 2)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f), modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A short track under the quick strip; the thumb shows how much is in view and where. */
@Composable
private fun QuickScrollIndicator(state: LazyListState, modifier: Modifier = Modifier) {
    val trackWidth = 64.dp
    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (visible.isEmpty() || viewport <= 0f || info.totalItemsCount == 0) return@derivedStateOf 1f to 0f
            val itemSpan = (visible.first().size + info.mainAxisItemSpacing).toFloat()
            val content = (itemSpan * info.totalItemsCount - info.mainAxisItemSpacing).coerceAtLeast(viewport)
            val scrolled = state.firstVisibleItemIndex * itemSpan + state.firstVisibleItemScrollOffset
            val fraction = (viewport / content).coerceIn(0.1f, 1f)
            val progress = if (content > viewport) (scrolled / (content - viewport)).coerceIn(0f, 1f) else 0f
            fraction to progress
        }
    }
    val (fraction, progress) = metrics
    if (fraction >= 1f) return
    Box(modifier.width(trackWidth).height(4.dp).clip(RoundedCornerShape(2.dp)).background(StillColors.Border)) {
        val thumb = trackWidth * fraction
        Box(
            Modifier.offset(x = (trackWidth - thumb) * progress).width(thumb).fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(StillColors.Band, Neon.Magenta)))
        )
    }
}

/**
 * A quick tool as a small cousin of the doors below: a glossy tile in two neighbouring neons
 * with a glow of its own colour, the moving glyph in white, and its name under it. Tiles spring
 * in one after another, and a sheen runs along the strip every few seconds, tile by tile.
 */
@Composable
private fun Quick(glyph: Glyph, accent: Pair<Color, Color>, label: String, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val clock = LocalLivelyClock.current
    val (from, to) = accent
    val dark = StillColors.isDark
    val shape = RoundedCornerShape(22.dp)
    // Arrival: a small spring from below, staggered along the strip.
    val arrive = remember { Animatable(if (clock == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (clock != null) { delay(60L * index.coerceAtMost(8)); arrive.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .graphicsLayer {
                val a = arrive.value
                alpha = a.coerceIn(0f, 1f)
                translationY = (1f - a) * 18.dp.toPx()
                val sc = 0.82f + 0.18f * a
                scaleX = sc; scaleY = sc
            }
            .pressScale(interaction, 0.9f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp)
    ) {
        Box(
            Modifier.size(68.dp)
                .shadow(if (dark) 12.dp else 7.dp, shape, clip = false, ambientColor = from.copy(alpha = 0.55f), spotColor = to.copy(alpha = 0.65f))
                .clip(shape)
                .background(Brush.linearGradient(listOf(from, to)))
                .drawWithContent {
                    // Gloss: a light in the top-left corner, as on the doors.
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.34f), Color.Transparent), center = Offset(size.width * 0.22f, size.height * 0.12f), radius = size.width * 0.75f),
                        size.width * 0.75f, Offset(size.width * 0.22f, size.height * 0.12f)
                    )
                    // A darker lip at the bottom gives the tile depth.
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f)), startY = size.height * 0.55f, endY = size.height))
                    // A soft shade behind the glyph, so white reads on the palest neon too.
                    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Transparent), center = center, radius = size.width * 0.42f), size.width * 0.42f, center)
                    drawContent()
                    // The sheen: a diagonal band of light crossing the tile, a beat after its neighbour.
                    val t = clock?.value
                    if (t != null) {
                        val phase = ((t * 2f) - index * 0.035f).mod(1f)
                        if (phase < 0.16f) {
                            val x = -size.width + (phase / 0.16f) * size.width * 3f
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.32f), Color.Transparent),
                                    start = Offset(x, 0f), end = Offset(x + size.width * 0.7f, size.height)
                                )
                            )
                        }
                    }
                    // A fine lit edge along the top.
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.0f)), endY = size.height * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()), style = Stroke(1.2.dp.toPx())
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedGlyph(glyph, Color.White, Modifier.size(34.dp), seed = glyph.ordinal * 11)
        }
        Text(
            label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = StillColors.Text,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 9.dp), maxLines = 2, minLines = 2, lineHeight = 15.sp
        )
    }
}

@Composable
private fun ProjectCard(p: ImageStore.Project, thumbnail: suspend (ImageStore.Project) -> Bitmap?, onOpen: () -> Unit, onForget: () -> Unit) {
    val image by produceState<Bitmap?>(null, p.id) { value = thumbnail(p) }
    val shape = RoundedCornerShape(22.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.width(150.dp).height(112.dp).pressScale(interaction, 0.97f).clip(shape).background(StillColors.Canvas)
            .border(1.dp, StillColors.Border, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
    ) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // The name on a dark glass strip over the bottom of the picture.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))).padding(10.dp)) {
            Text(p.name, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.file.width} × ${p.file.height}" + if (!p.recipe.isIdentity) " · " + stringResource(R.string.home_edited) else "", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
        }
        RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.home_remove_project), onClick = onForget, size = 26.dp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}
