package com.layerbit.studio.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.layerbit.studio.R
import com.layerbit.studio.ops.ToolId

/** The static drawing for each tool, kept in the UI layer so ops/ stays free of resource ids. */
@DrawableRes
fun iconFor(tool: ToolId): Int = when (tool) {
    ToolId.EDIT -> R.drawable.ic_tool_edit
    ToolId.CROP -> R.drawable.ic_tool_crop
    ToolId.PERSPECTIVE -> R.drawable.ic_tool_perspective
    ToolId.LIGHT -> R.drawable.ic_tool_light
    ToolId.COLOUR -> R.drawable.ic_tool_colour
    ToolId.CURVES -> R.drawable.ic_tool_curves
    ToolId.LOOKS -> R.drawable.ic_tool_looks
    ToolId.DETAIL -> R.drawable.ic_tool_detail
    ToolId.MARKUP -> R.drawable.ic_tool_markup
    ToolId.RETOUCH -> R.drawable.ic_tool_retouch
    ToolId.FRAME -> R.drawable.ic_tool_frame
    ToolId.WATERMARK -> R.drawable.ic_tool_watermark
    ToolId.RESIZE -> R.drawable.ic_tool_resize
    ToolId.RESIZE_KB -> R.drawable.ic_tool_resize_kb
    ToolId.COMPRESS -> R.drawable.ic_tool_compress
    ToolId.CONVERT -> R.drawable.ic_tool_convert
    ToolId.STRIP_METADATA -> R.drawable.ic_tool_strip
    ToolId.DETAILS -> R.drawable.ic_tool_details
    ToolId.ENHANCE -> R.drawable.ic_tool_enhance
    ToolId.APPLY_RECIPE -> R.drawable.ic_tool_recipe
    ToolId.COLLAGE -> R.drawable.ic_tool_collage
}

/** The panel's icon, for the strip under the editor canvas. */
@DrawableRes
fun iconFor(panel: ToolId.Panel): Int = when (panel) {
    ToolId.Panel.NONE, ToolId.Panel.CROP -> R.drawable.ic_tool_crop
    ToolId.Panel.PERSPECTIVE -> R.drawable.ic_tool_perspective
    ToolId.Panel.LIGHT -> R.drawable.ic_tool_light
    ToolId.Panel.COLOUR -> R.drawable.ic_tool_colour
    ToolId.Panel.CURVES -> R.drawable.ic_tool_curves
    ToolId.Panel.LOOKS -> R.drawable.ic_tool_looks
    ToolId.Panel.DETAIL -> R.drawable.ic_tool_detail
    ToolId.Panel.MARKUP -> R.drawable.ic_tool_markup
    ToolId.Panel.RETOUCH -> R.drawable.ic_tool_retouch
    ToolId.Panel.LAYERS -> R.drawable.ic_tool_layers
    ToolId.Panel.FRAME -> R.drawable.ic_tool_frame
    ToolId.Panel.DETAILS -> R.drawable.ic_tool_details
    ToolId.Panel.EXPORT -> R.drawable.ic_tool_export
}

/** Which drawn, moving icon replaces each static one. A drawable with no entry stays still. */
fun glyphFor(@DrawableRes icon: Int): Glyph? = when (icon) {
    R.drawable.ic_tool_edit, R.drawable.ic_shortcut_edit -> Glyph.EDIT
    R.drawable.ic_tool_crop -> Glyph.CROP
    R.drawable.ic_tool_perspective -> Glyph.PERSPECTIVE
    R.drawable.ic_tool_light -> Glyph.LIGHT
    R.drawable.ic_tool_colour -> Glyph.COLOUR
    R.drawable.ic_tool_curves -> Glyph.CURVES
    R.drawable.ic_tool_looks -> Glyph.LOOKS
    R.drawable.ic_tool_detail -> Glyph.DETAIL
    R.drawable.ic_tool_markup -> Glyph.MARKUP
    R.drawable.ic_tool_retouch -> Glyph.RETOUCH
    R.drawable.ic_tool_frame -> Glyph.FRAME
    R.drawable.ic_tool_watermark -> Glyph.WATERMARK
    R.drawable.ic_tool_resize -> Glyph.RESIZE
    R.drawable.ic_tool_resize_kb, R.drawable.ic_shortcut_resize -> Glyph.RESIZE_KB
    R.drawable.ic_tool_compress -> Glyph.COMPRESS
    R.drawable.ic_tool_convert -> Glyph.CONVERT
    R.drawable.ic_tool_strip, R.drawable.ic_shortcut_strip -> Glyph.STRIP
    R.drawable.ic_tool_details -> Glyph.DETAILS
    R.drawable.ic_tool_enhance -> Glyph.ENHANCE
    R.drawable.ic_tool_recipe -> Glyph.RECIPE
    R.drawable.ic_tool_collage -> Glyph.COLLAGE
    R.drawable.ic_tool_layers -> Glyph.LAYERS
    R.drawable.ic_tool_export -> Glyph.EXPORT
    R.drawable.ic_lock -> Glyph.LOCK
    R.drawable.ic_undo -> Glyph.UNDO
    R.drawable.ic_redo -> Glyph.REDO
    R.drawable.ic_plus -> Glyph.PLUS
    R.drawable.ic_compare -> Glyph.COMPARE
    else -> null
}

/** The Material glyphs that have a drawn counterpart. Arrows and crosses stay still. */
fun glyphFor(icon: ImageVector): Glyph? = when (icon.name.substringAfterLast('.')) {
    "Settings" -> Glyph.GEAR
    "Star" -> Glyph.STAR
    "Favorite" -> Glyph.HEART
    "Search" -> Glyph.SEARCH
    "Info" -> Glyph.INFO
    "Share" -> Glyph.SHARE
    "Send" -> Glyph.SEND
    "Email" -> Glyph.MAIL
    "Lock" -> Glyph.LOCK
    "Check" -> Glyph.CHECK
    "Add" -> Glyph.PLUS
    else -> null
}

/** A steady seed per icon, so each moves at its own moment in the lap. */
fun glyphSeed(key: Any): Int = (key.hashCode() and 0x7fffffff) % 89
