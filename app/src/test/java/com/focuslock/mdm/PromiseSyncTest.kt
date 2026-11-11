package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseSyncTest {
    @Test fun strengthsRoundTrip() {
        assertEquals("sealed", PromiseSync.strengthOf(FocusMode.SANCTUARY, noExit = true))
        assertEquals("soft", PromiseSync.strengthOf(FocusMode.SOFT, noExit = false))
        assertEquals("firm", PromiseSync.strengthOf(FocusMode.BLOCK, noExit = false))
        assertEquals(FocusMode.SANCTUARY to true, PromiseSync.modeFor("sealed"))
        assertEquals(FocusMode.BLOCK to false, PromiseSync.modeFor("firm"))
    }

    @Test fun aPulledPromiseStartsOnlyWhenNothingRunsAndItWasNotEndedHere() {
        val now = 1_000_000L
        assertTrue(PromiseSync.shouldStart("a", now + 30 * 60_000, now, localActive = false, endedHereId = null))
        assertFalse(PromiseSync.shouldStart("a", now + 30 * 60_000, now, localActive = true, endedHereId = null))
        assertFalse(PromiseSync.shouldStart("a", now + 30 * 60_000, now, localActive = false, endedHereId = "a"))
        assertFalse(PromiseSync.shouldStart("a", now + 30_000, now, localActive = false, endedHereId = null))
    }
}
