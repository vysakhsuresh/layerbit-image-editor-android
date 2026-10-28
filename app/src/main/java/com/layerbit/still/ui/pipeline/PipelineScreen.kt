package com.layerbit.still.ui.pipeline

import com.layerbit.still.ui.components.Celebrate
import com.layerbit.still.ui.components.ButtonShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.still.R
import com.layerbit.still.engine.Backdrop
import com.layerbit.still.engine.CutoutMode
import com.layerbit.still.engine.FitSize
import com.layerbit.still.engine.ScanMode
import com.layerbit.still.engine.Look
import com.layerbit.still.files.StillFile
import com.layerbit.still.image.OutputFormat
import com.layerbit.still.jobs.JobState
import com.layerbit.still.jobs.belongsTo
import com.layerbit.still.ops.OpOutcome
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ui.components.AmbientBackdrop
import com.layerbit.still.ui.components.ChipRow
import com.layerbit.still.ui.components.rise
import com.layerbit.still.ui.components.IconTile
import com.layerbit.still.ui.components.Panel
import com.layerbit.still.ui.components.Pill
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.SectionHeading
import com.layerbit.still.ui.components.ToggleRow
import com.layerbit.still.ui.editor.ColourRow
import com.layerbit.still.ui.editor.Hint
import com.layerbit.still.ui.editor.InkColours
import com.layerbit.still.ui.editor.Knob
import com.layerbit.still.ui.editor.fieldColours
import com.layerbit.still.ui.editor.lookLabel
import com.layerbit.still.ui.tools.InputChip
import com.layerbit.still.ui.tools.ResultRow
import com.layerbit.still.ui.theme.StillColors
import kotlin.math.roundToInt

/**
 * The one batch screen. Pictures at the top, steps you switch on in the middle, one button at
 * the bottom; results replace the steps when the run is done. Every step is off by default,
 * and "remove hidden data" is not a step because it always happens.
 */
