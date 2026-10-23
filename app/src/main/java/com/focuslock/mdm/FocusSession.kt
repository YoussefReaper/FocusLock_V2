package com.focuslock.mdm

import android.content.Context
import androidx.annotation.StringRes

/**
 * The four strengths of lock, weakest first.
 *
 * Every mode says out loud how hard it is to leave, because a person choosing a
 * 90-day kiosk session deserves to know that before they tap, not after.
 */
enum class FocusMode(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val oneLinerRes: Int,
    @StringRes val exitLineRes: Int,
    val strength: Int,
    val capabilityId: String
) {
    SOFT(
        id = "soft",
        labelRes = R.string.mode_soft_label,
        oneLinerRes = R.string.mode_soft_oneliner,
        exitLineRes = R.string.mode_soft_exitline,
        strength = 1,
        capabilityId = Capabilities.ADVISORY_MODE
    ),
    BLOCK(
        id = "block",
        labelRes = R.string.mode_block_label,
        oneLinerRes = R.string.mode_block_oneliner,
        exitLineRes = R.string.mode_block_exitline,
        strength = 2,
        capabilityId = Capabilities.APP_BLOCK
    ),
    SANCTUARY(
        id = "sanctuary",
        labelRes = R.string.mode_sanctuary_label,
        oneLinerRes = R.string.mode_sanctuary_oneliner,
        exitLineRes = R.string.mode_sanctuary_exitline,
        strength = 3,
        capabilityId = Capabilities.SANCTUARY_MODE
    ),
    KIOSK(
        id = "kiosk",
        labelRes = R.string.mode_kiosk_label,
        oneLinerRes = R.string.mode_kiosk_oneliner,
        exitLineRes = R.string.mode_kiosk_exitline,
        strength = 4,
        capabilityId = Capabilities.KIOSK_MODE
    );

    fun label(context: Context): String = context.getString(labelRes)
    fun oneLiner(context: Context): String = context.getString(oneLinerRes)
    fun exitLine(context: Context): String = context.getString(exitLineRes)

    val isHard: Boolean get() = this == KIOSK

    /**
     * The switches this mode turns on, as a starting point.
     *
     * A mode is a template, not a cage. Picking one writes these flags; every
     * one of them is then editable in You -> Advanced, including Kiosk's
     * "no early exit". The preset is re-applied only when you pick a
     * *different* mode, so an edit you make afterwards survives the next start
     * of the same mode.
     *
     * Deliberately narrow: these three flags are what the mode is *about*.
     * It does not touch which modes are available, app rules, schedules or
     * anything else the person configured, because a template that quietly
     * rewrote unrelated settings would be a trap.
     */
    fun preset(): Map<String, Boolean> = when (this) {
        SOFT -> mapOf(
            Capabilities.CAN_END_EARLY to true,
            Capabilities.HARD_BLOCK to false,
            Capabilities.SUSPEND_BLOCKED_APPS to false,
            Capabilities.HIDE_BLOCKED_APPS to false
        )
        BLOCK -> mapOf(
            Capabilities.CAN_END_EARLY to true,
            Capabilities.HARD_BLOCK to true,
            Capabilities.SUSPEND_BLOCKED_APPS to true,
            Capabilities.HIDE_BLOCKED_APPS to false
        )
        SANCTUARY -> mapOf(
            Capabilities.CAN_END_EARLY to true,
            Capabilities.HARD_BLOCK to true,
            Capabilities.SUSPEND_BLOCKED_APPS to true,
            Capabilities.HIDE_BLOCKED_APPS to true
        )
        KIOSK -> mapOf(
            Capabilities.CAN_END_EARLY to false,
            Capabilities.HARD_BLOCK to true,
            Capabilities.SUSPEND_BLOCKED_APPS to true,
            Capabilities.HIDE_BLOCKED_APPS to true
        )
    }

    /** The preset in words, for the line under each mode card. */
    fun presetSummary(context: Context): String {
        val parts = ArrayList<String>()
        parts.add(
            context.getString(
                if (preset()[Capabilities.HARD_BLOCK] == true) R.string.mode_preset_stops
                else R.string.mode_preset_nudges
            )
        )
        if (preset()[Capabilities.HIDE_BLOCKED_APPS] == true) {
            parts.add(context.getString(R.string.mode_preset_hides))
        }
        if (preset()[Capabilities.SUSPEND_BLOCKED_APPS] == true) {
            parts.add(context.getString(R.string.mode_preset_silences))
        }
        parts.add(
            context.getString(
                if (preset()[Capabilities.CAN_END_EARLY] == true) R.string.mode_preset_can_end_early
                else R.string.mode_preset_no_early_exit
            )
        )
        return context.getString(R.string.mode_preset_prefix) + parts.joinToString(" · ")
    }

    companion object {
        fun fromId(id: String?): FocusMode = values().firstOrNull { it.id == id } ?: SOFT

        /** Modes the user has left switched on, weakest first. */
        fun available(context: Context): List<FocusMode> =
            values().filter { CapabilityRegistry.isEnabled(context, it.capabilityId) }
    }
}

