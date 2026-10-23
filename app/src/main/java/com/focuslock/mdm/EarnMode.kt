package com.focuslock.mdm

import android.content.Context
import org.json.JSONObject

/**
 * Earn Mode: finish real work, unlock the rest of the phone.
 *
 * ## Why this shape
 *
 * The category is real and it works — StepPhone locks apps until you walk,
 * Achieve! sells "Earn Your Screen Time", Habit Doom keeps homework apps shut
 * behind on-device photo proof, Focus Lock Pomodoro hands back "the scroll time
 * you earned". What none of them have is Device Owner, so all of them can be
 * force-stopped, rebooted around, or uninstalled. FocusLock's version is the
 * only one where the lock is an OS policy.
 *
 * ## Why it is built carefully rather than enthusiastically
 *
 * Deci, Koestner and Ryan (1999, Psychological Bulletin, 128 experiments) found
 * tangible rewards undermine intrinsic motivation, and the breakdown matters
 * more than the headline figure:
 *
 *   - engagement-contingent (paid for merely doing it)   d = -0.40
 *   - completion-contingent (paid for finishing)         d = -0.36
 *   - performance-contingent (paid for how it went)      d = -0.28
 *
 * and the damage lands hardest on tasks the person already finds interesting.
 * Three design consequences, all of them load-bearing:
 *
 *  1. The default rate is **performance-contingent**: the reward scales with
 *     verified minutes actually spent, not with ticking a box. Least harmful of
 *     the three, and the only one that cannot be farmed by making tiny tasks.
 *  2. A task can be marked *enjoyable*, and then it pays nothing. Attaching an
 *     allowance to something you already like is the exact case the research
 *     says to avoid.
 *  3. Every number here is the user's. Self-Determination Theory is clear that
 *     an autonomy-supportive reward behaves differently from a controlling one,
 *     and in a self-lock app there is nobody else to set the terms anyway.
 *
 * Earn Mode is off by default and is never proposed as the primary way to use
 * FocusLock. Soft, Block and Sanctuary remain the defaults.
 */
object EarnMode {

    const val PARAM_PHOTO_PROOF = "photoProof"
    const val PARAM_INTERSECT = "intersectWithAllowlist"
    const val PARAM_SHOW_BUDGET = "showBudgetWhileActive"
    const val PARAM_CREDIBILITY = "credibilityMeter"
    const val PARAM_DECAY = "rewardDecay"
    const val PARAM_RATE = "ratePercent"
    const val PARAM_DAILY_CAP = "dailyCapMinutes"

    /** 25 minutes of leisure per 100 minutes of verified focus. The user's to change. */
    const val DEFAULT_RATE_PERCENT = 25
    const val DEFAULT_DAILY_CAP = 120

    fun isEnabled(context: Context): Boolean =
        CapabilityRegistry.isEnabled(context, Capabilities.EARN_MODE)

    fun photoProofEnabled(context: Context): Boolean =
        isEnabled(context) &&
            CapabilityRegistry.getBoolParam(context, Capabilities.EARN_MODE, PARAM_PHOTO_PROOF, true)

    /**
     * The loophole rule.
     *
     * When on, a task's allowed apps must also be in the user's standing
     * allowlist, so a task cannot smuggle a distracting app past the mode that
     * is already running. On by default, because off is the loophole.
     */
    fun intersectsWithAllowlist(context: Context): Boolean =
        CapabilityRegistry.getBoolParam(context, Capabilities.EARN_MODE, PARAM_INTERSECT, true)

    fun showsBudgetWhileActive(context: Context): Boolean =
        CapabilityRegistry.getBoolParam(context, Capabilities.EARN_MODE, PARAM_SHOW_BUDGET, true)

    fun showsCredibility(context: Context): Boolean =
        CapabilityRegistry.getBoolParam(context, Capabilities.EARN_MODE, PARAM_CREDIBILITY, true)

    /** Unspent minutes fading overnight. Off by default: hoarding is not a problem worth solving. */
    fun decaysUnspent(context: Context): Boolean =
        CapabilityRegistry.getBoolParam(context, Capabilities.EARN_MODE, PARAM_DECAY, false)

