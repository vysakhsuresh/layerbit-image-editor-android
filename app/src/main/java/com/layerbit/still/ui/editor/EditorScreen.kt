package com.layerbit.still.ui.editor

import com.layerbit.still.files.ImageStore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.layerbit.still.ui.components.Celebrate
import com.layerbit.still.ui.components.AmbientBackdrop
import androidx.compose.foundation.border
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.still.R
import com.layerbit.still.StillApplication
import com.layerbit.still.engine.FitSize
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.engine.Mark
import com.layerbit.still.engine.MarkKind
import com.layerbit.still.engine.Pt
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ui.components.IconTile
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.components.RoundIconButton
import com.layerbit.still.ui.icons.iconFor
import com.layerbit.still.ui.theme.GroupAccents
import com.layerbit.still.ui.theme.StillColors

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
    startTool: ToolId?,
    onBack: () -> Unit,
    onStripMetadata: (com.layerbit.still.files.StillFile) -> Unit,
    vm: EditorViewModel = viewModel()
) {
    val context = LocalContext.current
    val still = context.applicationContext as StillApplication
    val settings by still.settings.state.collectAsState()
    val ui by vm.ui.collectAsState()

    LaunchedEffect(projectId, uri) {
        if (projectId != null) vm.open(projectId, startPanel, startTool)
        else if (uri != null) vm.openUri(Uri.parse(uri), startPanel, startTool)
    }

    val transform = remember { CanvasTransform() }
    // Markup's move mode and the mark it holds, by index into the recipe's marks.
    var moving by remember { mutableStateOf(false) }
    var selectedMark by remember { mutableIntStateOf(-1) }
    var brush by rememberSaveable(stateSaver = BrushSaver) { mutableStateOf(Brush()) }
    var lockAspect by rememberSaveable { mutableFloatStateOf(0f) }
    var comparing by remember { mutableStateOf(false) }
    var strokePoints by remember { mutableStateOf<List<Pt>>(emptyList()) }
    var cropDragging by remember { mutableStateOf(false) }
    var editingText by remember { mutableIntStateOf(-1) }
    var presetDialog by remember { mutableStateOf(false) }
    var draggingText by remember { mutableIntStateOf(-1) }
    var cutKeep by rememberSaveable { mutableStateOf(true) }
    var cutWidth by rememberSaveable { mutableFloatStateOf(0.06f) }
    var pickingNeutral by rememberSaveable { mutableStateOf(startTool == ToolId.WHITE_BALANCE) }
    var split by rememberSaveable { mutableStateOf(false) }
    var splitAt by rememberSaveable { mutableFloatStateOf(0.5f) }
    LaunchedEffect(startTool) { if (startTool == ToolId.RED_EYE) brush = brush.copy(kind = MarkKind.REDEYE, width = 0.03f) }

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
            still.settings.update { it.copy(saveFolder = dest.toString()) }
            vm.saveInto(dest)
        }
    }

    BackHandler(enabled = ui.busy == null) { vm.commit(); onBack() }

    val project = ui.project
    val recipe = ui.recipe
    val panel = ui.panel
    val source = project?.let { Geometry2D.Size(it.file.width, it.file.height) } ?: Geometry2D.Size(1, 1)
    val scrubHost = remember { ScrubHost() }
    var scrubbing by remember { mutableStateOf(false) }
    val scope = PanelScope(recipe, vm::draft, vm::commit, vm::change, settings.haptics, source, scrubHost)
    // A chosen slider turns the whole picture into its track: a sideways swipe moves it.
    val viewOrScrub: TouchMode = scrubHost.target?.let { _ ->
        TouchMode.Scrub(
            onDelta = move@{ dx ->
                val t = scrubHost.target ?: return@move
                scrubbing = true
                val span = t.range.endInclusive - t.range.start
                var next = (t.value + dx * span * 1.1f).coerceIn(t.range.start, t.range.endInclusive)
                if (t.range.start < 0f && kotlin.math.abs(next) < span * 0.01f) next = 0f
                t.onChange(next)
            },
            onEnd = { scrubbing = false; scrubHost.target?.onDone?.invoke() }
        )
    } ?: TouchMode.View

    // The overlay and the touch mode the open panel asks for.
    val overlay: Overlay = when {
        comparing -> Overlay.None
        panel == ToolId.Panel.CROP -> Overlay.CropOverlay(recipe.geometry.crop, cropDragging)
        panel == ToolId.Panel.PERSPECTIVE -> Overlay.QuadOverlay(recipe.geometry.quad)
        panel == ToolId.Panel.MARKUP && moving -> Overlay.Selection(recipe.marks.filter { it.kind in Movable && it.visible }, recipe.marks.getOrNull(selectedMark)?.takeIf { it.kind in Movable })
        panel == ToolId.Panel.MARKUP && brush.kind == MarkKind.TEXT -> if (strokePoints.isEmpty()) Overlay.Texts(recipe.marks.filter { it.kind == MarkKind.TEXT && it.visible }) else Overlay.None
        panel == ToolId.Panel.MARKUP || panel == ToolId.Panel.RETOUCH -> Overlay.Stroke(brush.kind, strokePoints, brush.color, brush.width, brush.cloneSource.takeIf { brush.kind == MarkKind.CLONE })
        panel == ToolId.Panel.CUTOUT -> Overlay.Stroke(MarkKind.DRAW, strokePoints, if (cutKeep) 0xFF4CCB8F.toInt() else 0xFFE63946.toInt(), cutWidth, null)
        else -> Overlay.None
    }

    /** The selected mark changed by [block], as one undo step. */
    fun editSelected(block: (Mark) -> Mark) {
        val i = selectedMark
        if (i !in recipe.marks.indices) return
        vm.change { r -> r.copy(marks = r.marks.toMutableList().apply { set(i, block(get(i))) }) }
    }

    fun addMark(points: List<Pt>, kind: MarkKind) {
        val mark = Mark(kind, points, brush.color, brush.width, brush.text, source = brush.cloneSource.takeIf { kind == MarkKind.CLONE })
        if (!mark.valid) return
        if (recipe.marks.size >= com.layerbit.still.engine.Recipe.MAX_MARKS) { vm.message(context.getString(R.string.layers_full)); return }
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
        ToolId.Panel.CUTOUT -> if (recipe.cutout.mode == com.layerbit.still.engine.CutoutMode.SUBJECT) TouchMode.Draw(
            onStart = { p -> strokePoints = listOf(p) },
            onMove = { p -> if (strokePoints.size < Mark.MAX_POINTS) strokePoints = strokePoints + p },
            onEnd = { val pts = strokePoints; strokePoints = emptyList(); if (pts.isNotEmpty()) vm.addScribble(pts, cutWidth, cutKeep) },
            onCancel = { strokePoints = emptyList() }
        ) else TouchMode.View
        ToolId.Panel.COLOUR -> if (pickingNeutral) TouchMode.Tap { p -> pickingNeutral = false; vm.neutralAt(p) } else viewOrScrub
        ToolId.Panel.MARKUP, ToolId.Panel.RETOUCH -> when {
            // Move mode: a touch on a mark picks it, a drag carries it; a touch on nothing lets go.
            moving && panel == ToolId.Panel.MARKUP -> TouchMode.Handles(
                hit = { at -> recipe.marks.indices.lastOrNull { i -> val m = recipe.marks[i]; m.kind in Movable && m.visible && markBounds(m, transform).inflate(reach * 0.5f).contains(at) }?.also { selectedMark = it } },
                onDrag = { i, _, d -> vm.draft(recipe.copy(marks = recipe.marks.toMutableList().apply { set(i, get(i).let { m -> m.copy(points = m.points.map { p -> Pt((p.x + d.x).coerceIn(0f, 1f), (p.y + d.y).coerceIn(0f, 1f)) }) }) })) },
                onEnd = { vm.commit() },
                onTap = { selectedMark = -1 }
            )
            brush.pickingSource -> TouchMode.Tap { p -> brush = brush.copy(cloneSource = p, pickingSource = false) }
            // A tap on a correction takes it away; anywhere else adds one. Found and hand-placed eyes behave the same.
            brush.kind == MarkKind.REDEYE && panel == ToolId.Panel.RETOUCH -> TouchMode.Tap { p ->
                val at = transform.toOffset(p)
                val hit = recipe.marks.indexOfLast { m -> m.kind == MarkKind.REDEYE && m.visible && (transform.toOffset(m.points.first()) - at).getDistance() <= reach * 1.4f }
                if (hit >= 0) vm.change { it.copy(marks = it.marks.filterIndexed { i, _ -> i != hit }) } else addMark(listOf(p), MarkKind.REDEYE)
            }
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
        else -> viewOrScrub
    }

    Box(Modifier.fillMaxSize().background(StillColors.Background).imePadding()) {
        AmbientBackdrop(strength = if (ui.palette.isEmpty()) 0.5f else 0.9f, colours = ui.palette.map { androidx.compose.ui.graphics.Color(it) })
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LinearProgressIndicator(Modifier.width(160.dp), color = StillColors.Band, trackColor = StillColors.SurfaceDim) }
            return@Box
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 700.dp
            val panelLimit = maxHeight * 0.5f
            @Composable fun canvasArea(modifier: Modifier) {
                Box(modifier) {
                    Column(Modifier.fillMaxSize()) {
                        // The picture sits under the floating bar; the bar floats over the canvas's own ground, never the picture.
                        EditorCanvas(
                            bitmap = ui.preview, original = ui.original, comparing = comparing, transform = transform,
                            overlay = overlay, mode = mode, onCompare = { comparing = it },
                            split = if (split) splitAt else null, onSplit = { splitAt = it },
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 64.dp)
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ui.previewBusy) LinearProgressIndicator(Modifier.width(60.dp).height(2.dp), color = StillColors.Band, trackColor = StillColors.SurfaceDim)
                            Spacer(Modifier.weight(1f))
                            ZoomBar(transform)
                            Spacer(Modifier.width(8.dp))
                            SplitToggle(split, onToggle = { split = !split })
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.editor_hold_to_compare), style = MaterialTheme.typography.labelSmall, color = StillColors.Dim)
                        }
                    }
                    scrubHost.target?.let { t ->
                        // The slider's name and value, large, over the picture while it is being swiped.
                        androidx.compose.animation.AnimatedVisibility(visible = scrubbing, modifier = Modifier.align(Alignment.Center), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(StillColors.Glass).border(1.dp, StillColors.Border, RoundedCornerShape(22.dp)).padding(horizontal = 22.dp, vertical = 12.dp)
                            ) {
                                Text(t.label, style = MaterialTheme.typography.labelMedium, color = StillColors.Muted)
                                Text(t.format(t.value), style = MaterialTheme.typography.displayMedium, color = StillColors.BandBright)
                            }
                        }
                    }
                    FloatingBar(
                        title = project?.name ?: stringResource(R.string.tool_edit_title),
                        subtitle = ui.canvasSize?.let { "${it.width} × ${it.height}" },
                        onBack = { vm.commit(); onBack() },
                        canUndo = ui.canUndo, canRedo = ui.canRedo, onUndo = vm::undo, onRedo = vm::redo,
                        exporting = panel == ToolId.Panel.EXPORT, onExport = { vm.setPanel(ToolId.Panel.EXPORT) },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
            @Composable fun panelArea(modifier: Modifier) {
                val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                Column(
                    modifier.clip(sheet).background(StillColors.Glass)
                        .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(StillColors.Edge, StillColors.Border, StillColors.Border)), sheet)
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(width = 40.dp, height = 4.dp).background(StillColors.BorderStrong, RoundedCornerShape(2.dp)))
                    PanelStrip(panel, onPick = { vm.setPanel(it); strokePoints = emptyList(); comparing = false; scrubHost.target = null })
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (panel) {
                            ToolId.Panel.NONE, ToolId.Panel.CROP -> CropPanel(scope, lockAspect, onLockAspect = { lockAspect = it }, onAutoStraighten = vm::autoStraighten)
                            ToolId.Panel.PERSPECTIVE -> PerspectivePanel(scope, onFindPage = { vm.findPage() })
                            ToolId.Panel.LIGHT -> LightPanel(scope, ui.histogram, settings.showHistogram, onAuto = vm::auto, autoStep = ui.autoStep.takeIf { ui.autoPanel == ToolId.Panel.LIGHT }, onAutoAmount = vm::setAutoAmount, showClipping = ui.showClipping, onShowClipping = vm::setShowClipping)
                            ToolId.Panel.COLOUR -> ColourPanelWithNeutral(scope, pickingNeutral, onPick = { pickingNeutral = it }, onAuto = { pickingNeutral = false; vm.autoColour() }, autoStep = ui.autoStep.takeIf { ui.autoPanel == ToolId.Panel.COLOUR }, onAutoAmount = vm::setAutoAmount)
                            ToolId.Panel.CUTOUT -> CutoutPanel(scope, keep = cutKeep, onKeep = { cutKeep = it }, width = cutWidth, onWidth = { cutWidth = it }, kept = ui.cutoutKept, onClearStrokes = vm::clearScribbles, swatches = ui.swatches)
                            ToolId.Panel.SCAN -> ScanPanel(scope, onFindPage = { vm.findPage() }, onAdjustCorners = { vm.setPanel(ToolId.Panel.PERSPECTIVE) })
                            ToolId.Panel.CURVES -> CurvesPanel(scope, ui.histogram)
                            ToolId.Panel.LOOKS -> {
                                LaunchedEffect(ui.project?.id) { vm.ensureLookThumbs() }
                                LooksPanel(scope, ui.presets, ui.lookThumbs, ui.suggestedLooks, onSavePreset = { presetDialog = true }, onApplyPreset = vm::applyPreset, onRemovePreset = vm::removePreset, onRenamePreset = vm::renamePreset)
                            }
                            ToolId.Panel.DETAIL -> DetailPanel(scope)
                            ToolId.Panel.MARKUP -> MarkupPanel(
                                brush, onBrush = { brush = if (it.kind !in listOf(MarkKind.DRAW, MarkKind.TEXT, MarkKind.ARROW, MarkKind.BOX, MarkKind.ELLIPSE)) it.copy(kind = MarkKind.DRAW) else it },
                                onPlaceCentre = { addMark(listOf(Pt(0.5f, 0.5f)), MarkKind.TEXT) }, haptics = settings.haptics,
                                moving = moving, onMoving = { moving = it; if (!it) selectedMark = -1 },
                                selected = recipe.marks.getOrNull(selectedMark)?.takeIf { moving && it.kind in Movable },
                                onSelectedWidth = { w, done -> val i = selectedMark; if (i in recipe.marks.indices) { if (done) vm.commit() else vm.draft(recipe.copy(marks = recipe.marks.toMutableList().apply { set(i, get(i).copy(width = w)) })) } },
                                onSelectedColour = { c -> editSelected { it.copy(color = c) } },
                                onDeleteSelected = { val i = selectedMark; if (i in recipe.marks.indices) { vm.change { r -> r.copy(marks = r.marks.filterIndexed { k, _ -> k != i }) }; selectedMark = -1 } }
                            )
                            ToolId.Panel.RETOUCH -> RetouchPanel(if (brush.kind in listOf(MarkKind.BLUR, MarkKind.PIXELATE, MarkKind.COVER, MarkKind.ERASE, MarkKind.CLONE, MarkKind.REDEYE)) brush else brush.copy(kind = MarkKind.BLUR), onBrush = { brush = it }, haptics = settings.haptics, onFindRedEyes = vm::findRedEyes)
                            ToolId.Panel.LAYERS -> LayersPanel(scope, onEditText = { editingText = it })
                            ToolId.Panel.FRAME -> FramePanel(scope, ui.swatches)
                            ToolId.Panel.DETAILS -> {
                                // Reading a file's tags and segments is disk work; never on the main thread.
                                val inspected by produceState<Pair<List<Pair<String, String>>, List<ImageStore.Risk>>?>(null, project) {
                                    value = project?.let { pr -> withContext(Dispatchers.IO) { still.store.details(pr.file) to still.store.risks(pr.file) } }
                                }
                                DetailsPanel(inspected?.first ?: emptyList(), onStrip = { project?.let { onStripMetadata(it.file) } }, risks = inspected?.second)
                            }
                            ToolId.Panel.EXPORT -> ExportPanel(vm.options, onOptions = { vm.options = it }, canvas = ui.canvasSize, onPrepare = vm::prepareExport, haptics = settings.haptics)
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) { canvasArea(Modifier.weight(1.3f).fillMaxHeight()); panelArea(Modifier.weight(1f).fillMaxHeight()) }
            else Column(Modifier.fillMaxSize()) { canvasArea(Modifier.weight(1f).fillMaxWidth()); panelArea(Modifier.fillMaxWidth().heightIn(max = panelLimit)) }
        }
        Celebrate(key = ui.export, modifier = Modifier.fillMaxSize())
    }

    // ------------------------------------------------------------------ dialogs

    if (presetDialog) {
        var name by rememberSaveable { mutableStateOf(context.getString(R.string.looks_preset_default_name)) }
        AlertDialog(
            onDismissRequest = { presetDialog = false }, containerColor = StillColors.Surface,
            title = { Text(stringResource(R.string.looks_save_preset), color = StillColors.Text) },
            text = { Column { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, colors = fieldColours()); Hint(stringResource(R.string.looks_preset_keeps)) } },
            confirmButton = { TextButton(onClick = { vm.savePreset(name.ifBlank { "preset" }); presetDialog = false }) { Text(stringResource(R.string.app_save), color = StillColors.BandBright) } },
            dismissButton = { TextButton(onClick = { presetDialog = false }) { Text(stringResource(R.string.app_cancel), color = StillColors.Muted) } }
        )
    }
    if (editingText >= 0 && editingText < recipe.marks.size) {
        val m = recipe.marks[editingText]
        var text by remember(editingText) { mutableStateOf(m.text) }
        var angle by remember(editingText) { mutableFloatStateOf(m.angle) }
        AlertDialog(
            onDismissRequest = { editingText = -1 }, containerColor = StillColors.Surface,
            title = { Text(stringResource(R.string.layers_edit_text), color = StillColors.Text) },
            text = {
                Column {
                    OutlinedTextField(text, { text = it.take(Mark.MAX_TEXT) }, colors = fieldColours(), maxLines = 4)
                    Knob(stringResource(R.string.layers_angle), angle, -180f..180f, onChange = { angle = it }, onDone = {}, onReset = { angle = 0f }, haptics = settings.haptics, format = { "${it.toInt()}°" })
                }
            },
            confirmButton = { TextButton(onClick = { val i = editingText; vm.change { it.copy(marks = it.marks.toMutableList().apply { set(i, get(i).copy(text = text, angle = angle)) }) }; editingText = -1 }) { Text(stringResource(R.string.app_save), color = StillColors.BandBright) } },
            dismissButton = { TextButton(onClick = { editingText = -1 }) { Text(stringResource(R.string.app_cancel), color = StillColors.Muted) } }
        )
    }
    ui.export?.let { e ->
        val file = ui.exportFile
        // A share card: the picture big, the numbers bigger, and what to do with it.
        Dialog(onDismissRequest = vm::dismissExport) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(StillColors.Surface)
                    .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(StillColors.Edge, StillColors.Border, StillColors.Border)), RoundedCornerShape(28.dp))
                    .verticalScroll(rememberScrollState()).padding(18.dp)
            ) {
                ui.exportPreview?.let { Image(it.asImageBitmap(), stringResource(R.string.export_preview_description), Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(StillColors.Canvas), contentScale = ContentScale.Fit) }
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 14.dp)) {
                    Text(FitSize.sizeText(e.bytes), style = MaterialTheme.typography.displayMedium, color = StillColors.BandBright)
                    Text(stringResource(R.string.export_ready_size), style = MaterialTheme.typography.titleMedium, color = StillColors.Muted, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                }
                Text("${e.width} × ${e.height} · ${FitSize.sizeText(e.bytes)}", style = MaterialTheme.typography.titleMedium, color = StillColors.Text, modifier = Modifier.testTag("exportDimensions"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    com.layerbit.still.ui.components.Pill(e.format.label, StillColors.Band)
                    com.layerbit.still.ui.components.Pill(if (e.quality < 0) stringResource(R.string.export_lossless_line, "").trim(' ', '·') else stringResource(R.string.export_quality_line, "", e.quality).trim(' ', '·'), StillColors.Running)
                }
                for (n in e.notes) Text("· $n", style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, modifier = Modifier.padding(top = 4.dp))
                project?.let { Text(stringResource(R.string.export_original_line, it.file.width, it.file.height, FitSize.sizeText(it.file.sizeBytes)), style = MaterialTheme.typography.bodySmall, color = StillColors.Dim, modifier = Modifier.padding(top = 6.dp)) }
                PrimaryButton(stringResource(R.string.export_save_copy), onClick = { file?.let { save.launch(still.exporter.createDocumentIntent(it.displayName, it.mimeType)) } }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    SecondaryButton(stringResource(R.string.app_share), onClick = { vm.shareIntent()?.let { context.startActivity(it) } }, modifier = Modifier.weight(1f), tall = false)
                    if (!settings.askWhereToSave) SecondaryButton(stringResource(R.string.export_save_folder), onClick = {
                        val f = settings.saveFolder
                        if (f != null) vm.saveInto(Uri.parse(f)) else folder.launch(still.exporter.openFolderIntent())
                    }, modifier = Modifier.weight(1f), tall = false)
                }
                TextButton(onClick = vm::dismissExport, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) { Text(stringResource(R.string.app_close), color = StillColors.Muted) }
            }
        }
    }
    ui.message?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.message(null) }, containerColor = StillColors.Surface,
            title = { Text(stringResource(R.string.app_name), color = StillColors.Text) },
            text = { Text(message, color = StillColors.Muted) },
            confirmButton = { TextButton(onClick = { vm.message(null) }) { Text(stringResource(R.string.app_ok), color = StillColors.BandBright) } }
        )
    }
    ui.busy?.let { label ->
        AlertDialog(
            onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false), containerColor = StillColors.Surface,
            title = { Text(label, color = StillColors.Text) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth(), color = StillColors.Band, trackColor = StillColors.SurfaceDim) },
            confirmButton = { TextButton(onClick = vm::cancelExport) { Text(stringResource(R.string.app_cancel), color = StillColors.Muted) } }
        )
    }
}

