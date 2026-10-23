package com.focuslock.mdm

import android.content.Context

/**
 * Every word the user reads at a hard moment.
 *
 * Two registers. **Kind** is the default: plain, warm, second-person, and it
 * never implies the person failed — shame reliably backfires on exactly the
 * behaviour a blocker is trying to change, and "you failed" is the sentence
 * that gets apps uninstalled. **Plain** is the same information with the warmth
 * removed, for people who find encouragement patronising.
 *
 * Nothing here scolds, counts failures, or threatens a streak.
 *
 * Every line lives in `res/values/copy_strings.xml` / `copy_arrays.xml` (and
 * their `values-ar` Arabic counterparts), never as a Kotlin literal, so a
 * locale switch reaches this file for free. Every rotated ("kind", several
 * phrasings) line is a `<string-array>` fed through [pick]; every dynamic
 * line uses a positional `%1$s`/`%2$s` format placeholder rather than string
 * concatenation, because only a format placeholder can be reordered per
 * language - Arabic word order does not match English.
 */
object Copy {

    private fun kind(context: Context): Boolean =
        CapabilityRegistry.isEnabled(context, Capabilities.SELF_COMPASSION_COPY)

    /**
     * Picks a line that stays the same for a few minutes at a time, so a screen
     * that redraws every tick does not shuffle its words under the reader.
     */
    private fun pick(options: List<String>): String {
        if (options.isEmpty()) return ""
        val bucket = (System.currentTimeMillis() / 300_000L).toInt()
        val index = ((bucket % options.size) + options.size) % options.size
        return options[index]
    }

    /** [pick] against a resolved `<string-array>` resource, then applies the format args. */
    private fun pickFormatted(context: Context, arrayResId: Int, vararg args: Any): String {
        val options = context.resources.getStringArray(arrayResId).toList()
        return String.format(pick(options), *args)
    }

    private fun appLabel(context: Context, packageName: String): String =
        AppCatalog.label(context, packageName)

    // ── Blocking ──────────────────────────────────────────────────

    fun blockHeadline(context: Context, packageName: String): String {
        val label = appLabel(context, packageName)
        return if (kind(context)) {
            pickFormatted(context, R.array.copy_block_headline_kind, label)
        } else {
            context.getString(R.string.copy_block_headline_plain, label)
        }
    }

    fun blockDetail(context: Context, packageName: String): String {
        if (!kind(context)) return context.getString(R.string.copy_block_detail_plain)
        return pick(context.resources.getStringArray(R.array.copy_block_detail_kind).toList())
    }

    fun pauseHeadline(context: Context, packageName: String): String {
        val label = appLabel(context, packageName)
        return if (kind(context)) {
            pickFormatted(context, R.array.copy_pause_headline_kind, label)
        } else {
            context.getString(R.string.copy_pause_headline_plain, label)
        }
    }

