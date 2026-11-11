package com.focuslock.mdm

import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Focus tab: what is happening right now, and the one decision worth making.
 *
 * When a session is running this is a status screen and nothing else. When one
 * is not, it is a chooser with four clearly-described strengths — each stating
 * how hard it is to leave *before* it is picked, which is the single most
 * important honesty in the whole app.
 */
class FocusDashboardTab(activity: MainActivity, tokens: UiPrefs.Tokens) : FocusTab(activity, tokens) {

    private lateinit var container: LinearLayout
    private var ring: CircularProgressIndicator? = null
    private var countdownView: TextView? = null
    private var progressView: TextView? = null

    // The last session's mode and length, not a fixed default. Starting the
    // same kind of session again is the common case, and the picker used to
    // come back as "Block, 2h" every time the app restarted - so someone who
    // always runs Sanctuary for 25 minutes re-picked both, every time, and one
    // missed tap started a session in a mode whose presets they never chose.
    private var selectedMode: FocusMode = SessionManager.lastMode(activity) ?: FocusMode.BLOCK
    private var selectedStrength: Promise.Strength = Promise.strength(activity)
    private var selectedProtects: MutableSet<String> = Promise.protects(activity).toMutableSet()
    private var draftWords: String = Promise.words(activity)
    /** Set by the promise card just before confirmStart; null means "leave the mode's setting". */
    private var pendingNoExit: Boolean? = null
    private var selectedDurationMs: Long =
        FocusStore.getLong(activity, KEY_LAST_DURATION_MS, 0L).takeIf { it > 0L } ?: (2L * 60 * 60 * 1000)

    override fun build(): View {
        container = FocusUi.column(activity, tokens.density.contentPaddingDp)
        val scroll = FocusUi.scroll(activity, container)
        scroll.setPadding(0, 0, 0, FocusUi.dp(activity, 12))
        return hostScroll(scroll, container)
    }

    override fun onShow() {
        render()
    }

    override fun onTick() {
        if (!SessionManager.isActive(activity)) return
        countdownView?.text = SessionManager.formatCountdown(activity, SessionManager.remainingMs(activity))
        val percent = SessionManager.progressPercent(activity)
        progressView?.text = percent.toString() + "%"
        ring?.let { if (it.progress != percent) it.progress = percent }
    }

    // ── Render ────────────────────────────────────────────────────

