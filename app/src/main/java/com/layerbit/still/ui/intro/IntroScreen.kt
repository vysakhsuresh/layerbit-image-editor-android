package com.layerbit.still.ui.intro

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
import com.layerbit.still.ui.components.IconTile
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.theme.StillColors
import kotlinx.coroutines.launch

private data class IntroCard(val iconRes: Int, @StringRes val title: Int, @StringRes val body: Int)

private val cards = listOf(
    IntroCard(R.drawable.ic_lock, R.string.intro_offline_title, R.string.intro_offline_body),
    IntroCard(R.drawable.ic_tool_edit, R.string.intro_original_title, R.string.intro_original_body),
    IntroCard(R.drawable.ic_tool_resize_kb, R.string.intro_free_title, R.string.intro_free_body)
)

/** Three cards, once: offline, originals untouched, free. Skip is on every card. */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == cards.lastIndex
    Column(modifier = Modifier.fillMaxSize().background(StillColors.Background).padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.intro_skip), color = StillColors.Muted) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            val card = cards[index]
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                IconTile(iconRes = card.iconRes, accent = StillColors.Band, size = 88.dp, filled = true)
                Text(stringResource(card.title), style = MaterialTheme.typography.headlineSmall, color = StillColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp).widthIn(max = 480.dp))
                Text(stringResource(card.body), style = MaterialTheme.typography.bodyLarge, color = StillColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp).widthIn(max = 480.dp))
            }
        }
        val position = stringResource(R.string.intro_position, pager.currentPage + 1, cards.size)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 20.dp).semantics { contentDescription = position }) {
            cards.indices.forEach { i ->
                val active = i == pager.currentPage
                val width by animateDpAsState(if (active) 22.dp else 8.dp, label = "dot")
                val colour by animateColorAsState(if (active) StillColors.Band else StillColors.BorderStrong, label = "dot")
                Box(Modifier.height(8.dp).size(width = width, height = 8.dp).background(colour, CircleShape))
            }
        }
        PrimaryButton(stringResource(if (last) R.string.intro_start else R.string.intro_next), onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp))
        Spacer(Modifier.height(8.dp))
    }
}
