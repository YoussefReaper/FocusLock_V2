package com.focuslock.mdm

import android.content.Context
import android.os.PowerManager

/**
 * Where a task's time actually went.
 *
 * A task that names apps is credited for the minutes those apps are in front
 * **and the screen is on** - nothing else. Wall-clock time is not focus time:
 * counting it meant a "spend 25 minutes on this" task kept ticking while the
 * phone lay face-down on a desk and while the person was in a completely
 * different app, so the honest reading of that task was "start a timer and put
 * the phone away".
 *
 * One ledger serves both kinds of tracked task, which is why it lives outside
 * both:
 *
 *  - **TIMER** verification, which is satisfied once enough time has accrued
 *    rather than once enough time has passed.
 *  - **AMBIENT** tasks, which are never started at all: they simply accrue a
 *    balance in the background and the person claims it when they want it.
 *
 * Everything here is keyed by task id and stored as milliseconds, because the
 * enforcement tick is 250ms and anything coarser rounds to zero.
 */
object FocusLedger {

    private const val KEY_TRACKED_MS = "focus_ledger_ms_json"
    private const val KEY_CLAIMED_MS = "focus_ledger_claimed_ms_json"

    /** Ceiling on a single credit, so a phone that slept cannot be billed for it. */
    const val MAX_STEP_MS = 5_000L

    fun trackedMs(context: Context, taskId: String): Long =
        FocusStore.getJsonObject(context, KEY_TRACKED_MS).optLong(taskId, 0L).coerceAtLeast(0L)

    fun claimedMs(context: Context, taskId: String): Long =
        FocusStore.getJsonObject(context, KEY_CLAIMED_MS).optLong(taskId, 0L).coerceAtLeast(0L)

    /** Accrued but not yet collected. This is what the claim button offers. */
    fun unclaimedMs(context: Context, taskId: String): Long =
        (trackedMs(context, taskId) - claimedMs(context, taskId)).coerceAtLeast(0L)

    fun unclaimedMinutes(context: Context, taskId: String): Int =
        (unclaimedMs(context, taskId) / 60_000L).toInt()

    /**
     * Which apps each live tracked task counts.
     *
     * Memoised against the policy revision: the enforcement loop asks this four
     * times a second, and the answer cannot change without a store write.
     */
    fun trackedApps(context: Context): Map<String, Set<String>> =
        PolicyCache.get("ledgerTrackedApps") {
            FocusTaskStore.all(context)
                .filter { !it.completed && it.tracksApps && it.allowedApps.isNotEmpty() }
                .associate { it.id to it.allowedApps.toSet() }
        }

    /** Whether any open ambient task is configured to accrue time in the background. */
    fun hasAmbientTasks(context: Context): Boolean =
        EarnMode.isEnabled(context) &&
            PolicyCache.get("ledgerHasAmbientTasks") {
                FocusTaskStore.all(context).any {
                    !it.completed && it.verification == Verification.AMBIENT && it.allowedApps.isNotEmpty()
                }
            }

    fun isScreenOn(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (_: Exception) {
        // If the platform will not say, assume awake rather than silently
        // stopping every tracked task on the device.
        true
    }

    /**
     * Credits [ms] to every tracked task that names [packageName].
     *
     * Called from the enforcement tick with the app that is genuinely in front.
     * A task naming several apps gets the time whichever of them was used, which
     * is the point: "study apps" is a set, not one app.
     *
     * [screenKnownOn] is for the guard service, which buffers ticks and writes
     * them in batches: it only ever counted time with the screen on, and the
     * batch it writes as the screen goes off must not be thrown away for
     * arriving after the fact.
     */
    fun credit(context: Context, packageName: String, ms: Long, screenKnownOn: Boolean = false) {
        if (ms <= 0L || packageName.isEmpty()) return
        if (!screenKnownOn && !isScreenOn(context)) return
        val apps = trackedApps(context)
        if (apps.isEmpty()) return
        for ((taskId, named) in apps) {
            if (packageName in named) accumulate(context, taskId, ms)
        }
    }

    private fun accumulate(context: Context, taskId: String, ms: Long) {
        val map = FocusStore.getJsonObject(context, KEY_TRACKED_MS)
        map.put(taskId, map.optLong(taskId, 0L) + ms)
        FocusStore.setJsonObject(context, KEY_TRACKED_MS, map)
    }

    /** Marks everything tracked so far as collected. */
    fun markClaimed(context: Context, taskId: String) {
        val map = FocusStore.getJsonObject(context, KEY_CLAIMED_MS)
        map.put(taskId, trackedMs(context, taskId))
        FocusStore.setJsonObject(context, KEY_CLAIMED_MS, map)
    }

    /** Drops a task's ledger entirely, for when the task is deleted. */
    fun forget(context: Context, taskId: String) {
        val tracked = FocusStore.getJsonObject(context, KEY_TRACKED_MS)
        if (tracked.has(taskId)) {
            tracked.remove(taskId)
            FocusStore.setJsonObject(context, KEY_TRACKED_MS, tracked)
        }
        val claimed = FocusStore.getJsonObject(context, KEY_CLAIMED_MS)
        if (claimed.has(taskId)) {
            claimed.remove(taskId)
            FocusStore.setJsonObject(context, KEY_CLAIMED_MS, claimed)
        }
    }
}
