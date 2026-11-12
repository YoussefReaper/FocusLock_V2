package com.focuslock.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The computer-side control surface.
 *
 * Every command here is something the user can already do in the app; this is
 * for setting a phone up from a desk, or recovering one whose screen is
 * awkward to use. It is guarded by the DUMP permission, which only ADB and
 * system apps hold, so a normal app cannot send these.
 *
 * Deliberately absent: any command that turns a capability on. Provisioning a
 * phone should not be able to enable a guard the person never chose.
 */
class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        Migration.run(context)

        val command = intent.getStringExtra(EXTRA_COMMAND)?.trim()?.lowercase().orEmpty()
        val value = intent.getStringExtra(EXTRA_VALUE)?.trim().orEmpty()
        val confirm = intent.getBooleanExtra(EXTRA_CONFIRM, false)

        when (command) {
            "allow_app" -> withValue(value) { setPolicy(context, value, AppPolicy.ALLOW) }
            "block_app" -> withValue(value) { setPolicy(context, value, AppPolicy.BLOCK) }
            "pause_app" -> withValue(value) { setPolicy(context, value, AppPolicy.FRICTION) }
            "clear_app" -> withValue(value) {
                result(context, AppRules.clearPolicy(context, value), "Cleared rule for " + value)
            }

            "always_allow" -> withValue(value) {
                result(context, AppRules.addAlwaysAllowed(context, value), "Always-allowed " + value)
            }

            "add_website" -> withValue(value) {
                result(context, AllowlistStore.addWebUrl(context, value), "Allowed site " + value)
            }
            "remove_website" -> withValue(value) {
                result(context, AllowlistStore.removeWebUrl(context, value), "Removed site " + value)
            }

            "add_keyword" -> withValue(value) {
                result(
                    context,
                    KeywordRules.add(context, KeywordRules.newRule(phrase = value)),
                    "Watching phrase " + value
                )
            }

            // Appearance only (QA screenshots): never loosens anything.
            "set_theme" -> withValue(value) {
                UiPrefs.setThemeId(context, value)
                result(context, true, "Theme " + value)
            }

            // Legacy names from the previous build, kept so old scripts still run.
            "add_app" -> withValue(value) { setPolicy(context, value, AppPolicy.ALLOW) }
            "remove_app" -> withValue(value) { setPolicy(context, value, AppPolicy.BLOCK) }

            "stop_session", "stop_kiosk" -> {
                if (!confirm) {
                    log("Refused: pass --ez " + EXTRA_CONFIRM + " true to end a session from a computer")
                } else {
                    endSession(context)
                }
            }

            "status" -> logStatus(context)
            "sync_policy" -> PolicySync.applyNow(context)
            "start_service" -> AppBlockerService.start(context)

            else -> log("Unknown command: " + command)
        }
    }

    private fun setPolicy(context: Context, packageName: String, policy: AppPolicy) {
        result(context, AppRules.setPolicy(context, packageName, policy), packageName + " -> " + policy.id)
    }

    /**
     * Logs a store write honestly.
     *
     * Every one of these calls returns a Boolean, and the old code threw it
     * away and logged the success line regardless. That made the whole recovery
     * surface a liar at exactly the moment it mattered: `allow_app` during a
     * running session is refused by the freeze, so the person watching the log
     * read "com.example.app -> allow", saw no change on the phone, and had no
     * way to tell a no-op from a bug. A refusal is now the loudest thing the
     * command prints, and it says what refused it.
     */
    private fun result(context: Context, applied: Boolean, success: String) {
        if (applied) {
            log(success)
            return
        }
        log(
            if (SessionLock.isFrozen(context)) {
                "Refused: a session is holding the rules. End it first (stop_session --ez confirm true)."
            } else {
                "Refused: FocusLock would not apply that. Nothing changed."
            }
        )
    }

    /**
     * The recovery hatch. It ends the session and releases every managed app,
     * but it cannot silently do so: without the confirm flag it refuses, and it
     * always leaves a log line saying it happened.
     */
    private fun endSession(context: Context) {
        SessionManager.end(context)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as? android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(context, AdminReceiver::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
            KioskPolicy.releaseAllManagedApps(context, dpm, admin)
            KioskPolicy.clearDeviceOwnerKioskPolicies(context, dpm, admin)
        }
        log("Session ended from a computer")
    }

    private fun logStatus(context: Context) {
        log("Session active: " + SessionManager.isActive(context))
        log("Mode: " + SessionManager.mode(context).label(context))
        log("Remaining: " + SessionManager.formatRemaining(context))
        log("Device owner: " + SetupChecks.isDeviceOwner(context))
        log("Blocked apps: " + AppRules.blockedPackages(context).size)
        log("Always allowed: " + AppRules.alwaysAllowedRaw(context).size)
        log("Keyword rules active: " + KeywordRules.activeRules(context).size)
        Capabilities.all.forEach { spec ->
            log("  " + spec.id + " = " + CapabilityRegistry.isEnabled(context, spec.id))
        }
    }

    private inline fun withValue(value: String, action: () -> Unit) {
        if (value.isBlank()) {
            log("Ignored: missing --es " + EXTRA_VALUE)
            return
        }
        action()
    }

    private fun log(message: String) {
        Log.i("FocusLockAdb", message)
    }

    companion object {
        const val ACTION = "com.focuslock.mdm.ADB_COMMAND"
        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_VALUE = "value"
        const val EXTRA_CONFIRM = "confirm"
    }
}
