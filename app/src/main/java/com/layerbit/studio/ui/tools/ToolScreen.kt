package com.layerbit.studio.ui.tools

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.studio.R
import com.layerbit.studio.engine.Collage
import com.layerbit.studio.engine.FitSize
import com.layerbit.studio.files.StudioFile
import com.layerbit.studio.image.OutputFormat
import com.layerbit.studio.jobs.JobState
import com.layerbit.studio.jobs.belongsTo
import com.layerbit.studio.ops.CollageOp
import com.layerbit.studio.ops.OpOutcome
import com.layerbit.studio.ops.ToolId
import com.layerbit.studio.ui.components.ChipRow
import com.layerbit.studio.ui.components.Panel
import com.layerbit.studio.ui.components.Pill
import com.layerbit.studio.ui.components.PrimaryButton
import com.layerbit.studio.ui.components.SecondaryButton
import com.layerbit.studio.ui.components.SectionHeading
import com.layerbit.studio.ui.components.ToggleRow
import com.layerbit.studio.ui.editor.ColourRow
import com.layerbit.studio.ui.editor.Hint
import com.layerbit.studio.ui.editor.InkColours
import com.layerbit.studio.ui.editor.Knob
import com.layerbit.studio.ui.editor.Swatch
import com.layerbit.studio.ui.editor.fieldColours
import com.layerbit.studio.ui.theme.StudioColors
import kotlin.math.roundToInt

/**
 * A batch tool: pick pictures, set the options, run, look at what came out, save or share it,
 * or hand a result on to the editor. The same screen for every batch tool; only the options
 * section differs. The pickers live here because the MIME filter and the save destination are
 * per tool.
 */
