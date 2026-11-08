package com.focuslock.mdm

/**
 * Which rules may act when no session is running.
 *
 * A session the person started blocks exactly what it always did. Outside
 * one, a rule may only act when its own "runs on its own" switch is on, and
 * every one of those switches starts off: nothing blocks on a phone where
 * nobody asked it to. Schedules and Bedtime are their own switch (see
 * [CapabilitySpec.runsOnItsOwn]); the rest are listed in [switches].
 */
object Standalone {

    /** The switches that let a feature act with no session, all default off. */
    val switches: List<String> = listOf(
        Capabilities.LIMITS_STANDALONE,
        Capabilities.BUDGET_STANDALONE,
        Capabilities.PLACES_STANDALONE,
        Capabilities.RULES_STANDALONE,
        Capabilities.CONTENT_STANDALONE,
        Capabilities.NOTIFICATION_STANDALONE
    )

    /** Inside a session a rule always acts; outside one only with its switch on. */
    fun mayAct(sessionRunning: Boolean, runsOnItsOwn: Boolean): Boolean =
        sessionRunning || runsOnItsOwn
}
