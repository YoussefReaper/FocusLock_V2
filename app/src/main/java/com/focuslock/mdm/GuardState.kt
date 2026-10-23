package com.focuslock.mdm

/**
 * What the content guard saw a moment ago.
 *
 * The accessibility service and the enforcement service are separate processes'
 * worth of state in one app, and they need to agree fast. The guard writes here
 * the instant it recognises a screen; the blocker reads it on its next tick and
 * treats a live restriction as "this app is not allowed right now" even though
 * the app itself is on the allow list.
 *
 * Entries expire on their own so a stale hit can never wedge an app shut.
 */
data class GuardHit(
    val packageName: String,
    val phrase: String,
    val action: GuardAction,
    val timestampMs: Long
)

object GuardState {

    private const val HIT_TTL_MS = 1_500L

    @Volatile
    private var lastHit: GuardHit? = null

    @Synchronized
    fun record(packageName: String, phrase: String, action: GuardAction) {
        lastHit = GuardHit(packageName, phrase, action, System.currentTimeMillis())
    }

    @Synchronized
    fun clear() {
        lastHit = null
    }

    @Synchronized
    fun active(): GuardHit? {
        val hit = lastHit ?: return null
        if (System.currentTimeMillis() - hit.timestampMs > HIT_TTL_MS) {
            lastHit = null
            return null
        }
        return hit
    }

    /** A live hit for this package, or null. */
    fun activeFor(packageName: String): GuardHit? {
        val hit = active() ?: return null
        return if (hit.packageName == packageName) hit else null
    }
}
