package com.focuslock.mdm

import androidx.annotation.StringRes

import android.content.Context

/**
 * Watches the handful of permissions the whole enforcement model depends on,
 * and reacts when one of them is taken away mid-session.
 *
 * This is not about a permission FocusLock was never given - a fresh install
 * with Usage Access still pending is a setup step, not tampering, and is left
 * entirely alone. It is specifically about a permission that WAS granted,
 * disappearing while a session is running and something still needs it. That
 * is the exact escape route this exists to close: "turn off Usage Access" or
 * "disable the accessibility service" is the first thing anyone searches for
 * when they want a blocker gone without touching one of its own switches.
 *
 * Detection is edge-triggered (granted -> missing), not level-triggered, so
 * this never fires on a permission the person genuinely hasn't set up yet -
 * only on one that just changed under a running session.
 */
object PermissionGuard {

    enum class Guarded(@StringRes val labelRes: Int, @StringRes val explanationRes: Int) {
        USAGE_ACCESS(
            R.string.guarded_usage_access_label,
            R.string.guarded_usage_access_explanation
        ),
        OVERLAY(
            R.string.guarded_overlay_label,
            R.string.guarded_overlay_explanation
        ),
        ACCESSIBILITY(
            R.string.guarded_accessibility_label,
            R.string.guarded_accessibility_explanation
        ),
        DEVICE_OWNER(
            R.string.guarded_device_owner_label,
            R.string.guarded_device_owner_explanation
        );

        fun label(context: Context): String = context.getString(labelRes)
        fun explanation(context: Context): String = context.getString(explanationRes)
    }

    private fun lastKnownKey(g: Guarded) = "permguard_last_" + g.name

    /**
     * When each guarded permission was first seen missing after being granted.
     *
     * A permission has to stay missing for [CONFIRM_MS] before it counts. The
     * reads behind [isGranted] can blink: the accessibility list is rewritten
     * as its service restarts (an app update does it), a usage-access probe can
     * come back empty on a phone that has only just woken. One unlucky read
     * used to be enough to put the recovery screen over the whole phone for a
     * permission that had never gone anywhere - and it then cleared itself a
     * second later, which looked exactly like the app glitching.
     */
    private val missingSince = HashMap<Guarded, Long>()
    private const val CONFIRM_MS = 3_000L

    /**
     * The confirmation while the phone, or this process, is still starting up.
     *
     * Found on a real phone (Xiaomi, Android 14): for several seconds after an
     * unlock that follows a reboot, the overlay permission reads as not
     * granted, then comes back by itself. With a session running across the
     * reboot, that was long enough to clear [CONFIRM_MS], so every reboot put
     * the recovery screen up and pinned the phone for a permission nobody had
     * touched. A permission that really was taken away - including one revoked
     * while the phone was off, which the persisted baseline still catches -
     * stays missing well past this; a startup blink does not.
     */
    private const val STARTUP_CONFIRM_MS = 20_000L
    private const val BOOT_SETTLE_MS = 3 * 60 * 1000L
    private const val PROCESS_SETTLE_MS = 60 * 1000L

    private fun confirmMs(now: Long): Long {
        val processAge = now - android.os.Process.getStartElapsedRealtime()
        val settling = now < BOOT_SETTLE_MS || processAge < PROCESS_SETTLE_MS
        return if (settling) STARTUP_CONFIRM_MS else CONFIRM_MS
    }
    private const val KEY_WAS_OWNER = "permguard_was_owner"
    private const val KEY_EMERGENCY = "permguard_emergency"

    /** Whether losing [g] right now would actually weaken something switched on. */
    private fun isRelevant(context: Context, g: Guarded): Boolean = when (g) {
        Guarded.USAGE_ACCESS, Guarded.OVERLAY -> true
        Guarded.ACCESSIBILITY -> listOf(
            Capabilities.CONTENT_GUARD,
            Capabilities.KEYWORD_BLOCK,
            Capabilities.WHATSAPP_GUARD,
            Capabilities.SHORTS_BLOCK,
            Capabilities.REELS_BLOCK,
            Capabilities.ADULT_BLOCK,
            Capabilities.TELEGRAM_GUARD
        ).any { CapabilityRegistry.isEnabled(context, it) }
        // Once granted, always worth watching - device owner isn't tied to a
        // capability switch, and losing it outside a factory reset is
        // significant on its own.
        Guarded.DEVICE_OWNER -> SetupChecks.isDeviceOwner(context) || FocusStore.getBool(context, KEY_WAS_OWNER, false)
    }

