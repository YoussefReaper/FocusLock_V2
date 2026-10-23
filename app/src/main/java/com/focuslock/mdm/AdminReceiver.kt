package com.focuslock.mdm

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device admin just got activated – start the guardian service
        AppBlockerService.start(context)
    }

    /**
     * Called when someone tries to disable this admin from Settings.
     * The string returned is shown to the user as a warning.
     * Because we are Device Owner (not just admin), Settings will
     * refuse the deactivation entirely – this message is a fallback.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "⛔  FocusLock kiosk is active for ${LockManager.getDaysRemaining(context)} more days. " +
        "Device Owner removal happens when the timer ends."

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Nothing to clean up – if they got here they factory-reset the phone.
    }
}
