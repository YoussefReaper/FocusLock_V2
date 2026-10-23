package com.focuslock.mdm

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.PowerManager
import android.provider.Settings

/**
 * What Android has actually granted.
 *
 * Each check maps to one capability, so the app can say "this needs that
 * permission" rather than demanding every permission up front. Asking for
 * notification access from someone who never turned the shield on is how a
 * setup flow loses people.
 */
object SetupChecks {

    /**
     * Whether Usage Access has actually been granted.
     *
     * The query is not the permission. `queryUsageStats` over the last minute
     * only ever answers "was something used just now", so a phone whose screen
     * is off, that is sitting on one screen, or that has only just booted
     * returns an empty list with Usage Access fully granted.
     *
     * Reading that as "not granted" is what turned a locked phone into a
     * phantom revocation: [PermissionGuard] saw a permission it had recorded as
     * granted stop being granted, declared an emergency over the running
     * session, and then could never clear it - the permission it was waiting to
     * see come back had never gone anywhere, so the recovery screen sat there
     * asking for something that was not missing. The app-op is the permission;
     * the query is kept only as a second opinion for the OEM builds whose
     * toggle does not surface as [AppOpsManager.MODE_ALLOWED].
     */
    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val mode = appOps?.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            // MODE_DEFAULT means "decided by the permission", which is exactly
            // how the usage service itself reads it - so ask the permission,
            // which is cheap and exact, before the query.
            AppOpsManager.MODE_DEFAULT ->
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED || anyUsageEventJustNow(context)
            else -> anyUsageEventJustNow(context)
        }
    } catch (_: Exception) {
        false
    }

    /** The second opinion's last answer, and when it was taken. See [anyUsageEventJustNow]. */
    @Volatile
    private var usageProbe: Pair<Long, Boolean>? = null

    /**
     * Held for a few seconds, because it is expensive and it is asked a lot.
     *
     * The permission guard asks [hasUsageAccess] every second through a
     * session, and on a phone whose toggle does not read as MODE_ALLOWED this
     * query - today's usage for every app on the phone - was the answer every
     * single time. A few seconds of staleness costs nothing: revoking the
     * permission is still caught, just a moment later.
     */
    private fun anyUsageEventJustNow(context: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        usageProbe?.let { (at, answer) -> if (now - at < USAGE_PROBE_TTL_MS) return answer }
        val answer = try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val wall = System.currentTimeMillis()
            !usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, wall - 60_000, wall).isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
        usageProbe = now to answer
        return answer
    }

    private const val USAGE_PROBE_TTL_MS = 5_000L

    fun isNotificationAccessGranted(context: Context): Boolean {
        val component = ComponentName(context, FocusNotificationService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabled?.contains(component.flattenToString()) == true
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Whether FocusLock's own notifications can appear at all. */
    fun canPostNotifications(context: Context): Boolean = try {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (_: Exception) {
        true
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }

    fun isDeviceAdminActive(context: Context): Boolean = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(ComponentName(context, AdminReceiver::class.java))
    } catch (_: Exception) {
        false
    }

    /**
     * Does this build hold, and use, Device-Owner powers?
     *
     * The demo build answers no unconditionally. Its manifest does not even
     * register [AdminReceiver], so it cannot be provisioned in the first place;
     * this flag is the second lock on that door, and it is what every apply
     * path in [KioskPolicy] and [PolicySync] asks before touching policy.
     *
     * Release paths deliberately do not consult this. They ask the framework
     * directly, so that a build flag can never leave a phone stranded with
     * policy applied and nothing willing to take it off.
     */
    fun isDeviceOwner(context: Context): Boolean {
        if (!BuildConfig.ENFORCEMENT) return false
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /** The accessibility service behind every content guard. */
    fun isContentGuardEnabled(context: Context): Boolean {
        val services = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        }
        val serviceId = ComponentName(context, ContentGuardService::class.java).flattenToString()
        return accessibilityListContains(services, serviceId)
    }

    /**
     * Whether [serviceId] is one of the entries in a raw
     * `enabled_accessibility_services` value.
     *
     * The enabled-service list is the accessibility toggle: it is what Settings
     * itself renders, so reading it is reading the same thing the person sees.
     * `ACCESSIBILITY_ENABLED` is a separate flag that the system maintains on
     * its own, and the two can legitimately disagree - across a reboot, for the
     * moment between the two writes when a service is switched on, or when an
     * OEM's own accessibility shortcut is involved. This used to require the
     * flag as well, which meant that while they disagreed the app reported "not
     * granted" to someone looking at an ON toggle in Settings - and since the
     * recovery screen only clears once the permission reads as restored, the
     * one thing it asked for was the one thing that made no difference. That is
     * the shape of a screen you cannot get out of.
     *
     * The two written forms are both accepted, because Android and various OEM
     * Settings screens have written `pkg/pkg.Class` and `pkg/.Class`
     * interchangeably. Pure string work on purpose, so it is testable off a
     * device.
     */
    fun accessibilityListContains(raw: String?, serviceId: String): Boolean {
        if (raw.isNullOrBlank()) return false
        val wanted = normalizeComponentId(serviceId) ?: return false
        // Plain Kotlin rather than TextUtils.split: this function is the one
        // piece of the guard with a unit test, and a unit test has no Android
        // framework behind it.
        return raw.split(":").any { normalizeComponentId(it) == wanted }
    }

    /**
     * `pkg/.Class` and `pkg/pkg.Class` both become `pkg/pkg.class`, lowercased.
     * Anything that is not a component id (blank, no slash) becomes null, so it
     * can never equal a real one.
     */
    private fun normalizeComponentId(id: String): String? {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return null
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.length - 1) return null
        val packageName = trimmed.substring(0, slash)
        val className = trimmed.substring(slash + 1)
        val full = if (className.startsWith(".")) packageName + className else className
        return packageName.lowercase(java.util.Locale.US) + "/" + full.lowercase(java.util.Locale.US)
    }

    fun hasLocationAccess(context: Context): Boolean = PlaceRules.hasLocationPermission(context)

    /**
     * Only the permissions the user's own capability choices actually require.
     * A capability the user left off never blocks anything.
     */
    fun missingForCurrentCapabilities(context: Context): List<CapabilitySpec> =
        Capabilities.all.filter { spec ->
            if (!CapabilityRegistry.isEnabled(context, spec.id)) return@filter false
            when {
                spec.needsUsageAccess && !hasUsageAccess(context) -> true
                spec.needsAccessibility && !isContentGuardEnabled(context) -> true
                spec.needsNotificationAccess && !isNotificationAccessGranted(context) -> true
                spec.needsDeviceOwner && !isDeviceOwner(context) -> true
                spec.needsLocation && !hasLocationAccess(context) -> true
                else -> false
            }
        }

    /**
     * Whether any capability the user has switched on actually relies on the
     * accessibility guard. Used to tell "they turned it off" apart from "they
     * never needed it".
     */
    fun needsContentGuard(context: Context): Boolean =
        Capabilities.all.any { spec ->
            spec.needsAccessibility && CapabilityRegistry.isEnabled(context, spec.id)
        }
}
