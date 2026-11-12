package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WakeAlarmTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min, 0) }.timeInMillis

    private fun alarm(h: Int, m: Int, days: Set<Int> = emptySet()) =
        WakeAlarm.Alarm("x", h, m, days, WakeAlarm.Priority.MUST, WakeAlarm.SOUND_TONE, "")

    @Test fun ringsLaterTodayOrTomorrow() {
        val now = at(2026, 11, 12, 5, 0)
        assertEquals(at(2026, 11, 12, 6, 30), WakeAlarm.nextAt(alarm(6, 30), now))
        assertEquals(at(2026, 11, 13, 4, 0), WakeAlarm.nextAt(alarm(4, 0), now))
    }

    @Test fun skipsDaysNotChosen() {
        val thursday = at(2026, 11, 12, 5, 0)
        assertEquals(at(2026, 11, 14, 6, 0), WakeAlarm.nextAt(alarm(6, 0, setOf(Calendar.SATURDAY)), thursday))
    }

    @Test fun secondRingIsMuchHarder() {
        val (sums, words, snooze) = WakeAlarm.task(WakeAlarm.Priority.MUST, second = true)
        assertEquals(3, sums); assertEquals(12, words); assertFalse(snooze)
        val first = WakeAlarm.task(WakeAlarm.Priority.MUST, second = false)
        assertTrue(first.second < words)
        assertTrue(WakeAlarm.task(WakeAlarm.Priority.GENTLE, false).third)
    }

    @Test fun phraseHasTheWordsAsked() {
        assertEquals(12, WakeAlarm.phrase(12, seed = 1).split(" ").size)
    }

    @Test fun adhanVoicesLeaveOutA3() {
        assertFalse("a3" in WakeAlarm.ADHANS)
        assertEquals(9, WakeAlarm.ADHANS.size)
    }
}