@Composable
fun ToolRoute(
    tool: ToolId,
    onOpenInEditor: (StudioFile) -> Unit,
    onBack: () -> Unit,
    vm: ToolViewModel = viewModel()
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val job by vm.jobState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<StudioFile?>(null) }
    var pendingBatch by remember { mutableStateOf<List<StudioFile>>(emptyList()) }

    LaunchedEffect(tool) { vm.start(tool) }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.message(null) } }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ToolViewModel.MAX_INPUTS)) { vm.addPictures(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addPictures(it) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val file = pendingSave
        pendingSave = null
        if (result.resultCode == Activity.RESULT_OK && destination != null && file != null) {
            vm.writeTo(destination, file) { error -> message = error ?: context.getString(R.string.tools_saved_one, file.displayName) }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folder = result.data?.data
        val waiting = pendingBatch
        pendingBatch = emptyList()
        if (result.resultCode == Activity.RESULT_OK && folder != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.rememberFolder(folder)
            if (waiting.isNotEmpty()) vm.writeInto(folder, waiting) { message = it }
        }
    }
    fun saveInto(files: List<StudioFile>) {
        val folder = vm.savedFolder()
        if (folder == null) { pendingBatch = files; pickFolder.launch(vm.folderIntent()) } else vm.writeInto(folder, files) { message = it }
    }
    fun save(file: StudioFile) {
        if (vm.askWhereToSave) { pendingSave = file; saveFile.launch(vm.exportIntent(file)) } else saveInto(listOf(file))
    }

    Box(Modifier.fillMaxSize()) {
        ToolScreen(
            tool = tool, files = ui.files, importing = ui.importing, job = if (job.belongsTo(tool)) job else JobState.Idle,
            config = vm.config, onConfig = { vm.config = it }, presets = ui.presets.map { it.name },
            thumbnail = vm::thumbnail,
            onAddPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onAddFiles = { pickFiles.launch(arrayOf("image/*")) },
            onRemove = vm::remove, onClear = vm::clear, onRun = vm::run, onCancel = vm::cancel, onDone = vm::acknowledge,
            onSave = ::save, onSaveAll = ::saveInto, onShare = { files -> context.startActivity(vm.shareIntent(files)) },
            onOpenInEditor = onOpenInEditor, onBack = onBack
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data -> Snackbar(snackbarData = data, containerColor = StudioColors.Surface, contentColor = StudioColors.Text, actionColor = StudioColors.BandBright) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolScreen(
    tool: ToolId, files: List<StudioFile>, importing: Boolean, job: JobState,
    config: ToolConfig, onConfig: (ToolConfig) -> Unit, presets: List<String>,
    thumbnail: suspend (StudioFile) -> Bitmap?,
    onAddPhotos: () -> Unit, onAddFiles: () -> Unit, onRemove: (StudioFile) -> Unit, onClear: () -> Unit,
    onRun: () -> Unit, onCancel: () -> Unit, onDone: () -> Unit,
    onSave: (StudioFile) -> Unit, onSaveAll: (List<StudioFile>) -> Unit, onShare: (List<StudioFile>) -> Unit,
    onOpenInEditor: (StudioFile) -> Unit, onBack: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        // ---- results, when a job has finished
        if (job is JobState.Finished) {
            item {
                Panel(Modifier.fillMaxWidth(), tint = if (job.failed.isEmpty()) StudioColors.Done else StudioColors.Skipped) {
                    Text(job.opTitle, style = MaterialTheme.typography.titleMedium, color = StudioColors.Text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Pill(stringResource(R.string.tools_count_done, job.produced.size), StudioColors.Done)
                        if (job.skipped.isNotEmpty()) Pill(stringResource(R.string.tools_count_skipped, job.skipped.size), StudioColors.Skipped)
                        if (job.failed.isNotEmpty()) Pill(stringResource(R.string.tools_count_failed, job.failed.size), StudioColors.Failed)
                    }
                    if (job.produced.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            PrimaryButton(stringResource(R.string.tools_save_all), onClick = { onSaveAll(job.produced.map { it.file }) }, modifier = Modifier.weight(1f), tall = false)
                            SecondaryButton(stringResource(R.string.tools_share_all), onClick = { onShare(job.produced.map { it.file }) }, modifier = Modifier.weight(1f), tall = false)
                        }
                    }
                    TextButton(onClick = onDone, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.tools_start_again), color = StudioColors.Muted) }
                }
            }
            items(job.outcomes.size) { i ->
                when (val o = job.outcomes[i]) {
                    is OpOutcome.Produced -> ResultRow(o, thumbnail, onSave = { onSave(o.file) }, onShare = { onShare(listOf(o.file)) }, onEdit = { onOpenInEditor(o.file) })
                    is OpOutcome.Failed -> Panel(Modifier.fillMaxWidth(), tint = StudioColors.Failed, inset = 12.dp) { Text(o.inputName, style = MaterialTheme.typography.bodyMedium, color = StudioColors.Text); Text(o.reason, style = MaterialTheme.typography.bodySmall, color = StudioColors.Muted) }
                    is OpOutcome.Skipped -> Panel(Modifier.fillMaxWidth(), tint = StudioColors.Skipped, inset = 12.dp) { Text(o.inputName, style = MaterialTheme.typography.bodyMedium, color = StudioColors.Text); Text(o.reason, style = MaterialTheme.typography.bodySmall, color = StudioColors.Muted) }
                }
            }
            return@LazyColumn
        }
        if (job is JobState.Crashed) item { Panel(Modifier.fillMaxWidth(), tint = StudioColors.Failed) { Text(job.reason, color = StudioColors.Text); TextButton(onClick = onDone) { Text(stringResource(R.string.app_ok), color = StudioColors.BandBright) } } }
        if (job is JobState.Cancelled) item { Panel(Modifier.fillMaxWidth(), tint = StudioColors.Skipped) { Text(stringResource(R.string.tools_cancelled), color = StudioColors.Text); TextButton(onClick = onDone) { Text(stringResource(R.string.app_ok), color = StudioColors.BandBright) } } }

        // ---- inputs
        item {
            SectionHeading(stringResource(R.string.tools_pictures), modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(stringResource(R.string.tools_add_photos), onClick = onAddPhotos, modifier = Modifier.weight(1f), tall = false, enabled = job !is JobState.Running)
                SecondaryButton(stringResource(R.string.tools_browse_files), onClick = onAddFiles, modifier = Modifier.weight(1f), tall = false, enabled = job !is JobState.Running)
            }
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = StudioColors.Band, trackColor = StudioColors.SurfaceDim)
            if (files.isEmpty() && !importing) Hint(stringResource(R.string.tools_empty_hint))
        }
        if (files.isNotEmpty()) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in files) InputChip(f, thumbnail, onRemove = { onRemove(f) })
            }
            TextButton(onClick = onClear) { Text(stringResource(R.string.tools_clear_all), color = StudioColors.Muted) }
        }

        // ---- options
        item {
            SectionHeading(stringResource(R.string.tools_options), modifier = Modifier.padding(bottom = 8.dp))
            Panel(Modifier.fillMaxWidth()) { Options(tool, config, onConfig, presets) }
        }

        // ---- run
        item {
            if (job is JobState.Running) {
                Panel(Modifier.fillMaxWidth(), tint = StudioColors.Running) {
                    Text(job.opTitle, style = MaterialTheme.typography.titleMedium, color = StudioColors.Text)
                    Text(if (job.unitsTotal > 0) stringResource(R.string.tools_progress, job.unitsDone, job.unitsTotal, job.label) else job.label, style = MaterialTheme.typography.bodySmall, color = StudioColors.Muted, modifier = Modifier.padding(top = 4.dp))
                    if (job.unitsTotal > 0) LinearProgressIndicator(progress = { job.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = StudioColors.Band, trackColor = StudioColors.SurfaceDim)
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = StudioColors.Band, trackColor = StudioColors.SurfaceDim)
                    SecondaryButton(stringResource(R.string.app_cancel), onClick = onCancel, modifier = Modifier.padding(top = 10.dp), tall = false)
                }
            } else {
                PrimaryButton(tool.action, onClick = onRun, modifier = Modifier.fillMaxWidth(), enabled = files.isNotEmpty() && !importing, iconRes = com.layerbit.studio.ui.icons.iconFor(tool))
                Hint(stringResource(R.string.tools_run_note))
            }
        }
    }
}

