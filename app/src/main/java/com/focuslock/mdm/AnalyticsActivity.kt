package com.focuslock.mdm

import android.view.View
import android.widget.LinearLayout

/**
 * Where the time went.
 *
 * Information, not a verdict. There is no score, no comparison to anyone else,
 * and no leaderboard: competitive framing turns a bad week into a reason to
 * stop looking, which is the opposite of what this is for. Everything is
 * computed on the phone and stays there.
 */
class AnalyticsActivity : FocusScreenActivity() {

    private var windowIndex = 0
    private val windows: List<String>
        get() = listOf(
            getString(R.string.analytics_window_today),
            getString(R.string.analytics_window_7),
            getString(R.string.analytics_window_30)
        )

    override fun screenArt(): Int = R.drawable.art_insights

    override fun screenTitle(): String = getString(R.string.analytics_title)

    override fun screenSubtitle(): String = Copy.analyticsIntro(this)

    override fun buildContent(column: LinearLayout) {
        if (!UsageAnalytics.isEnabled(this)) {
            column.addView(buildDisabledCard())
            return
        }
        if (!SetupChecks.hasUsageAccess(this)) {
            column.addView(buildPermissionCard())
            return
        }

        column.addView(
            FocusUi.chipStrip(this, tokens, windows, windowIndex) { index ->
                windowIndex = index
                refresh()
            }
        )

        val report = currentReport()
        column.addView(buildHeadline(report))
        column.addView(sectionLabel(getString(R.string.analytics_by_category)))
        column.addView(buildCategoryCard(report))
        column.addView(sectionLabel(getString(R.string.analytics_by_app)))
        column.addView(buildAppCard(report))
        column.addView(sectionLabel(getString(R.string.analytics_section_sessions)))
        column.addView(buildSessionCard())

        if (EarnMode.isEnabled(this)) {
            column.addView(sectionLabel(getString(R.string.analytics_section_earning)))
            column.addView(buildEarnCard())
        }
    }