/** The bar floats over the canvas: round glass buttons and the name in a capsule, nothing solid across the top. */
@Composable
private fun FloatingBar(
    title: String, subtitle: String?, onBack: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit,
    exporting: Boolean, onExport: () -> Unit, modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.app_back), onClick = onBack)
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(StillColors.Glass).border(1.dp, StillColors.Border, RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = StillColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = StillColors.Dim, maxLines = 1)
        }
        RoundIconButton(iconRes = R.drawable.ic_undo, contentDescription = stringResource(R.string.editor_undo), onClick = onUndo, enabled = canUndo)
        RoundIconButton(iconRes = R.drawable.ic_redo, contentDescription = stringResource(R.string.editor_redo), onClick = onRedo, enabled = canRedo)
        RoundIconButton(iconRes = R.drawable.ic_tool_export, contentDescription = stringResource(R.string.tool_export), onClick = onExport, filled = exporting, modifier = Modifier.testTag("editorExport"))
    }
}

/** Split view on or off: a small capsule that lights up in the band colour. */
@Composable
private fun SplitToggle(on: Boolean, onToggle: () -> Unit) {
    val fill by androidx.compose.animation.animateColorAsState(if (on) StillColors.Band else StillColors.Glass, label = "split")
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(fill).border(1.dp, if (on) StillColors.Band else StillColors.Border, RoundedCornerShape(999.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Switch, onClick = onToggle).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(iconRes = R.drawable.ic_compare, accent = if (on) StillColors.OnBand else StillColors.Muted, size = 18.dp, lively = false)
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.editor_split), style = MaterialTheme.typography.labelSmall, color = if (on) StillColors.OnBand else StillColors.Muted)
    }
}