@Composable
fun InputChip(f: StudioFile, thumbnail: suspend (StudioFile) -> Bitmap?, onRemove: () -> Unit) {
    val image by produceState<Bitmap?>(null, f.file.path) { value = thumbnail(f) }
    Panel(inset = 6.dp, onClick = onRemove) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(StudioColors.Canvas)) {
                image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.padding(start = 8.dp, end = 4.dp)) {
                Text(f.displayName, style = MaterialTheme.typography.bodySmall, color = StudioColors.Text, maxLines = 1)
                Text("${f.width} × ${f.height} · ${FitSize.sizeText(f.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = StudioColors.Dim)
            }
        }
    }
}

@Composable
fun ResultRow(o: OpOutcome.Produced, thumbnail: suspend (StudioFile) -> Bitmap?, onSave: () -> Unit, onShare: () -> Unit, onEdit: () -> Unit) {
    val image by produceState<Bitmap?>(null, o.file.file.path) { value = thumbnail(o.file) }
    Panel(Modifier.fillMaxWidth(), inset = 12.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(StudioColors.Canvas)) {
                image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(o.file.displayName, style = MaterialTheme.typography.bodyMedium, color = StudioColors.Text, maxLines = 2)
                o.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StudioColors.Muted, modifier = Modifier.padding(top = 2.dp)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            PrimaryButton(stringResource(R.string.app_save), onClick = onSave, modifier = Modifier.weight(1f), tall = false)
            SecondaryButton(stringResource(R.string.app_share), onClick = onShare, modifier = Modifier.weight(1f), tall = false)
            SecondaryButton(stringResource(R.string.tools_edit_result), onClick = onEdit, modifier = Modifier.weight(1f), tall = false)
        }
    }
}

// ------------------------------------------------------------------ options per tool

