package com.layerbit.still.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.engine.Frame
import com.layerbit.still.engine.FrameStyle
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.components.pressScale
import com.layerbit.still.ui.theme.StillColors
import kotlinx.coroutines.delay

/** No frame, the plain one, then every designed style, each shown on the picture itself. */
@Composable
fun FrameStyleStrip(frame: Frame, thumbs: Map<FrameStyle?, Bitmap>, onSelect: (FrameStyle?) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val order: List<FrameStyle?> = remember { listOf<FrameStyle?>(null) + FrameStyle.entries }
    val selected: FrameStyle? = if (frame.isIdentity) null else frame.style
    SectionHeading(stringResource(R.string.frame_styles), modifier = Modifier.padding(bottom = 6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("frameStrip")) {
        items(order.size, key = { order[it]?.name ?: "NONE" }) { i ->
            val style = order[i]
            val active = style == selected
            val edge by animateColorAsState(if (active) StillColors.Band else StillColors.Border, label = "frameEdge")
            val interaction = remember { MutableInteractionSource() }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).pressScale(interaction, 0.95f)
                .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton) { onSelect(style) }) {
                Box(Modifier.size(72.dp).clip(shape).background(StillColors.SurfaceDim).border(if (active) 2.5.dp else 1.dp, edge, shape), contentAlignment = Alignment.Center) {
                    val bmp = thumbs[style]
                    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp).testTag("frameThumb"))
                }
                Text(frameStyleLabel(style), style = MaterialTheme.typography.labelMedium, color = if (active) StillColors.Text else StillColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun frameStyleLabel(style: FrameStyle?): String = stringResource(
    when (style) {
        null -> R.string.frame_transparent
        FrameStyle.PLAIN -> R.string.frame_style_plain; FrameStyle.POLAROID -> R.string.frame_style_polaroid; FrameStyle.INSTANT -> R.string.frame_style_instant
        FrameStyle.FILM -> R.string.frame_style_film; FrameStyle.SLIDE -> R.string.frame_style_slide; FrameStyle.GALLERY -> R.string.frame_style_gallery
        FrameStyle.LINE -> R.string.frame_style_line; FrameStyle.MAGAZINE -> R.string.frame_style_magazine; FrameStyle.SHADOW -> R.string.frame_style_shadow
        FrameStyle.FADE -> R.string.frame_style_fade; FrameStyle.TORN -> R.string.frame_style_torn; FrameStyle.BRUSH -> R.string.frame_style_brush
        FrameStyle.STAMP -> R.string.frame_style_stamp; FrameStyle.NEON -> R.string.frame_style_neon; FrameStyle.WOOD -> R.string.frame_style_wood
        FrameStyle.MARBLE -> R.string.frame_style_marble; FrameStyle.LINEN -> R.string.frame_style_linen
    }
)

/** The names of a style's variants, in order. */
@Composable
private fun variantLabels(style: FrameStyle): List<String> = when (style) {
    FrameStyle.FILM -> listOf(stringResource(R.string.frame_black), stringResource(R.string.frame_variant_amber))
    FrameStyle.INSTANT -> listOf(stringResource(R.string.frame_variant_paper_tape), stringResource(R.string.frame_variant_clear_tape))
    FrameStyle.SLIDE -> listOf(stringResource(R.string.frame_white), stringResource(R.string.frame_variant_card), stringResource(R.string.frame_black))
    FrameStyle.GALLERY -> listOf(stringResource(R.string.frame_black), stringResource(R.string.frame_from_picture), stringResource(R.string.frame_variant_gold))
    FrameStyle.MAGAZINE -> listOf(stringResource(R.string.frame_ink), stringResource(R.string.frame_from_picture))
    FrameStyle.WOOD -> listOf(stringResource(R.string.frame_variant_oak), stringResource(R.string.frame_variant_walnut), stringResource(R.string.frame_variant_ash))
    FrameStyle.MARBLE -> listOf(stringResource(R.string.frame_white), stringResource(R.string.frame_black), stringResource(R.string.frame_variant_green))
    FrameStyle.LINEN -> listOf(stringResource(R.string.frame_variant_natural), stringResource(R.string.frame_variant_grey), stringResource(R.string.frame_variant_navy))
    else -> emptyList()
}

/** A designed frame's own controls: weight, its variants, words, a new seed, its colours, padding and shape. */
@Composable
fun FrameStyleControls(s: PanelScope, swatches: List<Int>) {
    val f = s.recipe.frame
    val style = f.style
    fun set(block: (Frame) -> Frame) = s.change { it.copy(frame = block(it.frame)) }
    fun live(block: (Frame) -> Frame) = s.draft(s.recipe.copy(frame = block(s.recipe.frame)))
    Knob(stringResource(R.string.frame_thickness), f.thickness, 0f..1f, onChange = { v -> live { it.copy(thickness = v) } }, onDone = s.commit, onReset = { set { it.copy(thickness = 0.5f) } }, haptics = s.haptics)
    val variants = variantLabels(style)
    if (variants.size == style.variants && variants.size > 1) {
        ChipRow(variants.indices.map { it to variants[it] }, f.variant, { v -> set { it.copy(variant = v) } }, Modifier.padding(vertical = 4.dp))
    }
    if (style == FrameStyle.NEON) {
        SectionHeading(stringResource(R.string.frame_glow), modifier = Modifier.padding(top = 6.dp))
        ChipRow(listOf(0 to stringResource(R.string.frame_from_picture), 1 to stringResource(R.string.frame_glow_chosen)), f.variant, { v -> set { it.copy(variant = v) } }, Modifier.padding(vertical = 4.dp))
        ColourRow(swatches + InkColours, if (f.variant == 1) f.borderColor else -1, { c -> set { it.copy(borderColor = c, variant = 1) } }, Modifier.padding(top = 4.dp))
    }
    if (style.caption) CaptionField(s, f)
    if (style.seeded) SecondaryButton(stringResource(R.string.frame_shuffle), onClick = { set { it.copy(seed = it.seed + 1) } }, modifier = Modifier.padding(top = 6.dp), tall = false)
    if (style.paper) {
        SectionHeading(stringResource(R.string.frame_paper_colour), modifier = Modifier.padding(top = 8.dp))
        ColourRow(swatches + listOf(0xFFFBFAF6.toInt(), 0xFFF5F0E6.toInt(), 0xFFE9E4DA.toInt(), 0xFF1C1C1E.toInt()) + InkColours, f.borderColor, { c -> set { it.copy(borderColor = c) } }, Modifier.padding(top = 4.dp))
    }
    if (style == FrameStyle.SHADOW) {
        Knob(stringResource(R.string.frame_corners), f.cornerRadius, 0f..0.3f, onChange = { v -> live { it.copy(cornerRadius = v) } }, onDone = s.commit, onReset = { set { it.copy(cornerRadius = 0f) } }, haptics = s.haptics)
    }
    Knob(stringResource(R.string.frame_padding), f.padding, 0f..0.3f, onChange = { v -> live { it.copy(padding = v) } }, onDone = s.commit, onReset = { set { it.copy(padding = 0f) } }, haptics = s.haptics)
    val aspects = listOf(0f to stringResource(R.string.crop_original), 1f to "1:1", 4f / 5f to "4:5", 16f / 9f to "16:9", 9f / 16f to "9:16", 3f / 2f to "3:2")
    SectionHeading(stringResource(R.string.frame_pad_to), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    ChipRow(aspects, f.aspect, { a -> set { it.copy(aspect = a) } })
    if (style.showsBackground || f.padding > 0f || f.aspect > 0f) {
        SectionHeading(stringResource(R.string.frame_background), modifier = Modifier.padding(top = 8.dp))
        ColourRow(
            swatches + listOf(0xFFFFFFFF.toInt(), 0xFFEDEBE6.toInt(), 0xFF2A2A2E.toInt(), 0xFF0B0B12.toInt(), 0xFF111111.toInt()),
            if (f.blurredFill) -1 else f.background, { c -> set { it.copy(background = c, blurredFill = false) } }, Modifier.padding(top = 4.dp)
        )
        ChipRow(listOf(false to stringResource(R.string.frame_flat), true to stringResource(R.string.frame_blurred)), f.blurredFill, { b -> set { it.copy(blurredFill = b) } }, Modifier.padding(top = 6.dp))
    }
    Hint(stringResource(R.string.frame_style_hint))
}

/** Words under the picture: drawn live as they are typed, kept as one step once typing pauses. */
@Composable
private fun CaptionField(s: PanelScope, f: Frame) {
    var text by remember(f.style) { mutableStateOf(f.caption) }
    var dirty by remember { mutableStateOf(false) }
    OutlinedTextField(
        text, { v ->
            text = v.replace('\n', ' ').take(Frame.MAX_CAPTION); dirty = true
            s.draft(s.recipe.copy(frame = s.recipe.frame.copy(caption = text)))
        },
        label = { Text(stringResource(R.string.frame_caption)) }, singleLine = true, colors = fieldColours(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("frameCaption")
    )
    LaunchedEffect(text, dirty) {
        if (dirty) { delay(700); s.commit(); dirty = false }
    }
}
