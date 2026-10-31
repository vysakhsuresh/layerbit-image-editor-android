package com.layerbit.still.prefs

import android.content.Context

/**
 * What the app has already asked or shown once, so it never does twice. Kept apart from
 * [StillSettings] because none of this is a choice the user makes on the Settings screen.
 */
class Prompts(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("still.prompts", Context.MODE_PRIVATE)

    var introSeen: Boolean
        get() = prefs.getBoolean(KEY_INTRO_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_INTRO_SEEN, value).apply()

    /** Exports that finished with every picture produced. */
    var successfulJobs: Int
        get() = prefs.getInt(KEY_SUCCESSES, 0)
        set(value) = prefs.edit().putInt(KEY_SUCCESSES, value).apply()

    /** The crop handles hint, shown once on the first crop. */
    var cropHintSeen: Boolean
        get() = prefs.getBoolean(KEY_CROP_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_CROP_HINT, value).apply()

    /** Whether the notification permission has been asked for, so it is asked once. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    private companion object {
        const val KEY_NOTIFICATIONS = "notificationsAsked"
        const val KEY_INTRO_SEEN = "introSeen"
        const val KEY_SUCCESSES = "successfulJobs"
        const val KEY_CROP_HINT = "cropHintSeen"
    }
}
