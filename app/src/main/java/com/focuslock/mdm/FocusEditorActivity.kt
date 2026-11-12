package com.focuslock.mdm

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * The base for a full-screen "edit in place" editor - schedules, tasks, and
 * anything else that used to be a [FocusDialog.custom] sheet long enough to
 * make its own Save button hard to reach.
 *
 * The difference from [FocusScreenActivity] is exactly one thing: the header
 * (close + title + Save) sits outside the scrolling column instead of inside
 * it, so Save stays on screen no matter how long the body runs. That was the
 * actual bug behind "there's no Save button" - a dialog's bottom sheet had no
 * such fixed region, so its action row could scroll (or overflow) out of
 * reach entirely. See the design doc's "input panels" section.
 */
abstract class FocusEditorActivity : AppCompatActivity() {

    protected lateinit var tokens: UiPrefs.Tokens

    /** Shown in the persistent header. */
    protected abstract fun editorTitle(): String

    /** Fill the scrolling column below the header. Called on create and on every resume. */
    protected abstract fun buildContent(column: LinearLayout)

    /** Invoked when Save is tapped. Only reachable when [canSave] is true. */
    protected abstract fun onSave()

    /** Grays out and disables Save - e.g. a blank required title - instead of saving something incomplete. */
    protected open fun canSave(): Boolean = true

    protected open fun saveLabel(): String = getString(R.string.common_save)

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
        val signature = UiPrefs.signature(this)
        if (signature != themeSignature) {
            themeSignature = signature
            renderScreen()
        } else {
            refresh()
        }
    }

    private var themeSignature: String = ""

    /** Held so [refresh] can refill the body in place without losing the scroll offset. */
    private var scroll: androidx.core.widget.NestedScrollView? = null
    private var header: LinearLayout? = null
    private var body: LinearLayout? = null

    protected fun renderScreen() {
        tokens = UiPrefs.resolve(this)
        themeSignature = UiPrefs.signature(this)
        FocusUi.applySystemBars(window, tokens)

        val root = FocusUi.screenRoot(this, tokens)

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // The header is its own container so a refresh can swap just the bar
        // (whose Save button enables and disables as the body changes) without
        // touching the scrolling body below it.
        val headerHost = LinearLayout(this)
        headerHost.orientation = LinearLayout.VERTICAL
        header = headerHost
        fillHeader()
        outer.addView(headerHost)
        outer.addView(FocusUi.divider(this, tokens))

        val content = FocusUi.column(this, tokens.density.contentPaddingDp)
        body = content
        fillBody()

        val scroller = FocusUi.scroll(this, content)
        scroller.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        scroll = scroller
        outer.addView(scroller)

        root.addView(outer)
        setContentView(root)

        Motion.fadeIn(content, tokens)
    }

    private fun fillHeader() {
        val host = header ?: return
        host.removeAllViews()
        host.addView(
            FocusUi.editorHeader(
                this,
                tokens,
                editorTitle(),
                saveLabel(),
                canSave(),
                onClose = { finish() },
                onSave = { onSave() }
            )
        )
    }

    /** A small painting at the top of the editor in the Moon look; null for none. */
    protected open fun editorArt(): Int? = null

    private fun fillBody() {
        val host = body ?: return
        val art = editorArt()
        if (art != null && UiPrefs.isMoon(this)) {
            host.addView(MoonArt.painting(this, art, 150))
            host.addView(FocusUi.spacer(this, 6))
        }
        buildContent(host)
        host.addView(FocusUi.spacer(this, 28))
    }

    /**
     * Redraw after a change, header included so a title/canSave change shows
     * immediately - but in place, so an accordion opening halfway down a long
     * editor does not scroll the person back to the first field.
     */
    protected fun refresh() {
        val host = body
        if (host == null) {
            renderScreen()
            return
        }
        // Same reasoning as FocusScreenActivity.refresh: a theme change has to
        // repaint the root and the system bars, which only renderScreen does.
        val signature = UiPrefs.signature(this)
        if (signature != themeSignature) {
            themeSignature = signature
            renderScreen()
            return
        }
        tokens = UiPrefs.resolve(this)
        fillHeader()
        FocusUi.rebuildPreservingScroll(scroll, host) { fillBody() }
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
