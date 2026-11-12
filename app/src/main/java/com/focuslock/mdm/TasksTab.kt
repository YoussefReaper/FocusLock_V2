package com.focuslock.mdm

import android.content.Intent
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Tasks tab.
 *
 * Two things live here and they are deliberately separable: a task list that
 * works whether or not Earn Mode is on, and the earning layer on top of it.
 * Tasks are useful on their own, and a person who wants the list without the
 * economy should not have to accept the economy to get it.
 *
 * Nothing on this screen is gamified. There are no points, no confetti, no
 * levels — the reward is minutes of your own phone, stated in minutes.
 */
class TasksTab(activity: MainActivity, tokens: UiPrefs.Tokens) : FocusTab(activity, tokens) {

    private lateinit var container: LinearLayout
    private var filter = 0
    private val filters get() = activity.resources.getStringArray(R.array.tasks_filters).toList()

    override fun build(): View {
        container = FocusUi.column(activity, tokens.density.contentPaddingDp)
        return hostScroll(FocusUi.scroll(activity, container), container)
    }

    override fun onShow() {
        sweepMissed()
        shownFor = liveSignature()
        render()
    }

    /** What the time-dependent parts of the tab last showed. See [onTick]. */
    private var shownFor = ""

    override fun onTick() {
        // The pool is drained live, so the balance on this card changes while
        // the person is looking at it - but only by the minute, which is all
        // it shows. This used to rebuild the entire tab every second whenever
        // there was a balance at all: every card, every task row, re-parsed
        // and re-laid-out sixty times for each number that changed once.
        if (!EarnPool.hasMinutes(activity) && !EarnSession.isActive(activity)) return
        val now = liveSignature()
        if (now == shownFor) return
        shownFor = now
        render()
    }

    private fun liveSignature(): String =
        EarnPool.balanceMinutes(activity).toString() + "|" +
            EarnPool.earnedToday(activity) + "|" +
            EarnSession.activeTask(activity)?.id + "|" +
            (EarnSession.elapsedMs(activity) / 60_000L)

    /** Deadlines that came and went are recorded once, quietly, and never punished. */
    private fun sweepMissed() {
        FocusTaskStore.overdue(activity)
            .filter { it.deadline != null && it.missedCount == 0 }
            .forEach { FocusTaskStore.markMissed(activity, it) }
    }

    private fun render(): Unit = redraw {
        val added = ArrayList<View>()

        fun add(view: View) {
            container.addView(view)
            added.add(view)
        }

        add(
            FocusUi.smallButton(activity, tokens, activity.getString(R.string.nav_back_to_focus)) {
                activity.selectTab(MainActivity.TAB_FOCUS)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    FocusUi.dp(activity, tokens.density.quickButtonHeightDp)
                ).apply { bottomMargin = FocusUi.dp(activity, 8) }
            }
        )

        add(
            FocusUi.pageHeader(
                activity,
                tokens,
                activity.getString(R.string.tasks_page_title),
                // The deal itself is the last row of the pool card below;
                // repeating it here put the same sentence on screen twice.
                activity.getString(R.string.tasks_page_subtitle),
                art = R.drawable.art_free
            )
        )

        if (!EarnMode.isEnabled(activity)) {
            add(buildEarnIntro())
        } else {
            EarnSession.activeTask(activity)?.let { add(buildActiveSessionCard(it)) }
            add(buildBudgetCard())
        }

        add(buildTasksSectionHeader())
        add(FocusUi.chipStrip(activity, tokens, filters, filter) { index ->
            filter = index
            render()
        })

        add(buildTaskList())

        add(FocusUi.spacer(activity, 4))
        add(FocusUi.primaryButton(activity, tokens, activity.getString(R.string.tasks_add_task_button)) { openEditor(null) })

        add(FocusUi.spacer(activity, 16))
        val footer = FocusUi.caption(activity, tokens, Copy.onDeviceFooter(activity))
        footer.gravity = android.view.Gravity.CENTER
        add(footer)
        add(FocusUi.spacer(activity, 20))

