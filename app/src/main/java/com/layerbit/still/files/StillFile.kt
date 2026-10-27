package com.layerbit.still.files

import android.net.Uri
import java.io.File

/**
 * A picture the app is allowed to read, and where it came from.
 *
 * The two origins are kept apart because the difference is the app's central safety rule. A
 * [Origin.Imported] file is the app's own copy and may be read freely; the Uri it came from is
 * never opened for writing. See [ImageStore].
 */
data class StillFile(
    /** The app's own copy. Safe to read, safe to delete, never the user's original. */
    val file: File,
    /** What to call it in the UI, carried through an operation chain. */
    val displayName: String,
    val origin: Origin,
    val mimeType: String,
    /** Upright pixels, as the user sees them, after any EXIF orientation. */
    val width: Int,
    val height: Int
) {
    val sizeBytes: Long get() = file.length()
    val isJpeg: Boolean get() = mimeType == MIME_JPEG

    sealed interface Origin {
        /** Picked through the photo picker or SAF, or received via a share. */
        data class Imported(val uri: Uri) : Origin

        /** Produced by an operation in this app. [step] is what made it. */
        data class Derived(val step: String) : Origin
    }

    companion object {
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PNG = "image/png"
        const val MIME_WEBP = "image/webp"
    }
}
