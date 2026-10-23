package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyword exceptions, checked against the screen that broke them.
 *
 * On a real phone WhatsApp's home screen carries its search bar twice: as
 * "Ask Meta AI or Search" and as a shorter "Ask Meta AI". The exception named
 * the first, the blocked phrase "meta ai" matched the second, and the content
 * guard pressed Back on WhatsApp every time it was opened.
 */
class KeywordExceptionTest {

    private val searchBar = "ask meta ai or search"

    @Test
    fun theFullControlIsCovered() {
        assertTrue(KeywordRules.coveredByException("ask meta ai or search", searchBar))
    }

    @Test
    fun aShortenedCopyOfTheControlIsCovered() {
        assertTrue(KeywordRules.coveredByException("ask meta ai", searchBar))
    }

    @Test
    fun theThingTheRuleIsForIsNotCovered() {
        // The Meta AI chat's own title must still be caught.
        assertFalse(KeywordRules.coveredByException("meta ai", searchBar))
    }

    @Test
    fun tinyFragmentsAreNeverCovered() {
        assertFalse(KeywordRules.coveredByException("ask", searchBar))
        assertFalse(KeywordRules.coveredByException("ask me", searchBar))
    }

    @Test
    fun textAroundAnExceptionIsStillCovered() {
        assertTrue(KeywordRules.coveredByException("tap here: ask meta ai or search now", searchBar))
    }

    @Test
    fun anEmptyExceptionCoversNothing() {
        assertFalse(KeywordRules.coveredByException("anything at all", ""))
    }
}
