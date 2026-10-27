package com.layerbit.studio.ui.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.studio.R
import com.layerbit.studio.StudioApplication
import com.layerbit.studio.engine.FitSize
import com.layerbit.studio.engine.Geometry2D
import com.layerbit.studio.engine.Mark
import com.layerbit.studio.engine.MarkKind
import com.layerbit.studio.engine.Pt
import com.layerbit.studio.ops.ToolId
import com.layerbit.studio.ui.components.IconTile
import com.layerbit.studio.ui.components.PrimaryButton
import com.layerbit.studio.ui.components.SecondaryButton
import com.layerbit.studio.ui.components.StudioTopBar
import com.layerbit.studio.ui.components.RoundIconButton
import com.layerbit.studio.ui.icons.iconFor
import com.layerbit.studio.ui.theme.GroupAccents
import com.layerbit.studio.ui.theme.StudioColors

/**
 * The editor: the picture above, the panel strip and the open panel below, side by side when
 * the window is wide. The view model owns the recipe and the preview; this screen owns only
 * what a gesture needs between its start and its end.
 */
@Composable
fun EditorScreen(
    projectId: String?,
    uri: String?,
    startPanel: ToolId.Panel,
    onBack: () -> Unit,
    onStripMetadata: (com.layerbit.studio.files.StudioFile) -> Unit,
    vm: EditorViewModel = viewModel()
) {
    val context = LocalContext.current
    val studio = context.applicationContext as StudioApplication
    val settings by studio.settings.state.collectAsState()
    val ui by vm.ui.collectAsState()

    LaunchedEffect(projectId, uri) {
        if (projectId != null) vm.open(projectId, startPanel)
        else if (uri != null) vm.openUri(Uri.parse(uri), startPanel)
    }

    val transform = remember { CanvasTransform() }
    var brush by rememberSaveable(stateSaver = BrushSaver) { mutableStateOf(Brush()) }
    var lockAspect by rememberSaveable { mutableFloatStateOf(0f) }
    var comparing by remember { mutableStateOf(false) }
    var strokePoints by remember { mutableStateOf<List<Pt>>(emptyList()) }
    var cropDragging by remember { mutableStateOf(false) }
    var editingText by remember { mutableIntStateOf(-1) }
    var presetDialog by remember { mutableStateOf(false) }
    var draggingText by remember { mutableIntStateOf(-1) }

    val density = LocalDensity.current
    val reach = with(density) { 24.dp.toPx() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val dest = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && dest != null) vm.saveTo(dest)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val dest = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && dest != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(dest, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            studio.settings.update { it.copy(saveFolder = dest.toString()) }
            vm.saveInto(dest)
        }
    }

    BackHandler(enabled = ui.busy == null) { vm.commit(); onBack() }

    val project = ui.project
    val recipe = ui.recipe
    val panel = ui.panel
    val source = project?.let { Geometry2D.Size(it.file.width, it.file.height) } ?: Geometry2D.Size(1, 1)
    val scope = PanelScope(recipe, vm::draft, vm::commit, vm::change, settings.haptics, source)

    // The overlay and the touch mode the open panel asks for.
    val overlay: Overlay = when {
        comparing -> Overlay.None
        panel == ToolId.Panel.CROP -> Overlay.CropOverlay(recipe.geometry.crop, cropDragging)
        panel == ToolId.Panel.PERSPECTIVE -> Overlay.QuadOverlay(recipe.geometry.quad)
        panel == ToolId.Panel.MARKUP && brush.kind == MarkKind.TEXT -> if (strokePoints.isEmpty()) Overlay.Texts(recipe.marks.filter { it.kind == MarkKind.TEXT && it.visible }) else Overlay.None
        panel == ToolId.Panel.MARKUP || panel == ToolId.Panel.RETOUCH -> Overlay.Stroke(brush.kind, strokePoints, brush.color, brush.width, brush.cloneSource.takeIf { brush.kind == MarkKind.CLONE })
        else -> Overlay.None
    }

    fun addMark(points: List<Pt>, kind: MarkKind) {
        val mark = Mark(kind, points, brush.color, brush.width, brush.text, source = brush.cloneSource.takeIf { kind == MarkKind.CLONE })
        if (!mark.valid) return
        if (recipe.marks.size >= com.layerbit.studio.engine.Recipe.MAX_MARKS) { vm.message(context.getString(R.string.layers_full)); return }
        vm.change { it.copy(marks = it.marks + mark) }
    }

    val mode: TouchMode = when (panel) {
        ToolId.Panel.CROP -> TouchMode.Handles(
            hit = { at -> cropHandleAt(at, recipe.geometry.crop, transform, reach) },
            onDrag = { handle, _, delta ->
                cropDragging = true
                val canvas = Geometry2D.turned(source, recipe.geometry.quarterTurns).let { t -> if (kotlin.math.abs(recipe.geometry.straighten) < 0.01f) t else Geometry2D.rotatedBounds(t.width, t.height, recipe.geometry.straighten) }
                val ratio = if (lockAspect == -1f) source.aspect else lockAspect
                val next = Geometry2D.constrain(dragCrop(recipe.geometry.crop, handle, delta), if (handle == 8) 0f else ratio, canvas)
                vm.draft(recipe.copy(geometry = recipe.geometry.copy(crop = next)))
            },
            onEnd = { cropDragging = false; vm.commit() }
        )
        ToolId.Panel.PERSPECTIVE -> TouchMode.Handles(
            hit = { at -> quadCornerAt(at, recipe.geometry.quad, transform, reach) },
            onDrag = { corner, pt, _ -> val q = moveCorner(recipe.geometry.quad, corner, pt); if (q.valid) vm.draft(recipe.copy(geometry = recipe.geometry.copy(quad = q))) },
            onEnd = { vm.commit() }
        )
        ToolId.Panel.MARKUP, ToolId.Panel.RETOUCH -> when {
            brush.pickingSource -> TouchMode.Tap { p -> brush = brush.copy(cloneSource = p, pickingSource = false) }
            brush.kind == MarkKind.TEXT && panel == ToolId.Panel.MARKUP -> TouchMode.Handles(
                hit = { at -> recipe.marks.indices.firstOrNull { i -> val m = recipe.marks[i]; m.kind == MarkKind.TEXT && m.visible && (transform.toOffset(m.points.first()) - at).getDistance() <= reach * 1.6f } },
                onDrag = { i, pt, _ -> draggingText = i; vm.draft(recipe.copy(marks = recipe.marks.toMutableList().apply { set(i, get(i).copy(points = listOf(pt))) })) },
                onEnd = { if (draggingText >= 0) { draggingText = -1; vm.commit() } },
                // A tap with no text under it places the text; a drag on one moves it.
                onTap = { p -> if (brush.text.isNotBlank()) addMark(listOf(p), MarkKind.TEXT) }
            )
            brush.kind == MarkKind.CLONE && brush.cloneSource == null -> TouchMode.Tap { p -> brush = brush.copy(cloneSource = p) }
            else -> TouchMode.Draw(
                onStart = { p -> strokePoints = listOf(p) },
                onMove = { p -> if (strokePoints.size < Mark.MAX_POINTS) strokePoints = strokePoints + p },
                onEnd = { val pts = strokePoints; strokePoints = emptyList(); if (pts.size >= 2 || brush.kind == MarkKind.DRAW) addMark(pts, brush.kind) },
                onCancel = { strokePoints = emptyList() }
            )
        }
        else -> TouchMode.View
    }

    Column(Modifier.fillMaxSize().background(StudioColors.Background).imePadding()) {
        StudioTopBar(
            title = project?.name ?: stringResource(R.string.tool_edit_title),
            subtitle = ui.canvasSize?.let { "${it.width} × ${it.height}" },
            onBack = { vm.commit(); onBack() },
            iconRes = iconFor(ToolId.EDIT), accent = GroupAccents.of(ToolId.Group.EDIT)
        ) {
            UndoRedo(ui.canUndo, ui.canRedo, vm::undo, vm::redo)
            RoundIconButton(iconRes = R.drawable.ic_tool_export, contentDescription = stringResource(R.string.tool_export), onClick = { vm.setPanel(ToolId.Panel.EXPORT) }, filled = panel == ToolId.Panel.EXPORT, modifier = Modifier.testTag("editorExport"))
        }

        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LinearProgressIndicator(Modifier.width(160.dp), color = StudioColors.Band, trackColor = StudioColors.SurfaceDim) }
            return@Column
        }

        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth > 700.dp
            val panelLimit = maxHeight * 0.5f
            @Composable fun canvasArea(modifier: Modifier) {
                Column(modifier) {
                    EditorCanvas(
                        bitmap = ui.preview, original = ui.original, comparing = comparing, transform = transform,
                        overlay = overlay, mode = mode, onCompare = { comparing = it },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ui.previewBusy) LinearProgressIndicator(Modifier.width(60.dp).height(2.dp), color = StudioColors.Band, trackColor = StudioColors.SurfaceDim)
                        Spacer(Modifier.weight(1f))
                        ZoomBar(transform)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.editor_hold_to_compare), style = MaterialTheme.typography.labelSmall, color = StudioColors.Dim)
                    }
                }
            }
            @Composable fun panelArea(modifier: Modifier) {
                Column(modifier.background(StudioColors.Surface)) {
                    PanelStrip(panel, onPick = { vm.setPanel(it); strokePoints = emptyList(); comparing = false })
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (panel) {
                            ToolId.Panel.NONE, ToolId.Panel.CROP -> CropPanel(scope, lockAspect, onLockAspect = { lockAspect = it })
                            ToolId.Panel.PERSPECTIVE -> PerspectivePanel(scope)
                            ToolId.Panel.LIGHT -> LightPanel(scope, ui.histogram, settings.showHistogram, onAuto = vm::auto)
                            ToolId.Panel.COLOUR -> ColourPanel(scope)
                            ToolId.Panel.CURVES -> CurvesPanel(scope)
                            ToolId.Panel.LOOKS -> LooksPanel(scope, ui.presets, onSavePreset = { presetDialog = true }, onApplyPreset = vm::applyPreset, onRemovePreset = vm::removePreset)
                            ToolId.Panel.DETAIL -> DetailPanel(scope)
                            ToolId.Panel.MARKUP -> MarkupPanel(brush, onBrush = { brush = if (it.kind !in listOf(MarkKind.DRAW, MarkKind.TEXT, MarkKind.ARROW, MarkKind.BOX, MarkKind.ELLIPSE)) it.copy(kind = MarkKind.DRAW) else it }, onPlaceCentre = { addMark(listOf(Pt(0.5f, 0.5f)), MarkKind.TEXT) }, haptics = settings.haptics)
                            ToolId.Panel.RETOUCH -> RetouchPanel(if (brush.kind in listOf(MarkKind.BLUR, MarkKind.PIXELATE, MarkKind.COVER, MarkKind.ERASE, MarkKind.CLONE)) brush else brush.copy(kind = MarkKind.BLUR), onBrush = { brush = it }, haptics = settings.haptics)
                            ToolId.Panel.LAYERS -> LayersPanel(scope, onEditText = { editingText = it })
                            ToolId.Panel.FRAME -> FramePanel(scope)
                            ToolId.Panel.DETAILS -> DetailsPanel(remember(project) { project?.let { studio.store.details(it.file) } ?: emptyList() }, onStrip = { project?.let { onStripMetadata(it.file) } })
                            ToolId.Panel.EXPORT -> ExportPanel(vm.options, onOptions = { vm.options = it }, canvas = ui.canvasSize, onPrepare = vm::prepareExport, haptics = settings.haptics)
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) { canvasArea(Modifier.weight(1.3f).fillMaxHeight()); panelArea(Modifier.weight(1f).fillMaxHeight()) }
            else Column(Modifier.fillMaxSize()) { canvasArea(Modifier.weight(1f).fillMaxWidth()); panelArea(Modifier.fillMaxWidth().heightIn(max = panelLimit)) }
        }
    }

    // ------------------------------------------------------------------ dialogs

    if (presetDialog) {
        var name by rememberSaveable { mutableStateOf(context.getString(R.string.looks_preset_default_name)) }
        AlertDialog(
            onDismissRequest = { presetDialog = false }, containerColor = StudioColors.Surface,
            title = { Text(stringResource(R.string.looks_save_preset), color = StudioColors.Text) },
            text = { Column { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, colors = fieldColours()); Hint(stringResource(R.string.looks_preset_keeps)) } },
            confirmButton = { TextButton(onClick = { vm.savePreset(name.ifBlank { "preset" }); presetDialog = false }) { Text(stringResource(R.string.app_save), color = StudioColors.BandBright) } },
            dismissButton = { TextButton(onClick = { presetDialog = false }) { Text(stringResource(R.string.app_cancel), color = StudioColors.Muted) } }
        )
    }
    if (editingText >= 0 && editingText < recipe.marks.size) {
        val m = recipe.marks[editingText]
        var text by remember(editingText) { mutableStateOf(m.text) }
        var angle by remember(editingText) { mutableFloatStateOf(m.angle) }
        AlertDialog(
            onDismissRequest = { editingText = -1 }, containerColor = StudioColors.Surface,
            title = { Text(stringResource(R.string.layers_edit_text), color = StudioColors.Text) },
            text = {
                Column {
                    OutlinedTextField(text, { text = it.take(Mark.MAX_TEXT) }, colors = fieldColours(), maxLines = 4)
                    Knob(stringResource(R.string.layers_angle), angle, -180f..180f, onChange = { angle = it }, onDone = {}, onReset = { angle = 0f }, haptics = settings.haptics, format = { "${it.toInt()}°" })
                }
            },
            confirmButton = { TextButton(onClick = { val i = editingText; vm.change { it.copy(marks = it.marks.toMutableList().apply { set(i, get(i).copy(text = text, angle = angle)) }) }; editingText = -1 }) { Text(stringResource(R.string.app_save), color = StudioColors.BandBright) } },
            dismissButton = { TextButton(onClick = { editingText = -1 }) { Text(stringResource(R.string.app_cancel), color = StudioColors.Muted) } }
        )
    }
    ui.export?.let { e ->
        val file = ui.exportFile
        AlertDialog(
            onDismissRequest = vm::dismissExport, containerColor = StudioColors.Surface,
            title = { Text(stringResource(R.string.export_ready), color = StudioColors.Text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.exportPreview?.let { Image(it.asImageBitmap(), stringResource(R.string.export_preview_description), Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit) }
                    Text("${e.width} × ${e.height} · ${FitSize.sizeText(e.bytes)}", style = MaterialTheme.typography.titleMedium, color = StudioColors.Text, modifier = Modifier.testTag("exportDimensions"))
                    Text(if (e.quality < 0) stringResource(R.string.export_lossless_line, e.format.label) else stringResource(R.string.export_quality_line, e.format.label, e.quality), style = MaterialTheme.typography.bodySmall, color = StudioColors.Muted)
                    for (n in e.notes) Text("· $n", style = MaterialTheme.typography.bodySmall, color = StudioColors.Dim)
                    project?.let { Text(stringResource(R.string.export_original_line, it.file.width, it.file.height, FitSize.sizeText(it.file.sizeBytes)), style = MaterialTheme.typography.bodySmall, color = StudioColors.Dim) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        SecondaryButton(stringResource(R.string.app_share), onClick = { vm.shareIntent()?.let { context.startActivity(it) } }, modifier = Modifier.weight(1f), tall = false)
                        if (!settings.askWhereToSave) SecondaryButton(stringResource(R.string.export_save_folder), onClick = {
                            val f = settings.saveFolder
                            if (f != null) vm.saveInto(Uri.parse(f)) else folder.launch(studio.exporter.openFolderIntent())
                        }, modifier = Modifier.weight(1f), tall = false)
                    }
                }
            },
            confirmButton = { PrimaryButton(stringResource(R.string.export_save_copy), onClick = { file?.let { save.launch(studio.exporter.createDocumentIntent(it.displayName, it.mimeType)) } }, tall = false) },
            dismissButton = { TextButton(onClick = vm::dismissExport) { Text(stringResource(R.string.app_close), color = StudioColors.Muted) } }
        )
    }
    ui.message?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.message(null) }, containerColor = StudioColors.Surface,
            title = { Text(stringResource(R.string.app_name), color = StudioColors.Text) },
            text = { Text(message, color = StudioColors.Muted) },
            confirmButton = { TextButton(onClick = { vm.message(null) }) { Text(stringResource(R.string.app_ok), color = StudioColors.BandBright) } }
        )
    }
    ui.busy?.let { label ->
        AlertDialog(
            onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false), containerColor = StudioColors.Surface,
            title = { Text(label, color = StudioColors.Text) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth(), color = StudioColors.Band, trackColor = StudioColors.SurfaceDim) },
            confirmButton = { TextButton(onClick = vm::cancelExport) { Text(stringResource(R.string.app_cancel), color = StudioColors.Muted) } }
        )
    }
}

