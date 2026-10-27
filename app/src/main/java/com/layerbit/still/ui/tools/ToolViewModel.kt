package com.layerbit.still.ui.tools

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
import com.layerbit.still.engine.Collage
import com.layerbit.still.engine.Frame
import com.layerbit.still.files.ImageStore
import com.layerbit.still.files.StillFile
import com.layerbit.still.image.ExportOptions
import com.layerbit.still.image.OutputFormat
import com.layerbit.still.jobs.JobState
import com.layerbit.still.ops.CollageOp
import com.layerbit.still.ops.CompressOp
import com.layerbit.still.ops.EnhanceOp
import com.layerbit.still.ops.FitKbOp
import com.layerbit.still.ops.FrameOp
import com.layerbit.still.ops.Op
import com.layerbit.still.ops.RecipeOp
import com.layerbit.still.ops.ResizeOp
import com.layerbit.still.ops.StripMetadataOp
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ops.WatermarkOp
import com.layerbit.still.text.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything a batch tool can be told. One class for all tools; each reads the fields it needs. */
data class ToolConfig(
    val format: OutputFormat = OutputFormat.JPEG,
    val quality: Int = 92,
    val targetKb: String = "50",
    val pixels: String = "keep",
    val exactWidth: String = "",
    val exactHeight: String = "",
    val resizeMode: String = "edge",
    val percent: Int = 50,
    val longEdge: String = "1920",
    val keepCamera: Boolean = false,
    val allowShrink: Boolean = true,
    val watermarkText: String = "",
    val watermarkOpacity: Float = 0.35f,
    val watermarkAngle: Float = -30f,
    val watermarkSize: Float = 0.03f,
    val watermarkColor: Int = 0xFFFFFFFF.toInt(),
    val layout: Collage.Layout = Collage.Layout.GRID,
    val collageBackground: Int = 0xFFFFFFFF.toInt(),
    val presetName: String = "",
    val frame: Frame = Frame(padding = 0.05f),
    val pattern: String = "{name}"
)

/**
 * A batch tool's inputs, options, run and results. The job itself runs on the app-scoped
 * [com.layerbit.still.jobs.JobRunner], so it survives this screen; the screen observes.
 */
class ToolViewModel(app: Application) : AndroidViewModel(app) {

    private val still = app as StillApplication
    val jobState: StateFlow<JobState> get() = still.jobRunner.state