    fun ratePercent(context: Context): Int =
        CapabilityRegistry.getIntParam(context, Capabilities.EARN_MODE, PARAM_RATE, DEFAULT_RATE_PERCENT)
            .coerceIn(0, 200)

    fun setRatePercent(context: Context, value: Int) {
        CapabilityRegistry.setIntParam(context, Capabilities.EARN_MODE, PARAM_RATE, value.coerceIn(0, 200))
    }

    fun dailyCapMinutes(context: Context): Int =
        CapabilityRegistry.getIntParam(context, Capabilities.EARN_MODE, PARAM_DAILY_CAP, DEFAULT_DAILY_CAP)
            .coerceIn(0, 720)

    fun setDailyCapMinutes(context: Context, value: Int) {
        CapabilityRegistry.setIntParam(
            context,
            Capabilities.EARN_MODE,
            PARAM_DAILY_CAP,
            value.coerceIn(0, 720)
        )
    }

    /**
     * Device Owner buys the hard version. Without it Earn still runs, but as
     * friction rather than an OS-level lock, and the UI says so rather than
     * quietly pretending.
     */
    fun hasHardEnforcement(context: Context): Boolean = SetupChecks.isDeviceOwner(context)

    /** What a finished task is worth, before the daily cap. */
    fun rewardFor(context: Context, task: FocusTask, focusedMinutes: Int): Int {
        if (task.enjoyable) return 0
        task.rewardMin?.let { return it }
        val rate = ratePercent(context)
        if (rate <= 0) return 0
        return (focusedMinutes * rate) / 100
    }

    /**
     * What a finished Earn session is worth, computed from the deal frozen at
     * [EarnSession.start] rather than from the task as it stands now.
     *
     * This is the one that pays out. [rewardFor] remains for previewing what a
     * task *would* pay before it is started, which is the only honest use for
     * reading a live task's numbers.
     */
    fun rewardForDeal(context: Context, deal: EarnSession.EarnDeal, focusedMinutes: Int): Int {
        if (deal.enjoyable) return 0
        deal.rewardMin?.let { return it }
        val rate = ratePercent(context)
        if (rate <= 0) return 0
        return (focusedMinutes * rate) / 100
    }

    /**
     * What [minutes] of tracked focus is worth.
     *
     * Always rate-based. An ambient task has no session, so a per-session
     * reward has nothing to attach to - the deal has to be "this much time in
     * these apps is worth this much back", which is the rate.
     */
    fun rewardForTrackedMinutes(context: Context, minutes: Int): Int {
        val rate = ratePercent(context)
        if (rate <= 0 || minutes <= 0) return 0
        return (minutes * rate) / 100
    }
    fun describeDeal(context: Context): String {
        val rate = ratePercent(context)
        val cap = dailyCapMinutes(context)
        val base = if (rate <= 0) {
            context.getString(R.string.earn_deal_fixed)
        } else {
            context.getString(R.string.earn_deal_rate, rate * 60 / 100)
        }
        return if (cap <= 0) base else base + " " + context.getString(R.string.earn_deal_cap, cap)
    }
}

/**
 * The running Earn session: one task, one clock.
 *
 * Standalone means Earn is the lock. Merged means another mode is already
 * running and Earn narrows it further — the distinction changes which allowlist
 * the task intersects with, and nothing else.
 */
object EarnSession {

    private const val KEY_TASK_ID = "earn_task_id"
    private const val KEY_STARTED_AT = "earn_started_at"
    private const val KEY_STANDALONE = "earn_standalone"
    private const val KEY_PHOTO_ATTEMPTS = "earn_photo_attempts"

    // The payout-bearing fields, frozen at the moment the session starts.
    private const val KEY_SNAP_REWARD = "earn_snap_reward_min"
    private const val KEY_SNAP_VERIFICATION = "earn_snap_verification"
    private const val KEY_SNAP_ENJOYABLE = "earn_snap_enjoyable"
    private const val KEY_SNAP_TITLE = "earn_snap_title"

