package com.layerbit.still.files

import android.net.Uri

/**
 * Pictures another app shared, waiting for the screen that can take them. A hand-off and not a
 * queue: [take] empties it, so returning to a tool an hour later does not reload what was
 * shared this morning. Uris only - nothing is read here.
 */
class Handoff {

    private var waiting: List<Uri> = emptyList()

    fun offer(uris: List<Uri>) {
        waiting = uris
    }

    fun take(): List<Uri> {
        val held = waiting
        waiting = emptyList()
        return held
    }

    val isWaiting: Boolean get() = waiting.isNotEmpty()
}