    private fun render(): Unit = redraw {
        val added = ArrayList<View>()

        fun add(view: View) {
            container.addView(view)
            added.add(view)
        }

        add(buildGreeting())

        setupIssues().takeIf { it.isNotEmpty() }?.let { issues -> add(buildSetupCard(issues)) }

        buildNoticeCard()?.let { add(it) }

        // Quiet Pine: one decision per screen. Idle shows a single start card;
        // a running session shows only its status. The preset shortcuts and
        // the Device Owner upsell moved to Rules and You, where they are a
        // choice rather than a wall in front of the Start button.
        if (SessionManager.isActive(activity)) {
            add(buildActiveSession())
        } else {
            add(buildStarter())
        }

        buildOnATimerCard()?.let { add(it) }

        add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.redesign_while_you_focus)))
        add(buildTasksAndToolsCard())

        if (UiPrefs.showQuickSettings(activity)) {
            add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.dashboard_quick_settings)))
            add(buildQuickSettings())
        }

        Motion.stagger(added, tokens)
    }

    private fun buildTasksAndToolsCard(): View {
        val openTasksCount = FocusTaskStore.all(activity).count { !it.completed }
        val tasks = buildShortcutCard(
            R.drawable.ic_glyph_tasks,
            FocusUi.IconTone.WARM,
            activity.getString(R.string.redesign_tasks_title),
            if (openTasksCount > 0) activity.getString(R.string.redesign_tasks_open, openTasksCount)
            else activity.getString(R.string.redesign_tasks_none)
        ) { activity.selectTab(MainActivity.TAB_TASKS) }
        val tools = buildShortcutCard(
            R.drawable.ic_glyph_library,
            FocusUi.IconTone.ACCENT,
            activity.getString(R.string.redesign_tools_title),
            activity.getString(R.string.redesign_tools_sub)
        ) { activity.selectTab(MainActivity.TAB_LIBRARY) }
        val row = FocusUi.tileRow(activity, listOf(tasks, tools), gapDp = 10)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(activity, tokens.density.gapDp) }
        return row
    }

    /** A half-width card: icon tile, title, one line of state. */
    private fun buildShortcutCard(
        iconRes: Int,
        tone: FocusUi.IconTone,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): View {
        val card = FocusUi.card(activity, tokens, onClick = onClick)
        card.contentDescription = "$title. $subtitle"
        card.addView(FocusUi.iconTile(activity, tokens, iconRes, tone))
        card.addView(FocusUi.spacer(activity, 12))
        card.addView(FocusUi.heading(activity, tokens, title))
        val sub = FocusUi.caption(activity, tokens, subtitle)
        sub.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 2) }
        card.addView(sub)
        return card
    }

    private fun buildGreeting(): View {
        val column = FocusUi.column(activity)
        column.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(activity, tokens.density.gapDp + 4) }

        column.addView(FocusUi.secondary(activity, tokens, dayAndPeriod()))

        val headline = FocusUi.title(activity, tokens, statusHeadline())
        headline.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 2) }
        column.addView(headline)

        val stoppedToday = Streaks.blocksStoppedToday(activity)
        val streakSummary = if (Streaks.isEnabled(activity)) Streaks.summary(activity) else ""
        val sub = when {
            stoppedToday > 0 && streakSummary.isNotBlank() ->
                activity.getString(R.string.intercept_opens_stopped_today, stoppedToday) + " · " + streakSummary
            stoppedToday > 0 ->
                activity.getString(R.string.intercept_opens_stopped_today, stoppedToday)
            else -> streakSummary
        }
        if (sub.isNotBlank()) {
            val subView = FocusUi.secondary(activity, tokens, sub)
            subView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(activity, 6) }
            column.addView(subView)
        }
        return column
    }

    private fun dayAndPeriod(): String {
        val calendar = java.util.Calendar.getInstance()
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val period = when {
            hour < 5 -> activity.getString(R.string.dashboard_period_night)
            hour < 12 -> activity.getString(R.string.dashboard_period_morning)
            hour < 18 -> activity.getString(R.string.dashboard_period_afternoon)
            else -> activity.getString(R.string.dashboard_period_evening)
        }
        return activity.getString(R.string.dashboard_day_period, day, period)
    }

    /** A plain greeting: the running state already has its own pill below. */
    private fun statusHeadline(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return activity.getString(
            when {
                hour < 5 -> R.string.redesign_greeting_night
                hour < 12 -> R.string.redesign_greeting_morning
                hour < 18 -> R.string.redesign_greeting_afternoon
                else -> R.string.redesign_greeting_evening
            }
        )
    }

    // ── Active session ────────────────────────────────────────────

    private fun buildActiveSession(): View {
        val mode = SessionManager.mode(activity)
        if (mode == FocusMode.KIOSK) return buildKioskSession()

        // Quiet Pine running view: no card around it. A status pill, a big
        // countdown ring, one calm line, three numbers, and the way out.
        val column = FocusUi.column(activity)
        column.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(activity, tokens.density.gapDp) }

        val status = FocusUi.row(activity)
        val pill = FocusUi.pill(activity, tokens, activity.getString(R.string.redesign_mode_on, mode.label(activity)), tokens.accent)
        status.addView(pill)
        status.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        status.addView(buildInfoGlyph { showModeDetail(mode) })
        column.addView(status)
        buildPromiseReminder()?.let { column.addView(it) }

        val ringHolder = android.widget.FrameLayout(activity)
        ringHolder.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 8) }
        val indicator = FocusUi.progressRing(activity, tokens, 236)
        indicator.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        ringHolder.addView(indicator)
        ring = indicator

        val centre = FocusUi.column(activity)
        centre.gravity = Gravity.CENTER
        centre.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        val timeLeft = FocusUi.display(activity, tokens, SessionManager.formatCountdown(activity, SessionManager.remainingMs(activity)))
        timeLeft.gravity = Gravity.CENTER
        FocusUi.fitInRing(timeLeft, 236)
        countdownView = timeLeft
        progressView = null
        centre.addView(timeLeft)
        val leftOf = FocusUi.caption(
            activity,
            tokens,
            activity.getString(R.string.redesign_left_of, SessionManager.formatDuration(SessionManager.durationMs(activity)))
        )
        leftOf.gravity = Gravity.CENTER
        leftOf.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 4) }
        centre.addView(leftOf)
        ringHolder.addView(centre)
        column.addView(ringHolder)
        Motion.animateProgress(indicator, SessionManager.progressPercent(activity), tokens)

        val stay = FocusUi.title(activity, tokens, activity.getString(R.string.redesign_stay_with_it))
        stay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(22f))
        stay.gravity = Gravity.CENTER
        stay.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 18) }
        column.addView(stay)
        // Gentle never holds an app shut, so it must not promise that it will.
        val holdsShut = CapabilityRegistry.isEnabled(activity, Capabilities.HARD_BLOCK)
        val ends = FocusUi.caption(
            activity,
            tokens,
            activity.getString(
                if (holdsShut) R.string.redesign_ends_at_line else R.string.redesign_ends_at_line_gentle,
                TimeText.dateTime(activity, SessionManager.endsAt(activity))
            )
        )
        ends.gravity = Gravity.CENTER
        ends.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 4) }
        column.addView(ends)

        column.addView(FocusUi.spacer(activity, 18))
        column.addView(
            FocusUi.tileRow(
                activity,
                listOf(
                    FocusUi.statTile(activity, tokens, Streaks.blocksStoppedToday(activity).toString(), activity.getString(if (holdsShut) R.string.redesign_opens_stopped else R.string.redesign_nudges_given)),
                    FocusUi.statTile(activity, tokens, SessionManager.formatDuration(SessionManager.elapsedMs(activity)), activity.getString(R.string.redesign_focused_so_far)),
                    FocusUi.statTile(activity, tokens, AppRules.alwaysAllowed(activity).count { AppCatalog.isInstalled(activity, it) }.toString(), activity.getString(R.string.redesign_apps_open))
                ),
                gapDp = 10
            )
        )

        val passes = TakeABreak.activePasses(activity)
        if (passes.isNotEmpty()) {
            column.addView(FocusUi.spacer(activity, 12))
            val card = FocusUi.card(activity, tokens)
            passes.forEach { entry ->
                card.addView(
                    FocusUi.listRow(
                        activity,
                        tokens,
                        AppCatalog.label(activity, entry.key),
                        activity.getString(R.string.dashboard_break_ends_in, SessionManager.formatDuration(entry.value)),
                        trailing = FocusUi.smallButton(activity, tokens, activity.getString(R.string.dashboard_end_now)) {
                            TakeABreak.endEarly(activity, entry.key)
                            render()
                        },
                        leading = FocusUi.appIcon(activity, tokens, entry.key, 32)
                    )
                )
            }
            column.addView(card)
        }

        column.addView(FocusUi.spacer(activity, 14))
        if (SessionManager.canEndEarly(activity)) {
            // Outlined, so it reads as a button and not as a line of text.
            val end = FocusUi.secondaryButton(activity, tokens, activity.getString(R.string.redesign_end_early)) { confirmEndSession() }
            end.setTextColor(tokens.textSecondary)
            column.addView(end)
            val counts = FocusUi.caption(activity, tokens, activity.getString(R.string.redesign_time_counts))
            counts.gravity = Gravity.CENTER
            column.addView(counts)
        } else {
            val note = FocusUi.caption(
                activity,
                tokens,
                if (mode.isHard) activity.getString(R.string.dashboard_hard_mode_body)
                else activity.getString(R.string.dashboard_early_exit_off_body)
            )
            note.gravity = Gravity.CENTER
            column.addView(note)
        }
        return column
    }

    /** Their own words, and their picture if they added one, while a promise is held. */
    private fun buildPromiseReminder(): View? {
        val words = Promise.words(activity)
        val media = Promise.mediaView(activity, 150)
        if (words.isBlank() && media == null) return null
        val box = FocusUi.column(activity)
        box.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 12) }
        media?.let { box.addView(it) }
        if (words.isNotBlank()) {
            val line = FocusUi.heading(activity, tokens, "\u201C" + words + "\u201D")
            line.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(activity, if (media != null) 10 else 0) }
            box.addView(line)
        }
        return box
    }

    private fun buildKioskSession(): View {
        val card = FocusUi.card(activity, tokens)
        val mode = SessionManager.mode(activity)
        val kiosk = mode == FocusMode.KIOSK

        if (!kiosk) {
            val header = FocusUi.row(activity)
            val modeName = FocusUi.heading(activity, tokens, activity.getString(R.string.mode_name_suffix, mode.label(activity)))
            modeName.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            header.addView(modeName)
            header.addView(FocusUi.pill(activity, tokens, activity.getString(R.string.dashboard_running_pill), tokens.success))
            card.addView(header)
            card.addView(FocusUi.spacer(activity, 18))
        }

        val ringHolder = android.widget.FrameLayout(activity)
        ringHolder.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val indicator = FocusUi.progressRing(activity, tokens, 208)
        indicator.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        ringHolder.addView(indicator)
        ring = indicator

        val centre = FocusUi.column(activity)
        centre.gravity = Gravity.CENTER
        centre.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

        // Kiosk's ring centre is the design doc's "1:42 / HOURS LEFT" - the
        // countdown itself, since there is nothing to end early and a percent
        // answers a question ("how far along") nobody pinned to the phone is
        // asking. Every other mode keeps the percent, which still doubles as
        // the tick source for onTick() below either way.
        if (kiosk) {
            val timeLeft = FocusUi.display(activity, tokens, SessionManager.formatCountdown(activity, SessionManager.remainingMs(activity)))
            timeLeft.gravity = Gravity.CENTER
            FocusUi.fitInRing(timeLeft, 208)
            countdownView = timeLeft

            val label = FocusUi.caption(activity, tokens, activity.getString(R.string.dashboard_time_left))
            FocusUi.applyFont(label, tokens, mono = true, weight = 600)
            label.gravity = Gravity.CENTER
            label.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(activity, 6) }

            centre.addView(timeLeft)
            centre.addView(label)
        } else {
            val percentView = FocusUi.display(activity, tokens, SessionManager.progressPercent(activity).toString() + "%")
            percentView.gravity = Gravity.CENTER
            percentView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            progressView = percentView

            val remainingView = FocusUi.caption(activity, tokens, activity.getString(R.string.dashboard_of_the_way_through))
            remainingView.gravity = Gravity.CENTER
            remainingView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            centre.addView(percentView)
            centre.addView(remainingView)
        }
        ringHolder.addView(centre)
        card.addView(ringHolder)

        Motion.animateProgress(indicator, SessionManager.progressPercent(activity), tokens)

        card.addView(FocusUi.spacer(activity, 18))

        if (kiosk) {
            // Design doc: activity.getString(R.string.dashboard_kiosk_holding) - a fact, not a settings-style
            // label, and the one line under it that matters most in the
            // moment: you did not lose your grip, nothing here needed it.
            val headline = FocusUi.heading(activity, tokens, activity.getString(R.string.dashboard_kiosk_holding))
            headline.gravity = Gravity.CENTER
            card.addView(headline)
            card.addView(FocusUi.spacer(activity, 6))
            val body = FocusUi.secondary(
                activity,
                tokens,
                activity.getString(R.string.dashboard_kiosk_holding_body)
            )
            body.gravity = Gravity.CENTER
            card.addView(body)

            card.addView(FocusUi.spacer(activity, 18))
            card.addView(
                FocusUi.tileRow(
                    activity,
                    listOf(
                        FocusUi.statTile(activity, tokens, AppRules.kioskAllowlist(activity).size.toString(), activity.getString(R.string.dashboard_apps_still_allowed)),
                        FocusUi.statTile(activity, tokens, SessionManager.formatDuration(SessionManager.elapsedMs(activity)), activity.getString(R.string.dashboard_focus_so_far))
                    )
                )
            )

            card.addView(FocusUi.spacer(activity, 12))
            card.addView(
                FocusUi.tileRow(
                    activity,
                    listOf(
                        FocusUi.secondaryButton(activity, tokens, activity.getString(R.string.tab_library)) {
                            activity.selectTab(MainActivity.TAB_LIBRARY)
                        },
                        FocusUi.secondaryButton(activity, tokens, activity.getString(R.string.tab_tasks)) {
                            activity.selectTab(MainActivity.TAB_TASKS)
                        }
                    )
                )
            )
        } else {
            val countdown = FocusUi.heading(
                activity,
                tokens,
                SessionManager.formatCountdown(activity, SessionManager.remainingMs(activity))
            )
            countdown.gravity = Gravity.CENTER
            countdownView = countdown
            card.addView(countdown)

            val ends = FocusUi.caption(
                activity,
                tokens,
                activity.getString(R.string.dashboard_ends_at, TimeText.dateTime(activity, (SessionManager.endsAt(activity))))
            )
            ends.gravity = Gravity.CENTER
            ends.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(activity, 6) }
            card.addView(ends)
        }

        card.addView(FocusUi.spacer(activity, 20))

        if (SessionManager.canEndEarly(activity)) {
            card.addView(
                FocusUi.secondaryButton(activity, tokens, activity.getString(R.string.dashboard_end_session)) { confirmEndSession() }
            )
        } else {
            // Not mode.exitLine any more: that is the mode's default, and the
            // person may have changed it. Say what is true of this session.
            val note = FocusUi.secondary(
                activity,
                tokens,
                if (mode.isHard) {
                    activity.getString(R.string.dashboard_hard_mode_body)
                } else {
                    activity.getString(R.string.dashboard_early_exit_off_body)
                }
            )
            note.gravity = Gravity.CENTER
            card.addView(note)
        }

        val passes = TakeABreak.activePasses(activity)
        if (passes.isNotEmpty()) {
            card.addView(FocusUi.spacer(activity, 14))
            card.addView(FocusUi.divider(activity, tokens))
            passes.forEach { entry ->
                card.addView(
                    FocusUi.listRow(
                        activity,
                        tokens,
                        AppCatalog.label(activity, entry.key),
                        activity.getString(R.string.dashboard_break_ends_in, SessionManager.formatDuration(entry.value)),
                        trailing = FocusUi.smallButton(activity, tokens, activity.getString(R.string.dashboard_end_now)) {
                            TakeABreak.endEarly(activity, entry.key)
                            render()
                        },
                        leading = FocusUi.appIcon(activity, tokens, entry.key, 30)
                    )
                )
            }
        }

        return card
    }

    private fun confirmEndSession() {
        FocusDialog.alert(
            activity,
            title = activity.getString(R.string.dashboard_end_confirm_title),
            message = activity.getString(R.string.dashboard_end_confirm_message),
            confirmLabel = activity.getString(R.string.dashboard_end_confirm_button),
            cancelLabel = activity.getString(R.string.dashboard_end_keep_button),
            onConfirm = {
                SessionManager.end(activity)
                // Critical: release lock task here, not on the next onResume.
                // Before Kiosk could be ended early this never mattered, because
                // the only modes with an End button were never pinned. Now that
                // Kiosk's early exit is a switch, an un-released lock task would
                // leave the phone pinned to FocusLock with no session running
                // and no way out short of a factory reset. onResume will not
                // save us: the activity is already resumed, and a pinned screen
                // cannot be left to trigger another one.
                KioskPolicy.syncLockTaskState(activity)
                FocusDialog.toast(activity, Copy.sessionEndedEarly(activity))
                render()
            }
        )
    }

    // ── Starting a session ────────────────────────────────────────

    /**
     * The promise card: what you are protecting, how firm, how long, and why in your own
     * words. Three strengths stand in for the four modes; the modes and their switches are
     * still there under You > Advanced for anyone who wants them.
     */
    private fun buildStarter(): View {
        val card = FocusUi.card(activity, tokens)
        fun label(res: Int): View {
            val v = FocusUi.caption(activity, tokens, activity.getString(res))
            FocusUi.applyFont(v, tokens, weight = 650)
            v.setTextColor(tokens.textSecondary)
            return v
        }
        fun flow(): com.google.android.material.chip.ChipGroup {
            val g = com.google.android.material.chip.ChipGroup(activity)
            g.chipSpacingHorizontal = FocusUi.dp(activity, 8)
            g.chipSpacingVertical = FocusUi.dp(activity, 8)
            g.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            return g
        }

        card.addView(FocusUi.heading(activity, tokens, activity.getString(R.string.promise_title)))
        card.addView(FocusUi.spacer(activity, 12))
        val protects = flow()
        Promise.PROTECTS.forEach { (id, res) ->
            val chip = FocusUi.chip(activity, tokens, activity.getString(res), id in selectedProtects) {
                if (!selectedProtects.remove(id)) selectedProtects.add(id)
                render()
            }
            (chip.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
            protects.addView(chip)
        }
        card.addView(protects)

        card.addView(FocusUi.spacer(activity, 18))
        card.addView(label(R.string.promise_how_firm))
        card.addView(FocusUi.spacer(activity, 8))
        Promise.Strength.values().forEach { card.addView(buildStrengthOption(it)) }

        card.addView(FocusUi.spacer(activity, 10))
        card.addView(label(R.string.redesign_for_how_long))
        card.addView(FocusUi.spacer(activity, 8))
        card.addView(buildDurationPicker())

        card.addView(FocusUi.spacer(activity, 18))
        val words = FocusUi.input(activity, tokens, activity.getString(R.string.promise_words_hint), draftWords)
        words.filters = arrayOf(android.text.InputFilter.LengthFilter(Promise.MAX_WORDS))
        words.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { draftWords = s?.toString().orEmpty() }
        })
        card.addView(words)

        card.addView(FocusUi.spacer(activity, 10))
        Promise.mediaView(activity, 150)?.let { card.addView(it); card.addView(FocusUi.spacer(activity, 8)) }
        val mediaRow = FocusUi.row(activity)
        mediaRow.addView(
            FocusUi.ghostButton(
                activity, tokens,
                activity.getString(if (Promise.hasMedia(activity)) R.string.promise_change_media else R.string.promise_add_media)
            ) { activity.startActivity(Intent(activity, PromisePickActivity::class.java)) }
        )
        card.addView(mediaRow)
        if (Promise.hasMedia(activity)) {
            // Its own row: the buttons are full width, so a second one beside the first is pushed off screen.
            card.addView(
                FocusUi.ghostButton(activity, tokens, activity.getString(R.string.promise_remove_media)) {
                    Promise.clearMedia(activity)
                    render()
                }
            )
        }

        card.addView(FocusUi.spacer(activity, 16))
        val canStart = DemoMode.hasSessionLeft(activity)
        card.addView(
            FocusUi.primaryButton(
                activity, tokens,
                activity.getString(if (canStart) R.string.promise_make else R.string.demo_start_used_up)
            ) { makePromise() }
        )
        return card
    }

    private fun buildStrengthOption(strength: Promise.Strength): View {
        val selected = strength == selectedStrength
        val locked = DemoMode.isModeLocked(activity, strength.mode)
        val option = FocusUi.column(activity)
        val pad = FocusUi.dp(activity, 14)
        option.setPadding(pad, pad, pad, pad)
        option.background = FocusUi.roundedShape(
            activity,
            if (selected) tokens.surfaceAlt else tokens.surface,
            18,
            if (selected) tokens.accent else tokens.divider,
            if (selected) 2 else 1
        )
        option.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(activity, 8) }
        val name = FocusUi.rowTitle(
            activity, tokens,
            activity.getString(strength.labelRes) + if (locked) "  ·  " + activity.getString(R.string.pro_badge) else ""
        )
        FocusUi.applyDisplayFont(name, tokens)
        option.addView(name)
        option.addView(FocusUi.caption(activity, tokens, activity.getString(strength.lineRes)))
        option.isClickable = true
        option.isFocusable = true
        option.contentDescription = activity.getString(strength.labelRes) + ". " + activity.getString(strength.lineRes) +
            if (selected) ", " + activity.getString(R.string.common_selected) else ""
        option.setOnClickListener {
            if (locked) {
                DemoMode.showUpgradeDialog(activity, activity.getString(strength.labelRes)) {
                    selectedStrength = strength
                    render()
                }
            } else {
                selectedStrength = strength
                render()
            }
        }
        return option
    }

    /** Saves the promise as written, then hands over to the usual confirm-and-start. */
    private fun makePromise() {
        Promise.setWords(activity, draftWords)
        Promise.setStrength(activity, selectedStrength)
        Promise.setProtects(activity, selectedProtects)
        selectedMode = selectedStrength.mode
        pendingNoExit = selectedStrength.noExit
        confirmStart()
    }

    /**
     * The strength ladder: four equal tiles in one row, Gentle to Kiosk. Each
     * shows rising bars, so strength reads by height and fill, not colour.
     * A Pro step carries a small lock and opens the Pro sheet when tapped.
     */
    private fun buildModeLadder(available: List<FocusMode>): View {
        val tiles = available.map { buildModeTile(it) }
        return FocusUi.tileRow(activity, tiles, gapDp = 8)
    }

    private fun buildModeTile(mode: FocusMode): View {
        val locked = DemoMode.isModeLocked(activity, mode)
        val selected = !locked && mode == selectedMode
        val fg = if (selected) tokens.onAccent else tokens.textSecondary

        val tile = android.widget.FrameLayout(activity)
        tile.background = FocusUi.withRipple(
            activity,
            FocusUi.roundedShape(activity, if (selected) tokens.accent else tokens.surfaceAlt, 18),
            tokens
        )
        tile.isClickable = true
        tile.isFocusable = true
        tile.minimumHeight = FocusUi.dp(activity, 72)
        tile.contentDescription = mode.label(activity) +
            (if (locked) ", " + activity.getString(R.string.pro_badge) else "") +
            (if (selected) ", " + activity.getString(R.string.common_selected) else "")
        tile.setOnClickListener {
            if (locked) {
                DemoMode.showUpgradeDialog(activity, mode.label(activity)) {
                    selectedMode = mode
                    render()
                }
            } else {
                selectedMode = mode
                render()
            }
        }

        val column = FocusUi.column(activity)
        column.gravity = Gravity.CENTER
        column.setPadding(FocusUi.dp(activity, 4), FocusUi.dp(activity, 13), FocusUi.dp(activity, 4), FocusUi.dp(activity, 11))
        column.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        column.addView(FocusUi.strengthBars(activity, fg, mode.strength))
        val label = TextView(activity)
        label.text = mode.label(activity)
        label.gravity = Gravity.CENTER
        label.isSingleLine = true
        label.setTextColor(fg)
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(13.5f))
        FocusUi.applyFont(label, tokens, weight = 650)
        FocusUi.fitOneLine(label, 10f)
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 8) }
        column.addView(label)
        tile.addView(column)

        if (locked) {
            val lock = FocusUi.categoryIcon(activity, tokens, R.drawable.ic_glyph_kiosk, tokens.textMuted, 13)
            lock.layoutParams = android.widget.FrameLayout.LayoutParams(
                FocusUi.dp(activity, 13),
                FocusUi.dp(activity, 13)
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = FocusUi.dp(activity, 7)
                marginEnd = FocusUi.dp(activity, 7)
            }
            tile.addView(lock)
        }
        return tile
    }

    /**
     * What the chosen strength will do, in one tinted sentence, with the
     * details a tap away. Replaces the four-card wall of explanations.
     */
    private fun buildModeSummaryLine(): View {
        val row = FocusUi.row(activity)
        row.setPadding(FocusUi.dp(activity, 14), FocusUi.dp(activity, 4), FocusUi.dp(activity, 4), FocusUi.dp(activity, 4))
        row.background = FocusUi.withRipple(
            activity,
            FocusUi.roundedShape(activity, tokens.accentSoft, 16),
            tokens
        )
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = activity.getString(R.string.redesign_more_info)
        row.setOnClickListener { showModeDetail(selectedMode) }

        val text = FocusUi.caption(activity, tokens, selectedMode.oneLiner(activity))
        text.setTextColor(tokens.accentDeep)
        text.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(text)
        row.addView(buildInfoGlyph { showModeDetail(selectedMode) })
        return row
    }

    private fun buildInfoGlyph(onClick: () -> Unit): View {
        val view = TextView(activity)
        view.text = "ⓘ"
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(15f))
        view.setTextColor(tokens.accentDeep)
        view.isClickable = true
        view.isFocusable = true
        view.minWidth = FocusUi.dp(activity, 44)
        view.minHeight = FocusUi.dp(activity, 44)
        view.gravity = android.view.Gravity.CENTER
        view.contentDescription = activity.getString(R.string.a11y_mode_details)
        view.setOnClickListener { onClick() }
        return view
    }

    private fun showModeDetail(mode: FocusMode) {
        FocusDialog.custom(
            activity,
            title = mode.label(activity),
            subtitle = mode.oneLiner(activity),
            confirmLabel = null,
            cancelLabel = activity.getString(R.string.common_close)
        ) { body, dialogTokens, dismiss ->
            body.addView(FocusUi.body(activity, dialogTokens, mode.exitLine(activity)))
            body.addView(FocusUi.spacer(activity, 16))
            body.addView(FocusUi.caption(activity, dialogTokens, activity.getString(R.string.dashboard_what_it_changes)))
            val preview = FocusUi.secondary(activity, dialogTokens, mode.presetSummary(activity))
            preview.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(activity, 6) }
            body.addView(preview)

            if (!SessionManager.matchesPreset(activity, mode)) {
                body.addView(FocusUi.spacer(activity, 10))
                body.addView(
                    FocusUi.caption(
                        activity,
                        dialogTokens,
                        activity.getString(R.string.dashboard_preset_edited_note)
                    )
                )
                // The "· edited" marker already told the person their flags had
                // drifted from the template; until now it was a dead end, and
                // the only way back was to find each switch again by hand.
                body.addView(FocusUi.spacer(activity, 10))
                body.addView(
                    FocusUi.secondaryButton(activity, dialogTokens, activity.getString(R.string.dashboard_put_template_back)) {
                        if (SessionManager.resetToPreset(activity, mode)) {
                            dismiss()
                            render()
                        } else {
                            FocusDialog.toast(activity, Copy.rulesFrozen(activity, SessionManager.formatRemaining(activity)))
                        }
                    }
                )
            }

            if (mode.isHard && !SetupChecks.isDeviceOwner(activity)) {
                body.addView(FocusUi.spacer(activity, 12))
                body.addView(FocusUi.pill(activity, dialogTokens, activity.getString(R.string.dashboard_needs_device_owner), dialogTokens.warning))
            }

            // Kiosk's model inversion, which [RuleEngine.decide] reads on every
            // tick but nothing has ever offered a switch for. Without this the
            // setting existed, was exported and imported with a profile, and
            // could not be changed by the person it belongs to.
            if (mode == FocusMode.KIOSK) {
                body.addView(FocusUi.spacer(activity, 16))
                body.addView(FocusUi.caption(activity, dialogTokens, activity.getString(R.string.dashboard_what_kiosk_allows)))
                body.addView(FocusUi.spacer(activity, 8))
                val allowlistMode = AppRules.isKioskAllowlistMode(activity)
                // Only the loosening direction waits for the session to end, so
                // the row is frozen on that direction alone - turning the
                // inversion *on* blocks more and always goes through.
                val frozen = !SessionLock.allows(
                    activity,
                    if (allowlistMode) EditDirection.LOOSEN else EditDirection.TIGHTEN
                )
                body.addView(
                    FocusUi.toggleRow(
                        activity,
                        dialogTokens,
                        activity.getString(R.string.dashboard_kiosk_allowlist_title),
                        if (allowlistMode) {
                            activity.getString(R.string.dashboard_kiosk_allowlist_on)
                        } else {
                            activity.getString(R.string.dashboard_kiosk_allowlist_off)
                        },
                        allowlistMode,
                        frozen = frozen,
                        onChange = { next ->
                            if (AppRules.setKioskAllowlistMode(activity, next)) {
                                dismiss()
                                render()
                            } else {
                                FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                            }
                        }
                    )
                )
            }
        }
    }

    private val quickDurations = listOf(
        "25m" to 25L * 60 * 1000,
        "1h" to 60L * 60 * 1000,
        "2h" to 2L * 60 * 60 * 1000,
        "4h" to 4L * 60 * 60 * 1000
    )

    private val longerDurations = listOf(
        Triple("today", activity.getString(R.string.common_today), 8L * 60 * 60 * 1000L),
        Triple("1day", activity.getString(R.string.dashboard_duration_day), 24L * 60 * 60 * 1000L),
        Triple("1week", activity.getString(R.string.dashboard_duration_week), 7L * 24 * 60 * 60 * 1000L),
        Triple("30days", activity.getString(R.string.dashboard_duration_30_days), 30L * 24 * 60 * 60 * 1000L),
        Triple("90days", activity.getString(R.string.dashboard_duration_90_days), 90L * 24 * 60 * 60 * 1000L)
    )

    /**
     * Five equal segments, per the design doc (25m/1h/2h/4h/Set) - not the
     * old nine-item strip that scrolled sideways off the first screenful.
     * activity.getString(R.string.common_set) covers everything the strip used to: it opens a chooser with the
     * same longer presets plus any number of hours, and shows whichever of
     * those is picked in its own place instead of a fixed label.
     */
    private fun buildDurationPicker(): View {
        // Wrapping chips, not a scrolling strip: every choice is visible at once.
        val flow = com.google.android.material.chip.ChipGroup(activity)
        flow.chipSpacingHorizontal = FocusUi.dp(activity, 8)
        flow.chipSpacingVertical = FocusUi.dp(activity, 8)
        flow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        quickDurations.forEach { (_, ms) ->
            val chip = FocusUi.chip(activity, tokens, SessionManager.formatDuration(ms), selectedDurationMs == ms) {
                selectedDurationMs = ms
                render()
            }
            (chip.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
            flow.addView(chip)
        }
        val isCustom = quickDurations.none { it.second == selectedDurationMs }
        val other = FocusUi.chip(
            activity,
            tokens,
            if (isCustom) SessionManager.formatDuration(selectedDurationMs) else "+ " + activity.getString(R.string.redesign_other_duration),
            isCustom
        ) { openDurationChooser() }
        (other.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
        flow.addView(other)
        return flow
    }

    private fun openDurationChooser() {
        val choices = longerDurations.map { (key, label, _) -> FocusDialog.Choice(key, label) } +
            FocusDialog.Choice("custom", "Custom", activity.getString(R.string.dashboard_hours_hint_body))
        val currentKey = longerDurations.firstOrNull { it.third == selectedDurationMs }?.first
        FocusDialog.singleChoice(activity, activity.getString(R.string.dashboard_hours_title), null, choices, currentKey) { key ->
            if (key == "custom") {
                askCustomDuration()
            } else {
                longerDurations.firstOrNull { it.first == key }?.let { (_, _, ms) ->
                    selectedDurationMs = ms
                    render()
                }
            }
        }
    }

    private fun askCustomDuration() {
        FocusDialog.textInput(
            activity,
            title = activity.getString(R.string.dashboard_hours_confirm_title),
            subtitle = activity.getString(R.string.dashboard_hours_confirm_subtitle),
            hint = activity.getString(R.string.dashboard_hours_hint),
            numeric = true,
            confirmLabel = activity.getString(R.string.common_set)
        ) { value ->
            val hours = value.toLongOrNull() ?: 0L
            if (hours <= 0L) {
                FocusDialog.toast(activity, activity.getString(R.string.dashboard_hours_invalid))
                return@textInput
            }
            selectedDurationMs = hours * 60L * 60L * 1000L
            render()
        }
    }

    private fun buildKioskOptionsLink(): View {
        val card = FocusUi.card(activity, tokens)
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.dashboard_kiosk_options_title),
                activity.getString(R.string.dashboard_kiosk_options_subtitle),
                trailing = FocusUi.chevron(activity, tokens)
            ) { openKioskOptionsSheet() }
        )
        return card
    }

    private fun openKioskOptionsSheet() {
        FocusDialog.custom(
            activity,
            title = activity.getString(R.string.dashboard_kiosk_options_title),
            subtitle = null,
            confirmLabel = null,
            cancelLabel = activity.getString(R.string.common_done)
        ) { body, dialogTokens, _ ->
            body.addView(
                FocusUi.toggleRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.dashboard_kiosk_surface_title),
                    activity.getString(R.string.dashboard_kiosk_surface_subtitle),
                    CapabilityRegistry.getBoolParam(activity, Capabilities.KIOSK_MODE, "fullScreenSurface", false)
                ) { value ->
                    if (!CapabilityRegistry.setBoolParam(activity, Capabilities.KIOSK_MODE, "fullScreenSurface", value)) {
                        FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                    }
                }
            )

            body.addView(
                FocusUi.toggleRow(
                    activity,
                    dialogTokens,
                    activity.getString(R.string.dashboard_release_owner_title),
                    activity.getString(R.string.dashboard_release_owner_subtitle),
                    SessionManager.releasesOwnerOnEnd(activity)
                ) { value ->
                    SessionManager.setReleasesOwnerOnEnd(activity, value)
                }
            )
        }
    }

    private fun confirmStart() {
        val mode = selectedMode

        if (DemoMode.isModeLocked(activity, mode)) {
            DemoMode.showUpgradeDialog(activity, mode.label(activity)) {
                selectedMode = mode
                render()
            }
            return
        }

        if (!DemoMode.hasSessionLeft(activity)) {
            FocusDialog.alert(
                activity,
                title = activity.getString(R.string.dashboard_demo_used_title),
                message = activity.getString(R.string.dashboard_demo_used_message),
                confirmLabel = activity.getString(R.string.common_got_it),
                cancelLabel = null,
                onConfirm = {}
            )
            return
        }

        if (mode.isHard && !SetupChecks.isDeviceOwner(activity)) {
            FocusDialog.alert(
                activity,
                title = activity.getString(R.string.dashboard_kiosk_needs_step_title),
                message = activity.getString(R.string.dashboard_kiosk_needs_step_message),
                confirmLabel = activity.getString(R.string.dashboard_show_me_how),
                cancelLabel = activity.getString(R.string.common_not_now),
                onConfirm = {
                    activity.startActivity(Intent(activity, DeviceOwnerHelpActivity::class.java))
                }
            )
            return
        }

        // The preset is about to be loaded, so describe what the session will
        // actually do — not what the mode does by default. Someone who turned
        // the early exit back on in Advanced must not be told it is impossible.
        // Read-only: the template is loaded by SessionManager.start, on confirm.
        // Working it out by actually applying it here would change the person's
        // settings just because they opened a dialog and then cancelled.
        // A promise states its own exit (Sealed has none), and that wins over the mode's default.
        val willEndEarly = Promise.endsEarly(pendingNoExit, if (SessionManager.presetPending(activity, mode)) {
            mode.preset()[Capabilities.CAN_END_EARLY] == true
        } else {
            SessionManager.canEndEarly(activity)
        })

        // A first session with no way out is held to an hour (SessionManager.start
        // enforces it); say so here so the title never promises a longer one.
        val capped = SessionManager.firstSessionCapApplies(activity, mode, selectedDurationMs, pendingNoExit)
        val shownDurationMs = if (capped) SessionManager.FIRST_NO_EXIT_CAP_MS else selectedDurationMs

        val exitLine = if (willEndEarly) {
            activity.getString(R.string.dashboard_can_end_early_line)
        } else {
            activity.getString(R.string.dashboard_no_early_exit_line, SessionManager.formatDuration(shownDurationMs))
        }

        val message = mode.oneLiner(activity) + "\n\n" + exitLine +
            (if (capped) "\n\n" + activity.getString(R.string.redesign_first_session_cap) else "")

        FocusDialog.alert(
            activity,
            title = activity.getString(R.string.promise_confirm_title, mode.label(activity), SessionManager.formatDuration(shownDurationMs)),
            message = message,
            confirmLabel = activity.getString(R.string.promise_confirm_yes),
            cancelLabel = activity.getString(R.string.promise_confirm_no),
            onConfirm = {
                if (SessionManager.start(activity, mode, selectedDurationMs, pendingNoExit)) {
                    // Its own key: the session's duration is cleared when it
                    // ends and grows when it is extended, and neither is what
                    // the person picked.
                    FocusStore.setLong(activity, KEY_LAST_DURATION_MS, selectedDurationMs)
                    FocusDialog.toast(activity, Copy.sessionStarted(activity, mode))
                } else {
                    FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                }
                render()
            }
        )
    }

    // ── Setup, context, stats ─────────────────────────────────────

    private data class SetupIssue(val label: String, val detail: String, val intents: List<Intent>)

    /**
     * Missing permissions are shown as a calm checklist that the user taps when
     * ready. The old build fired a toast and threw them into Settings on every
     * resume, which is disorienting and easy to mistake for a crash.
     */
    private fun setupIssues(): List<SetupIssue> {
        val issues = ArrayList<SetupIssue>()

        if (!SetupChecks.hasUsageAccess(activity)) {
            issues.add(
                SetupIssue(
                    activity.getString(R.string.setup_issue_usage_access),
                    activity.getString(R.string.setup_issue_usage_access_detail),
                    PermissionIntents.usageAccess(activity)
                )
            )
        }
        if (needsAccessibility() && !SetupChecks.isContentGuardEnabled(activity)) {
            issues.add(
                SetupIssue(
                    activity.getString(R.string.setup_issue_content_guard),
                    activity.getString(R.string.setup_issue_content_guard_detail),
                    PermissionIntents.accessibility(activity)
                )
            )
        }
        if (CapabilityRegistry.isEnabled(activity, Capabilities.NOTIFICATION_BLOCK) &&
            !SetupChecks.isNotificationAccessGranted(activity)
        ) {
            issues.add(
                SetupIssue(
                    activity.getString(R.string.setup_issue_notification_access),
                    activity.getString(R.string.setup_issue_notification_access_detail),
                    PermissionIntents.notificationAccess(activity)
                )
            )
        }
        return issues
    }

    private fun needsAccessibility(): Boolean = listOf(
        Capabilities.CONTENT_GUARD,
        Capabilities.KEYWORD_BLOCK,
        Capabilities.SHORTS_BLOCK,
        Capabilities.REELS_BLOCK,
        Capabilities.ADULT_BLOCK,
        Capabilities.WHATSAPP_GUARD,
        Capabilities.TELEGRAM_GUARD
    ).any { CapabilityRegistry.isEnabled(activity, it) }

    /**
     * One quiet card for the two things the app has to tell someone unprompted:
     * that it crashed since they last looked, or that a newer version is known.
     *
     * The crash is offered as a report the person can send, never sent for
     * them. The update is only shown between sessions: during one, the browser
     * may be held to the allowlist, and the card would lead nowhere.
     */
    private fun buildNoticeCard(): View? {
        val crashAt = Diagnostics.unacknowledgedCrashAt(activity)
        val update = if (SessionManager.isActive(activity)) null else UpdateCheck.knownNewer(activity)
        if (crashAt == null && update == null) return null

        val card = FocusUi.card(activity, tokens)
        if (crashAt != null) {
            card.addView(FocusUi.heading(activity, tokens, activity.getString(R.string.diag_crash_card_title)))
            card.addView(FocusUi.spacer(activity, 4))
            card.addView(
                FocusUi.secondary(
                    activity,
                    tokens,
                    activity.getString(R.string.diag_crash_card_body, TimeText.dateTime(activity, crashAt))
                )
            )
            card.addView(FocusUi.spacer(activity, 12))
            card.addView(
                FocusUi.primaryButton(activity, tokens, activity.getString(R.string.diag_crash_card_send)) {
                    YouTab.confirmReport(activity)
                    render()
                }
            )
            card.addView(
                FocusUi.ghostButton(activity, tokens, activity.getString(R.string.diag_crash_card_dismiss)) {
                    Diagnostics.acknowledgeCrashes(activity)
                    render()
                }
            )
            return card
        }

        val release = update ?: return null
        card.addView(FocusUi.heading(activity, tokens, activity.getString(R.string.update_available_title, release.versionName)))
        card.addView(FocusUi.spacer(activity, 4))
        card.addView(
            FocusUi.secondary(
                activity,
                tokens,
                release.notes.ifBlank { activity.getString(R.string.update_available_message) }
            )
        )
        card.addView(FocusUi.spacer(activity, 12))
        card.addView(
            FocusUi.primaryButton(activity, tokens, activity.getString(R.string.update_get_it)) {
                YouTab.showUpdate(activity, release)
            }
        )
        return card
    }

    private fun buildSetupCard(issues: List<SetupIssue>): View {
        val card = FocusUi.card(activity, tokens, elevated = true)

        val header = FocusUi.row(activity)
        val title = FocusUi.heading(activity, tokens, activity.getString(R.string.dashboard_finish_setup))
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(title)
        header.addView(FocusUi.pill(activity, tokens, activity.getString(R.string.common_count_left, java.text.NumberFormat.getInstance().format(issues.size)), tokens.warning))
        card.addView(header)

        card.addView(FocusUi.spacer(activity, 4))
        card.addView(
            FocusUi.secondary(
                activity,
                tokens,
                activity.getString(R.string.dashboard_setup_body)
            )
        )
        card.addView(FocusUi.spacer(activity, 6))

        issues.forEach { issue ->
            card.addView(
                FocusUi.listRow(
                    activity,
                    tokens,
                    issue.label,
                    issue.detail,
                    trailing = FocusUi.smallButton(activity, tokens, activity.getString(R.string.dashboard_grant)) { openSetting(issue.intents) }
                ) { openSetting(issue.intents) }
            )
        }

        card.addView(FocusUi.spacer(activity, 6))
        card.addView(
            FocusUi.ghostButton(activity, tokens, activity.getString(R.string.dashboard_setup_guide)) {
                activity.startActivity(Intent(activity, SetupPermissionsActivity::class.java))
            }
        )
        return card
    }

    private fun openSetting(intents: List<Intent>) {
        if (intents.isEmpty()) return
        LockManager.allowSettingsUntil(activity, System.currentTimeMillis() + 2 * 60 * 1000)
        if (!PermissionIntents.launchFirst(activity, intents)) {
            FocusDialog.toast(activity, activity.getString(R.string.dashboard_setting_unavailable))
        }
    }

    private data class TimerLine(val icon: Int, val title: String, val value: String)

    /**
     * Whatever is running on its own clock right now, not by a session you
     * started - bedtime, a schedule window, a task's timer. Design doc:
     * these read as "on a timer" rows (icon, title, the time itself) rather
     * than a generic "Active" pill, since the time is the actual answer to
     * "when does this stop."
     */
    private fun buildOnATimerCard(): View? {
        val lines = ArrayList<TimerLine>()

        EarnSession.activeTask(activity)?.let { task ->
            lines.add(
                TimerLine(
                    R.drawable.ic_glyph_earn,
                    activity.getString(R.string.dashboard_task_running),
                    task.title + " · " + SessionManager.formatDuration(EarnSession.elapsedMs(activity)) + " in"
                )
            )
        }
        if (EarnPool.hasMinutes(activity)) {
            lines.add(
                TimerLine(
                    R.drawable.ic_glyph_earn,
                    activity.getString(R.string.dashboard_earned_pool),
                    // Seconds of balance still count as a balance; formatDuration
                    // rounds them down to a "0m left" that reads as empty.
                    activity.getString(
                        R.string.common_count_left,
                        EarnPool.balanceMs(activity).let { if (it < 60_000L) "<1m" else SessionManager.formatDuration(it) }
                    )
                )
            )
        }
        ScheduleManager.activeWindowIfEnabled(activity)?.let { window ->
            lines.add(
                TimerLine(
                    R.drawable.ic_glyph_schedules,
                    window.message.ifBlank { activity.getString(R.string.dashboard_schedule_running) },
                    activity.getString(R.string.dashboard_until, ScheduleManager.formatTime(activity, window.endMinutes))
                )
            )
        }
        ScheduleManager.nextWindow(activity)?.let { window ->
            lines.add(TimerLine(R.drawable.ic_glyph_schedules, activity.getString(R.string.dashboard_next_window), ScheduleManager.formatTime(activity, window.startMinutes)))
        }
        if (Bedtime.isActive(activity)) {
            lines.add(TimerLine(R.drawable.ic_glyph_bedtime, activity.getString(R.string.cap_bedtime_mode_label), activity.getString(R.string.dashboard_now_arrow, Bedtime.formatTime(activity, Bedtime.endMinutes(activity)))))
        }
        PlaceRules.activePlaces(activity).forEach { place ->
            lines.add(TimerLine(R.drawable.ic_glyph_places, activity.getString(R.string.dashboard_place_rule), place.label))
        }

        if (lines.isEmpty()) return null

        val card = FocusUi.card(activity, tokens)
        val header = FocusUi.row(activity)
        val label = FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.dashboard_on_a_timer))
        (label.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = 0; bottomMargin = 0 }
        label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(label)
        header.addView(FocusUi.valueLabel(activity, tokens, activity.getString(R.string.dashboard_active_count, java.text.NumberFormat.getInstance().format(lines.size))))
        card.addView(header)
        card.addView(FocusUi.spacer(activity, 8))

        lines.forEachIndexed { index, line ->
            card.addView(
                FocusUi.listRow(
                    activity,
                    tokens,
                    line.title,
                    null,
                    trailing = FocusUi.valueLabel(activity, tokens, line.value),
                    leading = FocusUi.categoryIcon(activity, tokens, line.icon, tokens.accent)
                )
            )
            if (index < lines.size - 1) card.addView(FocusUi.divider(activity, tokens))
        }
        return card
    }

    private fun buildQuickSettings(): View {
        val card = FocusUi.card(activity, tokens)
        val grid = FocusUi.row(activity)
        grid.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val entries = listOf(
            "Wi-Fi" to Intent(Settings.ACTION_WIFI_SETTINGS),
            "Data" to Intent("android.settings.MOBILE_NETWORK_SETTINGS"),
            "Bluetooth" to Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            "Sound" to Intent(Settings.ACTION_SOUND_SETTINGS),
            "Hotspot" to Intent("android.settings.TETHER_SETTINGS")
        )
        entries.forEach { entry ->
            grid.addView(FocusUi.smallButton(activity, tokens, entry.first) { openSetting(listOf(entry.second)) })
        }

        val scroll = FocusUi.horizontalScroll(activity, grid)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        card.addView(scroll)
        return card
    }

    private companion object {
        const val KEY_LAST_DURATION_MS = "dashboard_last_duration_ms"
    }
}