@Composable
fun PipelineRoute(preset: String?, onOpenInEditor: (StillFile) -> Unit, vm: PipelineViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val job by vm.jobState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<StillFile?>(null) }
    var pendingBatch by remember { mutableStateOf<List<StillFile>>(emptyList()) }

    LaunchedEffect(preset) { vm.start(preset) }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.message(null) } }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PipelineViewModel.MAX_INPUTS)) { vm.addPictures(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addPictures(it) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data; val file = pendingSave; pendingSave = null
        if (result.resultCode == Activity.RESULT_OK && destination != null && file != null) vm.writeTo(destination, file) { error -> message = error ?: context.getString(R.string.tools_saved_one, file.displayName) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folder = result.data?.data; val waiting = pendingBatch; pendingBatch = emptyList()
        if (result.resultCode == Activity.RESULT_OK && folder != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.rememberFolder(folder)
            if (waiting.isNotEmpty()) vm.writeInto(folder, waiting) { message = it }
        }
    }
    fun saveInto(files: List<StillFile>) {
        val folder = vm.savedFolder()
        if (folder == null) { pendingBatch = files; pickFolder.launch(vm.folderIntent()) } else vm.writeInto(folder, files) { message = it }
    }
    fun save(file: StillFile) { if (vm.askWhereToSave) { pendingSave = file; saveFile.launch(vm.exportIntent(file)) } else saveInto(listOf(file)) }

    val shown = if (job.belongsTo(ToolId.PROCESS)) job else JobState.Idle

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(strength = 0.6f)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().testTag("pipelineList")) {
            if (shown is JobState.Finished) {
                item {
                    Panel(Modifier.fillMaxWidth(), tint = if (shown.failed.isEmpty()) StillColors.Done else StillColors.Skipped) {
                        Text(shown.opTitle, style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Pill(stringResource(R.string.tools_count_done, shown.produced.size), StillColors.Done)
                            if (shown.skipped.isNotEmpty()) Pill(stringResource(R.string.tools_count_skipped, shown.skipped.size), StillColors.Skipped)
                            if (shown.failed.isNotEmpty()) Pill(stringResource(R.string.tools_count_failed, shown.failed.size), StillColors.Failed)
                        }
                        if (shown.produced.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            PrimaryButton(stringResource(R.string.tools_save_all), onClick = { saveInto(shown.produced.map { it.file }) }, modifier = Modifier.weight(1f), tall = false)
                            SecondaryButton(stringResource(R.string.tools_share_all), onClick = { context.startActivity(vm.shareIntent(shown.produced.map { it.file })) }, modifier = Modifier.weight(1f), tall = false)
                        }
                        TextButton(onClick = vm::acknowledge, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.tools_start_again), color = StillColors.Muted) }
                    }
                }
                items(shown.outcomes.size) { i ->
                    Box(rise(i + 1)) { when (val o = shown.outcomes[i]) {
                        is OpOutcome.Produced -> ResultRow(o, vm::thumbnail, onSave = { save(o.file) }, onShare = { context.startActivity(vm.shareIntent(listOf(o.file))) }, onEdit = { onOpenInEditor(o.file) })
                        is OpOutcome.Failed -> Panel(Modifier.fillMaxWidth(), tint = StillColors.Failed, inset = 12.dp) { Text(o.inputName, color = StillColors.Text); Text(o.reason, style = MaterialTheme.typography.bodySmall, color = StillColors.Muted) }
                        is OpOutcome.Skipped -> Panel(Modifier.fillMaxWidth(), tint = StillColors.Skipped, inset = 12.dp) { Text(o.inputName, color = StillColors.Text); Text(o.reason, style = MaterialTheme.typography.bodySmall, color = StillColors.Muted) }
                    } }
                }
                return@LazyColumn
            }
            if (shown is JobState.Crashed) item { Panel(Modifier.fillMaxWidth(), tint = StillColors.Failed) { Text(shown.reason, color = StillColors.Text); TextButton(onClick = vm::acknowledge) { Text(stringResource(R.string.app_ok), color = StillColors.BandBright) } } }
            if (shown is JobState.Cancelled) item { Panel(Modifier.fillMaxWidth(), tint = StillColors.Skipped) { Text(stringResource(R.string.tools_cancelled), color = StillColors.Text); TextButton(onClick = vm::acknowledge) { Text(stringResource(R.string.app_ok), color = StillColors.BandBright) } } }

            item {
                SectionHeading(stringResource(R.string.tools_pictures), modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(stringResource(R.string.tools_add_photos), onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f), tall = false, enabled = shown !is JobState.Running)
                    SecondaryButton(stringResource(R.string.tools_browse_files), onClick = { pickFiles.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f), tall = false, enabled = shown !is JobState.Running)
                }
                if (ui.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = StillColors.Band, trackColor = StillColors.SurfaceDim)
                if (ui.files.isEmpty() && !ui.importing) Hint(stringResource(R.string.tools_empty_hint))
            }
            if (ui.files.isNotEmpty()) item { InputRow(ui.files, vm::thumbnail, onRemove = vm::remove, onClear = vm::clear) }

            item {
                SectionHeading(stringResource(R.string.pipeline_steps), modifier = Modifier.padding(bottom = 4.dp))
                Hint(stringResource(R.string.pipeline_steps_hint))
            }
            item { StepsList(vm.steps, { vm.steps = it }, ui.presets.map { it.name }) }

            item {
                if (shown is JobState.Running) {
                    Panel(Modifier.fillMaxWidth(), tint = StillColors.Running) {
                        Text(shown.opTitle, style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                        Text(if (shown.unitsTotal > 0) stringResource(R.string.tools_progress, shown.unitsDone, shown.unitsTotal, shown.label) else shown.label, style = MaterialTheme.typography.bodySmall, color = StillColors.Muted, modifier = Modifier.padding(top = 4.dp))
                        if (shown.unitsTotal > 0) LinearProgressIndicator(progress = { shown.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = StillColors.Band, trackColor = StillColors.SurfaceDim)
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = StillColors.Band, trackColor = StillColors.SurfaceDim)
                        SecondaryButton(stringResource(R.string.app_cancel), onClick = vm::cancel, modifier = Modifier.padding(top = 10.dp), tall = false)
                    }
                } else {
                    Hint(stringResource(R.string.tools_run_note))
                }
            }
        }
        // The one button floats over the list, so it is there whatever is scrolled; a ring while the run is on.
        if (shown !is JobState.Finished) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                if (shown is JobState.Running) {
                    Row(
                        Modifier.align(Alignment.Center).clip(ButtonShape).background(StillColors.Glass).border(1.dp, StillColors.BorderStrong, ButtonShape).padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (shown.unitsTotal > 0) CircularProgressIndicator(progress = { shown.fraction }, modifier = Modifier.size(26.dp), color = StillColors.Band, trackColor = StillColors.SurfaceDim, strokeWidth = 4.dp)
                        else CircularProgressIndicator(modifier = Modifier.size(26.dp), color = StillColors.Band, trackColor = StillColors.SurfaceDim, strokeWidth = 4.dp)
                        Text(if (shown.unitsTotal > 0) "${shown.unitsDone} / ${shown.unitsTotal}" else shown.label, style = MaterialTheme.typography.titleMedium, color = StillColors.Text, modifier = Modifier.padding(start = 12.dp))
                    }
                } else {
                    val label = if (vm.steps.anyOn) stringResource(R.string.pipeline_run, ui.files.size) else stringResource(R.string.pipeline_run_clean, ui.files.size)
                    PrimaryButton(label, onClick = vm::run, modifier = Modifier.fillMaxWidth().testTag("pipelineRun"), enabled = ui.files.isNotEmpty() && !ui.importing, iconRes = R.drawable.ic_tool_export)
                }
            }
        }
        Celebrate(key = (shown as? JobState.Finished)?.let { it.opTitle + it.produced.size + it.outcomes.size }, modifier = Modifier.fillMaxSize())
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data -> Snackbar(snackbarData = data, containerColor = StillColors.Surface, contentColor = StillColors.Text, actionColor = StillColors.BandBright) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputRow(files: List<StillFile>, thumbnail: suspend (StillFile) -> android.graphics.Bitmap?, onRemove: (StillFile) -> Unit, onClear: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in files) InputChip(f, thumbnail, onRemove = { onRemove(f) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.pipeline_total, files.size, FitSize.sizeText(files.sumOf { it.sizeBytes })), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text(stringResource(R.string.tools_clear_all), color = StillColors.Muted) }
    }
}