/**
 * The running session.
 *
 * This is the generalisation of the old kiosk timer: same storage keys, so an
 * in-flight 90-day lock survives the upgrade, plus a mode and a few honest
 * options that used to be implicit.
 */
object SessionManager {

    private const val KEY_MODE = "session_mode"
    private const val KEY_PRESET_APPLIED_FOR = "session_preset_applied_for"
    private const val KEY_RELEASE_OWNER_ON_END = "session_release_owner_on_end"
    private const val KEY_LAST_ENDED_AT = "session_last_ended_at"
    private const val KEY_TOTAL_SESSIONS = "session_total_count"
    private const val KEY_TOTAL_FOCUS_MS = "session_total_focus_ms"

    // ── State ─────────────────────────────────────────────────────

    fun isActive(context: Context): Boolean = LockManager.isKioskActive(context)

    /**
     * Whether FocusLock is allowed to hold anything back at this moment.
     *
     * This is the single gate every rule now sits behind, and it is the answer
     * to "schedules and bedtime fire when nothing is running". They used to be
     * clock-driven and entirely independent of a session, which meant the app
     * could take the phone away on a Tuesday afternoon the person never asked
     * it to. They are rules that apply *inside* a session now: you start one,
     * and while it runs your windows, bedtime, places and budgets add their
     * restrictions on top of it.
     *
     * A standalone Earn task counts, because it is its own lock in every way
     * that matters - "the phone is only this until the work is done" is a
     * session whether or not it went through the mode picker.
     *
     * [activeOverride] is [TestMode]'s hook: a test answers `true` here without
     * [isActive] itself ever reporting true, so a test can preview every rule
     * below this line while nothing physical (lock-task, suspend/hide, the
     * freeze) can engage for real.
     */
    fun isEnforcing(context: Context, activeOverride: Boolean? = null): Boolean {
        if (activeOverride != null) return activeOverride
        if (isActive(context)) return true
        return EarnSession.isActive(context)
    }

    /**
     * Whether any guardrail is actively enforcing right now — either a manual
     * Focus Session, an Earn Task, Test Mode, or an active Schedule, Bedtime,
     * Place/Wi-Fi rule, Daily Limit, Custom Rule, or Budgeted app.
     */
    fun isGuardActive(context: Context, activeOverride: Boolean? = null): Boolean {
        if (isEnforcing(context, activeOverride)) return true
        if (ScheduleManager.activeWindowIfEnabled(context) != null) return true
        if (Bedtime.isActive(context)) return true
        if (PlaceRules.activePlaces(context).isNotEmpty()) return true
        if (AppLimits.hasEnforceableBudgets(context)) return true
        if (CapabilityRegistry.isEnabled(context, Capabilities.RULE_ENGINE) &&
            RuleStore.all(context).any { it.enabled }
        ) {
            return true
        }
        if (EarnMode.isEnabled(context) &&
            (AppRules.packagesWithPolicy(context, AppPolicy.LIMIT).isNotEmpty() ||
                AppRules.categoryPolicies(context).containsValue(AppPolicy.LIMIT))
        ) {
            return true
        }
        return false
    }

    /**
     * Whether the user has configured any automatic rule (Schedules, Bedtime,
     * Daily Limits, Place/Wi-Fi Rules, Custom Rules, or Budgeted apps) that
     * needs [AppBlockerService] watching in the background.
     */
    fun hasAlwaysOnRules(context: Context): Boolean {
        if (CapabilityRegistry.isEnabled(context, Capabilities.SCHEDULES) &&
            ScheduleManager.getSchedules(context).isNotEmpty()
        ) {
            return true
        }
        if (Bedtime.isEnabled(context)) return true
        if (AppLimits.hasEnforceableBudgets(context)) return true
        if ((CapabilityRegistry.isEnabled(context, Capabilities.LOCATION_BLOCK) ||
                CapabilityRegistry.isEnabled(context, Capabilities.WIFI_CONDITIONS)) &&
            PlaceRules.all(context).any { it.enabled }
        ) {
            return true
        }
        if (CapabilityRegistry.isEnabled(context, Capabilities.RULE_ENGINE) &&
            RuleStore.all(context).any { it.enabled }
        ) {
            return true
        }
        if (EarnMode.isEnabled(context) &&
            (AppRules.packagesWithPolicy(context, AppPolicy.LIMIT).isNotEmpty() ||
                AppRules.categoryPolicies(context).containsValue(AppPolicy.LIMIT))
        ) {
            return true
        }
        return false
    }

