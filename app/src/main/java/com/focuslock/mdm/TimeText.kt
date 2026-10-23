package com.focuslock.mdm

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Every clock time the app shows, in one place.
 *
 * The app used to print `String.format("%02d:%02d")` in five separate files, so
 * a bedtime that starts at ten at night read "22:00" on every screen no matter
 * what the phone itself was set to. Android has a system-wide 12/24 hour
 * setting and almost nobody who uses AM/PM wants one app ignoring it.
 *
 * The default follows that system setting. [UiPrefs.getClockFormat] can
 * override it in either direction, because "match my phone" is the right
 * default and not the right answer for everyone.
 */
object TimeText {

    const val CLOCK_SYSTEM = "system"
    const val CLOCK_12 = "12"
    const val CLOCK_24 = "24"

    fun uses24Hour(context: Context): Boolean = when (UiPrefs.getClockFormat(context)) {
        CLOCK_12 -> false
        CLOCK_24 -> true
        else -> android.text.format.DateFormat.is24HourFormat(context)
    }

    /**
     * A time of day held as minutes past midnight - what schedules, bedtime and
     * the custom rule engine all store.
     */
    fun ofDay(context: Context, minutesOfDay: Int): String {
        val wrapped = ((minutesOfDay % 1_440) + 1_440) % 1_440
        return ofDay(context, wrapped / 60, wrapped % 60)
    }

    fun ofDay(context: Context, hour: Int, minute: Int): String {
        val locale = Locale.getDefault()
        if (uses24Hour(context)) {
            return String.format(locale, "%02d:%02d", hour, minute)
        }
        // Deliberately not zero-padded on the hour: "9:05 PM", not "09:05 PM",
        // which is what a 12-hour clock looks like everywhere else on the phone.
        val display = when {
            hour % 12 == 0 -> 12
            else -> hour % 12
        }
        val suffix = if (hour < 12) amLabel(context) else pmLabel(context)
        return String.format(locale, "%d:%02d %s", display, minute, suffix)
    }

    /** A full date and time, for deadlines and "saved at" lines. */
    fun dateTime(context: Context, ms: Long, withWeekday: Boolean = false): String {
        val datePart = if (withWeekday) "EEE d MMM" else "d MMM"
        val pattern = if (uses24Hour(context)) "$datePart, HH:mm" else "$datePart, h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
    }

    /**
     * The am/pm markers from the device's own locale rather than hardcoded
     * Latin ones, so an Arabic phone gets ص/م like the rest of its UI.
     */
    private fun amLabel(context: Context): String = marker(0)

    private fun pmLabel(context: Context): String = marker(13)

    private fun marker(hour: Int): String {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        return SimpleDateFormat("a", Locale.getDefault()).format(calendar.time)
    }
}
