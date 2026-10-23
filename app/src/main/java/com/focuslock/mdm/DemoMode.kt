package com.focuslock.mdm

import android.app.Activity
import android.content.Context

/**
 * Commercial Free vs. Pro tier manager.
 *
 * Replaces the old compile-time 1-session-ever demo restriction with a single
 * commercial app that offers a generous Free tier and an in-app Pro unlock:
 *
 *  - **Free Tier (Forever):** Unlimited Gentle (Soft) and Block sessions,
 *    App Blocking, Always-Allowed Essentials, Daily App Limits, Schedules,
 *    Keyword Guard, and Safe Tools.
 *  - **FocusLock Pro:** Unlocks Sanctuary & Kiosk hard modes, automatic Bedtime
 *    dimming & sleep guard, Wi-Fi & GPS Place rules, Earn Mode time-banking,
 *    and Custom multi-condition rules — unlocked in-place without reinstalling
 *    or losing any configured rules.
 */
object DemoMode {

    private const val KEY_SESSIONS_USED = "demo_sessions_used"
    private const val KEY_PRO_UNLOCKED = "focuslock_pro_unlocked"

    /** Retained for compatibility with legacy callers. */
    const val SESSIONS_ALLOWED = Int.MAX_VALUE

    /** True only when running the legacy compile-time demo flavor without Pro unlocked. */
    val isDemo: Boolean get() = BuildConfig.DEMO

    /** Whether FocusLock Pro is unlocked on this device. */
    fun isPro(context: Context): Boolean =
        FocusStore.getBool(context, KEY_PRO_UNLOCKED, false)

    /** Unlocks or locks FocusLock Pro in-place without wiping user rules. */
    fun setPro(context: Context, unlocked: Boolean) {
        FocusStore.setBool(context, KEY_PRO_UNLOCKED, unlocked)
        PolicySync.request(context, "pro_tier_change")
    }

    /**
     * Modes that require FocusLock Pro (Sanctuary and Kiosk).
     */
    fun isModeLocked(context: Context, mode: FocusMode): Boolean =
        !isPro(context) && (mode == FocusMode.SANCTUARY || mode == FocusMode.KIOSK)

    /** Legacy overload for callers without a Context. */
    fun isModeLocked(mode: FocusMode): Boolean =
        isDemo && (mode == FocusMode.SANCTUARY || mode == FocusMode.KIOSK)

    fun sessionsUsed(context: Context): Int =
        FocusStore.getInt(context, KEY_SESSIONS_USED, 0).coerceAtLeast(0)

    /** Free tier now includes unlimited Gentle and Block sessions. */
    fun hasSessionLeft(context: Context): Boolean = true

    fun recordSessionStarted(context: Context) {
        FocusStore.setInt(context, KEY_SESSIONS_USED, sessionsUsed(context) + 1)
    }

    /**
     * Enforcement is always active for configured rules and sessions.
     */
    fun canShowBlockScreen(context: Context): Boolean = true

    /**
     * Checks if Pro is unlocked; if not, displays the FocusLock Pro upgrade
     * sheet so the user can unlock Pro in-place.
     */
    fun requirePro(
        activity: Activity,
        featureName: String? = null,
        onUnlocked: () -> Unit = {}
    ): Boolean {
        if (isPro(activity)) {
            onUnlocked()
            return true
        }
        showUpgradeDialog(activity, featureName, onUnlocked)
        return false
    }

    /**
     * Shows the FocusLock Pro upgrade modal.
     */
    fun showUpgradeDialog(
        activity: Activity,
        featureName: String? = null,
        onUnlocked: () -> Unit = {}
    ) {
        val subtitle = if (!featureName.isNullOrBlank()) {
            activity.getString(R.string.pro_dialog_feature_prompt, featureName) + "\n" +
                activity.getString(R.string.pro_dialog_subtitle)
        } else {
            activity.getString(R.string.pro_dialog_subtitle)
        }

        FocusDialog.custom(
            activity,
            title = activity.getString(R.string.pro_dialog_title),
            subtitle = subtitle,
            confirmLabel = activity.getString(R.string.pro_dialog_unlock_cta),
            cancelLabel = activity.getString(R.string.common_not_now),
            onConfirm = {
                setPro(activity, true)
                FocusDialog.toast(activity, activity.getString(R.string.pro_unlocked_toast))
                onUnlocked()
            }
        ) { body, dialogTokens, _ ->
            body.addView(FocusUi.body(activity, dialogTokens, activity.getString(R.string.pro_dialog_bullets)))
        }
    }
}