    /**
     * The Earn numbers, kept as flat facts. No "efficiency", no grade, no
     * comparison — a ratio of earned to spent is information about how you use
     * your own time, not a score to improve.
     */
    private fun buildEarnCard(): View = card { card ->
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.analytics_minutes_earned),
                getString(R.string.analytics_earned_detail, EarnPool.totalEarned(this).toString(), EarnPool.earnedToday(this).toString())
            )
        )
        card.addView(
            FocusUi.listRow(this, tokens, getString(R.string.analytics_minutes_spent), EarnPool.totalSpent(this).toString())
        )
        card.addView(
            FocusUi.listRow(this, tokens, getString(R.string.analytics_pool_now), EarnPool.balanceMinutes(this).toString())
        )
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.analytics_tasks_finished),
                getString(R.string.analytics_tasks_detail, FocusTaskStore.all(this).count { it.completed }.toString(), FocusTaskStore.completedToday(this).size.toString())
            )
        )

        if (EarnMode.showsCredibility(this)) {
            val trust = FocusTaskStore.credibility(this)
            card.addView(FocusUi.spacer(this, 6))
            card.addView(
                FocusUi.meter(
                    this,
                    tokens,
                    getString(R.string.analytics_verification_trust),
                    (trust * 100).toInt().toString() + "%",
                    trust,
                    if (trust < 0.6f) tokens.warning else tokens.success
                )
            )
            card.addView(
                FocusUi.caption(
                    this,
                    tokens,
                    getString(R.string.analytics_trust_note)
                )
            )
        }

        card.addView(FocusUi.spacer(this, 8))
        card.addView(FocusUi.caption(this, tokens, EarnMode.describeDeal(this)))
    }

    private fun currentReport(): UsageReport = when (windowIndex) {
        1 -> UsageAnalytics.last7Days(this)
        2 -> UsageAnalytics.report(this, 30L * 86_400_000L, getString(R.string.analytics_window_30))
        else -> UsageAnalytics.today(this)
    }

    private fun buildDisabledCard(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, getString(R.string.analytics_off_title)))
        card.addView(FocusUi.spacer(this, 6))
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.analytics_off_body)
            )
        )
        card.addView(FocusUi.spacer(this, 12))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.analytics_turn_on)) {
                if (!CapabilityRegistry.setEnabled(this, Capabilities.ANALYTICS, true)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )
    }

    private fun buildPermissionCard(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, getString(R.string.analytics_needs_usage_title)))
        card.addView(FocusUi.spacer(this, 6))
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.analytics_needs_usage_body)
            )
        )
        card.addView(FocusUi.spacer(this, 12))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.analytics_grant_usage)) {
                LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 2 * 60 * 1000)
                if (!PermissionIntents.launchFirst(this, PermissionIntents.usageAccess(this))) {
                    FocusDialog.toast(this, getString(R.string.common_page_not_available))
                }
            }
        )
    }

    private fun buildHeadline(report: UsageReport): View {
        val row = FocusUi.tileRow(
            this,
            listOf(
                FocusUi.statTile(this, tokens, UsageAnalytics.formatDuration(report.totalMs), getString(R.string.analytics_screen_time)),
                FocusUi.statTile(this, tokens, report.opens.toString(), getString(R.string.analytics_opens)),
                FocusUi.statTile(this, tokens, report.apps.size.toString(), getString(R.string.analytics_apps_used))
            )
        )
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(this@AnalyticsActivity, tokens.density.gapDp) }
        return row
    }

    /**
     * Proportion bars rather than a pie: the question people actually have is
     * "how much of my day was this", and a bar answers it without a legend.
     */
    private fun buildCategoryCard(report: UsageReport): View = card { card ->
        if (!report.hasData) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.analytics_no_usage)))
            return@card
        }

        report.byCategory.take(8).forEach { entry ->
            val fraction = if (report.totalMs > 0) {
                entry.second.toFloat() / report.totalMs.toFloat()
            } else {
                0f
            }
            card.addView(
                FocusUi.meter(
                    this,
                    tokens,
                    entry.first.label(this),
                    UsageAnalytics.formatDuration(entry.second) +
                        "  ·  " + (fraction * 100).toInt() + "%",
                    fraction,
                    colorFor(entry.first)
                )
            )
        }
    }

    private fun colorFor(category: AppCategory): Int = when (category) {
        AppCategory.SOCIAL -> tokens.danger
        AppCategory.VIDEO -> tokens.warning
        AppCategory.GAMES -> tokens.accent
        AppCategory.PRODUCTIVITY -> tokens.success
        AppCategory.ESSENTIAL -> tokens.textSecondary
        else -> UiPrefs.blend(tokens.accent, tokens.background, 0.4f)
    }

    private fun buildAppCard(report: UsageReport): View = card { card ->
        if (report.apps.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.analytics_nothing_yet)))
            return@card
        }

        val shown = report.apps.take(15)
        shown.forEachIndexed { index, slice ->
            val policy = AppRules.effectivePolicy(this, slice.packageName)
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    slice.label,
                    getString(
                        R.string.analytics_slice_detail,
                        UsageAnalytics.formatDuration(slice.totalMs),
                        slice.opens,
                        slice.category.label(this)
                    ),
                    trailing = FocusUi.smallButton(
                        this,
                        tokens,
                        if (policy.stopsLaunch) getString(R.string.analytics_policy_blocked)
                        else getString(R.string.analytics_policy_manage)
                    ) { manage(slice) },
                    leading = FocusUi.appIcon(this, tokens, slice.packageName, 34)
                ) { manage(slice) }
            )
            if (index < shown.size - 1) card.addView(FocusUi.divider(this, tokens))
        }
    }

    /** Seeing a number and acting on it should be one tap apart, not two screens. */
    private fun manage(slice: UsageSlice) {
        val current = AppRules.effectivePolicy(this, slice.packageName)
        FocusDialog.singleChoice(
            this,
            title = slice.label,
            subtitle = getString(R.string.analytics_slice_subtitle, UsageAnalytics.formatDuration(slice.totalMs)),
            choices = AppPolicy.ladder.map { policy ->
                FocusDialog.Choice(policy.id, policy.label(this), policy.blurb(this))
            },
            selectedKey = current.id
        ) { key ->
            if (!AppRules.setPolicy(this, slice.packageName, AppPolicy.fromId(key))) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
            }
            refresh()
        }
    }

    private fun buildSessionCard(): View = card { card ->
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.analytics_sessions_finished),
                SessionManager.totalSessions(this).toString()
            )
        )
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.analytics_time_held),
                UsageAnalytics.formatDuration(SessionManager.totalFocusMs(this))
            )
        )
        if (Streaks.isEnabled(this)) {
            card.addView(
                FocusUi.listRow(this, tokens, getString(R.string.analytics_current_run), getString(R.string.analytics_days, Streaks.current(this)))
            )
            card.addView(
                FocusUi.listRow(this, tokens, getString(R.string.analytics_best_run), getString(R.string.analytics_days, Streaks.best(this)))
            )
        }
        card.addView(FocusUi.spacer(this, 8))
        card.addView(
            FocusUi.caption(
                this,
                tokens,
                getString(R.string.analytics_privacy_note)
            )
        )
    }
}