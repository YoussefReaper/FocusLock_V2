package com.focuslock.mdm

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * The base every settings screen sits on.
 *
 * It owns the parts that must never differ between screens: resolving tokens,
 * painting the system bars, the scrolling column, the page header, and
 * re-rendering on resume so a theme change made two screens away is already
 * applied when you come back. Subclasses only describe their content.
 */
abstract class FocusScreenActivity : AppCompatActivity() {

    protected lateinit var tokens: UiPrefs.Tokens
    protected lateinit var content: LinearLayout

    /** Held so [refresh] can refill the column in place and keep the reader's place in it. */
    private var scroll: androidx.core.widget.NestedScrollView? = null

    /** Shown in the page header. */
    protected abstract fun screenTitle(): String

    protected open fun screenSubtitle(): String? = null

    /** Fill the scrolling column. Called on create and on every resume. */
    protected abstract fun buildContent(column: LinearLayout)

    /** onResume always follows onCreate; without this the screen builds twice on launch. */
    private var freshlyCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        freshlyCreated = true
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy.syncLockTaskState(this)
        if (freshlyCreated) {
            freshlyCreated = false
            themeSignature = UiPrefs.signature(this)
            return
        }
        // Coming back from a sub-screen is a refresh, not a rebuild: the values
        // may have changed but the layout has not, and rebuilding would lose
        // the reader's place for no reason. Only an actual theme change (which
        // reaches every colour, font and padding here) needs the full pass.
        val signature = UiPrefs.signature(this)
        if (signature != themeSignature) {
            themeSignature = signature
            renderScreen()
        } else {
            refresh()
        }
    }

    private var themeSignature: String = ""

    protected fun renderScreen() {
        tokens = UiPrefs.resolve(this)
        themeSignature = UiPrefs.signature(this)
        FocusUi.applySystemBars(window, tokens)

        val root = FocusUi.screenRoot(this, tokens)

        content = FocusUi.column(this, tokens.density.contentPaddingDp)
        fillContent()

        val scroller = FocusUi.scroll(this, content)
        scroller.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        scroll = scroller
        root.addView(scroller)
        setContentView(root)

        Motion.fadeIn(content, tokens)
    }

    protected open fun screenHelpText(): String? = null

    private fun fillContent() {
        content.addView(
            FocusUi.pageHeader(this, tokens, screenTitle(), screenSubtitle()) { finish() }
        )
        val whyLink = FocusUi.smallButton(this, tokens, getString(R.string.common_why_needed_button)) {
            val explanation = screenHelpText()
                ?: screenSubtitle()
                ?: getString(R.string.common_why_needed_default)
            FocusDialog.info(this, screenTitle(), explanation)
        }
        whyLink.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            FocusUi.dp(this, tokens.density.quickButtonHeightDp)
        ).apply { bottomMargin = FocusUi.dp(this@FocusScreenActivity, 12) }
        content.addView(whyLink)

        buildContent(content)
        content.addView(FocusUi.spacer(this, 28))
    }

    /**
     * Redraw after a change, without losing the reader's place.
     *
     * This used to call [renderScreen], which builds a whole new view tree and
     * calls setContentView - so every tap that changed anything (an app's
     * policy, a toggle, a filter chip) silently sent the person back to the top
     * of the screen. On a 200-app list that is the difference between a usable
     * screen and an unusable one. Only the column is refilled now, and its
     * offset is carried across the rebuild.
     */
    protected fun refresh() {
        if (!this::content.isInitialized) {
            renderScreen()
            return
        }
        // A screen that changes the theme from inside itself - Personalisation
        // is the whole reason this case exists - needs the full pass: the root
        // background, the wallpaper and the system bars are painted by
        // renderScreen, and refilling the column alone would leave a new theme
        // half-applied until you navigated away and back.
        val signature = UiPrefs.signature(this)
        if (signature != themeSignature) {
            themeSignature = signature
            renderScreen()
            return
        }
        tokens = UiPrefs.resolve(this)
        FocusUi.rebuildPreservingScroll(scroll, content) { fillContent() }
    }

    protected fun card(build: (LinearLayout) -> Unit): View {
        val view = FocusUi.card(this, tokens)
        build(view)
        return view
    }

    protected fun sectionLabel(text: String): View = FocusUi.sectionLabel(this, tokens, text)

    /** Every picker in the app funnels through here so they all behave alike. */
    protected fun pickApps(
        title: String,
        subtitle: String?,
        selected: Set<String>,
        includeSystem: Boolean = false,
        onSave: (Set<String>) -> Unit
    ) {
        val apps = if (includeSystem) AppCatalog.all(this) else AppCatalog.launchable(this)
        val choices = apps.map { app ->
            FocusDialog.Choice(
                key = app.packageName,
                label = app.label,
                subtitle = app.category.label(this),
                leadingPackage = app.packageName
            )
        }
        FocusDialog.multiChoice(this, title, subtitle, choices, selected) { result ->
            onSave(result)
            refresh()
        }
    }
}