    /**
     * The deal as it stood when this Earn session began.
     *
     * Reward-bearing fields are snapshotted at [start] and never re-read from
     * the task afterwards. Editing a task is a normal thing to do; editing the
     * *running* task so that it pays out differently is not, and this is what
     * makes that harmless rather than merely discouraged - the payout was fixed
     * before any work happened, so there is nothing left to move.
     *
     * Found as a real exploit: open the running task, switch its verification to
     * "I say so", set a 120-minute reward, tap done once. 120 minutes in the
     * pool. Freezing the editor would have closed that door; freezing the deal
     * removes the doorway.
     */
    data class EarnDeal(
        val rewardMin: Int?,
        val verification: Verification,
        val enjoyable: Boolean,
        val title: String
    )

    fun deal(context: Context): EarnDeal = EarnDeal(
        rewardMin = FocusStore.getInt(context, KEY_SNAP_REWARD, -1).takeIf { it >= 0 },
        verification = Verification.fromId(
            FocusStore.getString(context, KEY_SNAP_VERIFICATION, Verification.MANUAL.id)
        ),
        enjoyable = FocusStore.getBool(context, KEY_SNAP_ENJOYABLE, false),
        title = FocusStore.getString(context, KEY_SNAP_TITLE, "")
    )

    fun activeTaskId(context: Context): String? =
        FocusStore.getString(context, KEY_TASK_ID, "").takeIf { it.isNotBlank() }

    fun activeTask(context: Context): FocusTask? {
        if (!EarnMode.isEnabled(context)) return null
        val id = activeTaskId(context) ?: return null
        val task = FocusTaskStore.find(context, id)
        if (task == null || task.completed) {
            stop(context)
            return null
        }
        return task
    }

    fun isActive(context: Context): Boolean = activeTask(context) != null

    fun isStandalone(context: Context): Boolean = FocusStore.getBool(context, KEY_STANDALONE, true)

    fun startedAt(context: Context): Long = FocusStore.getLong(context, KEY_STARTED_AT, 0L)

    fun elapsedMs(context: Context): Long {
        val started = startedAt(context)
        if (started <= 0L) return 0L
        return (System.currentTimeMillis() - started).coerceAtLeast(0L)
    }

    fun elapsedMinutes(context: Context): Int = (elapsedMs(context) / 60_000L).toInt()

    /** For TIMER verification: the goal, and how far along it is. */
    fun timerTargetMinutes(context: Context, task: FocusTask): Int =
        (task.timeEstimateMin ?: 25).coerceAtLeast(1)

    /**
     * Whether a TIMER task has had its time.
     *
     * Read from the ledger, not from the clock. The old version compared
     * wall-clock elapsed time, so a task that said "spend 25 minutes studying"
     * was satisfied by starting it and putting the phone down - and it kept
     * counting while the screen was off. This asks how long the task's own apps
     * were actually in front with the screen on, which is the thing the task
     * claimed to be measuring.
     */
    fun timerSatisfied(context: Context, task: FocusTask): Boolean =
        FocusLedger.trackedMs(context, task.id) >= timerTargetMinutes(context, task) * 60_000L

    /** How long a tracked task's apps have actually been in front. */
    fun trackedMinutes(context: Context, task: FocusTask): Int =
        (FocusLedger.trackedMs(context, task.id) / 60_000L).toInt()

    fun start(context: Context, task: FocusTask, standalone: Boolean) {
        FocusStore.setString(context, KEY_TASK_ID, task.id)
        FocusStore.setLong(context, KEY_STARTED_AT, System.currentTimeMillis())
        FocusStore.setBool(context, KEY_STANDALONE, standalone)
        FocusStore.setInt(context, KEY_PHOTO_ATTEMPTS, 0)
        // Freeze the deal: what this task pays, and how it has to prove itself,
        // are decided now and not re-read from the editable task later.
        FocusStore.setInt(context, KEY_SNAP_REWARD, task.rewardMin ?: -1)
        FocusStore.setString(context, KEY_SNAP_VERIFICATION, task.verification.id)
        FocusStore.setBool(context, KEY_SNAP_ENJOYABLE, task.enjoyable)
        FocusStore.setString(context, KEY_SNAP_TITLE, task.title)
        PolicySync.request(context, "earn:start")
        AppBlockerService.start(context)
    }

