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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val watermarkOn: Boolean = false, val watermarkText: String = "", val watermarkOpacity: Float = 0.35f, val watermarkAngle: Float = -30f, val watermarkSize: Float = 0.03f, val watermarkColor: Int = 0xFFFFFFFF.toInt(),
    val frameOn: Boolean = false, val frame: Frame = Frame(padding = 0.05f),
    val cutoutOn: Boolean = false, val cutoutMode: CutoutMode = CutoutMode.SUBJECT, val cutoutBackdrop: Backdrop = Backdrop.TRANSPARENT, val cutoutColor: Int = 0xFFFFFFFF.toInt(), val cutoutBlur: Float = 0.03f,
    val scanOn: Boolean = false, val scanMode: ScanMode = ScanMode.COLOUR, val scanStrength: Float = 1f, val scanWhiten: Float = 0.5f,
    val keepCamera: Boolean = false, val allowShrink: Boolean = true,
    val pattern: String = ""
) {
    val anyOn: Boolean get() = resizeOn || underOn || formatOn || enhance || lookOn || watermarkOn || frameOn || cutoutOn || scanOn
}

class PipelineViewModel(app: Application) : AndroidViewModel(app) {

    private val still = app as StillApplication
    val jobState: StateFlow<JobState> get() = still.jobRunner.state

    data class Ui(val files: List<StillFile> = emptyList(), val importing: Boolean = false, val message: String? = null, val presets: List<ImageStore.Preset> = emptyList())

    private val _ui = MutableStateFlow(Ui(presets = still.store.presets()))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    var steps by mutableStateOf(Steps(format = still.settings.current.defaultFormat, quality = still.settings.current.defaultQuality, keepCamera = still.settings.current.keepCameraFields))

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

    /** The pipeline for the current steps, or null after a message about what is missing. */
    fun pipeline(): Pipeline? {
        val s = steps
        val resize = if (!s.resizeOn) null else when (s.resizeMode) {
            "exact" -> {
                val w = s.exactWidth.toIntOrNull(); val h = s.exactHeight.toIntOrNull()
                if (w == null || h == null || w !in 1..8192 || h !in 1..8192) { message(Strings.get(R.string.tools_need_dimensions)); return null }
                ResizeOp.Mode.Exact(w, h)
            }
            "percent" -> ResizeOp.Mode.Percent(s.percent)
            else -> {
                val e = s.longEdge.toIntOrNull()
                if (e == null || e !in 16..8192) { message(Strings.get(R.string.tools_need_edge)); return null }
                ResizeOp.Mode.LongEdge(e)
            }
        }
        val under = if (!s.underOn) null else s.underKb.toFloatOrNull()?.takeIf { it >= 2f } ?: run { message(Strings.get(R.string.engine_kb_too_small)); return null }
        val recipe = if (s.lookOn && s.recipeName.isNotBlank()) _ui.value.presets.firstOrNull { it.name == s.recipeName }?.recipe else null
        if (s.watermarkOn && s.watermarkText.isBlank()) { message(Strings.get(R.string.tools_need_text)); return null }
        return Pipeline(
            resize = resize, underKb = under, passport = s.underOn && s.passport, enhance = s.enhance,
            look = if (s.lookOn) s.look else Look.NONE, lookStrength = s.lookStrength, recipe = recipe,
            watermark = if (s.watermarkOn) Pipeline.Watermark(s.watermarkText, s.watermarkOpacity, s.watermarkAngle, s.watermarkSize, s.watermarkColor) else null,
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

    companion object { const val MAX_INPUTS = 100 }
}
