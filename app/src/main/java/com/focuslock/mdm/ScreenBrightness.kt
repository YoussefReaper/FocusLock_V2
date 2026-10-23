package com.focuslock.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Screen brightness, from inside FocusLock.
 *
 * ## Why this exists
 *
 * During a Kiosk or overlay session the quick settings shade is gone, and no
 * toggle in the app brings it back - which reads like a bug, and was reported
 * as one. It is not: Android's lock task mode has a feature flag for
 * notifications (`LOCK_TASK_FEATURE_NOTIFICATIONS`, which "Lock the status bar"
 * controls) but **none for quick settings**. The platform removes the quick
 * settings panel for the whole duration of a pinned task and offers no way to
 * ask for it back. "Lock the status bar" genuinely cannot re-enable it, and no
 * amount of turning that switch off ever will.
 *
 * Which leaves a real problem: brightness lives in quick settings, and a phone
 * you cannot dim at 1am is a phone you put down angry. So the control moves
 * into the app, where the session cannot take it away.
 *
 * ## How it writes
 *
 * A Device Owner may write a small allowlist of system settings directly via
 * [DevicePolicyManager.setSystemSetting], and `SCREEN_BRIGHTNESS` is on it.
 * That is the path used when FocusLock is Device Owner, which is exactly when
 * the shade is missing. Otherwise it falls back to `Settings.System`, which
 * needs WRITE_SETTINGS - and when that is not granted either, [canControl]
 * reports false so the UI can say so instead of showing a slider that does
 * nothing.
 */
object ScreenBrightness {

    /** Android stores brightness as 0-255; below this the screen is effectively off. */
    private const val MIN_RAW = 8
    private const val MAX_RAW = 255

    private const val TAG = "FocusLockBrightness"

    fun canControl(context: Context): Boolean =
        SetupChecks.isDeviceOwner(context) || Settings.System.canWrite(context)

    /** Current brightness as a percentage, or null if it cannot be read. */
    fun percent(context: Context): Int? = try {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        (raw * 100 / MAX_RAW).coerceIn(0, 100)
    } catch (_: Exception) {
        null
    }

    /**
     * Whether the phone is picking its own brightness.
     *
     * Setting a level while this is on does nothing that lasts - the sensor
     * overrides it within a second - so the UI has to be able to say so.
     */
    fun isAutomatic(context: Context): Boolean = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Exception) {
        false
    }

    fun setAutomatic(context: Context, automatic: Boolean): Boolean = write(
        context,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        if (automatic) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
    )

    fun setPercent(context: Context, percent: Int): Boolean {
        val raw = (percent.coerceIn(0, 100) * MAX_RAW / 100).coerceAtLeast(MIN_RAW)
        return write(context, Settings.System.SCREEN_BRIGHTNESS, raw)
    }

    private fun write(context: Context, key: String, value: Int): Boolean {
        if (SetupChecks.isDeviceOwner(context)) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm != null) {
                try {
                    dpm.setSystemSetting(
                        ComponentName(context, AdminReceiver::class.java),
                        key,
                        value.toString()
                    )
                    return true
                } catch (e: Exception) {
                    // Falls through to the ordinary route below rather than
                    // failing outright: some OEM builds refuse the Device Owner
                    // path for settings the AOSP allowlist permits.
                    Log.w(TAG, "Device Owner could not write $key", e)
                }
            }
        }
        return try {
            if (!Settings.System.canWrite(context)) return false
            Settings.System.putInt(context.contentResolver, key, value)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not write $key", e)
            false
        }
    }

    /** The Settings page that grants WRITE_SETTINGS, for the non-Device-Owner case. */
    fun permissionIntent(context: Context): android.content.Intent =
        android.content.Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            android.net.Uri.parse("package:" + context.packageName)
        )
}