    fun stop(context: Context) {
        FocusStore.setString(context, KEY_TASK_ID, "")
        FocusStore.setLong(context, KEY_STARTED_AT, 0L)
        FocusStore.setInt(context, KEY_PHOTO_ATTEMPTS, 0)
        PolicySync.request(context, "earn:stop")
    }

    /**
     * A standalone Earn task is its own kiosk. A merged one is not: it narrows
     * whatever mode is already running rather than starting a new lock.
     *
     * Without Device Owner there is no lock-task to enter, so Earn falls back to
     * the soft variant — the enforcement loop and Intercept still hold the line,
     * just at friction strength rather than OS strength.
     */
    fun requiresLockTask(context: Context): Boolean =
        isActive(context) && isStandalone(context) && EarnMode.hasHardEnforcement(context)

    fun photoAttempts(context: Context): Int = FocusStore.getInt(context, KEY_PHOTO_ATTEMPTS, 0)

    fun recordPhotoAttempt(context: Context) {
        FocusStore.setInt(context, KEY_PHOTO_ATTEMPTS, photoAttempts(context) + 1)
    }

    /**
     * The apps that may run while this task is active.
     *
     * Standalone: FocusLock plus whatever the task names. An empty task list
     * means FocusLock alone — the pure "do the thing" state.
     *
     * Merged: the intersection with the user's standing allowlist, so a task
     * cannot widen a mode that is already running.
     *
     * Always-allowed apps are unioned back in either way. That is a deliberate
     * departure from a strict intersection: a task must never be able to lock
     * someone out of their own phone calls, and an escape route that requires
     * dialling 999 to use is not an escape route.
     */
    fun allowedPackages(
        context: Context,
        task: FocusTask,
        standalone: Boolean = isStandalone(context)
    ): Set<String> {
        val out = LinkedHashSet<String>()
        out.add(context.packageName)
        out.addAll(AppRules.alwaysAllowed(context))

        val requested = task.allowedApps
        if (requested.isEmpty()) {
            // Nothing named: standalone means FocusLock only, merged leaves the
            // underlying mode's allowlist untouched (handled by the caller).
            return out
        }

        if (standalone || !EarnMode.intersectsWithAllowlist(context)) {
            out.addAll(requested)
        } else {
            val global = AppRules.kioskAllowlist(context)
            out.addAll(requested.filter { it in global })
        }

        // Photo proof needs a camera, and a camera the lock task forbids is a
        // task that cannot be finished.
        if (task.verification == Verification.PHOTO && EarnMode.photoProofEnabled(context)) {
            PhotoProof.cameraPackage(context)?.let { out.add(it) }
        }
        return out
    }

    /**
     * Apps the task asked for that the standing allowlist refuses. Shown, never
     * hidden: a task that quietly loses half its apps is a task that looks
     * broken rather than one that looks governed.
     *
     * [standalone] defaults to the running session's flag, but callers
     * previewing a task before it starts pass what the session *would* be.
     */
    fun rejectedPackages(
        context: Context,
        task: FocusTask,
        standalone: Boolean = isStandalone(context)
    ): Set<String> {
        if (standalone || !EarnMode.intersectsWithAllowlist(context)) return emptySet()
        val global = AppRules.kioskAllowlist(context)
        return task.allowedApps.filterNot { it in global }.toSet()
    }
}

/**
 * The earned pool.
 *
 * One balance of minutes earned by finishing verified work, spent by the apps
 * the user labelled **Budgeted** ([AppPolicy.LIMIT]) and drained in real time -
 * second by second - for as long as one of those apps is actually in front.
 *
 * This replaced a model where the balance sat untouched until the person tapped
 * "start 15 minutes", which bought a countdown window that lifted app rules
 * wholesale. That shape had two problems this one does not: the balance was
 * invisible until deliberately spent, so it never felt like something you
 * owned, and spending it opened *everything*, so the reward did not point at
 * anything in particular. A pool that a named set of apps draws down as you use
 * them is what "budgeted" already means to people.
 *
 * Held in milliseconds rather than whole minutes because draining happens on
 * the enforcement loop's own 250ms tick. Rounding a charge to whole minutes on
 * every tick rounds every one of them to zero, so a minute-based pool would
 * simply never move.
 */
