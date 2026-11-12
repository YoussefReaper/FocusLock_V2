package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SalahTest {
    private val cairo = TimeZone.getTimeZone("Africa/Cairo")
    private fun day(y: Int, m: Int, d: Int, tz: TimeZone = cairo) =
        Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, 12, 0, 0) }
    private fun hm(ms: Long, tz: TimeZone = cairo): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
    private fun cairoTimes(cal: Calendar, m: Salah.Method = Salah.Method.EGYPT, hanafi: Boolean = false) =
        Salah.times(cal, Salah.CAIRO_LAT, Salah.CAIRO_LNG, cairo, m, hanafi)

    @Test fun egyptianMatchesTheOwnersCairoTimes() {
        assertEquals(listOf("04:46", "11:40", "14:41", "17:02", "18:22"), cairoTimes(day(2026, 11, 9)).map { hm(it.second) })
    }

    @Test fun methodsMoveFajrAndIsha() {
        val egypt = cairoTimes(day(2026, 11, 9))
        val isna = cairoTimes(day(2026, 11, 9), Salah.Method.ISNA)
        assertTrue(isna[0].second > egypt[0].second) // 15° Fajr is later than 19.5°
        val umm = cairoTimes(day(2026, 11, 9), Salah.Method.UMM_AL_QURA)
        assertEquals(umm[3].second + 90 * 60_000L, umm[4].second) // Isha 90 min after Maghrib
    }

    @Test fun hanafiAsrIsLater() {
        assertTrue(cairoTimes(day(2026, 11, 9), hanafi = true)[2].second > cairoTimes(day(2026, 11, 9))[2].second)
    }

    @Test fun offsetsShiftOnePrayer() {
        val base = cairoTimes(day(2026, 11, 9))
        val moved = Salah.times(day(2026, 11, 9), Salah.CAIRO_LAT, Salah.CAIRO_LNG, cairo, offsets = mapOf("Maghrib" to 3))
        assertEquals(base[3].second + 3 * 60_000L, moved[3].second)
        assertEquals(base[2].second, moved[2].second)
    }

    @Test fun windowIsFiveBeforeToTwentyAfter() {
        val dhuhr = cairoTimes(day(2026, 11, 9))[1].second // a Monday
        val at = { now: Long -> Salah.windowAt(now, cairo, 20) { cairoTimes(it) } }
        assertNull(at(dhuhr - 6 * 60_000L))
        assertEquals("Dhuhr", at(dhuhr - 5 * 60_000L)?.name)
        assertEquals("Dhuhr", at(dhuhr + 19 * 60_000L)?.name)
        assertNull(at(dhuhr + 20 * 60_000L))
    }

    @Test fun jumuahHoldsAnHour() {
        val dhuhr = cairoTimes(day(2026, 11, 13))[1].second // a Friday
        val w = Salah.windowAt(dhuhr + 45 * 60_000L, cairo, 20) { cairoTimes(it) }
        assertEquals("Dhuhr", w?.name)
        assertEquals(dhuhr + 60 * 60_000L, w?.closes)
    }

    @Test fun otherCitiesWork() {
        val london = TimeZone.getTimeZone("Europe/London")
        val t = Salah.times(day(2026, 6, 21, london), 51.5074, -0.1278, london, Salah.Method.MWL)
        // Midsummer London: Dhuhr around 13:02, Maghrib around 21:2x.
        assertTrue(hm(t[1].second, london).startsWith("13:0"))
        assertTrue(hm(t[3].second, london).startsWith("21:2"))
    }

    @Test fun callsQuranQiblaAndClockStayOpen() {
        assertTrue(Salah.allowed("com.google.android.dialer"))
        assertTrue(Salah.allowed("com.quran.labs.androidquran"))
        assertTrue(Salah.allowed("com.google.android.deskclock"))
        assertTrue(!Salah.allowed("com.android.chrome"))
    }
}
