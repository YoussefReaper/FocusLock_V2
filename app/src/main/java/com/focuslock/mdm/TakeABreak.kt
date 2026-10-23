package com.focuslock.mdm

import android.content.Context
import org.json.JSONObject

/**
 * A short, deliberate, counted exception.
 *
 * All-or-nothing blocking is what turns one slip into an abandoned session, so
 * FocusLock lets you open a blocked app on purpose for a few minutes and then
 * closes it again. Taking a break is not a failure state and is never described
 * as one.
 */
object TakeABreak {

    private const val KEY_PASSES = "break_passes_json"
    private const val KEY_USED_TODAY = "break_used_today_json"

    private const val PARAM_MINUTES = "minutes"
    private const val PARAM_DAILY_MAX = "dailyMax"

    const val DEFAULT_MINUTES = 5
    const val DEFAULT_DAILY_MAX = 3

    fun isAvailable(context: Context): Boolean =
        CapabilityRegistry.isEnabled(context, Capabilities.TAKE_A_BREAK)

    fun breakMinutes(context: Context): Int =
        CapabilityRegistry.getIntParam(context, Capabilities.TAKE_A_BREAK, PARAM_MINUTES, DEFAULT_MINUTES)
            .coerceIn(1, 60)

    fun setBreakMinutes(context: Context, minutes: Int) {
        CapabilityRegistry.setIntParam(
            context,
            Capabilities.TAKE_A_BREAK,
            PARAM_MINUTES,
            minutes.coerceIn(1, 60)
        )
    }

    fun dailyMax(context: Context): Int =
        CapabilityRegistry.getIntParam(context, Capabilities.TAKE_A_BREAK, PARAM_DAILY_MAX, DEFAULT_DAILY_MAX)
            .coerceIn(0, 20)

    fun setDailyMax(context: Context, value: Int) {
        CapabilityRegistry.setIntParam(
            context,
            Capabilities.TAKE_A_BREAK,
            PARAM_DAILY_MAX,
            value.coerceIn(0, 20)
        )
    }

    // ── Passes ────────────────────────────────────────────────────

    /**
     * Milliseconds left on an active pass, or 0 when there is none.
     *
     * Pure on purpose. This is read several times a second by the enforcement
     * loop, and it used to delete the expired entry on the way through - so a
     * read had a side effect, and a SharedPreferences write sat on the hot
     * path of every block decision. Expired passes are cleared by
     * [sweepExpired], which the tick calls once.
     */
    fun remainingMs(context: Context, packageName: String): Long {
        val expiry = FocusStore.getJsonObject(context, KEY_PASSES).optLong(packageName, 0L)
        if (expiry <= 0L) return 0L
        return (expiry - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * Drops every pass that has run out. Writes only when something actually
     * expired, so a tick with nothing to do costs one JSON read and no commit.
     */
    fun sweepExpired(context: Context) {
        val passes = FocusStore.getJsonObject(context, KEY_PASSES)
        if (passes.length() == 0) return
        val now = System.currentTimeMillis()
        val expired = passes.keys().asSequence().filter { passes.optLong(it, 0L) <= now }.toList()
        if (expired.isEmpty()) return
        expired.forEach { passes.remove(it) }
        FocusStore.setJsonObject(context, KEY_PASSES, passes)
    }

    fun hasActivePass(context: Context, packageName: String): Boolean =
        remainingMs(context, packageName) > 0L

    fun activePasses(context: Context): Map<String, Long> {
        val passes = FocusStore.getJsonObject(context, KEY_PASSES)
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, Long>()
        val keys = passes.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val expiry = passes.optLong(key, 0L)
            if (expiry > now) out[key] = expiry - now
        }
        return out
    }

    fun usedToday(context: Context): Int {
        val record = FocusStore.getJsonObject(context, KEY_USED_TODAY)
        if (record.optString("day", "") != todayKey()) return 0
        return record.optInt("count", 0)
    }

    fun remainingToday(context: Context): Int {
        val max = dailyMax(context)
        if (max <= 0) return 0
        return (max - usedToday(context)).coerceAtLeast(0)
    }

    fun canStart(context: Context): Boolean = isAvailable(context) && remainingToday(context) > 0

    /** Grants a pass. Returns false when the daily allowance is already spent. */
    fun start(context: Context, packageName: String): Boolean {
        if (!canStart(context)) return false

        val passes = FocusStore.getJsonObject(context, KEY_PASSES)
        passes.put(packageName, System.currentTimeMillis() + breakMinutes(context) * 60_000L)
        FocusStore.setJsonObject(context, KEY_PASSES, passes)

        val record = JSONObject()
        record.put("day", todayKey())
        record.put("count", usedToday(context) + 1)
        FocusStore.setJsonObject(context, KEY_USED_TODAY, record)

        PolicySync.request(context, "break:" + packageName)
        return true
    }

    /**
     * Grants a pass unconditionally, bypassing both [isAvailable] and the daily
     * allowance. Used only by the launch-friction "open anyway" flow.
     *
     * Friction's own promise is "the few seconds are the whole intervention":
     * once someone waits out the countdown, the way through is supposed to
     * stay open, full stop. Routing that through the budgeted [start] used to
     * mean the app opened for an instant and then got re-intercepted on the
     * very next policy check the moment the separate Take a Break capability
     * was off or its daily allowance was already spent - the pause screen
     * would just reappear no matter how many times "open anyway" was tapped.
     * This does not touch [usedToday], so it never competes with that budget.
     */
    fun grantFrictionPass(context: Context, packageName: String) {
        grantTempPass(context, packageName, breakMinutes(context))
    }

    /**
     * Grants a temporary pass for [minutes] for a custom [RuleAction.ALLOW_TEMP]
     * rule, independent of the global Take a Break daily counter.
     */
    fun grantTempPass(context: Context, packageName: String, minutes: Int) {
        val passes = FocusStore.getJsonObject(context, KEY_PASSES)
        passes.put(packageName, System.currentTimeMillis() + minutes.coerceIn(1, 120) * 60_000L)
        FocusStore.setJsonObject(context, KEY_PASSES, passes)
        PolicySync.request(context, "break:" + packageName)
    }

    fun endEarly(context: Context, packageName: String) {
        val passes = FocusStore.getJsonObject(context, KEY_PASSES)
        passes.remove(packageName)
        FocusStore.setJsonObject(context, KEY_PASSES, passes)
        PolicySync.request(context, "breakEnd:" + packageName)
    }

    fun clearAll(context: Context) {
        FocusStore.setJsonObject(context, KEY_PASSES, JSONObject())
        PolicySync.request(context, "breakClear")
    }

    private fun todayKey(): String {
        val calendar = java.util.Calendar.getInstance()
        return calendar.get(java.util.Calendar.YEAR).toString() + "-" +
            calendar.get(java.util.Calendar.DAY_OF_YEAR)
    }
}