    fun pauseDetail(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_pause_detail_plain)
        return pick(context.resources.getStringArray(R.array.copy_pause_detail_kind).toList())
    }

    fun softDetail(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_soft_detail_plain)
        return context.getString(R.string.copy_soft_detail_kind)
    }

    fun kioskDetail(context: Context): String {
        val remaining = SessionManager.formatRemaining(context)
        if (!kind(context)) return context.getString(R.string.copy_kiosk_detail_plain, remaining)
        return pickFormatted(context, R.array.copy_kiosk_detail_kind, remaining)
    }

    fun kioskHomeHeadline(context: Context): String =
        if (kind(context)) context.getString(R.string.copy_kiosk_home_headline_kind)
        else context.getString(R.string.copy_kiosk_home_headline_plain)

    fun kioskHomeDetail(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_kiosk_home_detail_plain)
        return context.getString(R.string.copy_kiosk_home_detail_kind)
    }

    fun overlayGuardHeadline(context: Context): String =
        if (kind(context)) context.getString(R.string.copy_overlay_guard_headline_kind)
        else context.getString(R.string.copy_overlay_guard_headline_plain)

    fun overlayGuardDetail(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_overlay_guard_detail_plain)
        return context.getString(R.string.copy_overlay_guard_detail_kind)
    }

    // ── Schedules, bedtime, place ─────────────────────────────────

    fun scheduleHeadline(context: Context, window: ScheduleWindow): String {
        if (window.message.isNotBlank()) return window.message
        return if (kind(context)) context.getString(R.string.copy_schedule_headline_kind)
        else context.getString(R.string.copy_schedule_headline_plain)
    }

    fun scheduleDetail(context: Context, window: ScheduleWindow): String {
        val until = ScheduleManager.formatTime(context, window.endMinutes)
        if (!kind(context)) return context.getString(R.string.copy_schedule_detail_plain, until)
        return pickFormatted(context, R.array.copy_schedule_detail_kind, until)
    }

    fun scheduleOverlayHeadline(context: Context, window: ScheduleWindow): String {
        if (window.message.isNotBlank()) return window.message
        return if (kind(context)) context.getString(R.string.copy_schedule_overlay_headline_kind)
        else context.getString(R.string.copy_schedule_overlay_headline_plain)
    }

    /** Same shape as [scheduleDetail], but never offers a break - this window doesn't bend. */
    fun scheduleOverlayDetail(context: Context, window: ScheduleWindow): String {
        val until = ScheduleManager.formatTime(context, window.endMinutes)
        if (!kind(context)) return context.getString(R.string.copy_schedule_overlay_detail_plain, until)
        return pickFormatted(context, R.array.copy_schedule_overlay_detail_kind, until)
    }

    fun bedtimeHeadline(context: Context): String =
        if (kind(context)) context.getString(R.string.copy_bedtime_headline_kind)
        else context.getString(R.string.copy_bedtime_headline_plain)

    fun bedtimeDetail(context: Context): String {
        val end = Bedtime.formatTime(context, Bedtime.endMinutes(context))
        if (!kind(context)) return context.getString(R.string.copy_bedtime_detail_plain, end)
        return pickFormatted(context, R.array.copy_bedtime_detail_kind, end)
    }

    fun placeHeadline(context: Context, place: Place): String {
        if (!kind(context)) return context.getString(R.string.copy_place_headline_plain, place.label)
        return if (place.trigger == PlaceTrigger.INSIDE) {
            context.getString(R.string.copy_place_headline_inside_kind, place.label)
        } else {
            context.getString(R.string.copy_place_headline_outside_kind, place.label)
        }
    }

    fun placeDetail(context: Context, place: Place): String {
        if (!kind(context)) return context.getString(R.string.copy_place_detail_plain, place.label)
        return context.getString(R.string.copy_place_detail_kind, place.label)
    }

    fun limitHeadline(context: Context, packageName: String): String {
        val label = appLabel(context, packageName)
        return if (kind(context)) context.getString(R.string.copy_limit_headline_kind, label)
        else context.getString(R.string.copy_limit_headline_plain, label)
    }

    /**
     * The headline for a Budgeted app with nothing left to pay with.
     *
     * Deliberately not the same words as an ordinary daily limit. "Limit
     * reached" is a dead end; a budget is something the person can refill, and
     * the sentence should say so without nagging them about it.
     */
    fun budgetHeadline(context: Context, packageName: String): String {
        val label = appLabel(context, packageName)
        return if (kind(context)) context.getString(R.string.copy_budget_headline_kind, label)
        else context.getString(R.string.copy_budget_headline_plain, label)
    }

    /**
     * The detail line under a Budgeted block.
     *
     * Two genuinely different situations, so two different sentences. An app
     * with a daily limit ran out of *today's* free minutes and needs the pool;
     * an app with no limit never had free minutes and needs the pool from the
     * start. Telling someone their free minutes are gone when they never had
     * any is the kind of small lie that makes the rest of the screen
     * untrustworthy.
     */
    fun budgetDetail(context: Context, limit: Int?): String = when {
        limit == null ->
            if (kind(context)) context.getString(R.string.copy_budget_detail_nolimit_kind)
            else context.getString(R.string.copy_budget_detail_nolimit_plain)
        else ->
            if (kind(context)) context.getString(R.string.copy_budget_detail_limit_kind, limit)
            else context.getString(R.string.copy_budget_detail_limit_plain, limit)
    }

    /** The pool line shown on a Budgeted block and on the Earn card. */
    fun budgetPoolLine(context: Context, poolMinutes: Int): String =
        if (poolMinutes <= 0) {
            if (kind(context)) context.getString(R.string.copy_budget_pool_empty_kind)
            else context.getString(R.string.copy_budget_pool_empty_plain)
        } else {
            if (kind(context)) context.getString(R.string.copy_budget_pool_left_kind, poolMinutes)
            else context.getString(R.string.copy_budget_pool_left_plain, poolMinutes)
        }

    fun ruleHeadline(context: Context, rule: Rule): String {
        if (rule.label.isNotBlank()) return rule.label
        return if (kind(context)) context.getString(R.string.copy_rule_headline_kind)
        else context.getString(R.string.copy_rule_headline_plain)
    }

    fun ruleDetail(context: Context, rule: Rule): String {
        val condition = rule.conditionType.label(context).lowercase()
        if (!kind(context)) return context.getString(R.string.copy_rule_detail_plain, condition)
        return context.getString(R.string.copy_rule_detail_kind, condition)
    }

    // ── Earn mode ─────────────────────────────────────────────────
    //
    // The tone here does more work than anywhere else in the app. A reward
    // system that sounds like a slot machine trains the wrong thing, and one
    // that sounds like a supervisor is exactly the "controlling" framing the
    // Self-Determination research says turns a reward into a demotivator. So:
    // the time is described as already the person's, the deal as one they wrote,
    // and a miss as a fact rather than a verdict.

    fun earnHeadline(context: Context, packageName: String): String {
        val label = appLabel(context, packageName)
        return if (kind(context)) {
            pickFormatted(context, R.array.copy_earn_headline_kind, label)
        } else {
            context.getString(R.string.copy_earn_headline_plain, label)
        }
    }

    fun earnDetail(context: Context, task: FocusTask): String {
        if (!kind(context)) return context.getString(R.string.copy_earn_detail_plain, task.title)
        val reward = task.rewardMin
        val tail = when {
            task.enjoyable -> context.getString(R.string.copy_earn_tail_enjoyable)
            reward != null && reward > 0 -> context.getString(R.string.copy_earn_tail_reward, reward)
            else -> context.getString(R.string.copy_earn_tail_default)
        }
        return context.getString(R.string.copy_earn_detail_kind, task.title, tail)
    }

    fun earnCompleted(context: Context, minutes: Int): String {
        if (minutes <= 0) {
            return if (kind(context)) {
                context.getString(R.string.copy_earn_completed_zero_kind)
            } else {
                context.getString(R.string.copy_earn_completed_zero_plain)
            }
        }
        if (!kind(context)) return context.getString(R.string.copy_earn_completed_plain, minutes)
        return pickFormatted(context, R.array.copy_earn_completed_kind, minutes)
    }

    fun earnCapReached(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_earn_cap_reached_plain)
        return context.getString(R.string.copy_earn_cap_reached_kind)
    }

    fun earnPhotoRetry(context: Context, attempts: Int): String {
        if (!kind(context)) return context.getString(R.string.copy_earn_photo_retry_plain, attempts)
        return pick(context.resources.getStringArray(R.array.copy_earn_photo_retry_kind).toList())
    }

    fun earnDeal(context: Context): String =
        if (kind(context)) {
            context.getString(R.string.copy_earn_deal_kind_prefix, EarnMode.describeDeal(context))
        } else {
            EarnMode.describeDeal(context)
        }

    /** Shown wherever tasks or proof photos are handled. */
    fun onDeviceFooter(context: Context): String =
        if (kind(context)) {
            context.getString(R.string.copy_on_device_footer_kind)
        } else {
            context.getString(R.string.copy_on_device_footer_plain)
        }

    // ── Sessions ──────────────────────────────────────────────────

    fun sessionStarted(context: Context, mode: FocusMode): String {
        if (!kind(context)) return context.getString(R.string.copy_session_started_plain, mode.label(context))
        return pickFormatted(context, R.array.copy_session_started_kind, mode.label(context))
    }

    fun sessionEndedEarly(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_session_ended_early_plain)
        return context.getString(R.string.copy_session_ended_early_kind)
    }

    /**
     * Shown when a rule change is refused because a session is holding.
     *
     * The person is not being told off for trying. They are being reminded that
     * a calmer version of them already made this call, which is the whole point
     * of setting it up in advance.
     */
    fun rulesFrozen(context: Context, remaining: String): String {
        if (!kind(context)) return context.getString(R.string.copy_rules_frozen_plain, remaining)
        return context.getString(R.string.copy_rules_frozen_kind, remaining)
    }

    /** Same refusal, for an overlay schedule window rather than a session - see [ScheduleWindow.overlay]. */
    fun rulesFrozenBySchedule(context: Context, until: String): String {
        if (!kind(context)) return context.getString(R.string.copy_rules_frozen_by_schedule_plain, until)
        return context.getString(R.string.copy_rules_frozen_by_schedule_kind, until)
    }

    /** The line under a frozen switch, explaining why it will not move. */
    fun rulesFrozenHint(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_rules_frozen_hint_plain)
        return context.getString(R.string.copy_rules_frozen_hint_kind)
    }

    fun breakStarted(context: Context, minutes: Int): String {
        if (!kind(context)) return context.getString(R.string.copy_break_started_plain, minutes)
        return context.getString(R.string.copy_break_started_kind, minutes)
    }

    fun breakUnavailable(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_break_unavailable_plain)
        return context.getString(R.string.copy_break_unavailable_kind)
    }

    fun relapseNote(context: Context): String {
        if (!kind(context)) return context.getString(R.string.copy_relapse_note_plain)
        return pick(context.resources.getStringArray(R.array.copy_relapse_note_kind).toList())
    }

    // ── Onboarding and settings ───────────────────────────────────

    fun weakenWarning(context: Context, spec: CapabilitySpec): String =
        spec.weakenNote(context) ?: context.getString(R.string.copy_weaken_warning_fallback)

    fun emptyRules(context: Context): String =
        if (kind(context)) {
            context.getString(R.string.copy_empty_rules_kind)
        } else {
            context.getString(R.string.copy_empty_rules_plain)
        }

    fun emptySchedules(context: Context): String =
        if (kind(context)) {
            context.getString(R.string.copy_empty_schedules_kind)
        } else {
            context.getString(R.string.copy_empty_schedules_plain)
        }

    fun emptyKeywords(context: Context): String =
        if (kind(context)) {
            context.getString(R.string.copy_empty_keywords_kind)
        } else {
            context.getString(R.string.copy_empty_keywords_plain)
        }

    // ── Content guard ─────────────────────────────────────────────

    /** Names the exact keyword rule that fired - `phrase` is the rule's own text, never raw on-screen content. */
    fun contentGuardHeadline(context: Context, packageName: String, phrase: String): String {
        val label = appLabel(context, packageName)
        return if (kind(context)) {
            pickFormatted(context, R.array.copy_content_guard_headline_kind, label, phrase)
        } else {
            context.getString(R.string.copy_content_guard_headline_plain, label, phrase)
        }
    }

    fun contentGuardDetail(context: Context, phrase: String): String {
        if (!kind(context)) return context.getString(R.string.copy_content_guard_detail_plain, phrase)
        return context.getString(R.string.copy_content_guard_detail_kind)
    }

    fun analyticsIntro(context: Context): String =
        if (kind(context)) {
            context.getString(R.string.copy_analytics_intro_kind)
        } else {
            context.getString(R.string.copy_analytics_intro_plain)
        }
}
