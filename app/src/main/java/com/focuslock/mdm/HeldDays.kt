package com.focuslock.mdm

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Time held under a promise, day by day, for the week of moons.
 * Finished promises are split across the days they covered when they end;
 * the running one is added live.
 */
object HeldDays {
    private const val KEY = "held_days_json"
    private const val KEEP_DAYS = 70
    const val DEFAULT_GOAL_MS = 3 * 60 * 60 * 1000L

    fun dayKey(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Splits a held span over the days it touched and saves it. */
    fun record(context: Context, from: Long, to: Long) {
        if (to <= from) return
        val json = FocusStore.getJsonObject(context, KEY)
        forEachDay(from, to) { key, ms -> json.put(key, json.optLong(key, 0L) + ms) }
        prune(json)
        FocusStore.setJsonObject(context, KEY, json)
    }

    /** Held on the day containing [dayMillis], including the running promise. */
    fun heldMs(context: Context, dayMillis: Long): Long {
        val key = dayKey(dayMillis)
        var total = FocusStore.getJsonObject(context, KEY).optLong(key, 0L)
        if (SessionManager.isActive(context)) {
            val start = SessionManager.startedAt(context)
            val now = System.currentTimeMillis()
            forEachDay(start, now) { k, ms -> if (k == key) total += ms }
        }
        return total
    }

    fun fraction(context: Context, dayMillis: Long, goalMs: Long = DEFAULT_GOAL_MS): Float =
        (heldMs(context, dayMillis).toFloat() / goalMs).coerceIn(0f, 1f)

    /** The seven days of this week, Saturday first, as start-of-day millis. */
    fun thisWeek(now: Long = System.currentTimeMillis()): List<Long> {
        val c = Calendar.getInstance().apply { timeInMillis = startOfDay(now) }
        val back = (c.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7
        c.add(Calendar.DAY_OF_MONTH, -back)
        return (0 until 7).map { i -> (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }.timeInMillis }
    }

    private inline fun forEachDay(from: Long, to: Long, each: (String, Long) -> Unit) {
        var t = from
        while (t < to) {
            val next = minOf(to, startOfDay(t) + 26 * 3_600_000L).let { startOfDay(it) }.let { if (it <= t) to else it }
            val end = minOf(to, next)
            each(dayKey(t), end - t)
            t = end
        }
    }

    private fun prune(json: JSONObject) {
        val keys = json.keys().asSequence().toList().sorted()
        if (keys.size > KEEP_DAYS) keys.take(keys.size - KEEP_DAYS).forEach { json.remove(it) }
    }
}
