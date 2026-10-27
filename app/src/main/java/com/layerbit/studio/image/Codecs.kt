package com.layerbit.studio.image

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.exifinterface.media.ExifInterface
import com.layerbit.studio.R
import com.layerbit.studio.engine.FitSize
import com.layerbit.studio.engine.JpegSegments
import com.layerbit.studio.files.StudioFile
import com.layerbit.studio.text.Strings
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

enum class OutputFormat(val extension: String, val mime: String, @StringRes val labelRes: Int, val lossy: Boolean, val alpha: Boolean) {
    JPEG("jpg", StudioFile.MIME_JPEG, R.string.format_jpeg, lossy = true, alpha = false),
    PNG("png", StudioFile.MIME_PNG, R.string.format_png, lossy = false, alpha = true),
    WEBP("webp", StudioFile.MIME_WEBP, R.string.format_webp, lossy = true, alpha = true),
    WEBP_LOSSLESS("webp", StudioFile.MIME_WEBP, R.string.format_webp_lossless, lossy = false, alpha = true);

    val label: String get() = Strings.get(labelRes)

    /** WebP lossless arrived in API 30; below it the option is not offered. */
    val available: Boolean get() = this != WEBP_LOSSLESS || Build.VERSION.SDK_INT >= 30

    companion object {
        fun fromMime(mime: String?): OutputFormat? = entries.firstOrNull { it.mime == mime }
    }
}

data class ExportOptions(
    val format: OutputFormat = OutputFormat.JPEG,
    /** The ceiling; the target-size search may go lower. */
    val quality: Int = 92,
    /** 0 means no target. */
    val targetBytes: Int = 0,
    /** When the target cannot be met at full size, let the picture shrink. */
    val allowShrink: Boolean = true,
    /** Copy make, model, date and exposure into a JPEG. Location never. */
    val keepCameraFields: Boolean = false
) {
    val valid: Boolean get() = quality in 1..100 && targetBytes >= 0 && !(targetBytes > 0 && !format.lossy)
}

/** What came out, measured on the file that was actually written. */
data class Exported(
    val file: File,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val format: OutputFormat,
    /** The encoder quality used, or -1 for lossless. */
    val quality: Int,
    /** Sentences for the user: what was kept, what was removed, what changed. */
    val notes: List<String>,
    /** The picture had to shrink to meet the target. */
    val shrunk: Boolean
)

/**
 * Pixels to bytes, with the search for a size done in memory and the result measured on disk.
 *
 * Everything the user is told about an export is read back from the file that was written:
 * the bytes are the file's bytes, the dimensions are the picture's, and a limit is a hard
 * maximum after the metadata has gone in, not an estimate before it.
 */
class Codecs(private val renderer: Renderer) {

    class TargetUnreachable(message: String) : IllegalArgumentException(message)