object EarnPool {

    private const val KEY_BALANCE_MS = "earn_pool_balance_ms"
    private const val KEY_TOTAL_EARNED_MS = "earn_pool_total_earned_ms"
    private const val KEY_TOTAL_SPENT_MS = "earn_pool_total_spent_ms"
    private const val KEY_EARNED_TODAY = "earn_earned_today_json"
    private const val KEY_LAST_DECAY_DAY = "earn_last_decay_day"

    private const val MINUTE_MS = 60_000L

    /** Pre-pool keys. Read once by [Migration], never again. */
    const val LEGACY_KEY_BALANCE_MINUTES = "earn_balance_minutes"
    const val LEGACY_KEY_TOTAL_EARNED_MINUTES = "earn_total_earned_minutes"
    const val LEGACY_KEY_TOTAL_SPENT_MINUTES = "earn_total_spent_minutes"
    private const val LEGACY_KEY_SPEND_UNTIL = "earn_spend_until_ms"

    fun balanceMs(context: Context): Long {
        applyDecayIfDue(context)
        return FocusStore.getLong(context, KEY_BALANCE_MS, 0L).coerceAtLeast(0L)
    }

    /**
     * One-time carry-over from the pre-pool balance, which was held as whole
     * minutes under a different key.
     *
     * The balance is preserved exactly rather than reset. Losing banked time to
     * a new build is the single fastest way to make someone stop trusting the
     * number, and this is the one migration in the app where the user can see
     * the before and after on a screen.
     *
     * The old countdown key is cleared on the way through: a leftover
     * "spend until" timestamp from the previous model would otherwise sit there
     * meaning nothing, waiting to be misread by a future change.
     */
    fun migrateLegacyBalance(context: Context) {
        if (FocusStore.getLong(context, KEY_BALANCE_MS, -1L) >= 0L) return
        FocusStore.setLong(
            context,
            KEY_BALANCE_MS,
            FocusStore.getInt(context, LEGACY_KEY_BALANCE_MINUTES, 0).coerceAtLeast(0) * MINUTE_MS
        )
        FocusStore.setLong(
            context,
            KEY_TOTAL_EARNED_MS,
            FocusStore.getInt(context, LEGACY_KEY_TOTAL_EARNED_MINUTES, 0).coerceAtLeast(0) * MINUTE_MS
        )
        FocusStore.setLong(
            context,
            KEY_TOTAL_SPENT_MS,
            FocusStore.getInt(context, LEGACY_KEY_TOTAL_SPENT_MINUTES, 0).coerceAtLeast(0) * MINUTE_MS
        )
        FocusStore.remove(context, LEGACY_KEY_SPEND_UNTIL)
    }

    fun balanceMinutes(context: Context): Int = (balanceMs(context) / MINUTE_MS).toInt()

    fun hasMinutes(context: Context): Boolean = spendableMs(context) > 0L

    /**
     * The pool as far as enforcement is concerned.
     *
     * Zero when Earn Mode is switched off, so a balance left over from before
     * cannot keep opening Budgeted apps after the person has turned the feature
     * off. [RuleEngine.decide] and the drain loop both have to ask this rather
     * than [balanceMs] directly, or the engine could allow an app that the
     * drain loop then refuses to bill - an app open for free, which is exactly
     * the inconsistency [isOnPoolTime] exists to prevent.
     */
    /**
     * Collects what a tracked task has accrued.
     *
     * Returns the minutes actually credited, which may be less than asked for
     * when the daily earning cap is in the way. The ledger is marked claimed
     * either way: what was tracked was tracked, and refusing the cap's share
     * must not silently bank it for tomorrow.
     */
    fun claimTracked(context: Context, task: FocusTask): Int {
        val minutes = FocusLedger.unclaimedMinutes(context, task.id)
        if (minutes <= 0) return 0
        val granted = credit(context, EarnMode.rewardForTrackedMinutes(context, minutes))
        FocusLedger.markClaimed(context, task.id)
        return granted
    }
    fun spendableMs(context: Context): Long =
        if (EarnMode.isEnabled(context)) balanceMs(context) else 0L

