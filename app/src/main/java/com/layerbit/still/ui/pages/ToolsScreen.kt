package com.layerbit.still.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ui.components.Panel
import com.layerbit.still.ui.components.Pill
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.icons.AnimatedGlyph
import com.layerbit.still.ui.icons.glyphFor
import com.layerbit.still.ui.icons.iconFor
import com.layerbit.still.ui.theme.GroupAccents
import com.layerbit.still.ui.theme.StillColors

/**
 * Every tool, by what it is for, each with its moving icon and one line on what it does. A tap
 * opens it: one-picture tools ask for the picture; batch tools open the steps with theirs on.
 */
@Composable
fun ToolsScreen(onTool: (ToolId) -> Unit, modifier: Modifier = Modifier) {
    val groups = ToolId.Group.entries
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("toolsList"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text(stringResource(R.string.tools_page_intro), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(bottom = 6.dp)) }
        for (group in groups) {
            val tools = ToolId.entries.filter { it.group == group && it != ToolId.PROCESS }
            if (tools.isEmpty()) continue
            item(key = "g-" + group.name) { SectionHeading(group.title, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
            items(tools.size, key = { "t-" + tools[it].name }) { i ->
                val tool = tools[i]
                val accent = GroupAccents.of(group)
                Panel(modifier = Modifier.fillMaxWidth(), onClick = { onTool(tool) }, inset = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            val glyph = glyphFor(iconFor(tool))
                            if (glyph != null) AnimatedGlyph(glyph, accent, Modifier.size(30.dp), seed = tool.ordinal * 7)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(tool.title, style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                            Text(tool.summary, style = MaterialTheme.typography.bodySmall, color = StillColors.Dim)
                        }
                        Pill(stringResource(if (tool.mode == ToolId.Mode.EDITOR) R.string.tools_page_one else R.string.tools_page_many), if (tool.mode == ToolId.Mode.EDITOR) StillColors.Running else StillColors.Done)
                    }
                }
            }
        }
    }
}
