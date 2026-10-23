package com.focuslock.mdm

import android.view.View
import android.widget.LinearLayout

/**
 * Daily budgets.
 *
 * Two different levers, because they catch two different habits: minutes catch
 * the evening that disappears, opens catch the twenty-times-an-hour reflex.
 * Both are read from Android's own usage stats, so the numbers match what the
 * phone itself reports and survive FocusLock being killed.
 */
class AppLimitsActivity : FocusScreenActivity() {

    override fun screenTitle(): String = getString(R.string.app_limits_title)

    override fun screenSubtitle(): String = getString(R.string.app_limits_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildSwitches())

        if (!SetupChecks.hasUsageAccess(this)) {
            column.addView(buildUsageWarning())
        }

        column.addView(sectionLabel(getString(R.string.app_limits_section_minute_budgets)))
        column.addView(buildMinuteList())

        column.addView(sectionLabel(getString(R.string.app_limits_open_caps_title)))
        column.addView(buildOpenList())

        column.addView(sectionLabel(getString(R.string.app_limits_section_take_a_break)))
        column.addView(buildBreakCard())
    }

    private fun buildSwitches(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.cap_per_app_limits_label),
                getString(R.string.cap_per_app_limits_blurb),
                CapabilityRegistry.isEnabled(this, Capabilities.PER_APP_LIMITS)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.PER_APP_LIMITS, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )
    }

    private fun buildUsageWarning(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, getString(R.string.app_limits_usage_off_heading)))
        card.addView(FocusUi.spacer(this, 6))
        card.addView(FocusUi.secondary(this, tokens, getString(R.string.app_limits_usage_off_body)))
        card.addView(FocusUi.spacer(this, 12))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.app_limits_grant_usage_access)) {
                LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 2 * 60 * 1000)
                if (!PermissionIntents.launchFirst(this, PermissionIntents.usageAccess(this))) {
                    FocusDialog.toast(this, getString(R.string.common_page_not_available))
                }
            }
        )
    }

    private fun buildMinuteList(): View = card { card ->
        val limits = AppLimits.allMinuteLimits(this)

        if (limits.isEmpty()) {
            card.addView(
                FocusUi.emptyState(this, tokens, getString(R.string.app_limits_no_minute_budgets))
            )
        } else {
            limits.entries.sortedBy { AppCatalog.label(this, it.key) }.forEach { entry ->
                val used = AppLimits.usedMinutesToday(this, entry.key)
                val fraction = if (entry.value > 0) used.toFloat() / entry.value.toFloat() else 0f
                val holder = FocusUi.column(this)
                holder.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        AppCatalog.label(this, entry.key),
                        getString(R.string.app_limits_minute_row_subtitle, used, entry.value),
                        trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_change)) {
                            askMinutes(entry.key)
                        },
                        leading = FocusUi.appIcon(this, tokens, entry.key, 34)
                    )
                )
                holder.addView(
                    FocusUi.meter(
                        this,
                        tokens,
                        "",
                        "",
                        fraction,
                        if (fraction >= 1f) tokens.danger else tokens.accent
                    )
                )
                card.addView(holder)
            }
        }

        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.app_limits_add_minute_budget)) { pickAppFor(true) }
        )
    }

    private fun buildOpenList(): View = card { card ->
        val limits = AppLimits.allOpenLimits(this)

        if (limits.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.app_limits_no_open_caps)))
        } else {
            limits.entries.sortedBy { AppCatalog.label(this, it.key) }.forEach { entry ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        AppCatalog.label(this, entry.key),
                        getString(R.string.app_limits_open_row_subtitle, AppLimits.opensToday(this, entry.key), entry.value),
                        trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_change)) { askOpens(entry.key) },
                        leading = FocusUi.appIcon(this, tokens, entry.key, 34)
                    )
                )
            }
        }

        card.addView(FocusUi.spacer(this, 10))
        card.addView(FocusUi.primaryButton(this, tokens, getString(R.string.app_limits_add_open_cap)) { pickAppFor(false) })
    }

    private fun pickAppFor(minutes: Boolean) {
        val choices = AppCatalog.launchable(this).map { app ->
            FocusDialog.Choice(app.packageName, app.label, app.category.label(this), app.packageName)
        }
        FocusDialog.singleChoice(
            this,
            title = if (minutes) {
                getString(R.string.app_limits_pick_minute_title)
            } else {
                getString(R.string.app_limits_pick_open_title)
            },
            subtitle = null,
            choices = choices,
            selectedKey = null
        ) { key ->
            if (minutes) askMinutes(key) else askOpens(key)
        }
    }

    private fun askMinutes(packageName: String) {
        FocusDialog.textInput(
            this,
            title = AppCatalog.label(this, packageName),
            subtitle = getString(R.string.app_limits_minutes_subtitle),
            hint = getString(R.string.common_minutes_hint),
            value = (AppLimits.allMinuteLimits(this)[packageName] ?: "").toString(),
            numeric = true
        ) { value ->
            if (!AppLimits.setMinuteLimit(this, packageName, value.toIntOrNull())) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
            }
            refresh()
        }
    }

    private fun askOpens(packageName: String) {
        FocusDialog.textInput(
            this,
            title = AppCatalog.label(this, packageName),
            subtitle = getString(R.string.app_limits_opens_subtitle),
            hint = getString(R.string.common_opens_hint),
            value = (AppLimits.allOpenLimits(this)[packageName] ?: "").toString(),
            numeric = true
        ) { value ->
            if (!AppLimits.setOpenLimit(this, packageName, value.toIntOrNull())) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
            }
            refresh()
        }
    }

    /**
     * The escape valve for budgets. A limit with no way to make an exception is
     * a limit people work around by uninstalling the app that set it.
     */
    private fun buildBreakCard(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.app_limits_allow_breaks_title),
                getString(R.string.app_limits_allow_breaks_subtitle),
                CapabilityRegistry.isEnabled(this, Capabilities.TAKE_A_BREAK)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.TAKE_A_BREAK, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )

        if (!CapabilityRegistry.isEnabled(this, Capabilities.TAKE_A_BREAK)) return@card

        card.addView(
            FocusUi.sliderRow(
                this,
                tokens,
                getString(R.string.app_limits_break_length_label),
                1,
                30,
                TakeABreak.breakMinutes(this),
                { getString(R.string.app_limits_min_suffix, it) }
            ) { value -> TakeABreak.setBreakMinutes(this, value) }
        )
        card.addView(
            FocusUi.sliderRow(
                this,
                tokens,
                getString(R.string.app_limits_breaks_per_day_label),
                0,
                10,
                TakeABreak.dailyMax(this),
                { if (it == 0) getString(R.string.common_none) else it.toString() }
            ) { value -> TakeABreak.setDailyMax(this, value) }
        )
        card.addView(
            FocusUi.caption(
                this,
                tokens,
                getString(R.string.app_limits_left_today, TakeABreak.remainingToday(this))
            )
        )
    }
}
