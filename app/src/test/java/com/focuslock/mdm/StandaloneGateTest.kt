package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blocking with no session running waits on one master switch, which starts off. */
class StandaloneGateTest {

    @Test
    fun aRunningSessionActsWhateverTheMasterSays() {
        assertTrue(Standalone.mayAct(sessionRunning = true, outsideAllowed = false))
        assertTrue(Standalone.mayAct(sessionRunning = true, outsideAllowed = true))
    }

    @Test
    fun withNoSessionAndTheMasterOffNothingActs() {
        assertFalse(Standalone.mayAct(sessionRunning = false, outsideAllowed = false))
    }

    @Test
    fun withNoSessionAndTheMasterOnTheBlockerActs() {
        assertTrue(Standalone.mayAct(sessionRunning = false, outsideAllowed = true))
    }

    @Test
    fun theMasterSwitchExistsAndStartsOff() {
        val spec = Capabilities.spec(Capabilities.OUTSIDE_SESSION)
        assertNotNull(spec)
        assertFalse(spec!!.default)
    }
}
