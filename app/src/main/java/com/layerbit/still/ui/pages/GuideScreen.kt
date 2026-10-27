package com.layerbit.still.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.ui.components.IconTile
import com.layerbit.still.ui.components.Panel
import com.layerbit.still.ui.theme.StillColors

/** How the app works, in the order a first day goes: open, edit, process many, the harder tools, saving, privacy. */
@Composable
fun GuideScreen(modifier: Modifier = Modifier) {
    val sections = listOf(
        Triple(R.drawable.ic_plus, R.string.guide_open_title, R.string.guide_open_body),
        Triple(R.drawable.ic_tool_edit, R.string.guide_edit_title, R.string.guide_edit_body),
        Triple(R.drawable.ic_tool_recipe, R.string.guide_batch_title, R.string.guide_batch_body),
        Triple(R.drawable.ic_tool_cutout, R.string.guide_cutout_title, R.string.guide_cutout_body),
        Triple(R.drawable.ic_tool_scan, R.string.guide_scan_title, R.string.guide_scan_body),
        Triple(R.drawable.ic_tool_resize_kb, R.string.guide_size_title, R.string.guide_size_body),
        Triple(R.drawable.ic_tool_export, R.string.guide_save_title, R.string.guide_save_body),
        Triple(R.drawable.ic_lock, R.string.guide_privacy_title, R.string.guide_privacy_body)
    )
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("guideList"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(bottom = 4.dp)) }
        items(sections.size) { i ->
            val (icon, title, body) = sections[i]
            Panel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = icon, accent = StillColors.Band)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.guide_outro), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, modifier = Modifier.padding(top = 6.dp)) }
    }
}
