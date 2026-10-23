package com.focuslock.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings enforcement back after a reboot or an app update.
 *
 * It checks for anything worth watching, not just a running session: a
 * schedule window, bedtime, a daily budget or a place rule all deserve a guard,
 * and a reboot in the middle of any of them should not quietly end it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant) return

        Migration.run(context)

        // The service works out whether there is anything to watch and stops
        // itself within a tick if there is not, so this stays unconditional.
        AppBlockerService.start(context)
        PolicySync.request(context, "boot")
    }
}