    /**
     * The mode this session is running at.
     *
     * The fallback is [FocusMode.SOFT], not Kiosk. A missing or unreadable
     * `session_mode` key used to resolve to the strongest mode the app has -
     * the one that locks the phone to FocusLock until its timer runs out and
     * cannot be left early - which is the worst possible thing to guess. The
     * two defaults now agree: [FocusMode.fromId] falls back to Soft as well.
     */
    fun mode(context: Context): FocusMode =
        FocusMode.fromId(FocusStore.getString(context, KEY_MODE, FocusMode.SOFT.id))

    /** The mode of the most recent session, or null if there has never been one. */
    fun lastMode(context: Context): FocusMode? =
        if (FocusStore.has(context, KEY_MODE)) mode(context) else null

    fun startedAt(context: Context): Long =
        FocusStore.getLong(context, Constants.KEY_KIOSK_START_MS, 0L)

    fun durationMs(context: Context): Long = LockManager.getKioskDurationMs(context)

    fun remainingMs(context: Context): Long = LockManager.getKioskRemainingMs(context)

    fun elapsedMs(context: Context): Long {
        val duration = durationMs(context)
        if (duration <= 0L) return 0L
        return (duration - remainingMs(context)).coerceAtLeast(0L)
    }

    fun progressPercent(context: Context): Int {
        val duration = durationMs(context)
        if (duration <= 0L) return 0
        return ((elapsedMs(context).toFloat() / duration.toFloat()) * 100f).toInt().coerceIn(0, 100)
    }

    fun endsAt(context: Context): Long = startedAt(context) + durationMs(context)

    /**
     * Whether ending this session should also hand back Device-Owner rights.
     *
     * Off by default: sessions are meant to be run again next week. The original
     * one-shot 90-day sprint is still available by turning this on before start.
     */
    fun releasesOwnerOnEnd(context: Context): Boolean =
        FocusStore.getBool(context, KEY_RELEASE_OWNER_ON_END, false)

    fun setReleasesOwnerOnEnd(context: Context, value: Boolean) {
        FocusStore.setBool(context, KEY_RELEASE_OWNER_ON_END, value)
    }

    fun lastEndedAt(context: Context): Long = FocusStore.getLong(context, KEY_LAST_ENDED_AT, 0L)

    fun totalSessions(context: Context): Int = FocusStore.getInt(context, KEY_TOTAL_SESSIONS, 0)

    fun totalFocusMs(context: Context): Long = FocusStore.getLong(context, KEY_TOTAL_FOCUS_MS, 0L)

    // ── Transitions ───────────────────────────────────────────────

    /**
     * Loads a mode's template, unless it is already loaded.
     *
     * The guard is the whole point. If this ran on every start it would undo
     * the person's own edits every time they pressed Start, which would make
     * You -> Advanced feel broken. Picking a *different* mode is the deliberate
     * act that says "give me that template again".
     *
     * Returns true if anything actually changed, so the caller can say so.
     */
    fun applyPresetIfModeChanged(context: Context, mode: FocusMode): Boolean {
        if (FocusStore.getString(context, KEY_PRESET_APPLIED_FOR, "") == mode.id) return false
        // Always unfrozen in practice: this runs from start(), before the
        // session is marked active. Checking the result anyway rather than
        // assuming it, so a future caller can't silently skip the guard.
        if (!CapabilityRegistry.applyPreset(context, mode.preset())) return false
        FocusStore.setString(context, KEY_PRESET_APPLIED_FOR, mode.id)
        return true
    }

    /** Whether starting this mode would load a new template. Reads only. */
    fun presetPending(context: Context, mode: FocusMode): Boolean =
        FocusStore.getString(context, KEY_PRESET_APPLIED_FOR, "") != mode.id

    /** Records a mode as the loaded template without touching any flag. */
    fun markPresetApplied(context: Context, mode: FocusMode) {
        FocusStore.setString(context, KEY_PRESET_APPLIED_FOR, mode.id)
    }

    /** Puts a mode's template back after the person has edited it. */
    fun resetToPreset(context: Context, mode: FocusMode): Boolean {
        if (!CapabilityRegistry.applyPreset(context, mode.preset())) return false
        FocusStore.setString(context, KEY_PRESET_APPLIED_FOR, mode.id)
        return true
    }

