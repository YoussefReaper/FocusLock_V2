package com.focuslock.mdm

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * A count of the days you showed up.
 *
 * Deliberately forgiving: a missed day pauses the count and keeps the best run
 * on record instead of resetting it to zero. Streak-shattering is the single
 * most common way habit apps turn one bad day into a quit, and it buys nothing.
 */
object Streaks {

    private const val KEY_LAST_DAY = "streak_last_day"
    private const val KEY_CURRENT = "streak_current"
    private const val KEY_BEST = "streak_best"
    private const val KEY_TOTAL_DAYS = "streak_total_days"
    private const val KEY_BLOCKS_DAY = "streak_blocks_day"
    private const val KEY_BLOCKS_TODAY = "streak_blocks_today"
    private const val KEY_FIRST_BLOCK_SEEN = "streak_first_block_seen"

    fun isEnabled(context: Context): Boolean =
        CapabilityRegistry.isEnabled(context, Capabilities.STREAKS)

    fun current(context: Context): Int = FocusStore.getInt(context, KEY_CURRENT, 0)

    fun best(context: Context): Int = FocusStore.getInt(context, KEY_BEST, 0)

    fun totalDays(context: Context): Int = FocusStore.getInt(context, KEY_TOTAL_DAYS, 0)

    /**
     * Records a stopped app open today. Returns true if this is the very first
     * block ever stopped on this phone, so InterceptActivity can celebrate it warmly.
     */
    fun recordBlockStopped(context: Context): Boolean {
        val today = dayNumber()
        val storedDay = FocusStore.getInt(context, KEY_BLOCKS_DAY, 0)
        val count = if (storedDay == today) FocusStore.getInt(context, KEY_BLOCKS_TODAY, 0) + 1 else 1
        FocusStore.setInt(context, KEY_BLOCKS_DAY, today)
        FocusStore.setInt(context, KEY_BLOCKS_TODAY, count)

        val seenFirst = FocusStore.getBool(context, KEY_FIRST_BLOCK_SEEN, false)
        if (!seenFirst) {
            FocusStore.setBool(context, KEY_FIRST_BLOCK_SEEN, true)
            return true
        }
        return false
    }

    fun blocksStoppedToday(context: Context): Int {
        val today = dayNumber()
        if (FocusStore.getInt(context, KEY_BLOCKS_DAY, 0) != today) return 0
        return FocusStore.getInt(context, KEY_BLOCKS_TODAY, 0)
    }

    /** Called whenever a session runs. Idempotent within a day. */
    fun recordActivity(context: Context) {
        if (!isEnabled(context)) return

        val today = dayNumber()
        val lastDay = FocusStore.getInt(context, KEY_LAST_DAY, 0)
        if (lastDay == today) return

        val next = if (lastDay == today - 1) current(context) + 1 else 1
        FocusStore.setInt(context, KEY_LAST_DAY, today)
        FocusStore.setInt(context, KEY_CURRENT, next)
        FocusStore.setInt(context, KEY_TOTAL_DAYS, totalDays(context) + 1)
        if (next > best(context)) FocusStore.setInt(context, KEY_BEST, next)
    }

    /** True when the run is paused rather than broken: yesterday was missed. */
    fun isPaused(context: Context): Boolean {
        val lastDay = FocusStore.getInt(context, KEY_LAST_DAY, 0)
        if (lastDay == 0) return false
        return dayNumber() - lastDay > 1
    }

    fun summary(context: Context): String {
        if (!isEnabled(context)) return ""
        val currentRun = current(context)
        if (currentRun <= 0) return context.getString(R.string.insights_first_day)
        if (isPaused(context)) {
            return context.getString(R.string.insights_best_run, best(context))
        }
        return context.getString(
            if (currentRun == 1) R.string.insights_run_one else R.string.insights_run_many,
            currentRun
        )
    }

