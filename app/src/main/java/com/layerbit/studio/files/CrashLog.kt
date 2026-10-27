package com.layerbit.studio.files

import android.content.Context
import android.os.Build
import com.layerbit.studio.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The crash log the user sends themselves.
 *
 * No INTERNET permission, so no crash reporter and never one. The stack trace is written to
 * private storage as the process dies and offered from Settings, where the user decides whether
 * it goes anywhere and to whom. Nothing from a picture is in it.
 */
object CrashLog {

    private const val DIR = "crashes"
    private const val KEEP = 5

    fun directory(context: Context): File = File(context.filesDir, DIR)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun latest(context: Context): File? =
        directory(context).listFiles { f -> f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }

    fun count(context: Context): Int = directory(context).listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = directory(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("Layerbit Studio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Heap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
            appendLine("Thread: ${thread.name}")
            appendLine("When: $stamp")
            appendLine()
            append(trace)
        }
        File(dir, "crash-$stamp.txt").writeText(text)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
    }
}
