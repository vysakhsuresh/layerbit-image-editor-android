package com.layerbit.still.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.layerbit.still.R
import com.layerbit.still.engine.JpegSegments
import com.layerbit.still.engine.Names
import com.layerbit.still.engine.Recipe
import com.layerbit.still.engine.RecipeJson
import com.layerbit.still.image.Budget
import com.layerbit.still.image.Renderer
import com.layerbit.still.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The only way a user's picture enters this app.
 *
 * THE APP NEVER WRITES TO A FILE THE USER PICKED. [import] copies the picture into private
 * storage and hands back a [StillFile] pointing at the copy; every operation takes a
 * [StillFile] and can therefore only ever reach the copy. The original Uri survives for
 * provenance, and the only component allowed to open a Uri for writing is [Exporter], against a
 * new document the user just created.
 *
 * A [Project] is an imported picture with the recipe the editor has written for it, kept in
 * filesDir so it is there next week; a one-off batch input lives in the cache.
 */
class ImageStore(private val context: Context, private val renderer: Renderer) {

    data class Project(
        val id: String,
        val file: StillFile,
        val recipe: Recipe,
        val modified: Long
    ) {
        val name: String get() = file.displayName
    }

    private val projectsDir: File get() = File(context.filesDir, "projects").apply { mkdirs() }
    private val presetsDir: File get() = File(context.filesDir, "presets").apply { mkdirs() }
    private val importDir: File get() = File(context.cacheDir, "imported").apply { mkdirs() }
    private val thumbDir: File get() = File(context.cacheDir, "thumbs").apply { mkdirs() }

    private val thumbs = object : LruCache<String, Bitmap>(24) {}

    // ------------------------------------------------------------------ import