    fun earnedToday(context: Context): Int {
        val record = FocusStore.getJsonObject(context, KEY_EARNED_TODAY)
        if (record.optString("day", "") != todayKey()) return 0
        return record.optInt("minutes", 0)
    }

    fun totalEarned(context: Context): Int =
        (FocusStore.getLong(context, KEY_TOTAL_EARNED_MS, 0L) / MINUTE_MS).toInt()

    fun totalSpent(context: Context): Int =
        (FocusStore.getLong(context, KEY_TOTAL_SPENT_MS, 0L) / MINUTE_MS).toInt()

    /**
     * Credits a reward, honouring the user's own daily cap.
     * Returns what was actually credited, which may be less than asked.
     */
    fun credit(context: Context, minutes: Int): Int {
        if (minutes <= 0) return 0

        val cap = EarnMode.dailyCapMinutes(context)
        val already = earnedToday(context)
        val granted = if (cap <= 0) minutes else minutes.coerceAtMost((cap - already).coerceAtLeast(0))
        if (granted <= 0) return 0

        FocusStore.setLong(
            context,
            KEY_BALANCE_MS,
            balanceMs(context) + granted * MINUTE_MS
        )
        FocusStore.setLong(
            context,
            KEY_TOTAL_EARNED_MS,
            FocusStore.getLong(context, KEY_TOTAL_EARNED_MS, 0L) + granted * MINUTE_MS
        )

        val record = JSONObject()
        record.put("day", todayKey())
        record.put("minutes", already + granted)
        FocusStore.setJsonObject(context, KEY_EARNED_TODAY, record)

        PolicySync.request(context, "earn:credit")
        return granted
    }

    fun capReachedToday(context: Context): Boolean {
        val cap = EarnMode.dailyCapMinutes(context)
        return cap > 0 && earnedToday(context) >= cap
    }

    // ── Spending ──────────────────────────────────────────────────

    /**
     * Drains [ms] out of the pool and returns how much was actually there.
     *
     * Called from the enforcement loop for every tick a Budgeted app is in
     * front, so it has to be cheap and it has to be honest about running dry:
     * the value returned is what the caller charged, not what it asked for.
     *
     * Deliberately does *not* call `PolicySync.request` on the way through.
     * That would bump the policy revision four times a second and re-run every
     * Device-Owner call in [KioskPolicy] with it, for a number that only the
     * balance display reads. The request is raised at the one moment that
     * genuinely changes enforcement: when the pool hits zero and a Budgeted app
     * that was open a tick ago now has to be refused.
     */
    fun spendMs(context: Context, ms: Long): Long {
        if (ms <= 0L) return 0L
        val available = balanceMs(context)
        if (available <= 0L) return 0L

        val spent = ms.coerceAtMost(available)
        val left = available - spent
        FocusStore.setLong(context, KEY_BALANCE_MS, left)
        FocusStore.setLong(
            context,
            KEY_TOTAL_SPENT_MS,
            FocusStore.getLong(context, KEY_TOTAL_SPENT_MS, 0L) + spent
        )
        if (left <= 0L) PolicySync.request(context, "earn:poolDrained")
        return spent
    }

    /**
     * What a Budgeted app may do, as a pure function of the two numbers it
     * depends on.
     *
     * Kept separate from the [Context] reads on purpose, for the same reason
     * [KioskPolicy.buildLockTaskPackages] is pure: this is the rule the whole
     * Earn feature is, and it should be checkable without a device.
     */
    enum class BudgetVerdict {
        /** Still inside the app's own free daily minutes. */
        FREE_MINUTES,

        /** Past the free allowance, but the pool can pay for it. */
        POOL,

        /** Nothing left to open it with. */
        BLOCKED
    }