@Composable
private fun ZoomBar(t: CanvasTransform) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RoundIconButton(iconRes = R.drawable.ic_minus, contentDescription = stringResource(R.string.editor_zoom_out), onClick = { t.step(false) }, enabled = t.zoomed, size = 30.dp)
        Text("${(t.zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = StillColors.Muted, modifier = Modifier.clickable { t.fit() }.padding(horizontal = 4.dp))
        RoundIconButton(iconRes = R.drawable.ic_plus, contentDescription = stringResource(R.string.editor_zoom_in), onClick = { t.step(true) }, enabled = t.zoom < CanvasTransform.MAX, size = 30.dp)
    }
}

/** The strip of panels under the picture: icon and name, the open one in the band. */
@Composable
private fun PanelStrip(current: ToolId.Panel, onPick: (ToolId.Panel) -> Unit) {
    val panels = ToolId.Panel.entries.filter { it != ToolId.Panel.NONE }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("panelStrip")) {
        items(panels, key = { it.name }) { p ->
            val active = p == current
            val label = panelLabel(p)
            val tabColour by androidx.compose.animation.animateColorAsState(if (active) StillColors.Band else StillColors.SurfaceDim, label = "tab")
            val ink by androidx.compose.animation.animateColorAsState(if (active) StillColors.OnBand else StillColors.Muted, label = "tabInk")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(tabColour)
                    .then(if (active) Modifier else Modifier.border(1.dp, StillColors.Border, RoundedCornerShape(999.dp)))
                    .clickable(role = androidx.compose.ui.semantics.Role.Tab) { onPick(p) }.padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp).semantics { contentDescription = label }
            ) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(if (active) StillColors.OnBand.copy(alpha = 0.18f) else StillColors.Surface), contentAlignment = Alignment.Center) {
                    IconTile(iconRes = iconFor(p), accent = ink, size = 28.dp, lively = active)
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = ink, modifier = Modifier.padding(start = 7.dp), maxLines = 1)
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
        ToolId.Panel.CUTOUT -> R.string.panel_cutout; ToolId.Panel.SCAN -> R.string.panel_scan
    }
)

/** Brush survives rotation; the clone source and picking state do not need to. */
val BrushSaver = androidx.compose.runtime.saveable.Saver<Brush, List<Any>>(
    save = { listOf(it.kind.name, it.color, it.width, it.text) },
    restore = { Brush(MarkKind.valueOf(it[0] as String), it[1] as Int, it[2] as Float, it[3] as String) }
)

/** The marks Markup can pick up and move: what it draws, not what Retouch does to pixels. */
private val Movable = setOf(MarkKind.DRAW, MarkKind.TEXT, MarkKind.ARROW, MarkKind.BOX, MarkKind.ELLIPSE)
