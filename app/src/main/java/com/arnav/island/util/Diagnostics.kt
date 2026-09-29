package com.arnav.island.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostics for bug reports: the last few warnings in memory and the last crash on disk
 * (app-private storage). Nothing is uploaded; the user copies or shares the report themselves.
 */
object Diagnostics {
    private const val MAX_ENTRIES = 80
    private const val CRASH_FILE = "last_crash.txt"
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun w(tag: String, message: String, t: Throwable? = null) {
        Log.w(tag, message, t)
        record("W", tag, message, t)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(tag, message, t)
        record("E", tag, message, t)
    }

    private fun record(level: String, tag: String, message: String, t: Throwable?) {
        val line = buildString {
            append(time.format(Date())).append(' ').append(level).append(' ').append(tag).append(": ").append(message)
            if (t != null) append(" (").append(t.javaClass.simpleName).append(": ").append(t.message).append(')')
        }
        synchronized(entries) {
            if (entries.size == MAX_ENTRIES) entries.removeFirst()
            entries.addLast(line)
        }
    }

    fun recent(): List<String> = synchronized(entries) { entries.toList() }

    /** Keeps the last crash (stack trace only) so the next launch can include it in a report. */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, CRASH_FILE).writeText("${Date()}\n${Log.getStackTraceString(error)}")
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? =
        File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()?.take(6_000)

    fun clearCrash(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
    }
}
