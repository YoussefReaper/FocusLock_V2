package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PersonalTest {
    private fun cairoDay(y: Int, m: Int, d: Int) =
        Calendar.getInstance(TimeZone.getTimeZone("Africa/Cairo")).apply { clear(); set(y, m - 1, d, 12, 0, 0) }

    private fun hm(ms: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Africa/Cairo")).apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    @Test fun cairoTimesMatchTheLaptop() {
        // Same maths as Pinehold PC, which gave 04:46 11:40 14:41 17:02 18:22 for this day.
        val t = Personal.times(cairoDay(2026, 11, 9)).map { hm(it.second) }
        assertEquals(listOf("04:46", "11:40", "14:41", "17:02", "18:22"), t)
    }

    @Test fun salahWindowIsFiveBeforeToThirtyFiveAfter() {
        val dhuhr = Personal.times(cairoDay(2026, 11, 9))[1].second
        assertFalse(Personal.inSalah(dhuhr - 6 * 60_000L))
        assertTrue(Personal.inSalah(dhuhr - 5 * 60_000L))
        assertTrue(Personal.inSalah(dhuhr + 34 * 60_000L))
        assertFalse(Personal.inSalah(dhuhr + 35 * 60_000L))
    }

    @Test fun callsAndQuranStayOpenInSalah() {
        assertTrue(Personal.allowedInSalah("com.google.android.dialer"))
        assertTrue(Personal.allowedInSalah("com.quran.labs.androidquran"))
        assertFalse(Personal.allowedInSalah("com.android.chrome"))
    }

    @Test fun aiHostsMatchSubdomainsOnly() {
        assertTrue(Personal.isAiHost("gemini.google.com"))
        assertTrue(Personal.isAiHost("www.claude.ai"))
        assertFalse(Personal.isAiHost("classroom.google.com"))
    }

    @Test fun videoGateFollowsTheFourteenDayRule() {
        assertNull(Personal.videoGate(curriculum = true, daysIn = 0, todaysPick = null, videoId = "a"))
        assertTrue(Personal.videoGate(false, 3, null, "a")!!.contains("first 14 days"))
        assertNull(Personal.videoGate(false, 14, null, "a"))
        assertNull(Personal.videoGate(false, 20, "a", "a"))
        assertTrue(Personal.videoGate(false, 20, "a", "b")!!.contains("one free video"))
    }
}
