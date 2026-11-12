package com.focuslock.mdm

import android.content.Context

/**
 * The layers that can hold the phone, highest first. Their blocks add up (each
 * rule in [RuleEngine.decide] still answers for itself); this only decides
 * which one owns the screen and the notification: the top one speaks, the
 * next one waits in brackets. Outside every layer the phone is just a phone.
 */
object Layers {

    enum class Layer { ALARM, SALAH, BEDTIME, PROMISE, SCHEDULE }

    data class Active(val layer: Layer, val line: String)

    fun active(c: Context): List<Active> {
        val out = ArrayList<Active>()
        if (WakeAlarm.isRinging(c)) out += Active(Layer.ALARM, c.getString(R.string.alarm_layer_line))
        Salah.window(c)?.let { w ->
            out += Active(Layer.SALAH, c.getString(R.string.status_salah_until, Salah.prayerName(c, w.name), clock(c, w.closes)))
        }
        if (Bedtime.isActive(c)) {
            out += Active(Layer.BEDTIME, c.getString(R.string.status_bedtime_until, Bedtime.formatTime(c, Bedtime.endMinutes(c))))
        }
        if (SessionManager.isActive(c)) {
            out += Active(Layer.PROMISE, c.getString(R.string.mode_notification_line,
                SessionManager.mode(c).label(c), SessionManager.formatRemaining(c)))
        }
        ScheduleManager.activeWindowIfEnabled(c)?.let { w ->
            out += Active(Layer.SCHEDULE, c.getString(R.string.status_scheduled_until, ScheduleManager.formatTime(c, w.endMinutes)))
        }
        return out
    }

    fun top(c: Context): Layer? = active(c).firstOrNull()?.layer

    /** "Top line (next line)" when two or more layers hold the phone, else the one line, else null. */
    fun statusLine(c: Context): String? {
        val a = active(c)
        return when (a.size) {
            0 -> null
            1 -> a[0].line
            else -> "${a[0].line} (${a[1].line})"
        }
    }

    private fun clock(c: Context, ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return TimeText.ofDay(c, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }
}
