package com.focuslock.mdm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Commercial Free vs. Pro tier manager.
 *
 *  - **Free Tier (Forever):** Every kind of app blocking, schedules, daily limits,
 *    keyword guard, always-allowed essentials, Gentle & Block modes, and Safe Tools.
 *  - **FocusLock Pro ($9.99 one-time):** Sanctuary & Kiosk (the unbreakable
 *    Device Owner modes), Earn Mode, Bedtime, Places, and Custom Rules.
 *
 * Pro is unlocked only with an offline-verified licence code (`FL2-XXXX-XXXX-XXXX-SSSS`)
 * issued by the payment server after checkout confirmation.
 */
object DemoMode {

    private const val KEY_SESSIONS_USED = "demo_sessions_used"
    private const val KEY_PRO_UNLOCKED = "focuslock_pro_unlocked"
    private const val KEY_LICENCE_CODE = "focuslock_pro_licence_code"

    const val PRO_PRICE_DISPLAY = "$9.99"
    const val BUY_PRO_URL = "https://focus-lock-c0xg.onrender.com/#buy"

    private const val LICENCE_PREFIX = "FL2"
    private const val PAYLOAD_LENGTH = 12
    private const val SIGNATURE_LENGTH = 4
    private const val SIGNING_CONTEXT = "FocusLock-V2-Pro-Licence-v1:com.focuslock.mdm"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Retained for compatibility with legacy callers. */
    const val SESSIONS_ALLOWED = Int.MAX_VALUE

    /** True only when running the legacy compile-time demo flavor without Pro unlocked. */
    val isDemo: Boolean get() = BuildConfig.DEMO

    /**
     * Whether FocusLock Pro is unlocked on this device.
     * Requires both the unlock flag and a valid offline-verified licence code.
     */
    fun isPro(context: Context): Boolean {
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
     * Pure, offline signature check for a FocusLock V2 licence code.
     *
     * Expected canonical shape: `FL2-XXXX-XXXX-XXXX-SSSS`
     * where `XXXX-XXXX-XXXX` is the 12-char receipt payload and `SSSS` is the
     * 4-char HMAC-SHA256 tag over `FL2:<payload>`.
     */
    fun verifyLicenceCode(rawCode: String?): Boolean {
        if (rawCode.isNullOrBlank()) return false
        val cleaned = rawCode.uppercase()
            .replace("-", "")
            .replace(" ", "")
            .trim()
        val expectedTotal = LICENCE_PREFIX.length + PAYLOAD_LENGTH + SIGNATURE_LENGTH
        if (cleaned.length != expectedTotal) return false
        if (!cleaned.startsWith(LICENCE_PREFIX)) return false

        val body = cleaned.substring(LICENCE_PREFIX.length)
        if (body.any { it !in ALPHABET }) return false

        val payload = body.substring(0, PAYLOAD_LENGTH)
        val providedSig = body.substring(PAYLOAD_LENGTH, PAYLOAD_LENGTH + SIGNATURE_LENGTH)
        val expectedSig = computeSignatureTag(payload)
        return constantTimeEquals(providedSig, expectedSig)
    }

    /** Computes the 4-character base32 signature tag for a 12-character uppercase payload. */
    fun computeSignatureTag(payload: String): String {
        val cleanPayload = payload.uppercase().replace("-", "").replace(" ", "").trim()
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(SIGNING_CONTEXT.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val digest = mac.doFinal(("FL2:$cleanPayload").toByteArray(StandardCharsets.UTF_8))
        val out = StringBuilder(SIGNATURE_LENGTH)
        for (i in 0 until SIGNATURE_LENGTH) {
            val idx = (digest[i].toInt() and 0xFF) % ALPHABET.length
            out.append(ALPHABET[idx])
        }
        return out.toString()
    }

    /** Formats a valid raw code as `FL2-XXXX-XXXX-XXXX-SSSS`. */
    fun normalizeAndFormat(rawCode: String): String? {
        val cleaned = rawCode.uppercase().replace("-", "").replace(" ", "").trim()
        val expectedTotal = LICENCE_PREFIX.length + PAYLOAD_LENGTH + SIGNATURE_LENGTH
        if (cleaned.length != expectedTotal || !cleaned.startsWith(LICENCE_PREFIX)) return null
        val p1 = cleaned.substring(3, 7)
        val p2 = cleaned.substring(7, 11)
        val p3 = cleaned.substring(11, 15)
        val sig = cleaned.substring(15, 19)
        return "FL2-$p1-$p2-$p3-$sig"
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
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
