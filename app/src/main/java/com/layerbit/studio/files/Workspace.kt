package com.layerbit.studio.files

import android.content.Context
import com.layerbit.studio.engine.Names
import java.io.File

/**
 * Scratch space for operation output.
 *
 * Every operation writes a new file here rather than modifying its input, which is what makes
 * a chain undoable and a failure harmless: the worst case is a half-written file swept up on
 * the next launch. Nothing here survives a reinstall and nothing is backed up; the user's real
 * output went out through [Exporter].
 */
class Workspace(private val context: Context) {

    private val root: File
        get() = File(context.cacheDir, "work").apply { mkdirs() }

    /** A fresh file for an operation to write. [step] ends up in the name for debugging. */
    fun newOutput(baseName: String, step: String, extension: String): File {
        val safe = Names.safe(Names.stem(baseName))
        return File(root, "${System.nanoTime()}-$step-$safe.$extension")
    }

    fun sizeBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes oldest-first until scratch is under [limitBytes], never touching [protect]. */
    fun trimTo(limitBytes: Long, protect: Set<File> = emptySet()) {
        val files = root.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return
        val keep = protect.map { it.absolutePath }.toSet()
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= limitBytes) break
            if (file.absolutePath in keep) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** A few large exports. Past this, oldest results go. */
        const val DEFAULT_LIMIT_BYTES = 768L * 1024L * 1024L
    }
}
