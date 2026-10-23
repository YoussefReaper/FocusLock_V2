package com.focuslock.mdm

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * Daily budgets: minutes of use, and number of opens.
 *
 * Both are read from Android's own usage stats rather than a private counter,
 * so the numbers match what Digital Wellbeing shows and survive FocusLock being
 * killed. Results are cached briefly because the enforcement loop asks often.
 */
object AppLimits {

    private const val KEY_MINUTE_LIMITS = "app_minute_limits_json"
    private const val KEY_OPEN_LIMITS = "app_open_limits_json"

    private const val CACHE_TTL_MS = 20_000L

    // @Volatile on all three, because refreshCache() is @Synchronized but the
    // readers are not: the enforcement thread writes these several times a
    // second while the UI thread reads them, and without the annotation the UI
    // can keep serving a stale map long after the numbers moved.
    @Volatile
    private var minutesCache: Map<String, Long> = emptyMap()

    @Volatile
    private var opensCache: Map<String, Int> = emptyMap()

    @Volatile
    private var cacheStamp: Long = 0L

    /**
     * How far through today's usage log the open count has read.
     *
     * The log is read incrementally now: the whole day from midnight, every
     * refresh, is thousands of events by the evening - parsed by the system and
     * copied across to this process every twenty seconds for as long as a
     * limited app was in use. Only the tail since the last read is new.
     */
    private var opensDay = 0L
    private var opensLastEventAt = 0L
    private var opensLastPackage: String? = null

    // ── Configuration ─────────────────────────────────────────────

