package com.focuslock.mdm

import android.content.Context

/**
 * Which rules may act when no session is running.
 *
 * A session the person started blocks exactly what it always did. Outside
 * one, every blocker that can act on its own - Bedtime and its dimmer,
 * schedule windows, places, daily limits, the budget pool, custom rules, the
 * content guard and the notification shield - waits on one master switch,
 * [Capabilities.OUTSIDE_SESSION], which starts off: nothing blocks on a phone
 * where nobody asked it to.
 */
object Standalone {

    /** Inside a session a rule always acts; outside one only with the master switch on. */
    fun mayAct(sessionRunning: Boolean, outsideAllowed: Boolean): Boolean =
        sessionRunning || outsideAllowed

    fun outsideAllowed(c: Context): Boolean =
        CapabilityRegistry.isEnabled(c, Capabilities.OUTSIDE_SESSION)

    /** [mayAct] for callers that have no decision of their own to reuse. */
    fun mayActNow(c: Context, sessionActiveOverride: Boolean? = null): Boolean =
        mayAct(SessionManager.isEnforcing(c, sessionActiveOverride), outsideAllowed(c))
}
