package com.focuslock.mdm

/**
 * Daily time windows given as minutes since midnight, including ones that run
 * past midnight.
 *
 * Schedules and bedtime each had their own copy of this arithmetic, and both
 * answered only "is now inside the window" - never "inside *which day's*
 * window". For a window that crosses midnight those are different questions:
 * at 1 AM on Saturday, a Friday 11 PM-4 AM window is running, but it is
 * Friday's window, not Saturday's. Asking "is today Friday?" at that moment
 * said no, so a weekly overnight schedule switched itself off at midnight - and
 * a Saturday-only one switched on in the small hours of Saturday, which
 * belonged to Friday night. Pure, and unit-tested.
 */
object TimeWindow {

    /**
     * Which day's occurrence of the window covers [nowMinutes]:
     * 0 for today's, -1 for yesterday's (the tail of an overnight window),
     * or null when neither does. An empty window (start == end) never runs.
     */
    fun owningDay(startMinutes: Int, endMinutes: Int, nowMinutes: Int): Int? {
        if (startMinutes == endMinutes) return null
        return if (endMinutes > startMinutes) {
            if (nowMinutes in startMinutes until endMinutes) 0 else null
        } else {
            when {
                nowMinutes >= startMinutes -> 0
                nowMinutes < endMinutes -> -1
                else -> null
            }
        }
    }

    fun contains(startMinutes: Int, endMinutes: Int, nowMinutes: Int): Boolean =
        owningDay(startMinutes, endMinutes, nowMinutes) != null
}