    /** Whether the live flags still match the mode's template. */
    fun matchesPreset(context: Context, mode: FocusMode): Boolean =
        mode.preset().all { CapabilityRegistry.isEnabled(context, it.key) == it.value }

    /**
     * Starts a session. Returns false when something refused it, having said
     * why through [SessionLock.refusalMessage] or [DemoMode].
     *
     * The demo's two limits are enforced here rather than only in the UI. A
     * greyed-out tile is a hint; this is the rule, and it holds even if a
     * future screen forgets to ask.
     */
    fun start(context: Context, mode: FocusMode, durationMs: Long): Boolean {
        if (DemoMode.isModeLocked(mode)) return false
        if (!DemoMode.hasSessionLeft(context)) return false

        applyPresetIfModeChanged(context, mode)
        FocusStore.setString(context, KEY_MODE, mode.id)
        LockManager.startKiosk(
            context,
            capFirstNoExitSession(durationMs, canEndEarly(context), totalSessions(context))
        )
        // What counts as "revoked" is measured from the moment this session
        // starts. Without this, a baseline left behind by an earlier session
        // makes a permission that was already missing when this one began read
        // as a permission that was just taken away. See
        // PermissionGuard.resetBaselines.
        PermissionGuard.resetBaselines(context)
        // Recorded as the session begins, not as it ends, so the demo's one
        // session cannot be doubled up by starting a second while the first is
        // still running.
        DemoMode.recordSessionStarted(context)
        Streaks.recordActivity(context)
        PolicySync.request(context, "session:start")
        AppBlockerService.start(context)
        return true
    }

    /**
     * Whether the running session offers an early exit.
     *
     * This used to be `mode != KIOSK`, hardcoded. It is a switch now. Kiosk's
     * preset still turns it off — that is what choosing Kiosk means — but the
     * person can turn it back on in You -> Advanced, and the help screen says
     * so. A lock you cannot inspect or undo is not a tool, it is a trap, and
     * the honest version of "hard mode" is one you had to deliberately keep.
     */
    fun canEndEarly(context: Context): Boolean =
        CapabilityRegistry.isEnabled(context, Capabilities.CAN_END_EARLY)

    /** Longest a person's first session may run when it has no early exit. */
    const val FIRST_NO_EXIT_CAP_MS = 60L * 60L * 1000L

    /**
     * Someone who has never finished a session cannot know yet what a
     * no-exit lock feels like, so their first one is held to an hour. Picking
     * "Strict" or Kiosk and then "90 days" on day one would otherwise trap
     * them with no way out but a factory reset.
     */
    fun capFirstNoExitSession(durationMs: Long, canEndEarly: Boolean, sessionsFinished: Int): Long =
        if (!canEndEarly && sessionsFinished == 0) minOf(durationMs, FIRST_NO_EXIT_CAP_MS) else durationMs

    /** Whether [start] would shorten this session; the UI says so before it starts. */
    fun firstSessionCapApplies(context: Context, mode: FocusMode, durationMs: Long): Boolean {
        val endsEarlyAfterPreset = if (presetPending(context, mode)) {
            mode.preset()[Capabilities.CAN_END_EARLY] ?: canEndEarly(context)
        } else {
            canEndEarly(context)
        }
        return capFirstNoExitSession(durationMs, endsEarlyAfterPreset, totalSessions(context)) < durationMs
    }

    fun end(context: Context) {
        val elapsed = elapsedMs(context)
        FocusStore.setLong(context, KEY_LAST_ENDED_AT, System.currentTimeMillis())
        FocusStore.setInt(context, KEY_TOTAL_SESSIONS, totalSessions(context) + 1)
        FocusStore.setLong(context, KEY_TOTAL_FOCUS_MS, totalFocusMs(context) + elapsed)
        // The one place every "a session just ended" path passes through,
        // called directly by the UI's own End button as well as by
        // AppBlockerService on natural expiry - so this is where a permission
        // emergency has to be released too. The session it was protecting is
        // over; holding the lock past that point would be punishing someone
        // for something that would have ended on its own regardless.
        PermissionGuard.clearEmergency(context)
        LockManager.stopKiosk(context)
        TakeABreak.clearAll(context)

        // Release synchronously rather than trusting the debounced sync.
        //
        // PolicySync.request only *schedules* the un-suspend, 250ms later, on a
        // worker thread. If the process goes away in that window - and ending a
        // session is exactly when it does, because the foreground service that
        // was keeping the process alive stops moments later - the apps this
        // session suspended stay suspended at the OS level, reading "blocked by
        // your organization" with no session left to explain them. The request
        // below still runs for everything else policy-related; this makes the
        // one irreversible-looking part happen before we can be killed.
        PolicySync.releaseManagedAppsNow(context)
        PolicySync.request(context, "session:end")
    }

