package com.focuslock.mdm

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * The device admin a Sealed promise asks for. It declares no policies at all: it exists only so
 * Android refuses to uninstall Pinehold and leaves it off the "stop running apps" list. The
 * full [AdminReceiver] keeps the policies a Device Owner needs, and its request page lists
 * powers such as erasing the phone that a Sealed promise never uses.
 */
class SealedAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)

    companion object {
        fun component(context: Context) = android.content.ComponentName(context, SealedAdminReceiver::class.java)

        fun isActive(context: Context): Boolean = try {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
                .isAdminActive(component(context))
        } catch (_: Exception) {
            false
        }
    }
}
