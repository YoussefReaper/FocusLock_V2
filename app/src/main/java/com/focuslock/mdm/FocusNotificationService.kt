package com.focuslock.mdm

import android.os.Build
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
        if (shouldSuppress(notification)) { dismiss(notification); return }
        if (SideDoors.isOn(this, SideDoors.Group.PICTURE_NOTIFICATIONS) && shouldFoldPicture(notification)) foldPicture(notification)
    }

    /**
     * A picture in a notification from an app that is otherwise allowed (a chat
     * app sending a photo) is a picture nobody checked. While a promise runs it is
     * folded into one plain line from Pinehold: who it was from, no picture.
     */
    private fun shouldFoldPicture(sbn: StatusBarNotification): Boolean {
        if (!BuildConfig.ENFORCEMENT) return false
        val packageName = sbn.packageName ?: return false
        if (packageName == applicationContext.packageName || SystemSurfaces.isCritical(packageName)) return false
        if (!SessionManager.isEnforcing(this)) return false
        val extras = sbn.notification?.extras ?: return false
        if (extras.containsKey(android.app.Notification.EXTRA_PICTURE)) return true
        if (Build.VERSION.SDK_INT >= 31 && extras.containsKey(android.app.Notification.EXTRA_PICTURE_ICON)) return true
        val messages = extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES) ?: return false
        return messages.any { (it as? android.os.Bundle)?.get("uri") != null }
    }

    private fun foldPicture(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras
        val who = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            ?: try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        dismiss(sbn)
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel("folded_pictures", getString(R.string.folded_picture_channel), android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = packageManager.getLaunchIntentForPackage(sbn.packageName)
            val pi = open?.let { android.app.PendingIntent.getActivity(this, sbn.key.hashCode(), it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT) }
            val n = androidx.core.app.NotificationCompat.Builder(this, "folded_pictures")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(getString(R.string.folded_picture_title, who))
                .setContentText(getString(R.string.folded_picture_body))
                .setAutoCancel(true)
                .apply { pi?.let { setContentIntent(it) } }
                .build()
            nm.notify(sbn.key.hashCode(), n)
        } catch (_: Exception) {
        }
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