    fun encode(b: Bitmap, format: OutputFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(max(64 * 1024, b.width * b.height / 4))
        val codec = when (format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            OutputFormat.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val q = if (format == OutputFormat.WEBP_LOSSLESS && Build.VERSION.SDK_INT < 30) 100 else quality
        require(b.compress(codec, q, out)) { Strings.get(R.string.engine_encoder_failed) }
        return out.toByteArray()
    }

    /**
     * Writes [picture] to [destination] under [options]. [sourceForExif] is the original the
     * camera fields may be copied from. The bitmap is not recycled.
     */
    fun export(picture: Bitmap, options: ExportOptions, destination: File, sourceForExif: File?, scaledFromSource: Boolean, check: () -> Unit = {}): Exported {
        require(options.valid) { Strings.get(R.string.engine_options_invalid) }
        val format = options.format
        var working = if (!format.alpha) renderer.flatten(picture) else picture
        var shrunk = false
        try {
            var quality = if (format.lossy) options.quality else 100
            var bytes: ByteArray
            if (options.targetBytes > 0 && format.lossy) {
                val limit = options.targetBytes - exifAllowance(options)
                check()
                var shot = FitSize.bestQuality(limit, options.quality) { q -> check(); encode(working, format, q) }
                if (!shot.fits) {
                    if (!options.allowShrink) throw TargetUnreachable(Strings.get(R.string.engine_target_unreachable_fixed, FitSize.sizeText(options.targetBytes.toLong())))
                    val scaled = FitSize.bestSize(limit, working.width, working.height, options.quality) { w, h, q ->
                        check()
                        val small = renderer.scale(working, w, h)
                        try { encode(small, format, q) } finally { if (small !== working) small.recycle() }
                    } ?: throw TargetUnreachable(Strings.get(R.string.engine_target_unreachable_any, FitSize.sizeText(options.targetBytes.toLong())))
                    val small = renderer.scale(working, scaled.width, scaled.height)
                    if (working !== picture) working.recycle()
                    working = small
                    shot = scaled.shot
                    shrunk = true
                }
                quality = shot.quality
                bytes = shot.bytes
            } else {
                check()
                bytes = encode(working, format, quality)
            }
            destination.writeBytes(bytes)
            if (format == OutputFormat.JPEG && options.keepCameraFields && sourceForExif != null) {
                copyCameraFields(sourceForExif, destination)
                // The fields can push a file over its limit; measured, so step down until under.
                while (options.targetBytes > 0 && destination.length() > options.targetBytes && quality > FitSize.LOWEST_QUALITY) {
                    quality -= 2
                    destination.writeBytes(encode(working, format, quality))
                    copyCameraFields(sourceForExif, destination)
                }
            }
            check()
            if (options.targetBytes > 0) require(destination.length() <= options.targetBytes) { Strings.get(R.string.engine_target_unreachable_any, FitSize.sizeText(options.targetBytes.toLong())) }
            val notes = buildList {
                if (!format.alpha) add(Strings.get(R.string.engine_note_flattened))
                add(Strings.get(if (format.lossy) R.string.engine_note_lossy else R.string.engine_note_lossless))
                add(Strings.get(if (format == OutputFormat.JPEG && options.keepCameraFields) R.string.engine_note_camera_kept else R.string.engine_note_metadata_removed))
                if (shrunk) add(Strings.get(R.string.engine_note_shrunk_for_target, working.width, working.height))
                if (scaledFromSource) add(Strings.get(R.string.engine_note_scaled_from_source))
            }
            return Exported(destination, working.width, working.height, destination.length(), format, if (format.lossy) quality else -1, notes, shrunk)
        } catch (t: Throwable) {
            destination.delete()
            throw t
        } finally {
            if (working !== picture) working.recycle()
        }
    }

    /** Room left for the camera fields when a limit is set: they are measured afterwards anyway. */
    private fun exifAllowance(o: ExportOptions) = if (o.keepCameraFields && o.format == OutputFormat.JPEG) 1024 else 0

    /**
     * The handful of fields a user may choose to keep. Written one by one from a list, never by
     * copying the EXIF block, so nothing arrives that is not on this list - not the GPS fix,
     * not the camera's serial, not the embedded thumbnail with its own copy of the location.
     */
    fun copyCameraFields(source: File, target: File) {
        val from = runCatching { ExifInterface(source) }.getOrNull() ?: return
        val to = ExifInterface(target)
        for (tag in CAMERA_TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        to.saveAttributes()
    }

    /**
     * The same picture with every hidden segment removed and not one pixel re-encoded. Null when
     * the file is not a JPEG this can walk, in which case the caller re-encodes and says so.
     */
    fun losslessStrip(source: File, destination: File): LosslessStrip? {
        val bytes = source.readBytes()
        if (!JpegSegments.isJpeg(bytes)) return null
        val hadGps = JpegSegments.hasGps(bytes)
        val hadHidden = JpegSegments.hasHiddenData(bytes)
        val stripped = JpegSegments.strip(bytes) ?: return null
        destination.writeBytes(stripped)
        return LosslessStrip(bytes.size.toLong(), stripped.size.toLong(), hadGps, hadHidden)
    }

    data class LosslessStrip(val before: Long, val after: Long, val hadGps: Boolean, val hadHidden: Boolean)

    companion object {
        val CAMERA_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_LENS_MODEL
        )
    }
}
