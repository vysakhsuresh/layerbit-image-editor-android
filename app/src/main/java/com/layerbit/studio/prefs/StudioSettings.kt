package com.layerbit.studio.prefs

import android.content.Context
import androidx.annotation.StringRes
import com.layerbit.studio.R
import com.layerbit.studio.image.OutputFormat
import com.layerbit.studio.text.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the user has told the app to remember about itself. SharedPreferences, because
 * the theme decides every colour on screen and must be in memory before the first frame.
 * Nothing about a picture is stored here.
 */
class StudioSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("studio.settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_state.value)
        prefs.edit()
            .putString(KEY_THEME, updated.theme.name)
            .putBoolean(KEY_LIVELY, updated.livelyIcons)
            .putBoolean(KEY_ASK_SAVE, updated.askWhereToSave)
            .putString(KEY_SAVE_FOLDER, updated.saveFolder)
            .putStringSet(KEY_FAVOURITES, updated.favouriteTools)
            .putString(KEY_RECENT_TOOLS, updated.recentTools.joinToString(","))
            .putString(KEY_FORMAT, updated.defaultFormat.name)
            .putInt(KEY_QUALITY, updated.defaultQuality)
            .putBoolean(KEY_KEEP_CAMERA, updated.keepCameraFields)
            .putBoolean(KEY_HAPTICS, updated.haptics)
            .putBoolean(KEY_SHOW_HISTOGRAM, updated.showHistogram)
            .apply()
        _state.value = updated
    }

    fun rememberTool(tool: String) = update { s ->
        s.copy(recentTools = (listOf(tool) + s.recentTools.filter { it != tool }).take(MAX_RECENT_TOOLS))
    }

    fun toggleFavourite(tool: String) = update { s ->
        val favourites = s.favouriteTools.toMutableSet()
        if (!favourites.remove(tool)) favourites += tool
        s.copy(favouriteTools = favourites)
    }

    private fun read(): Settings = Settings(
        theme = prefs.getString(KEY_THEME, null)?.let { n -> ThemeChoice.entries.firstOrNull { it.name == n } } ?: ThemeChoice.SYSTEM,
        livelyIcons = prefs.getBoolean(KEY_LIVELY, true),
        askWhereToSave = prefs.getBoolean(KEY_ASK_SAVE, true),
        saveFolder = prefs.getString(KEY_SAVE_FOLDER, null),
        favouriteTools = prefs.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty(),
        recentTools = prefs.getString(KEY_RECENT_TOOLS, null).orEmpty().split(",").filter { it.matches(Regex("[A-Z_]+")) },
        defaultFormat = prefs.getString(KEY_FORMAT, null)?.let { n -> OutputFormat.entries.firstOrNull { it.name == n } } ?: OutputFormat.JPEG,
        defaultQuality = prefs.getInt(KEY_QUALITY, 92).coerceIn(40, 100),
        keepCameraFields = prefs.getBoolean(KEY_KEEP_CAMERA, false),
        haptics = prefs.getBoolean(KEY_HAPTICS, true),
        showHistogram = prefs.getBoolean(KEY_SHOW_HISTOGRAM, true)
    )

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_LIVELY = "livelyIcons"
        const val KEY_ASK_SAVE = "askWhereToSave"
        const val KEY_SAVE_FOLDER = "saveFolder"
        const val KEY_FAVOURITES = "favouriteTools"
        const val KEY_RECENT_TOOLS = "recentTools"
        const val KEY_FORMAT = "defaultFormat"
        const val KEY_QUALITY = "defaultQuality"
        const val KEY_KEEP_CAMERA = "keepCameraFields"
        const val KEY_HAPTICS = "haptics"
        const val KEY_SHOW_HISTOGRAM = "showHistogram"
        const val MAX_RECENT_TOOLS = 6
    }
}

data class Settings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** Tool icons make their small movement now and then. */
    val livelyIcons: Boolean = true,
    /** Off means results go to the folder chosen once, which makes a batch one gesture. */
    val askWhereToSave: Boolean = true,
    val saveFolder: String? = null,
    val favouriteTools: Set<String> = emptySet(),
    val recentTools: List<String> = emptyList(),
    val defaultFormat: OutputFormat = OutputFormat.JPEG,
    val defaultQuality: Int = 92,
    /** Keep make, model, date and exposure in JPEG exports. Location never. */
    val keepCameraFields: Boolean = false,
    /** A tick when a crop handle snaps or a slider passes zero. */
    val haptics: Boolean = true,
    val showHistogram: Boolean = true
)

enum class ThemeChoice(@StringRes val labelRes: Int) {
    SYSTEM(R.string.app_theme_system),
    LIGHT(R.string.app_theme_light),
    DARK(R.string.app_theme_dark);

    val label: String get() = Strings.get(labelRes)
}
