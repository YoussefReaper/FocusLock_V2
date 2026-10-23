package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Daily windows, and which day owns the moment - the part that went wrong. */
class TimeWindowTest {

    private fun m(h: Int, min: Int = 0) = h * 60 + min

    @Test
    fun aDaytimeWindowIsTodaysOnly() {
        assertEquals(0, TimeWindow.owningDay(m(9), m(17), m(9)))
        assertEquals(0, TimeWindow.owningDay(m(9), m(17), m(16, 59)))
        assertNull(TimeWindow.owningDay(m(9), m(17), m(17)))
        assertNull(TimeWindow.owningDay(m(9), m(17), m(8, 59)))
    }

    @Test
    fun anOvernightWindowBelongsToTheNightItStarted() {
        // Friday 11 PM - 4 AM, asked about at 11:30 PM and at 1 AM.
        assertEquals(0, TimeWindow.owningDay(m(23), m(4), m(23, 30)))
        assertEquals(-1, TimeWindow.owningDay(m(23), m(4), m(1)))
        assertEquals(-1, TimeWindow.owningDay(m(23), m(4), m(0)))
        assertNull(TimeWindow.owningDay(m(23), m(4), m(4)))
        assertNull(TimeWindow.owningDay(m(23), m(4), m(12)))
    }

    @Test
    fun anEmptyWindowNeverRuns() {
        assertNull(TimeWindow.owningDay(m(22), m(22), m(22)))
        assertFalse(TimeWindow.contains(m(22), m(22), m(3)))
    }

    @Test
    fun containsAgreesWithOwningDay() {
        assertTrue(TimeWindow.contains(m(23), m(4), m(2)))
        assertTrue(TimeWindow.contains(m(9), m(17), m(12)))
        assertFalse(TimeWindow.contains(m(23), m(4), m(5)))
    }
}
