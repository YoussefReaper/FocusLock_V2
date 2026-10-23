package com.focuslock.mdm

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * The setup quiz.
 *
 * Six taps, skippable at every step, and it never applies anything on its own:
 * the last screen shows the whole proposed setup and waits. That matters more
 * than it sounds — an app that configures itself from a quiz has taken the
 * decision away from the person, which is exactly the failure mode the
 * Capability Registry exists to prevent.
 *
 * Skipping is a first-class path, not a punishment: it lands on a sane default
 * setup that is no worse than the old hardcoded one.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var host: FrameLayout

    private var step = 0
    private var rerun = false

    private val goals = LinkedHashSet<String>()
    private val pulls = LinkedHashSet<String>()
    private val hardTimes = LinkedHashSet<String>()
    private var strictness = 1
    private val essentials = LinkedHashSet<String>()
    private val chosenDistractions = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Migration.run(this)
        rerun = intent.getBooleanExtra(EXTRA_RERUN, false)

        essentials.addAll(AppCatalog.detectEssentials(this))
        chosenDistractions.addAll(UsageAnalytics.suggestedDistractions(this, 10))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step > 0) {
                    step -= 1
                    render()
                } else if (rerun) {
                    finish()
                }
            }
        })

        render()
    }

    override fun onResume() {
        super.onResume()
        // Refresh live when returning from Android Settings after granting permissions.
        render()
    }

    // ── Shell ─────────────────────────────────────────────────────

    private fun render() {
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens)

        val root = FocusUi.screenRoot(this, tokens)
        host = FrameLayout(this)
        host.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val column = FocusUi.column(this, tokens.density.contentPaddingDp)
        column.addView(buildProgress())

        when (step) {
            0 -> buildWelcome(column)
            1 -> buildDistractionsAndEssentials(column)
            2 -> buildPermissionsStep(column)
            else -> buildReview(column)
        }

        val scroll = FocusUi.scroll(this, column)
        host.addView(scroll)
        root.addView(host)
        setContentView(root)

        Motion.fadeIn(column, tokens)
    }

    private fun buildProgress(): View {
        val container = FocusUi.column(this)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(this@OnboardingActivity, 20) }

        val row = FocusUi.row(this)
        for (i in 0..TOTAL_STEPS) {
            val segment = View(this)
            segment.background = FocusUi.roundedShape(
                this,
                if (i <= step) tokens.accent else tokens.track,
                3
            )
            segment.layoutParams = LinearLayout.LayoutParams(0, FocusUi.dp(this, 4), 1f).apply {
                marginEnd = FocusUi.dp(this@OnboardingActivity, 5)
            }
            row.addView(segment)
        }
        container.addView(row)

        if (step in 1 until TOTAL_STEPS) {
            val skip = FocusUi.ghostButton(this, tokens, getString(R.string.onboarding_skip_questions)) { skipToDefaults() }
            skip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                FocusUi.dp(this, tokens.density.quickButtonHeightDp)
            ).apply { topMargin = FocusUi.dp(this@OnboardingActivity, 12) }
            container.addView(skip)
        }
        return container
    }

    private fun next() {
        step += 1
        render()
    }

    private fun buildDistractionsAndEssentials(column: LinearLayout) {
        column.addView(FocusUi.title(this, tokens, getString(R.string.onboarding_pulls_title)))
        column.addView(FocusUi.spacer(this, 8))
        column.addView(FocusUi.secondary(this, tokens, getString(R.string.onboarding_pulls_subtitle)))
        column.addView(FocusUi.spacer(this, 16))

        val pullOptions = listOf(
            "shorts" to getString(R.string.onboarding_pull_shorts),
            "social" to getString(R.string.onboarding_pull_social),
            "games" to getString(R.string.onboarding_pull_games),
            "browsing" to getString(R.string.onboarding_pull_browsing),
            "messaging" to getString(R.string.onboarding_pull_messaging)
        )
        pullOptions.forEach { option ->
            column.addView(
                buildOptionRow(option.second, option.first in pulls) {
                    if (option.first in pulls) pulls.remove(option.first) else pulls.add(option.first)
                    render()
                }
            )
            column.addView(FocusUi.spacer(this, 10))
        }

        column.addView(FocusUi.spacer(this, 12))
        buildEssentials(column)
    }

    private fun buildPermissionsStep(column: LinearLayout) {
        column.addView(FocusUi.title(this, tokens, getString(R.string.onboarding_perms_title)))
        column.addView(FocusUi.spacer(this, 8))
        column.addView(FocusUi.secondary(this, tokens, getString(R.string.onboarding_perms_subtitle)))
        column.addView(FocusUi.spacer(this, 18))

        val card = FocusUi.card(this, tokens, elevated = true)
        val hasUsage = SetupChecks.hasUsageAccess(this)
        val hasOverlay = SetupChecks.canDrawOverlays(this)

        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.perms_usage_title),
                getString(R.string.perms_usage_detail),
                trailing = if (hasUsage) {
                    FocusUi.pill(this, tokens, getString(R.string.common_granted), tokens.success)
                } else {
                    FocusUi.smallButton(this, tokens, getString(R.string.dashboard_grant)) {
                        PermissionIntents.launchFirst(this, PermissionIntents.usageAccess(this))
                    }
                }
            ) {
                if (!hasUsage) PermissionIntents.launchFirst(this, PermissionIntents.usageAccess(this))
            }
        )
        card.addView(FocusUi.divider(this, tokens))
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.perms_overlay_title),
                getString(R.string.perms_overlay_detail),
                trailing = if (hasOverlay) {
                    FocusUi.pill(this, tokens, getString(R.string.common_granted), tokens.success)
                } else {
                    FocusUi.smallButton(this, tokens, getString(R.string.dashboard_grant)) {
                        PermissionIntents.launchFirst(this, PermissionIntents.overlay(this))
                    }
                }
            ) {
                if (!hasOverlay) PermissionIntents.launchFirst(this, PermissionIntents.overlay(this))
            }
        )
        column.addView(card)

        column.addView(FocusUi.spacer(this, 10))
        column.addView(FocusUi.caption(this, tokens, getString(R.string.onboarding_perms_optional_note)))
        column.addView(FocusUi.spacer(this, 18))
        column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.common_continue)) { next() })
    }

    // ── Steps ─────────────────────────────────────────────────────

    private fun buildWelcome(column: LinearLayout) {
        column.addView(FocusUi.spacer(this, 24))
        column.addView(FocusUi.display(this, tokens, "FocusLock"))
        column.addView(FocusUi.spacer(this, 14))
        column.addView(
            FocusUi.body(
                this,
                tokens,
                getString(R.string.onboarding_intro_body)
            )
        )
        column.addView(FocusUi.spacer(this, 16))
        column.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.onboarding_intro_promise)
            )
        )
        column.addView(FocusUi.spacer(this, 28))
        column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.common_start)) { next() })
        column.addView(FocusUi.spacer(this, 10))
        column.addView(
            FocusUi.secondaryButton(this, tokens, getString(R.string.onboarding_skip_defaults)) { skipToDefaults() }
        )
    }

    private fun buildGoals(column: LinearLayout) {
        question(
            column,
            getString(R.string.onboarding_goals_title),
            getString(R.string.onboarding_goals_subtitle),
            listOf(
                "study" to getString(R.string.onboarding_goal_study),
                "work" to getString(R.string.onboarding_goal_work),
                "sleep" to getString(R.string.onboarding_goal_sleep),
                "earn" to getString(R.string.onboarding_goal_earn),
                "less" to getString(R.string.onboarding_goal_less)
            ),
            goals
        )
    }

    private fun buildPulls(column: LinearLayout) {
        question(
            column,
            getString(R.string.onboarding_pulls_title),
            getString(R.string.onboarding_pulls_subtitle),
            listOf(
                "shorts" to getString(R.string.onboarding_pull_shorts),
                "social" to getString(R.string.onboarding_pull_social),
                "games" to getString(R.string.onboarding_pull_games),
                "browsing" to getString(R.string.onboarding_pull_browsing),
                "messaging" to getString(R.string.onboarding_pull_messaging)
            ),
            pulls
        )
    }

    private fun buildStrictness(column: LinearLayout) {
        column.addView(FocusUi.title(this, tokens, getString(R.string.onboarding_firm_title)))
        column.addView(FocusUi.spacer(this, 8))
        column.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.onboarding_firm_note)
            )
        )
        column.addView(FocusUi.spacer(this, 18))

        val options = listOf(
            Triple(getString(R.string.onboarding_firm_gentle_label), getString(R.string.onboarding_firm_gentle_detail), 0),
            Triple(getString(R.string.onboarding_firm_firm_label), getString(R.string.onboarding_firm_firm_detail), 1),
            Triple(getString(R.string.onboarding_firm_all_label), getString(R.string.onboarding_firm_all_detail), 2)
        )

        options.forEach { option ->
            val selected = strictness == option.third
            val card = FocusUi.card(this, tokens, elevated = selected) {
                strictness = option.third
                render()
            }
            if (selected) {
                card.background = FocusUi.roundedShape(this, tokens.surfaceAlt, tokens.radiusDp, tokens.accent, 2)
            }
            card.addView(FocusUi.heading(this, tokens, option.first))
            card.addView(FocusUi.spacer(this, 6))
            card.addView(FocusUi.secondary(this, tokens, option.second))
            column.addView(card)
        }

        column.addView(FocusUi.spacer(this, 12))
        column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.common_continue)) { next() })
    }

    private fun buildHardTimes(column: LinearLayout) {
        question(
            column,
            getString(R.string.onboarding_times_title),
            getString(R.string.onboarding_times_subtitle),
            listOf(
                "morning" to getString(R.string.onboarding_time_morning),
                "school" to getString(R.string.onboarding_time_school),
                "evening" to getString(R.string.onboarding_time_evening),
                "night" to getString(R.string.onboarding_time_night)
            ),
            hardTimes
        )
    }

    private fun buildEssentials(column: LinearLayout) {
        column.addView(FocusUi.title(this, tokens, getString(R.string.onboarding_essentials_title)))
        column.addView(FocusUi.spacer(this, 8))
        column.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.onboarding_essentials_subtitle)
            )
        )
        column.addView(FocusUi.spacer(this, 16))

        val card = FocusUi.card(this, tokens)
        // Only apps with an icon. The background call services (Phone
        // Services, Manage calls) stay allowed regardless - they are what
        // makes a call ring through a kiosk session - but a list of package
        // names nobody recognises is the wrong thing to ask a beginner about.
        val launchable = AppCatalog.launchable(this).map { it.packageName }.toSet()
        val candidates = (AppCatalog.detectEssentials(this) + essentials).distinct().filter { it in launchable }
        val labels = candidates.associateWith { AppCatalog.label(this, it) }
        val duplicated = labels.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        candidates.forEach { packageName ->
            val label = labels.getValue(packageName)
            card.addView(
                FocusUi.toggleRow(
                    this,
                    tokens,
                    label,
                    // The package name only where two apps share a name and
                    // there is no other way to tell them apart.
                    if (label in duplicated) packageName else null,
                    packageName in essentials
                ) { checked ->
                    if (checked) essentials.add(packageName) else essentials.remove(packageName)
                }
            )
        }
        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.smallButton(this, tokens, getString(R.string.onboarding_add_more_apps)) {
                val choices = AppCatalog.launchable(this).map { app ->
                    FocusDialog.Choice(app.packageName, app.label, app.category.label(this), app.packageName)
                }
                FocusDialog.multiChoice(
                    this,
                    getString(R.string.onboarding_never_block_these),
                    null,
                    choices,
                    essentials
                ) { selected ->
                    // The picker only lists apps with an icon, so anything else
                    // already on the list - the call services above all - is
                    // kept rather than dropped by a list it was never shown in.
                    val unseen = essentials.filter { pkg -> choices.none { it.key == pkg } }
                    essentials.clear()
                    essentials.addAll(unseen)
                    essentials.addAll(selected)
                    render()
                }
            }
        )
        column.addView(card)

        column.addView(FocusUi.spacer(this, 12))
        column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.common_continue)) { next() })
    }

    private fun question(
        column: LinearLayout,
        title: String,
        subtitle: String,
        options: List<Pair<String, String>>,
        selection: MutableSet<String>
    ) {
        column.addView(FocusUi.title(this, tokens, title))
        column.addView(FocusUi.spacer(this, 8))
        column.addView(FocusUi.secondary(this, tokens, subtitle))
        column.addView(FocusUi.spacer(this, 18))

        options.forEach { option ->
            column.addView(
                buildOptionRow(option.second, option.first in selection) {
                    if (option.first in selection) selection.remove(option.first) else selection.add(option.first)
                    render()
                }
            )
            column.addView(FocusUi.spacer(this, 10))
        }

        column.addView(FocusUi.spacer(this, 4))
        column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.common_continue)) { next() })
    }

    /**
     * The design doc's own quiz row (1g: "one question a screen") - a
     * selectable card with a checkmark when chosen, not a switch. An answer
     * is picked, not left on, and re-rendering the whole step on every tap
     * (rather than flipping a switch in place) is what lets the row's own
     * fill and border actually change, not just its trailing control.
     */
    private fun buildOptionRow(label: String, selected: Boolean, onToggle: () -> Unit): View {
        val row = FocusUi.row(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val padH = FocusUi.dp(this, 18)
        val padV = FocusUi.dp(this, 16)
        row.setPadding(padH, padV, padH, padV)
        row.background = FocusUi.withRipple(
            this,
            FocusUi.roundedShape(
                this,
                if (selected) tokens.accentSoft else tokens.surface,
                tokens.cardRadiusDp,
                if (selected) tokens.accent else tokens.divider,
                if (selected) 2 else 1
            ),
            tokens
        )
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onToggle() }

        val text = FocusUi.rowTitle(this, tokens, label)
        if (!selected) text.setTextColor(tokens.textSecondary)
        text.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(text)

        if (selected) {
            row.addView(FocusUi.spacerH(this, 10))
            val check = FocusUi.rowTitle(this, tokens, "✓")
            check.setTextColor(tokens.accent)
            row.addView(check)
        }

        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return row
    }

    // ── Review ────────────────────────────────────────────────────

    private fun buildReview(column: LinearLayout) {
        val plan = buildPlan()

        column.addView(FocusUi.title(this, tokens, getString(R.string.onboarding_summary_title)))
        column.addView(FocusUi.spacer(this, 8))
        column.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.onboarding_summary_body)
            )
        )
        column.addView(FocusUi.spacer(this, 18))

        val modeCard = FocusUi.card(this, tokens)
        modeCard.addView(FocusUi.caption(this, tokens, getString(R.string.onboarding_section_mode)))
        modeCard.addView(FocusUi.spacer(this, 6))
        modeCard.addView(FocusUi.heading(this, tokens, plan.mode.label(this)))
        modeCard.addView(FocusUi.spacer(this, 4))
        modeCard.addView(FocusUi.secondary(this, tokens, plan.mode.oneLiner(this)))
        modeCard.addView(FocusUi.spacer(this, 4))
        modeCard.addView(FocusUi.caption(this, tokens, plan.mode.exitLine(this)))
        column.addView(modeCard)

        val blockCard = FocusUi.card(this, tokens)
        blockCard.addView(FocusUi.caption(this, tokens, getString(R.string.onboarding_section_blocking)))
        blockCard.addView(FocusUi.spacer(this, 6))
        if (plan.block.isEmpty()) {
            blockCard.addView(FocusUi.secondary(this, tokens, getString(R.string.onboarding_nothing_blocked)))
        } else {
            plan.block.forEach { packageName ->
                blockCard.addView(
                    FocusUi.toggleRow(
                        this,
                        tokens,
                        AppCatalog.label(this, packageName),
                        AppCatalog.categoryOf(this, packageName).label(this),
                        packageName in chosenDistractions
                    ) { checked ->
                        if (checked) chosenDistractions.add(packageName) else chosenDistractions.remove(packageName)
                    }
                )
            }
        }
        column.addView(blockCard)

        val capabilityCard = FocusUi.card(this, tokens)
        capabilityCard.addView(FocusUi.caption(this, tokens, getString(R.string.onboarding_section_enabled)))
        capabilityCard.addView(FocusUi.spacer(this, 6))
        plan.enable.forEach { id ->
            Capabilities.spec(id)?.let { spec ->
                capabilityCard.addView(
                    FocusUi.listRow(this, tokens, spec.label(this), spec.blurb(this))
                )
            }
        }
        column.addView(capabilityCard)

        if (plan.notes.isNotEmpty()) {
            val notesCard = FocusUi.card(this, tokens)
            notesCard.addView(FocusUi.caption(this, tokens, getString(R.string.onboarding_section_notes)))
            notesCard.addView(FocusUi.spacer(this, 6))
            plan.notes.forEach { note ->
                notesCard.addView(FocusUi.listRow(this, tokens, note.first, note.second))
            }
            column.addView(notesCard)
        }

        val essentialsCard = FocusUi.card(this, tokens)
        essentialsCard.addView(FocusUi.caption(this, tokens, getString(R.string.onboarding_section_essentials)))
        essentialsCard.addView(FocusUi.spacer(this, 6))
        essentialsCard.addView(
            FocusUi.secondary(
                this,
                tokens,
                if (essentials.isEmpty()) {
                    getString(R.string.onboarding_nothing_chosen)
                } else {
                    essentials.joinToString { AppCatalog.label(this, it) }
                }
            )
        )
        column.addView(essentialsCard)

        column.addView(FocusUi.spacer(this, 14))
        column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.onboarding_use_setup)) { apply(plan) })
        column.addView(FocusUi.spacer(this, 10))
        column.addView(
            FocusUi.secondaryButton(this, tokens, getString(R.string.onboarding_start_over)) {
                step = 1
                render()
            }
        )
        column.addView(FocusUi.spacer(this, 10))
        column.addView(
            FocusUi.ghostButton(this, tokens, getString(R.string.onboarding_use_defaults)) { skipToDefaults() }
        )
        column.addView(FocusUi.spacer(this, 24))
    }

    // ── The recommendation ────────────────────────────────────────

    private data class Plan(
        val mode: FocusMode,
        val enable: List<String>,
        val disable: List<String>,
        val block: List<String>,
        val notes: List<Pair<String, String>>
    )

    /**
     * Turns the answers into a proposal.
     *
     * The mapping is intentionally conservative: strictness sets the ceiling,
     * the pulls decide what gets suggested for blocking, and the hard times
     * decide whether a schedule or bedtime is offered. Anything not clearly
     * implied by an answer is left alone rather than guessed at.
     */
    private fun buildPlan(): Plan {
        val enable = LinkedHashSet<String>()
        val disable = LinkedHashSet<String>()
        val notes = ArrayList<Pair<String, String>>()

        enable.add(Capabilities.ALWAYS_ALLOWED)
        enable.add(Capabilities.SELF_COMPASSION_COPY)
        enable.add(Capabilities.TAKE_A_BREAK)
        enable.add(Capabilities.REPLACEMENT_SUGGESTIONS)
        enable.add(Capabilities.SAFE_BROWSER)
        enable.add(Capabilities.TEXT_SEARCH)

        val mode = when (strictness) {
            0 -> {
                enable.add(Capabilities.ADVISORY_MODE)
                enable.add(Capabilities.LAUNCH_FRICTION)
                disable.add(Capabilities.SUSPEND_BLOCKED_APPS)
                disable.add(Capabilities.HIDE_BLOCKED_APPS)
                FocusMode.SOFT
            }
            2 -> {
                enable.add(Capabilities.KIOSK_MODE)
                enable.add(Capabilities.APP_BLOCK)
                enable.add(Capabilities.WEB_BLOCK)
                enable.add(Capabilities.SUSPEND_BLOCKED_APPS)
                enable.add(Capabilities.SAFE_BOOT_BLOCK)
                enable.add(Capabilities.UNINSTALL_PROTECTION)
                enable.add(Capabilities.PERSISTENT_HOME)
                notes.add(
                    getString(R.string.onboarding_kiosk_needs_computer) to getString(R.string.onboarding_kiosk_needs_computer_detail)
                )
                FocusMode.KIOSK
            }
            else -> {
                enable.add(Capabilities.APP_BLOCK)
                enable.add(Capabilities.SANCTUARY_MODE)
                enable.add(Capabilities.LAUNCH_FRICTION)
                enable.add(Capabilities.SUSPEND_BLOCKED_APPS)
                FocusMode.BLOCK
            }
        }

        if ("shorts" in pulls) {
            enable.add(Capabilities.CONTENT_GUARD)
            enable.add(Capabilities.SHORTS_BLOCK)
            enable.add(Capabilities.REELS_BLOCK)
            notes.add(getString(R.string.onboarding_note_shorts_title) to getString(R.string.onboarding_note_shorts_detail))
        }
        if ("social" in pulls) {
            enable.add(Capabilities.CONTENT_GUARD)
            enable.add(Capabilities.NOTIFICATION_BLOCK)
        }
        if ("messaging" in pulls) {
            enable.add(Capabilities.CONTENT_GUARD)
            enable.add(Capabilities.WHATSAPP_GUARD)
            notes.add(getString(R.string.onboarding_note_whatsapp_title) to getString(R.string.onboarding_note_whatsapp_detail))
        }
        if ("browsing" in pulls) {
            enable.add(Capabilities.WEB_BLOCK)
            enable.add(Capabilities.ADULT_BLOCK)
        }
        if ("games" in pulls) {
            enable.add(Capabilities.PER_APP_LIMITS)
            notes.add(getString(R.string.onboarding_note_games_title) to getString(R.string.onboarding_note_games_detail))
        }

        if ("night" in hardTimes || "sleep" in goals) {
            enable.add(Capabilities.BEDTIME_MODE)
            notes.add(getString(R.string.onboarding_note_bedtime_title) to getString(R.string.onboarding_note_bedtime_detail, TimeText.ofDay(this, 22 * 60), TimeText.ofDay(this, 6 * 60)))
        }
        if ("school" in hardTimes) {
            enable.add(Capabilities.SCHEDULES)
            notes.add(getString(R.string.onboarding_note_school_title) to getString(R.string.onboarding_note_school_detail, TimeText.ofDay(this, 8 * 60), TimeText.ofDay(this, 15 * 60)))
        }
        if ("morning" in hardTimes) {
            enable.add(Capabilities.SCHEDULES)
            notes.add(getString(R.string.onboarding_note_morning_title) to getString(R.string.onboarding_note_morning_detail, TimeText.ofDay(this, 7 * 60), TimeText.ofDay(this, 9 * 60)))
        }
        if ("evening" in hardTimes) {
            enable.add(Capabilities.SCHEDULES)
            notes.add(getString(R.string.onboarding_note_evening_title) to getString(R.string.onboarding_note_evening_detail, TimeText.ofDay(this, 19 * 60), TimeText.ofDay(this, 22 * 60)))
        }

        if ("study" in goals || "work" in goals) {
            enable.add(Capabilities.ANALYTICS)
            enable.add(Capabilities.STREAKS)
        }

        // Earn Mode is only ever proposed because the person asked for exactly
        // this, never inferred from a general wish to use the phone less. The
        // research is clear enough about the downside that it should not be a
        // default anybody drifts into.
        if ("earn" in goals) {
            enable.add(Capabilities.EARN_MODE)
            enable.add(Capabilities.ANALYTICS)
            notes.add(
                getString(R.string.onboarding_note_earn_title) to getString(R.string.onboarding_note_earn_detail)
            )
        }

        val suggestions = UsageAnalytics.suggestedDistractions(this, 12)
            .filterNot { it in essentials }
            .filter { packageName ->
                val category = AppCatalog.categoryOf(this, packageName)
                when {
                    // Nothing picked means nothing to go on, so only the apps
                    // everyone means by "distracting" - never a guess from a
                    // category. Guessing here suggested blocking Gmail and a
                    // caller-ID app to someone who had skipped the question.
                    pulls.isEmpty() -> packageName in Seed.obviousDistractions
                    "shorts" in pulls && category == AppCategory.VIDEO -> true
                    "social" in pulls && category == AppCategory.SOCIAL -> true
                    "games" in pulls && category == AppCategory.GAMES -> true
                    "browsing" in pulls && category == AppCategory.BROWSING -> true
                    "messaging" in pulls && category == AppCategory.MESSAGING -> false
                    else -> false
                }
            }

        return Plan(mode, enable.toList(), disable.toList(), suggestions, notes)
    }

    private fun apply(plan: Plan) {
        // Belt-and-braces: the rerun entry point in You -> Advanced already
        // refuses to open this screen while frozen, but the underlying write
        // is what actually has to hold, in case this is ever reached another
        // way.
        if (SessionLock.isFrozen(this)) {
            FocusDialog.toast(this, SessionLock.refusalMessage(this))
            return
        }
        val values = HashMap<String, Boolean>()
        plan.enable.forEach { values[it] = true }
        plan.disable.forEach { values[it] = false }
        CapabilityRegistry.applySet(this, values)

        AppRules.setAlwaysAllowed(this, essentials)

        val toBlock = chosenDistractions.filterNot { it in essentials }
        if (toBlock.isNotEmpty()) {
            AppRules.setPolicies(this, toBlock, AppPolicy.BLOCK)
        }

        if ("games" in pulls) {
            AppCatalog.packagesInCategory(this, AppCategory.GAMES)
                .filterNot { it in essentials }
                .forEach { AppLimits.setMinuteLimit(this, it, 60) }
        }

        if (CapabilityRegistry.isEnabled(this, Capabilities.BEDTIME_MODE)) {
            Bedtime.setWindow(this, 22 * 60, 6 * 60)
            Bedtime.setBlockedCategories(
                this,
                listOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAMES)
            )
        }

        seedSchedules()
        finishOnboarding()
    }

    private fun seedSchedules() {
        if (!CapabilityRegistry.isEnabled(this, Capabilities.SCHEDULES)) return
        val existing = ScheduleManager.getSchedules(this).toMutableList()

        if ("school" in hardTimes) {
            existing.add(
                ScheduleManager.newSchedule(
                    startMinutes = 8 * 60,
                    endMinutes = 15 * 60,
                    repeat = RepeatType.WEEKLY,
                    daysOfWeek = listOf(
                        java.util.Calendar.MONDAY,
                        java.util.Calendar.TUESDAY,
                        java.util.Calendar.WEDNESDAY,
                        java.util.Calendar.THURSDAY,
                        java.util.Calendar.FRIDAY
                    ),
                    dayOfMonth = 0,
                    message = getString(R.string.onboarding_schedule_school),
                    allowedApps = essentials
                )
            )
        }
        if ("morning" in hardTimes) {
            existing.add(
                ScheduleManager.newSchedule(
                    startMinutes = 7 * 60,
                    endMinutes = 9 * 60,
                    repeat = RepeatType.DAILY,
                    daysOfWeek = emptyList(),
                    dayOfMonth = 0,
                    message = getString(R.string.onboarding_schedule_morning),
                    allowedApps = essentials
                )
            )
        }
        if ("evening" in hardTimes) {
            existing.add(
                ScheduleManager.newSchedule(
                    startMinutes = 19 * 60,
                    endMinutes = 22 * 60,
                    repeat = RepeatType.DAILY,
                    daysOfWeek = emptyList(),
                    dayOfMonth = 0,
                    message = getString(R.string.onboarding_schedule_evening),
                    allowedApps = essentials
                )
            )
        }
        ScheduleManager.saveSchedules(this, existing)
    }

    /**
     * The skip path still produces a working phone: the same defaults the specs
     * declare, plus detected essentials protected. Skipping must never mean
     * "unconfigured".
     */
    private fun skipToDefaults() {
        CapabilityRegistry.markSeeded(this)
        if (AppRules.alwaysAllowedRaw(this).isEmpty()) {
            AppRules.setAlwaysAllowed(this, AppCatalog.detectEssentials(this))
        }
        finishOnboarding()
    }

    private fun finishOnboarding() {
        FocusStore.setBool(this, Constants.KEY_ONBOARDING_DONE, true)
        PolicySync.request(this, "onboarding")
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        finish()
    }

    companion object {
        const val EXTRA_RERUN = "onboarding_rerun"
        private const val TOTAL_STEPS = 3
    }
}
