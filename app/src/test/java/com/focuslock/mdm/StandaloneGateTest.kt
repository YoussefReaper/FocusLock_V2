package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blocking with no session running is opt-in, and every opt-in starts off. */
class StandaloneGateTest {

    @Test
    fun withNoSessionAndTheSwitchOffNothingActs() {
        assertFalse(Standalone.mayAct(sessionRunning = false, runsOnItsOwn = false))
    }

    @Test
    fun withNoSessionAndTheSwitchOnTheRuleActs() {
        assertTrue(Standalone.mayAct(sessionRunning = false, runsOnItsOwn = true))
    }

    @Test
    fun aRunningSessionActsWhateverTheSwitchSays() {
        assertTrue(Standalone.mayAct(sessionRunning = true, runsOnItsOwn = false))
        assertTrue(Standalone.mayAct(sessionRunning = true, runsOnItsOwn = true))
    }

    @Test
    fun everyRunsOnItsOwnSwitchDefaultsOffAndIsLabelled() {
        val ids = Standalone.switches + Capabilities.SCHEDULES + Capabilities.BEDTIME_MODE
        assertEquals(8, ids.distinct().size)
        for (id in ids) {
            val spec = Capabilities.spec(id) ?: throw AssertionError("no spec for $id")
            assertFalse("$id must start off", spec.default)
            assertTrue("$id must say it runs on its own", spec.runsOnItsOwn)
        }
    }
}
