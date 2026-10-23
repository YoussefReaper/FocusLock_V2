package com.focuslock.mdm

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * The screen a person actually meets when something is blocked.
 *
 * This is the emotional centre of the app, so it is built around three ideas:
 *
 *  - **Say why, kindly.** The headline names what happened without implying a
 *    failure, and the detail reminds them it was their own earlier decision.
 *  - **Never a dead end.** There is always somewhere to go: a calm alternative,
 *    a counted break, or simply back. A blocker that offers nothing gets
 *    uninstalled at the first hard moment.
 *  - **Pause, don't fight.** In soft and friction cases the way through stays
 *    open; the few seconds are the whole intervention.
 */
class InterceptActivity : AppCompatActivity() {

    private lateinit var tokens: UiPrefs.Tokens
    private val handler = Handler(Looper.getMainLooper())

    private var blockedPackage: String = ""
    private var headline: String = ""
    private var detail: String = ""
    private var source: String = ""
    private var phrase: String = ""
    private var isPause: Boolean = false
    private var offersBreak: Boolean = false
    private var breakMinutesOverride: Int = 0
    private var poolMinutes: Int = 0
    private var isBudgetBlock: Boolean = false
    private var testMode: Boolean = false

    private var countdownSeconds = 0
    private var continueButton: TextView? = null
    private var countdownLabel: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            countdownSeconds -= 1
            if (countdownSeconds <= 0) {
                countdownLabel?.visibility = View.GONE
                continueButton?.let {
                    it.isEnabled = true
                    it.alpha = 1f
                    Motion.fadeIn(it, tokens)
                }
                return
            }
            countdownLabel?.text = countdownSeconds.toString()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        blockedPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        headline = intent.getStringExtra(EXTRA_HEADLINE).orEmpty()
        detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        phrase = intent.getStringExtra(EXTRA_PHRASE).orEmpty()
        isPause = intent.getBooleanExtra(EXTRA_PAUSE, false)
        offersBreak = intent.getBooleanExtra(EXTRA_OFFERS_BREAK, false)
        breakMinutesOverride = intent.getIntExtra(EXTRA_BREAK_MINUTES, 0)
        poolMinutes = intent.getIntExtra(EXTRA_POOL_MINUTES, -1)
        isBudgetBlock = source == "budget"
        testMode = intent.getBooleanExtra(EXTRA_TEST_MODE, false)