        Motion.stagger(added, tokens)
    }

    // ── Earn Mode introduction ────────────────────────────────────

    /**
     * The honest pitch, including the part that argues against itself.
     *
     * Telling someone up front that rewards can undercut motivation for work
     * they already enjoy is the difference between a tool and a sales page, and
     * it is what makes the opt-in a real choice.
     */
    private fun buildEarnIntro(): View {
        val card = FocusUi.card(activity, tokens, elevated = true)
        card.addView(FocusUi.heading(activity, tokens, activity.getString(R.string.tasks_earn_intro_heading)))
        card.addView(FocusUi.spacer(activity, 8))
        card.addView(
            FocusUi.secondary(
                activity,
                tokens,
                activity.getString(R.string.tasks_earn_intro_body)
            )
        )
        card.addView(FocusUi.spacer(activity, 10))
        card.addView(
            FocusUi.caption(
                activity,
                tokens,
                activity.getString(R.string.tasks_earn_intro_caption)
            )
        )
        card.addView(FocusUi.spacer(activity, 14))
        card.addView(
            FocusUi.primaryButton(activity, tokens, activity.getString(R.string.tasks_earn_intro_button)) {
                DemoMode.requirePro(activity, activity.getString(R.string.tasks_earn_intro_heading)) {
                    if (!CapabilityRegistry.setEnabled(activity, Capabilities.EARN_MODE, true)) {
                        FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                    } else {
                        AppBlockerService.startIfNeeded(activity)
                    }
                    render()
                }
            }
        )

        if (!EarnMode.hasHardEnforcement(activity)) {
            card.addView(FocusUi.spacer(activity, 10))
            card.addView(
                FocusUi.pill(activity, tokens, activity.getString(R.string.tasks_earn_intro_needs_device_owner), tokens.warning)
            )
        }
        return card
    }

    // ── Active session ────────────────────────────────────────────

    private fun buildActiveSessionCard(task: FocusTask): View {
        val card = FocusUi.card(activity, tokens, elevated = true) { openSession() }

        val header = FocusUi.row(activity)
        val title = FocusUi.heading(activity, tokens, task.title)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(title)
        header.addView(FocusUi.pill(activity, tokens, activity.getString(R.string.tasks_active_session_running_pill), tokens.success))
        card.addView(header)

        card.addView(FocusUi.spacer(activity, 6))
        card.addView(
            FocusUi.secondary(
                activity,
                tokens,
                if (EarnSession.isStandalone(activity)) {
                    activity.getString(
                        R.string.tasks_active_session_summary_standalone,
                        SessionManager.formatDuration(EarnSession.elapsedMs(activity)),
                        task.verification.label(activity).lowercase()
                    )
                } else {
                    activity.getString(
                        R.string.tasks_active_session_summary_linked,
                        SessionManager.formatDuration(EarnSession.elapsedMs(activity)),
                        task.verification.label(activity).lowercase()
                    )
                }
            )
        )

        card.addView(FocusUi.spacer(activity, 14))
        card.addView(FocusUi.primaryButton(activity, tokens, activity.getString(R.string.tasks_active_session_open_button)) { openSession() })
        return card
    }

    // ── Pool ──────────────────────────────────────────────────────

    /**
     * The earned pool.
     *
     * A balance, what it is for, and which apps it pays for - and no spend
     * button, because there is nothing to start. The pool is drawn down by the
     * Budgeted apps themselves, live, for as long as one of them is in front.
     * Showing the app list here is the part that makes the number mean
     * something: "40 minutes in the pool" is abstract, "40 minutes, and it is
     * Instagram and TikTok that spend it" is not.
     */
    private fun buildBudgetCard(): View {
        // The "pool" hero card (design doc: gradient wash, a big mono number).
        val card = FocusUi.card(activity, tokens, elevated = true)
        card.background = FocusUi.gradientShape(
            activity,
            UiPrefs.blend(tokens.surfaceAlt, tokens.accent, 0.16f),
            tokens.surface,
            tokens.cardRadiusDp
        ).apply {
            setStroke(FocusUi.dp(activity, 1), UiPrefs.blend(tokens.divider, tokens.accent, 0.35f))
        }

        val overline = FocusUi.caption(activity, tokens, activity.getString(R.string.tasks_budget_banked_label).uppercase())
        overline.setTextColor(tokens.accent)
        FocusUi.applyFont(overline, tokens, mono = true, weight = 600)
        card.addView(overline)
        card.addView(FocusUi.spacer(activity, 8))

        val balance = EarnPool.balanceMinutes(activity)
        // Both wrap-content and on a shared baseline. Text defaults to
        // match-parent, and in a row that let the number take the whole width:
        // "minutes" was squeezed to nothing and wrapped one letter per line,
        // which is where the tall blank space around a bare "0" came from.
        val numberRow = FocusUi.row(activity)
        numberRow.gravity = Gravity.BOTTOM
        numberRow.isBaselineAligned = true
        val wrap = {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        numberRow.addView(FocusUi.display(activity, tokens, balance.toString()).apply { layoutParams = wrap() })
        numberRow.addView(FocusUi.spacerH(activity, 8))
        numberRow.addView(
            FocusUi.secondary(activity, tokens, activity.getString(R.string.tasks_budget_minutes_unit)).apply {
                layoutParams = wrap()
            }
        )
        card.addView(numberRow)

        card.addView(FocusUi.spacer(activity, 8))
        card.addView(
            FocusUi.secondary(
                activity,
                tokens,
                if (balance <= 0) {
                    activity.getString(R.string.tasks_pool_empty_hint)
                } else if (EarnMode.decaysUnspent(activity)) {
                    activity.getString(R.string.tasks_pool_hint_decay)
                } else {
                    activity.getString(R.string.tasks_pool_hint)
                }
            )
        )

        if (EarnPool.earnedToday(activity) > 0) {
            card.addView(FocusUi.spacer(activity, 8))
            card.addView(
                FocusUi.pill(
                    activity,
                    tokens,
                    activity.getString(R.string.tasks_budget_earned_today_pill, EarnPool.earnedToday(activity)),
                    tokens.accent
                )
            )
        }

        // Which apps actually spend this. Read straight from the policy store,
        // so adding or removing a Budgeted app changes this list and nothing
        // else has to be kept in step.
        val budgeted = AppRules.packagesWithPolicy(activity, AppPolicy.LIMIT)
            .filter { it != activity.packageName }
            .sortedBy { AppCatalog.label(activity, it) }

        card.addView(FocusUi.spacer(activity, 12))
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.tasks_pool_apps_row_title),
                if (budgeted.isEmpty()) {
                    activity.getString(R.string.tasks_pool_apps_row_none)
                } else {
                    budgeted.joinToString(", ") { AppCatalog.label(activity, it) }
                },
                trailing = FocusUi.chevron(activity, tokens)
            ) { openBudgetedApps() }
        )

        card.addView(FocusUi.spacer(activity, 10))
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.tasks_budget_deal_row_title),
                EarnMode.describeDeal(activity),
                trailing = FocusUi.chevron(activity, tokens)
            ) { openDealSheet() }
        )
        return card
    }

    /**
     * Sends the person to the app rules editor, which is where an app becomes
     * Budgeted in the first place. The pool card is a dead end otherwise: it
     * can say which apps spend the balance but not let you change any of them.
     */
    private fun openBudgetedApps() {
        activity.startActivity(
            android.content.Intent(activity, AppRulesActivity::class.java)
        )
    }

    /** Every number in the economy, in one place, all of them editable. */
    private fun openDealSheet() {
        FocusDialog.custom(
            activity,
            title = activity.getString(R.string.tasks_deal_sheet_title),
            subtitle = activity.getString(R.string.tasks_deal_sheet_subtitle),
            confirmLabel = null,
            cancelLabel = activity.getString(R.string.tasks_deal_sheet_done)
        ) { body, dialogTokens, _ ->
            body.addView(
                FocusUi.sliderRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.tasks_deal_rate_label),
                    0,
                    60,
                    EarnMode.ratePercent(activity) * 60 / 100,
                    { if (it == 0) activity.getString(R.string.tasks_deal_rate_zero) else activity.getString(R.string.tasks_deal_minutes_value, it) }
                ) { value -> EarnMode.setRatePercent(activity, value * 100 / 60) }
            )
            body.addView(
                FocusUi.sliderRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.tasks_deal_cap_label),
                    0,
                    360,
                    EarnMode.dailyCapMinutes(activity),
                    { if (it == 0) activity.getString(R.string.tasks_deal_cap_zero) else activity.getString(R.string.tasks_deal_minutes_value, it) }
                ) { value -> EarnMode.setDailyCapMinutes(activity, value) }
            )

            body.addView(FocusUi.divider(activity, dialogTokens, 8))

            body.addView(
                FocusUi.toggleRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.tasks_deal_photo_proof_label),
                    activity.getString(R.string.tasks_deal_photo_proof_desc),
                    CapabilityRegistry.getBoolParam(activity, Capabilities.EARN_MODE, EarnMode.PARAM_PHOTO_PROOF, true)
                ) { value ->
                    if (!CapabilityRegistry.setBoolParam(activity, Capabilities.EARN_MODE, EarnMode.PARAM_PHOTO_PROOF, value)) {
                        FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                    }
                }
            )
            body.addView(
                FocusUi.toggleRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.tasks_deal_intersect_label),
                    activity.getString(R.string.tasks_deal_intersect_desc),
                    EarnMode.intersectsWithAllowlist(activity)
                ) { value ->
                    if (!CapabilityRegistry.setBoolParam(activity, Capabilities.EARN_MODE, EarnMode.PARAM_INTERSECT, value)) {
                        FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                    } else if (!value) {
                        FocusDialog.toast(activity, activity.getString(R.string.tasks_deal_intersect_off_toast))
                    }
                }
            )
            body.addView(
                FocusUi.toggleRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.tasks_deal_show_budget_label),
                    null,
                    EarnMode.showsBudgetWhileActive(activity)
                ) { value ->
                    if (!CapabilityRegistry.setBoolParam(activity, Capabilities.EARN_MODE, EarnMode.PARAM_SHOW_BUDGET, value)) {
                        FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                    }
                }
            )
            body.addView(
                FocusUi.toggleRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.tasks_deal_credibility_label),
                    activity.getString(R.string.tasks_deal_credibility_desc),
                    EarnMode.showsCredibility(activity)
                ) { value ->
                    if (!CapabilityRegistry.setBoolParam(activity, Capabilities.EARN_MODE, EarnMode.PARAM_CREDIBILITY, value)) {
                        FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                    }
                }
            )
            body.addView(
                FocusUi.toggleRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.tasks_deal_decay_label),
                    activity.getString(R.string.tasks_deal_decay_desc),
                    EarnMode.decaysUnspent(activity)
                ) { value ->
                    if (!CapabilityRegistry.setBoolParam(activity, Capabilities.EARN_MODE, EarnMode.PARAM_DECAY, value)) {
                        FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                    }
                }
            )
        }
    }

    // ── The list ──────────────────────────────────────────────────

    /**
     * The "Tasks" overline plus how many are done - design doc's Earn Mode
     * screen shows this right above the filters, not buried in a card.
     */
    private fun buildTasksSectionHeader(): View {
        val row = FocusUi.row(activity)
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = FocusUi.dp(activity, 4)
            bottomMargin = FocusUi.dp(activity, 8)
        }

        val label = FocusUi.caption(activity, tokens, activity.getString(R.string.tasks_section_label).uppercase())
        FocusUi.applyFont(label, tokens, mono = true, weight = 600)
        label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(label)

        val all = FocusTaskStore.all(activity)
        if (all.isNotEmpty()) {
            row.addView(
                FocusUi.caption(
                    activity,
                    tokens,
                    activity.getString(R.string.tasks_section_done_count, all.count { it.completed }, all.size)
                )
            )
        }
        return row
    }

    /**
     * A currently-running task already gets its own prominent card above
     * (buildActiveSessionCard) - showing it again here, unchanged, in the
     * ordinary list was the exact kind of duplication that makes a screen
     * feel twice as long as it needs to be.
     */
    private fun buildTaskList(): View {
        val activeId = EarnSession.activeTask(activity)?.id
        val tasks = when (filter) {
            1 -> FocusTaskStore.overdue(activity)
            2 -> FocusTaskStore.open(activity).sortedWith(
                compareByDescending<FocusTask> { it.priority.ordinal }.thenBy { it.title }
            )
            3 -> FocusTaskStore.all(activity).filter { it.completed }
                .sortedByDescending { it.completedAt ?: 0L }
            else -> FocusTaskStore.dueToday(activity)
        }.filter { it.id != activeId }

        if (tasks.isEmpty()) {
            val card = FocusUi.card(activity, tokens)
            card.addView(FocusUi.emptyState(activity, tokens, emptyMessage()))
            return card
        }

        val column = FocusUi.column(activity)
        tasks.forEach { task -> column.addView(buildTaskCard(task)) }
        return column
    }

    private fun emptyMessage(): String = when (filter) {
        1 -> activity.getString(R.string.tasks_empty_overdue)
        3 -> activity.getString(R.string.tasks_empty_done)
        else -> activity.getString(R.string.tasks_empty_default)
    }

    /**
     * Its own card, per the design doc's task rows - a checkbox, a title and
     * status line, and a trailing action, instead of one long card with every
     * task crammed into it behind dividers.
     */
    private fun buildTaskCard(task: FocusTask): View {
        val card = FocusUi.card(activity, tokens)

        val row = FocusUi.row(activity)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(buildTaskCheckbox(task))
        row.addView(FocusUi.spacerH(activity, 13))

        val textColumn = FocusUi.column(activity)
        textColumn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val titleView = FocusUi.rowTitle(activity, tokens, task.title)
        if (task.completed) {
            titleView.paintFlags = titleView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            titleView.setTextColor(tokens.textMuted)
        }
        textColumn.addView(titleView)

        val subtitle = describe(task)
        if (subtitle.isNotBlank()) {
            val subtitleView = FocusUi.caption(activity, tokens, subtitle)
            subtitleView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(activity, 2) }
            textColumn.addView(subtitleView)
        }
        row.addView(textColumn)

        // An ambient task has nothing to start - it is already counting - so it
        // gets no Start button. The claim button below is the action it does
        // have, and offering a session here would be describing a different
        // feature: one with a beginning, a lock and an end.
        val trailing = when {
            task.completed -> null
            task.verification == Verification.AMBIENT -> null
            EarnMode.isEnabled(activity) ->
                FocusUi.smallButton(activity, tokens, activity.getString(R.string.tasks_row_start_button)) { startTask(task) }
            else -> FocusUi.chevron(activity, tokens)
        }
        if (trailing != null) {
            row.addView(FocusUi.spacerH(activity, 13))
            row.addView(trailing)
        }

        row.isClickable = true
        row.isFocusable = true
        row.background = FocusUi.withRipple(
            activity,
            FocusUi.roundedShape(activity, UiPrefs.withAlpha(tokens.surface, 0), tokens.rowRadiusDp),
            tokens
        )
        row.setOnClickListener { openEditor(task) }
        card.addView(row)

        if (task.subtasks.isNotEmpty() && !task.completed) {
            card.addView(FocusUi.spacer(activity, 8))
            card.addView(
                FocusUi.meter(
                    activity,
                    tokens,
                    activity.getString(R.string.tasks_row_steps_meter, task.subtasks.count { it.done }, task.subtasks.size),
                    activity.getString(R.string.tasks_row_percent, task.progressPercent),
                    task.progressPercent / 100f,
                    tokens.accent
                )
            )
        }

        // An ambient task is paid by claiming, so its row carries the balance
        // and the one button that collects it. There is no session to open and
        // nothing to tick off, which is why this sits here rather than in the
        // Start flow the other verifications use.
        if (EarnMode.isEnabled(activity) && !task.completed && task.verification == Verification.AMBIENT) {
            val tracked = EarnSession.trackedMinutes(activity, task)
            // What the button collects is what has *not* been collected yet.
            // The ledger keeps counting after a claim, so lifetime tracked time
            // would keep offering minutes that were already banked.
            val unclaimed = FocusLedger.unclaimedMinutes(activity, task.id)
            val claimable = EarnMode.rewardForTrackedMinutes(activity, unclaimed)
            card.addView(FocusUi.spacer(activity, 8))
            card.addView(
                FocusUi.caption(
                    activity,
                    tokens,
                    activity.getString(R.string.tasks_ambient_tracked, tracked, claimable)
                )
            )
            card.addView(FocusUi.spacer(activity, 8))
            val claim = FocusUi.secondaryButton(
                activity,
                tokens,
                activity.getString(R.string.tasks_ambient_claim, claimable)
            ) {
                val granted = EarnPool.claimTracked(activity, task)
                // The daily cap can refuse the whole claim, and the ledger is
                // marked collected either way. Saying nothing there would look
                // like the button did not work.
                FocusDialog.toast(
                    activity,
                    if (granted > 0) Copy.earnCompleted(activity, granted) else Copy.earnCapReached(activity)
                )
                render()
            }
            // Nothing waiting means nothing to collect. It says so by being
            // unavailable rather than by collecting zero and reporting success.
            // Dimmed as well as disabled, because a button that looks tappable
            // and does nothing reads as broken.
            claim.isEnabled = claimable > 0
            claim.alpha = if (claimable > 0) 1f else 0.5f
            card.addView(claim)
        }
        return card
    }

    /**
     * A literal checkbox, not a settings-style chevron row - tapping it
     * finishes a manually-verified task on the spot. Anything that needs a
     * timer, a photo or its steps ticked cannot be self-certified by a tap
     * (this app's whole point is verification over an honour system), so
     * those stay outline-only and the row itself still opens the editor.
     * The border borrows the old priority dot's colour instead of drawing a
     * second dot next to it.
     */
    private fun buildTaskCheckbox(task: FocusTask): View {
        val size = FocusUi.dp(activity, 26)
        val box: View

        if (task.completed) {
            val check = TextView(activity)
            check.text = "✓"
            check.gravity = Gravity.CENTER
            check.setTextColor(
                if (FocusUi.isLightColor(tokens.success)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
            FocusUi.applyFont(check, tokens, weight = 700)
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(14f))
            check.background = FocusUi.roundedShape(activity, tokens.success, 9)
            box = check
        } else {
            box = View(activity)
            box.background = FocusUi.roundedShape(
                activity,
                UiPrefs.withAlpha(tokens.surface, 0),
                9,
                priorityColor(task.priority)
            )
        }
        box.layoutParams = LinearLayout.LayoutParams(size, size)

        if (!task.completed && task.verification == Verification.MANUAL) {
            box.isClickable = true
            box.isFocusable = true
            box.setOnClickListener {
                FocusTaskStore.complete(activity, task)
                render()
            }
        }
        return box
    }

    /**
     * The one line under a task title.
     *
     * It used to concatenate everything it knew - deadline, overdue flag,
     * estimate, verification method, reward, recurrence and every tag - into a
     * single "·"-joined string that routinely ran to three wrapped lines and
     * made a list of six tasks look like a wall. Almost none of it is what you
     * are scanning the list for.
     *
     * Two facts, at most: *when* (the only urgent one) and *what it pays*, and
     * only when Earn Mode is actually on. Everything else is still one tap
     * away in the editor, which is where you go when you care about it.
     */
    private fun describe(task: FocusTask): String {
        val parts = ArrayList<String>()

        if (task.completed) {
            task.completedAt?.let {
                parts.add(
                    activity.getString(
                        R.string.tasks_describe_done,
                        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(it))
                    )
                )
            }
            return parts.joinToString(" · ")
        }

        // Overdue replaces the date rather than following it: "overdue" is the
        // fact, and repeating a date the person has already missed next to it
        // is just a longer way of saying the same thing.
        if (task.isOverdue) {
            parts.add(activity.getString(R.string.tasks_describe_overdue))
        } else {
            (task.deadline ?: task.dueDate)?.let { due ->
                val formatted = TimeText.dateTime(activity, due)
                parts.add(
                    if (task.deadline != null) {
                        activity.getString(R.string.tasks_describe_due_by, formatted)
                    } else {
                        activity.getString(R.string.tasks_describe_for, formatted)
                    }
                )
            }
        }

        // An ambient task is paid at your rate for time in its apps, never from
        // the per-task reward - it has no session for one to attach to - so
        // quoting that reward here would promise minutes it cannot pay. The
        // accrual line on its own row says what it does pay.
        if (EarnMode.isEnabled(activity) && task.verification != Verification.AMBIENT) {
            when {
                task.enjoyable -> parts.add(activity.getString(R.string.tasks_describe_no_reward))
                task.rewardMin != null -> parts.add(activity.getString(R.string.tasks_describe_reward_min, task.rewardMin))
                else -> task.timeEstimateMin?.let {
                    // Only worth saying when it is the thing that decides the
                    // payout; on its own, an estimate is editor detail.
                    parts.add(activity.getString(R.string.tasks_describe_minutes, it))
                }
            }
        }

        return parts.joinToString(" · ")
    }

    private fun priorityColor(priority: Priority): Int = when (priority) {
        Priority.HIGH -> tokens.danger
        Priority.MED -> tokens.warning
        Priority.LOW -> tokens.accent
        Priority.NONE -> tokens.divider
    }

    // ── Actions ───────────────────────────────────────────────────

    private fun openEditor(task: FocusTask?) {
        activity.startActivity(
            Intent(activity, TaskEditorActivity::class.java).apply {
                if (task != null) putExtra(TaskEditorActivity.EXTRA_TASK_ID, task.id)
            }
        )
    }

    private fun openSession() {
        activity.startActivity(Intent(activity, EarnSessionActivity::class.java))
    }

    /**
     * Starting a task is the moment the deal becomes real, so it states what is
     * about to shut and what is about to stay open before anything changes.
     */
    private fun startTask(task: FocusTask) {
        // An ambient task has nothing to start. It is already counting in the
        // background, and offering it a session would be describing a different
        // feature - one with a start button, a lock and an end.
        if (task.verification == Verification.AMBIENT) return

        val sessionRunning = SessionManager.isActive(activity)
        val standalone = !sessionRunning

        FocusDialog.custom(
            activity,
            title = activity.getString(R.string.tasks_start_title, task.title),
            subtitle = null,
            confirmLabel = activity.getString(R.string.tasks_start_confirm),
            cancelLabel = activity.getString(R.string.tasks_start_cancel),
            onConfirm = {
                EarnSession.start(activity, task, standalone)
                openSession()
            }
        ) { body, dialogTokens, _ ->
            body.addView(
                FocusUi.secondary(
                    activity,
                    dialogTokens,
                    if (standalone) {
                        if (EarnMode.hasHardEnforcement(activity)) {
                            activity.getString(R.string.tasks_start_hard_enforcement)
                        } else {
                            activity.getString(R.string.tasks_start_soft_enforcement)
                        }
                    } else {
                        activity.getString(R.string.tasks_start_linked_session, SessionManager.mode(activity).label(activity).lowercase())
                    }
                )
            )

            body.addView(FocusUi.spacer(activity, 10))
            body.addView(FocusUi.caption(activity, dialogTokens, activity.getString(R.string.tasks_start_open_during_caption)))

            val allowed = EarnSession.allowedPackages(activity, task, standalone)
                .filter { it != activity.packageName }
                .filter { AppCatalog.isInstalled(activity, it) }
            body.addView(
                FocusUi.secondary(
                    activity,
                    dialogTokens,
                    if (allowed.isEmpty()) {
                        activity.getString(R.string.tasks_start_focuslock_only)
                    } else {
                        allowed.joinToString { AppCatalog.label(activity, it) }
                    }
                )
            )

            // The loophole, made visible instead of silent.
            val rejected = EarnSession.rejectedPackages(activity, task, standalone)
            if (rejected.isNotEmpty()) {
                body.addView(FocusUi.spacer(activity, 10))
                val warning = FocusUi.caption(
                    activity,
                    dialogTokens,
                    activity.getString(
                        R.string.tasks_start_rejected_warning,
                        rejected.joinToString { AppCatalog.label(activity, it) }
                    )
                )
                warning.setTextColor(dialogTokens.warning)
                body.addView(warning)
            }
        }
    }
}
