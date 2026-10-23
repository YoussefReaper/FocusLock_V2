package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Test

/** A first session with no early exit can never be longer than an hour. */
class FirstSessionCapTest {

    private val hour = 60L * 60L * 1000L
    private val ninetyDays = 90L * 24L * hour

    @Test
    fun firstNoExitSessionIsHeldToAnHour() {
        assertEquals(hour, SessionManager.capFirstNoExitSession(ninetyDays, canEndEarly = false, sessionsFinished = 0))
    }

    @Test
    fun shorterFirstSessionIsLeftAlone() {
        assertEquals(25L * 60L * 1000L, SessionManager.capFirstNoExitSession(25L * 60L * 1000L, false, 0))
    }

    @Test
    fun sessionsWithAnExitAreNeverCapped() {
        assertEquals(ninetyDays, SessionManager.capFirstNoExitSession(ninetyDays, canEndEarly = true, sessionsFinished = 0))
    }

    @Test
    fun capLiftsAfterOneFinishedSession() {
        assertEquals(ninetyDays, SessionManager.capFirstNoExitSession(ninetyDays, canEndEarly = false, sessionsFinished = 1))
    }
}