        // The content guard is the one source that names exactly what it saw -
        // the keyword rule that matched, not the surrounding on-screen text -
        // so the person is never left guessing what tripped it.
        if (headline.isBlank() && source == "contentGuard" && phrase.isNotBlank()) {
            headline = Copy.contentGuardHeadline(this, blockedPackage, phrase)
        }
        if (detail.isBlank() && source == "contentGuard" && phrase.isNotBlank()) {
            detail = Copy.contentGuardDetail(this, phrase)
        }
        if (headline.isBlank() && blockedPackage.isNotBlank()) {
            headline = Copy.blockHeadline(this, blockedPackage)
        }
        if (detail.isBlank() && blockedPackage.isNotBlank()) {
            detail = Copy.blockDetail(this, blockedPackage)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leave()
            }
        })

        render()
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy.syncLockTaskState(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    // ── Layout ────────────────────────────────────────────────────

    private fun render() {
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens)

        val root = FocusUi.screenRoot(this, tokens)

        // Two stacked groups, not one centred column: the mark/headline float in
        // whatever space is left above, and "ways through" anchors to the
        // bottom - the block screen mockup's "breathing room" direction, rather
        // than a wall of buttons in the middle of the screen.
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val top = FocusUi.column(this, tokens.density.contentPaddingDp + 6)
        top.gravity = Gravity.CENTER
        top.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        if (testMode) {
            val banner = FocusUi.pill(
                this,
                tokens,
                getString(R.string.intercept_testing_banner, TestMode.formatRemaining(this)),
                tokens.warning
            )
            banner.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = FocusUi.dp(this@InterceptActivity, 14) }
            top.addView(banner)
        }

        top.addView(buildMark())
        top.addView(FocusUi.spacer(this, 22))
        top.addView(buildOverline())
        top.addView(FocusUi.spacer(this, 6))

        val headlineView = FocusUi.display(this, tokens, headline)
        headlineView.gravity = Gravity.CENTER
        top.addView(headlineView)

        if (detail.isNotBlank()) {
            val detailView = FocusUi.secondary(this, tokens, detail)
            detailView.gravity = Gravity.CENTER
            detailView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(this@InterceptActivity, 12) }
            top.addView(detailView)
        }

        outer.addView(top)

        val bottom = FocusUi.column(this, tokens.density.contentPaddingDp + 6)
        bottom.setPadding(bottom.paddingLeft, FocusUi.dp(this, 10), bottom.paddingRight, FocusUi.dp(this, 26))
        bottom.addView(buildWaysThrough())

        buildStillOpen()?.let {
            bottom.addView(FocusUi.spacer(this, 20))
            bottom.addView(it)
        }

        outer.addView(bottom)

        root.addView(outer)
        setContentView(root)

        Motion.fadeIn(outer, tokens)
    }

    /** "Your rules · Apps", "Your rules · Bedtime" - names which of your own rules this was, the way every screen mockup in the design doc does. */
    private fun buildOverline(): View {
        val category = when (source) {
            "schedule", "scheduleOverlay" -> getString(R.string.schedule_title)
            "bedtime" -> getString(R.string.bedtime_title)
            "place" -> getString(R.string.place_rules_title)
            "contentGuard" -> getString(R.string.keyword_guard_title)
            "limit" -> getString(R.string.app_limits_title)
            else -> getString(R.string.app_rules_title)
        }
        val view = TextView(this)
        view.text = getString(R.string.intercept_overline_format, category).uppercase()
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(11f))
        view.setTextColor(tokens.accent)
        FocusUi.applyFont(view, tokens, mono = true, weight = 600)
        view.letterSpacing = 0.09f
        view.gravity = Gravity.CENTER
        return view
    }

    /**
     * The app's own icon inside a soft ring, rather than a warning triangle.
     * Nothing here is an error, and the visual language should not say it is.
     */
    private fun buildMark(): View {
        val holder = FrameLayout(this)
        val size = FocusUi.dp(this, 108)
        holder.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        holder.background = FocusUi.roundedShape(
            this,
            UiPrefs.withAlpha(tokens.accent, 26),
            54,
            UiPrefs.withAlpha(tokens.accent, 70)
        )

        val inner: View = if (blockedPackage.isNotBlank() && AppCatalog.icon(this, blockedPackage) != null) {
            FocusUi.appIcon(this, tokens, blockedPackage, 46)
        } else {
            val glyph = TextView(this)
            glyph.text = if (isPause) "…" else "•"
            glyph.setTextColor(tokens.accent)
            glyph.typeface = tokens.typeface
            glyph.textSize = tokens.scaled(34f)
            glyph
        }
        holder.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )

        if (isPause) Motion.breathe(holder, tokens)
        return holder
    }

    private fun buildWaysThrough(): View {
        val column = FocusUi.column(this)

        if (isPause) {
            countdownSeconds = pauseSeconds()

            val label = FocusUi.display(this, tokens, countdownSeconds.toString())
            label.gravity = Gravity.CENTER
            label.setTextColor(tokens.textMuted)
            countdownLabel = label
            column.addView(label)
            column.addView(FocusUi.spacer(this, 14))

            val continueAnyway = FocusUi.secondaryButton(
                this,
                tokens,
                getString(R.string.intercept_open_anyway, appLabel())
            ) { openAnyway() }
            continueAnyway.isEnabled = false
            continueAnyway.alpha = 0.4f
            continueButton = continueAnyway

            column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.intercept_not_now_go_back)) { leave() })
            column.addView(FocusUi.spacer(this, 10))
            column.addView(continueAnyway)

            if (testMode) {
                column.addView(FocusUi.spacer(this, 10))
                column.addView(FocusUi.ghostButton(this, tokens, getString(R.string.intercept_end_test_button)) { endTest() })
            }

            handler.postDelayed(ticker, 1_000L)
            return column
        }

        // A plain block reads as a short list of full row-cards to move
        // through, not a stack of buttons - the break, the banked minutes and
        // every calm alternative are all "a way through", styled the same.
        // The one thing that isn't a real choice - just going back - gets no
        // card at all, the design doc's quietest possible treatment.
        column.addView(FocusUi.sectionLabel(this, tokens, getString(R.string.intercept_ways_through)))

        var hasCardRow = false

        if (offersBreak && breakMinutesOverride > 0) {
            column.addView(
                buildWayRow(
                    R.drawable.ic_glyph_break,
                    getString(R.string.intercept_break_row_title, breakMinutesOverride),
                    null
                ) { startBreak() }
            )
            hasCardRow = true
        } else if (offersBreak && TakeABreak.canStart(this)) {
            val minutes = TakeABreak.breakMinutes(this)
            val left = TakeABreak.remainingToday(this)
            val total = TakeABreak.dailyMax(this)
            column.addView(
                buildWayRow(
                    R.drawable.ic_glyph_break,
                    getString(R.string.intercept_break_row_title, minutes),
                    getString(R.string.intercept_break_row_subtitle, left, total)
                ) { startBreak() }
            )
            hasCardRow = true
        } else if (offersBreak) {
            column.addView(buildQuietRow(Copy.breakUnavailable(this)))
        }

        // A Budgeted app explains itself rather than merely refusing.
        //
        // There is deliberately no "spend now" button any more. The pool is
        // drained live by the app itself for as long as it is in front, so
        // buying a block of minutes from this screen would be a second,
        // competing way to spend the same balance. The only useful thing this
        // screen can do is say where the balance stands and put the way to
        // refill it one tap away. See [EarnPool].
        if (isBudgetBlock) {
            column.addView(
                buildWayRow(
                    R.drawable.ic_glyph_earn,
                    Copy.budgetPoolLine(this, poolMinutes),
                    getString(R.string.intercept_earn_pool_subtitle)
                ) { openEarnTasks() }
            )
            hasCardRow = true
        }

        if (CapabilityRegistry.isEnabled(this, Capabilities.REPLACEMENT_SUGGESTIONS)) {
            availableAlternatives().forEach { pair ->
                column.addView(buildWayRow(iconForAlternative(pair.second), pair.first, null) { openAlternative(pair.second) })
                hasCardRow = true
            }
        }

        if (hasCardRow) column.addView(FocusUi.spacer(this, 4))
        column.addView(buildQuietRow(getString(R.string.intercept_back_to_focuslock)) { leave() })

        if (testMode) {
            column.addView(FocusUi.spacer(this, 10))
            column.addView(FocusUi.ghostButton(this, tokens, getString(R.string.intercept_end_test_button)) { endTest() })
        }

        return column
    }

    /** One "way through": an icon, a title, an optional usage/status subtitle, its own card - the doc's row-card, not a button. */
    private fun buildWayRow(iconRes: Int, title: String, subtitle: String?, onClick: () -> Unit): View {
        val row = FocusUi.card(this, tokens, onClick = onClick)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(FocusUi.categoryIcon(this, tokens, iconRes, tokens.accent, 22))
        row.addView(FocusUi.spacerH(this, 14))

        val textColumn = FocusUi.column(this)
        textColumn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        textColumn.addView(FocusUi.rowTitle(this, tokens, title))
        if (!subtitle.isNullOrBlank()) {
            val subtitleView = FocusUi.caption(this, tokens, subtitle)
            subtitleView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(this@InterceptActivity, 2) }
            textColumn.addView(subtitleView)
        }
        row.addView(textColumn)
        return row
    }

    /** The lowest-emphasis row: no card, no icon, centred text - reserved for "just going back", never a real way through. */
    private fun buildQuietRow(text: String, onClick: (() -> Unit)? = null): View {
        val label = FocusUi.rowTitle(this, tokens, text)
        label.setTextColor(tokens.textSecondary)
        label.gravity = Gravity.CENTER
        label.setPadding(FocusUi.dp(this, 4), FocusUi.dp(this, 14), FocusUi.dp(this, 4), FocusUi.dp(this, 14))
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (onClick != null) {
            label.isClickable = true
            label.isFocusable = true
            label.setOnClickListener { onClick() }
        }
        return label
    }

    private fun iconForAlternative(key: String): Int = when (key) {
        "textSearch" -> R.drawable.ic_glyph_keywords
        "analytics" -> R.drawable.ic_glyph_insight
        else -> R.drawable.ic_glyph_apps
    }

    /**
     * Sends the person to the task list, which is where the pool gets refilled.
     *
     * The block screen's job on a Budgeted app is not to argue - it is to name
     * the one action that changes the answer, and put it one tap away.
     */
    private fun openEarnTasks() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TASKS)
            }
        )
        finish()
    }

    /**
     * During a scheduled window or bedtime, the useful thing to show is not
     * "you cannot do this" but "here is what is still open". Otherwise a quiet
     * window looks like a broken phone.
     */
    private fun buildStillOpen(): View? {
        if (source != "schedule" && source != "scheduleOverlay" && source != "bedtime" && source != "place") return null

        val window = ScheduleManager.activeWindowIfEnabled(this)
        val open = (AppRules.alwaysAllowed(this) + window?.allowedApps.orEmpty())
            .filter { it != packageName }
            .filter { packageManager.getLaunchIntentForPackage(it) != null }
            .distinct()
            .sortedBy { AppCatalog.label(this, it) }
            .take(8)

        if (open.isEmpty()) return null

        val column = FocusUi.column(this)
        val label = FocusUi.caption(this, tokens, getString(R.string.intercept_still_open))
        label.gravity = Gravity.CENTER
        label.letterSpacing = 0.08f
        column.addView(label)
        column.addView(FocusUi.spacer(this, 10))

        val strip = FocusUi.row(this)
        strip.gravity = Gravity.CENTER
        strip.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        open.forEach { packageName ->
            strip.addView(
                FocusUi.chip(this, tokens, AppCatalog.label(this, packageName), false) {
                    val launch = this.packageManager.getLaunchIntentForPackage(packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                        finish()
                    }
                }
            )
        }

        val scroll = FocusUi.horizontalScroll(this, strip)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        column.addView(scroll)
        return column
    }

    /** Only ever offers replacements the user has actually left switched on. */
    private fun availableAlternatives(): List<Pair<String, String>> =
        Seed.replacements.map { getString(it.first) to it.second }.filter { pair ->
            when (pair.second) {
                "textSearch" -> CapabilityRegistry.isEnabled(this, Capabilities.TEXT_SEARCH)
                "safeBrowser" -> CapabilityRegistry.isEnabled(this, Capabilities.SAFE_BROWSER)
                "analytics" -> CapabilityRegistry.isEnabled(this, Capabilities.ANALYTICS)
                else -> false
            }
        }

    // ── Actions ───────────────────────────────────────────────────

    private fun pauseSeconds(): Int =
        CapabilityRegistry.getIntParam(this, Capabilities.LAUNCH_FRICTION, "seconds", 8)
            .coerceIn(3, 60)

    private fun appLabel(): String =
        if (blockedPackage.isBlank()) getString(R.string.intercept_it_fallback) else AppCatalog.label(this, blockedPackage)

    /**
     * A pause is not a wall. Letting the person through after the countdown is
     * what keeps soft mode honest, so this grants a short pass unconditionally
     * (see [TakeABreak.grantFrictionPass]) rather than the budgeted, capability-
     * gated [TakeABreak.start] - that used to silently fail whenever Take a
     * Break was off or its daily allowance was spent, and the app would just
     * get re-intercepted the moment it opened, looping the pause screen.
     */
    private fun openAnyway() {
        if (blockedPackage.isNotBlank()) {
            TakeABreak.grantFrictionPass(this, blockedPackage)
            val launch = packageManager.getLaunchIntentForPackage(blockedPackage)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                finish()
                return
            }
        }
        finish()
    }

    private fun startBreak() {
        if (blockedPackage.isBlank()) {
            leave()
            return
        }
        val grantedMinutes = if (breakMinutesOverride > 0) {
            TakeABreak.grantTempPass(this, blockedPackage, breakMinutesOverride)
            breakMinutesOverride
        } else {
            if (!TakeABreak.start(this, blockedPackage)) {
                FocusDialog.toast(this, Copy.breakUnavailable(this))
                return
            }
            TakeABreak.breakMinutes(this)
        }
        FocusDialog.toast(this, Copy.breakStarted(this, grantedMinutes))
        val launch = packageManager.getLaunchIntentForPackage(blockedPackage)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
        finish()
    }

    private fun openAlternative(key: String) {
        val intent = when (key) {
            "textSearch" -> Intent(this, TextSearchActivity::class.java)
            "safeBrowser" -> Intent(this, WebViewActivity::class.java)
            "analytics" -> Intent(this, AnalyticsActivity::class.java)
            else -> null
        } ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    private fun leave() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        finish()
    }

    /** Reachable from every test screen, exactly the "end it anytime" the test promised. */
    private fun endTest() {
        TestMode.end(this)
        leave()
    }

    companion object {
        const val EXTRA_PACKAGE = "intercept_package"
        const val EXTRA_HEADLINE = "intercept_headline"
        const val EXTRA_DETAIL = "intercept_detail"
        const val EXTRA_SOURCE = "intercept_source"
        const val EXTRA_PHRASE = "intercept_phrase"
        const val EXTRA_PAUSE = "intercept_pause"
        const val EXTRA_OFFERS_BREAK = "intercept_offers_break"
        const val EXTRA_BREAK_MINUTES = "intercept_break_minutes"
        const val EXTRA_POOL_MINUTES = "intercept_pool_minutes"
        const val EXTRA_TEST_MODE = "intercept_test_mode"
    }
}
