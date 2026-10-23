package com.focuslock.mdm

import android.content.Context

/**
 * Which way an edit moves the lock.
 *
 * The freeze was originally all-or-nothing: while a session ran, no rule store
 * accepted a write at all. That is the wrong shape, and it was the single most
 * complained-about thing about running a session. Adding an app to the blocklist
 * at 2pm on day three is not an escape - it is the person doing exactly what the
 * app is for. What has to stay shut is the other direction.
 *
 * So a write now declares which way it moves:
 *
 * - [TIGHTEN] - blocks more than before. Always allowed, even in Kiosk, even
 *   with the rules frozen. Blocking another app, adding a keyword, removing
 *   something from a whitelist, shortening a budget, adding a schedule window.
 * - [LOOSEN] - blocks less than before. Refused while frozen, which is the
 *   whole point of the freeze. Un-blocking an app, adding to always-allowed,
 *   adding a web allowlist entry, deleting a keyword rule, adding a content
 *   exception, lengthening a budget.
 *
 * The web allowlist has worked this way since long before the rest of the app
 * did ("removing is fine, adding waits"); this generalises that one editor's
 * rule to every store, which is what the user actually expected everywhere.
 */
enum class EditDirection {
    TIGHTEN,
    LOOSEN
}

/**
 * The freeze that makes a session mean something.
 *
 * A self-lock you can edit from inside is not a lock. The moment the urge
 * arrives is precisely the moment a person walks into settings and unblocks the
 * thing, and it feels perfectly reasonable while they are doing it. So while a
 * session runs, the rules stop *loosening*: app policies, allowlists, schedules,
 * keywords, limits and the capability switches can all be made stricter, and
 * none of them can be relaxed until it ends.
 *
 * Three deliberate holes, none of which is an escape route:
 *
 * - **Take a break** still works. It is a bounded, counted, already-agreed
 *   exception, and refusing it would push people into ending the whole session
 *   instead — the all-or-nothing relapse this app exists to avoid.
 * - **Ending the session** still works, when the person left themselves that
 *   door ([Capabilities.CAN_END_EARLY]). Freezing rules is not the same as
 *   trapping someone.
 * - **Migration and session cleanup** write through
 *   [CapabilityRegistry.writeEnabled], because neither is a person changing
 *   their mind mid-session.
 *
 * And the freeze itself is a capability. Someone who genuinely wants to steer
 * mid-session turns [Capabilities.LOCK_RULES_IN_SESSION] off *before* starting.
 * It cannot be turned off from inside a session, because a lock with the key
 * taped to it is just a sticker.
 */
object SessionLock {

    /** True when a running session is currently holding the rules still. */
    fun isFrozen(context: Context): Boolean = CapabilityRegistry.isFrozen(context)

    /**
     * Whether a write moving the lock [direction] may go through right now.
     *
     * [EditDirection.TIGHTEN] is always permitted. Only loosening waits.
     */
    fun allows(context: Context, direction: EditDirection): Boolean =
        direction == EditDirection.TIGHTEN || !isFrozen(context)

    /**
     * The one line shown when something is refused.
     *
     * Says what is happening and when it lifts, never scolds, and never implies
     * the person did something wrong by trying. A session's own freeze wins the
     * wording when both happen to be true at once; the schedule-only phrasing
     * only shows up when nothing else is running, so it never talks about a
     * "session" that is not actually the reason.
     */
    fun refusalMessage(context: Context): String {
        if (!SessionManager.isActive(context)) {
            ScheduleManager.activeWindowIfEnabled(context)?.takeIf { it.overlay }?.let { window ->
                return Copy.rulesFrozenBySchedule(context, ScheduleManager.formatTime(context, window.endMinutes))
            }
        }
        return Copy.rulesFrozen(context, SessionManager.formatRemaining(context))
    }

    /**
     * Guards a mutation.
     *
     * Returns true when the caller may proceed. When it returns false it has
     * already told the user why, so callers just return.
     */
    fun allow(
        context: Context,
        direction: EditDirection = EditDirection.LOOSEN,
        onRefused: (String) -> Unit
    ): Boolean {
        if (allows(context, direction)) return true
        onRefused(refusalMessage(context))
        return false
    }

    // ── Direction helpers ─────────────────────────────────────────
    //
    // Each store works out its own direction, but the shapes repeat often
    // enough to be worth naming once here.

    /** Growing a set of *restrictions* tightens; shrinking it loosens. */
    fun forRestrictionSet(before: Set<String>, after: Set<String>): EditDirection =
        if (after.containsAll(before)) EditDirection.TIGHTEN else EditDirection.LOOSEN

    /** Growing a set of *exemptions* (a whitelist) loosens; shrinking it tightens. */
    fun forExemptionSet(before: Set<String>, after: Set<String>): EditDirection =
        if (before.containsAll(after)) EditDirection.TIGHTEN else EditDirection.LOOSEN

    /**
     * A numeric budget: a smaller cap is stricter. `null` means "no cap at all",
     * which is the loosest possible value.
     */
    fun forBudget(before: Int?, after: Int?): EditDirection = when {
        after == null -> EditDirection.LOOSEN
        before == null -> EditDirection.TIGHTEN
        after <= before -> EditDirection.TIGHTEN
        else -> EditDirection.LOOSEN
    }
}
