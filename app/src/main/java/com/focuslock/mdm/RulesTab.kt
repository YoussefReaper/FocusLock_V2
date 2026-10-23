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
        add(FocusUi.spacer(activity, tokens.density.gapDp))
        add(buildQuickLinksCard())

        add(FocusUi.spacer(activity, tokens.density.gapDp + 6))
        add(buildTestSection())

        Motion.stagger(added, tokens)
    }

    // ── The grid ──────────────────────────────────────────────────

    private data class GridTile(
        val icon: Int,
        val title: String,
        val value: String,
        val valueColor: Int,
        val intent: Intent,
        val proOnly: Boolean = false
    )

    private fun buildRulesGrid(): View {
        val isPro = DemoMode.isPro(activity)
        val tiles = listOf(
            GridTile(
                R.drawable.ic_glyph_apps,
                activity.getString(R.string.rules_editor_apps_title),
                activity.getString(R.string.rules_grid_blocked_count, AppRules.blockedPackages(activity).size),
                tokens.textMuted,
                Intent(activity, AppRulesActivity::class.java)
            ),
            GridTile(
                R.drawable.ic_glyph_schedules,
                activity.getString(R.string.rules_editor_schedules_title),
                ScheduleManager.getSchedules(activity).size.toString(),
                tokens.textMuted,
                Intent(activity, ScheduleActivity::class.java)
            ),
            GridTile(
                R.drawable.ic_glyph_bedtime,
                activity.getString(R.string.rules_editor_bedtime_title),
                if (!isPro) {
                    activity.getString(R.string.pro_badge)
                } else if (CapabilityRegistry.isEnabled(activity, Capabilities.BEDTIME_MODE)) {
                    Bedtime.formatWindow(activity)
                } else {
                    activity.getString(R.string.common_off)
                },
                if (!isPro || CapabilityRegistry.isEnabled(activity, Capabilities.BEDTIME_MODE)) tokens.accent else tokens.textMuted,
                Intent(activity, BedtimeActivity::class.java),
                proOnly = true
            ),
            GridTile(
                R.drawable.ic_glyph_limits,
                activity.getString(R.string.rules_editor_daily_limits_title),
                (AppLimits.allMinuteLimits(activity).size + AppLimits.allOpenLimits(activity).size).toString(),
                tokens.textMuted,
                Intent(activity, AppLimitsActivity::class.java)
            ),
            GridTile(
                R.drawable.ic_glyph_guard,
                activity.getString(R.string.rules_editor_websites_title),
                AllowlistStore.getWebAllowlistUrls(activity).size.toString(),
                tokens.textMuted,
                Intent(activity, WebAllowlistEditorActivity::class.java)
            ),
            GridTile(
                R.drawable.ic_glyph_keywords,
                activity.getString(R.string.rules_editor_keyword_guard_title),
                KeywordRules.activeRules(activity).size.toString(),
                tokens.textMuted,
                Intent(activity, KeywordGuardActivity::class.java)
            ),
            GridTile(
                R.drawable.ic_glyph_places,
                activity.getString(R.string.rules_editor_places_title),
                if (!isPro) {
                    activity.getString(R.string.pro_badge)
                } else {
                    PlaceRules.activePlaces(activity).firstOrNull()?.label
                        ?: PlaceRules.all(activity).size.toString()
                },
                if (!isPro) tokens.accent else if (PlaceRules.activePlaces(activity).isNotEmpty()) tokens.success else tokens.textMuted,
                Intent(activity, PlaceRulesActivity::class.java),
                proOnly = true
            ),
            GridTile(
                R.drawable.ic_glyph_rules,
                activity.getString(R.string.rules_editor_custom_rules_title),
                if (!isPro) {
                    activity.getString(R.string.pro_badge)
                } else {
                    RuleStore.all(activity).size.toString()
                },
                if (!isPro) tokens.accent else tokens.textMuted,
                Intent(activity, RuleEditorActivity::class.java),
                proOnly = true
            )
        )

        val column = FocusUi.column(activity)
        tiles.chunked(2).forEach { pair ->
            val views = pair.map { buildGridTile(it) }
            val row = FocusUi.tileRow(
                activity,
                if (views.size == 1) views + spacerTile() else views,
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

    /** An invisible half-width placeholder, so a lone last tile keeps the grid's column width. */
    private fun spacerTile(): View = View(activity)

    private fun buildGridTile(tile: GridTile): View {
        val card = FocusUi.card(activity, tokens) {
            if (tile.proOnly) {
                DemoMode.requirePro(activity, tile.title) {
                    activity.startActivity(tile.intent)
                    render()
                }
            } else {
                activity.startActivity(tile.intent)
            }
        }

        val topRow = FocusUi.row(activity)
        topRow.addView(FocusUi.categoryIcon(activity, tokens, tile.icon, tokens.accent, 20))
        val value = FocusUi.caption(activity, tokens, tile.value)
        value.setTextColor(tile.valueColor)
        value.gravity = android.view.Gravity.END
        value.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = FocusUi.dp(activity, 8) }
        topRow.addView(value)
        card.addView(topRow)

        card.addView(FocusUi.spacer(activity, 8))
        card.addView(FocusUi.rowTitle(activity, tokens, tile.title))
        return card
    }

    // ── Always allowed · Profiles ─────────────────────────────────

    private fun buildQuickLinksCard(): View {
        val card = FocusUi.card(activity, tokens)
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.rules_editor_always_allowed_title),
                activity.getString(R.string.common_apps_count, AppRules.alwaysAllowed(activity).size),
                trailing = FocusUi.chevron(activity, tokens)
            ) { activity.startActivity(Intent(activity, AlwaysAllowedActivity::class.java)) }
        )
        card.addView(FocusUi.divider(activity, tokens))

        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.rules_profiles_title),
                activity.getString(R.string.rules_profiles_subtitle),
                trailing = FocusUi.chevron(activity, tokens)
            ) { activity.startActivity(Intent(activity, ProfilesActivity::class.java)) }
        )
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
            else -> column.addView(
                buildOutlineIconButton(R.drawable.ic_glyph_break, activity.getString(R.string.rules_test_start_button), tokens.accent) {
                    pickTestLength()
                }
            )
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
