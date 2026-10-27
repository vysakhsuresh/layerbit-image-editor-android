package com.layerbit.still.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.layerbit.still.R
import com.layerbit.still.brand.BrandLinks
import com.layerbit.still.files.CrashLog
import com.layerbit.still.image.Budget
import com.layerbit.still.image.OutputFormat
import com.layerbit.still.prefs.Settings
import com.layerbit.still.prefs.ThemeChoice
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.components.Divider
import com.layerbit.still.ui.components.IconTile
import com.layerbit.still.ui.components.Panel
import com.layerbit.still.ui.components.Pill
import com.layerbit.still.ui.components.RowBetween
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.ToggleRow
import com.layerbit.still.ui.components.formatBytes
import com.layerbit.still.ui.theme.StillColors

/** Everything about the app the user gets to decide. Every switch says what it costs. */
@Composable
fun SettingsScreen(
    settings: Settings,
    workspaceBytes: Long,
    projectBytes: Long,
    projectCount: Int,
    onChange: ((Settings) -> Settings) -> Unit,
    onClearWorkspace: () -> Unit,
    onClearProjects: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section(stringResource(R.string.settings_appearance), R.drawable.ic_tool_looks, Color(0xFFB08CFF)) {
                ThemePicker(settings.theme) { choice -> onChange { it.copy(theme = choice) } }
                ToggleRow(stringResource(R.string.settings_lively), settings.livelyIcons, { on -> onChange { it.copy(livelyIcons = on) } }, detail = stringResource(R.string.settings_lively_detail))
                ToggleRow(stringResource(R.string.settings_histogram), settings.showHistogram, { on -> onChange { it.copy(showHistogram = on) } }, detail = stringResource(R.string.settings_histogram_detail))
                ToggleRow(stringResource(R.string.settings_haptics), settings.haptics, { on -> onChange { it.copy(haptics = on) } }, detail = stringResource(R.string.settings_haptics_detail))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val context = LocalContext.current
                    Divider()
                    RowBetween(Modifier.padding(top = 8.dp)) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge, color = StillColors.Text)
                            Text(stringResource(R.string.settings_language_detail), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim)
                        }
                        SecondaryButton(stringResource(R.string.settings_language_choose), onClick = {
                            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS).setData(android.net.Uri.fromParts("package", context.packageName, null))) }
                        }, tall = false)
                    }
                }
            }
        }
        item {
            Section(stringResource(R.string.settings_export), R.drawable.ic_tool_export, Color(0xFF3ED3C0)) {
                Text(stringResource(R.string.settings_default_format), style = MaterialTheme.typography.bodyLarge, color = StillColors.Text)
                ChipRow(OutputFormat.entries.filter { it.available }.map { it to it.label }, settings.defaultFormat, { f -> onChange { it.copy(defaultFormat = f) } }, Modifier.padding(top = 6.dp, bottom = 8.dp))
                Divider()
                ToggleRow(stringResource(R.string.settings_keep_camera), settings.keepCameraFields, { on -> onChange { it.copy(keepCameraFields = on) } }, detail = stringResource(R.string.settings_keep_camera_detail))
                Divider()
                ToggleRow(stringResource(R.string.settings_ask_save), settings.askWhereToSave, { on -> onChange { it.copy(askWhereToSave = on) } }, detail = stringResource(R.string.settings_ask_save_detail))
            }
        }
        item {
            Section(stringResource(R.string.settings_space), R.drawable.ic_tool_compress, Color(0xFF5B9DFF)) {
                RowBetween {
                    Column {
                        Text(formatBytes(workspaceBytes), style = MaterialTheme.typography.headlineSmall, color = StillColors.Text)
                        Text(stringResource(R.string.settings_results), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim)
                    }
                    SecondaryButton(stringResource(R.string.app_clear), onClick = onClearWorkspace, enabled = workspaceBytes > 0, tall = false)
                }
                Text(stringResource(R.string.settings_results_detail), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, modifier = Modifier.padding(top = 8.dp))
                Divider()
                RowBetween(Modifier.padding(top = 8.dp)) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(pluralStringResource(R.plurals.settings_project_count, projectCount, projectCount, formatBytes(projectBytes)), style = MaterialTheme.typography.bodyLarge, color = StillColors.Text)
                        Text(stringResource(R.string.settings_projects_detail), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim)
                    }
                    SecondaryButton(stringResource(R.string.app_clear), onClick = onClearProjects, enabled = projectCount > 0, tall = false)
                }
                Divider()
                Text(stringResource(R.string.settings_budget, Budget.exportPixels / 1_000_000, Runtime.getRuntime().maxMemory() / (1024 * 1024)), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item { CrashLogSection() }
        item {
            Panel(modifier = Modifier.fillMaxWidth(), tint = StillColors.Done) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = R.drawable.ic_lock, accent = StillColors.Done, size = 40.dp)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.settings_cannot), style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Pill(stringResource(R.string.app_pill_no_internet), StillColors.Done)
                            Pill(stringResource(R.string.app_pill_no_account), StillColors.Done)
                            Pill(stringResource(R.string.app_pill_no_tracking), StillColors.Done)
                        }
                        Text(stringResource(R.string.settings_cannot_detail), style = MaterialTheme.typography.bodySmall, color = StillColors.Muted, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashLogSection() {
    val context = LocalContext.current
    var count by remember { mutableIntStateOf(CrashLog.count(context)) }
    Section(stringResource(R.string.settings_crash_log), R.drawable.ic_tool_details, StillColors.Failed) {
        Text(if (count == 0) stringResource(R.string.settings_crash_none) else pluralStringResource(R.plurals.settings_crash_count, count, count), style = MaterialTheme.typography.bodyLarge, color = StillColors.Text)
        Text(stringResource(R.string.settings_crash_detail), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim)
        if (count > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                SecondaryButton(stringResource(R.string.settings_crash_send), onClick = {
                    val log = CrashLog.latest(context) ?: return@SecondaryButton
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", log)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_EMAIL, arrayOf(BrandLinks.SUPPORT_EMAIL))
                        putExtra(Intent.EXTRA_SUBJECT, "Still (Layerbit) crash log"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_crash_chooser))) }
                }, tall = false)
                SecondaryButton(stringResource(R.string.app_delete), onClick = { CrashLog.clear(context); count = 0 }, tall = false)
            }
        }
    }
}

