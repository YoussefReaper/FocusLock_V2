package com.focuslock.mdm

import android.content.Context
import android.os.SystemClock

object LockManager {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val KEY_KIOSK_START_ELAPSED_MS = "kiosk_start_elapsed_ms"
    private const val KEY_KIOSK_BOOT_COUNT = "kiosk_boot_count"

    private fun currentBootCount(context: Context): Int = try {
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            android.provider.Settings.Global.BOOT_COUNT,
            -1
        )
    } catch (_: Exception) {
        -1
    }

    /** Initialize baseline prefs safely. */
    fun initLock(context: Context) {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        if (!prefs.contains(Constants.KEY_INSTALL_TIME)) {
            prefs.edit()
                .putLong(Constants.KEY_INSTALL_TIME, System.currentTimeMillis())
                .apply()
        }
    }

    fun startKiosk(context: Context, durationMs: Long) {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(Constants.KEY_KIOSK_ACTIVE, true)
            .putLong(Constants.KEY_KIOSK_START_MS, now)
            .putLong(Constants.KEY_KIOSK_DURATION_MS, durationMs)
            .putLong(KEY_KIOSK_START_ELAPSED_MS, SystemClock.elapsedRealtime())
            .putInt(KEY_KIOSK_BOOT_COUNT, currentBootCount(context))
            .apply()
    }

    fun stopKiosk(context: Context) {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(Constants.KEY_KIOSK_ACTIVE, false)
            .putLong(Constants.KEY_KIOSK_START_MS, 0L)
            .putLong(Constants.KEY_KIOSK_DURATION_MS, 0L)
            .putLong(KEY_KIOSK_START_ELAPSED_MS, 0L)
            .putInt(KEY_KIOSK_BOOT_COUNT, -1)
            .apply()
    }

    /**
     * Elapsed session time in ms, protected against forward wall-clock tampering.
     * Within the same boot session, monotonic [SystemClock.elapsedRealtime] is
     * authoritative whenever the wall clock jumps ahead of it.
     */
    private fun elapsedSessionMs(context: Context, prefs: android.content.SharedPreferences, startWallMs: Long): Long {
        val wallElapsed = (System.currentTimeMillis() - startWallMs).coerceAtLeast(0L)
        val savedBoot = prefs.getInt(KEY_KIOSK_BOOT_COUNT, -1)
        val startElapsed = prefs.getLong(KEY_KIOSK_START_ELAPSED_MS, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (savedBoot != -1 && savedBoot == currentBootCount(context) && startElapsed > 0L && nowElapsed >= startElapsed) {
            val monotonicElapsed = nowElapsed - startElapsed
            // If the user jumped the wall clock forward, trust monotonic time.
            return minOf(wallElapsed, monotonicElapsed + 5_000L)
        }
        return wallElapsed
    }

    fun isKioskActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.KEY_KIOSK_ACTIVE, false)) return false
        val start = prefs.getLong(Constants.KEY_KIOSK_START_MS, 0L)
        val duration = prefs.getLong(Constants.KEY_KIOSK_DURATION_MS, 0L)
        if (start <= 0L || duration <= 0L) return false
        return elapsedSessionMs(context, prefs, start) < duration
    }

    fun getKioskDurationMs(context: Context): Long {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        return prefs.getLong(Constants.KEY_KIOSK_DURATION_MS, 0L)
    }

    fun getKioskRemainingMs(context: Context): Long {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        val start = prefs.getLong(Constants.KEY_KIOSK_START_MS, 0L)
        val duration = prefs.getLong(Constants.KEY_KIOSK_DURATION_MS, 0L)
        if (start <= 0L || duration <= 0L) return 0L
        val remaining = duration - elapsedSessionMs(context, prefs, start)
        return remaining.coerceAtLeast(0L)
    }

    fun hasKioskExpired(context: Context): Boolean {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.KEY_KIOSK_ACTIVE, false)) return false
        val start = prefs.getLong(Constants.KEY_KIOSK_START_MS, 0L)
        val duration = prefs.getLong(Constants.KEY_KIOSK_DURATION_MS, 0L)
        if (start <= 0L || duration <= 0L) return true
        return elapsedSessionMs(context, prefs, start) >= duration
    }

    /** True after initial permission/bootstrap setup has been completed once. */
    fun isSecurityBaselineReady(context: Context): Boolean {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        return prefs.getBoolean(Constants.KEY_SECURITY_BASELINE_READY, false)
    }

    fun allowSettingsUntil(context: Context, untilMs: Long) {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        prefs.edit().putLong(Constants.KEY_SETTINGS_ALLOW_UNTIL_MS, untilMs).apply()
    }

    fun isSettingsAccessAllowed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)
        val until = prefs.getLong(Constants.KEY_SETTINGS_ALLOW_UNTIL_MS, 0L)
        return System.currentTimeMillis() <= until
    }

    /** Returns remaining full days (rounded up) for messaging/UI. */
    fun getDaysRemaining(context: Context): Long {
        val remaining = getKioskRemainingMs(context)
        if (remaining <= 0L) return 0L
        return (remaining + DAY_MS - 1L) / DAY_MS
    }
}