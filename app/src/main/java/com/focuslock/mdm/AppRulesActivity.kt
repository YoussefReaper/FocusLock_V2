package com.focuslock.mdm

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.util.Locale

/**
 * Per-app rules.
 *
 * This screen is the direct replacement for the old multi-choice dialog over
 * every installed package. Two things make it usable instead of merely present:
 * a real ladder of outcomes per app rather than a single on/off tick, and bulk
 * rules by category so nobody has to tap through two hundred rows to block
 * "social".
 */
class AppRulesActivity : FocusScreenActivity() {

    private var query: String = ""
    private var filter: Int = 0

    /** Once the "show the other N apps" link is tapped, every category stays fully expanded. */
    private var expanded: Boolean = false

    private companion object {
        /** Rows shown per category before "show the other N apps" takes over - keeps a 50+ app phone from being one long scroll. */
        const val PREVIEW_PER_CATEGORY = 3
    }

    private val filters by lazy {
        listOf(
            getString(R.string.app_rules_filter_all),
            getString(R.string.app_rules_filter_blocked),
            getString(R.string.app_rules_filter_paused),
            getString(R.string.app_rules_filter_budgeted),
            getString(R.string.app_rules_filter_open)
        )
    }

    override fun screenTitle(): String = getString(R.string.app_rules_title)

