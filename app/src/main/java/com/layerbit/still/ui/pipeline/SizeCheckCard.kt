package com.layerbit.still.ui.pipeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.FitSize
import com.layerbit.still.engine.WatermarkPlace
import com.layerbit.still.ui.components.Panel
import com.layerbit.still.ui.components.Pill
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.editor.Hint
import com.layerbit.still.ui.theme.Numerals
import com.layerbit.still.ui.theme.Neon
import com.layerbit.still.ui.theme.StillColors
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt

private val PreviewShape = RoundedCornerShape(12.dp)

/**
 * The first picture through the size steps for real: before and after under one divider, and
 * the bytes and pixels read off the file the trial wrote. Busy shows as a small ring; the last
 * answer stays on screen until the new one is in.
 */
@Composable
fun SizeCheckCard(check: SizeCheck, modifier: Modifier = Modifier) {
    Panel(modifier.fillMaxWidth().testTag("sizeCheck"), tint = Neon.Mint, inset = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(stringResource(R.string.size_check_title), modifier = Modifier.weight(1f), accent = Neon.Mint)
            BusyMark(check.busy)
        }
        val r = check.result
        if (r != null) {
            Comparison(r, Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        FitSize.sizeText(r.beforeBytes) + "  →  " + FitSize.sizeText(r.afterBytes),
                        style = Numerals.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), color = StillColors.Text, maxLines = 1
                    )
                    Text(
                        "${r.beforeSize.width} × ${r.beforeSize.height}  →  ${r.afterSize.width} × ${r.afterSize.height}",
                        style = Numerals, color = StillColors.Dim, maxLines = 1, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                val p = r.percent
                when {
                    p < 0 -> Pill(stringResource(R.string.size_check_smaller, abs(p)), StillColors.Done)
                    p > 0 -> Pill(stringResource(R.string.size_check_bigger, p), StillColors.Skipped)
                    else -> Pill(stringResource(R.string.size_check_same), StillColors.Muted)
                }
            }
        }
        check.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (check.skipped) StillColors.Muted else StillColors.Failed, modifier = Modifier.padding(top = 8.dp)) }
        if (r == null && check.problem == null) Hint(stringResource(R.string.size_check_working))
        Hint(stringResource(R.string.size_check_hint))
    }
}

/** A quiet ring while a trial runs; nothing at all when it is done. */
@Composable
private fun BusyMark(busy: Boolean) {
    AnimatedVisibility(busy, enter = fadeIn(), exit = fadeOut()) {
        CircularProgressIndicator(Modifier.size(16.dp), color = StillColors.Band, trackColor = StillColors.SurfaceDim, strokeWidth = 2.dp)
    }
}

/**
 * Before and after. One frame with a divider to drag when the shapes match, as a resize or a
 * new format does; side by side when a crop changed the shape, so neither is stretched.
 */
@Composable
private fun Comparison(r: SizeCheck.Result, modifier: Modifier = Modifier) {
    val beforeAspect = r.before.width.toFloat() / r.before.height
    val afterAspect = r.after.width.toFloat() / r.after.height
    if (abs(beforeAspect - afterAspect) / afterAspect < 0.02f) {
        var split by remember { mutableFloatStateOf(0.5f) }
        BoxWithConstraints(
            modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(afterAspect.coerceIn(0.6f, 2.2f), matchHeightConstraintsFirst = true)
                .clip(PreviewShape).background(StillColors.Canvas).border(1.dp, StillColors.Border, PreviewShape)
                .pointerInput(Unit) { detectTapGestures { split = (it.x / size.width).coerceIn(0f, 1f) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> split = (change.position.x / size.width).coerceIn(0f, 1f) } }
        ) {
            Image(r.before.asImageBitmap(), stringResource(R.string.size_check_before), Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Image(
                r.after.asImageBitmap(), stringResource(R.string.size_check_after),
                Modifier.fillMaxSize().drawWithContent { clipRect(left = size.width * split) { this@drawWithContent.drawContent() } },
                contentScale = ContentScale.FillBounds
            )
            Box(Modifier.offset { IntOffset(((maxWidth * split - 1.dp).toPx()).roundToInt(), 0) }.width(2.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)))
            Box(Modifier.offset { IntOffset((maxWidth * split - 11.dp).toPx().roundToInt(), (maxHeight / 2 - 11.dp).toPx().roundToInt()) }.size(22.dp).background(Color.White, CircleShape).border(2.dp, StillColors.Band, CircleShape))
            Corner(stringResource(R.string.size_check_before), Modifier.align(Alignment.TopStart))
            Corner(stringResource(R.string.size_check_after), Modifier.align(Alignment.TopEnd))
        }
        Hint(stringResource(R.string.size_check_drag))
    } else {
        Row(modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((bitmap, label) in listOf(r.before to stringResource(R.string.size_check_before), r.after to stringResource(R.string.size_check_after))) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(PreviewShape).background(StillColors.Canvas).border(1.dp, StillColors.Border, PreviewShape)) {
                    Image(bitmap.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Corner(label, Modifier.align(Alignment.TopStart))
                }
            }
        }
    }
}

