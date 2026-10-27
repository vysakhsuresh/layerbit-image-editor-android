package com.layerbit.studio.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.studio.R
import com.layerbit.studio.brand.BrandLinks
import com.layerbit.studio.files.ImageStore
import com.layerbit.studio.ops.ToolId
import com.layerbit.studio.ui.components.IconTile
import com.layerbit.studio.ui.components.LivelyIcon
import com.layerbit.studio.ui.components.Panel
import com.layerbit.studio.ui.components.Pill
import com.layerbit.studio.ui.components.PrimaryButton
import com.layerbit.studio.ui.components.RoundIconButton
import com.layerbit.studio.ui.components.SecondaryButton
import com.layerbit.studio.ui.components.SectionHeading
import com.layerbit.studio.ui.icons.iconFor
import com.layerbit.studio.ui.theme.GroupAccents
import com.layerbit.studio.ui.theme.StudioColors

/**
 * The home board: everything the app does.
 *
 * The top is the two things people come for most - opening a picture and the pictures they
 * were in the middle of. Below that, every tool, grouped by what someone is trying to do and
 * coloured by group. Groups are headings, not navigation: nothing hides behind a tap. The
 * search finds tools by what they say they do; a long press stars one to the top.
 */
@Composable
fun HomeScreen(
    projects: List<ImageStore.Project>,
    thumbnail: suspend (ImageStore.Project) -> Bitmap?,
    favourites: Set<String>,
    recentTools: List<ToolId>,
    onOpenPicture: () -> Unit,
    onOpenProject: (ImageStore.Project) -> Unit,
    onForgetProject: (ImageStore.Project) -> Unit,
    onTool: (ToolId) -> Unit,
    onToggleFavourite: (ToolId) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { toolsMatching(query) }
    val starred = remember(favourites) { ToolId.entries.filter { it.name in favourites } }

    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("homeList"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "hero") { Hero(onSettings, onAbout) }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(stringResource(R.string.home_open_picture), iconRes = R.drawable.ic_tool_edit, onClick = onOpenPicture, modifier = Modifier.weight(1.3f).testTag("openPhotos"))
                SecondaryButton(stringResource(R.string.home_under_kb), iconRes = R.drawable.ic_tool_resize_kb, onClick = { onTool(ToolId.RESIZE_KB) }, modifier = Modifier.weight(1f))
            }
        }
        item(key = "search") { SearchField(query) { query = it } }

        if (query.isNotBlank()) {
            if (matches.isEmpty()) item { Text(stringResource(R.string.home_no_match), style = MaterialTheme.typography.bodyMedium, color = StudioColors.Muted, modifier = Modifier.padding(top = 8.dp)) }
            else item(key = "results") {
                SectionHeading(pluralStringResource(R.plurals.app_tool_count, matches.size, matches.size), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                ToolCards(matches, favourites, onTool, onToggleFavourite)
            }
        } else {
            if (projects.isNotEmpty()) item(key = "projects") {
                SectionHeading(stringResource(R.string.home_pick_up), modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(projects, key = { it.id }) { p -> ProjectCard(p, thumbnail, onOpen = { onOpenProject(p) }, onForget = { onForgetProject(p) }) }
                }
            }
            if (recentTools.isNotEmpty()) item(key = "recent-tools") {
                SectionHeading(stringResource(R.string.home_recent_tools), modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recentTools, key = { it.name }) { tool ->
                        Panel(onClick = { onTool(tool) }, inset = 10.dp, tint = GroupAccents.of(tool.group)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(iconRes = iconFor(tool), accent = GroupAccents.of(tool.group), size = 28.dp)
                                Text(tool.title, style = MaterialTheme.typography.bodyMedium, color = StudioColors.Text, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            if (starred.isNotEmpty()) item(key = "favourites") {
                SectionHeading(stringResource(R.string.home_yours), accent = StudioColors.Band, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                ToolCards(starred, favourites, onTool, onToggleFavourite)
            }
            ToolId.Group.entries.forEach { group ->
                val tools = ToolId.entries.filter { it.group == group }
                item(key = "group-${group.name}") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)) {
                        SectionHeading(group.title, accent = GroupAccents.of(group), modifier = Modifier.weight(1f))
                        Text("${tools.size}", style = MaterialTheme.typography.labelSmall, color = StudioColors.Dim)
                    }
                    ToolCards(tools, favourites, onTool, onToggleFavourite)
                }
            }
        }
        item(key = "footer") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.home_pin_hint), style = MaterialTheme.typography.bodySmall, color = StudioColors.Dim)
                TextButton(onClick = onAbout) { Text(stringResource(R.string.home_footer, BrandLinks.BRAND_LABEL), style = MaterialTheme.typography.bodySmall, color = StudioColors.Dim) }
            }
        }
    }
}

