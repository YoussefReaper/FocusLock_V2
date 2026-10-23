package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule behind "you can always tighten a running session, never loosen it":
 * which direction an edit goes.
 */
class SessionLockTest {

    private val t = EditDirection.TIGHTEN
    private val l = EditDirection.LOOSEN

    @Test
    fun addingToABlocklistTightens() {
        assertEquals(t, SessionLock.forRestrictionSet(setOf("a"), setOf("a", "b")))
        assertEquals(t, SessionLock.forRestrictionSet(setOf("a"), setOf("a")))
    }

    @Test
    fun removingFromABlocklistLoosens() {
        assertEquals(l, SessionLock.forRestrictionSet(setOf("a", "b"), setOf("a")))
        assertEquals(l, SessionLock.forRestrictionSet(setOf("a"), setOf("b")))
    }

    @Test
    fun anAllowlistIsTheMirrorImage() {
        assertEquals(t, SessionLock.forExemptionSet(setOf("a", "b"), setOf("a")))
        assertEquals(l, SessionLock.forExemptionSet(setOf("a"), setOf("a", "b")))
        assertEquals(l, SessionLock.forExemptionSet(setOf("a"), setOf("b")))
    }

    @Test
    fun budgetsTightenOnlyDownward() {
        assertEquals(t, SessionLock.forBudget(null, 30))
        assertEquals(t, SessionLock.forBudget(60, 30))
        assertEquals(t, SessionLock.forBudget(30, 30))
        assertEquals(l, SessionLock.forBudget(30, 60))
        assertEquals(l, SessionLock.forBudget(30, null))
    }
}
