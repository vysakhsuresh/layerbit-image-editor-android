package com.layerbit.still.ui.pipeline

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.still.R
import com.layerbit.still.StillApplication
import com.layerbit.still.engine.Backdrop
import com.layerbit.still.engine.Cutout
import com.layerbit.still.engine.CutoutMode
import com.layerbit.still.engine.Frame
import com.layerbit.still.engine.Scan
import com.layerbit.still.engine.ScanMode
import com.layerbit.still.engine.Look
import com.layerbit.still.files.ImageStore
import com.layerbit.still.files.StillFile
import com.layerbit.still.image.OutputFormat
import com.layerbit.still.jobs.JobState
import com.layerbit.still.ops.Pipeline
import com.layerbit.still.ops.PipelineOp
import com.layerbit.still.ops.ResizeOp
import com.layerbit.still.text.Strings
import androidx.compose.runtime.snapshotFlow
import com.layerbit.still.engine.FitSize
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.engine.WatermarkPlace
import com.layerbit.still.files.Workspace
import com.layerbit.still.ops.OpContext
import com.layerbit.still.ops.OpOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The steps as the screen holds them: switches and the text fields behind them. Turned into a
 * [Pipeline] when the run starts, with every field checked and a sentence when one is wrong.
 */
data class Steps(
    val resizeOn: Boolean = false, val resizeMode: String = "edge", val longEdge: String = "1920", val percent: Int = 50, val exactWidth: String = "", val exactHeight: String = "",
    val underOn: Boolean = false, val underKb: String = "50", val passport: Boolean = false,
    val formatOn: Boolean = false, val format: OutputFormat = OutputFormat.JPEG, val quality: Int = 90,
    val enhance: Boolean = false,
    val lookOn: Boolean = false, val look: Look = Look.NONE, val lookStrength: Float = 1f, val recipeName: String = "",
    val watermarkOn: Boolean = false, val watermarkText: String = "", val watermarkOpacity: Float = 0.35f, val watermarkAngle: Float = -30f, val watermarkSize: Float = 0.03f, val watermarkColor: Int = 0xFFFFFFFF.toInt(), val watermarkPlace: WatermarkPlace = WatermarkPlace.TILED,
    val frameOn: Boolean = false, val frame: Frame = Frame(padding = 0.05f),
    val cutoutOn: Boolean = false, val cutoutMode: CutoutMode = CutoutMode.SUBJECT, val cutoutBackdrop: Backdrop = Backdrop.TRANSPARENT, val cutoutColor: Int = 0xFFFFFFFF.toInt(), val cutoutBlur: Float = 0.03f,
    val scanOn: Boolean = false, val scanMode: ScanMode = ScanMode.COLOUR, val scanStrength: Float = 1f, val scanWhiten: Float = 0.5f,
    val keepCamera: Boolean = false, val allowShrink: Boolean = true,
    val pattern: String = ""
) {
    val anyOn: Boolean get() = resizeOn || underOn || formatOn || enhance || lookOn || watermarkOn || frameOn || cutoutOn || scanOn

    /** A step whose whole point is the file's size or pixels: worth a size check. */
    val changesSize: Boolean get() = resizeOn || underOn || formatOn

    /** Whether a trial run on the first picture has anything to show. */
    val wantsCheck: Boolean get() = changesSize || watermarkOn
}

/**
 * The first picture run through the steps for real, into the app's private cache. Every number
 * is read off the file that was written; [before] and [after] are that file and its source,
 * decoded small for the card.
 */
data class SizeCheck(
    val busy: Boolean = false,
    val result: Result? = null,
    /** Why the trial produced nothing: a size that cannot be reached, a picture already clean. */
    val problem: String? = null,
    /** The batch would leave this picture as it is: said plainly, not as an error. */
    val skipped: Boolean = false
) {
    data class Result(
        val source: String,
        val before: Bitmap, val after: Bitmap,
        val beforeBytes: Long, val afterBytes: Long,
        val beforeSize: Geometry2D.Size, val afterSize: Geometry2D.Size
    ) {
        val percent: Int get() = FitSize.percentChange(beforeBytes, afterBytes)
    }
}

@OptIn(FlowPreview::class)
class PipelineViewModel(app: Application) : AndroidViewModel(app) {

    private val still = app as StillApplication
    val jobState: StateFlow<JobState> get() = still.jobRunner.state

    data class Ui(val files: List<StillFile> = emptyList(), val importing: Boolean = false, val message: String? = null, val presets: List<ImageStore.Preset> = emptyList())

    private val _ui = MutableStateFlow(Ui(presets = still.store.presets()))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    var steps by mutableStateOf(Steps(format = still.settings.current.defaultFormat, quality = still.settings.current.defaultQuality, keepCamera = still.settings.current.keepCameraFields))

    /** The live trial on the first picture; empty while no step it can show is on. */
    var sizeCheck by mutableStateOf(SizeCheck())
        private set

