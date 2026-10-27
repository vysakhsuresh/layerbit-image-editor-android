package com.layerbit.studio.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.layerbit.studio.R
import com.layerbit.studio.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

/**
 * How a result leaves the app.
 *
 * This is the ONLY component permitted to open a Uri for writing, and the only Uri it may write
 * to is one the user just created through ACTION_CREATE_DOCUMENT or a folder they granted. The
 * system picker is what makes that safe: the user chose the place and the name, so the app can
 * never reach a picture they did not point at. If you are ever tempted to add a
 * `contentResolver.openOutputStream(source.uri)` anywhere else, that is the bug.
 */
class Exporter(private val context: Context) {

    fun createDocumentIntent(suggestedName: String, mimeType: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }

    /**
     * Copies [source] to the document the user picked. "wt" truncates, which it must, and the
     * picker can hand back a document the user chose to replace, so a copy that dies halfway
     * clears up and says plainly what state the destination is in.
     */
    suspend fun writeTo(destination: Uri, source: StudioFile) = withContext(Dispatchers.IO) {
        val descriptor = context.contentResolver.openFileDescriptor(destination, "wt")
            ?: throw IOException(Strings.get(R.string.app_error_write_location))
        try {
            descriptor.use {
                FileOutputStream(it.fileDescriptor).use { output ->
                    source.file.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                    syncIfSupported(it.fileDescriptor)
                }
            }
        } catch (e: IOException) {
            throw IOException(discardPartial(destination, source.displayName, mayHaveReplaced = true, cause = e), e)
        } catch (e: SecurityException) {
            throw IOException(discardPartial(destination, source.displayName, mayHaveReplaced = true, cause = e), e)
        }
    }

    /** Pushes bytes to the medium where the destination can; swallowed where it cannot. */
    private fun syncIfSupported(fd: FileDescriptor) {
        runCatching { fd.sync() }
    }

    private fun discardPartial(destination: Uri, name: String, mayHaveReplaced: Boolean, cause: Throwable? = null): String {
        val removed = runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }.getOrDefault(false)
        val outcome = when {
            removed && mayHaveReplaced -> Strings.get(R.string.app_error_write_removed_replaced, name)
            removed -> Strings.get(R.string.app_error_write_removed, name)
            mayHaveReplaced -> Strings.get(R.string.app_error_write_incomplete, name)
            else -> Strings.get(R.string.app_error_write_left_behind, name)
        }
        return reasonFor(cause)?.let { Strings.get(R.string.app_error_with_reason, outcome, it) } ?: outcome
    }

    private fun reasonFor(cause: Throwable?): String? = when {
        cause == null -> null
        cause is SecurityException -> Strings.get(R.string.app_error_permission_withdrawn)
        cause.message?.contains("ENOSPC") == true -> Strings.get(R.string.app_error_no_space)
        cause.message?.contains("EROFS") == true -> Strings.get(R.string.app_error_read_only)
        else -> null
    }

    fun openFolderIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    /**
     * Writes [source] into a folder the user granted earlier, never overwriting: a name already
     * in use is handed to the provider, which makes it unique. Null on success, else a sentence.
     */
    suspend fun writeInto(folder: Uri, source: StudioFile): String? = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, folder) ?: return@withContext Strings.get(R.string.app_error_folder_gone)
        if (!tree.canWrite()) return@withContext Strings.get(R.string.app_error_folder_denied)
        val target = tree.createFile(source.mimeType, source.displayName)
            ?: return@withContext Strings.get(R.string.app_error_create_failed, source.displayName)
        try {
            val descriptor = runCatching { context.contentResolver.openFileDescriptor(target.uri, "wt") }.getOrNull()
            if (descriptor != null) {
                descriptor.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        source.file.inputStream().use { input -> input.copyTo(output) }
                        output.flush()
                        syncIfSupported(it.fileDescriptor)
                    }
                }
            } else {
                val stream = context.contentResolver.openOutputStream(target.uri, "wt")
                    ?: return@withContext discardPartial(target.uri, source.displayName, mayHaveReplaced = false)
                stream.use { output ->
                    source.file.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                }
            }
        } catch (e: IOException) {
            return@withContext discardPartial(target.uri, source.displayName, mayHaveReplaced = false, cause = e)
        } catch (e: SecurityException) {
            return@withContext discardPartial(target.uri, source.displayName, mayHaveReplaced = false, cause = e)
        }
        null
    }

    fun shareIntent(source: StudioFile): Intent = shareIntent(listOf(source))

    /** Share one or several results in one gesture through the FileProvider. */
    fun shareIntent(sources: List<StudioFile>): Intent {
        val uris = ArrayList(sources.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it.file) })
        val type = sources.map { it.mimeType }.distinct().singleOrNull() ?: "image/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { this.type = type; putExtra(Intent.EXTRA_STREAM, uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { this.type = type; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}
