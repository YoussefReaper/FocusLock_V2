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
        countdownView?.text = SessionManager.formatCountdown(SessionManager.remainingMs(activity))
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

        if (SessionManager.isActive(activity)) {
            add(buildActiveSession())
        } else {
            add(buildStarter())
            add(buildOneTapPresetsCard())
            if (!SetupChecks.isDeviceOwner(activity)) {
                add(buildUnbreakableUpgradeCard())
            }
        }

        buildOnATimerCard()?.let { add(it) }

        add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.dashboard_tasks_and_tools)))
        add(buildTasksAndToolsCard())

        if (UiPrefs.showQuickSettings(activity)) {
            add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.dashboard_quick_settings)))
            add(buildQuickSettings())
        }

        Motion.stagger(added, tokens)
    }

    private fun buildOneTapPresetsCard(): View {
        val card = FocusUi.card(activity, tokens)
        card.addView(FocusUi.caption(activity, tokens, activity.getString(R.string.onboarding_presets_heading)))
        card.addView(FocusUi.spacer(activity, 8))

        val row = FocusUi.row(activity)
        val presets = listOf(
            "study" to activity.getString(R.string.preset_study_title),
            "sleep" to activity.getString(R.string.preset_sleep_title),
            "detox" to activity.getString(R.string.preset_detox_title)
        )
        presets.forEachIndexed { idx, (key, label) ->
            val btn = FocusUi.smallButton(activity, tokens, label) {
                applyQuickPreset(key)
            }
            btn.layoutParams = LinearLayout.LayoutParams(0, FocusUi.dp(activity, 40), 1f).apply {
                if (idx > 0) marginStart = FocusUi.dp(activity, 8)
            }
            row.addView(btn)
        }
        card.addView(row)
        return card
    }

    private fun applyQuickPreset(presetId: String) {
        if (SessionLock.isFrozen(activity)) {
            FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
            return
        }
        val essentials = AppCatalog.detectEssentials(activity).toSet()
        val launchable = AppCatalog.launchable(activity).filterNot { it.packageName in essentials }
        when (presetId) {
            "study" -> {
                val targets = launchable.filter {
                    it.category in setOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAMES)
                }.map { it.packageName }.ifEmpty { Seed.obviousDistractions }
                AppRules.setPolicies(activity, targets, AppPolicy.BLOCK)
                selectedMode = FocusMode.BLOCK
                selectedDurationMs = 60L * 60L * 1000L
                SessionManager.start(activity, FocusMode.BLOCK, selectedDurationMs)
                FocusDialog.toast(activity, activity.getString(R.string.preset_applied_toast, activity.getString(R.string.preset_study_title)))
            }
            "sleep" -> {
                CapabilityRegistry.setEnabled(activity, Capabilities.BEDTIME_MODE, true)
                Bedtime.setWindow(activity, 22 * 60, 6 * 60)
                Bedtime.setBlockedCategories(
                    activity,
                    listOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAMES)
                )
                FocusDialog.toast(activity, activity.getString(R.string.preset_applied_toast, activity.getString(R.string.preset_sleep_title)))
            }
            "detox" -> {
                val targets = launchable.filter {
                    it.category in setOf(AppCategory.SOCIAL, AppCategory.VIDEO)
                }.map { it.packageName }.ifEmpty { Seed.obviousDistractions }
                AppRules.setPolicies(activity, targets, AppPolicy.FRICTION)
                CapabilityRegistry.setEnabled(activity, Capabilities.LAUNCH_FRICTION, true)
                selectedMode = FocusMode.SOFT
                selectedDurationMs = 2L * 60L * 60L * 1000L
                SessionManager.start(activity, FocusMode.SOFT, selectedDurationMs)
                FocusDialog.toast(activity, activity.getString(R.string.preset_applied_toast, activity.getString(R.string.preset_detox_title)))
            }
        }
        render()
    }

    private fun buildUnbreakableUpgradeCard(): View {
        val card = FocusUi.card(activity, tokens) {
            activity.startActivity(Intent(activity, DeviceOwnerHelpActivity::class.java))
        }
        val header = FocusUi.row(activity)
        val title = FocusUi.heading(activity, tokens, activity.getString(R.string.dashboard_unbreakable_card_title))
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(title)
        header.addView(FocusUi.pill(activity, tokens, activity.getString(R.string.pro_badge), tokens.accent))
        card.addView(header)
        card.addView(FocusUi.spacer(activity, 4))
        card.addView(FocusUi.secondary(activity, tokens, activity.getString(R.string.dashboard_unbreakable_card_body)))
        card.addView(FocusUi.spacer(activity, 10))
        card.addView(
            FocusUi.smallButton(activity, tokens, activity.getString(R.string.dashboard_show_me_how)) {
                activity.startActivity(Intent(activity, DeviceOwnerHelpActivity::class.java))
            }
        )
        return card
    }

    private fun buildTasksAndToolsCard(): View {
        val card = FocusUi.card(activity, tokens)
        val openTasksCount = FocusTaskStore.all(activity).count { !it.completed }
        val tasksSubtitle = if (openTasksCount > 0) {
            activity.getString(R.string.dashboard_tasks_card_subtitle) + " · " + openTasksCount
        } else {
            activity.getString(R.string.dashboard_tasks_card_subtitle)
        }
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.dashboard_tasks_card_title),
                tasksSubtitle,
                trailing = FocusUi.chevron(activity, tokens),
                leading = FocusUi.categoryIcon(activity, tokens, R.drawable.ic_glyph_tasks, tokens.accent)
            ) {
                activity.selectTab(MainActivity.TAB_TASKS)
            }
        )
        card.addView(FocusUi.divider(activity, tokens))
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.dashboard_tools_card_title),
                activity.getString(R.string.dashboard_tools_card_subtitle),
                trailing = FocusUi.chevron(activity, tokens),
                leading = FocusUi.categoryIcon(activity, tokens, R.drawable.ic_glyph_library, tokens.accent)
            ) {
                activity.selectTab(MainActivity.TAB_LIBRARY)
            }
        )
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

    private fun statusHeadline(): String =
        if (SessionManager.isActive(activity)) {
            activity.getString(R.string.mode_running_suffix, SessionManager.mode(activity).label(activity))
        } else {
            activity.getString(R.string.dashboard_nothing_running)
        }

    // ── Active session ────────────────────────────────────────────

    private fun buildActiveSession(): View {
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
            val timeLeft = FocusUi.display(activity, tokens, SessionManager.formatCountdown(SessionManager.remainingMs(activity)))
            timeLeft.gravity = Gravity.CENTER
            timeLeft.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
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
                SessionManager.formatCountdown(SessionManager.remainingMs(activity))
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

    private fun buildStarter(): View {
        val column = FocusUi.column(activity)

        val available = FocusMode.available(activity)
        if (available.isEmpty()) {
            val card = FocusUi.card(activity, tokens)
            card.addView(
                FocusUi.body(
                    activity,
                    tokens,
                    activity.getString(R.string.all_modes_off_body)
                )
            )
            card.addView(FocusUi.spacer(activity, 12))
            card.addView(
                FocusUi.secondaryButton(activity, tokens, activity.getString(R.string.dashboard_open_rules)) {
                    activity.selectTab(MainActivity.TAB_RULES)
                }
            )
            column.addView(card)
            return column
        }

        // Never leave the selection sitting on a mode that cannot be started -
        // either because it is switched off, or because the demo locks it. The
        // fallback is the first mode that *can* run, so the Start button below
        // always describes something real.
        if (selectedMode !in available || DemoMode.isModeLocked(activity, selectedMode)) {
            selectedMode = available.firstOrNull { !DemoMode.isModeLocked(activity, it) } ?: available.first()
        }

        column.addView(buildModeGrid(available))
        column.addView(FocusUi.spacer(activity, 10))
        column.addView(buildModeSummaryLine())
        column.addView(FocusUi.spacer(activity, 6))
        column.addView(buildDurationPicker())

        column.addView(FocusUi.spacer(activity, 10))
        val canStart = DemoMode.hasSessionLeft(activity)
        if (canStart) {
            column.addView(
                FocusUi.primaryButton(
                    activity,
                    tokens,
                    activity.getString(R.string.mode_start_button, selectedMode.label(activity), SessionManager.formatDuration(selectedDurationMs))
                ) { confirmStart() }
            )
        } else {
            // The demo's session is spent. The button says so and still opens
            // the explanation, rather than being inert - a dead control with no
            // reason on it is the thing this whole banner exists to avoid.
            column.addView(
                FocusUi.primaryButton(
                    activity,
                    tokens,
                    activity.getString(R.string.demo_start_used_up)
                ) { confirmStart() }
            )
        }

        // A link, not a card: the two toggles it opens are for the one
        // in a hundred sessions where the default isn't right, and a
        // full settings card for that used to out-weigh the Start button
        // itself whenever Kiosk was picked.
        if (selectedMode == FocusMode.KIOSK) {
            column.addView(FocusUi.spacer(activity, 8))
            column.addView(buildKioskOptionsLink())
        }

        // The same gap a card leaves under itself. The starter is a plain
        // column, not a card, so it had none, and the next card sat flush
        // against the Start button.
        column.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(activity, tokens.density.gapDp) }
        return column
    }

    /**
     * A 2×2 grid, each tile just a strength and a name (design doc: "a row is
     * a title and a state"). What a mode actually does, its exit line, and
     * the device-owner warning all moved to [buildModeSummaryLine]'s ⓘ -
     * four full explanations stacked at once was a wall before you had even
     * picked one.
     */
    private fun buildModeGrid(available: List<FocusMode>): View {
        val column = FocusUi.column(activity)
        available.chunked(2).forEach { pair ->
            val cards = pair.map { buildModeCard(it) }
            // Equal heights enforced by the row rather than by hoping every
            // mode name happens to fit on one line - at a large text scale, or
            // in Arabic, "Sanctuary" wraps and its partner did not follow.
            val row = FocusUi.tileRow(
                activity,
                if (cards.size == 1) cards + View(activity) else cards,
                gapDp = 9
            )
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = FocusUi.dp(activity, 9) }
            column.addView(row)
        }
        return column
    }

    private fun buildModeCard(mode: FocusMode): View {
        if (DemoMode.isModeLocked(activity, mode)) return buildLockedModeCard(mode)

        val selected = mode == selectedMode
        val card = FocusUi.card(activity, tokens, elevated = selected) {
            selectedMode = mode
            render()
        }
        if (selected) {
            card.background = FocusUi.roundedShape(
                activity,
                tokens.surfaceAlt,
                tokens.radiusDp,
                tokens.accent,
                2
            )
        }

        card.addView(buildStrengthDots(mode.strength))
        card.addView(FocusUi.spacer(activity, 8))
        card.addView(FocusUi.rowTitle(activity, tokens, mode.label(activity)))
        return card
    }

    /**
     * A Pro mode shown in the grid with a "Pro" badge; tapping it opens the
     * in-app FocusLock Pro upgrade modal so the user can unlock it in-place.
     */
    private fun buildLockedModeCard(mode: FocusMode): View {
        val card = FocusUi.card(activity, tokens) {
            DemoMode.showUpgradeDialog(activity, mode.label(activity)) {
                selectedMode = mode
                render()
            }
        }
        card.alpha = 0.78f

        val topRow = FocusUi.row(activity)
        topRow.addView(buildStrengthDots(mode.strength).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(
            FocusUi.pill(
                activity,
                tokens,
                activity.getString(R.string.pro_badge),
                tokens.accent
            )
        )
        card.addView(topRow)
        card.addView(FocusUi.spacer(activity, 8))
        card.addView(FocusUi.rowTitle(activity, tokens, mode.label(activity)))
        return card
    }

    /**
     * The one line the four cards used to say four times over: what the
     * selected mode does, its exit line, the live preset (and whether it was
     * edited), and the device-owner warning, all one tap away behind the ⓘ.
     */
    private fun buildModeSummaryLine(): View {
        val row = FocusUi.row(activity)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(activity, 4) }

        val text = FocusUi.caption(activity, tokens, activity.getString(R.string.mode_summary_line, selectedMode.label(activity), selectedMode.oneLiner(activity)))
        text.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(text)
        row.addView(FocusUi.spacerH(activity, 8))
        row.addView(buildInfoGlyph { showModeDetail(selectedMode) })
        return row
    }

    private fun buildInfoGlyph(onClick: () -> Unit): View {
        val view = TextView(activity)
        view.text = "ⓘ"
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(13f))
        view.setTextColor(tokens.textMuted)
        view.isClickable = true
        view.isFocusable = true
        // A 13sp glyph with 4dp around it was a target about a third of the
        // 48dp minimum; the padding makes the tap area, not the glyph, larger.
        val pad = FocusUi.dp(activity, 12)
        view.setPadding(pad, pad, pad, pad)
        view.minWidth = FocusUi.dp(activity, 48)
        view.minHeight = FocusUi.dp(activity, 48)
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

    /** Four dots, filled to the mode's strength: readable at a glance, no icon to decode. */
    private fun buildStrengthDots(strength: Int): View {
        val row = FocusUi.row(activity)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        for (i in 1..4) {
            val dot = View(activity)
            val size = FocusUi.dp(activity, 7)
            dot.background = FocusUi.roundedShape(
                activity,
                if (i <= strength) tokens.accent else tokens.track,
                4
            )
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = FocusUi.dp(activity, 4)
            }
            row.addView(dot)
        }
        return row
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
        val row = FocusUi.row(activity)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        quickDurations.forEachIndexed { index, (label, ms) ->
            val segment = buildDurationSegment(label, selectedDurationMs == ms) {
                selectedDurationMs = ms
                render()
            }
            segment.layoutParams = LinearLayout.LayoutParams(0, FocusUi.dp(activity, 44), 1f).apply {
                if (index > 0) marginStart = FocusUi.dp(activity, 8)
            }
            row.addView(segment)
        }

        val isCustom = quickDurations.none { it.second == selectedDurationMs }
        val setLabel = if (isCustom) SessionManager.formatDuration(selectedDurationMs) else activity.getString(R.string.common_set)
        val setSegment = buildDurationSegment(setLabel, isCustom) { openDurationChooser() }
        setSegment.layoutParams = LinearLayout.LayoutParams(0, FocusUi.dp(activity, 44), 1f).apply {
            marginStart = FocusUi.dp(activity, 8)
        }
        row.addView(setSegment)

        return row
    }

    private fun buildDurationSegment(label: String, selected: Boolean, onClick: () -> Unit): View {
        val view = TextView(activity)
        view.text = label
        view.gravity = Gravity.CENTER
        view.isSingleLine = true
        view.setTextColor(if (selected) tokens.textPrimary else tokens.textSecondary)
        FocusUi.applyFont(view, tokens, mono = true, weight = if (selected) 600 else 500)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(13f))
        val fill = if (selected) tokens.surfaceAlt else UiPrefs.withAlpha(tokens.surface, 0)
        val stroke = if (selected) null else tokens.divider
        view.background = FocusUi.withRipple(
            activity,
            FocusUi.roundedShape(activity, fill, tokens.chipRadiusDp, stroke),
            tokens
        )
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
        return view
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
        val willEndEarly = if (SessionManager.presetPending(activity, mode)) {
            mode.preset()[Capabilities.CAN_END_EARLY] == true
        } else {
            SessionManager.canEndEarly(activity)
        }

        val exitLine = if (willEndEarly) {
            activity.getString(R.string.dashboard_can_end_early_line)
        } else {
            activity.getString(R.string.dashboard_no_early_exit_line, SessionManager.formatDuration(selectedDurationMs))
        }

        val message = mode.oneLiner(activity) + "\n\n" + exitLine

        FocusDialog.alert(
            activity,
            title = activity.getString(R.string.mode_start_confirm_title, mode.label(activity), SessionManager.formatDuration(selectedDurationMs)),
            message = message,
            confirmLabel = activity.getString(R.string.common_start),
            cancelLabel = activity.getString(R.string.common_cancel),
            onConfirm = {
                if (SessionManager.start(activity, mode, selectedDurationMs)) {
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
