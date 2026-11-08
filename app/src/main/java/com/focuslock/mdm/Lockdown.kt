package com.focuslock.mdm

import android.content.Context

/**
 * The hardest state the app has: FocusLock closed against itself.
 *
 * An overlay schedule window already pins the phone so nothing but FocusLock
 * and the named apps can come forward. But FocusLock is still FocusLock -
 * Library still opens the browser, Rules still scrolls, Tasks is still there -
 * so the "dead phone" it promises is really "a phone that is only this app",
 * which is not the same thing and is still very much a place to spend an hour.
 *
 * Lockdown closes that. While it holds, the shell shows one screen: what is
 * holding it, when it lifts, and the essentials. No tabs, no library, no rules.
 *
 * ## Why this cannot loop
 *
 * The obvious way to build this - have the enforcement loop overlay FocusLock
 * the way it overlays other apps - is exactly the fatal loop the request warned
 * about: the overlay covers FocusLock, FocusLock is what the overlay tells you
 * to go to, and the two fight forever. So nothing here overlays anything.
 * [AppBlockerService] still returns early the moment our own package is in
 * front, unchanged. This is FocusLock's own shell choosing to draw a different
 * screen, which is an ordinary UI decision with nothing to fight.
 *
 * ## Why it cannot trap anyone
 *
 * It ends on its own clock, it never outranks a permission emergency (that
 * screen has to stay reachable or a revoked permission would be unrecoverable),
 * the always-allowed apps stay launchable from it so a phone call is always
 * possible, and a factory reset is still a factory reset.
 */
object Lockdown {

    /** Set on a schedule window, and on bedtime, alongside their existing overlay flag. */
    const val PARAM_BRICK = "bricksFocusLock"

    /**
     * Whether the app should be showing nothing but the lockdown screen.
     *
     * Gated on [SessionManager.isEnforcing] like every other rule: a window set
     * up weeks ago must not brick the phone on an evening nobody started
     * anything.
     */
    fun isActive(context: Context): Boolean {
        // A permission emergency always wins. Its recovery screen is the only
        // way back from a revoked permission, so nothing may cover it.
        if (PermissionGuard.isEmergency(context)) return false
        return scheduleBrick(context) != null || bedtimeBricks(context)
    }

    /** The active overlay window that also bricks the app, if there is one. */
    private fun scheduleBrick(context: Context): ScheduleWindow? =
        ScheduleManager.activeWindowIfEnabled(context)
            ?.takeIf { it.overlay && it.bricksApp && Standalone.mayActNow(context) }

    private fun bedtimeBricks(context: Context): Boolean =
        Standalone.mayActNow(context) && Bedtime.isActive(context) && Bedtime.bricksApp(context)

    /** When it lifts, as minutes past midnight, or null when nothing is holding. */
    fun liftsAtMinutes(context: Context): Int? {
        scheduleBrick(context)?.let { return it.endMinutes }
        if (bedtimeBricks(context)) return Bedtime.endMinutes(context)
        return null
    }

    fun liftsAt(context: Context): String? =
        liftsAtMinutes(context)?.let { TimeText.ofDay(context, it) }

    /** The window's own message where it has one, so the person sees what they wrote. */
    fun message(context: Context): String? =
        scheduleBrick(context)?.message?.takeIf { it.isNotBlank() }
}
