package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalGuardTest {
    @Test fun appInfoAndUninstallScreensAreRemovalScreens() {
        assertTrue(SystemSurfaces.isRemovalScreen("com.android.settings", "com.android.settings.spa.SpaActivity"))
        assertTrue(SystemSurfaces.isRemovalScreen("com.android.settings", "com.android.settings.applications.InstalledAppDetails"))
        assertTrue(SystemSurfaces.isRemovalScreen("com.android.settings", "com.android.settings.DeviceAdminAdd"))
        assertTrue(SystemSurfaces.isRemovalScreen("com.google.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity"))
    }

    @Test fun ordinarySettingsStayOpen() {
        assertFalse(SystemSurfaces.isRemovalScreen("com.android.settings", "com.android.settings.wifi.WifiSettings"))
        assertFalse(SystemSurfaces.isRemovalScreen("com.android.settings", "com.android.settings.Settings\$SoundSettingsActivity"))
        assertFalse(SystemSurfaces.isRemovalScreen("com.google.android.packageinstaller", "com.android.packageinstaller.InstallStart"))
        assertFalse(SystemSurfaces.isRemovalScreen("com.google.android.youtube", "com.google.android.apps.youtube.app.WatchWhileActivity"))
        assertFalse(SystemSurfaces.isRemovalScreen("com.android.settings", null))
    }
}