@Composable
private fun Options(tool: ToolId, c: ToolConfig, set: (ToolConfig) -> Unit, presets: List<String>) {
    when (tool) {
        ToolId.RESIZE -> {
            ChipRow(listOf("edge" to stringResource(R.string.resize_long_edge), "percent" to stringResource(R.string.resize_percent), "exact" to stringResource(R.string.resize_exact)), c.resizeMode, { set(c.copy(resizeMode = it)) })
            when (c.resizeMode) {
                "edge" -> {
                    ChipRow(listOf("640", "1080", "1600", "1920", "2560", "4096").map { it to it }, c.longEdge, { set(c.copy(longEdge = it)) }, Modifier.padding(top = 8.dp))
                    NumberField(c.longEdge, { set(c.copy(longEdge = it)) }, stringResource(R.string.resize_long_edge_px))
                }
                "percent" -> Knob(stringResource(R.string.resize_percent), c.percent / 100f, 0.05f..2f, onChange = { set(c.copy(percent = (it * 100).roundToInt())) }, onDone = {}, onReset = { set(c.copy(percent = 50)) }, format = { "${(it * 100).roundToInt()}%" })
                else -> ExactSize(c, set)
            }
            FormatOptions(c, set)
        }
        ToolId.RESIZE_KB -> {
            ChipRow(listOf("20", "50", "100", "200", "500", "1000").map { it to FitSize.limitText(it.toFloat()) }, c.targetKb, { set(c.copy(targetKb = it)) })
            NumberField(c.targetKb, { set(c.copy(targetKb = it)) }, stringResource(R.string.kb_limit_label), decimal = true)
            ChipRow(listOf("keep" to stringResource(R.string.kb_keep_pixels), "passport" to stringResource(R.string.kb_passport), "exact" to stringResource(R.string.resize_exact)), c.pixels, { set(c.copy(pixels = it)) }, Modifier.padding(top = 8.dp))
            if (c.pixels == "exact") ExactSize(c, set)
            Hint(stringResource(R.string.kb_hint))
            ToggleRow(stringResource(R.string.export_keep_camera), c.keepCamera, { set(c.copy(keepCamera = it)) }, detail = stringResource(R.string.export_keep_camera_detail))
        }
        ToolId.COMPRESS -> {
            ChipRow(listOf("quality" to stringResource(R.string.compress_by_quality), "target" to stringResource(R.string.compress_by_size)), if (c.pixels == "target") "target" else "quality", { set(c.copy(pixels = it)) })
            if (c.pixels == "target") NumberField(c.targetKb, { set(c.copy(targetKb = it)) }, stringResource(R.string.kb_limit_label), decimal = true)
            else Knob(stringResource(R.string.export_quality), c.quality / 100f, 0.4f..1f, onChange = { set(c.copy(quality = (it * 100).roundToInt())) }, onDone = {}, onReset = { set(c.copy(quality = 80)) }, format = { "${(it * 100).roundToInt()}" })
            ChipRow(OutputFormat.entries.filter { it.lossy }.map { it to it.label }, if (c.format.lossy) c.format else OutputFormat.JPEG, { set(c.copy(format = it)) }, Modifier.padding(top = 8.dp))
            ToggleRow(stringResource(R.string.export_allow_shrink), c.allowShrink, { set(c.copy(allowShrink = it)) }, detail = stringResource(R.string.export_allow_shrink_detail))
            ToggleRow(stringResource(R.string.export_keep_camera), c.keepCamera, { set(c.copy(keepCamera = it)) })
        }
        ToolId.CONVERT -> {
            ChipRow(OutputFormat.entries.filter { it.available }.map { it to it.label }, c.format, { set(c.copy(format = it)) })
            if (c.format.lossy) Knob(stringResource(R.string.export_quality), c.quality / 100f, 0.4f..1f, onChange = { set(c.copy(quality = (it * 100).roundToInt())) }, onDone = {}, onReset = { set(c.copy(quality = 92)) }, format = { "${(it * 100).roundToInt()}" })
            Hint(stringResource(R.string.convert_hint))
        }
        ToolId.STRIP_METADATA -> Hint(stringResource(R.string.strip_hint))
        ToolId.ENHANCE -> { Hint(stringResource(R.string.enhance_hint)); FormatOptions(c, set) }
        ToolId.APPLY_RECIPE -> {
            if (presets.isEmpty()) Hint(stringResource(R.string.recipe_none)) else ChipRow(presets.map { it to it }, c.presetName, { set(c.copy(presetName = it)) })
            FormatOptions(c, set)
        }
        ToolId.WATERMARK -> {
            OutlinedTextField(c.watermarkText, { set(c.copy(watermarkText = it.take(60))) }, label = { Text(stringResource(R.string.watermark_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColours())
            ColourRow(InkColours, c.watermarkColor, { set(c.copy(watermarkColor = it)) }, Modifier.padding(top = 8.dp))
            Knob(stringResource(R.string.layers_opacity), c.watermarkOpacity, 0.05f..1f, onChange = { set(c.copy(watermarkOpacity = it)) }, onDone = {}, onReset = { set(c.copy(watermarkOpacity = 0.35f)) })
            Knob(stringResource(R.string.layers_angle), c.watermarkAngle, -90f..90f, onChange = { set(c.copy(watermarkAngle = it)) }, onDone = {}, onReset = { set(c.copy(watermarkAngle = -30f)) }, format = { "${it.toInt()}°" })
            Knob(stringResource(R.string.mark_text_size), c.watermarkSize, 0.01f..0.1f, onChange = { set(c.copy(watermarkSize = it)) }, onDone = {}, onReset = { set(c.copy(watermarkSize = 0.03f)) }, format = { "${(it * 1000).roundToInt()}" })
            FormatOptions(c, set)
        }
        ToolId.COLLAGE -> {
            ChipRow(Collage.Layout.entries.map { it to stringResource(CollageOp.layoutLabel(it)) }, c.layout, { set(c.copy(layout = it)) })
            Row(Modifier.padding(top = 8.dp)) {
                Swatch(0xFFFFFFFF.toInt(), stringResource(R.string.frame_white), c.collageBackground == 0xFFFFFFFF.toInt()) { set(c.copy(collageBackground = 0xFFFFFFFF.toInt())) }
                Swatch(0xFF111111.toInt(), stringResource(R.string.frame_black), c.collageBackground == 0xFF111111.toInt()) { set(c.copy(collageBackground = 0xFF111111.toInt())) }
                Swatch(0xFFF5F4EF.toInt(), stringResource(R.string.frame_paper), c.collageBackground == 0xFFF5F4EF.toInt()) { set(c.copy(collageBackground = 0xFFF5F4EF.toInt())) }
                Swatch(0xFF0B1220.toInt(), stringResource(R.string.frame_ink), c.collageBackground == 0xFF0B1220.toInt()) { set(c.copy(collageBackground = 0xFF0B1220.toInt())) }
            }
            Hint(stringResource(R.string.collage_hint, CollageOp.MAX))
            FormatOptions(c, set)
        }
        ToolId.FRAME -> {
            Knob(stringResource(R.string.frame_padding), c.frame.padding, 0f..0.3f, onChange = { set(c.copy(frame = c.frame.copy(padding = it))) }, onDone = {}, onReset = { set(c.copy(frame = c.frame.copy(padding = 0.05f))) })
            Knob(stringResource(R.string.frame_border), c.frame.border, 0f..0.1f, onChange = { set(c.copy(frame = c.frame.copy(border = it))) }, onDone = {}, onReset = { set(c.copy(frame = c.frame.copy(border = 0f))) })
            Knob(stringResource(R.string.frame_corners), c.frame.cornerRadius, 0f..0.3f, onChange = { set(c.copy(frame = c.frame.copy(cornerRadius = it))) }, onDone = {}, onReset = { set(c.copy(frame = c.frame.copy(cornerRadius = 0f))) })
            FormatOptions(c, set)
        }
        else -> Unit
    }
    if (tool != ToolId.COLLAGE) {
        OutlinedTextField(c.pattern, { set(c.copy(pattern = it.take(40))) }, label = { Text(stringResource(R.string.tools_name_pattern)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = fieldColours())
        Hint(stringResource(R.string.tools_name_pattern_hint))
    }
}

@Composable
private fun FormatOptions(c: ToolConfig, set: (ToolConfig) -> Unit) {
    SectionHeading(stringResource(R.string.tools_output), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    ChipRow(OutputFormat.entries.filter { it.available }.map { it to it.label }, c.format, { set(c.copy(format = it)) })
    if (c.format.lossy) Knob(stringResource(R.string.export_quality), c.quality / 100f, 0.4f..1f, onChange = { set(c.copy(quality = (it * 100).roundToInt())) }, onDone = {}, onReset = { set(c.copy(quality = 92)) }, format = { "${(it * 100).roundToInt()}" })
    if (c.format == OutputFormat.JPEG) ToggleRow(stringResource(R.string.export_keep_camera), c.keepCamera, { set(c.copy(keepCamera = it)) })
}

@Composable
private fun ExactSize(c: ToolConfig, set: (ToolConfig) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(c.exactWidth, { set(c.copy(exactWidth = it.filter { ch -> ch.isDigit() }.take(5))) }, label = { Text(stringResource(R.string.crop_width)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), colors = fieldColours())
        OutlinedTextField(c.exactHeight, { set(c.copy(exactHeight = it.filter { ch -> ch.isDigit() }.take(5))) }, label = { Text(stringResource(R.string.crop_height)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), colors = fieldColours())
    }
    Hint(stringResource(R.string.resize_exact_hint))
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, decimal: Boolean = false) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || (decimal && it == '.') }.take(7)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = fieldColours())
}
