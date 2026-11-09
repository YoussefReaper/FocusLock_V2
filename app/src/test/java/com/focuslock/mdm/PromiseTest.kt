package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseTest {
    @Test fun threeStrengthsMapToRisingModes() {
        assertEquals(FocusMode.SOFT, Promise.Strength.SOFT.mode)
        assertEquals(FocusMode.BLOCK, Promise.Strength.FIRM.mode)
        assertEquals(FocusMode.SANCTUARY, Promise.Strength.SEALED.mode)
        assertTrue(Promise.Strength.SOFT.mode.strength < Promise.Strength.FIRM.mode.strength)
        assertTrue(Promise.Strength.FIRM.mode.strength < Promise.Strength.SEALED.mode.strength)
    }

    @Test fun onlySealedHasNoExit() {
        assertFalse(Promise.Strength.SOFT.noExit)
        assertFalse(Promise.Strength.FIRM.noExit)
        assertTrue(Promise.Strength.SEALED.noExit)
    }

    @Test fun unknownStrengthFallsBackToFirm() {
        assertEquals(Promise.Strength.FIRM, Promise.Strength.of("nonsense"))
        assertEquals(Promise.Strength.FIRM, Promise.Strength.of(null))
    }

    @Test fun wordsAreOneTrimmedCappedLine() {
        assertEquals("I want my mornings back.", Promise.clean("  I want\n my   mornings back.  "))
        assertEquals(Promise.MAX_WORDS, Promise.clean("x".repeat(300)).length)
    }

    @Test fun onlyPicturesAndClipsAreAccepted() {
        assertTrue(Promise.acceptsType("image/gif"))
        assertTrue(Promise.acceptsType("video/mp4"))
        assertFalse(Promise.acceptsType("application/pdf"))
        assertFalse(Promise.acceptsType(""))
    }
}
