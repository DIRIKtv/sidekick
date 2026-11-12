package com.diriktv.sidekick.data

import android.content.Context
import android.net.Uri
import android.os.Build
import com.diriktv.sidekick.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports that stay on the TV until the viewer chooses to send one. A crash is written to a
 * file; the next time the app opens it offers to report it, which shows a QR code for a GitHub
 * issue already filled in with the details. Nothing is ever sent by the app itself.
 */
object CrashReports {
    private const val FILE = "last_crash.txt"
    /** Long URLs make dense QR codes that phone cameras struggle with. */
    private const val MAX_TRACE = 1400

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never let writing the report get in the way of the crash itself.
            runCatching { file(app).writeText(report(error, thread.name)) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun report(e: Throwable, thread: String): String {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return "Crashed $time on thread $thread\n$trace"
    }

    /** The crash from last time, if there is one the viewer hasn't dealt with yet. */
    fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /**
     * A link to a new GitHub issue with the bug form filled in: version, device, and [crash]
     * (trimmed to its first lines) when there is one.
     */
    fun issueUrl(crash: String?): String {
        val what = if (crash != null) {
            val trimmed = crash.lines().filter { it.isNotBlank() }.joinToString("\n").take(MAX_TRACE)
            "Sidekick closed unexpectedly.\n\n```\n$trimmed\n```"
        } else {
            "(Describe what happened here.)"
        }
        return Uri.parse("https://github.com/${Updater.REPO}/issues/new").buildUpon()
            .appendQueryParameter("template", "bug_report.yml")
            .appendQueryParameter("title", if (crash != null) "Crash: ${firstLine(crash)}" else "")
            .appendQueryParameter("what", what)
            .appendQueryParameter("version", BuildConfig.VERSION_NAME)
            .appendQueryParameter("model", device())
            .build().toString()
    }

    /** "java.lang.IllegalStateException: …" from the report, short enough for a title. */
    private fun firstLine(crash: String): String {
        val line = crash.lines().getOrNull(1)?.trim().orEmpty()
        val type = line.substringBefore(':').substringAfterLast('.')
        val message = line.substringAfter(':', "").trim()
        return listOf(type, message).filter { it.isNotEmpty() }.joinToString(": ").take(80).ifEmpty { "Sidekick closed" }
    }
}