    override fun screenSubtitle(): String = getString(R.string.app_rules_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildCategoryCard())
        column.addView(sectionLabel(getString(R.string.app_rules_individual_apps_section)))
        column.addView(buildSearch())
        column.addView(buildFilterChips())
        column.addView(buildAppList())
    }

    /** Counts ride along on every chip label, not just "All" - the doc's after mockup shows "Blocked 44", not just "Blocked". */
    private fun buildFilterChips(): View {
        val counts = IntArray(filters.size)
        AppCatalog.launchable(this).forEach { app ->
            counts[0]++
            when (AppRules.effectivePolicy(this, app.packageName)) {
                AppPolicy.BLOCK, AppPolicy.HIDE -> counts[1]++
                AppPolicy.FRICTION -> counts[2]++
                AppPolicy.LIMIT -> counts[3]++
                AppPolicy.ALLOW -> counts[4]++
            }
        }
        val labels = filters.mapIndexed { index, label ->
            getString(R.string.app_rules_filter_count_format, label, counts[index])
        }
        return FocusUi.chipStrip(this, tokens, labels, filter) { index ->
            filter = index
            refresh()
        }
    }

    // ── Bulk by category ──────────────────────────────────────────

    private fun buildCategoryCard(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, getString(R.string.app_rules_by_category)))
        card.addView(FocusUi.spacer(this, 4))
        card.addView(FocusUi.secondary(this, tokens, getString(R.string.app_rules_category_blurb)))
        card.addView(FocusUi.spacer(this, 8))

        AppCategory.ruleTargets.forEach { category ->
            val current = AppRules.categoryPolicy(this, category)
            val count = AppCatalog.packagesInCategory(this, category).size
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    category.label(this),
                    getString(R.string.app_rules_category_count_subtitle, count, category.blurb(this)),
                    trailing = FocusUi.pill(
                        this,
                        tokens,
                        current?.label(this) ?: getString(R.string.app_rules_no_rule),
                        if (current == null) tokens.textMuted else policyColor(current)
                    )
                ) { pickCategoryPolicy(category, current) }
            )
        }
    }

    private fun pickCategoryPolicy(category: AppCategory, current: AppPolicy?) {
        val choices = listOf(
            FocusDialog.Choice(
                "none",
                getString(R.string.app_rules_no_rule),
                getString(R.string.app_rules_no_rule_subtitle)
            )
        ) + AppPolicy.ladder.map { policy ->
            FocusDialog.Choice(policy.id, policy.label(this), policy.blurb(this))
        }

        FocusDialog.singleChoice(
            this,
            title = category.label(this),
            subtitle = category.blurb(this),
            choices = choices,
            selectedKey = current?.id ?: "none"
        ) { key ->
            if (!AppRules.setCategoryPolicy(
                    this,
                    category,
                    if (key == "none") null else AppPolicy.fromId(key)
                )
            ) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
            }
            refresh()
        }
    }

    // ── Search and list ───────────────────────────────────────────

    private fun buildSearch(): View {
        val field = FocusUi.input(this, tokens, getString(R.string.app_rules_search_hint), query)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                renderList()
            }
        })
        return field
    }

    private lateinit var listHost: LinearLayout

    private fun buildAppList(): View {
        listHost = FocusUi.column(this)
        renderList()
        return listHost
    }

    private fun renderList() {
        if (!this::listHost.isInitialized) return
        listHost.removeAllViews()

        val needle = query.trim().lowercase(Locale.getDefault())
        val searching = needle.isNotEmpty()
        val apps = AppCatalog.launchable(this)
            .filter { app ->
                needle.isEmpty() ||
                    app.label.lowercase(Locale.getDefault()).contains(needle) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(needle)
            }
            .filter { app -> matchesFilter(AppRules.effectivePolicy(this, app.packageName)) }

        if (apps.isEmpty()) {
            listHost.addView(FocusUi.emptyState(this, tokens, getString(R.string.app_rules_no_apps_match)))
            return
        }

        // A search or a filter narrows the list to something small and specific
        // enough that grouping it back into categories would just be noise -
        // one plain card of results reads better there. Left at "All" with
        // nothing typed, the full install list groups under category headers
        // instead of one 50+ row card (see the design doc's before/after for
        // this screen).
        if (searching || filter != 0) {
            val card = FocusUi.card(this, tokens)
            val sorted = apps.sortedBy { it.label.lowercase(Locale.getDefault()) }
            sorted.forEachIndexed { index, app ->
                card.addView(buildAppRow(app))
                if (index < sorted.size - 1) card.addView(FocusUi.divider(this, tokens))
            }
            listHost.addView(card)
            return
        }

        val grouped = apps.groupBy { it.category }
        var hidden = 0

        AppCategory.values().filter { grouped.containsKey(it) }.forEach { category ->
            val inCategory = grouped.getValue(category).sortedBy { it.label.lowercase(Locale.getDefault()) }
            listHost.addView(sectionLabel(categoryHeader(category, inCategory)))

            val visible = if (expanded) inCategory else inCategory.take(PREVIEW_PER_CATEGORY)
            hidden += inCategory.size - visible.size

            val card = FocusUi.card(this, tokens)
            visible.forEachIndexed { index, app ->
                card.addView(buildAppRow(app))
                if (index < visible.size - 1) card.addView(FocusUi.divider(this, tokens))
            }
            listHost.addView(card)
        }

        if (hidden > 0) {
            listHost.addView(
                FocusUi.ghostButton(this, tokens, getString(R.string.app_rules_show_more_apps, hidden)) {
                    expanded = true
                    renderList()
                }
            )
        }
    }

    /** "Social · 3 blocked", "Essentials · never blocked", "Games · 14 apps, budgeted" - the overline uppercases it. */
    private fun categoryHeader(category: AppCategory, apps: List<InstalledApp>): String {
        if (apps.all { AppRules.isAlwaysAllowed(this, it.packageName) }) {
            return getString(R.string.app_rules_category_header_never_blocked, category.label(this))
        }
        val policies = apps.map { AppRules.effectivePolicy(this, it.packageName) }
        val blocked = policies.count { it == AppPolicy.BLOCK || it == AppPolicy.HIDE }
        return when {
            blocked == apps.size -> getString(R.string.app_rules_category_header_blocked, category.label(this), blocked)
            policies.all { it == AppPolicy.LIMIT } ->
                getString(R.string.app_rules_category_header_budgeted, category.label(this), apps.size)
            policies.all { it == AppPolicy.ALLOW } ->
                getString(R.string.app_rules_category_header_allowed, category.label(this), apps.size)
            else -> getString(R.string.app_rules_category_header_generic, category.label(this), apps.size)
        }
    }

    private fun matchesFilter(policy: AppPolicy): Boolean = when (filter) {
        1 -> policy == AppPolicy.BLOCK || policy == AppPolicy.HIDE
        2 -> policy == AppPolicy.FRICTION
        3 -> policy == AppPolicy.LIMIT
        4 -> policy == AppPolicy.ALLOW
        else -> true
    }

    private fun buildAppRow(app: InstalledApp): View {
        val policy = AppRules.effectivePolicy(this, app.packageName)
        val alwaysAllowed = AppRules.isAlwaysAllowed(this, app.packageName)

        val subtitle = when {
            alwaysAllowed -> getString(R.string.app_rules_always_allowed_subtitle)
            policy == AppPolicy.LIMIT -> {
                val limit = AppLimits.minuteLimit(this, app.packageName) ?: 0
                if (limit > 0) {
                    getString(R.string.app_rules_budget_used_subtitle, AppLimits.usedMinutesToday(this, app.packageName), limit)
                } else {
                    usageSubtitle(app.packageName)
                }
            }
            policy == AppPolicy.FRICTION -> {
                val seconds = CapabilityRegistry.getIntParam(this, Capabilities.LAUNCH_FRICTION, "seconds", 8)
                    .coerceIn(3, 60)
                getString(R.string.app_rules_pause_first_subtitle, seconds)
            }
            else -> usageSubtitle(app.packageName)
        }

        return FocusUi.listRow(
            this,
            tokens,
            app.label,
            subtitle,
            trailing = FocusUi.pill(this, tokens, policy.label(this), policyColor(policy)),
            leading = FocusUi.appIcon(this, tokens, app.packageName, 32)
        ) { openAppSheet(app) }
    }

    private fun usageSubtitle(packageName: String): String {
        val minutes = AppLimits.usedMinutesToday(this, packageName)
        return if (minutes <= 0) {
            getString(R.string.app_rules_no_usage_today)
        } else {
            getString(R.string.app_rules_usage_today, UsageAnalytics.formatDuration(minutes * 60_000L))
        }
    }

    private fun policyColor(policy: AppPolicy): Int = when (policy) {
        AppPolicy.ALLOW -> tokens.success
        AppPolicy.FRICTION -> tokens.accent
        AppPolicy.LIMIT -> tokens.warning
        AppPolicy.BLOCK, AppPolicy.HIDE -> tokens.danger
    }

    /**
     * One sheet per app holding every decision about it: the ladder, the daily
     * budgets, the always-allowed exemption and the category correction. Having
     * them in one place is what stops "why is this still opening" confusion.
     */
    private fun openAppSheet(app: InstalledApp) {
        FocusDialog.custom(
            this,
            title = app.label,
            subtitle = app.packageName,
            confirmLabel = null,
            cancelLabel = getString(R.string.app_rules_done)
        ) { body, dialogTokens, refreshBody ->
            body.addView(FocusUi.caption(this, dialogTokens, getString(R.string.app_rules_what_happens_caption)))

            val current = AppRules.effectivePolicy(this, app.packageName)
            AppPolicy.ladder.forEach { policy ->
                val marker = FocusUi.pill(
                    this,
                    dialogTokens,
                    if (policy == current) getString(R.string.common_now) else getString(R.string.common_set),
                    if (policy == current) dialogTokens.accent else dialogTokens.textMuted
                )
                body.addView(
                    FocusUi.listRow(this, dialogTokens, policy.label(this), policy.blurb(this), trailing = marker) {
                        val applied = AppRules.setPolicy(this, app.packageName, policy)
                        refresh()
                        refreshBody()
                        FocusDialog.toast(
                            this,
                            if (applied) {
                                getString(R.string.app_rules_policy_applied_toast, app.label, policy.label(this).lowercase())
                            } else {
                                SessionLock.refusalMessage(this)
                            }
                        )
                    }
                )
            }

            body.addView(FocusUi.divider(this, dialogTokens, 8))

            val minuteLimit = AppLimits.allMinuteLimits(this)[app.packageName] ?: 0
            body.addView(
                FocusUi.listRow(
                    this,
                    dialogTokens,
                    getString(R.string.app_rules_daily_minutes),
                    if (minuteLimit > 0) {
                        getString(
                            R.string.app_rules_daily_minutes_subtitle,
                            minuteLimit,
                            AppLimits.usedMinutesToday(this, app.packageName)
                        )
                    } else {
                        getString(R.string.app_rules_no_budget)
                    },
                    trailing = FocusUi.chevron(this, dialogTokens)
                ) { askMinuteLimit(app, refreshBody) }
            )

            val openLimit = AppLimits.allOpenLimits(this)[app.packageName] ?: 0
            body.addView(
                FocusUi.listRow(
                    this,
                    dialogTokens,
                    getString(R.string.app_rules_daily_opens),
                    if (openLimit > 0) {
                        getString(
                            R.string.app_rules_daily_opens_subtitle,
                            openLimit,
                            AppLimits.opensToday(this, app.packageName)
                        )
                    } else {
                        getString(R.string.app_rules_no_cap)
                    },
                    trailing = FocusUi.chevron(this, dialogTokens)
                ) { askOpenLimit(app, refreshBody) }
            )

            body.addView(FocusUi.divider(this, dialogTokens, 8))

            body.addView(
                FocusUi.toggleRow(
                    this,
                    dialogTokens,
                    getString(R.string.app_rules_always_allowed_toggle_title),
                    getString(R.string.app_rules_always_allowed_toggle_subtitle),
                    AppRules.isAlwaysAllowed(this, app.packageName)
                ) { checked ->
                    val current2 = AppRules.alwaysAllowedRaw(this).toMutableSet()
                    if (checked) current2.add(app.packageName) else current2.remove(app.packageName)
                    if (!AppRules.setAlwaysAllowed(this, current2)) {
                        FocusDialog.toast(this, SessionLock.refusalMessage(this))
                    }
                    refresh()
                    refreshBody()
                }
            )

            body.addView(
                FocusUi.listRow(
                    this,
                    dialogTokens,
                    getString(R.string.app_rules_category_label),
                    getString(R.string.app_rules_category_tap_to_correct, app.category.label(this)),
                    trailing = FocusUi.chevron(this, dialogTokens)
                ) { pickCategory(app, refreshBody) }
            )

            if (AppRules.explicitPolicy(this, app.packageName) != null) {
                body.addView(
                    FocusUi.ghostButton(this, dialogTokens, getString(R.string.app_rules_clear_own_rule)) {
                        if (!AppRules.clearPolicy(this, app.packageName)) {
                            FocusDialog.toast(this, SessionLock.refusalMessage(this))
                        }
                        refresh()
                        refreshBody()
                    }
                )
            }
        }
    }

    private fun askMinuteLimit(app: InstalledApp, onSaved: (() -> Unit)? = null) {
        FocusDialog.textInput(
            this,
            title = getString(R.string.app_rules_minute_limit_title, app.label),
            subtitle = getString(R.string.app_rules_minute_limit_subtitle),
            hint = getString(R.string.common_minutes_hint),
            value = (AppLimits.minuteLimit(this, app.packageName) ?: "").toString(),
            numeric = true
        ) { value ->
            if (!AppLimits.setMinuteLimit(this, app.packageName, value.toIntOrNull())) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
            } else if (value.toIntOrNull() != null &&
                !CapabilityRegistry.isEnabled(this, Capabilities.PER_APP_LIMITS)
            ) {
                offerToEnable(Capabilities.PER_APP_LIMITS)
            }
            refresh()
            onSaved?.invoke()
        }
    }

    private fun askOpenLimit(app: InstalledApp, onSaved: (() -> Unit)? = null) {
        FocusDialog.textInput(
            this,
            title = getString(R.string.app_rules_open_limit_title, app.label),
            subtitle = getString(R.string.app_rules_open_limit_subtitle),
            hint = getString(R.string.common_opens_hint),
            value = (AppLimits.openLimit(this, app.packageName) ?: "").toString(),
            numeric = true
        ) { value ->
            if (!AppLimits.setOpenLimit(this, app.packageName, value.toIntOrNull())) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
            } else if (value.toIntOrNull() != null &&
                !CapabilityRegistry.isEnabled(this, Capabilities.OPEN_COUNT_LIMITS)
            ) {
                offerToEnable(Capabilities.OPEN_COUNT_LIMITS)
            }
            refresh()
            onSaved?.invoke()
        }
    }

    /**
     * Setting a budget while its capability is off would silently do nothing,
     * so ask rather than either failing quietly or flipping the switch for them.
     */
    private fun offerToEnable(capabilityId: String) {
        val spec = Capabilities.spec(capabilityId) ?: return
        FocusDialog.alert(
            this,
            title = getString(R.string.app_rules_turn_on_title, spec.label(this)),
            message = getString(R.string.app_rules_turn_on_message, spec.label(this).lowercase()),
            confirmLabel = getString(R.string.app_rules_turn_it_on),
            cancelLabel = getString(R.string.app_rules_leave_it_off),
            onConfirm = {
                if (!CapabilityRegistry.setEnabled(this, capabilityId, true)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )
    }

    private fun pickCategory(app: InstalledApp, onSaved: (() -> Unit)? = null) {
        FocusDialog.singleChoice(
            this,
            title = getString(R.string.app_rules_category_for_title, app.label),
            subtitle = getString(R.string.app_rules_category_for_subtitle),
            choices = AppCategory.values().map { category ->
                FocusDialog.Choice(category.id, category.label(this), category.blurb(this))
            },
            selectedKey = app.category.id
        ) { key ->
            if (!AppCatalog.setCategoryOverride(this, app.packageName, AppCategory.fromId(key))) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
            }
            refresh()
            onSaved?.invoke()
        }
    }
}