    fun minuteLimit(context: Context, packageName: String): Int? {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.PER_APP_LIMITS)) return null
        val value = FocusStore.getIntMap(context, KEY_MINUTE_LIMITS)[packageName] ?: return null
        return if (value > 0) value else null
    }

    /**
     * Tightening a budget (a smaller number of minutes) goes through at any
     * time. Raising or clearing one mid-session defeats the budget, so that
     * waits for the session to end.
     */
    fun setMinuteLimit(context: Context, packageName: String, minutes: Int?): Boolean {
        val before = FocusStore.getIntMap(context, KEY_MINUTE_LIMITS)[packageName]?.takeIf { it > 0 }
        if (!SessionLock.allows(context, SessionLock.forBudget(before, minutes?.takeIf { it > 0 }))) {
            return false
        }
        val map = FocusStore.getIntMap(context, KEY_MINUTE_LIMITS).toMutableMap()
        if (minutes == null || minutes <= 0) map.remove(packageName) else map[packageName] = minutes
        FocusStore.setIntMap(context, KEY_MINUTE_LIMITS, map)
        PolicySync.request(context, "minuteLimit:" + packageName)
        return true
    }

    fun allMinuteLimits(context: Context): Map<String, Int> =
        FocusStore.getIntMap(context, KEY_MINUTE_LIMITS).filterValues { it > 0 }

    fun openLimit(context: Context, packageName: String): Int? {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.PER_APP_LIMITS)) return null
        val value = FocusStore.getIntMap(context, KEY_OPEN_LIMITS)[packageName] ?: return null
        return if (value > 0) value else null
    }

    fun setOpenLimit(context: Context, packageName: String, opens: Int?): Boolean {
        val before = FocusStore.getIntMap(context, KEY_OPEN_LIMITS)[packageName]?.takeIf { it > 0 }
        if (!SessionLock.allows(context, SessionLock.forBudget(before, opens?.takeIf { it > 0 }))) {
            return false
        }
        val map = FocusStore.getIntMap(context, KEY_OPEN_LIMITS).toMutableMap()
        if (opens == null || opens <= 0) map.remove(packageName) else map[packageName] = opens
        FocusStore.setIntMap(context, KEY_OPEN_LIMITS, map)
        PolicySync.request(context, "openLimit:" + packageName)
        return true
    }

    fun allOpenLimits(context: Context): Map<String, Int> =
        FocusStore.getIntMap(context, KEY_OPEN_LIMITS).filterValues { it > 0 }

    fun hasAnyLimit(context: Context, packageName: String): Boolean =
        minuteLimit(context, packageName) != null || openLimit(context, packageName) != null

    /**
     * Whether any budget is both set and switched on.
     *
     * The editors deliberately still show budgets whose capability is off, so a
     * person can set one up before enabling it; enforcement uses this instead,
     * so a disabled capability costs nothing at runtime.
     */
    fun hasEnforceableBudgets(context: Context): Boolean {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.PER_APP_LIMITS)) return false
        return allMinuteLimits(context).isNotEmpty() || allOpenLimits(context).isNotEmpty()
    }

    // ── Today's usage ─────────────────────────────────────────────

    fun usedMinutesToday(context: Context, packageName: String): Int {
        refreshCache(context)
        val ms = minutesCache[packageName] ?: 0L
        return (ms / 60_000L).toInt()
    }

    fun opensToday(context: Context, packageName: String): Int {
        refreshCache(context)
        return opensCache[packageName] ?: 0
    }

    fun minutesRemaining(context: Context, packageName: String): Int? {
        val limit = minuteLimit(context, packageName) ?: return null
        return (limit - usedMinutesToday(context, packageName)).coerceAtLeast(0)
    }

    fun opensRemaining(context: Context, packageName: String): Int? {
        val limit = openLimit(context, packageName) ?: return null
        return (limit - opensToday(context, packageName)).coerceAtLeast(0)
    }

    /** Null when there is no budget; otherwise the reason the budget is spent. */
    fun exhaustedReason(context: Context, packageName: String): String? {
        minuteLimit(context, packageName)?.let { limit ->
            val used = usedMinutesToday(context, packageName)
            if (used >= limit) {
                return context.getString(R.string.limit_minutes_used, limit)
            }
        }
        openLimit(context, packageName)?.let { limit ->
            val opens = opensToday(context, packageName)
            if (opens >= limit) {
                return context.getString(R.string.limit_opens_used, opens, limit)
            }
        }
        return null
    }

    fun invalidate() {
        cacheStamp = 0L
    }

    private fun startOfToday(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    @Synchronized
    private fun refreshCache(context: Context) {
        val now = System.currentTimeMillis()
        if (now - cacheStamp < CACHE_TTL_MS) return
        cacheStamp = now

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val start = startOfToday()

        val minutes = HashMap<String, Long>()
        try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)?.forEach { stat ->
                val pkg = stat.packageName ?: return@forEach
                if (stat.totalTimeInForeground <= 0L) return@forEach
                minutes[pkg] = (minutes[pkg] ?: 0L) + stat.totalTimeInForeground
            }
        } catch (_: Exception) {
            // Usage access can be revoked at any time; limits simply stop biting.
        }
        minutesCache = minutes

        if (opensDay != start) {
            // A new day: start the count again from midnight.
            opensDay = start
            opensLastEventAt = 0L
            opensLastPackage = null
            opensCache = emptyMap()
        }
        val opens = HashMap(opensCache)
        try {
            // A few seconds of overlap, because an event can reach the log a
            // moment after its own timestamp; anything at or before the last
            // one already counted is skipped, so the overlap never counts twice.
            val from = if (opensLastEventAt == 0L) start else maxOf(start, opensLastEventAt - OPENS_OVERLAP_MS)
            val events = usm.queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isResume = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (!isResume) continue
                if (event.timeStamp <= opensLastEventAt) continue
                opensLastEventAt = event.timeStamp
                val pkg = event.packageName ?: continue
                // Only count a genuine switch into the app, not every activity change.
                if (pkg == opensLastPackage) continue
                opensLastPackage = pkg
                opens[pkg] = (opens[pkg] ?: 0) + 1
            }
        } catch (_: Exception) {
            // Same story: no events, no open counting.
        }
        opensCache = opens
    }

    private const val OPENS_OVERLAP_MS = 5_000L

    fun exportJson(context: Context): org.json.JSONObject {
        val out = org.json.JSONObject()
        out.put("minutes", FocusStore.getJsonObject(context, KEY_MINUTE_LIMITS))
        out.put("opens", FocusStore.getJsonObject(context, KEY_OPEN_LIMITS))
        return out
    }

    fun importJson(context: Context, json: org.json.JSONObject): Boolean {
        if (SessionLock.isFrozen(context)) return false
        json.optJSONObject("minutes")?.let { FocusStore.setJsonObject(context, KEY_MINUTE_LIMITS, it) }
        json.optJSONObject("opens")?.let { FocusStore.setJsonObject(context, KEY_OPEN_LIMITS, it) }
        PolicySync.request(context, "limits:import")
        return true
    }
}
