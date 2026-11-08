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
import androidx.core.widget.NestedScrollView

/**
 * First-run setup: 3 questions, then go.
 *
 *  1. Which apps pull you? (Pick from installed apps, or tap Study / Sleep / Social detox)
 *  2. How firm? (Gentle · Block · Strict)
 *  3. When? (Start now, or a daily schedule)
 *
 * Asks only for Usage Access with one plain sentence on FocusLock's own screen
 * before opening Android's permission page, and starts the first block immediately.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var host: FrameLayout

    private var step = 0
    private var rerun = false
    private var awaitingUsagePermission = false

    private val essentials = LinkedHashSet<String>()
    private val chosenDistractions = LinkedHashSet<String>()
    // Frozen on first show so a row never jumps while someone is toggling it.
    private var candidates: MutableList<InstalledApp>? = null
    private var scrollView: NestedScrollView? = null
    private var renderedStep = -1
    private var strictness = 1 // 0 = Gentle, 1 = Block, 2 = Strict (no early exit)
    private var whenChoice = "now" // "now", "evening", "study", "bedtime"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Migration.run(this)
        rerun = intent.getBooleanExtra(EXTRA_RERUN, false)

        essentials.addAll(AppCatalog.detectEssentials(this))
        chosenDistractions.addAll(UsageAnalytics.suggestedDistractions(this, 8))

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
        if (awaitingUsagePermission) {
            awaitingUsagePermission = false
            completeAndStart()
            return
        }
        render()
    }

    private fun render() {
        // A toggle re-renders the same step: keep the list where the finger is.
        val sameStep = step == renderedStep
        val keepY = if (sameStep) scrollView?.scrollY ?: 0 else 0
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
            0 -> buildStep1AppsAndPresets(column)
            1 -> buildStep2Firmness(column)
            else -> buildStep3When(column)
        }

        val scroll = FocusUi.scroll(this, column)
        host.addView(scroll)
        root.addView(host)
        setContentView(root)
        scrollView = scroll
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }

        if (!sameStep) Motion.fadeIn(column, tokens)
        renderedStep = step
    }

    private fun buildProgress(): View {
        val container = FocusUi.column(this)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(this@OnboardingActivity, 18) }

        val row = FocusUi.row(this)
        for (i in 0 until TOTAL_STEPS) {
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

        val topBar = FocusUi.row(this)
        topBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(this@OnboardingActivity, 10) }

        val stepCaption = FocusUi.caption(this, tokens, "${step + 1} / $TOTAL_STEPS")
        stepCaption.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        topBar.addView(stepCaption)

        val skip = FocusUi.ghostButton(this, tokens, getString(R.string.onboarding_skip_questions)) {
            skipToDefaults()
        }
        skip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            FocusUi.dp(this, tokens.density.quickButtonHeightDp)
        )
        topBar.addView(skip)
        container.addView(topBar)

        return container
    }

    // ── Step 1: Pick apps ───────────────────────

    private fun buildStep1AppsAndPresets(column: LinearLayout) {
        column.addView(FocusUi.title(this, tokens, getString(R.string.onboarding_q1_apps_title)))
        column.addView(FocusUi.spacer(this, 6))
        column.addView(FocusUi.secondary(this, tokens, getString(R.string.onboarding_q1_apps_subtitle)))
        column.addView(FocusUi.spacer(this, 16))

        // Quiet Pine: one question per screen. The three preset cards that
        // used to sit above this list were a second decision on the first
        // screen; the three questions already build the same setups.
        // Installed apps list
        val appsCard = FocusUi.card(this, tokens)
        val candidates = this.candidates ?: candidateDistractionApps().toMutableList().also { this.candidates = it }
        if (candidates.isEmpty()) {
            appsCard.addView(FocusUi.secondary(this, tokens, getString(R.string.onboarding_nothing_blocked)))
        } else {
            candidates.forEachIndexed { index, app ->
                appsCard.addView(
                    FocusUi.toggleRow(
                        this,
                        tokens,
                        app.label,
                        app.category.label(this),
                        app.packageName in chosenDistractions
                    ) { checked ->
                        if (checked) chosenDistractions.add(app.packageName) else chosenDistractions.remove(app.packageName)
                        render()
                    }
                )
                if (index < candidates.size - 1) appsCard.addView(FocusUi.divider(this, tokens))
            }
        }
        appsCard.addView(FocusUi.spacer(this, 10))
        appsCard.addView(
            FocusUi.smallButton(this, tokens, getString(R.string.onboarding_add_more_apps)) {
                val allLaunchable = AppCatalog.launchable(this)
                    .filterNot { it.packageName in essentials }
                    .map { app ->
                        FocusDialog.Choice(app.packageName, app.label, app.category.label(this), app.packageName)
                    }
                FocusDialog.multiChoice(
                    this,
                    getString(R.string.onboarding_q1_apps_title),
                    getString(R.string.onboarding_q1_apps_subtitle),
                    allLaunchable,
                    chosenDistractions
                ) { selected ->
                    chosenDistractions.clear()
                    chosenDistractions.addAll(selected)
                    // New picks join the bottom of the list; nothing already shown moves.
                    val shown = candidates.map { it.packageName }.toSet()
                    val byPkg = AppCatalog.launchable(this).associateBy { it.packageName }
                    selected.filterNot { it in shown }.mapNotNull { byPkg[it] }.forEach { candidates.add(it) }
                    render()
                }
            }
        )
        column.addView(appsCard)

        column.addView(FocusUi.spacer(this, 16))
        column.addView(
            FocusUi.primaryButton(
                this,
                tokens,
                if (chosenDistractions.isEmpty()) getString(R.string.common_continue)
                else resources.getQuantityString(R.plurals.redesign_continue_with_apps, chosenDistractions.size, chosenDistractions.size)
            ) {
                step = 1
                render()
            }
        )
    }

    private fun candidateDistractionApps(): List<InstalledApp> {
        val launchable = AppCatalog.launchable(this).filterNot { it.packageName in essentials }
        val byPkg = launchable.associateBy { it.packageName }
        val prioritized = ArrayList<InstalledApp>()

        chosenDistractions.mapNotNull { byPkg[it] }.forEach { prioritized.add(it) }
        UsageAnalytics.suggestedDistractions(this, 10)
            .mapNotNull { byPkg[it] }
            .filterNot { it in prioritized }
            .forEach { prioritized.add(it) }
        launchable
            .filter { it.category in setOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAMES, AppCategory.BROWSING) }
            .filterNot { it in prioritized }
            .take(8)
            .forEach { prioritized.add(it) }

        return prioritized.take(10)
    }

    // ── Step 2: How firm? ─────────────────────────────────────────

    private fun buildStep2Firmness(column: LinearLayout) {
        column.addView(FocusUi.title(this, tokens, getString(R.string.onboarding_firm_title)))
        column.addView(FocusUi.spacer(this, 8))
        column.addView(FocusUi.secondary(this, tokens, getString(R.string.onboarding_firm_note)))
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

        column.addView(FocusUi.spacer(this, 14))
        column.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.common_continue)) {
                step = 2
                render()
            }
        )
    }

    // ── Step 3: When? ─────────────────────────────────────────────

    private fun buildStep3When(column: LinearLayout) {
        column.addView(FocusUi.title(this, tokens, getString(R.string.onboarding_q3_when_title)))
        column.addView(FocusUi.spacer(this, 8))
        column.addView(FocusUi.secondary(this, tokens, getString(R.string.onboarding_q3_when_subtitle)))
        column.addView(FocusUi.spacer(this, 18))

        val options = listOf(
            "now" to getString(R.string.onboarding_when_now_label),
            "evening" to getString(R.string.onboarding_when_evening_label),
            "study" to getString(R.string.onboarding_when_study_label),
            "bedtime" to getString(R.string.onboarding_when_bedtime_label)
        )

        options.forEach { (key, label) ->
            column.addView(
                buildOptionRow(label, whenChoice == key) {
                    whenChoice = key
                    render()
                }
            )
            column.addView(FocusUi.spacer(this, 10))
        }

        column.addView(FocusUi.spacer(this, 14))
        column.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.onboarding_start_first_block)) {
                requestUsageAccessIfNeededAndFinish()
            }
        )
    }

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
            // rowTitle() is match-parent wide. Next to the weighted label that left
            // the label zero pixels, so it wrapped one letter per line into a tall card.
            check.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            row.addView(check)
        }

        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return row
    }

    // ── Presets & Completion ──────────────────────────────────────

    private fun requestUsageAccessIfNeededAndFinish() {
        if (SetupChecks.hasUsageAccess(this)) {
            completeAndStart()
            return
        }
        FocusDialog.alert(
            this,
            title = getString(R.string.perms_usage_title),
            message = getString(R.string.perms_usage_detail),
            confirmLabel = getString(R.string.dashboard_grant),
            cancelLabel = getString(R.string.common_not_now),
            onConfirm = {
                awaitingUsagePermission = true
                if (!PermissionIntents.launchFirst(this, PermissionIntents.usageAccess(this))) {
                    awaitingUsagePermission = false
                    completeAndStart()
                }
            },
            onCancel = {
                completeAndStart()
            }
        )
    }

    private fun completeAndStart() {
        if (SessionLock.isFrozen(this)) {
            FocusDialog.toast(this, SessionLock.refusalMessage(this))
            return
        }

        val values = HashMap<String, Boolean>()
        values[Capabilities.ALWAYS_ALLOWED] = true
        values[Capabilities.APP_BLOCK] = true
        values[Capabilities.TAKE_A_BREAK] = true
        values[Capabilities.SAFE_BROWSER] = true
        values[Capabilities.TEXT_SEARCH] = true
        values[Capabilities.SAFE_YOUTUBE] = true
        values[Capabilities.ANALYTICS] = true

        val mode = when (strictness) {
            0 -> {
                values[Capabilities.ADVISORY_MODE] = true
                values[Capabilities.LAUNCH_FRICTION] = true
                values[Capabilities.HARD_BLOCK] = false
                values[Capabilities.SUSPEND_BLOCKED_APPS] = false
                FocusMode.SOFT
            }
            2 -> {
                values[Capabilities.APP_BLOCK] = true
                values[Capabilities.HARD_BLOCK] = true
                values[Capabilities.CAN_END_EARLY] = false
                values[Capabilities.LOCK_RULES_IN_SESSION] = true
                FocusMode.BLOCK
            }
            else -> {
                values[Capabilities.APP_BLOCK] = true
                values[Capabilities.HARD_BLOCK] = true
                values[Capabilities.CAN_END_EARLY] = true
                FocusMode.BLOCK
            }
        }

        if (whenChoice == "bedtime") {
            values[Capabilities.BEDTIME_MODE] = true
        }

        CapabilityRegistry.applySet(this, values)
        // These flags are the person's own answer. Without this, SessionManager.start()
        // sees a mode it has not loaded yet and stamps the template over them, which
        // turned "Strict" back into a session you can end with one tap.
        SessionManager.markPresetApplied(this, mode)
        AppRules.setAlwaysAllowed(this, essentials)

        val toBlock = chosenDistractions.filterNot { it in essentials }
        if (toBlock.isNotEmpty()) {
            AppRules.setPolicies(
                this,
                toBlock,
                if (strictness == 0) AppPolicy.FRICTION else AppPolicy.BLOCK
            )
        }

        val existingSchedules = ScheduleManager.getSchedules(this).toMutableList()
        when (whenChoice) {
            "now" -> {
                SessionManager.start(this, mode, 60L * 60L * 1000L)
            }
            "evening" -> {
                existingSchedules.add(
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
                ScheduleManager.saveSchedules(this, existingSchedules)
            }
            "study" -> {
                existingSchedules.add(
                    ScheduleManager.newSchedule(
                        startMinutes = 9 * 60,
                        endMinutes = 17 * 60,
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
                ScheduleManager.saveSchedules(this, existingSchedules)
            }
            "bedtime" -> {
                Bedtime.setWindow(this, 22 * 60, 6 * 60)
                Bedtime.setBlockedCategories(
                    this,
                    listOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAMES)
                )
            }
        }

        finishOnboarding()
    }

    private fun skipToDefaults() {
        CapabilityRegistry.markSeeded(this)
        if (AppRules.alwaysAllowedRaw(this).isEmpty()) {
            AppRules.setAlwaysAllowed(this, AppCatalog.detectEssentials(this))
        }
        // Skipping the questions still ends up with the same starting blocks the first
        // screen would have ticked, rather than an empty list or the built-in one.
        val starters = chosenDistractions.filterNot { it in essentials }
        if (starters.isNotEmpty() && AppRules.allPolicies(this).isEmpty()) {
            AppRules.setPolicies(this, starters, AppPolicy.BLOCK)
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