private val STEP_ORDER = listOf("resize", "under", "format", "enhance", "look", "watermark", "frame", "cutout", "scan")

/** One panel per step: a switch in its header, the options under it when it is on. */
@Composable
private fun Step(key: String, title: String, detail: String, iconRes: Int, accent: Color, on: Boolean, onToggle: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Panel(rise(STEP_ORDER.indexOf(key) + 2).fillMaxWidth().padding(bottom = 10.dp), tint = if (on) accent else null, inset = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(iconRes = iconRes, accent = accent, size = 34.dp, lively = on)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = StillColors.Dim)
            }
            Switch(checked = on, onCheckedChange = onToggle, modifier = Modifier.testTag("step-$key"), colors = SwitchDefaults.colors(checkedThumbColor = StillColors.OnBand, checkedTrackColor = StillColors.Band, uncheckedThumbColor = StillColors.Muted, uncheckedTrackColor = StillColors.SurfaceDim, uncheckedBorderColor = StillColors.Border))
        }
        AnimatedVisibility(on) { Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
    }
}

@Composable
private fun StepsList(s: Steps, set: (Steps) -> Unit, presets: List<String>) {
    Column {
        Step("resize", stringResource(R.string.tool_resize_title), stringResource(R.string.step_resize_detail), R.drawable.ic_tool_resize, Color(0xFF3ED3C0), s.resizeOn, { set(s.copy(resizeOn = it)) }) {
            ChipRow(listOf("edge" to stringResource(R.string.resize_long_edge), "percent" to stringResource(R.string.resize_percent), "exact" to stringResource(R.string.resize_exact)), s.resizeMode, { set(s.copy(resizeMode = it)) })
            when (s.resizeMode) {
                "edge" -> { ChipRow(listOf("640", "1080", "1600", "1920", "2560", "4096").map { it to it }, s.longEdge, { set(s.copy(longEdge = it)) }); NumberField(s.longEdge, { set(s.copy(longEdge = it)) }, stringResource(R.string.resize_long_edge_px)) }
                "percent" -> Knob(stringResource(R.string.resize_percent), s.percent / 100f, 0.05f..2f, onChange = { set(s.copy(percent = (it * 100).roundToInt())) }, onDone = {}, onReset = { set(s.copy(percent = 50)) }, format = { "${(it * 100).roundToInt()}%" })
                else -> ExactSize(s.exactWidth, s.exactHeight, { set(s.copy(exactWidth = it)) }, { set(s.copy(exactHeight = it)) })
            }
        }
        Step("under", stringResource(R.string.tool_resize_kb_title), stringResource(R.string.step_under_detail), R.drawable.ic_tool_resize_kb, Color(0xFF3ED3C0), s.underOn, { set(s.copy(underOn = it)) }) {
            ChipRow(listOf("20", "50", "100", "200", "500", "1000").map { it to FitSize.limitText(it.toFloat()) }, s.underKb, { set(s.copy(underKb = it)) })
            NumberField(s.underKb, { set(s.copy(underKb = it)) }, stringResource(R.string.kb_limit_label), decimal = true)
            ToggleRow(stringResource(R.string.kb_passport), s.passport, { set(s.copy(passport = it)) }, detail = stringResource(R.string.engine_passport_note))
            ToggleRow(stringResource(R.string.export_allow_shrink), s.allowShrink, { set(s.copy(allowShrink = it)) }, detail = stringResource(R.string.export_allow_shrink_detail))
            Hint(stringResource(R.string.kb_hint))
        }
        Step("format", stringResource(R.string.tool_convert_title), stringResource(R.string.step_format_detail), R.drawable.ic_tool_convert, Color(0xFF3ED3C0), s.formatOn, { set(s.copy(formatOn = it)) }) {
            ChipRow(OutputFormat.entries.filter { it.available }.map { it to it.label }, s.format, { set(s.copy(format = it)) })
            if (s.format.lossy) Knob(stringResource(R.string.export_quality), s.quality / 100f, 0.4f..1f, onChange = { set(s.copy(quality = (it * 100).roundToInt())) }, onDone = {}, onReset = { set(s.copy(quality = 90)) }, format = { "${(it * 100).roundToInt()}" })
            Hint(stringResource(R.string.convert_hint))
        }
        Step("enhance", stringResource(R.string.tool_enhance_title), stringResource(R.string.step_enhance_detail), R.drawable.ic_tool_enhance, Color(0xFF5B9DFF), s.enhance, { set(s.copy(enhance = it)) }) { Hint(stringResource(R.string.enhance_hint)) }
        Step("look", stringResource(R.string.tool_looks_title), stringResource(R.string.step_look_detail), R.drawable.ic_tool_looks, Color(0xFF5B9DFF), s.lookOn, { set(s.copy(lookOn = it)) }) {
            ChipRow(Look.entries.map { it to lookLabel(it) }, s.look, { set(s.copy(look = it)) })
            if (s.look != Look.NONE) Knob(stringResource(R.string.looks_strength), s.lookStrength, 0f..1f, onChange = { set(s.copy(lookStrength = it)) }, onDone = {}, onReset = { set(s.copy(lookStrength = 1f)) })
            if (presets.isNotEmpty()) { SectionHeading(stringResource(R.string.looks_presets), modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)); ChipRow(listOf("" to stringResource(R.string.look_none)) + presets.map { it to it }, s.recipeName, { set(s.copy(recipeName = it)) }) }
            else Hint(stringResource(R.string.recipe_none))
        }
        Step("watermark", stringResource(R.string.tool_watermark_title), stringResource(R.string.step_watermark_detail), R.drawable.ic_tool_watermark, Color(0xFFB08CFF), s.watermarkOn, { set(s.copy(watermarkOn = it)) }) {
            OutlinedTextField(s.watermarkText, { set(s.copy(watermarkText = it.take(60))) }, label = { Text(stringResource(R.string.watermark_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColours())
            ColourRow(InkColours, s.watermarkColor, { set(s.copy(watermarkColor = it)) })
            Knob(stringResource(R.string.layers_opacity), s.watermarkOpacity, 0.05f..1f, onChange = { set(s.copy(watermarkOpacity = it)) }, onDone = {}, onReset = { set(s.copy(watermarkOpacity = 0.35f)) })
            Knob(stringResource(R.string.layers_angle), s.watermarkAngle, -90f..90f, onChange = { set(s.copy(watermarkAngle = it)) }, onDone = {}, onReset = { set(s.copy(watermarkAngle = -30f)) }, format = { "${it.toInt()}°" })
            Knob(stringResource(R.string.mark_text_size), s.watermarkSize, 0.01f..0.1f, onChange = { set(s.copy(watermarkSize = it)) }, onDone = {}, onReset = { set(s.copy(watermarkSize = 0.03f)) }, format = { "${(it * 1000).roundToInt()}" })
        }
        Step("frame", stringResource(R.string.tool_frame_title), stringResource(R.string.step_frame_detail), R.drawable.ic_tool_frame, Color(0xFFB08CFF), s.frameOn, { set(s.copy(frameOn = it)) }) {
            ChipRow(listOf(0f to stringResource(R.string.crop_original), 1f to "1:1", 4f / 5f to "4:5", 16f / 9f to "16:9", 9f / 16f to "9:16"), s.frame.aspect, { a -> set(s.copy(frame = s.frame.copy(aspect = a))) })
            Knob(stringResource(R.string.frame_padding), s.frame.padding, 0f..0.3f, onChange = { set(s.copy(frame = s.frame.copy(padding = it))) }, onDone = {}, onReset = { set(s.copy(frame = s.frame.copy(padding = 0.05f))) })
            Knob(stringResource(R.string.frame_border), s.frame.border, 0f..0.1f, onChange = { set(s.copy(frame = s.frame.copy(border = it))) }, onDone = {}, onReset = { set(s.copy(frame = s.frame.copy(border = 0f))) })
            Knob(stringResource(R.string.frame_corners), s.frame.cornerRadius, 0f..0.3f, onChange = { set(s.copy(frame = s.frame.copy(cornerRadius = it))) }, onDone = {}, onReset = { set(s.copy(frame = s.frame.copy(cornerRadius = 0f))) })
            ToggleRow(stringResource(R.string.frame_blurred), s.frame.blurredFill, { set(s.copy(frame = s.frame.copy(blurredFill = it))) })
        }
        Step("cutout", stringResource(R.string.tool_cutout_title), stringResource(R.string.step_cutout_detail), R.drawable.ic_tool_cutout, Color(0xFFB08CFF), s.cutoutOn, { set(s.copy(cutoutOn = it)) }) {
            ChipRow(listOf(CutoutMode.SUBJECT to stringResource(R.string.cutout_subject), CutoutMode.INK to stringResource(R.string.cutout_ink)), s.cutoutMode, { set(s.copy(cutoutMode = it)) })
            ChipRow(listOf(Backdrop.TRANSPARENT to stringResource(R.string.cutout_transparent), Backdrop.COLOUR to stringResource(R.string.cutout_colour), Backdrop.BLUR to stringResource(R.string.cutout_blur)), s.cutoutBackdrop, { set(s.copy(cutoutBackdrop = it)) })
            if (s.cutoutBackdrop == Backdrop.COLOUR) ColourRow(listOf(0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFF2A531.toInt(), 0xFF5BA9E8.toInt(), 0xFF4CCB8F.toInt(), 0xFFE62E80.toInt()), s.cutoutColor, { set(s.copy(cutoutColor = it)) })
            if (s.cutoutBackdrop == Backdrop.BLUR) Knob(stringResource(R.string.cutout_blur_amount), s.cutoutBlur, 0.005f..0.1f, onChange = { set(s.copy(cutoutBlur = it)) }, onDone = {}, onReset = { set(s.copy(cutoutBlur = 0.03f)) }, format = { "${(it * 1000).roundToInt()}" })
            Hint(stringResource(R.string.step_cutout_hint))
        }
        Step("scan", stringResource(R.string.tool_scan_title), stringResource(R.string.step_scan_detail), R.drawable.ic_tool_scan, Color(0xFF5B9DFF), s.scanOn, { set(s.copy(scanOn = it)) }) {
            ChipRow(listOf(ScanMode.COLOUR to stringResource(R.string.scan_colour), ScanMode.GREY to stringResource(R.string.scan_grey), ScanMode.INK to stringResource(R.string.scan_ink)), s.scanMode, { set(s.copy(scanMode = it)) })
            Knob(stringResource(R.string.scan_strength), s.scanStrength, 0f..1f, onChange = { set(s.copy(scanStrength = it)) }, onDone = {}, onReset = { set(s.copy(scanStrength = 1f)) }, format = { "${(it * 100).roundToInt()}" })
            Knob(stringResource(R.string.scan_whiten), s.scanWhiten, 0f..1f, onChange = { set(s.copy(scanWhiten = it)) }, onDone = {}, onReset = { set(s.copy(scanWhiten = 0.5f)) }, format = { "${(it * 100).roundToInt()}" })
            Hint(stringResource(R.string.scan_hint))
        }
        Panel(Modifier.fillMaxWidth(), tint = StillColors.Done, inset = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(iconRes = R.drawable.ic_tool_strip, accent = StillColors.Done, size = 34.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.tool_strip_title), style = MaterialTheme.typography.titleMedium, color = StillColors.Text)
                    Text(stringResource(R.string.step_clean_detail), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim)
                }
                Pill(stringResource(R.string.step_always), StillColors.Done)
            }
            ToggleRow(stringResource(R.string.export_keep_camera), s.keepCamera, { set(s.copy(keepCamera = it)) }, detail = stringResource(R.string.export_keep_camera_detail), modifier = Modifier.padding(top = 4.dp))
        }
        OutlinedTextField(s.pattern, { set(s.copy(pattern = it.take(40))) }, label = { Text(stringResource(R.string.tools_name_pattern)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = fieldColours())
        Hint(stringResource(R.string.tools_name_pattern_hint))
    }
}

@Composable
private fun ExactSize(w: String, h: String, onW: (String) -> Unit, onH: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(w, { onW(it.filter { c -> c.isDigit() }.take(5)) }, label = { Text(stringResource(R.string.crop_width)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), colors = fieldColours())
        OutlinedTextField(h, { onH(it.filter { c -> c.isDigit() }.take(5)) }, label = { Text(stringResource(R.string.crop_height)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), colors = fieldColours())
    }
    Hint(stringResource(R.string.resize_exact_hint))
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, decimal: Boolean = false) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || (decimal && it == '.') }.take(7)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number), modifier = Modifier.fillMaxWidth(), colors = fieldColours())
}
