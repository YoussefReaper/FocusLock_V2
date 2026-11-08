package com.focuslock.mdm

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The notification shield.
 *
 * A blocked app that still buzzes has not really been blocked: the pull is the
 * alert, not the icon. When the shield is on, notifications from apps the user
 * has blocked are dismissed as they arrive, and everything else is left alone.
 *
 * Always-allowed apps are never touched, deliberately and without exception, so
 * a missed call can never be the price of a focus session. Ongoing system
 * notifications are left alone too.
 */
class FocusNotificationService : NotificationListenerService() {

    @Volatile
    private var connected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        sweepExisting()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (!shouldSuppress(notification)) return
        dismiss(notification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Nothing to do: a notification going away is exactly what we want.
    }

    private fun shouldSuppress(sbn: StatusBarNotification): Boolean {
        // The demo never touches another app's notifications, same as it never
        // touches another app's screen. See the matching gate in
        // AppBlockerService.handleForeground.
        if (!BuildConfig.ENFORCEMENT) return false
        if (!CapabilityRegistry.isEnabled(this, Capabilities.NOTIFICATION_BLOCK)) return false

        val packageName = sbn.packageName ?: return false
        if (packageName == applicationContext.packageName) return false
        if (SystemSurfaces.isCritical(packageName)) return false
        if (AppRules.isAlwaysAllowed(this, packageName)) return false
        if (TakeABreak.hasActivePass(this, packageName)) return false

        if (!Standalone.mayActNow(this)) return false
        if (!SessionManager.isGuardActive(this)) return false

        return RuleEngine.decide(this, packageName).isBlocked
    }

    private fun dismiss(sbn: StatusBarNotification) {
        try {
            cancelNotification(sbn.key)
        } catch (_: Exception) {
            // The system can revoke listener access at any moment.
        }
    }

    /** Clears anything that arrived before the shield came up. */
    private fun sweepExisting() {
        if (!connected) return
        val active = try {
            activeNotifications
        } catch (_: Exception) {
            null
        } ?: return

        active.forEach { sbn ->
            if (shouldSuppress(sbn)) dismiss(sbn)
        }
    }
}
