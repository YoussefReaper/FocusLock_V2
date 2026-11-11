package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPasswordTest {
    @Test fun firm_with_a_password_needs_it() =
        assertEquals(SessionPassword.EndNeed.PASSWORD, SessionPassword.endNeed(firm = true, passwordSet = true))

    @Test fun firm_without_a_password_cannot_end_early() =
        assertEquals(SessionPassword.EndNeed.IMPOSSIBLE, SessionPassword.endNeed(firm = true, passwordSet = false))

    @Test fun soft_needs_nothing() =
        assertEquals(SessionPassword.EndNeed.NOTHING, SessionPassword.endNeed(firm = false, passwordSet = true))

    @Test fun hash_is_salted_and_repeatable() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(3, 2, 1)
        assertTrue(SessionPassword.hash("correct horse", a).contentEquals(SessionPassword.hash("correct horse", a)))
        assertFalse(SessionPassword.hash("correct horse", a).contentEquals(SessionPassword.hash("correct horse", b)))
        assertFalse(SessionPassword.hash("correct horse", a).contentEquals(SessionPassword.hash("correct hors", a)))
    }
}
