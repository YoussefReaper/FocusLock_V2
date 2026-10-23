package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the accessibility toggle.
 *
 * [SetupChecks.accessibilityListContains] is pure so the one string this whole
 * guard hangs on can be checked without a device. It matters more than it looks:
 * while it answered "not granted" to someone looking at an ON toggle in
 * Settings, the recovery screen could not clear - it only clears once the
 * permission reads as restored - so the single thing it asked for was the single
 * thing that changed nothing. A screen like that has no way out.
 */
class AccessibilityListTest {

    private val full = "com.focuslock.mdm/com.focuslock.mdm.ContentGuardService"

    @Test
    fun theFullyQualifiedFormMatches() {
        assertTrue(SetupChecks.accessibilityListContains(full, full))
    }

    @Test
    fun theShortFormMatchesTheSameService() {
        assertTrue(
            SetupChecks.accessibilityListContains(
                "com.focuslock.mdm/.ContentGuardService",
                full
            )
        )
        assertTrue(
            SetupChecks.accessibilityListContains(
                full,
                "com.focuslock.mdm/.ContentGuardService"
            )
        )
    }

    @Test
    fun otherEnabledServicesDoNotHideIt() {
        val list = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService:" +
            full + ":com.android.switchaccess/com.android.switchaccess.SwitchAccessService"
        assertTrue(SetupChecks.accessibilityListContains(list, full))
    }

    @Test
    fun otherAppsServicesNeverCount() {
        // Same package prefix, different service: a substring search would say
        // yes, a component comparison says no.
        assertFalse(
            SetupChecks.accessibilityListContains(
                "com.focuslock.mdm/com.focuslock.mdm.ContentGuardServiceOther",
                full
            )
        )
        assertFalse(
            SetupChecks.accessibilityListContains(
                "com.focuslock.mdm/com.focuslock.mdm.OtherService",
                full
            )
        )
    }

    @Test
    fun nothingEnabledIsNotGranted() {
        assertFalse(SetupChecks.accessibilityListContains(null, full))
        assertFalse(SetupChecks.accessibilityListContains("", full))
        assertFalse(SetupChecks.accessibilityListContains("   ", full))
        assertFalse(
            SetupChecks.accessibilityListContains(
                "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService",
                full
            )
        )
    }

    @Test
    fun tornDownListHasNoEmptyEntryThatMatches() {
        assertTrue(SetupChecks.accessibilityListContains("$full:", full))
        assertTrue(SetupChecks.accessibilityListContains(":$full", full))
        assertTrue(SetupChecks.accessibilityListContains(":$full::", full))
    }

    @Test
    fun straySeparatorsAreNotComponents() {
        assertFalse(SetupChecks.accessibilityListContains(":", full))
        assertFalse(SetupChecks.accessibilityListContains("::", full))
        assertFalse(SetupChecks.accessibilityListContains("noSlashAtAll", full))
        assertFalse(SetupChecks.accessibilityListContains("com.focuslock.mdm/", full))
    }

    @Test
    fun caseAndWhitespaceDoNotMatter() {
        assertTrue(SetupChecks.accessibilityListContains("  $full  ", full))
        assertTrue(
            SetupChecks.accessibilityListContains(
                "com.focuslock.mdm/com.focuslock.mdm.contentguardservice",
                full
            )
        )
    }
}