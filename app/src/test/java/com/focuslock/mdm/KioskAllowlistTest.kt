package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock-task package assembly.
 *
 * [KioskPolicy.buildLockTaskPackages] is deliberately pure so the rule that
 * matters most — what a kiosk session will and will not let through — can be
 * checked without a device.
 */
class KioskAllowlistTest {

    private val own = "com.focuslock.mdm"

    @Test
    fun ownPackageIsAlwaysPermitted() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false
        )
        assertTrue(packages.contains(own))
    }

    @Test
    fun alwaysAllowedAppsSurviveKiosk() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = setOf("com.android.phone", "com.google.android.apps.maps"),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false
        )
        assertTrue(packages.contains("com.android.phone"))
        assertTrue(packages.contains("com.google.android.apps.maps"))
    }

    @Test
    fun launcherIsExcludedWhenFocusLockIsHome() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false
        )
        SystemSurfaces.launchers.forEach { launcher ->
            assertFalse(packages.contains(launcher))
        }
    }

    @Test
    fun launcherIsIncludedWhenPersistentHomeIsOff() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = true
        )
        SystemSurfaces.launchers.forEach { launcher ->
            assertTrue(packages.contains(launcher))
        }
    }

    @Test
    fun settingsOnlyReachableBeforeTheBaselineIsComplete() {
        val duringSetup = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = false,
            allowLauncherEscape = false
        )
        val afterSetup = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false
        )

        assertTrue(duringSetup.contains("com.android.settings"))
        assertFalse(afterSetup.contains("com.android.settings"))
    }

    /**
     * Setup finishing used to be the moment Settings closed for good. A session
     * started with a permission still ungranted then pinned the phone to
     * FocusLock with no way to reach the toggle - and no recovery screen
     * either, since PermissionGuard only reacts to a permission that was
     * granted and taken away.
     */
    @Test
    fun settingsStayReachableWhileAPermissionIsMissing() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = setOf("com.example.blocked"),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false,
            permissionsMissing = true
        )

        assertTrue(packages.contains("com.android.settings"))
        assertTrue(packages.contains("com.google.android.permissioncontroller"))
        // Settings is the recovery route, not an escape hatch: the launcher
        // stays out, so the session is still the session.
        SystemSurfaces.launchers.forEach { launcher ->
            assertFalse(packages.contains(launcher))
        }
    }

    /** The route has to survive an overlay window too - that is the state that pins hardest. */
    @Test
    fun settingsStayReachableInsideAnOverlayWindowWhenAPermissionIsMissing() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false,
            permissionsMissing = true,
            scheduleOverlayAllowed = setOf("com.example.windowOnly")
        )

        assertTrue(packages.contains("com.android.settings"))
        assertTrue(packages.contains("com.example.windowOnly"))
    }

    @Test
    fun systemSurfacesAreNeverLockedOut() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false
        )
        assertTrue(packages.contains("com.android.systemui"))
        assertTrue(packages.contains("android"))
    }

    // ── Overlay schedule windows ─────────────────────────────────

    @Test
    fun overlayWindowReplacesTheStandingAllowlistsEntirely() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = setOf("com.example.kioskOnly"),
            alwaysAllowed = emptySet(),
            scheduleAllowed = setOf("com.example.otherWindow"),
            baselineReady = true,
            allowLauncherEscape = false,
            scheduleOverlayAllowed = setOf("com.example.thisWindowOnly")
        )
        assertTrue(packages.contains("com.example.thisWindowOnly"))
        assertFalse(packages.contains("com.example.kioskOnly"))
        assertFalse(packages.contains("com.example.otherWindow"))
    }

    @Test
    fun anEarnTaskCannotWidenPastAnOverlayWindow() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false,
            earnAllowed = setOf("com.example.earnTaskApp", "com.example.windowApp"),
            scheduleOverlayAllowed = setOf("com.example.windowApp")
        )
        assertTrue(packages.contains("com.example.windowApp"))
        assertFalse(packages.contains("com.example.earnTaskApp"))
    }

    @Test
    fun ownPackageAndSystemSurfacesSurviveAnOverlayWindow() {
        val packages = KioskPolicy.buildLockTaskPackages(
            ownPackage = own,
            userAllowed = emptySet(),
            alwaysAllowed = emptySet(),
            scheduleAllowed = emptySet(),
            baselineReady = true,
            allowLauncherEscape = false,
            scheduleOverlayAllowed = emptySet()
        )
        assertTrue(packages.contains(own))
        assertTrue(packages.contains("com.android.systemui"))
    }
}