@Composable
private fun Corner(label: String, modifier: Modifier) {
    Text(
        label, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(6.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** The watermark as the batch will draw it, on the first picture after every step. */
@Composable
fun WatermarkPreview(check: SizeCheck) {
    val r = check.result ?: return
    Box(
        Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(max = 220.dp)
            .aspectRatio((r.after.width.toFloat() / r.after.height).coerceIn(0.6f, 2.2f), matchHeightConstraintsFirst = true)
            .clip(PreviewShape).background(StillColors.Canvas).border(1.dp, StillColors.Border, PreviewShape).testTag("watermarkPreview")
    ) {
        Image(r.after.asImageBitmap(), stringResource(R.string.watermark_preview), Modifier.fillMaxSize().alpha(if (check.busy) 0.75f else 1f), contentScale = ContentScale.Fit)
        Corner(stringResource(R.string.watermark_preview), Modifier.align(Alignment.TopStart))
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { BusyMark(check.busy) }
    }
}

/** The five single spots, in reading order on the little picture. */
private val Spots = listOf(
    WatermarkPlace.TOP_LEFT to Alignment.TopStart, WatermarkPlace.TOP_RIGHT to Alignment.TopEnd, WatermarkPlace.CENTRE to Alignment.Center,
    WatermarkPlace.BOTTOM_LEFT to Alignment.BottomStart, WatermarkPlace.BOTTOM_RIGHT to Alignment.BottomEnd
)

@Composable
fun placeLabel(place: WatermarkPlace): String = stringResource(
    when (place) {
        WatermarkPlace.TILED -> R.string.watermark_place_tiled
        WatermarkPlace.TOP_LEFT -> R.string.watermark_place_top_left
        WatermarkPlace.TOP_RIGHT -> R.string.watermark_place_top_right
        WatermarkPlace.CENTRE -> R.string.watermark_place_centre
        WatermarkPlace.BOTTOM_LEFT -> R.string.watermark_place_bottom_left
        WatermarkPlace.BOTTOM_RIGHT -> R.string.watermark_place_bottom_right
    }
)

/**
 * Where the watermark goes, picked on a little picture: "All over" for the tiled grid, or one of
 * five spots for a single mark. The chosen name is written under it.
 */
@Composable
fun WatermarkPlacePicker(place: WatermarkPlace, onPick: (WatermarkPlace) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(stringResource(R.string.watermark_place), style = MaterialTheme.typography.labelLarge, color = StillColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            val tiled = place == WatermarkPlace.TILED
            val tiledLabel = placeLabel(WatermarkPlace.TILED)
            val tiledFill by animateColorAsState(if (tiled) StillColors.Band else StillColors.SurfaceDim, label = "tiled")
            Box(
                Modifier.size(width = 96.dp, height = 64.dp).clip(PreviewShape).background(tiledFill)
                    .border(1.dp, if (tiled) StillColors.Band else StillColors.Border, PreviewShape)
                    .clickable(role = Role.RadioButton) { onPick(WatermarkPlace.TILED) }
                    .semantics { contentDescription = tiledLabel; selected = tiled },
                contentAlignment = Alignment.Center
            ) {
                Text("©  ©  ©\n  ©  ©  ©\n©  ©  ©", style = Numerals, color = if (tiled) StillColors.OnBand else StillColors.Dim, modifier = Modifier.alpha(0.9f))
            }
            Box(
                Modifier.size(width = 96.dp, height = 64.dp).clip(PreviewShape).background(StillColors.SurfaceDim)
                    .border(1.dp, if (!tiled) StillColors.Band else StillColors.Border, PreviewShape).padding(4.dp)
            ) {
                for ((spot, align) in Spots) {
                    val on = place == spot
                    val label = placeLabel(spot)
                    Box(
                        Modifier.align(align).size(26.dp).clip(CircleShape).clickable(role = Role.RadioButton) { onPick(spot) }
                            .semantics { contentDescription = label; selected = on },
                        contentAlignment = Alignment.Center
                    ) {
                        val dot by animateColorAsState(if (on) StillColors.Band else StillColors.Border, label = "spot")
                        Box(Modifier.size(if (on) 14.dp else 9.dp).background(dot, CircleShape))
                    }
                }
            }
            Text(placeLabel(place), style = MaterialTheme.typography.bodyMedium, color = StillColors.Text, modifier = Modifier.weight(1f))
        }
    }
}