    /** Copies [uri] into the cache for a batch. Throws with a sentence when it cannot. */
    suspend fun import(uri: Uri, check: () -> Unit = {}): StillFile = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val target = File(importDir, "${System.nanoTime()}-${Names.safe(name)}")
        copy(uri, target, name, check)
        describe(target, name, StillFile.Origin.Imported(uri))
    }

    /** Copies [uri] into a project of its own, where the editor keeps its recipe. */
    suspend fun importProject(uri: Uri, check: () -> Unit = {}): Project = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val id = UUID.randomUUID().toString()
        val folder = File(projectsDir, id).apply { mkdirs() }
        try {
            val target = File(folder, "source")
            copy(uri, target, name, check)
            val file = describe(target, name, StillFile.Origin.Imported(uri))
            val project = Project(id, file, Recipe(), System.currentTimeMillis())
            save(project)
            project
        } catch (t: Throwable) {
            folder.deleteRecursively(); throw t
        }
    }

    /** A picture the app itself produced, adopted as a project so it can be edited further. */
    suspend fun adoptAsProject(source: StillFile): Project = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val folder = File(projectsDir, id).apply { mkdirs() }
        try {
            val target = File(folder, "source")
            source.file.copyTo(target, overwrite = true)
            val file = describe(target, source.displayName, StillFile.Origin.Derived("adopted"))
            val project = Project(id, file, Recipe(), System.currentTimeMillis())
            save(project)
            project
        } catch (t: Throwable) {
            folder.deleteRecursively(); throw t
        }
    }

    private fun copy(uri: Uri, target: File, name: String, check: () -> Unit) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException(Strings.get(R.string.app_error_open_revoked, name))
        var total = 0L
        input.use { ins ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check()
                    val n = ins.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > Budget.MAX_IMPORT_BYTES) { target.delete(); throw IOException(Strings.get(R.string.app_error_too_large, name)) }
                    out.write(buffer, 0, n)
                }
            }
        }
        if (total == 0L) { target.delete(); throw IOException(Strings.get(R.string.app_error_empty, name)) }
    }

    /** Reads the header, refusing anything that is not a still image the platform can decode. */
    private fun describe(file: File, name: String, origin: StillFile.Origin): StillFile {
        val head = readHead(file, 64)
        val kind = JpegSegments.kindOf(head)
        val size = renderer.bounds(file) ?: run { file.delete(); throw IOException(Strings.get(R.string.app_error_not_image, name)) }
        val mime = kind?.mime ?: runCatching { context.contentResolver.getType(Uri.fromFile(file)) }.getOrNull() ?: "image/*"
        return StillFile(file, name, origin, mime, size.width, size.height)
    }

    private fun displayName(uri: Uri): String =
        runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "image"

    // ------------------------------------------------------------------ projects

    @Synchronized
    fun save(p: Project) {
        val folder = File(projectsDir, p.id).apply { mkdirs() }
        val f = p.file
        val json = com.layerbit.still.engine.jsonOf(
            "name" to com.layerbit.still.engine.JsonValue.Str(f.displayName),
            "mime" to com.layerbit.still.engine.JsonValue.Str(f.mimeType),
            "width" to com.layerbit.still.engine.JsonValue.Num(f.width.toDouble()),
            "height" to com.layerbit.still.engine.JsonValue.Num(f.height.toDouble()),
            "origin" to com.layerbit.still.engine.JsonValue.Str((f.origin as? StillFile.Origin.Imported)?.uri?.toString() ?: ""),
            "modified" to com.layerbit.still.engine.JsonValue.Num(System.currentTimeMillis().toDouble()),
            "recipe" to RecipeJson.toJson(p.recipe)
        )
        val tmp = File(folder, "project.tmp")
        tmp.writeText(com.layerbit.still.engine.Json.write(json))
        check(tmp.renameTo(File(folder, "project.json")))
    }

    fun projects(): List<Project> = projectsDir.listFiles().orEmpty()
        .mapNotNull { dir -> runCatching { load(dir) }.getOrNull() }
        .sortedByDescending { it.modified }

    fun project(id: String): Project? = runCatching { load(File(projectsDir, id)) }.getOrNull()

    private fun load(dir: File): Project {
        val o = com.layerbit.still.engine.Json.parse(File(dir, "project.json").readText())
        val source = File(dir, "source")
        require(source.exists())
        val originText = o["origin"]?.str().orEmpty()
        val origin = if (originText.isBlank()) StillFile.Origin.Derived("adopted") else StillFile.Origin.Imported(Uri.parse(originText))
        val file = StillFile(
            source, o["name"]?.str() ?: "image", origin, o["mime"]?.str() ?: "image/*",
            o["width"]?.num()?.toInt() ?: 0, o["height"]?.num()?.toInt() ?: 0
        )
        val recipe = o["recipe"]?.let { RecipeJson.read(com.layerbit.still.engine.Json.write(it)) } ?: Recipe()
        return Project(dir.name, file, recipe, o["modified"]?.num()?.toLong() ?: File(dir, "project.json").lastModified())
    }

    fun remove(p: Project) {
        File(projectsDir, p.id).deleteRecursively()
        thumbs.remove(p.id)
        File(thumbDir, "${p.id}.jpg").delete()
    }

    fun projectsBytes(): Long = projectsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ------------------------------------------------------------------ presets

    data class Preset(val name: String, val recipe: Recipe)

    fun savePreset(name: String, recipe: Recipe) {
        File(presetsDir, Names.safe(name, 60) + ".json").writeText(RecipeJson.write(recipe.asPreset()))
    }

    /** The look of the last picture edited, kept so the next one can start the same. */
    private val lastFile: File get() = File(context.filesDir, "last-edits.json")

    fun saveLastEdits(recipe: Recipe) {
        val look = recipe.asPreset()
        if (look.isIdentity) return
        runCatching { lastFile.writeText(RecipeJson.write(look)) }
    }

    fun lastEdits(): Recipe? = runCatching { if (lastFile.exists()) RecipeJson.read(lastFile.readText()) else null }.getOrNull()?.takeIf { !it.isIdentity }

    fun presets(): List<Preset> = presetsDir.listFiles().orEmpty().sortedBy { it.name }
        .mapNotNull { f -> runCatching { Preset(f.nameWithoutExtension, RecipeJson.read(f.readText())) }.getOrNull() }

    fun removePreset(name: String) {
        File(presetsDir, Names.safe(name, 60) + ".json").delete()
    }

    /** Renames a preset; false when the new name is taken or empty, so nothing is overwritten. */
    fun renamePreset(from: String, to: String): Boolean {
        val target = File(presetsDir, Names.safe(to, 60) + ".json")
        val source = File(presetsDir, Names.safe(from, 60) + ".json")
        if (to.isBlank() || !source.exists() || (target.exists() && target != source)) return false
        return source.renameTo(target)
    }

    // ------------------------------------------------------------------ thumbnails

    /** A small upright thumbnail, from a disk cache, decoded once per project. */
    suspend fun thumbnail(p: Project): Bitmap? = withContext(Dispatchers.IO) {
        thumbs.get(p.id)?.let { return@withContext it }
        val cached = File(thumbDir, "${p.id}.jpg")
        val fromDisk = if (cached.exists() && cached.lastModified() >= p.file.file.lastModified()) Renderer.thumbnail(cached, Budget.THUMB_PIXELS * 2) else null
        val bitmap = fromDisk ?: Renderer.thumbnail(p.file.file, Budget.THUMB_PIXELS * 2)?.also { b ->
            runCatching { cached.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        }
        bitmap?.also { thumbs.put(p.id, it) }
    }

    suspend fun thumbnail(f: StillFile): Bitmap? = withContext(Dispatchers.IO) {
        thumbs.get(f.file.path) ?: (if (f.isPdf) pdfThumbnail(f.file) else Renderer.thumbnail(f.file))?.also { thumbs.put(f.file.path, it) }
    }

    /** The first page of a PDF the app wrote itself, read-only from its private copy, on white. */
    private fun pdfThumbnail(file: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val k = kotlin.math.sqrt(Budget.THUMB_PIXELS.toDouble() / (page.width.toLong() * page.height).coerceAtLeast(1))
                    val w = (page.width * k).toInt().coerceAtLeast(1); val h = (page.height * k).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(android.graphics.Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    // ------------------------------------------------------------------ details

    /** What the source says about itself, as lines for the Details panel. Never the location itself. */
    fun details(f: StillFile): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += Strings.get(R.string.details_dimensions) to "${f.width} × ${f.height}"
        out += Strings.get(R.string.details_size) to com.layerbit.still.engine.FitSize.sizeText(f.sizeBytes)
        out += Strings.get(R.string.details_type) to f.mimeType
        val head = runCatching { readHead(f.file, 64) }.getOrNull()
        val exif = runCatching { ExifInterface(f.file) }.getOrNull()
        if (exif != null) {
            for (tag in listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH)) {
                exif.getAttribute(tag)?.let { out += tag.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() } to it }
            }
            val hasLocation = exif.latLong != null
            out += Strings.get(R.string.details_location) to Strings.get(if (hasLocation) R.string.details_location_present else R.string.details_location_none)
        }
        if (head != null) {
            val hidden = when (JpegSegments.kindOf(head)) {
                JpegSegments.Kind.JPEG -> runCatching { JpegSegments.hasHiddenData(f.file.readBytes()) }.getOrDefault(false)
                JpegSegments.Kind.PNG -> runCatching { JpegSegments.pngHasHiddenChunks(f.file.readBytes()) }.getOrDefault(false)
                else -> false
            }
            out += Strings.get(R.string.details_hidden) to Strings.get(if (hidden) R.string.details_hidden_present else R.string.details_hidden_none)
        }
        return out
    }

    /** One thing in a file that could say who, where or when; [serious] ones identify a person or place. */
    data class Risk(val label: String, val serious: Boolean)

    /**
     * What in [f] could identify the person who shared it, most serious first: where it was taken,
     * whose camera, whose name, when, and data a viewer never shows. Values are not repeated here.
     */
    fun risks(f: StillFile): List<Risk> {
        val out = ArrayList<Risk>()
        val exif = runCatching { ExifInterface(f.file) }.getOrNull()
        if (exif != null) {
            if (exif.latLong != null) out += Risk(Strings.get(R.string.risk_location), true)
            if (listOf(ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_ARTIST).any { !exif.getAttribute(it).isNullOrBlank() }) out += Risk(Strings.get(R.string.risk_owner), true)
            if (listOf(ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER).any { !exif.getAttribute(it).isNullOrBlank() }) out += Risk(Strings.get(R.string.risk_serial), true)
            if (!exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()) out += Risk(Strings.get(R.string.risk_time), false)
            if (!exif.getAttribute(ExifInterface.TAG_MODEL).isNullOrBlank()) out += Risk(Strings.get(R.string.risk_device), false)
        }
        val head = runCatching { readHead(f.file, 64) }.getOrNull()
        if (head != null) {
            val hidden = when (JpegSegments.kindOf(head)) {
                JpegSegments.Kind.JPEG -> runCatching { JpegSegments.hasHiddenData(f.file.readBytes()) }.getOrDefault(false)
                JpegSegments.Kind.PNG -> runCatching { JpegSegments.pngHasHiddenChunks(f.file.readBytes()) }.getOrDefault(false)
                else -> false
            }
            if (hidden) out += Risk(Strings.get(R.string.risk_hidden), false)
        }
        return out.sortedByDescending { it.serious }
    }

    /** The first [n] bytes, or fewer for a shorter file. readNBytes is API 33, so by hand. */
    private fun readHead(file: File, n: Int): ByteArray = file.inputStream().use { ins ->
        val buffer = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = ins.read(buffer, read, n - read)
            if (got < 0) break
            read += got
        }
        buffer.copyOf(read)
    }

    // ------------------------------------------------------------------ housekeeping

    fun clearImports() {
        importDir.listFiles()?.forEach { it.delete() }
    }

    fun clearThumbnails() {
        thumbs.evictAll()
        thumbDir.listFiles()?.forEach { it.delete() }
    }
}