    private fun dayNumber(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()
}

data class UsageSlice(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val totalMs: Long,
    val opens: Int
)

data class UsageReport(
    val windowLabel: String,
    val totalMs: Long,
    val opens: Int,
    val apps: List<UsageSlice>,
    val byCategory: List<Pair<AppCategory, Long>>
) {
    val hasData: Boolean get() = totalMs > 0L
}

/**
 * Where the time actually went, computed on the phone and kept there.
 *
 * Nothing in here is uploaded, compared against anyone else, or turned into a
 * score. It exists so the person can see a pattern and decide what to do about
 * it, which is the only use for this data that helps.
 */
object UsageAnalytics {

    fun isEnabled(context: Context): Boolean =
        CapabilityRegistry.isEnabled(context, Capabilities.ANALYTICS)

    fun report(context: Context, windowMs: Long, windowLabel: String): UsageReport {
        if (!isEnabled(context)) {
            return UsageReport(windowLabel, 0L, 0, emptyList(), emptyList())
        }

        val now = System.currentTimeMillis()
        val start = now - windowMs
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return UsageReport(windowLabel, 0L, 0, emptyList(), emptyList())

        val totals = HashMap<String, Long>()
        try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)?.forEach { stat ->
                val pkg = stat.packageName ?: return@forEach
                if (stat.totalTimeInForeground <= 0L) return@forEach
                if (pkg == context.packageName) return@forEach
                if (SystemSurfaces.isCritical(pkg)) return@forEach
                totals[pkg] = (totals[pkg] ?: 0L) + stat.totalTimeInForeground
            }
        } catch (_: Exception) {
            return UsageReport(windowLabel, 0L, 0, emptyList(), emptyList())
        }

        val opens = HashMap<String, Int>()
        var totalOpens = 0
        try {
            val events = usm.queryEvents(start, now)
            val event = UsageEvents.Event()
            var lastPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isResume = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (!isResume) continue
                val pkg = event.packageName ?: continue
                if (pkg == lastPackage) continue
                lastPackage = pkg
                if (pkg == context.packageName || SystemSurfaces.isCritical(pkg)) continue
                opens[pkg] = (opens[pkg] ?: 0) + 1
                totalOpens += 1
            }
        } catch (_: Exception) {
            // Opens are a nice-to-have; times still stand.
        }

        val slices = totals.map { entry ->
            UsageSlice(
                packageName = entry.key,
                label = AppCatalog.label(context, entry.key),
                category = AppCatalog.categoryOf(context, entry.key),
                totalMs = entry.value,
                opens = opens[entry.key] ?: 0
            )
        }.sortedByDescending { it.totalMs }

        val byCategory = slices
            .groupBy { it.category }
            .map { entry -> entry.key to entry.value.sumOf { it.totalMs } }
            .sortedByDescending { it.second }

        return UsageReport(
            windowLabel = windowLabel,
            totalMs = slices.sumOf { it.totalMs },
            opens = totalOpens,
            apps = slices,
            byCategory = byCategory
        )
    }

    fun today(context: Context): UsageReport =
        report(context, System.currentTimeMillis() - startOfToday(), context.getString(R.string.common_today))

    fun last7Days(context: Context): UsageReport =
        report(context, 7L * 86_400_000L, context.getString(R.string.analytics_window_7))

    private fun startOfToday(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) hours.toString() + "h " + minutes + "m" else minutes.toString() + "m"
    }

    /**
     * The apps a first-time setup should propose blocking: the heaviest users of
     * the last week that are not essentials. Suggestions only, never applied.
     */
    fun suggestedDistractions(context: Context, limit: Int = 8): List<String> {
        val usage = last7Days(context).apps.map { it.packageName to it.category }
        val installedSeeds = Seed.distractions.filter { AppCatalog.isInstalled(context, it) }
        return pickSuggestions(usage, AppCatalog.detectEssentials(context).toSet(), installedSeeds, limit)
    }

    /**
     * Browsing is left out on purpose: a pre-ticked Chrome or Play Store makes the
     * phone feel broken on day one. People can still add them by hand.
     */
    fun pickSuggestions(
        usage: List<Pair<String, AppCategory>>,
        essentials: Set<String>,
        installedSeeds: List<String>,
        limit: Int,
    ): List<String> {
        val fromUsage = usage
            .filter { (_, category) -> category in setOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAMES) }
            .map { it.first }
        return (fromUsage + installedSeeds).filterNot { it in essentials }.distinct().take(limit)
    }
}
