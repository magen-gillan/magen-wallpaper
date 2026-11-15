package com.anthonyla.paperize.presentation.crash

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlin.system.exitProcess

/**
 * Installs a [Thread.UncaughtExceptionHandler] that routes uncaught exceptions to [CrashActivity]
 * so the user can see and share the stack trace instead of a silent crash-to-home.
 *
 * Pattern borrowed from magen-player (which uses the same welcome screen and Hilt-less DI).
 * The handler must be installed BEFORE any code that might throw — i.e. in
 * `Application.attachBaseContext` or at the very top of `Application.onCreate`.
 */
class GlobalExceptionHandler(
    private val context: Context,
    private val activity: Class<*>,
) : Thread.UncaughtExceptionHandler {
    private val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        // Capture a logcat excerpt NOW, while we're still in the crashing (main) process.
        // CrashActivity runs in a separate ":crash" process and on Android 16+ cannot read
        // another UID's logcat — so we must collect it here and pass it via the Intent extra.
        val logcatExcerpt = runCatching { collectLogcatExcerpt() }.getOrDefault("")

        val intent = Intent(context, activity).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXCEPTION_EXTRA, e.stackTraceToString())
            // Intent extras have a ~1MB total size limit; truncate to be safe.
            putExtra(LOGCAT_EXTRA, logcatExcerpt.take(500_000))
        }
        runCatching { context.startActivity(intent) }
        // Give ActivityTaskManager a moment to schedule the launch before we chain to the
        // previous handler (which typically calls Process.killProcess). Without this delay,
        // some OEM ROMs (Honor/Huawei) can drop the startActivity IPC when the process is
        // killed immediately afterwards.
        SystemClock.sleep(150)
        // Per Android convention, chain to the previous handler so the system still records the
        // crash in logcat/battery-stats etc. If previous is null (no prior handler), exit cleanly.
        previous?.uncaughtException(t, e) ?: exitProcess(2)
    }

    /**
     * Collect a recent logcat excerpt from the calling process. Uses `logcat -d -t 200` to get
     * the last 200 lines (-d = dump and exit, -t 200 = last 200 lines). Filters to warning+
     * level to keep the excerpt focused on the crash context.
     */
    private fun collectLogcatExcerpt(): String {
        val process = Runtime.getRuntime()
        val reader = process.exec("logcat -d -t 200 -v brief *:W").inputStream.bufferedReader()
        val sb = StringBuilder()
        reader.lines().forEach { sb.appendLine(it) }
        return sb.toString()
    }

    companion object {
        const val EXCEPTION_EXTRA = "magen.crash.exception"
        const val LOGCAT_EXTRA = "magen.crash.logcat"
    }
}
