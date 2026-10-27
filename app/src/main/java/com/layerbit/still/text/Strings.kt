package com.layerbit.still.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Translated text for code that has no Context of its own: ops, the renderer's notes, job
 * states. Composables use stringResource() as usual; this is for sentences made far from any
 * screen and shown on one later. Installed first thing in StillApplication.onCreate.
 */
object Strings {

    private lateinit var app: Context

    fun install(context: Context) {
        app = context.applicationContext
    }

    val installed: Boolean get() = ::app.isInitialized

    /** Outside the app - a JVM test - the answer is the id and the arguments. */
    fun get(@StringRes id: Int, vararg args: Any): String = when {
        !installed -> "#$id" + args.joinToString(prefix = "(", postfix = ")")
        args.isEmpty() -> app.getString(id)
        else -> app.getString(id, *args)
    }

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String {
        val values = if (args.isEmpty()) arrayOf<Any>(count) else args
        return if (!installed) "#$id" + values.joinToString(prefix = "(", postfix = ")")
        else app.resources.getQuantityString(id, count, *values)
    }
}
