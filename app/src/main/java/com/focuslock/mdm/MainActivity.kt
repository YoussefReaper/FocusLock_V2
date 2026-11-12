package com.focuslock.mdm

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * The shell.
 *
 * Four tabs, always labelled, always in the same order: **Focus** is what is
 * happening now, **Library** is what to do instead, **Rules** is everything the
 * user owns, **You** is the app itself. That split is the whole information
 * architecture, and it replaced a single 640-line scrolling dashboard where
 * starting a session and factory-resetting the phone were the same distance
 * from the top.
 *
 * Tabs are plain view builders rather than fragments: the screens are simple,
 * and this keeps state, theming and refresh in one obvious place.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var contentHost: FrameLayout
    private lateinit var navBar: LinearLayout

    private var currentTab = TAB_FOCUS
    private val tabs = HashMap<Int, FocusTab>()
    private val navButtons = ArrayList<NavButton>()

    /** Set when onCreate bounced to onboarding, so onResume does not build a dead shell. */
    private var handingOffToOnboarding = false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The last lock-task state we acted on, so the ticker can notice a change
     * without asking the framework twice a second.
     */
    private var lastLockTaskWanted: Boolean? = null

    private val ticker = object : Runnable {
        override fun run() {
            // A session can end while this screen is already resumed — the timer
            // runs out, or the person taps End. onResume will not fire again to
            // release the pin, and while pinned they cannot easily go elsewhere
            // to trigger one. So watch for the transition here and release it.
            val wanted = SessionManager.shouldLockTask(this@MainActivity)
            if (lastLockTaskWanted != wanted) {
                lastLockTaskWanted = wanted
                KioskPolicy.syncLockTaskState(this@MainActivity)
            }
            // A brick window starts and ends on the clock, so the shell has to
            // notice the transition without anyone navigating anywhere - the
            // person may well be looking at this screen when it lifts.
            if (Lockdown.isActive(this@MainActivity) != lockedDown) {
                buildShell()
                handler.postDelayed(this, 1_000L)
                return
            }
            tabs[currentTab]?.onTick()
            handler.postDelayed(this, 1_000L)
        }
    }

    private data class NavButton(
        val root: LinearLayout,
        val iconHolder: FrameLayout,
        val icon: ImageView,
        val label: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Migration.run(this)
        LockManager.initLock(this)
        handleLicenceLink(intent)

        if (!FocusStore.getBool(this, Constants.KEY_ONBOARDING_DONE, false)) {
            handingOffToOnboarding = true
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Back must not fall out of the app while a hard session is running.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != TAB_FOCUS) {
                    selectTab(TAB_FOCUS)
                } else if (!SessionManager.shouldLockTask(this@MainActivity)) {
                    moveTaskToBack(true)
                }
            }
        })

        currentTab = intent.getIntExtra(EXTRA_TAB, TAB_FOCUS)
        buildShell()
    }

    override fun onResume() {
        super.onResume()
        if (handingOffToOnboarding || isFinishing) return
        applyPolicyOnResume()
        PromiseSync.pullAsync(this) { recreate() }
        rebuildIfThemeChanged()
        tabs[currentTab]?.onShow()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleLicenceLink(intent)) {
            tabs[currentTab]?.onShow()
            return
        }
        val tab = intent.getIntExtra(EXTRA_TAB, -1)
        if (tab >= 0) selectTab(tab)
    }

    /**
     * `focuslock://pro/FL2-...` from the access page's "Open in FocusLock"
     * button. The code is checked offline exactly like a pasted one, so a
     * link can never unlock anything a typed code could not.
     */
    private fun handleLicenceLink(intent: Intent?): Boolean {
        val linkIntent = intent ?: return false
        val data = linkIntent.data ?: return false
        if (data.scheme != "focuslock" || data.host != "pro") return false
        // Consumed: a rotation or a later resume must not redeem it again.
        linkIntent.data = null
        val unlocked = DemoMode.redeemLicenceCode(this, data.toString())
        FocusDialog.toast(
            this,
            getString(if (unlocked) R.string.pro_unlocked_toast else R.string.pro_code_invalid_toast)
        )
        return true
    }

    // ── Policy ────────────────────────────────────────────────────

    private fun applyPolicyOnResume() {
        lastLockTaskWanted = SessionManager.shouldLockTask(this)
        KioskPolicy.syncLockTaskState(this)
        // A check-in, not a change: reapply rather than request, so opening
        // FocusLock does not invalidate every cache in the process. The block
        // loop brings this screen forward every time it turns an app away, so
        // this ran far more often than "whenever the person opens the app".
        PolicySync.reapply(this)
        // Only when there is something to watch or to tear down. It would
        // stop by itself otherwise, but only after going foreground and
        // running a full release, on every single resume.
        AppBlockerService.startIfNeeded(this)
        // Only if the person switched the weekly check on; off by default.
        UpdateCheck.maybeCheckInBackground(this)
    }

    // ── Shell ─────────────────────────────────────────────────────

    private var lastThemeSignature = ""

    /** The shared one, so the shell and every screen agree on what counts as a theme change. */
    private fun themeSignature(): String = UiPrefs.signature(this)

    /** True while [buildLockdownScreen] owns the window, so the tab shell's views do not exist. */
    private var lockedDown = false

    private fun rebuildIfThemeChanged() {
        // Entering or leaving a brick window swaps the entire shell, so it has
        // to force a rebuild the same way a theme change does.
        if (Lockdown.isActive(this) != lockedDown) {
            buildShell()
            return
        }
        val signature = themeSignature()
        if (signature == lastThemeSignature) return
        buildShell()
    }

    private fun buildShell() {
        tokens = UiPrefs.resolve(this)
        lastThemeSignature = themeSignature()
        tabs.clear()
        navButtons.clear()
        FocusUi.applySystemBars(window, tokens, tokens.surface)

        // One screen and nothing else while a brick window holds. Drawn instead
        // of the shell rather than over it: see [Lockdown] on why overlaying
        // our own UI would be the fatal loop and this is not.
        lockedDown = Lockdown.isActive(this)
        if (lockedDown) {
            buildLockdownScreen()
            return
        }

        val root = FocusUi.screenRoot(this, tokens)

        val shell = LinearLayout(this)
        shell.orientation = LinearLayout.VERTICAL
        shell.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        contentHost = FrameLayout(this)
        contentHost.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        shell.addView(contentHost)

        navBar = buildNavBar()
        shell.addView(navBar)

        root.addView(shell)
        setContentView(root)

        selectTab(currentTab, animate = false)
    }

    /**
     * The brick screen: what is holding the phone, when it lifts, and the
     * essentials.
     *
     * Deliberately not a dead end. The always-allowed apps are launchable from
     * here, because a lock that can stop someone making a phone call is not a
     * lock anyone should ship - and because the lock-task allowlist already
     * permits exactly these, tapping one works rather than bouncing.
     */
    private fun buildLockdownScreen() {
        val root = FocusUi.screenRoot(this, tokens)

        val column = FocusUi.column(this, tokens.density.contentPaddingDp)
        column.gravity = Gravity.CENTER_HORIZONTAL

        column.addView(FocusUi.spacer(this, 64))

        val headline = FocusUi.title(this, tokens, getString(R.string.lockdown_headline))
        headline.gravity = Gravity.CENTER
        column.addView(headline)

        column.addView(FocusUi.spacer(this, 10))
        val detail = FocusUi.secondary(
            this,
            tokens,
            Lockdown.message(this)
                ?: Lockdown.liftsAt(this)?.let { getString(R.string.lockdown_lifts_at, it) }
                ?: getString(R.string.lockdown_generic)
        )
        detail.gravity = Gravity.CENTER
        column.addView(detail)

        val essentials = AppRules.alwaysAllowed(this)
            .filter { it != packageName && AppCatalog.isInstalled(this, it) }
            .sortedBy { AppCatalog.label(this, it) }

        if (essentials.isNotEmpty()) {
            column.addView(FocusUi.spacer(this, 36))
            column.addView(FocusUi.sectionLabel(this, tokens, getString(R.string.lockdown_essentials_label)))
            val card = FocusUi.card(this, tokens)
            essentials.forEachIndexed { index, pkg ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        AppCatalog.label(this, pkg),
                        null,
                        trailing = FocusUi.chevron(this, tokens),
                        leading = FocusUi.appIcon(this, tokens, pkg, 32)
                    ) { launchEssential(pkg) }
                )
                if (index < essentials.size - 1) card.addView(FocusUi.divider(this, tokens))
            }
            column.addView(card)
        }

        column.addView(FocusUi.spacer(this, 28))

        val scroll = FocusUi.scroll(this, column)
        scroll.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(scroll)
        setContentView(root)
    }

    private fun launchEssential(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            FocusDialog.toast(this, getString(R.string.library_no_screen_toast))
            return
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            FocusDialog.toast(this, getString(R.string.library_kiosk_holding_toast))
        }
    }

    /**
     * A hand-built bottom bar rather than `BottomNavigationView`.
     *
     * The stock component takes its colours from theme attributes, which is
     * exactly the coupling this refactor removed: here the active state is the
     * user's accent, the labels never collapse, and every target is a full
     * 56dp regardless of text scale.
     */
    private fun buildNavBar(): LinearLayout {
        val blossom = UiPrefs.isBlossom(this)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        if (blossom) {
            // Blossom: a floating, fully rounded dock over the page instead of a flat bar.
            bar.setBackgroundColor(tokens.background)
            bar.setPadding(FocusUi.dp(this, 18), FocusUi.dp(this, 6), FocusUi.dp(this, 18), FocusUi.dp(this, 14))
            strip.background = FocusUi.roundedShape(this, tokens.surface, 30)
            strip.elevation = FocusUi.dp(this, 6).toFloat()
            strip.setPadding(FocusUi.dp(this, 8), FocusUi.dp(this, 6), FocusUi.dp(this, 8), FocusUi.dp(this, 6))
        } else {
            bar.setBackgroundColor(tokens.surface)
            val topLine = View(this)
            topLine.setBackgroundColor(tokens.divider)
            // Quiet Pine: 9dp top / 18dp bottom inside the bar, as in the design.
            bar.addView(
                topLine,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, FocusUi.dp(this, 1))
            )
            strip.setPadding(FocusUi.dp(this, 12), FocusUi.dp(this, 9), FocusUi.dp(this, 12), FocusUi.dp(this, 12))
        }

        TAB_SPECS.forEachIndexed { index, spec ->
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.minimumHeight = FocusUi.dp(this, 56)
            item.isClickable = true
            item.isFocusable = true
            item.background = FocusUi.withRipple(
                this,
                FocusUi.roundedShape(this, UiPrefs.withAlpha(tokens.surface, 0), 14),
                tokens
            )
            item.setOnClickListener { selectTab(spec.tabId) }
            item.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            item.contentDescription = getString(spec.labelRes)

            // The active indicator is a filled pill behind the icon rather than a
            // line under the label: it survives any text scale the user picks.
            val iconHolder = FrameLayout(this)
            iconHolder.layoutParams = LinearLayout.LayoutParams(
                FocusUi.dp(this, 60),
                FocusUi.dp(this, 32)
            )

            val icon = ImageView(this)
            icon.setImageResource(spec.iconRes)
            icon.layoutParams = FrameLayout.LayoutParams(
                FocusUi.dp(this, 21),
                FocusUi.dp(this, 21)
            ).apply { gravity = Gravity.CENTER }
            iconHolder.addView(icon)

            val label = TextView(this)
            label.text = getString(spec.labelRes)
            label.gravity = Gravity.CENTER
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(12.5f))
            label.typeface = tokens.typeface
            label.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FocusUi.dp(this@MainActivity, 3) }

            item.addView(iconHolder)
            item.addView(label)
            strip.addView(item)
            navButtons.add(NavButton(item, iconHolder, icon, label))
        }

        bar.addView(strip)
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return bar
    }

    private fun updateNavState() {
        val activeNavTab = when (currentTab) {
            TAB_TASKS, TAB_LIBRARY -> TAB_FOCUS
            else -> currentTab
        }
        navButtons.forEachIndexed { index, button ->
            val spec = TAB_SPECS[index]
            val active = spec.tabId == activeNavTab
            val color = if (active) tokens.accentDeep else tokens.textMuted

            button.icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
            button.iconHolder.background = if (active) {
                FocusUi.roundedShape(this, tokens.accentSoft, if (UiPrefs.isBlossom(this)) 20 else 16)
            } else {
                null
            }

            button.label.setTextColor(color)
            button.label.typeface = Typeface.create(tokens.typeface, 650, false)
        }
    }

    /**
     * Kiosk can take over the whole screen, but only because the user asked for
     * it: hiding the nav also hides the safe browser and the library, and a
     * locked phone with nothing to do on it is what drives people to break out.
     */
    private fun shouldHideNav(): Boolean =
        SessionManager.shouldLockTask(this) &&
            CapabilityRegistry.getBoolParam(this, Capabilities.KIOSK_MODE, "fullScreenSurface", false)

    fun selectTab(index: Int, animate: Boolean = true) {
        val safeIndex = index.coerceIn(TAB_FOCUS, TAB_YOU)
        currentTab = safeIndex
        // The tab shell's views do not exist during a brick window. Remember
        // where they wanted to go, so it opens there when the window lifts.
        if (lockedDown) return

        val tab = tabs.getOrPut(safeIndex) { createTab(safeIndex) }
        val view = tab.view

        if (view.parent == null) contentHost.addView(view)
        val outgoing = (0 until contentHost.childCount)
            .map { contentHost.getChildAt(it) }
            .firstOrNull { it != view && it.visibility == View.VISIBLE }

        if (animate) {
            Motion.crossfade(outgoing, view, tokens)
        } else {
            outgoing?.visibility = View.GONE
            view.visibility = View.VISIBLE
        }

        tab.onShow()
        updateNavState()
        // Moon has no bottom bar: Home carries a You button, You leads to the circle.
        navBar.visibility = if (shouldHideNav() || tokens.moon) View.GONE else View.VISIBLE
    }

    private fun createTab(index: Int): FocusTab = when (index) {
        TAB_TASKS -> TasksTab(this, tokens)
        TAB_LIBRARY -> LibraryTab(this, tokens)
        TAB_RULES -> RulesTab(this, tokens)
        TAB_YOU -> YouTab(this, tokens)
        else -> FocusDashboardTab(this, tokens)
    }

    /** Lets a tab ask the shell to redraw after a change that alters the theme. */
    fun requestShellRebuild() {
        buildShell()
    }

    private data class TabSpec(val tabId: Int, val labelRes: Int, val iconRes: Int)

    companion object {
        const val EXTRA_TAB = "focuslock_tab"
        const val TAB_FOCUS = 0
        const val TAB_TASKS = 1
        const val TAB_LIBRARY = 2
        const val TAB_RULES = 3
        const val TAB_YOU = 4

        // Streamlined 3-tab bottom bar: Focus, Rules, and You.
        // Tasks and Library remain accessible from the Focus tab cards and Kiosk screen.
        private val TAB_SPECS = listOf(
            TabSpec(TAB_FOCUS, R.string.tab_focus, R.drawable.ic_tab_focus),
            TabSpec(TAB_RULES, R.string.tab_rules, R.drawable.ic_tab_rules),
            TabSpec(TAB_YOU, R.string.tab_you, R.drawable.ic_tab_you)
        )

        fun open(context: android.content.Context, tab: Int) {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_TAB, tab)
                }
            )
        }
    }
}

/**
 * A tab owns its view and knows how to refresh it. Nothing more.
 */
abstract class FocusTab(protected val activity: MainActivity, protected val tokens: UiPrefs.Tokens) {

    val view: View by lazy { build() }

    protected abstract fun build(): View

    /** Called every time the tab becomes visible. */
    open fun onShow() = Unit

    /** Called once a second while this tab is the visible one. */
    open fun onTick() = Unit

    /**
     * The scrolling column this tab redraws into, and the scroller around it.
     *
     * Subclasses register these once in [build] and then redraw through
     * [redraw] instead of clearing the column themselves - otherwise every
     * toggle in Rules, every filter chip in Tasks and every state change in You
     * silently scrolls the person back to the top of the tab, because an
     * emptied scroll view has no height left to hold its offset.
     */
    private var scroller: View? = null
    private var column: ViewGroup? = null

    protected fun hostScroll(scroll: View, content: ViewGroup): View {
        scroller = scroll
        column = content
        return scroll
    }

    /** Refill the tab's column, keeping the reader's place in it. */
    protected fun redraw(fill: () -> Unit) {
        val content = column
        if (content == null) {
            fill()
            return
        }
        FocusUi.rebuildPreservingScroll(scroller, content, fill)
    }
}