@Composable
private fun UndoRedo(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    RoundIconButton(iconRes = R.drawable.ic_undo, contentDescription = stringResource(R.string.editor_undo), onClick = onUndo, enabled = canUndo)
    RoundIconButton(iconRes = R.drawable.ic_redo, contentDescription = stringResource(R.string.editor_redo), onClick = onRedo, enabled = canRedo)
}

@Composable
private fun ZoomBar(t: CanvasTransform) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RoundIconButton(iconRes = R.drawable.ic_minus, contentDescription = stringResource(R.string.editor_zoom_out), onClick = { t.step(false) }, enabled = t.zoomed, size = 30.dp)
        Text("${(t.zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = StudioColors.Muted, modifier = Modifier.clickable { t.fit() }.padding(horizontal = 4.dp))
        RoundIconButton(iconRes = R.drawable.ic_plus, contentDescription = stringResource(R.string.editor_zoom_in), onClick = { t.step(true) }, enabled = t.zoom < CanvasTransform.MAX, size = 30.dp)
    }
}

/** The strip of panels under the picture: icon and name, the open one in the band. */
@Composable
private fun PanelStrip(current: ToolId.Panel, onPick: (ToolId.Panel) -> Unit) {
    val panels = ToolId.Panel.entries.filter { it != ToolId.Panel.NONE }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(panels, key = { it.name }) { p ->
            val active = p == current
            val label = panelLabel(p)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (active) StudioColors.BandDim else StudioColors.SurfaceDim)
                    .clickable(role = androidx.compose.ui.semantics.Role.Tab) { onPick(p) }.padding(horizontal = 10.dp, vertical = 6.dp).semantics { contentDescription = label }
            ) {
                IconTile(iconRes = iconFor(p), accent = if (active) StudioColors.Band else StudioColors.Muted, size = 30.dp, lively = active)
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) StudioColors.Text else StudioColors.Muted, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
            }
        }
    }
}