    fun extend(context: Context, extraMs: Long) {
        val current = durationMs(context)
        FocusStore.setLong(context, Constants.KEY_KIOSK_DURATION_MS, current + extraMs)
        PolicySync.request(context, "session:extend")
    }

    // ── What the mode implies ─────────────────────────────────────

    /**
     * Kiosk pins FocusLock as the shell, and so does a standalone Earn task:
     * "the phone is only this until the work is done" is the same primitive
     * either way. A merged Earn task rides on whatever mode is already running
     * and does not turn lock-task on by itself.
     *
     * An overlay schedule window pins it too - but only inside a session now.
     * It used to pin the phone with nothing running at all, which is the same
     * bug as schedules firing unprompted, in its most extreme form: a window
     * set up weeks earlier could hard-pin a phone on a random evening. See
     * [isEnforcing] and [ScheduleWindow.overlay].
     */
    fun shouldLockTask(context: Context): Boolean {
        if (isActive(context) &&
            mode(context) == FocusMode.KIOSK &&
            CapabilityRegistry.isEnabled(context, Capabilities.KIOSK_MODE)
        ) {
            return true
        }
        if (EarnSession.requiresLockTask(context)) return true
        return ScheduleManager.requiresLockTask(context) || Bedtime.requiresLockTask(context)
    }

    /**
     * Whether blocked apps come off the launcher.
     *
     * The mode no longer decides this; the flag does. Sanctuary and Kiosk set
     * it on by preset, but Block can have it too if that is what you want.
     */
    fun shouldHideApps(context: Context): Boolean =
        isActive(context) && CapabilityRegistry.isEnabled(context, Capabilities.HIDE_BLOCKED_APPS)

    fun shouldSuspendApps(context: Context): Boolean =
        isActive(context) && CapabilityRegistry.isEnabled(context, Capabilities.SUSPEND_BLOCKED_APPS)

    /**
     * Whether a blocked app is stopped or merely paused.
     *
     * Soft mode's preset turns this off, which is what makes Soft soft. It is
     * a flag rather than an enum check so any mode can be softened.
     *
     * [activeOverride], when non-null, replaces the [isActive] read - the one
     * hook [RuleEngine.decide] uses to answer "what would this decide if a
     * session were running" for [TestMode], without [isActive] itself ever
     * reporting true and without touching lock-task, suspend/hide, or the
     * rule freeze, which all still read [isActive] (or its device-owner
     * equivalents) directly and are entirely unaffected by a test.
     */
    fun blocksOutright(context: Context, activeOverride: Boolean? = null): Boolean =
        (activeOverride ?: isActive(context)) && CapabilityRegistry.isEnabled(context, Capabilities.HARD_BLOCK)

    /**
     * Kiosk's allowlist inversion: only the apps you named stay open.
     *
     * This is part of the lock-task primitive, which is the one place the enum
     * legitimately still decides something. Kept separate from
     * [shouldLockTask] on purpose: that one is also true for a standalone Earn
     * task, and an Earn task must not silently inherit Kiosk's allowlist model.
     *
     * See [blocksOutright] for what [activeOverride] is for.
     */
    fun usesAllowlistModel(context: Context, activeOverride: Boolean? = null): Boolean =
        (activeOverride ?: isActive(context)) &&
            mode(context) == FocusMode.KIOSK &&
            CapabilityRegistry.isEnabled(context, Capabilities.KIOSK_MODE)

    fun formatRemaining(context: Context): String = formatDuration(remainingMs(context))

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalMinutes = ms / 60_000L
        val days = totalMinutes / 1_440L
        val hours = (totalMinutes % 1_440L) / 60L
        val minutes = totalMinutes % 60L
        return when {
            days > 0L -> days.toString() + "d " + hours + "h"
            hours > 0L -> hours.toString() + "h " + minutes + "m"
            else -> minutes.toString() + "m"
        }
    }

    fun formatCountdown(ms: Long): String {
        if (ms <= 0L) return "0d 00h 00m 00s"
        val days = ms / 86_400_000L
        val hours = (ms % 86_400_000L) / 3_600_000L
        val minutes = (ms % 3_600_000L) / 60_000L
        val seconds = (ms % 60_000L) / 1_000L
        return String.format(
            java.util.Locale.getDefault(),
            "%dd %02dh %02dm %02ds",
            days, hours, minutes, seconds
        )
    }
}
