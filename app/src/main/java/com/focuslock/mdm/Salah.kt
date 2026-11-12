package com.focuslock.mdm

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/**
 * Salah, for everyone. Around each prayer the phone keeps only calls, the Quran,
 * the Qibla and the clock open, from [BEFORE_MIN] before the Adhan until the
 * window closes (20 minutes by default, 60 for Jumu'ah). After [PRAYED_MIN]
 * minutes "I've prayed" opens the phone early.
 *
 * It is a layer of its own: it acts at prayer times whether or not a promise
 * runs, and only when it is switched on. Outside its windows it does nothing.
 */
object Salah {

    enum class Method(val fajr: Double, val isha: Double, val ishaAfterMaghribMin: Int = 0) {
        EGYPT(19.5, 17.5), MWL(18.0, 17.0), UMM_AL_QURA(18.5, 0.0, 90), ISNA(15.0, 15.0), KARACHI(18.0, 18.0)
    }

    data class Window(val name: String, val adhan: Long, val opens: Long, val closes: Long)

    const val BEFORE_MIN = 5
    const val PRAYED_MIN = 10
    const val JUMUAH_MIN = 60
    val NAMES = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    const val CAIRO_LAT = 30.0444
    const val CAIRO_LNG = 31.2357

    private const val K_ON = "salah_on"
    private const val K_LAT = "salah_lat"
    private const val K_LNG = "salah_lng"
    private const val K_TZ = "salah_tz"
    private const val K_PLACE = "salah_place"
    private const val K_METHOD = "salah_method"
    private const val K_HANAFI = "salah_hanafi"
    private const val K_WINDOW = "salah_window_min"
    private const val K_PRAYED = "salah_prayed_adhan"
    private fun kOffset(name: String) = "salah_offset_$name"

    // ── Settings ──────────────────────────────────────────────────

    fun isOn(c: Context): Boolean = FocusStore.getBool(c, K_ON, Personal.on)
    fun setOn(c: Context, on: Boolean) = FocusStore.setBool(c, K_ON, on)

    fun lat(c: Context): Double = FocusStore.getString(c, K_LAT, "").toDoubleOrNull() ?: CAIRO_LAT
    fun lng(c: Context): Double = FocusStore.getString(c, K_LNG, "").toDoubleOrNull() ?: CAIRO_LNG
    fun placeName(c: Context): String = FocusStore.getString(c, K_PLACE, "Cairo")
    fun zone(c: Context): TimeZone = TimeZone.getTimeZone(FocusStore.getString(c, K_TZ, "Africa/Cairo"))

    fun setPlace(c: Context, lat: Double, lng: Double, name: String, tz: TimeZone = TimeZone.getDefault()) {
        FocusStore.setString(c, K_LAT, lat.toString())
        FocusStore.setString(c, K_LNG, lng.toString())
        FocusStore.setString(c, K_PLACE, name)
        FocusStore.setString(c, K_TZ, tz.id)
    }

    fun method(c: Context): Method =
        runCatching { Method.valueOf(FocusStore.getString(c, K_METHOD, Method.EGYPT.name)) }.getOrDefault(Method.EGYPT)
    fun setMethod(c: Context, m: Method) = FocusStore.setString(c, K_METHOD, m.name)

    fun hanafi(c: Context): Boolean = FocusStore.getBool(c, K_HANAFI, false)
    fun setHanafi(c: Context, v: Boolean) = FocusStore.setBool(c, K_HANAFI, v)

    fun windowMinutes(c: Context): Int = FocusStore.getInt(c, K_WINDOW, if (Personal.on) 35 else 20).coerceIn(10, 90)
    fun setWindowMinutes(c: Context, v: Int) = FocusStore.setInt(c, K_WINDOW, v.coerceIn(10, 90))

    fun offset(c: Context, name: String): Int = FocusStore.getInt(c, kOffset(name), 0).coerceIn(-30, 30)
    fun setOffset(c: Context, name: String, v: Int) = FocusStore.setInt(c, kOffset(name), v.coerceIn(-30, 30))

    // ── Times ─────────────────────────────────────────────────────

