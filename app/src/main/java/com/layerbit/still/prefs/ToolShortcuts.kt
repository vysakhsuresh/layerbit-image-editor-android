package com.layerbit.still.prefs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.layerbit.still.MainActivity
import com.layerbit.still.ops.ToolId
import com.layerbit.still.ui.icons.iconFor

/**
 * The launcher's long-press menu learns: beside the three fixed shortcuts, the tools this person
 * reached for last, so their own favourites are one press from the home screen.
 */
object ToolShortcuts {
    /** The fixed shortcuts in res/xml/shortcuts.xml; never repeated as dynamic ones. */
    private val FIXED = setOf("EDIT", "RESIZE_KB", "STRIP_METADATA")
    private const val DYNAMIC = 2

    fun publish(context: Context, recent: List<String>) {
        val tools = recent.mapNotNull { ToolId.byName(it) }.filter { it.name !in FIXED && it != ToolId.PROCESS && it != ToolId.COLLAGE }.take(DYNAMIC)
        val shortcuts = tools.mapIndexed { i, tool ->
            ShortcutInfoCompat.Builder(context, "recent-${tool.name}")
                .setShortLabel(tool.title.take(24))
                .setLongLabel(tool.title)
                .setIcon(IconCompat.createWithResource(context, iconFor(tool)))
                .setRank(i)
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("still://tool/${tool.name}"), context, MainActivity::class.java))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
