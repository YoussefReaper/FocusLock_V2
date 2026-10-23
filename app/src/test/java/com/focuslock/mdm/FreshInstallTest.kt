package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshInstallTest {

    @Test fun anEmptyStoreIsAFreshInstall() = assertTrue(Migration.isFresh(emptySet()))

    @Test fun onlyTheVersionMarkerIsStillFresh() =
        assertTrue(Migration.isFresh(setOf("store_migration_version")))

    @Test fun anyOtherSettingMeansAnUpgrader() {
        assertFalse(Migration.isFresh(setOf("app_allowlist")))
        assertFalse(Migration.isFresh(setOf("store_migration_version", "session_total_count")))
    }
}