    /**
     * The Budgeted ladder.
     *
     * A daily limit, when the person set one, is a free allowance and comes
     * first - thirty minutes means thirty minutes, no questions. Past it, or
     * immediately for an app with no limit at all, the pool decides.
     */
    fun budgetVerdict(dailyLimitMinutes: Int?, usedMinutesToday: Int, poolMs: Long): BudgetVerdict = when {
        dailyLimitMinutes != null && usedMinutesToday < dailyLimitMinutes -> BudgetVerdict.FREE_MINUTES
        poolMs > 0L -> BudgetVerdict.POOL
        else -> BudgetVerdict.BLOCKED
    }

    /**
     * Whether [packageName] is a Budgeted app being paid for out of the pool
     * right now, rather than out of its own free daily minutes.
     *
     * This is the single question the drain loop and [RuleEngine.decide] must
     * agree on, so it lives in one place and is defined in terms of
     * [budgetVerdict] rather than restating the ladder.
     */
    fun isOnPoolTime(context: Context, packageName: String): Boolean {
        if (AppRules.effectivePolicy(context, packageName) != AppPolicy.LIMIT) return false
        return budgetVerdict(
            AppLimits.minuteLimit(context, packageName),
            AppLimits.usedMinutesToday(context, packageName),
            spendableMs(context)
        ) == BudgetVerdict.POOL
    }

    /**
     * Optional overnight fade. Off by default, and even when on it halves
     * rather than clears: a balance that vanishes entirely reads as punishment
     * for not spending it, which is the wrong lesson.
     */
    private fun applyDecayIfDue(context: Context) {
        if (!EarnMode.decaysUnspent(context)) return
        val today = dayNumber()

        // The marker defaults to 0, not to today, and the first read writes it.
        //
        // Defaulting it to today was why "it fades overnight" never actually
        // faded anything: the first call found last == today and returned
        // *without storing anything*, so the next day's call defaulted to that
        // day and returned again, forever. The key was never written once in
        // the feature's whole life, and the balance just sat there.
        val last = FocusStore.getInt(context, KEY_LAST_DECAY_DAY, 0)
        if (last <= 0) {
            FocusStore.setInt(context, KEY_LAST_DECAY_DAY, today)
            return
        }
        if (last >= today) {
            // A clock moved backwards (travel, a manual change). Re-anchor
            // rather than decaying repeatedly on the way back to the present.
            if (last > today) FocusStore.setInt(context, KEY_LAST_DECAY_DAY, today)
            return
        }

        // One halving per night missed, so a week away does not leave the
        // balance untouched just because nothing read it in between.
        val nights = (today - last).coerceAtMost(MAX_DECAY_NIGHTS)
        var balance = FocusStore.getLong(context, KEY_BALANCE_MS, 0L)
        repeat(nights) { balance /= 2 }
        FocusStore.setLong(context, KEY_BALANCE_MS, balance)
        FocusStore.setInt(context, KEY_LAST_DECAY_DAY, today)
    }

    /**
     * The pool in words, for the dashboard and the Earn card.
     *
     * Sub-minute balances are shown as "< 1 min" rather than "0 min", because
     * a pool that is being drained live spends most of its life under a minute
     * and telling someone with 40 seconds left that they have nothing is both
     * wrong and the exact moment they are looking hardest.
     */
    fun formatBalance(context: Context): String {
        val ms = balanceMs(context)
        if (ms <= 0L) return context.getString(R.string.earn_pool_empty)
        val minutes = ms / MINUTE_MS
        if (minutes <= 0L) return context.getString(R.string.earn_pool_under_minute)
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) {
            context.getString(R.string.earn_pool_hours, hours.toInt(), rest.toInt())
        } else {
            context.getString(R.string.earn_pool_minutes, minutes.toInt())
        }
    }

    private fun todayKey(): String {
        val calendar = java.util.Calendar.getInstance()
        return calendar.get(java.util.Calendar.YEAR).toString() + "-" +
            calendar.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** Halving more than this many times in one go is indistinguishable from zero. */
    private const val MAX_DECAY_NIGHTS = 16

    /**
     * The local day, not the UTC one.
     *
     * Dividing epoch millis by a day rolls over at UTC midnight, which for this
     * user is 2am - "overnight" has to mean their night, not Greenwich's.
     */
    private fun dayNumber(): Int {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return (calendar.timeInMillis / 86_400_000L).toInt()
    }
}
