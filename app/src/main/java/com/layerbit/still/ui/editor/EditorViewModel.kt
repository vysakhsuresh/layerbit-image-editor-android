package com.layerbit.still.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.still.R
import com.layerbit.still.StillApplication
import com.layerbit.still.engine.AutoEnhance
import com.layerbit.still.engine.AutoWhite
import com.layerbit.still.engine.LookAdvice
import com.layerbit.still.engine.Clipping
import com.layerbit.still.engine.RedEyes
import com.layerbit.still.engine.PageQuad
import com.layerbit.still.engine.Level
import kotlin.math.min
import kotlin.math.max
import com.layerbit.still.engine.Mark
import com.layerbit.still.engine.MarkKind
import com.layerbit.still.engine.Heal
import com.layerbit.still.engine.ToneStep
import com.layerbit.still.engine.Crop
import com.layerbit.still.engine.Frame
import com.layerbit.still.engine.Geometry
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.engine.Histogram
import com.layerbit.still.engine.Backdrop
import com.layerbit.still.engine.CutoutMode
import com.layerbit.still.engine.Pt
import com.layerbit.still.engine.Recipe
import com.layerbit.still.BuildConfig
import com.layerbit.still.engine.Palette
import com.layerbit.still.engine.Look
import com.layerbit.still.engine.Retouch
import com.layerbit.still.engine.Scan
import com.layerbit.still.engine.ScanMode
import com.layerbit.still.engine.Scribble
import com.layerbit.still.files.ImageStore
import com.layerbit.still.files.StillFile
import com.layerbit.still.image.Budget
import com.layerbit.still.image.ExportOptions
import com.layerbit.still.image.Exported
import com.layerbit.still.ops.ToolId
import com.layerbit.still.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * One picture, one recipe, and the preview that follows the recipe.
 *
 * The expensive half of a render - decode, perspective, turns, straighten, crop - is cached
 * as [base] and reused while a tone slider moves; only [com.layerbit.still.image.Renderer.finish]
 * runs per tick. A slider drag calls [draft] on every move and [commit] on release, so undo has
 * one entry per gesture. The latest committed recipe is written to the project on commit.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val still = app as StillApplication
    private val store = still.store
    private val renderer = still.renderer
    private val codecs = still.codecs

    data class Ui(
        val project: ImageStore.Project? = null,
        val recipe: Recipe = Recipe(),
        val panel: ToolId.Panel = ToolId.Panel.CROP,
        /** The preview of what the panel wants to show; see [previewRecipe]. */
        val preview: Bitmap? = null,
        /** The untouched source at preview size, for press-and-hold compare. */
        val original: Bitmap? = null,
        val previewBusy: Boolean = false,
        val histogram: Histogram? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val message: String? = null,
        /** A modal task's label while it runs. */
        val busy: String? = null,
        val export: Exported? = null,
        val exportFile: StillFile? = null,
        val exportPreview: Bitmap? = null,
        val presets: List<ImageStore.Preset> = emptyList(),
        /** The canvas the preview stands for, in output pixels. */
        val canvasSize: Geometry2D.Size? = null,
        /** On the cut-out panel: how much of the picture the current decision keeps. */
        val cutoutKept: Float? = null,
        /** The picture under each look, small, for the Looks strip. Filled the first time the panel opens. */
        val lookThumbs: Map<Look, Bitmap> = emptyMap(),
        /** True while the preview is the quick half-size one shown during a slider move. */
        val previewFast: Boolean = false,
        /** The few colours that carry the picture, for the glow behind the editor. */
        val palette: List<Int> = emptyList(),
        /** The last Auto press, kept so its strength can be turned up or down; null once it no longer applies. */
        val autoStep: ToneStep? = null,
        /** The panel whose Auto made [autoStep]: Light or Colour. */
        val autoPanel: ToolId.Panel? = null,
        /** Backgrounds in the picture's own colours, a pale and a deep one per colour, for Frame and Cut out. */
        val swatches: List<Int> = emptyList(),
        /** The looks that suit this picture, best first, read once per picture. */
        val suggestedLooks: List<Look> = emptyList(),
        /** On the Light panel: blown pixels painted red and crushed ones blue on the preview. */
        val showClipping: Boolean = false,
        /** The look of the last picture edited, offered as one tap on an untouched picture. */
        val lastEdits: Recipe? = null,
        val loading: Boolean = true
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    var options by mutableStateOf(ExportOptions(format = still.settings.current.defaultFormat, quality = still.settings.current.defaultQuality, keepCameraFields = still.settings.current.keepCameraFields))

    private val undo = ArrayDeque<Recipe>()
    private val redo = ArrayDeque<Recipe>()
    private var baseline = Recipe()

    /** The cached geometry stage and the geometry it was rendered for. */
    private var base: Bitmap? = null
    private var baseGeometry: Geometry? = null
    private var baseScaled = false
    /** The geometry stage at half size, for the preview that follows a finger. */
    private var baseFast: Bitmap? = null
    /** True from a draft until its commit: previews in between are the quick kind. */
    @Volatile private var dragging = false

    private val renderRequests = MutableStateFlow(0L)
    private var persistJob: Job? = null
    private var exportJob: Job? = null

    init {
        viewModelScope.launch { renderLoop() }
    }

    // ------------------------------------------------------------------ opening

    fun open(projectId: String, panel: ToolId.Panel, tool: ToolId? = null) {
        val p = store.project(projectId)
        if (p == null) { message(Strings.get(R.string.editor_project_gone)); return }
        install(p, panel, tool)
    }

    fun openUri(uri: Uri, panel: ToolId.Panel, tool: ToolId? = null) {
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val p = store.importProject(uri)
                install(p, panel, tool)
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, message = e.message ?: Strings.get(R.string.app_error_not_image, "")) }
            }
        }
    }

    fun adopt(file: StillFile, panel: ToolId.Panel) {
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try { install(store.adoptAsProject(file), panel) } catch (e: Exception) { _ui.update { it.copy(loading = false, message = e.message) } }
        }
    }

    private fun install(p: ImageStore.Project, panel: ToolId.Panel, tool: ToolId? = null) {
        dropBase()
        undo.clear(); redo.clear()
        baseline = p.recipe
        _ui.update { Ui(project = p, recipe = p.recipe, panel = if (panel == ToolId.Panel.NONE) ToolId.Panel.CROP else panel, presets = store.presets(), lastEdits = store.lastEdits(), loading = false) }
        // A quick tool opens the editor already set for its job.
        when (tool) {
            ToolId.SIGNATURE -> if (p.recipe.cutout.isIdentity) change { it.copy(cutout = it.cutout.copy(enabled = true, mode = CutoutMode.INK)) }
            // The remover opens on the wash and the question; nothing is removed until the user says so.
            ToolId.CUTOUT -> if (p.recipe.cutout.isIdentity) change { it.copy(cutout = it.cutout.copy(enabled = false, mode = CutoutMode.SUBJECT)) }
            ToolId.STICKER -> { if (!p.recipe.cutout.enabled) change { it.copy(cutout = it.cutout.copy(enabled = true, backdrop = Backdrop.TRANSPARENT)) }; options = options.copy(sticker = true) }
            ToolId.SCAN -> if (p.recipe.scan.isIdentity) {
                change { it.copy(scan = Scan(ScanMode.COLOUR)) }
                // A scanner finds the page first; the corners stay adjustable on the Perspective panel.
                if (p.recipe.geometry.quad.isIdentity) pagePending = true
            }
            // Red-eye opens already looking: the eyes it finds are corrected, and a tap undoes any it got wrong.
            ToolId.RED_EYE -> redEyesPending = true
            else -> Unit
        }
        requestRender()
    }

    // ------------------------------------------------------------------ cut out, neutral

    fun addScribble(points: List<Pt>, width: Float, keep: Boolean) {
        val s = Scribble(points, width, keep)
        if (!s.valid) return
        if (_ui.value.recipe.cutout.scribbles.size >= com.layerbit.still.engine.Cutout.MAX_SCRIBBLES) { message(Strings.get(R.string.cutout_full)); return }
        change { it.copy(cutout = it.cutout.copy(scribbles = it.cutout.scribbles + s)) }
    }

    fun clearScribbles() = change { it.copy(cutout = it.cutout.copy(scribbles = emptyList())) }

    /** Reads the colour under a tap on the geometry stage and sets temperature and tint to make it neutral. */
    fun neutralAt(p: Pt) {
        val b = base ?: return
        val x = (p.x * (b.width - 1)).toInt().coerceIn(0, b.width - 1); val y = (p.y * (b.height - 1)).toInt().coerceIn(0, b.height - 1)
        val x0 = (x - 2).coerceAtLeast(0); val y0 = (y - 2).coerceAtLeast(0)
        val rw = (x + 3).coerceAtMost(b.width) - x0; val rh = (y + 3).coerceAtMost(b.height) - y0
        val region = IntArray(rw * rh)
        b.getPixels(region, 0, rw, x0, y0, rw, rh)
        val c = Retouch.sample(region, rw, rh, x - x0, y - y0, half = 2)
        change { it.copy(tone = Retouch.neutralise(c[0], c[1], c[2], it.tone)) }
        message(Strings.get(R.string.colour_neutral_done))
    }

    // ------------------------------------------------------------------ recipe edits

    fun setPanel(panel: ToolId.Panel) {
        commit()
        _ui.update { it.copy(panel = panel, autoStep = null, autoPanel = null) }
        requestRender()
    }

    /** A live change during a gesture: rendered, not yet undoable. */
    fun draft(recipe: Recipe) {
        if (!recipe.valid) return
        dragging = true
        _ui.update { it.copy(recipe = recipe, export = null, exportFile = null, exportPreview = null) }
        requestRender()
    }

    /** Ends a gesture: the change becomes one undo step and is written to the project. */
    fun commit() {
        val wasDragging = dragging
        dragging = false
        // The quick preview is replaced by the full one once the finger lifts.
        if (wasDragging) requestRender()
        val recipe = _ui.value.recipe
        if (recipe != baseline) {
            undo.addLast(baseline)
            if (undo.size > MAX_UNDO) undo.removeFirst()
            redo.clear()
            baseline = recipe
            persist()
        }
        _ui.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
    }

    /** A one-shot change: draft and commit together. */
    fun change(block: (Recipe) -> Recipe) {
        val next = block(_ui.value.recipe)
        if (!next.valid) { message(Strings.get(R.string.editor_invalid_change)); return }
        draft(next); commit()
    }

    fun undo() {
        val r = undo.removeLastOrNull() ?: return
        redo.addLast(baseline); baseline = r
        _ui.update { it.copy(recipe = r, canUndo = undo.isNotEmpty(), canRedo = true, export = null, exportFile = null, exportPreview = null, autoStep = null, autoPanel = null) }
        persist(); requestRender()
    }

    fun redo() {
        val r = redo.removeLastOrNull() ?: return
        undo.addLast(baseline); baseline = r
        _ui.update { it.copy(recipe = r, canUndo = true, canRedo = redo.isNotEmpty(), export = null, exportFile = null, exportPreview = null, autoStep = null, autoPanel = null) }
        persist(); requestRender()
    }

    fun resetAll() {
        change { Recipe() }
        _ui.update { it.copy(autoStep = null, autoPanel = null) }
    }

    /** Levels and exposure from the current preview's histogram, as sliders the user can see. */
    fun auto() {
        val h = _ui.value.histogram ?: return
        val before = _ui.value.recipe.tone
        val after = AutoEnhance.tone(h, before)
        change { it.copy(tone = after) }
        _ui.update { it.copy(autoStep = ToneStep.between(before, after), autoPanel = ToolId.Panel.LIGHT) }
    }

    /**
     * The colour cast, found from what ought to be grey in the picture before any tone, and
     * cancelled with temperature and tint. A tap on something white still overrides it.
     */
    fun autoColour() {
        val b = base?.takeIf { !it.isRecycled } ?: return
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        viewModelScope.launch {
            val before = _ui.value.recipe.tone
            val after = withContext(Dispatchers.Default) { AutoWhite.tone(px, before) }
            if (after == before) { message(Strings.get(R.string.colour_auto_nothing)); return@launch }
            change { it.copy(tone = after) }
            _ui.update { it.copy(autoStep = ToneStep.between(before, after), autoPanel = ToolId.Panel.COLOUR) }
        }
    }

    /**
     * Reads the strongest horizon or upright in the picture as the Crop panel shows it, before
     * any straightening, and sets Straighten to level it. The slider stays the user's to nudge.
     */
    fun autoStraighten() {
        val project = _ui.value.project ?: return
        val g = _ui.value.recipe.geometry.copy(straighten = 0f, crop = Crop.FULL, width = 0, height = 0)
        viewModelScope.launch {
            val suggestion = try {
                withContext(Dispatchers.Default) {
                    val upright = renderer.geometry(project.file.file, g, 600_000L).bitmap
                    try {
                        val px = IntArray(upright.width * upright.height)
                        upright.getPixels(px, 0, upright.width, 0, 0, upright.width, upright.height)
                        Level.suggest(px, upright.width, upright.height)
                    } finally { upright.recycle() }
                }
            } catch (c: CancellationException) { throw c } catch (e: Exception) { null }
            if (suggestion == null) { message(Strings.get(R.string.level_none)); return@launch }
            // Level, and cropped in so no empty corner shows; the crop stays draggable.
            val degrees = suggestion.degrees.coerceIn(-45f, 45f)
            change {
                val turned = Geometry2D.turned(Geometry2D.Size(project.file.width, project.file.height), it.geometry.quarterTurns)
                it.copy(geometry = it.geometry.copy(straighten = degrees, crop = Geometry2D.innerCrop(turned.width, turned.height, degrees)))
            }
            message(Strings.get(R.string.level_done, String.format(java.util.Locale.getDefault(), "%+.1f°", suggestion.degrees)))
        }
    }

    /** Set when Scan opens before the picture is ready; the first render looks for the page. */
    private var pagePending = false

    /**
     * Finds the page's four corners on the upright picture and flattens it. The corners are the
     * Perspective handles, so a wrong guess is a drag away from right.
     */
    fun findPage(quiet: Boolean = false) {
        val o = _ui.value.original?.takeIf { !it.isRecycled }
        if (o == null) { pagePending = true; return }
        pagePending = false
        val w = o.width; val h = o.height
        val px = IntArray(w * h)
        o.getPixels(px, 0, w, 0, 0, w, h)
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) { PageQuad.find(px, w, h) }
            if (found == null) { if (!quiet) message(Strings.get(R.string.page_not_found)); return@launch }
            change { it.copy(geometry = it.geometry.copy(quad = found.quad)) }
            message(Strings.get(R.string.page_found))
        }
    }

    /** Set when the red-eye tool opens before the picture is ready; the first render runs the search. */
    private var redEyesPending = false

    /**
     * Finds flash red-eye and adds a correction on each, skipping eyes already corrected. The
     * user then taps a wrong one away or taps a missed eye; nothing here is final.
     */
    /**
     * A heal stroke: the engine picks the patch on the preview the user is looking at, which
     * holds every earlier layer, and the choice is written into the mark so the export reuses it.
     */
    fun addHeal(points: List<Pt>, width: Float) {
        val b = _ui.value.preview?.takeIf { !it.isRecycled } ?: return
        if (_ui.value.recipe.marks.size >= Recipe.MAX_MARKS) { message(Strings.get(R.string.layers_full)); return }
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        viewModelScope.launch {
            val source = withContext(Dispatchers.Default) { Heal.findSource(px, w, h, points, width) }
            if (source == null) { message(Strings.get(R.string.heal_no_room)); return@launch }
            val mark = Mark(MarkKind.HEAL, points, width = width, source = source)
            if (!mark.valid) return@launch
            change { it.copy(marks = it.marks + mark) }
        }
    }

    fun findRedEyes() {
        val b = base?.takeIf { !it.isRecycled }
        if (b == null) { redEyesPending = true; return }
        redEyesPending = false
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        viewModelScope.launch {
            val eyes = withContext(Dispatchers.Default) { RedEyes.find(px, w, h) }
            val existing = _ui.value.recipe.marks.filter { it.kind == MarkKind.REDEYE }
            val fresh = eyes.map { it.mark() }.filter { m -> existing.none { e -> near(e.points.first(), m.points.first(), w, h, max(e.width, m.width)) } }
            val room = (Recipe.MAX_MARKS - _ui.value.recipe.marks.size).coerceAtLeast(0)
            val adding = fresh.take(room)
            if (adding.isNotEmpty()) change { it.copy(marks = it.marks + adding) }
            message(
                if (eyes.isEmpty()) Strings.get(R.string.redeye_none_found)
                else Strings.plural(R.plurals.redeye_found, adding.size, adding.size)
            )
        }
    }

    /** Whether two normalised points are within [radius] (a fraction of the short edge) of each other. */
    private fun near(a: Pt, b: Pt, w: Int, h: Int, radius: Float): Boolean {
        val short = min(w, h).toFloat()
        val dx = (a.x - b.x) * w; val dy = (a.y - b.y) * h
        return dx * dx + dy * dy <= (radius * short) * (radius * short)
    }

    fun setShowClipping(on: Boolean) {
        _ui.update { it.copy(showClipping = on) }
        requestRender()
    }

    /** Turns the last Auto up or down; slider moves made since are kept. */
    fun setAutoAmount(amount: Float, done: Boolean) {
        val step = _ui.value.autoStep ?: return
        val next = _ui.value.recipe.let { it.copy(tone = step.rescale(it.tone, amount)) }
        draft(next)
        _ui.update { it.copy(autoStep = step.copy(amount = amount)) }
        if (done) commit()
    }

    fun savePreset(name: String) {
        val r = _ui.value.recipe
        viewModelScope.launch(Dispatchers.IO) {
            store.savePreset(name, r)
            val presets = store.presets()
            _ui.update { it.copy(presets = presets, message = Strings.get(R.string.editor_preset_saved, name)) }
        }
    }

    fun applyPreset(p: ImageStore.Preset) = change { current ->
        p.recipe.copy(marks = current.marks, geometry = current.geometry.copy(width = p.recipe.geometry.width, height = p.recipe.geometry.height))
    }

    /** The last picture's look on this one, as one undoable step; this picture's own crop and marks stay. */
    fun applyLastEdits() {
        val last = _ui.value.lastEdits ?: return
        change { current -> last.copy(marks = current.marks, geometry = current.geometry, cutout = current.cutout) }
        message(Strings.get(R.string.last_edits_done))
    }

    fun renamePreset(p: ImageStore.Preset, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = store.renamePreset(p.name, name.trim())
            val presets = store.presets()
            _ui.update { it.copy(presets = presets, message = if (ok) null else Strings.get(R.string.looks_rename_taken)) }
        }
    }

    fun removePreset(p: ImageStore.Preset) {
        viewModelScope.launch(Dispatchers.IO) {
            store.removePreset(p.name)
            val presets = store.presets()
            _ui.update { it.copy(presets = presets) }
        }
    }

    fun message(text: String?) = _ui.update { it.copy(message = text) }

    private fun persist() {
        val p = _ui.value.project ?: return
        val recipe = _ui.value.recipe
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            try { store.save(p.copy(recipe = recipe)) } catch (c: CancellationException) { throw c } catch (e: Exception) {
                _ui.update { it.copy(message = Strings.get(R.string.editor_save_failed, e.message ?: "")) }
            }
        }
    }

    // ------------------------------------------------------------------ preview

    /**
     * What the canvas shows for a panel. Crop shows the whole straightened picture with the
     * crop drawn over it; perspective shows the upright source with the corners over it; the
     * layer panels hide the frame so a tap lands on canvas coordinates; everything else shows
     * the recipe as it will export.
     */
    fun previewRecipe(recipe: Recipe, panel: ToolId.Panel): Recipe = when (panel) {
        ToolId.Panel.CROP -> recipe.copy(geometry = recipe.geometry.copy(crop = Crop.FULL, width = 0, height = 0), marks = emptyList(), frame = Frame())
        ToolId.Panel.PERSPECTIVE -> recipe.copy(geometry = Geometry(), marks = emptyList(), frame = Frame())
        ToolId.Panel.MARKUP, ToolId.Panel.RETOUCH, ToolId.Panel.LAYERS -> recipe.copy(frame = Frame())
        // The cut-out panel shows the whole picture with what would go under a wash, whatever the switch says.
        // Until the user says "remove it", the panel shows the whole picture with what would go under a wash; after, the real result.
        ToolId.Panel.CUTOUT -> if (recipe.cutout.enabled) recipe.copy(frame = Frame()) else recipe.copy(frame = Frame(), cutout = recipe.cutout.copy(enabled = true, backdrop = Backdrop.TRANSPARENT))
        else -> recipe
    }

    private var thumbJob: Job? = null

    /** Renders every look over a small copy of the picture, once per project, for the Looks strip. */
    fun ensureLookThumbs() {
        if (_ui.value.lookThumbs.isNotEmpty() || thumbJob?.isActive == true) return
        thumbJob = viewModelScope.launch {
            val project = _ui.value.project ?: return@launch
            try {
                val small = withContext(Dispatchers.Default) { renderer.decode(project.file.file, Budget.LOOK_THUMB_PIXELS) }
                val thumbs = LinkedHashMap<Look, Bitmap>()
                for (look in Look.entries) thumbs[look] = renderer.finish(small, Recipe(look = look))
                _ui.update { it.copy(lookThumbs = thumbs) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // The strip falls back to labels alone; the looks themselves are unaffected.
                android.util.Log.w("Still", "look thumbnails", e)
            }
        }
    }

    private fun maskKey(geometry: Geometry): String = (_ui.value.project?.id ?: "") + "|" + geometry.hashCode()

    private fun requestRender() { renderRequests.value = System.nanoTime() }

    @OptIn(FlowPreview::class)
    private suspend fun renderLoop() {
        renderRequests.debounce(30).collectLatest {
            val state = _ui.value
            val project = state.project ?: return@collectLatest
            val shown = previewRecipe(state.recipe, state.panel)
            _ui.update { it.copy(previewBusy = true) }
            try {
                val ctx = currentCoroutineContext()
                val check = { ctx.ensureActive() }
                val geometry = shown.geometry
                val current = base
                val b = if (current != null && baseGeometry == geometry && !current.isRecycled) current else {
                    val fresh = withContext(Dispatchers.Default) { renderer.geometry(project.file.file, geometry, Budget.PREVIEW_PIXELS, check) }
                    dropBase()
                    base = fresh.bitmap; baseGeometry = geometry; baseScaled = fresh.scaled
                    if (redEyesPending) findRedEyes()
                    val swatches = withContext(Dispatchers.Default) {
                        val bmp = fresh.bitmap
                        val px = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        Palette.matched(px) to LookAdvice.suggest(px)
                    }
                    _ui.update { it.copy(swatches = swatches.first, suggestedLooks = swatches.second) }
                    fresh.bitmap
                }
                val cutoutPanel = state.panel == ToolId.Panel.CUTOUT && !state.recipe.cutout.enabled
                val onCutout = state.panel == ToolId.Panel.CUTOUT
                // While a finger is on a slider the preview is rendered at half size: a quarter of the
                // pixels, so it keeps up with the hand; the full one follows when the finger lifts.
                val fast = dragging && b.width >= 400 && b.height >= 400
                val source = if (fast) {
                    val cached = baseFast
                    if (cached != null && !cached.isRecycled && cached.width == b.width / 2) cached
                    else withContext(Dispatchers.Default) { renderer.scale(b, b.width / 2, b.height / 2) }.also { baseFast?.recycle(); baseFast = it }
                } else b
                val started = System.nanoTime()
                val finished = renderer.finish(source, shown, check, maskKey = maskKey(geometry), cutoutPreview = cutoutPanel)
                if (BuildConfig.DEBUG) android.util.Log.d("Still", "preview ${if (fast) "fast" else "full"} ${source.width}x${source.height} in ${(System.nanoTime() - started) / 1_000_000} ms")
                val kept = if (onCutout && !fast) withContext(Dispatchers.Default) { renderer.cutoutCoverage(b, shown.cutout, maskKey(geometry), check) } else state.cutoutKept
                val original = state.original ?: withContext(Dispatchers.Default) { renderer.decode(project.file.file, Budget.PREVIEW_PIXELS, check) }
                val (histogram, palette, shown2) = withContext(Dispatchers.Default) {
                    val px = IntArray(finished.width * finished.height)
                    finished.getPixels(px, 0, finished.width, 0, 0, finished.width, finished.height)
                    val h = Histogram.of(px)
                    val pal = if (fast) state.palette else Palette.of(px)
                    // The clipping warning is drawn on a copy, after the histogram has been read from the real pixels.
                    val display = if (state.showClipping && state.panel == ToolId.Panel.LIGHT) {
                        Clipping.paint(px)
                        finished.copy(finished.config ?: android.graphics.Bitmap.Config.ARGB_8888, true).also { it.setPixels(px, 0, it.width, 0, 0, it.width, it.height) }
                    } else finished
                    Triple(h, pal, display)
                }
                val canvas = Geometry2D.framedSize(Geometry2D.canvasSize(Geometry2D.Size(project.file.width, project.file.height), state.recipe.geometry), state.recipe.frame)
                // Displayed bitmaps are never recycled by hand: the canvas may be mid-draw on the
                // old one, and from API 26 the garbage collector frees pixel memory itself.
                _ui.update { it.copy(preview = shown2, original = original, histogram = histogram, previewBusy = false, canvasSize = canvas, cutoutKept = kept, previewFast = fast, palette = palette) }
                if (pagePending) findPage(quiet = true)
            } catch (c: CancellationException) {
                throw c
            } catch (e: OutOfMemoryError) {
                _ui.update { it.copy(previewBusy = false, message = Strings.get(R.string.engine_out_of_memory)) }
            } catch (e: Exception) {
                _ui.update { it.copy(previewBusy = false, message = e.message ?: Strings.get(R.string.editor_preview_failed)) }
            }
        }
    }

    private fun dropBase() {
        base?.recycle(); base = null; baseGeometry = null
        baseFast?.recycle(); baseFast = null
    }

    // ------------------------------------------------------------------ export

    /** Renders the full picture, encodes it under [options], and measures the result. */
    fun prepareExport() {
        commit()
        val project = _ui.value.project ?: return
        val recipe = _ui.value.recipe
        val settings = options
        if (!settings.valid) { message(Strings.get(R.string.export_options_invalid)); return }
        exportJob?.cancel()
        _ui.update { it.copy(busy = Strings.get(R.string.export_preparing), export = null, exportFile = null, exportPreview = null) }
        exportJob = viewModelScope.launch {
            try {
                val ctx = currentCoroutineContext()
                val check = { ctx.ensureActive() }
                val result = withContext(Dispatchers.Default) {
                    val stage = renderer.geometry(project.file.file, recipe.geometry, Budget.exportPixels, check)
                    val picture = try { renderer.finish(stage.bitmap, recipe, check, maskKey = maskKey(recipe.geometry)) } finally { stage.bitmap.recycle() }
                    try {
                        val out = still.workspace.newOutput(project.name, "export", settings.format.extension)
                        val exported = codecs.export(picture, settings, out, project.file.file, stage.scaled, check)
                        val file = StillFile(out, com.layerbit.still.engine.Names.stem(project.name) + "-edited." + settings.format.extension, StillFile.Origin.Derived("export"), settings.format.mime, exported.width, exported.height)
                        val preview = renderer.decode(out, 600_000, check)
                        Triple(exported, file, preview)
                    } finally { picture.recycle() }
                }
                _ui.update { it.copy(busy = null, export = result.first, exportFile = result.second, exportPreview = result.third) }
            } catch (c: CancellationException) {
                _ui.update { it.copy(busy = null) }
                throw c
            } catch (e: OutOfMemoryError) {
                _ui.update { it.copy(busy = null, message = Strings.get(R.string.engine_out_of_memory)) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, message = e.message ?: Strings.get(R.string.export_failed)) }
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }

    fun dismissExport() = _ui.update { it.copy(export = null, exportFile = null, exportPreview = null) }

    /** Copies the prepared export to a document the user just created. */
    fun saveTo(uri: Uri) {
        val file = _ui.value.exportFile ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = Strings.get(R.string.export_saving)) }
            try {
                still.exporter.writeTo(uri, file)
                _ui.update { it.copy(busy = null, message = Strings.get(R.string.export_saved, file.displayName)) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, message = e.message) }
            }
        }
    }

    fun saveInto(folder: Uri) {
        val file = _ui.value.exportFile ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = Strings.get(R.string.export_saving)) }
            val problem = still.exporter.writeInto(folder, file)
            _ui.update { it.copy(busy = null, message = problem ?: Strings.get(R.string.export_saved, file.displayName)) }
        }
    }

    fun shareIntent() = _ui.value.exportFile?.let { still.exporter.shareIntent(it) }

    val exportSourceFile: File? get() = _ui.value.project?.file?.file

    override fun onCleared() {
        // What this picture looked like becomes the "same as last time" for the next one.
        val r = _ui.value.recipe
        if (!r.isIdentity) store.saveLastEdits(r)
        dropBase()
    }

    companion object {
        const val MAX_UNDO = 100
    }
}
