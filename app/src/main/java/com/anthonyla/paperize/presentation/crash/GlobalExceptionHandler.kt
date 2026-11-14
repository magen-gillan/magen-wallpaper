package com.anthonyla.paperize.presentation.crash

import android.content.Context
import android.content.Intent
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
        val intent = Intent(context, activity).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXCEPTION_EXTRA, e.stackTraceToString())
        }
        runCatching { context.startActivity(intent) }
        // Per Android convention, chain to the previous handler so the system still records the
        // crash in logcat/battery-stats etc. If previous is null (no prior handler), exit cleanly.
        previous?.uncaughtException(t, e) ?: exitProcess(2)
    }

    companion object {
        const val EXCEPTION_EXTRA = "magen.crash.exception"
    }
}
