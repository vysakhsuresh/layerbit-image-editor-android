package com.layerbit.studio.image

import com.layerbit.studio.engine.Geometry

/**
 * How many pixels the app will hold at once.
 *
 * The working bitmap is 4 bytes a pixel, and the colour stage holds one IntArray copy of it,
 * so the peak is about 8 bytes a pixel plus the encoder's buffer. A twentieth of the heap in
 * pixels keeps that peak under half the heap: 24 MP on a 512 MB heap, 12 MP on 256 MB. The
 * clamp at 24 MP is the edge limit's area; the floor of 2 MP is what any device can carry.
 *
 * This is computed, not measured. VALIDATION.md lists the device run that confirms it, and the
 * number here changes only with a measurement in the commit.
 */
object Budget {
    val exportPixels: Long = (Runtime.getRuntime().maxMemory() / 20).coerceIn(2_000_000L, 24_000_000L)

    /** Previews need no more than a phone screen holds. */
    const val PREVIEW_PIXELS = 1_200_000L

    /** Thumbnails for lists. */
    const val THUMB_PIXELS = 60_000L

    const val MAX_EDGE = Geometry.MAX_EDGE

    /** The largest import accepted, in bytes. A 100 MB picture is not a picture a phone edits. */
    const val MAX_IMPORT_BYTES = 100L * 1024 * 1024
}
