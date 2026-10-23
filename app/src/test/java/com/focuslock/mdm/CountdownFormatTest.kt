package com.focuslock.mdm

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CountdownFormatTest {

    private fun fmt(ms: Long) = SessionManager.formatCountdown(ms, { "${it}d" }, Locale.US)
    private val s = 1_000L
    private val m = 60 * s
    private val h = 60 * m
    private val d = 24 * h

    @Test fun underAnHourIsMinutesAndSeconds() = assertEquals("59:12", fmt(59 * m + 12 * s))

    @Test fun pastAnHourAddsHours() = assertEquals("1:21:05", fmt(h + 21 * m + 5 * s))

    @Test fun daysComeFirstOnlyWhenThereAreDays() = assertEquals("2d 3:20:05", fmt(2 * d + 3 * h + 20 * m + 5 * s))

    @Test fun anExactDayStillShowsTheClock() = assertEquals("1d 0:00:00", fmt(d))

    @Test fun zeroAndNegativeAreZero() {
        assertEquals("00:00", fmt(0))
        assertEquals("00:00", fmt(-5 * s))
    }

    @Test fun noLettersSurviveForALocaleWithItsOwnDigitsAndUnits() {
        val out = SessionManager.formatCountdown(h + 5 * m, { "${it} يوم" }, Locale.US)
        assertFalse(out.any { it in "hms" })
    }
}
