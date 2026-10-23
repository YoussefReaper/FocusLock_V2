package com.focuslock.mdm

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The Rules tab: everything the user owns, as a grid you read in one glance.
 *
 * Design doc, "one screen, one job": this used to be ten stacked editors plus
 * the whole capability registry on one scroll. Editors are now eight tiles;
 * the switchboard (every capability switch) moved one level down, to
 * [CapabilitiesActivity] - a screen you open twice a year, not one you
 * scroll past every time you want to check a rule.
 */
class RulesTab(activity: MainActivity, tokens: UiPrefs.Tokens) : FocusTab(activity, tokens) {

    private lateinit var container: LinearLayout

    override fun build(): View {
        container = FocusUi.column(activity, tokens.density.contentPaddingDp)
        return hostScroll(FocusUi.scroll(activity, container), container)
    }

    override fun onShow() {
        render()
    }

    private fun render(): Unit = redraw {
        val added = ArrayList<View>()

        fun add(view: View) {
            container.addView(view)
            added.add(view)
        }

        add(
            FocusUi.pageHeader(
                activity,
                tokens,
                activity.getString(R.string.rules_title),
                activity.getString(R.string.rules_subtitle)
            )
        )

        add(buildRulesGrid())
        add(FocusUi.spacer(activity, tokens.density.gapDp + 6))
        add(buildTestSection())

        Motion.stagger(added, tokens)
    }

    // ── The grid ──────────────────────────────────────────────────

    private data class RuleRow(
        val icon: Int,
        val title: String,
        val value: String,
        val intent: Intent,
        val proOnly: Boolean = false,
        val tone: FocusUi.IconTone = FocusUi.IconTone.ACCENT
    )

    /**
     * Quiet Pine: three short groups that answer the questions people actually
     * ask - what is blocked, when, and what is the exception - instead of a
     * grid of nine equal tiles. Each row says its current state in words.
     */
    private fun buildRulesGrid(): View {
        val isPro = DemoMode.isPro(activity)
        val column = FocusUi.column(activity)

        fun group(label: Int, rows: List<RuleRow>) {
            column.addView(FocusUi.sectionLabel(activity, tokens, activity.getString(label)))
            column.addView(buildGroupCard(rows, isPro))
        }

        group(
            R.string.redesign_rules_blocked,
            listOf(
                RuleRow(
                    R.drawable.ic_glyph_apps,
                    activity.getString(R.string.rules_editor_apps_title),
                    activity.getString(R.string.rules_grid_blocked_count, AppRules.blockedInstalledCount(activity)),
                    Intent(activity, AppRulesActivity::class.java)
                ),
                RuleRow(
                    R.drawable.ic_glyph_guard,
                    activity.getString(R.string.rules_editor_websites_title),
                    activity.getString(R.string.redesign_rules_sites, AllowlistStore.getWebAllowlistUrls(activity).size),
                    Intent(activity, WebAllowlistEditorActivity::class.java)
                ),
                RuleRow(
                    R.drawable.ic_glyph_keywords,
                    activity.getString(R.string.rules_editor_keyword_guard_title),
                    activity.getString(R.string.redesign_rules_words, KeywordRules.activeRules(activity).size),
                    Intent(activity, KeywordGuardActivity::class.java)
                )
            )
        )
        group(
            R.string.redesign_rules_when,
            listOf(
                RuleRow(
                    R.drawable.ic_glyph_schedules,
                    activity.getString(R.string.rules_editor_schedules_title),
                    activity.getString(R.string.redesign_rules_windows, ScheduleManager.getSchedules(activity).size),
                    Intent(activity, ScheduleActivity::class.java)
                ),
                RuleRow(
                    R.drawable.ic_glyph_bedtime,
                    activity.getString(R.string.rules_editor_bedtime_title),
                    if (CapabilityRegistry.isEnabled(activity, Capabilities.BEDTIME_MODE)) Bedtime.formatWindow(activity)
                    else activity.getString(R.string.common_off),
                    Intent(activity, BedtimeActivity::class.java),
                    proOnly = true
                ),
                RuleRow(
                    R.drawable.ic_glyph_places,
                    activity.getString(R.string.rules_editor_places_title),
                    PlaceRules.activePlaces(activity).firstOrNull()?.label
                        ?: activity.getString(R.string.redesign_rules_places, PlaceRules.all(activity).size),
                    Intent(activity, PlaceRulesActivity::class.java),
                    proOnly = true
                )
            )
        )
        group(
            R.string.redesign_rules_limits_group,
            listOf(
                RuleRow(
                    R.drawable.ic_glyph_limits,
                    activity.getString(R.string.rules_editor_daily_limits_title),
                    activity.getString(R.string.redesign_rules_budgets, AppLimits.allMinuteLimits(activity).size + AppLimits.allOpenLimits(activity).size),
                    Intent(activity, AppLimitsActivity::class.java),
                    tone = FocusUi.IconTone.WARM
                ),
                RuleRow(
                    R.drawable.ic_glyph_apps,
                    activity.getString(R.string.rules_editor_always_allowed_title),
                    activity.getString(R.string.common_apps_count, AppRules.alwaysAllowed(activity).size),
                    Intent(activity, AlwaysAllowedActivity::class.java)
                )
            )
        )
        group(
            R.string.redesign_rules_more,
            listOf(
                RuleRow(
                    R.drawable.ic_glyph_rules,
                    activity.getString(R.string.rules_editor_custom_rules_title),
                    activity.getString(R.string.redesign_rules_rules, RuleStore.all(activity).size),
                    Intent(activity, RuleEditorActivity::class.java),
                    proOnly = true,
                    tone = FocusUi.IconTone.MUTED
                ),
                RuleRow(
                    R.drawable.ic_glyph_insight,
                    activity.getString(R.string.rules_profiles_title),
                    activity.getString(R.string.rules_profiles_subtitle),
                    Intent(activity, ProfilesActivity::class.java),
                    tone = FocusUi.IconTone.MUTED
                )
            )
        )
        return column
    }

