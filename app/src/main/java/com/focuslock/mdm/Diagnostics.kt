package com.focuslock.mdm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash records and the problem report.
 *
 * FocusLock promises no crash reporter and no calls home, and this keeps that
 * promise: nothing here touches the network. A crash is written to a file in
 * the app's own private storage, and a report is a piece of text handed to
 * Android's share sheet - it goes nowhere until the person picks where to send
 * it, and they can read every line of it first in whatever app they pick.
 *
 * What a report contains, and nothing else: the app's version and tier, the
 * phone's make, model and Android version, which permissions are granted,
 * whether a session is running, the last few crash records, and FocusLock's own
 * recent log lines (never another app's - an app can only read its own). Those
 * log lines can name apps that were blocked and Telegram chats the guard read,
 * which the report screen says before anything is sent.
 */
object Diagnostics {

    const val SUPPORT_EMAIL = "support@focuslock.app"

    private const val DIR = "crashes"
    private const val MAX_CRASH_FILES = 5
    private const val MAX_LOG_LINES = 400
    private const val KEY_ACKED_CRASH_AT = "diag_acked_crash_at"

    // ── Crashes ───────────────────────────────────────────────────

    /**
     * Records every uncaught exception before the process dies, then hands it
     * on to whatever handler was there before - so Android still shows its own
     * dialog and restarts what it restarts. Called once from [FocusLockApp].
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrash(appContext, thread, error)
            } catch (_: Throwable) {
                // Recording a crash must never become a second crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun crashDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val now = System.currentTimeMillis()
        val text = buildString {
            append("Crash at ").append(stamp(now)).append('\n')
            append("Thread: ").append(thread.name).append('\n')
            append(header(context))
            append('\n').append(stack)
        }
        File(crashDir(context), "crash-$now.txt").writeText(text)
        // Keep the newest few; a crash loop must not fill the phone.
        crashFiles(context).drop(MAX_CRASH_FILES).forEach { it.delete() }
    }

    /** Crash records, newest first. */
    private fun crashFiles(context: Context): List<File> =
        crashDir(context).listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L }
            ?: emptyList()

    /** When the most recent crash happened, or null if there is none on record. */
    fun lastCrashAt(context: Context): Long? =
        crashFiles(context).firstOrNull()?.name?.removePrefix("crash-")?.removeSuffix(".txt")?.toLongOrNull()

    /** A crash the person has not yet been told about - what the dashboard card asks about. */
    fun unacknowledgedCrashAt(context: Context): Long? {
        val last = lastCrashAt(context) ?: return null
        return if (last > FocusStore.getLong(context, KEY_ACKED_CRASH_AT, 0L)) last else null
    }

    fun acknowledgeCrashes(context: Context) {
        FocusStore.setLong(context, KEY_ACKED_CRASH_AT, lastCrashAt(context) ?: System.currentTimeMillis())
    }

    fun clearCrashes(context: Context) {
        crashFiles(context).forEach { it.delete() }
        acknowledgeCrashes(context)
    }

    // ── The report ────────────────────────────────────────────────

    /**
     * Builds the report text. Reads the log with a subprocess, so call it off
     * the main thread.
     */
    fun buildReport(context: Context): String = buildString {
        append("FocusLock problem report — ").append(stamp(System.currentTimeMillis())).append("\n\n")
        append(header(context))
        append('\n')
        append("Session running: ").append(SessionManager.isActive(context)).append('\n')
        append("Test mode: ").append(TestMode.isActive(context)).append('\n')
        append("Permission emergency: ").append(PermissionGuard.activeEmergency(context).joinToString().ifEmpty { "none" }).append('\n')
        append('\n').append("Permissions\n")
        append("  Usage access: ").append(SetupChecks.hasUsageAccess(context)).append('\n')
        append("  Display over apps: ").append(SetupChecks.canDrawOverlays(context)).append('\n')
        append("  Content guard (accessibility): ").append(SetupChecks.isContentGuardEnabled(context)).append('\n')
        append("  Notification access: ").append(SetupChecks.isNotificationAccessGranted(context)).append('\n')
        append("  Battery unrestricted: ").append(SetupChecks.isIgnoringBatteryOptimizations(context)).append('\n')
        append("  Device owner: ").append(SetupChecks.isDeviceOwner(context)).append('\n')
        append('\n')
        append("Capabilities on: ")
            .append(Capabilities.all.count { CapabilityRegistry.isEnabled(context, it.id) })
            .append(" of ").append(Capabilities.all.size).append('\n')

        val crashes = crashFiles(context)
        append('\n').append("Crashes on record: ").append(crashes.size).append('\n')
        crashes.take(3).forEach { append('\n').append(it.readText().take(6_000)).append('\n') }

        append('\n').append("Recent FocusLock log\n")
        append(ownLog())
    }

    /** Hands the report to the share sheet, addressed to support but sent by nobody but the person. */
    fun share(context: Context, report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "FocusLock problem report (v" + BuildConfig.VERSION_NAME + ")")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.diag_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun header(context: Context): String = buildString {
        append("App: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE)
            .append(", ").append(BuildConfig.TIER).append(")\n")
        append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("Language: ").append(Locale.getDefault().toLanguageTag()).append('\n')
        append("Package: ").append(context.packageName).append('\n')
    }

    /**
     * FocusLock's own recent log lines. An app can read its own process's log
     * without any permission, and only its own.
     */
    private fun ownLog(): String = try {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", "3000", "--pid=" + Process.myPid()
        ).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        lines.filter { it.contains("FocusLock") || it.contains("AndroidRuntime") }
            .takeLast(MAX_LOG_LINES)
            .joinToString("\n")
            .ifEmpty { "(nothing logged since FocusLock last started)" }
    } catch (e: Exception) {
        "(log unavailable: " + e.javaClass.simpleName + ")"
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))
}