    /** Adhan times (epoch ms) for the calendar day of [day] at a place. Pure, for tests. */
    fun times(
        day: Calendar, lat: Double, lng: Double, tz: TimeZone,
        method: Method = Method.EGYPT, hanafi: Boolean = false, offsets: Map<String, Int> = emptyMap()
    ): List<Pair<String, Long>> {
        val y = day.get(Calendar.YEAR); val m = day.get(Calendar.MONTH) + 1; val d = day.get(Calendar.DAY_OF_MONTH)
        val midnight = Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, 0, 0, 0) }.timeInMillis
        val offset = tz.getOffset(midnight + 12 * 3_600_000L) / 3_600_000.0
        val jd = julian(y, m, d) - lng / (15 * 24)
        val (decl, eqt) = sun(jd + 0.5)
        val noon = 12 - eqt - lng / 15 + offset
        fun angle(a: Double): Double {
            val v = (-sin(a) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat))
            return acos(v.coerceIn(-1.0, 1.0)) / 15
        }
        val asrAlt = -acot((if (hanafi) 2 else 1) + tan(Math.abs(lat - decl)))
        val maghrib = noon + angle(0.833)
        val isha = if (method.ishaAfterMaghribMin > 0) maghrib + method.ishaAfterMaghribMin / 60.0 else noon + angle(method.isha)
        val hours = listOf(noon - angle(method.fajr), noon + 1.0 / 60, noon + angle(asrAlt), maghrib, isha)
        return NAMES.zip(hours).map { (n, h) ->
            n to midnight + (Math.round(h * 60) + (offsets[n] ?: 0)) * 60_000L
        }
    }

    fun times(c: Context, day: Calendar): List<Pair<String, Long>> =
        times(day, lat(c), lng(c), zone(c), method(c), hanafi(c), NAMES.associateWith { offset(c, it) })

    /** The window [nowMs] is in for these settings, or null. Pure, for tests. */
    fun windowAt(nowMs: Long, tz: TimeZone, windowMin: Int, timesOf: (Calendar) -> List<Pair<String, Long>>): Window? {
        for (dayOffset in -1..1) {
            val day = Calendar.getInstance(tz).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, dayOffset) }
            val friday = day.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
            for ((name, at) in timesOf(day)) {
                val len = if (friday && name == "Dhuhr") maxOf(JUMUAH_MIN, windowMin) else windowMin
                val w = Window(name, at, at - BEFORE_MIN * 60_000L, at + len * 60_000L)
                if (nowMs >= w.opens && nowMs < w.closes) return w
            }
        }
        return null
    }

    /** The window that holds the phone now, or null (off, outside, or already prayed). */
    fun window(c: Context, nowMs: Long = System.currentTimeMillis()): Window? {
        if (!isOn(c)) return null
        val w = windowAt(nowMs, zone(c), windowMinutes(c)) { times(c, it) } ?: return null
        if (FocusStore.getLong(c, K_PRAYED, 0L) == w.adhan) return null
        return w
    }

    fun canMarkPrayed(c: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val w = window(c, nowMs) ?: return false
        return nowMs >= w.adhan + PRAYED_MIN * 60_000L
    }

    fun markPrayed(c: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!canMarkPrayed(c, nowMs)) return false
        FocusStore.setLong(c, K_PRAYED, window(c, nowMs)!!.adhan)
        return true
    }

    /** The next Adhan after [nowMs] (name, ms), for the notification and the alarm. */
    fun next(c: Context, nowMs: Long = System.currentTimeMillis()): Pair<String, Long>? {
        if (!isOn(c)) return null
        for (dayOffset in 0..1) {
            val day = Calendar.getInstance(zone(c)).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, dayOffset) }
            times(c, day).firstOrNull { it.second > nowMs }?.let { return it }
        }
        return null
    }

    // ── What stays open ───────────────────────────────────────────

    private val QURAN_HINTS = listOf("quran", "mushaf", "koran", "ayah", "qibla", "muslim", "athan", "adhan", "salat", "prayer")
    private val ALWAYS = setOf(
        "com.android.dialer", "com.google.android.dialer", "com.android.contacts",
        "com.google.android.contacts", "com.android.incallui", "com.android.server.telecom",
        "com.android.phone", "com.samsung.android.dialer", "com.samsung.android.incallui",
        "com.android.emergency", "com.google.android.apps.safetyhub",
        "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage"
    )

    fun allowed(pkg: String): Boolean = pkg in ALWAYS || QURAN_HINTS.any { pkg.lowercase().contains(it) }

    fun decide(c: Context, pkg: String, nowMs: Long = System.currentTimeMillis()): GuardDecision? {
        val w = window(c, nowMs) ?: return null
        if (allowed(pkg)) return null
        val back = Calendar.getInstance().apply { timeInMillis = w.closes }
            .let { TimeText.ofDay(c, it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
        return GuardDecision(
            pkg, GuardOutcome.BLOCK,
            c.getString(R.string.salah_block_headline, prayerName(c, w.name)),
            c.getString(R.string.salah_block_detail, back),
            SOURCE, offersBreak = false
        )
    }

    const val SOURCE = "salah"

    fun prayerName(c: Context, name: String): String = when (name) {
        "Fajr" -> c.getString(R.string.salah_fajr)
        "Dhuhr" -> c.getString(R.string.salah_dhuhr)
        "Asr" -> c.getString(R.string.salah_asr)
        "Maghrib" -> c.getString(R.string.salah_maghrib)
        else -> c.getString(R.string.salah_isha)
    }

    // ── Astronomy ─────────────────────────────────────────────────

    private fun julian(yy: Int, mm: Int, d: Int): Double {
        var y = yy; var m = mm
        if (m <= 2) { y--; m += 12 }
        val a = Math.floor(y / 100.0); val b = 2 - a + Math.floor(a / 4)
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    private fun sun(jd: Double): Pair<Double, Double> {
        val dd = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * dd, 360.0); val q = fix(280.459 + 0.98564736 * dd, 360.0)
        val l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * dd
        val ra = atan2(cos(e) * sin(l), cos(l)) / 15
        var eqt = q / 15 - fix(ra, 24.0)
        if (eqt > 12) eqt -= 24; if (eqt < -12) eqt += 24
        return asin(sin(e) * sin(l)) to eqt
    }

    private fun fix(a: Double, b: Double): Double { val r = a - b * Math.floor(a / b); return if (r < 0) r + b else r }
    private fun sin(d: Double) = Math.sin(Math.toRadians(d))
    private fun cos(d: Double) = Math.cos(Math.toRadians(d))
    private fun tan(d: Double) = Math.tan(Math.toRadians(d))
    private fun asin(x: Double) = Math.toDegrees(Math.asin(x))
    private fun acos(x: Double) = Math.toDegrees(Math.acos(x))
    private fun atan2(y: Double, x: Double) = Math.toDegrees(Math.atan2(y, x))
    private fun acot(x: Double) = Math.toDegrees(Math.atan(1 / x))
}