@Composable
private fun Hero(onSettings: () -> Unit, onAbout: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(StudioColors.Band.copy(alpha = if (StudioColors.isDark) 0.16f else 0.10f), Color.Transparent)), RoundedCornerShape(24.dp))
            .padding(top = 6.dp, bottom = 10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(stringResource(R.string.app_name_short), style = MaterialTheme.typography.displayMedium, color = StudioColors.Text)
                    Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium, color = StudioColors.Muted)
                }
                RoundIconButton(icon = Icons.Filled.Settings, contentDescription = stringResource(R.string.app_settings), onClick = onSettings)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(icon = Icons.Filled.Info, contentDescription = stringResource(R.string.app_about), onClick = onAbout)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp, start = 4.dp)) {
                Pill(stringResource(R.string.app_pill_free), StudioColors.Done)
                Pill(stringResource(R.string.app_pill_no_internet), StudioColors.Running)
                Pill(pluralStringResource(R.plurals.app_tool_count, ToolId.entries.size, ToolId.entries.size), StudioColors.Band)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).background(StudioColors.Surface, shape)
            .border(1.dp, if (query.isNotBlank()) StudioColors.Band.copy(alpha = 0.7f) else StudioColors.Border, shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LivelyIcon(Icons.Filled.Search, contentDescription = null, tint = StudioColors.Dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.home_search_hint), style = MaterialTheme.typography.bodyMedium, color = StudioColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(query, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioColors.Text), cursorBrush = SolidColor(StudioColors.Band), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotBlank()) RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.app_clear), onClick = { onChange("") }, size = 28.dp)
    }
}

/** Title, summary, the verb and the group are all matched, so "shrink" finds Compress. */
private fun toolsMatching(query: String): List<ToolId> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    fun score(tool: ToolId): Int {
        val title = tool.title.lowercase()
        val haystack = "$title ${tool.summary.lowercase()} ${tool.action.lowercase()} ${tool.group.title.lowercase()}"
        return when {
            title.startsWith(needle) -> 3
            title.contains(needle) -> 2
            haystack.split(' ', ',', '-', '.').any { it.startsWith(needle) } -> 1
            haystack.contains(needle) -> 1
            else -> 0
        }
    }
    return ToolId.entries.map { it to score(it) }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<ToolId, Int>> { it.second }.thenBy { it.first.title }).map { it.first }
}

@Composable
private fun ToolCards(tools: List<ToolId>, favourites: Set<String>, onTool: (ToolId) -> Unit, onToggleFavourite: (ToolId) -> Unit) {
    val columns = toolColumns()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        tools.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { tool -> ToolCard(tool, tool.name in favourites, { onTool(tool) }, { onToggleFavourite(tool) }, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun toolColumns(): Int {
    val width = LocalConfiguration.current.screenWidthDp
    return when { width >= 840 -> 4; width >= 600 -> 3; else -> 2 }
}

@Composable
private fun ToolCard(tool: ToolId, starred: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = GroupAccents.of(tool.group)
    Panel(modifier = modifier.heightIn(min = 156.dp), onClick = onClick, onLongClick = onLongClick, highlighted = starred, inset = 14.dp) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            IconTile(iconRes = iconFor(tool), accent = accent)
            Spacer(Modifier.weight(1f))
            if (starred) LivelyIcon(Icons.Filled.Star, contentDescription = stringResource(R.string.home_pinned), tint = StudioColors.BandBright, modifier = Modifier.size(16.dp))
        }
        Text(tool.title, style = MaterialTheme.typography.titleMedium, color = StudioColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        Text(tool.summary, style = MaterialTheme.typography.bodySmall, color = StudioColors.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A picture somebody was editing, with its thumbnail. */
@Composable
private fun ProjectCard(p: ImageStore.Project, thumbnail: suspend (ImageStore.Project) -> Bitmap?, onOpen: () -> Unit, onForget: () -> Unit) {
    val image by produceState<Bitmap?>(null, p.id) { value = thumbnail(p) }
    Panel(modifier = Modifier.width(180.dp), onClick = onOpen, inset = 10.dp) {
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(StudioColors.Canvas)) {
            image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Crop) }
            RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.home_remove_project), onClick = onForget, size = 26.dp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(p.name, style = MaterialTheme.typography.bodyMedium, color = StudioColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text("${p.file.width} × ${p.file.height}" + if (!p.recipe.isIdentity) " · " + stringResource(R.string.home_edited) else "", style = MaterialTheme.typography.labelSmall, color = StudioColors.Dim)
    }
}
