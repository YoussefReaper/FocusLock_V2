package com.focuslock.mdm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class RepeatType {
    DAILY,
    WEEKLY,
    MONTHLY
}

data class ScheduleWindow(
    val id: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val repeat: RepeatType,
    val daysOfWeek: List<Int>,
    val dayOfMonth: Int,
    val message: String,
    val allowedApps: Set<String> = emptySet(),
    /**
     * A hard, Device-Owner lock-task lock for the duration of the window,
     * instead of the ordinary accessibility-service block.
     *
     * The ordinary block is a polite request the accessibility service makes
     * on every foreground change - reachable in between checks, and never
     * covers the home screen or FocusLock's own other screens. Overlay is
     * absolute: nothing outside always-allowed and this window's own
     * [allowedApps] can be brought to the foreground at all, not even the
     * launcher, not even a break pass. See [RuleEngine.decide] and
     * [KioskPolicy.buildLockTaskPackages].
     */
    val overlay: Boolean = false,
    /**
     * Closes FocusLock's own tabs for the duration too, so an overlay window
     * means a dead phone rather than a phone that is only this app. Only
     * meaningful alongside [overlay]. See [Lockdown].
     */
    val bricksApp: Boolean = false
)

object ScheduleManager {

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)

    fun getSchedules(context: Context): List<ScheduleWindow> {
        val raw = prefs(context).getString(Constants.KEY_SCHEDULE_JSON, null) ?: return emptyList()
        return parseSchedules(raw)
    }

    /**
     * Frozen-gated. A schedule window's allowedApps is unioned into both the
     * accessibility-based decision (RuleEngine.decide - a window match wins
     * outright, before the Kiosk allowlist check ever runs) and the real
     * Device-Owner lock-task allowlist (KioskPolicy.buildLockTaskPackages).
     * Adding or editing a window mid-session is therefore a genuine escape
     * from Kiosk, not just a settings change - the one rule store the
     * original freeze work missed entirely.
     */
    fun saveSchedules(context: Context, schedules: List<ScheduleWindow>): Boolean {
        if (!SessionLock.allows(context, directionOf(getSchedules(context), schedules))) return false
        val json = serializeSchedules(schedules)
        prefs(context).edit().putString(Constants.KEY_SCHEDULE_JSON, json).apply()
        PolicySync.request(context, "schedules")
        return true
    }

    fun exportJson(context: Context): JSONArray = try {
        JSONArray(prefs(context).getString(Constants.KEY_SCHEDULE_JSON, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    fun importJson(context: Context, array: JSONArray): Boolean {
        if (SessionLock.isFrozen(context)) return false
        prefs(context).edit().putString(Constants.KEY_SCHEDULE_JSON, array.toString()).apply()
        PolicySync.request(context, "schedules:import")
        return true
    }

    /**
     * A new quiet window is a tightening; deleting one, shortening one, or
     * widening its allowedApps is a loosening.
     *
     * A window's allowedApps is unioned into both the accessibility decision
     * ([RuleEngine.decide]) and the real Device-Owner lock-task allowlist
     * ([KioskPolicy.buildLockTaskPackages]), so widening one mid-session is a
     * genuine escape from Kiosk rather than a settings change - which is why
     * that particular edit is on the waiting side of the line.
     */
    private fun directionOf(before: List<ScheduleWindow>, after: List<ScheduleWindow>): EditDirection {
        val afterById = after.associateBy { it.id }
        val weakened = before.any { previous ->
            val now = afterById[previous.id] ?: return@any true
            now.startMinutes != previous.startMinutes ||
                now.endMinutes != previous.endMinutes ||
                now.repeat != previous.repeat ||
                // dayOfMonth is only read for MONTHLY, but comparing it
                // unconditionally is the safer default: a window that stops
                // covering today is a loosening whichever field moved.
                now.dayOfMonth != previous.dayOfMonth ||
                now.daysOfWeek.toSet() != previous.daysOfWeek.toSet() ||
                !previous.allowedApps.containsAll(now.allowedApps) ||
                (previous.overlay && !now.overlay) ||
                (previous.bricksApp && !now.bricksApp)
        }
        return if (weakened) EditDirection.LOOSEN else EditDirection.TIGHTEN
    }

    fun addSchedule(context: Context, schedule: ScheduleWindow): Boolean {
        val current = getSchedules(context).toMutableList()
        current.add(schedule)
        return saveSchedules(context, current)
    }

    fun removeSchedule(context: Context, id: String): Boolean {
        val current = getSchedules(context).filterNot { it.id == id }
        return saveSchedules(context, current)
    }

    fun updateSchedule(context: Context, updated: ScheduleWindow): Boolean {
        val current = getSchedules(context).map { if (it.id == updated.id) updated else it }
        return saveSchedules(context, current)
    }

    /**
     * Always-allowed apps are one list now, owned by [AppRules], because having
     * a separate schedule-only version was the reason a person could be locked
     * out of their dialler by a window they set up months earlier.
     */
    fun getAlwaysAllowedApps(context: Context): Set<String> =
        AppRules.alwaysAllowed(context).filter { it != context.packageName }.toSet()

    fun setAlwaysAllowedApps(context: Context, packages: Collection<String>) {
        AppRules.setAlwaysAllowed(context, packages)
    }

    fun getAllScheduleAllowedApps(context: Context): Set<String> {
        return getAlwaysAllowedApps(context) + getSchedules(context).flatMap { it.allowedApps }
    }

    fun newSchedule(
        startMinutes: Int,
        endMinutes: Int,
        repeat: RepeatType,
        daysOfWeek: List<Int>,
        dayOfMonth: Int,
        message: String,
        allowedApps: Collection<String> = emptyList(),
        overlay: Boolean = false,
        bricksApp: Boolean = false
    ): ScheduleWindow {
        return ScheduleWindow(
            id = UUID.randomUUID().toString(),
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            repeat = repeat,
            daysOfWeek = daysOfWeek,
            dayOfMonth = dayOfMonth,
            message = message,
            allowedApps = allowedApps.map { it.trim() }.filter { it.isNotBlank() }.toSet(),
            overlay = overlay,
            bricksApp = bricksApp
        )
    }

    /**
     * Whether the currently active window demands the real Device-Owner lock,
     * not just the ordinary accessibility-service block. See
     * [ScheduleWindow.overlay].
     */
    fun requiresLockTask(context: Context, now: Calendar = Calendar.getInstance()): Boolean =
        Standalone.mayActNow(context) && activeWindowIfEnabled(context, now)?.overlay == true

    fun getActiveWindow(context: Context, now: Calendar = Calendar.getInstance()): ScheduleWindow? {
        val schedules = getSchedules(context)
        return schedules.firstOrNull { schedule -> isActiveAt(schedule, now) }
    }

    /**
     * Whether [schedule] is running at [now], judged against the day the
     * current occurrence *started* on. See [TimeWindow] for why that is not
     * simply "today".
     */
    private fun isActiveAt(schedule: ScheduleWindow, now: Calendar): Boolean {
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val offset = TimeWindow.owningDay(schedule.startMinutes, schedule.endMinutes, nowMinutes) ?: return false
        val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
        return matchesRepeat(schedule, day)
    }

    /**
     * The active window, or null when the user has schedules switched off.
     * Enforcement calls this one so a single toggle silences every window.
     */
    fun activeWindowIfEnabled(
        context: Context,
        now: Calendar = Calendar.getInstance()
    ): ScheduleWindow? {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.SCHEDULES)) return null
        return getActiveWindow(context, now)
    }

    fun nextWindow(context: Context, now: Calendar = Calendar.getInstance()): ScheduleWindow? {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.SCHEDULES)) return null
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return getSchedules(context)
            .filter { matchesRepeat(it, now) && it.startMinutes > nowMinutes }
            .minByOrNull { it.startMinutes }
    }

    /** Needs a Context now: whether this reads "9:00 PM" or "21:00" is the user's setting. See [TimeText]. */
    fun formatTime(context: Context, minutes: Int): String = TimeText.ofDay(context, minutes)

    private fun matchesRepeat(schedule: ScheduleWindow, now: Calendar): Boolean {
        return when (schedule.repeat) {
            RepeatType.DAILY -> true
            RepeatType.WEEKLY -> {
                if (schedule.daysOfWeek.isEmpty()) true
                else schedule.daysOfWeek.contains(now.get(Calendar.DAY_OF_WEEK))
            }
            RepeatType.MONTHLY -> {
                val day = schedule.dayOfMonth
                day in 1..31 && now.get(Calendar.DAY_OF_MONTH) == day
            }
        }
    }


    private fun serializeSchedules(schedules: List<ScheduleWindow>): String {
        val array = JSONArray()
        schedules.forEach { schedule ->
            val obj = JSONObject()
            obj.put("id", schedule.id)
            obj.put("startMinutes", schedule.startMinutes)
            obj.put("endMinutes", schedule.endMinutes)
            obj.put("repeat", schedule.repeat.name)
            obj.put("dayOfMonth", schedule.dayOfMonth)
            obj.put("message", schedule.message)
            val days = JSONArray()
            schedule.daysOfWeek.forEach { days.put(it) }
            obj.put("daysOfWeek", days)
            val allowedApps = JSONArray()
            schedule.allowedApps.forEach { allowedApps.put(it) }
            obj.put("allowedApps", allowedApps)
            obj.put("overlay", schedule.overlay)
            obj.put("bricksApp", schedule.bricksApp)
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseSchedules(raw: String): List<ScheduleWindow> {
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val repeat = runCatching { RepeatType.valueOf(obj.optString("repeat")) }
                    .getOrElse { RepeatType.DAILY }
                val days = obj.optJSONArray("daysOfWeek")?.let { daysArray ->
                    (0 until daysArray.length()).mapNotNull { i ->
                        val day = daysArray.optInt(i, -1)
                        if (day in 1..7) day else null
                    }
                } ?: emptyList()
                val allowedApps = obj.optJSONArray("allowedApps")?.let { appsArray ->
                    (0 until appsArray.length()).mapNotNull { i ->
                        appsArray.optString(i).trim().takeIf { it.isNotBlank() }
                    }.toSet()
                } ?: emptySet()
                ScheduleWindow(
                    id = obj.optString("id"),
                    startMinutes = obj.optInt("startMinutes"),
                    endMinutes = obj.optInt("endMinutes"),
                    repeat = repeat,
                    daysOfWeek = days,
                    dayOfMonth = obj.optInt("dayOfMonth"),
                    message = obj.optString("message"),
                    allowedApps = allowedApps,
                    overlay = obj.optBoolean("overlay", false),
                    bricksApp = obj.optBoolean("bricksApp", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
