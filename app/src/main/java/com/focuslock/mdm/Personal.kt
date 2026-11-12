package com.focuslock.mdm

import java.util.Calendar
import java.util.TimeZone

/**
 * The owner's own rules. Only in the personal build (flavor "personal"); the store build never
 * runs any of this.
 *  - Around every prayer (Cairo, Egyptian General Authority) only calls and the Quran open,
 *    from 5 minutes before the Adhan to 35 minutes after.
 * (The AI-app block and the video quota were dropped at his request: he controls those himself.)
 */
object Personal {
    val on: Boolean get() = BuildConfig.FLAVOR == "personal"

    // ── Salah (now [Salah] for everyone; these keep the owner's Cairo defaults) ──
    const val BEFORE_MIN = Salah.BEFORE_MIN
    const val AFTER_MIN = 35
    private val CAIRO = TimeZone.getTimeZone("Africa/Cairo")

    fun allowedInSalah(pkg: String): Boolean = Salah.allowed(pkg)

    fun inSalah(nowMs: Long = System.currentTimeMillis()): Boolean = salahWindow(nowMs) != null

    /** Name and Adhan time (ms) of the prayer whose window contains [nowMs], or null. Plain days only. */
    fun salahWindow(nowMs: Long = System.currentTimeMillis()): Pair<String, Long>? {
        for (dayOffset in -1..1) {
            val day = Calendar.getInstance(CAIRO).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, dayOffset) }
            for ((name, at) in times(day)) {
                if (nowMs >= at - BEFORE_MIN * 60_000L && nowMs < at + AFTER_MIN * 60_000L) return name to at
            }
        }
        return null
    }

    /** Adhan times (epoch ms) for the calendar day of [day], Cairo, Egyptian method. */
    fun times(day: Calendar): List<Pair<String, Long>> = Salah.times(day, Salah.CAIRO_LAT, Salah.CAIRO_LNG, CAIRO)
}