    data class Ui(
        val tool: ToolId = ToolId.RESIZE,
        val files: List<StillFile> = emptyList(),
        val importing: Boolean = false,
        val message: String? = null,
        val presets: List<ImageStore.Preset> = emptyList()
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    var config by mutableStateOf(ToolConfig(format = still.settings.current.defaultFormat, quality = still.settings.current.defaultQuality, keepCamera = still.settings.current.keepCameraFields))

    fun start(tool: ToolId) {
        if (_ui.value.tool != tool) {
            _ui.update { Ui(tool = tool, presets = still.store.presets()) }
            still.jobRunner.acknowledge()
        } else {
            _ui.update { it.copy(presets = still.store.presets()) }
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

    /** The op for the current tool and config, or null with a message when the config is not ready. */
    fun buildOp(): Op? {
        val c = config
        val export = ExportOptions(c.format, c.quality, 0, c.allowShrink, c.keepCamera)
        return when (_ui.value.tool) {
            ToolId.RESIZE -> {
                val mode = when (c.resizeMode) {
                    "exact" -> {
                        val w = c.exactWidth.toIntOrNull(); val h = c.exactHeight.toIntOrNull()
                        if (w == null || h == null || w !in 1..8192 || h !in 1..8192) { message(Strings.get(R.string.tools_need_dimensions)); return null }
                        ResizeOp.Mode.Exact(w, h)
                    }
                    "percent" -> ResizeOp.Mode.Percent(c.percent)
                    else -> {
                        val e = c.longEdge.toIntOrNull()
                        if (e == null || e !in 16..8192) { message(Strings.get(R.string.tools_need_edge)); return null }
                        ResizeOp.Mode.LongEdge(e)
                    }
                }
                ResizeOp(mode, export, c.pattern.ifBlank { "{name}-{w}x{h}" })
            }
            ToolId.RESIZE_KB -> {
                val kb = c.targetKb.toFloatOrNull()
                if (kb == null || kb < 2f) { message(Strings.get(R.string.engine_kb_too_small)); return null }
                val pixels = when (c.pixels) {
                    "passport" -> FitKbOp.Pixels.Passport
                    "exact" -> {
                        val w = c.exactWidth.toIntOrNull(); val h = c.exactHeight.toIntOrNull()
                        if (w == null || h == null || w !in 16..4096 || h !in 16..4096) { message(Strings.get(R.string.tools_need_dimensions)); return null }
                        FitKbOp.Pixels.Exact(w, h)
                    }
                    else -> FitKbOp.Pixels.Keep
                }
                FitKbOp(kb, pixels, c.keepCamera, c.pattern.ifBlank { "{name}-under" })
            }
            ToolId.COMPRESS -> {
                val kb = c.targetKb.toFloatOrNull()
                val target = if (c.pixels == "target" && kb != null && kb >= 2f) com.layerbit.still.engine.FitSize.limitBytes(kb, "KB") else 0
                val format = if (c.format.lossy) c.format else OutputFormat.JPEG
                CompressOp(ExportOptions(format, c.quality, target, c.allowShrink, c.keepCamera), ToolId.COMPRESS, c.pattern.ifBlank { "{name}-small" })
            }
            ToolId.CONVERT -> CompressOp(ExportOptions(c.format, c.quality, 0, false, c.keepCamera), ToolId.CONVERT, c.pattern.ifBlank { "{name}" })
            ToolId.STRIP_METADATA -> StripMetadataOp(c.pattern.ifBlank { "{name}-clean" })
            ToolId.ENHANCE -> EnhanceOp(export, c.pattern.ifBlank { "{name}-enhanced" })
            ToolId.APPLY_RECIPE -> {
                val preset = _ui.value.presets.firstOrNull { it.name == c.presetName } ?: run { message(Strings.get(R.string.tools_pick_preset)); return null }
                RecipeOp(preset.recipe, preset.name, export, c.pattern.ifBlank { "{name}-{n}" })
            }
            ToolId.WATERMARK -> {
                if (c.watermarkText.isBlank()) { message(Strings.get(R.string.tools_need_text)); return null }
                WatermarkOp(c.watermarkText, c.watermarkOpacity, c.watermarkAngle, c.watermarkSize, c.watermarkColor, export, c.pattern.ifBlank { "{name}-marked" })
            }
            ToolId.COLLAGE -> CollageOp(c.layout, c.collageBackground, export)
            ToolId.FRAME -> FrameOp(c.frame, export, c.pattern.ifBlank { "{name}-framed" })
            else -> null
        }
    }

    fun run() {
        val files = _ui.value.files
        if (files.isEmpty()) { message(Strings.get(R.string.tools_add_first)); return }
        val op = buildOp() ?: return
        val arity = op.arity
        if (arity is Op.Arity.Together && files.size < arity.min) { message(Strings.get(R.string.tools_need_more, arity.min)); return }
        if (!still.jobRunner.submit(op, files)) message(Strings.get(R.string.tools_busy))
    }

    fun cancel() = still.jobRunner.cancel()
    fun acknowledge() = still.jobRunner.acknowledge()

    // ------------------------------------------------------------------ saving

    val askWhereToSave: Boolean get() = still.settings.current.askWhereToSave
    fun savedFolder(): Uri? = still.settings.current.saveFolder?.let { Uri.parse(it) }
    fun rememberFolder(uri: Uri) = still.settings.update { it.copy(saveFolder = uri.toString()) }
    fun exportIntent(file: StillFile): Intent = still.exporter.createDocumentIntent(file.displayName, file.mimeType)
    fun folderIntent(): Intent = still.exporter.openFolderIntent()
    fun shareIntent(files: List<StillFile>): Intent = still.exporter.shareIntent(files)

    fun writeTo(destination: Uri, file: StillFile, done: (String?) -> Unit) {
        viewModelScope.launch {
            try { still.exporter.writeTo(destination, file); done(null) } catch (e: Exception) { done(e.message) }
        }
    }

    fun writeInto(folder: Uri, files: List<StillFile>, done: (String) -> Unit) {
        viewModelScope.launch {
            var saved = 0
            var problem: String? = null
            for (f in files) {
                val p = still.exporter.writeInto(folder, f)
                if (p == null) saved++ else { problem = p; break }
            }
            done(problem?.let { Strings.get(R.string.tools_saved_some, saved, it) } ?: Strings.get(R.string.tools_saved_all, saved))
        }
    }

    companion object { const val MAX_INPUTS = 100 }
}