@Composable
fun panelLabel(p: ToolId.Panel): String = stringResource(
    when (p) {
        ToolId.Panel.NONE, ToolId.Panel.CROP -> R.string.panel_crop; ToolId.Panel.PERSPECTIVE -> R.string.panel_perspective; ToolId.Panel.LIGHT -> R.string.panel_light
        ToolId.Panel.COLOUR -> R.string.panel_colour; ToolId.Panel.CURVES -> R.string.panel_curves; ToolId.Panel.LOOKS -> R.string.panel_looks; ToolId.Panel.DETAIL -> R.string.panel_detail
        ToolId.Panel.MARKUP -> R.string.panel_markup; ToolId.Panel.RETOUCH -> R.string.panel_retouch; ToolId.Panel.LAYERS -> R.string.panel_layers; ToolId.Panel.FRAME -> R.string.panel_frame
        ToolId.Panel.DETAILS -> R.string.panel_details; ToolId.Panel.EXPORT -> R.string.panel_export
    }
)

/** Brush survives rotation; the clone source and picking state do not need to. */
val BrushSaver = androidx.compose.runtime.saveable.Saver<Brush, List<Any>>(
    save = { listOf(it.kind.name, it.color, it.width, it.text) },
    restore = { Brush(MarkKind.valueOf(it[0] as String), it[1] as Int, it[2] as Float, it[3] as String) }
)
