package com.layerbit.studio.ui.pipeline

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.studio.R
import com.layerbit.studio.StudioApplication
import com.layerbit.studio.engine.Frame
import com.layerbit.studio.engine.Look
import com.layerbit.studio.files.ImageStore
import com.layerbit.studio.files.StudioFile
import com.layerbit.studio.image.OutputFormat
import com.layerbit.studio.jobs.JobState
import com.layerbit.studio.ops.Pipeline
import com.layerbit.studio.ops.PipelineOp
import com.layerbit.studio.ops.ResizeOp
import com.layerbit.studio.text.Strings
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
    val keepCamera: Boolean = false, val allowShrink: Boolean = true,
    val pattern: String = ""
) {
    val anyOn: Boolean get() = resizeOn || underOn || formatOn || enhance || lookOn || watermarkOn || frameOn
}

class PipelineViewModel(app: Application) : AndroidViewModel(app) {

    private val studio = app as StudioApplication
    val jobState: StateFlow<JobState> get() = studio.jobRunner.state

    data class Ui(val files: List<StudioFile> = emptyList(), val importing: Boolean = false, val message: String? = null, val presets: List<ImageStore.Preset> = emptyList())

    private val _ui = MutableStateFlow(Ui(presets = studio.store.presets()))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    var steps by mutableStateOf(Steps(format = studio.settings.current.defaultFormat, quality = studio.settings.current.defaultQuality, keepCamera = studio.settings.current.keepCameraFields))

    /** A shortcut or the home board can switch a step on before the screen shows. */
    fun start(preset: String?) {
        _ui.update { it.copy(presets = studio.store.presets()) }
        when (preset) {
            "under" -> steps = steps.copy(underOn = true)
            "clean" -> steps = steps.copy()
            "resize" -> steps = steps.copy(resizeOn = true)
            "convert" -> steps = steps.copy(formatOn = true)
        }
        val waiting = studio.handoff.take()
        if (waiting.isNotEmpty()) addPictures(waiting)
    }

    fun addPictures(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _ui.update { it.copy(importing = true) }
        viewModelScope.launch {
            val added = ArrayList<StudioFile>()
            var problem: String? = null
            for (uri in uris.take(MAX_INPUTS)) {
                try { added += studio.store.import(uri) } catch (e: Exception) { problem = e.message }
            }
            _ui.update { it.copy(files = (it.files + added).take(MAX_INPUTS), importing = false, message = problem ?: if (uris.size > MAX_INPUTS) Strings.get(R.string.tools_too_many, MAX_INPUTS) else null) }
        }
    }

    fun addFile(file: StudioFile) = _ui.update { it.copy(files = (it.files + file).take(MAX_INPUTS)) }
    fun remove(file: StudioFile) = _ui.update { it.copy(files = it.files - file) }
    fun clear() = _ui.update { it.copy(files = emptyList()) }
    fun message(text: String?) = _ui.update { it.copy(message = text) }
    suspend fun thumbnail(file: StudioFile): Bitmap? = studio.store.thumbnail(file)

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
            format = if (s.formatOn) s.format else null, quality = s.quality, keepCamera = s.keepCamera, allowShrink = s.allowShrink, pattern = s.pattern
        )
    }

    fun run() {
        val files = _ui.value.files
        if (files.isEmpty()) { message(Strings.get(R.string.tools_add_first)); return }
        val p = pipeline() ?: return
        if (!studio.jobRunner.submit(PipelineOp(p), files)) message(Strings.get(R.string.tools_busy))
    }

    fun cancel() = studio.jobRunner.cancel()
    fun acknowledge() = studio.jobRunner.acknowledge()

    val askWhereToSave: Boolean get() = studio.settings.current.askWhereToSave
    fun savedFolder(): Uri? = studio.settings.current.saveFolder?.let { Uri.parse(it) }
    fun rememberFolder(uri: Uri) = studio.settings.update { it.copy(saveFolder = uri.toString()) }
    fun exportIntent(file: StudioFile): Intent = studio.exporter.createDocumentIntent(file.displayName, file.mimeType)
    fun folderIntent(): Intent = studio.exporter.openFolderIntent()
    fun shareIntent(files: List<StudioFile>): Intent = studio.exporter.shareIntent(files)

    fun writeTo(destination: Uri, file: StudioFile, done: (String?) -> Unit) {
        viewModelScope.launch { try { studio.exporter.writeTo(destination, file); done(null) } catch (e: Exception) { done(e.message) } }
    }

    fun writeInto(folder: Uri, files: List<StudioFile>, done: (String) -> Unit) {
        viewModelScope.launch {
            var saved = 0; var problem: String? = null
            for (f in files) { val p = studio.exporter.writeInto(folder, f); if (p == null) saved++ else { problem = p; break } }
            done(problem?.let { Strings.get(R.string.tools_saved_some, saved, it) } ?: Strings.get(R.string.tools_saved_all, saved))
        }
    }

    companion object { const val MAX_INPUTS = 100 }
}