    /** The trial's own scratch folder in the private cache, emptied after every trial. */
    private val checkSpace = Workspace(app, Workspace.SIZE_CHECK)
    private var beforeCache: Pair<String, Bitmap>? = null

    init {
        // Settings, the first picture and whether a batch is running: each change starts a new
        // trial after a short pause, and a newer change cancels the trial still running.
        viewModelScope.launch {
            combine(
                snapshotFlow { steps },
                _ui.map { it.files.firstOrNull() }.distinctUntilChanged(),
                still.jobRunner.state.map { it is JobState.Running }.distinctUntilChanged()
            ) { s, first, running -> Triple(s, first, running) }
                .distinctUntilChanged()
                .onEach { (s, first, running) -> if (s.wantsCheck && first != null && !running) sizeCheck = sizeCheck.copy(busy = true) }
                .debounce(CHECK_DELAY_MS)
                .collectLatest { (s, first, running) -> check(s, first, running) }
        }
    }

    private suspend fun check(s: Steps, first: StillFile?, running: Boolean) {
        if (first == null || !s.wantsCheck) { sizeCheck = SizeCheck(); return }
        // The batch has the renderer; the last trial stays on screen until it is done.
        if (running) { sizeCheck = sizeCheck.copy(busy = false); return }
        // A step not filled in yet (no watermark text, an empty size) has nothing honest to show.
        val p = pipeline(s) {} ?: run { sizeCheck = SizeCheck(); return }
        sizeCheck = sizeCheck.copy(busy = true)
        val job = currentCoroutineContext().job
        val context = OpContext(checkSpace, still.renderer, still.codecs) { !job.isActive }
        try {
            sizeCheck = withContext(Dispatchers.Default) { trial(p, first, context) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            sizeCheck = SizeCheck(problem = Strings.get(R.string.engine_out_of_memory))
        } catch (e: Exception) {
            sizeCheck = SizeCheck(problem = e.message ?: Strings.get(R.string.engine_unreadable))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { checkSpace.clear() }
        }
    }

    /**
     * The batch's own op over a one-picture list, so the trial cannot drift from the run: same
     * steps, same encoder, same size search. The written file is measured, decoded small for
     * the card and deleted.
     */
    private suspend fun trial(p: Pipeline, first: StillFile, context: OpContext): SizeCheck =
        when (val outcome = PipelineOp(p).run(listOf(first), context) {}.single()) {
            is OpOutcome.Produced -> {
                val out = outcome.file.file
                try {
                    val afterBytes = out.length()
                    val afterSize = still.renderer.bounds(out) ?: throw IllegalStateException(Strings.get(R.string.engine_unreadable))
                    val beforeSize = still.renderer.bounds(first.file) ?: Geometry2D.Size(first.width, first.height)
                    val after = still.renderer.decode(out, CHECK_PIXELS, context.check)
                    val before = beforeCache?.takeIf { it.first == first.file.path }?.second
                        ?: still.renderer.decode(first.file, CHECK_PIXELS, context.check).also { beforeCache = first.file.path to it }
                    SizeCheck(result = SizeCheck.Result(first.file.path, before, after, first.file.length(), afterBytes, beforeSize, afterSize))
                } finally {
                    out.delete()
                }
            }
            is OpOutcome.Failed -> SizeCheck(problem = outcome.reason)
            is OpOutcome.Skipped -> SizeCheck(problem = outcome.reason, skipped = true)
        }

    override fun onCleared() {
        checkSpace.clear()
    }

    /** A shortcut or the home board can switch a step on before the screen shows. */
    fun start(preset: String?) {
        _ui.update { it.copy(presets = still.store.presets()) }
        when (preset) {
            "under" -> steps = steps.copy(underOn = true)
            "clean" -> steps = steps.copy()
            "resize" -> steps = steps.copy(resizeOn = true)
            "convert" -> steps = steps.copy(formatOn = true)
            "passport" -> steps = steps.copy(underOn = true, passport = true)
            "watermark" -> steps = steps.copy(watermarkOn = true)
            "cutout" -> steps = steps.copy(cutoutOn = true)
            "scan" -> steps = steps.copy(scanOn = true)
            "enhance" -> steps = steps.copy(enhance = true)
            "look" -> steps = steps.copy(lookOn = true)
        }
        val waiting = still.handoff.take()
        if (waiting.isNotEmpty()) addPictures(waiting)
    }

    fun addPictures(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _ui.update { it.copy(importing = true) }
        viewModelScope.launch {
            val added = ArrayList<StillFile>()
            var problem: String? = null
            for (uri in uris.take(MAX_INPUTS)) {
                try { added += still.store.import(uri) } catch (e: Exception) { problem = e.message }
            }
            _ui.update { it.copy(files = (it.files + added).take(MAX_INPUTS), importing = false, message = problem ?: if (uris.size > MAX_INPUTS) Strings.get(R.string.tools_too_many, MAX_INPUTS) else null) }
        }
    }

    fun addFile(file: StillFile) = _ui.update { it.copy(files = (it.files + file).take(MAX_INPUTS)) }
    fun remove(file: StillFile) = _ui.update { it.copy(files = it.files - file) }
    fun clear() = _ui.update { it.copy(files = emptyList()) }
    fun message(text: String?) = _ui.update { it.copy(message = text) }
    suspend fun thumbnail(file: StillFile): Bitmap? = still.store.thumbnail(file)

    /**
     * The pipeline for [s], or null after [report] says what is missing. The batch and the size
     * check both come through here, so the trial runs exactly the steps the batch will.
     */
    fun pipeline(s: Steps = steps, report: (String) -> Unit = ::message): Pipeline? {
        val resize = if (!s.resizeOn) null else when (s.resizeMode) {
            "exact" -> {
                val w = s.exactWidth.toIntOrNull(); val h = s.exactHeight.toIntOrNull()
                if (w == null || h == null || w !in 1..8192 || h !in 1..8192) { report(Strings.get(R.string.tools_need_dimensions)); return null }
                ResizeOp.Mode.Exact(w, h)
            }
            "percent" -> ResizeOp.Mode.Percent(s.percent)
            else -> {
                val e = s.longEdge.toIntOrNull()
                if (e == null || e !in 16..8192) { report(Strings.get(R.string.tools_need_edge)); return null }
                ResizeOp.Mode.LongEdge(e)
            }
        }
        val under = if (!s.underOn) null else s.underKb.toFloatOrNull()?.takeIf { it >= 2f } ?: run { report(Strings.get(R.string.engine_kb_too_small)); return null }
        val recipe = if (s.lookOn && s.recipeName.isNotBlank()) _ui.value.presets.firstOrNull { it.name == s.recipeName }?.recipe else null
        if (s.watermarkOn && s.watermarkText.isBlank()) { report(Strings.get(R.string.tools_need_text)); return null }
        return Pipeline(
            resize = resize, underKb = under, passport = s.underOn && s.passport, enhance = s.enhance,
            look = if (s.lookOn) s.look else Look.NONE, lookStrength = s.lookStrength, recipe = recipe,
            watermark = if (s.watermarkOn) Pipeline.Watermark(s.watermarkText, s.watermarkOpacity, s.watermarkAngle, s.watermarkSize, s.watermarkColor, s.watermarkPlace) else null,
            frame = if (s.frameOn) s.frame else null,
            cutout = if (s.cutoutOn) Cutout(enabled = true, mode = s.cutoutMode, backdrop = s.cutoutBackdrop, color = s.cutoutColor, blur = s.cutoutBlur) else null,
            scan = if (s.scanOn) Scan(s.scanMode, s.scanStrength, s.scanWhiten) else null,
            format = if (s.formatOn) s.format else null, quality = s.quality, keepCamera = s.keepCamera, allowShrink = s.allowShrink, pattern = s.pattern
        )
    }

    fun run() {
        val files = _ui.value.files
        if (files.isEmpty()) { message(Strings.get(R.string.tools_add_first)); return }
        val p = pipeline() ?: return
        if (!still.jobRunner.submit(PipelineOp(p), files)) message(Strings.get(R.string.tools_busy))
    }

    fun cancel() = still.jobRunner.cancel()
    fun acknowledge() = still.jobRunner.acknowledge()

    val askWhereToSave: Boolean get() = still.settings.current.askWhereToSave
    fun savedFolder(): Uri? = still.settings.current.saveFolder?.let { Uri.parse(it) }
    fun rememberFolder(uri: Uri) = still.settings.update { it.copy(saveFolder = uri.toString()) }
    fun exportIntent(file: StillFile): Intent = still.exporter.createDocumentIntent(file.displayName, file.mimeType)
    fun folderIntent(): Intent = still.exporter.openFolderIntent()
    fun shareIntent(files: List<StillFile>): Intent = still.exporter.shareIntent(files)

    fun writeTo(destination: Uri, file: StillFile, done: (String?) -> Unit) {
        viewModelScope.launch { try { still.exporter.writeTo(destination, file); done(null) } catch (e: Exception) { done(e.message) } }
    }

    fun writeInto(folder: Uri, files: List<StillFile>, done: (String) -> Unit) {
        viewModelScope.launch {
            var saved = 0; var problem: String? = null
            for (f in files) { val p = still.exporter.writeInto(folder, f); if (p == null) saved++ else { problem = p; break } }
            done(problem?.let { Strings.get(R.string.tools_saved_some, saved, it) } ?: Strings.get(R.string.tools_saved_all, saved))
        }
    }

    companion object {
        const val MAX_INPUTS = 100
        /** The pause after a change before the trial runs, so a dragged knob runs it once. */
        const val CHECK_DELAY_MS = 400L
        /** The card's pictures: enough to see blocks and blur at the size they are shown. */
        const val CHECK_PIXELS = 500_000L
    }
}