    private fun isGranted(context: Context, g: Guarded): Boolean = when (g) {
        Guarded.USAGE_ACCESS -> SetupChecks.hasUsageAccess(context)
        Guarded.OVERLAY -> SetupChecks.canDrawOverlays(context)
        Guarded.ACCESSIBILITY -> SetupChecks.isContentGuardEnabled(context)
        Guarded.DEVICE_OWNER -> SetupChecks.isDeviceOwner(context)
    }

    /**
     * Call every tick while a session is active. Returns whichever guarded
     * permissions were seen granted a moment ago and are missing now - a real
     * revocation, not just "never set up".
     */
    @Synchronized
    fun checkForRevocation(context: Context): Set<Guarded> {
        val revoked = LinkedHashSet<Guarded>()
        val now = android.os.SystemClock.elapsedRealtime()
        Guarded.values().forEach { g ->
            val key = lastKnownKey(g)
            if (!isRelevant(context, g)) {
                // Not relevant right now (e.g. the guard capability it backs is
                // off) - forget the baseline, so re-enabling later starts fresh
                // instead of firing on stale state.
                if (FocusStore.has(context, key)) FocusStore.remove(context, key)
                missingSince.remove(g)
                return@forEach
            }
            val granted = isGranted(context, g)
            if (g == Guarded.DEVICE_OWNER && granted && !FocusStore.getBool(context, KEY_WAS_OWNER, false)) {
                FocusStore.setBool(context, KEY_WAS_OWNER, true)
            }
            val known = FocusStore.getString(context, key, "")
            if (known == "granted" && !granted) {
                // Suspected, not yet confirmed: keep the baseline at "granted"
                // until it has stayed missing long enough to mean it.
                val since = missingSince.getOrPut(g) { now }
                if (now - since < confirmMs(now)) return@forEach
                revoked.add(g)
            }
            missingSince.remove(g)

            // Write only on an actual change.
            //
            // This runs four times a second for four permissions, and it used
            // to write unconditionally every time: sixteen SharedPreferences
            // commits a second, plus a revision bump each, to record the fact
            // that nothing had happened. On a phone that is meant to sit in a
            // session for hours that is pure battery, and it made every write
            // through FocusStore look like a policy change to anything watching.
            val next = if (granted) "granted" else "missing"
            if (known != next) FocusStore.setString(context, key, next)
        }
        return revoked
    }

    // ── Emergency state ──────────────────────────────────────────────
    //
    // Persisted rather than kept in memory: the emergency has to survive the
    // service being killed and restarted, which is exactly the kind of thing
    // that happens around the moment someone is fighting the lock.

    fun activeEmergency(context: Context): Set<Guarded> =
        FocusStore.getSet(context, KEY_EMERGENCY).mapNotNull { id ->
            Guarded.values().firstOrNull { it.name == id }
        }.toSet()

    fun isEmergency(context: Context): Boolean = activeEmergency(context).isNotEmpty()

    fun declareEmergency(context: Context, revoked: Set<Guarded>) {
        if (revoked.isEmpty()) return
        val current = activeEmergency(context)
        FocusStore.setSet(context, KEY_EMERGENCY, (current + revoked).map { it.name })
    }

    /**
     * Forgets every baseline, so the next comparison starts from what is true
     * now instead of from what was true during some earlier session.
     *
     * The baseline is what makes this edge-triggered, and it is only ever
     * *written* while a session runs - but it was never thrown away, so it
     * outlived the session that measured it. A phone where the accessibility
     * service was on in one month and off the next therefore told its owner, on
     * the first tick of a session in the following month, that a permission
     * they had never granted had "just" been taken away, and put the recovery
     * screen over everything for it. That is precisely the case this guard
     * documents itself as not being: it is for a permission that disappears
     * under a running session, and "it was already missing when the session
     * started" is a setup step.
     *
     * Called from [SessionManager.start].
     */
    @Synchronized
    fun resetBaselines(context: Context) {
        missingSince.clear()
        Guarded.values().forEach { g ->
            val key = lastKnownKey(g)
            if (FocusStore.has(context, key)) FocusStore.remove(context, key)
        }
    }

    /**
     * Ends the emergency outright, regardless of whether the permission was
     * ever restored. Only called when the session it was protecting is
     * itself over - naturally expiring or being ended is not a bypass of
     * this lock, it is the same ending that would have happened anyway.
     */
    fun clearEmergency(context: Context) {
        FocusStore.remove(context, KEY_EMERGENCY)
    }

    /** Call every tick: drops any guarded permission that has been granted again. */
    fun clearResolved(context: Context) {
        val current = activeEmergency(context)
        if (current.isEmpty()) return
        val stillMissing = current.filter { !isGranted(context, it) }.toSet()
        if (stillMissing.size != current.size) {
            FocusStore.setSet(context, KEY_EMERGENCY, stillMissing.map { it.name })
        }
    }
}
