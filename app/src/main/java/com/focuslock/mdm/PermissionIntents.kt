package com.focuslock.mdm

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Where each permission is actually switched on.
 *
 * Each list is most specific first. The generic pages - every app with usage
 * access, every accessibility service on the phone - are where the buttons
 * used to go, and on a phone with a few dozen apps that meant a long list to
 * search with no hint of which row was FocusLock, which reads as "the button
 * did nothing". The specific pages open on FocusLock's own switch; they are
 * not on every build, so each is followed by the page that always exists.
 */
object PermissionIntents {

    private const val KEY_A11Y_DISCLOSURE_ACCEPTED = "a11y_prominent_disclosure_accepted"

    fun usageAccess(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri(context)),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    )

    fun accessibility(context: Context): List<Intent> {
        val out = ArrayList<Intent>(2)
        // Public from Android 11. Settings honours it only for a service that
        // belongs to the caller, which this is.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            out.add(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(
                    Intent.EXTRA_COMPONENT_NAME,
                    ComponentName(context, ContentGuardService::class.java).flattenToString()
                )
            )
        }
        out.add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return out
    }

    /**
     * Shows a Google Play–compliant Prominent Disclosure dialog explaining
     * what data the AccessibilityService reads, why it is used, and that no
     * data ever leaves the device, before opening Android's Accessibility
     * Settings page.
     */
    fun openAccessibilityWithDisclosure(
        activity: Activity,
        grantWindowMs: Long = 3 * 60 * 1000L
    ) {
        FocusDialog.alert(
            activity,
            title = activity.getString(R.string.a11y_disclosure_title),
            message = activity.getString(R.string.a11y_disclosure_body),
            confirmLabel = activity.getString(R.string.a11y_disclosure_agree),
            cancelLabel = activity.getString(R.string.a11y_disclosure_decline),
            onConfirm = {
                FocusStore.setBool(activity, KEY_A11Y_DISCLOSURE_ACCEPTED, true)
                LockManager.allowSettingsUntil(activity, System.currentTimeMillis() + grantWindowMs)
                if (!launchFirstDirect(activity, accessibility(activity))) {
                    FocusDialog.toast(activity, activity.getString(R.string.common_page_not_available))
                }
            }
        )
    }

    fun notificationAccess(context: Context): List<Intent> {
        val out = ArrayList<Intent>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            out.add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, FocusNotificationService::class.java).flattenToString()
                )
            )
        }
        out.add(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        return out
    }

    fun overlay(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    )

    fun notifications(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
    )

    fun appInfo(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
    )

    /**
     * Whether a sideloaded app's accessibility and notification switches may
     * be greyed out as a "restricted setting" (Android 13 and later), which
     * only App info -> ⋮ -> "Allow restricted settings" lifts.
     */
    fun mayBeRestricted(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Starts the first intent that resolves. Returns false when none did. */
    fun launchFirst(context: Context, intents: List<Intent>): Boolean {
        val isAccessibilityTarget = intents.any {
            it.action == Settings.ACTION_ACCESSIBILITY_SETTINGS ||
                it.action == "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        }
        if (isAccessibilityTarget && context is Activity && !SetupChecks.isContentGuardEnabled(context)) {
            openAccessibilityWithDisclosure(context)
            return true
        }
        return launchFirstDirect(context, intents)
    }

    private fun launchFirstDirect(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // Not on this build; try the next, less specific page.
            }
        }
        return false
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:" + context.packageName)
}