    private fun buildGroupCard(rows: List<RuleRow>, isPro: Boolean): View {
        val card = FocusUi.card(activity, tokens)
        val padV = FocusUi.dp(activity, 4)
        card.setPadding(FocusUi.dp(activity, 16), padV, FocusUi.dp(activity, 12), padV)
        rows.forEachIndexed { index, row ->
            if (index > 0) card.addView(FocusUi.divider(activity, tokens))
            val locked = row.proOnly && !isPro
            val trailing: View = if (locked) {
                FocusUi.pill(activity, tokens, activity.getString(R.string.pro_badge), tokens.textPrimary)
            } else {
                FocusUi.chevron(activity, tokens)
            }
            card.addView(
                FocusUi.listRow(
                    activity,
                    tokens,
                    row.title,
                    row.value,
                    trailing = trailing,
                    leading = FocusUi.iconTile(activity, tokens, row.icon, row.tone)
                ) {
                    if (row.proOnly) {
                        DemoMode.requirePro(activity, row.title) {
                            activity.startActivity(row.intent)
                            render()
                        }
                    } else {
                        activity.startActivity(row.intent)
                    }
                }
            )
        }
        return card
    }

    // ── Test the block ────────────────────────────────────────────

    /**
     * A live, honest preview: opening an app while a test is running shows the
     * exact same intercept screen a real session would, computed by the same
     * [RuleEngine.decide] - not a mockup that can drift out of sync with what
     * the rules actually do. Nothing enforced ever engages for real (see
     * [TestMode]), and it can be ended from here or from the intercept screen
     * itself at any time.
     */
    private fun buildTestSection(): View {
        val column = FocusUi.column(activity)
        val active = TestMode.isActive(activity)

        when {
            active -> {
                column.addView(
                    FocusUi.secondary(activity, tokens, activity.getString(R.string.rules_test_active_body, TestMode.formatRemaining(activity)))
                )
                column.addView(FocusUi.spacer(activity, 10))
                column.addView(
                    buildOutlineIconButton(R.drawable.ic_glyph_break, activity.getString(R.string.rules_test_end_button), tokens.danger) {
                        TestMode.end(activity)
                        render()
                    }
                )
            }
            !TestMode.canStart(activity) -> column.addView(
                FocusUi.caption(activity, tokens, activity.getString(R.string.rules_test_session_running))
            )
            else -> {
                val card = FocusUi.card(activity, tokens) { pickTestLength() }
                card.addView(
                    FocusUi.listRow(
                        activity,
                        tokens,
                        activity.getString(R.string.redesign_rules_test_title),
                        activity.getString(R.string.redesign_rules_test_body),
                        trailing = FocusUi.chevron(activity, tokens),
                        leading = FocusUi.iconTile(activity, tokens, R.drawable.ic_glyph_break, FocusUi.IconTone.SOLID)
                    ) { pickTestLength() }
                )
                column.addView(card)
            }
        }
        return column
    }

    private fun buildOutlineIconButton(icon: Int, label: String, tint: Int, onClick: () -> Unit): View {
        val row = FocusUi.row(activity)
        row.gravity = Gravity.CENTER
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            FocusUi.dp(activity, tokens.density.buttonHeightDp)
        )
        row.background = FocusUi.roundedShape(activity, UiPrefs.withAlpha(tokens.surface, 0), tokens.buttonRadiusDp, tokens.divider)
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick() }
        row.addView(FocusUi.categoryIcon(activity, tokens, icon, tint, 18))
        row.addView(FocusUi.spacerH(activity, 9))
        val text = FocusUi.rowTitle(activity, tokens, label)
        text.setTextColor(tint)
        row.addView(text)
        return row
    }

    private fun pickTestLength() {
        FocusDialog.singleChoice(
            activity,
            activity.getString(R.string.rules_test_length_title),
            activity.getString(R.string.rules_test_length_subtitle),
            listOf(
                FocusDialog.Choice("5", activity.getString(R.string.rules_test_minutes_5)),
                FocusDialog.Choice("10", activity.getString(R.string.rules_test_minutes_10)),
                FocusDialog.Choice("20", activity.getString(R.string.rules_test_minutes_20)),
                FocusDialog.Choice("30", activity.getString(R.string.rules_test_minutes_30))
            ),
            TestMode.DEFAULT_MINUTES.toString()
        ) { selected ->
            val minutes = selected.toIntOrNull() ?: TestMode.DEFAULT_MINUTES
            if (TestMode.start(activity, minutes)) {
                FocusDialog.toast(activity, activity.getString(R.string.rules_test_started_toast))
                render()
            } else {
                FocusDialog.toast(activity, activity.getString(R.string.rules_test_session_running_toast))
            }
        }
    }
}
