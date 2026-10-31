package com.layerbit.still.ui.pages

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
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.still.R
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ui.components.AmbientBackdrop
import com.layerbit.still.ui.components.Panel
import com.layerbit.still.ui.components.rise
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
fun ToolsScreen(onTool: (ToolId) -> Unit, modifier: Modifier = Modifier, recent: List<String> = emptyList()) {
    val groups = ToolId.Group.entries
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember { ToolId.entries.filter { it != ToolId.PROCESS } }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    // Every word must appear somewhere in what the tool is called or does.
    val matches = if (words.isEmpty()) emptyList() else all.filter { t ->
        val hay = (t.title + " " + t.summary + " " + t.action + " " + t.group.title + " " + searchWords(t)).lowercase()
        words.all { it in hay }
    }
    val recentTools = recent.mapNotNull { ToolId.byName(it) }.filter { it in all }.take(4)
    Box(modifier.fillMaxSize()) {
    AmbientBackdrop(strength = 0.7f)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("toolsList"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "search") {
            OutlinedTextField(
                query, { query = it.take(40) },
                placeholder = { Text(stringResource(R.string.home_search_hint), color = StillColors.Dim) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = StillColors.Muted) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tools_search_clear), tint = StillColors.Muted) } },
                singleLine = true, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().testTag("toolsSearch"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StillColors.Band, unfocusedBorderColor = StillColors.Border,
                    focusedContainerColor = StillColors.Surface.copy(alpha = 0.6f), unfocusedContainerColor = StillColors.Surface.copy(alpha = 0.4f),
                    cursorColor = StillColors.Band, focusedTextColor = StillColors.Text, unfocusedTextColor = StillColors.Text
                )
            )
        }
        if (words.isNotEmpty()) {
            if (matches.isEmpty()) item(key = "none") { Text(stringResource(R.string.home_no_match), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(top = 10.dp)) }
            items(matches.size, key = { "m-" + matches[it].name }) { i -> ToolRow(matches[i], i, onTool) }
            return@LazyColumn
        }
        item { Text(stringResource(R.string.tools_page_intro), style = MaterialTheme.typography.bodyMedium, color = StillColors.Muted, modifier = Modifier.padding(bottom = 6.dp)) }
        if (recentTools.isNotEmpty()) {
            item(key = "g-recent") { SectionHeading(stringResource(R.string.home_recent_tools), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) }
            items(recentTools.size, key = { "r-" + recentTools[it].name }) { i -> ToolRow(recentTools[i], i, onTool) }
        }
        for (group in groups) {
            val tools = all.filter { it.group == group }
            if (tools.isEmpty()) continue
            item(key = "g-" + group.name) { SectionHeading(group.title, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
            items(tools.size, key = { "t-" + tools[it].name }) { i -> ToolRow(tools[i], i, onTool) }
        }
    }
    }
}

/** One tool: its moving icon in the group's colour, its name and one line on what it does. */
@Composable
private fun ToolRow(tool: ToolId, index: Int, onTool: (ToolId) -> Unit) {
    val accent = GroupAccents.of(tool.group)
    Panel(modifier = rise(index).fillMaxWidth(), onClick = { onTool(tool) }, inset = 12.dp) {
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

/** The everyday words people search with that a tool's name may not contain: "shrink", "bg", "gps". */
private fun searchWords(tool: ToolId): String {
    val res = when (tool) {
        ToolId.CROP -> R.string.tool_words_crop
        ToolId.PERSPECTIVE -> R.string.tool_words_perspective
        ToolId.LIGHT -> R.string.tool_words_light
        ToolId.COLOUR -> R.string.tool_words_colour
        ToolId.CURVES -> R.string.tool_words_curves
        ToolId.LOOKS -> R.string.tool_words_looks
        ToolId.DETAIL -> R.string.tool_words_detail
        ToolId.MARKUP -> R.string.tool_words_markup
        ToolId.RETOUCH -> R.string.tool_words_retouch
        ToolId.FRAME -> R.string.tool_words_frame
        ToolId.WATERMARK -> R.string.tool_words_watermark
        ToolId.RESIZE -> R.string.tool_words_resize
        ToolId.RESIZE_KB -> R.string.tool_words_resize_kb
        ToolId.COMPRESS -> R.string.tool_words_compress
        ToolId.CONVERT -> R.string.tool_words_convert
        ToolId.STRIP_METADATA -> R.string.tool_words_strip_metadata
        ToolId.DETAILS -> R.string.tool_words_details
        ToolId.ENHANCE -> R.string.tool_words_enhance
        ToolId.CUTOUT -> R.string.tool_words_cutout
        ToolId.SIGNATURE -> R.string.tool_words_signature
        ToolId.STICKER -> R.string.tool_words_sticker
        ToolId.SCAN -> R.string.tool_words_scan
        ToolId.SCAN_PDF -> R.string.tool_words_scan_pdf
        ToolId.RED_EYE -> R.string.tool_words_red_eye
        ToolId.WHITE_BALANCE -> R.string.tool_words_white_balance
        ToolId.PASSPORT -> R.string.tool_words_passport
        ToolId.COLLAGE -> R.string.tool_words_collage
        ToolId.APPLY_RECIPE -> R.string.tool_words_apply_recipe
        else -> return ""
    }
    return com.layerbit.still.text.Strings.get(res)
}
