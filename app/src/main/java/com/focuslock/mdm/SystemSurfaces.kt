package com.focuslock.mdm

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Android's own plumbing.
 *
 * These are not policy and never appear in the Rules screen: they are the parts
 * of the OS that have to keep working for the phone to be a phone at all. The
 * user's lists decide what is *blocked*; this file only stops FocusLock from
 * fighting the system UI, the installer or the share sheet.
 */
object SystemSurfaces {

    /** Must stay reachable in every mode, or the device becomes unusable. */
    val critical: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.keyguard",
        "com.miui.systemui",
        "com.miui.systemui.plugin",
        "com.samsung.android.app.systemui",
        "com.oneplus.systemui",
        "com.coloros.systemui",
        "com.oplus.systemui",
        "com.vivo.systemui",
        "com.huawei.systemui",
        "com.android.intentresolver",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.google.android.gms",
        "com.google.android.inputmethod.latin",
        "com.facemoji.keyboard.xiaomi",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey"
    )

    /** Home screens and launchers: where a kiosk session leaks out. */
    val launchers: Set<String> = setOf(
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "net.oneplus.launcher",
        "com.oppo.launcher",
        "com.bbk.launcher2",
        "com.huawei.android.launcher"
    )

    /** Settings surfaces that can force-stop apps or revoke FocusLock's permissions. */
    val settings: Set<String> = setOf(
        "com.android.settings",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.miui.permcenter",
        "com.miui.powerkeeper",
        "com.miui.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.settings"
    )

    /** Class-name fragments for the "draw over other apps" pages, per OEM. */
    val overlayPermissionClassHints: Set<String> = setOf(
        "overlay",
        "drawover",
        "systemalertwindow",
        "manageoverlay",
        "floatwindow",
        "displaypopup"
    )

    /** Class-name fragments for USB / file-transfer pages, which stay reachable. */
    val usbSettingsClassHints: Set<String> = setOf(
        "usb",
        "filetransfer",
        "mtp",
        "ptp",
        "usbmode",
        "usbpreferences",
        "usbprefs",
        "usbdetails",
        "usbsettings",
        "storageusb"
    )

    fun isCritical(packageName: String): Boolean = packageName in critical

    fun isLauncher(packageName: String): Boolean = packageName in launchers

    fun isSettings(packageName: String): Boolean = packageName in settings

    /**
     * Whether this package ships with the ROM.
     *
     * Used in exactly one place: while the recovery screen's own "go grant it"
     * window is open, so that the system's consent UI is never covered by the
     * emergency overlay in the middle of the one action that screen asks for.
     * That UI is not always the Settings package - the accessibility
     * confirmation is drawn by the permission controller or SystemUI on some
     * OEM builds, and by a differently-named settings package on others - and a
     * hardcoded name list cannot cover every ROM, which is how the overlay
     * ended up covering the very screen it was telling the person to use.
     *
     * A package cannot be installed into the system image by hand, so this
     * opens no route to an ordinary app.
     */
    fun isSystemSurface(context: Context, packageName: String): Boolean =
        // Asked every tick while the grant window is open, and the answer
        // cannot change without a new ROM.
        systemFlags.getOrPut(packageName) {
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Exception) {
                false
            }
        }

    private val systemFlags = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun normalize(className: String?): String? {
        val lower = className?.lowercase(java.util.Locale.US) ?: return null
        return lower.replace("_", "").replace("-", "")
    }

    fun isOverlayPermissionScreen(packageName: String, className: String?): Boolean {
        if (packageName !in settings) return false
        val normalized = normalize(className) ?: return false
        return overlayPermissionClassHints.any { normalized.contains(it) }
    }

    fun isUsbSettingsScreen(packageName: String, className: String?): Boolean {
        if (packageName !in settings) return false
        val normalized = normalize(className) ?: return false
        return usbSettingsClassHints.any { normalized.contains(it) }
    }

    /** Domains that must load for sign-in flows to complete inside the safe browser. */
    val authDomains: List<String> = listOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "gstatic.com",
        "openai.com",
        "auth0.openai.com",
        "chatgpt.com"
    )
}
