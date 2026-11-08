package com.focuslock.mdm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Commercial Free vs. Pro tier manager.
 *
 *  - **Free Tier (Forever):** Every kind of app blocking, schedules, daily limits,
 *    keyword guard, always-allowed essentials, Gentle & Block modes, and Safe Tools.
 *  - **FocusLock Pro ($9.99 one-time):** Sanctuary & Kiosk (the two
 *    firmest strengths, held by Device Owner), Earn Mode, Bedtime, Places, and Custom Rules.
 *
 * Pro is unlocked only with a licence code signed by the payment server after
 * checkout (`FL2-…`), verified offline against the server's public key.
 */
object DemoMode {

    private const val KEY_SESSIONS_USED = "demo_sessions_used"
    private const val KEY_PRO_UNLOCKED = "focuslock_pro_unlocked"
    private const val KEY_LICENCE_CODE = "focuslock_pro_licence_code"

    const val PRO_PRICE_DISPLAY = "$9.99"
    const val BUY_PRO_URL = "https://focus-lock-c0xg.onrender.com/#get"

    /** Retained for compatibility with legacy callers. */
    const val SESSIONS_ALLOWED = Int.MAX_VALUE

    /** True only when running the legacy compile-time demo flavor without Pro unlocked. */
    val isDemo: Boolean get() = BuildConfig.DEMO

    /**
     * Whether FocusLock Pro is unlocked on this device.
     * Either a valid offline-verified licence code on this device, or mobile_pro
     * on the signed-in account (cached; see [Account]).
     */
    fun isPro(context: Context): Boolean {
        if (Account.hasMobilePro(context)) return true
        if (!FocusStore.getBool(context, KEY_PRO_UNLOCKED, false)) return false
        val code = FocusStore.getString(context, KEY_LICENCE_CODE, "")
        return verifyLicenceCode(code)
    }

    /** Returns the masked or formatted licence code currently active on this device, if any. */
    fun activeLicenceCode(context: Context): String? {
        val code = FocusStore.getString(context, KEY_LICENCE_CODE, "")
        return if (verifyLicenceCode(code)) normalizeAndFormat(code) else null
    }

    /**
     * Verifies and activates a Pro licence code in-place without wiping user rules.
     * Returns true only if [rawCode] carries a valid offline signature.
     */
    fun redeemLicenceCode(context: Context, rawCode: String): Boolean {
        if (!verifyLicenceCode(rawCode)) return false
        val formatted = normalizeAndFormat(rawCode) ?: return false
        FocusStore.setString(context, KEY_LICENCE_CODE, formatted)
        FocusStore.setBool(context, KEY_PRO_UNLOCKED, true)
        PolicySync.request(context, "pro_tier_change")
        return true
    }

    /**
     * Offline check of a Pro licence code. The signature is made by the
     * payment server's private key and checked here with its public key;
     * see [LicenceVerifier]. Nothing in this APK can produce a valid code.
     */
    fun verifyLicenceCode(rawCode: String?): Boolean = LicenceVerifier.verify(rawCode)

    /** Canonical `FL2-XXXXX-...` form of a well-formed code, or null. */
    fun normalizeAndFormat(rawCode: String): String? = LicenceVerifier.format(rawCode)

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

    /** Free tier includes unlimited Gentle and Block sessions. */
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
     * sheet so the user can buy a licence or enter their licence code.
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
     * Shows the FocusLock Pro upgrade modal with transparent pricing ($9.99),
     * a Buy button that opens checkout, and an "I have a code" button that
     * verifies the offline licence signature.
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
            confirmLabel = activity.getString(R.string.pro_enter_code_button),
            cancelLabel = activity.getString(R.string.common_not_now),
            onConfirm = {
                showEnterCodeDialog(activity, onUnlocked)
            }
        ) { body, dialogTokens, dismiss ->
            body.addView(FocusUi.body(activity, dialogTokens, activity.getString(R.string.pro_dialog_bullets)))
            body.addView(FocusUi.spacer(activity, 14))
            body.addView(
                FocusUi.primaryButton(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.pro_buy_button)
                ) {
                    dismiss()
                    openCheckout(activity)
                }
            )
        }
    }

    /**
     * Opens the checkout URL in the user's browser.
     */
    fun openCheckout(activity: Activity) {
        try {
            LockManager.allowSettingsUntil(activity, System.currentTimeMillis() + 5 * 60 * 1000)
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(BUY_PRO_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            FocusDialog.toast(activity, activity.getString(R.string.common_page_not_available))
        }
    }

    /**
     * A valid code the person just copied from the payment page, so the box is
     * already filled in. Anything else on the clipboard is ignored, never shown.
     */
    private fun codeOnClipboard(activity: Activity): String? = try {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (LicenceVerifier.verify(text)) LicenceVerifier.format(text) else null
    } catch (_: Exception) {
        null
    }

    /**
     * Prompts the user to enter their offline-verified licence code.
     */
    fun showEnterCodeDialog(
        activity: Activity,
        onUnlocked: () -> Unit = {}
    ) {
        FocusDialog.textInput(
            activity,
            title = activity.getString(R.string.pro_code_dialog_title),
            subtitle = activity.getString(R.string.pro_code_dialog_subtitle),
            hint = activity.getString(R.string.pro_code_dialog_hint),
            value = codeOnClipboard(activity).orEmpty(),
            // Codes are about 140 characters; let them wrap instead of
            // scrolling a single line out of sight.
            multiline = true,
            confirmLabel = activity.getString(R.string.pro_code_verify_button)
        ) { input ->
            if (redeemLicenceCode(activity, input)) {
                FocusDialog.toast(activity, activity.getString(R.string.pro_unlocked_toast))
                onUnlocked()
            } else {
                FocusDialog.toast(activity, activity.getString(R.string.pro_code_invalid_toast))
            }
        }
    }
}
