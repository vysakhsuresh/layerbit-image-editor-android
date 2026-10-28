package com.layerbit.still.ui.intro

import com.layerbit.still.ui.theme.Neon
import com.layerbit.still.ui.icons.Glyph
import com.layerbit.still.ui.icons.AnimatedGlyph
import com.layerbit.still.ui.components.AmbientBackdrop
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.theme.StillColors
import kotlinx.coroutines.launch

private data class IntroCard(val glyph: Glyph, @StringRes val title: Int, @StringRes val body: Int)

private val cards = listOf(
    IntroCard(Glyph.LOCK, R.string.intro_offline_title, R.string.intro_offline_body),
    IntroCard(Glyph.EDIT, R.string.intro_original_title, R.string.intro_original_body),
    IntroCard(Glyph.RESIZE_KB, R.string.intro_free_title, R.string.intro_free_body)
)

/** Three full-bleed cards, once: offline, originals untouched, free. Each wears its own colour. Skip is on every card. */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == cards.lastIndex
    val accents = listOf(Neon.Violet, Neon.Mint, Neon.Coral)
    val accent by animateColorAsState(accents[pager.currentPage], label = "introAccent")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.55f), StillColors.Background)))) {
        AmbientBackdrop(strength = 0.6f)
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.intro_skip), color = Color.White, style = MaterialTheme.typography.titleMedium) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                val card = cards[index]
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center) {
                    Box(
                        Modifier.size(104.dp).clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = 0.22f))
                            .drawBehind { drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.4f), Color.Transparent), center = Offset(size.width * 0.3f, size.height * 0.25f), radius = size.width * 0.6f), size.width * 0.6f, Offset(size.width * 0.3f, size.height * 0.25f)) },
                        contentAlignment = Alignment.Center
                    ) { AnimatedGlyph(card.glyph, Color.White, Modifier.size(60.dp), seed = index * 13) }
                    Text(stringResource(card.title), style = MaterialTheme.typography.displayMedium, color = Color.White, modifier = Modifier.padding(top = 30.dp).widthIn(max = 520.dp))
                    Text(stringResource(card.body), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.86f), modifier = Modifier.padding(top = 14.dp).widthIn(max = 520.dp))
                }
            }
            val position = stringResource(R.string.intro_position, pager.currentPage + 1, cards.size)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 20.dp).semantics { contentDescription = position }) {
                cards.indices.forEach { i ->
                    val active = i == pager.currentPage
                    val width by animateDpAsState(if (active) 26.dp else 8.dp, label = "dot")
                    val colour by animateColorAsState(if (active) Color.White else Color.White.copy(alpha = 0.4f), label = "dot")
                    Box(Modifier.height(8.dp).size(width = width, height = 8.dp).background(colour, CircleShape))
                }
            }
            PrimaryButton(stringResource(if (last) R.string.intro_start else R.string.intro_next), onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp))
            Spacer(Modifier.height(8.dp))
        }
    }
}