@Composable
private fun Section(title: String, iconRes: Int, accent: Color, content: @Composable () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            IconTile(iconRes = iconRes, accent = accent, size = 34.dp)
            Text(title, style = MaterialTheme.typography.titleMedium, color = StillColors.Text, modifier = Modifier.padding(start = 10.dp))
        }
        content()
    }
}

/** The three looks as three small pictures of themselves. */
@Composable
private fun ThemePicker(selected: ThemeChoice, onSelect: (ThemeChoice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        ThemeChoice.entries.forEach { choice ->
            val active = choice == selected
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier.weight(1f).background(StillColors.SurfaceDim, shape).border(if (active) 2.dp else 1.dp, if (active) StillColors.Band else StillColors.Border, shape).clickable { onSelect(choice) }.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Swatch(choice)
                Text(choice.label, style = MaterialTheme.typography.bodyMedium, color = if (active) StillColors.Text else StillColors.Muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Swatch(choice: ThemeChoice) {
    val inkBg = Color(0xFF0B1220); val inkCard = Color(0xFF141E33); val paperBg = Color(0xFFF5F4EF); val paperCard = Color(0xFFFFFFFF)
    Row(modifier = Modifier.fillMaxWidth().height(44.dp).background(StillColors.Border, RoundedCornerShape(8.dp)).padding(1.dp)) {
        val halves = when (choice) {
            ThemeChoice.SYSTEM -> listOf(paperBg to paperCard, inkBg to inkCard)
            ThemeChoice.LIGHT -> listOf(paperBg to paperCard, paperBg to paperCard)
            ThemeChoice.DARK -> listOf(inkBg to inkCard, inkBg to inkCard)
        }
        halves.forEachIndexed { i, (bg, card) ->
            Box(modifier = Modifier.weight(1f).fillMaxWidth().height(44.dp).background(bg, if (i == 0) RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp) else RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)).padding(6.dp)) {
                Column {
                    Box(Modifier.fillMaxWidth().height(10.dp).background(card, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(width = 22.dp, height = 6.dp).background(Color(0xFFF2A531), RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}
