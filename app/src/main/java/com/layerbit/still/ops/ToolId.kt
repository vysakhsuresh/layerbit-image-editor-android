package com.layerbit.still.ops

import androidx.annotation.StringRes
import com.layerbit.still.R
import com.layerbit.still.text.Strings

/**
 * Every capability the app has, by name, for jobs, shortcuts, icons and the about screen.
 *
 * A tool either opens the editor on one picture with a panel in front ([Mode.EDITOR]), or runs
 * an [Op] over any number of pictures ([Mode.BATCH]). The front door shows three doors and one
 * batch screen ([PROCESS]) that composes the batch tools as steps; the entries stay so each op
 * keeps its own title, icon and test.
 */
enum class ToolId(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    @StringRes val actionRes: Int,
    val group: Group,
    val mode: Mode,
    /** For editor tools, the panel to open on. */
    val panel: Panel = Panel.NONE
) {
    EDIT(R.string.tool_edit_title, R.string.tool_edit_summary, R.string.tool_edit_action, Group.EDIT, Mode.EDITOR, Panel.NONE),
    CROP(R.string.tool_crop_title, R.string.tool_crop_summary, R.string.tool_crop_action, Group.EDIT, Mode.EDITOR, Panel.CROP),
    PERSPECTIVE(R.string.tool_perspective_title, R.string.tool_perspective_summary, R.string.tool_perspective_action, Group.EDIT, Mode.EDITOR, Panel.PERSPECTIVE),
    LIGHT(R.string.tool_light_title, R.string.tool_light_summary, R.string.tool_light_action, Group.EDIT, Mode.EDITOR, Panel.LIGHT),
    COLOUR(R.string.tool_colour_title, R.string.tool_colour_summary, R.string.tool_colour_action, Group.EDIT, Mode.EDITOR, Panel.COLOUR),
    CURVES(R.string.tool_curves_title, R.string.tool_curves_summary, R.string.tool_curves_action, Group.EDIT, Mode.EDITOR, Panel.CURVES),
    LOOKS(R.string.tool_looks_title, R.string.tool_looks_summary, R.string.tool_looks_action, Group.EDIT, Mode.EDITOR, Panel.LOOKS),
    DETAIL(R.string.tool_detail_title, R.string.tool_detail_summary, R.string.tool_detail_action, Group.EDIT, Mode.EDITOR, Panel.DETAIL),

    MARKUP(R.string.tool_markup_title, R.string.tool_markup_summary, R.string.tool_markup_action, Group.MARK, Mode.EDITOR, Panel.MARKUP),
    RETOUCH(R.string.tool_retouch_title, R.string.tool_retouch_summary, R.string.tool_retouch_action, Group.MARK, Mode.EDITOR, Panel.RETOUCH),
    FRAME(R.string.tool_frame_title, R.string.tool_frame_summary, R.string.tool_frame_action, Group.MARK, Mode.EDITOR, Panel.FRAME),
    WATERMARK(R.string.tool_watermark_title, R.string.tool_watermark_summary, R.string.tool_watermark_action, Group.MARK, Mode.BATCH),

    RESIZE(R.string.tool_resize_title, R.string.tool_resize_summary, R.string.tool_resize_action, Group.SIZE, Mode.BATCH),
    RESIZE_KB(R.string.tool_resize_kb_title, R.string.tool_resize_kb_summary, R.string.tool_resize_kb_action, Group.SIZE, Mode.BATCH),
    COMPRESS(R.string.tool_compress_title, R.string.tool_compress_summary, R.string.tool_compress_action, Group.SIZE, Mode.BATCH),
    CONVERT(R.string.tool_convert_title, R.string.tool_convert_summary, R.string.tool_convert_action, Group.SIZE, Mode.BATCH),

    STRIP_METADATA(R.string.tool_strip_title, R.string.tool_strip_summary, R.string.tool_strip_action, Group.PRIVACY, Mode.BATCH),
    DETAILS(R.string.tool_details_title, R.string.tool_details_summary, R.string.tool_details_action, Group.PRIVACY, Mode.EDITOR, Panel.DETAILS),

    ENHANCE(R.string.tool_enhance_title, R.string.tool_enhance_summary, R.string.tool_enhance_action, Group.BATCH, Mode.BATCH),
    APPLY_RECIPE(R.string.tool_recipe_title, R.string.tool_recipe_summary, R.string.tool_recipe_action, Group.BATCH, Mode.BATCH),
    COLLAGE(R.string.tool_collage_title, R.string.tool_collage_summary, R.string.tool_collage_action, Group.BATCH, Mode.BATCH),
    /** The one batch screen: every step composed into one run. */
    PROCESS(R.string.tool_process_title, R.string.tool_process_summary, R.string.tool_process_action, Group.BATCH, Mode.BATCH),

    CUTOUT(R.string.tool_cutout_title, R.string.tool_cutout_summary, R.string.tool_cutout_action, Group.MARK, Mode.EDITOR, Panel.CUTOUT),
    SIGNATURE(R.string.tool_signature_title, R.string.tool_signature_summary, R.string.tool_signature_action, Group.MARK, Mode.EDITOR, Panel.CUTOUT),
    STICKER(R.string.tool_sticker_title, R.string.tool_sticker_summary, R.string.tool_sticker_action, Group.MARK, Mode.EDITOR, Panel.CUTOUT),
    SCAN(R.string.tool_scan_title, R.string.tool_scan_summary, R.string.tool_scan_action, Group.EDIT, Mode.EDITOR, Panel.SCAN),
    RED_EYE(R.string.tool_redeye_title, R.string.tool_redeye_summary, R.string.tool_redeye_action, Group.MARK, Mode.EDITOR, Panel.RETOUCH),
    WHITE_BALANCE(R.string.tool_neutral_title, R.string.tool_neutral_summary, R.string.tool_neutral_action, Group.EDIT, Mode.EDITOR, Panel.COLOUR),
    PASSPORT(R.string.tool_passport_title, R.string.tool_passport_summary, R.string.tool_passport_action, Group.SIZE, Mode.BATCH);

    val title: String get() = Strings.get(titleRes)
    val summary: String get() = Strings.get(summaryRes)
    val action: String get() = Strings.get(actionRes)

    enum class Mode { EDITOR, BATCH }

    /** The editor's panels. Order here is the order of the strip under the canvas. */
    enum class Panel { NONE, CROP, PERSPECTIVE, LIGHT, COLOUR, CURVES, LOOKS, DETAIL, CUTOUT, SCAN, MARKUP, RETOUCH, LAYERS, FRAME, DETAILS, EXPORT }

    /** How the home board groups the tools. Order here is the order on screen. */
    enum class Group(@StringRes val titleRes: Int) {
        EDIT(R.string.group_edit),
        MARK(R.string.group_mark),
        SIZE(R.string.group_size),
        PRIVACY(R.string.group_privacy),
        BATCH(R.string.group_batch);

        val title: String get() = Strings.get(titleRes)
    }

    companion object {
        fun byName(name: String?): ToolId? = entries.firstOrNull { it.name == name }
    }
}
