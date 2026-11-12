package com.focuslock.mdm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.widget.NestedScrollView
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * The component library.
 *
 * One rule holds the whole design system together: **a screen never names a
 * colour**. It asks for a component, passes the resolved [UiPrefs.Tokens], and
 * gets back something already wearing the user's theme, font, radius, density
 * and text scale. That is why changing the accent reaches the block screen and
 * the dialogs, not just the dashboard.
 *
 * Everything is built in code rather than XML for the same reason: an XML
 * attribute is a hardcoded value waiting to drift out of sync with the tokens.
 */
object FocusUi {

    // ── Units ─────────────────────────────────────────────────────

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    fun dpf(context: Context, value: Int): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        )

    // ── System bars ───────────────────────────────────────────────

    /**
     * Paints the status and navigation bars to match the screen, and flips their
     * icon contrast to suit.
     *
     * Contrast is decided from the actual background colour rather than the
     * theme's light flag, because a light theme with a dark custom background
     * would otherwise end up with white icons on white.
     */
    fun applySystemBars(window: android.view.Window, tokens: UiPrefs.Tokens, navColor: Int? = null) {
        window.statusBarColor = tokens.background
        window.navigationBarColor = navColor ?: tokens.background

        val light = isLightColor(tokens.background)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = isLightColor(navColor ?: tokens.background)
    }

    fun isLightColor(color: Int): Boolean {
        val luminance = (0.299 * android.graphics.Color.red(color) +
            0.587 * android.graphics.Color.green(color) +
            0.114 * android.graphics.Color.blue(color)) / 255.0
        return luminance > 0.55
    }

    // ── Shapes ────────────────────────────────────────────────────

    fun roundedShape(
        context: Context,
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpf(context, radiusDp)
        setColor(color)
        if (strokeColor != null) setStroke(dp(context, strokeWidthDp), strokeColor)
    }

    fun pillShape(context: Context, color: Int, heightDp: Int): GradientDrawable =
        roundedShape(context, color, heightDp / 2)

    /** [roundedShape] with independent per-corner radii - the dialog sheet's top-only rounding needs this. */
    fun cornersShape(
        context: Context,
        color: Int,
        topLeftDp: Int,
        topRightDp: Int,
        bottomRightDp: Int,
        bottomLeftDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        val tl = dpf(context, topLeftDp)
        val tr = dpf(context, topRightDp)
        val br = dpf(context, bottomRightDp)
        val bl = dpf(context, bottomLeftDp)
        cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
        setColor(color)
        if (strokeColor != null) setStroke(dp(context, strokeWidthDp), strokeColor)
    }

    /**
     * The primary-button gradient - the one deliberate gradient fill in the
     * app, reserved for the single highest-emphasis action on a screen.
     * `startColor` (usually tokens.accent) is the light stop; `endColor` is
     * the deep stop, so this looks right for whichever of the 8 accents the
     * user picked, not just the default blue.
     */
    fun gradientShape(context: Context, startColor: Int, endColor: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(startColor, endColor)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(context, radiusDp)
        }

    /**
     * Press feedback that follows the accent instead of the platform default,
     * so a tap looks like the rest of the app rather than like stock Android.
     */
    fun withRipple(context: Context, content: Drawable, tokens: UiPrefs.Tokens): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(UiPrefs.withAlpha(tokens.accent, 70)),
            content,
            null
        )

    // ── Containers ────────────────────────────────────────────────

    /**
     * The root of every screen: wallpaper or flat background, plus the bedtime
     * dim as a real overlay so it also darkens whatever the screen draws.
     */
    fun screenRoot(context: Context, tokens: UiPrefs.Tokens): FrameLayout {
        val root = FrameLayout(context)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        if (tokens.wallpaperRes != 0) {
            root.setBackgroundResource(tokens.wallpaperRes)
        } else {
            root.setBackgroundColor(tokens.background)
        }
        return root
    }

    fun dimOverlay(context: Context, tokens: UiPrefs.Tokens): View? {
        if (tokens.dimPercent <= 0) return null
        val view = View(context)
        view.setBackgroundColor(
            UiPrefs.withAlpha(android.graphics.Color.BLACK, tokens.dimPercent * 255 / 100)
        )
        view.isClickable = false
        view.isFocusable = false
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        return view
    }

    fun column(context: Context, paddingDp: Int = 0): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (paddingDp > 0) {
            val padding = dp(context, paddingDp)
            layout.setPadding(padding, padding, padding, padding)
        }
        return layout
    }

    fun row(context: Context): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER_VERTICAL
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return layout
    }

    fun scroll(context: Context, content: View): NestedScrollView {
        val scroll = NestedScrollView(context)
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        scroll.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    /**
     * A scroll view that shrinks to fit short content but never grows past
     * [maxHeightPx] - the fix for a bottom sheet whose body can run longer
     * than the screen. Giving a `NestedScrollView` a WRAP_CONTENT height (as
     * a naive fix might) doesn't make it scroll at all - there's nothing to
     * clip against, so it just measures as tall as its content and the sheet
     * grows off the bottom of the screen, taking the Save button with it.
     * This clamps the measure spec directly instead, so content under the
     * cap sizes naturally and content over it actually scrolls.
     */
    fun boundedScroll(context: Context, content: View, maxHeightPx: Int): NestedScrollView {
        val scroll = object : NestedScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
            }
        }
        scroll.isFillViewport = false
        scroll.clipToPadding = false
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return scroll
    }

    /** A dialog's own body should never eat the whole screen - this leaves room above it (title/subtitle) and below it (the action row) so both stay visible no matter how long the body is. */
    fun dialogScrollMaxHeight(context: Context): Int =
        (context.resources.displayMetrics.heightPixels * 0.52f).toInt()

    fun horizontalScroll(context: Context, content: View): android.widget.HorizontalScrollView {
        val scroll = android.widget.HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    /**
     * Refills a scrolling column without throwing the reader back to the top.
     *
     * Every screen in the app redraws by tearing its content down and building
     * it again - that is what makes a toggle's knock-on effects show up
     * immediately - but an emptied `NestedScrollView` has nothing left to
     * scroll, so its offset collapses to zero and the person lands at the top
     * of a list they were forty rows into. Tapping an app, changing its policy
     * and being bounced back to the start of the list was this, and so was
     * every toggle in Rules doing the same.
     *
     * The offset is captured before the teardown and reapplied in a pre-draw
     * pass, which is the first moment the rebuilt content has a real height to
     * clamp against - a plain `post` can land before layout and silently clamp
     * to zero, which looks exactly like not having fixed it at all.
     */
    fun rebuildPreservingScroll(scroll: View?, content: ViewGroup, fill: () -> Unit) {
        val scroller = scroll as? NestedScrollView
        val offset = scroller?.scrollY ?: 0

        content.removeAllViews()
        fill()

        if (scroller == null || offset <= 0) return
        val observer = scroller.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // The listener has to come off whichever observer is alive now:
                // the one captured above is dead if the view was reattached.
                if (scroller.viewTreeObserver.isAlive) {
                    scroller.viewTreeObserver.removeOnPreDrawListener(this)
                } else {
                    observer.removeOnPreDrawListener(this)
                }
                scroller.scrollTo(0, offset)
                return true
            }
        })
    }

    fun spacer(context: Context, heightDp: Int): View {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, heightDp)
        )
        return view
    }

    fun divider(context: Context, tokens: UiPrefs.Tokens, marginTopDp: Int = 0): View {
        val view = View(context)
        view.setBackgroundColor(tokens.divider)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 1)
        ).apply { topMargin = dp(context, marginTopDp) }
        return view
    }

    // ── Cards ─────────────────────────────────────────────────────

    /**
     * A hairline stroke rather than a shadow: elevation shadows disappear on
     * dark surfaces, and a 1px border reads as a real edge in every theme.
     */
    fun card(
        context: Context,
        tokens: UiPrefs.Tokens,
        elevated: Boolean = false,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        val padding = dp(context, tokens.density.cardPaddingDp)
        layout.setPadding(padding, padding, padding, padding)

        val fill = if (elevated) tokens.surfaceAlt else tokens.surface
        // Quiet Pine: cards sit on the stone ground with no border or shadow;
        // the colour step alone separates them. Dark themes keep a hairline,
        // where a 6% lightness step is too faint to see on some screens.
        val shape = roundedShape(
            context,
            fill,
            tokens.cardRadiusDp,
            if (tokens.isLight) null else tokens.divider
        )

        if (onClick != null) {
            layout.background = withRipple(context, shape, tokens)
            layout.isClickable = true
            layout.isFocusable = true
            layout.setOnClickListener { onClick() }
        } else {
            layout.background = shape
        }

        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, tokens.density.gapDp) }
        return layout
    }

    // ── Type ──────────────────────────────────────────────────────

    /**
     * The one place that decides which face a piece of text gets: the
     * user's chosen prose font ([UiPrefs.Tokens.typeface]) for words, or the
     * fixed [UiPrefs.Tokens.monoTypeface] (always IBM Plex Mono) for anything
     * structural - numerals, timers, counts, chip/pill labels, overlines.
     * Weight is a real weight (400-800), not the old NORMAL/BOLD style pair,
     * so Figtree and Plex Mono's actual weight instances get used rather
     * than a synthetic bold.
     */
    fun applyFont(view: TextView, tokens: UiPrefs.Tokens, mono: Boolean = false, weight: Int = 400) {
        // Quiet Pine: the old "mono" role (numbers, counts, small structural
        // labels) splits by size. Big numbers and timers get the Bricolage
        // display face; small labels stay in the prose face, where a
        // grotesque display cut would only look cramped. Real code blocks set
        // Typeface.MONOSPACE themselves and never come through here.
        val large = view.textSize >= 18f * view.resources.displayMetrics.scaledDensity
        val base = when {
            mono && large -> tokens.displayTypeface
            else -> tokens.typeface
        }
        view.typeface = if (tokens.serifDisplay && base === tokens.displayTypeface) base else Typeface.create(base, weight, false)
    }

    /** Titles and big numbers: the Bricolage display face. */
    fun applyDisplayFont(view: TextView, tokens: UiPrefs.Tokens, weight: Int = 650) {
        view.typeface = if (tokens.serifDisplay) tokens.displayTypeface else Typeface.create(tokens.displayTypeface, weight, false)
        if (tokens.serifDisplay) view.letterSpacing = 0f
    }

    private fun text(
        context: Context,
        tokens: UiPrefs.Tokens,
        value: CharSequence,
        sizeSp: Float,
        color: Int,
        weight: Int = 400,
        mono: Boolean = false,
        letterSpacing: Float = 0f
    ): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(sizeSp))
        view.setTextColor(color)
        applyFont(view, tokens, mono, weight)
        if (letterSpacing != 0f) view.letterSpacing = letterSpacing
        view.setLineSpacing(dpf(context, 3), 1f)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return view
    }

    // Sizes/weights below match the Components/Redesign design docs' type
    // scale exactly (2026-09 design system pass): display 34/40 700,
    // screenTitle 26/32 800, heading 19/25 700, rowTitle 15/20 600,
    // body 15/22 400, caption 13/18 500, overline 11/14 600 mono +9%,
    // numeral 46/46 500 mono.

    // Quiet Pine type scale: display 44 and title 30 in Bricolage Grotesque,
    // heading 17/650 in Figtree, body 15.5, caption 13.5.
    fun display(context: Context, tokens: UiPrefs.Tokens, value: CharSequence): TextView =
        text(context, tokens, value, 44f, tokens.textPrimary, 650, letterSpacing = -0.025f).also {
            applyDisplayFont(it, tokens)
            it.setLineSpacing(0f, 1f)
        }

    /**
     * Keeps a countdown inside its ring. The text gets a fixed width that clears the
     * stroke and shrinks to fit, so "2:59:59" or a day count never spills over the arc.
     */
    fun fitInRing(view: TextView, ringDp: Int, maxSp: Int = 44) {
        val inner = (ringDp * 0.76f).toInt()
        view.layoutParams = LinearLayout.LayoutParams(dp(view.context, inner), LinearLayout.LayoutParams.WRAP_CONTENT)
        view.maxLines = 1
        view.setAutoSizeTextTypeUniformWithConfiguration(18, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

    fun title(context: Context, tokens: UiPrefs.Tokens, value: CharSequence): TextView =
        text(context, tokens, value, 30f, tokens.textPrimary, 650, letterSpacing = -0.015f).also {
            applyDisplayFont(it, tokens)
        }

    fun heading(context: Context, tokens: UiPrefs.Tokens, value: CharSequence): TextView =
        text(context, tokens, value, 17f, tokens.textPrimary, 650)

    fun body(context: Context, tokens: UiPrefs.Tokens, value: CharSequence): TextView =
        text(context, tokens, value, 15f, tokens.textPrimary, 400)

    fun secondary(context: Context, tokens: UiPrefs.Tokens, value: CharSequence): TextView =
        text(context, tokens, value, 15f, tokens.textSecondary, 400)

    /** The 15/20/600 title role [listRow] uses internally, exposed for a caller building its own row shape (e.g. a per-row card) instead of the shared row container. */
    fun rowTitle(context: Context, tokens: UiPrefs.Tokens, value: CharSequence): TextView =
        text(context, tokens, value, 15f, tokens.textPrimary, 600)

    fun caption(context: Context, tokens: UiPrefs.Tokens, value: CharSequence): TextView =
        text(context, tokens, value, 13.5f, tokens.textMuted, 500)

    /** Small all-caps label that opens a group of cards. Mono, per the "mono retreats to structure" rule. */
    fun sectionLabel(context: Context, tokens: UiPrefs.Tokens, value: String): TextView {
        // Sentence case in the prose face: a quiet group title, not a shout.
        val view = text(
            context,
            tokens,
            value,
            13f,
            tokens.textMuted,
            650,
            letterSpacing = 0.01f
        )
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // Fixed per the Components doc ("margin 24 above, 12 below"), not density-derived -
            // a section label separates groups of content, which doesn't get tighter just
            // because the density setting shrank button heights and card padding.
            topMargin = dp(context, 26)
            bottomMargin = dp(context, 10)
            marginStart = dp(context, 4)
        }
        return view
    }

    // ── Buttons ───────────────────────────────────────────────────

    private fun baseButton(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        fill: Int,
        textColor: Int,
        strokeColor: Int?,
        weight: Int = 600,
        gradient: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val view = TextView(context)
        view.text = label
        view.gravity = Gravity.CENTER
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(16f))
        view.setTextColor(textColor)
        applyFont(view, tokens, weight = weight)
        view.isAllCaps = false
        view.isClickable = true
        view.isFocusable = true

        val shape = if (gradient) {
            gradientShape(context, fill, tokens.accentDeep, tokens.buttonRadiusDp)
        } else {
            roundedShape(context, fill, tokens.buttonRadiusDp, strokeColor)
        }
        view.background = withRipple(context, shape, tokens)
        view.setOnClickListener {
            Motion.tap(view, tokens)
            onClick()
        }
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, tokens.density.buttonHeightDp + 2)
        )
        return view
    }

    /**
     * The one gradient fill in the app, reserved for a screen's single
     * highest-emphasis action - #4C93FF -> #1D4ED8 on the default accent,
     * derived the same way for whichever of the 8 accents the user picked.
     */
    fun primaryButton(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        onClick: () -> Unit
    ): TextView = baseButton(context, tokens, label, tokens.accent, tokens.onAccent, null, weight = 650, onClick = onClick)

    /** Soft accent fill: an important action that is not the screen's one primary. */
    fun tonalButton(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        onClick: () -> Unit
    ): TextView = baseButton(context, tokens, label, tokens.accentSoft, tokens.accentDeep, null, weight = 650, onClick = onClick)

    /**
     * No fill, a plain 1dp divider border, muted text - the doc's "Set it up"
     * spec exactly. A filled surfaceAlt button here would compete with the
     * primary gradient for attention on a screen that's meant to have a
     * single highest-emphasis action.
     */
    fun secondaryButton(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        onClick: () -> Unit
    ): TextView = baseButton(
        context,
        tokens,
        label,
        tokens.surface,
        tokens.textPrimary,
        UiPrefs.blend(tokens.divider, tokens.textMuted, 0.25f),
        weight = 650,
        onClick = onClick
    )

    fun ghostButton(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        onClick: () -> Unit
    ): TextView = baseButton(
        context,
        tokens,
        label,
        UiPrefs.withAlpha(tokens.surface, 0),
        tokens.accent,
        null,
        weight = 500,
        onClick = onClick
    )

    /**
     * Danger red never fills a button - a border only, so a destructive
     * action never gets one accidental tap away. See FocusUi's own doc
     * comment on this: "a filled red button invites a mis-tap."
     */
    fun dangerButton(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        onClick: () -> Unit
    ): TextView = baseButton(
        context,
        tokens,
        label,
        tokens.dangerSoft,
        if (tokens.isLight) UiPrefs.blend(tokens.danger, android.graphics.Color.BLACK, 0.2f) else tokens.danger,
        null,
        weight = 650,
        onClick = onClick
    )

    fun smallButton(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        onClick: () -> Unit
    ): TextView {
        val view = baseButton(
            context,
            tokens,
            label,
            tokens.surface,
            tokens.textPrimary,
            UiPrefs.blend(tokens.divider, tokens.textMuted, 0.25f),
            weight = 600,
            onClick = onClick
        )
        view.background = withRipple(
            context,
            roundedShape(context, tokens.surface, 12, UiPrefs.blend(tokens.divider, tokens.textMuted, 0.25f)),
            tokens
        )
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(14f))
        val horizontal = dp(context, 14)
        view.setPadding(horizontal, 0, horizontal, 0)
        // No trailing margin.
        //
        // It used to carry `marginEnd = 8` unconditionally, which is wrong in
        // the place these are most often used: as a `listRow` trailing control.
        // A row ending in a small button sat 8dp further from the card's right
        // edge than the row above it ending in a chevron or a pill, so lists
        // that mixed the two (always-allowed, the suggestions list, the
        // capability rows) had a visibly ragged right edge. Rows of several
        // small buttons ask for their own spacing with [spacerH] instead.
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(context, tokens.density.quickButtonHeightDp)
        )
        return view
    }

    // ── Rows ──────────────────────────────────────────────────────

    /**
     * The workhorse: a title, an optional explanation, and a control on the
     * right. Every settings screen is made of these, which is why they all
     * behave the same.
     */
    fun listRow(
        context: Context,
        tokens: UiPrefs.Tokens,
        title: CharSequence,
        subtitle: CharSequence? = null,
        trailing: View? = null,
        leading: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val rowView = LinearLayout(context)
        rowView.orientation = LinearLayout.HORIZONTAL
        rowView.gravity = Gravity.CENTER_VERTICAL
        val padV = dp(context, 13)
        // No horizontal padding of its own: the card already provides the
        // inset, and adding 2dp on top of it put every row - and so every
        // leading icon and every trailing switch - two pixels inside the
        // headings, dividers and buttons stacked around them in the same card.
        // Two pixels is invisible on any one row and unmistakable down a list
        // of twenty, which is most of what "the wrappers aren't equal" is.
        rowView.setPadding(0, padV, 0, padV)
        rowView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rowView.minimumHeight = dp(context, if (tokens.density.id == "compact") 48 else 56)

        if (leading != null) {
            rowView.addView(leading)
            rowView.addView(spacerH(context, 13))
        }

        val textColumn = LinearLayout(context)
        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        val titleView = text(context, tokens, title, 15f, tokens.textPrimary, 600)
        textColumn.addView(titleView)
        if (!subtitle.isNullOrBlank()) {
            val subtitleView = text(context, tokens, subtitle, 13f, tokens.textMuted, 400)
            subtitleView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 2) }
            textColumn.addView(subtitleView)
        }
        rowView.addView(textColumn)

        if (trailing != null) {
            rowView.addView(spacerH(context, 13))
            rowView.addView(trailing)
        }

        if (onClick != null) {
            val shape = roundedShape(context, UiPrefs.withAlpha(tokens.surface, 0), tokens.rowRadiusDp)
            rowView.background = withRipple(context, shape, tokens)
            rowView.isClickable = true
            rowView.isFocusable = true
            rowView.setOnClickListener { onClick() }
        }
        return rowView
    }

    fun spacerH(context: Context, widthDp: Int): View {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), 1)
        return view
    }

    fun chevron(context: Context, tokens: UiPrefs.Tokens): TextView {
        val view = TextView(context)
        view.text = "›"
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(18f))
        view.setTextColor(tokens.textMuted)
        applyFont(view, tokens, weight = 600)
        // Decoration: the row it sits in is what gets announced and tapped.
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return view
    }

    /** Mono, accent-colored - a value being reported, not a word. Matches numeral/overline roles. */
    fun valueLabel(context: Context, tokens: UiPrefs.Tokens, value: CharSequence): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(15f))
        view.setTextColor(tokens.accent)
        applyFont(view, tokens, mono = true, weight = 500)
        return view
    }

    // ── Toggle ────────────────────────────────────────────────────

    /**
     * The Components doc's switch, drawn by hand rather than the platform's
     * Material one: a flat 44×26 pill and a 20dp knob, not the taller oval
     * track and shadowed thumb `SwitchCompat` renders - the difference is
     * small in isolation but every toggle in the app is one of these, so it
     * was the single biggest reason settings screens still read as
     * off-the-shelf Android instead of the app's own design.
     *
     * `View.background` is left alone here (the track is its own child view)
     * specifically so [toggleRow]'s missing-permission case can keep doing
     * what it always did: set a warning-ringed background plus padding on
     * the returned control to frame it, without that ring ever being
     * confused for the track itself.
     */
    fun switchControl(
        context: Context,
        tokens: UiPrefs.Tokens,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): FocusSwitch {
        val control = FocusSwitch(context, tokens, checked)
        control.onCheckedChange = onChange
        return control
    }

    /** The switch [switchControl] builds - a public type so callers that toggle it programmatically (e.g. a tapped row) can still read/set `isChecked` directly. */
    class FocusSwitch(
        context: Context,
        private val tokens: UiPrefs.Tokens,
        initialChecked: Boolean
    ) : FrameLayout(context) {
        private val trackView = View(context)
        private val knobView = View(context)
        private val knobPx = dp(context, 24)
        private val insetPx = dp(context, 3)
        private val travelPx = (dp(context, 50) - knobPx - insetPx * 2).toFloat()
        private val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

        var onCheckedChange: ((Boolean) -> Unit)? = null

        var isChecked: Boolean = initialChecked
            set(value) {
                val changed = field != value
                field = value
                applyState(animate = changed)
                if (changed) {
                    onCheckedChange?.invoke(value)
                    sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }

        init {
            layoutParams = ViewGroup.LayoutParams(dp(context, 50), dp(context, 30))
            trackView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(trackView)
            knobView.layoutParams = LayoutParams(knobPx, knobPx).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = insetPx
            }
            addView(knobView)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (isEnabled) isChecked = !isChecked }
            applyState(animate = false)
        }

        private fun applyState(animate: Boolean) {
            trackView.background = roundedShape(
                context,
                if (isChecked) tokens.accent else UiPrefs.blend(tokens.track, tokens.textMuted, 0.25f),
                20
            )
            knobView.background = roundedShape(
                context,
                if (isChecked) tokens.onAccent.let { if (tokens.isLight) android.graphics.Color.WHITE else it }
                else android.graphics.Color.WHITE,
                12
            )
            val forward = if (rtl) -1f else 1f
            val target = if (isChecked) travelPx * forward else 0f
            knobView.animate().cancel()
            val duration = if (animate && !tokens.reducedMotion) 200L else 0L
            if (duration > 0L) {
                knobView.animate().translationX(target).setDuration(duration).start()
            } else {
                knobView.translationX = target
            }
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.5f
        }

        // Drawn from two plain views, so without these a screen reader heard
        // "double-tap to activate" and nothing about what it was or whether it
        // was on. It announces itself as a switch, with its state.
        override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isCheckable = true
            info.isChecked = isChecked
        }
    }

    /**
     * The workhorse toggle. Two states beyond plain on/off/disabled, both
     * opt-in via parameters rather than auto-detected, since a row has no
     * way to know on its own *why* it's locked:
     *
     * [frozen] - a session's rules are held still (SessionLock.isFrozen).
     * The row dims to 55% and gets a small padlock glyph next to the title,
     * but the switch keeps showing its real value underneath - the point is
     * "this is true, and you cannot change it right now," not "this is off."
     *
     * [missingPermissionHint] - the switch is on, but the permission it
     * depends on isn't granted, so it isn't actually doing anything. Gets a
     * warning-coloured ring on the switch itself plus a consequence line
     * naming what's missing, instead of silently pretending to work.
     */
    fun toggleRow(
        context: Context,
        tokens: UiPrefs.Tokens,
        title: CharSequence,
        subtitle: CharSequence?,
        checked: Boolean,
        enabled: Boolean = true,
        frozen: Boolean = false,
        missingPermissionHint: String? = null,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val control = switchControl(context, tokens, checked, onChange)
        control.isEnabled = enabled
        control.contentDescription = title
        if (missingPermissionHint != null) {
            control.background = roundedShape(
                context,
                android.graphics.Color.TRANSPARENT,
                999,
                tokens.warning,
                strokeWidthDp = 2
            )
            val pad = dp(context, 3)
            control.setPadding(pad, pad, pad, pad)
        }

        val titleRow = row(context)
        titleRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val titleView = text(context, tokens, title, 15f, tokens.textPrimary, 600)
        titleView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleRow.addView(titleView)
        if (frozen) {
            titleRow.addView(spacerH(context, 6))
            titleRow.addView(kioskGlyph(context, tokens, sizeDp = 14))
        }

        val subtitleColumn = column(context)
        subtitleColumn.addView(titleRow)
        if (!subtitle.isNullOrBlank()) {
            val subtitleView = text(context, tokens, subtitle, 13f, tokens.textMuted, 400)
            subtitleView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 2) }
            subtitleColumn.addView(subtitleView)
        }
        if (missingPermissionHint != null) {
            val warnRow = row(context)
            warnRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 4) }
            val warnLabel = text(context, tokens, missingPermissionHint, 12.5f, tokens.warning, 500, mono = true)
            warnRow.addView(warnLabel)
            subtitleColumn.addView(warnRow)
        }

        val rowView = listRow(context, tokens, "", null, trailing = control, leading = null) {
            if (enabled) control.isChecked = !control.isChecked
        }
        // listRow already built a plain title TextView from the empty string
        // above; replace its text column with the richer one built here so
        // the glyph/warning line can sit inline with the title instead of
        // needing listRow to know about either.
        (rowView.getChildAt(0) as? LinearLayout)?.let { existingColumn ->
            val index = rowView.indexOfChild(existingColumn)
            rowView.removeViewAt(index)
            subtitleColumn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            rowView.addView(subtitleColumn, index)
        }
        rowView.alpha = if (frozen) 0.55f else if (enabled) 1f else 0.5f
        return rowView
    }

    /** A small padlock, used only for the frozen-row indicator - not part of the main icon set. */
    private fun kioskGlyph(context: Context, tokens: UiPrefs.Tokens, sizeDp: Int): ImageView {
        val view = ImageView(context)
        view.setImageResource(R.drawable.ic_glyph_kiosk)
        view.setColorFilter(tokens.textSecondary)
        view.contentDescription = context.getString(R.string.a11y_held_by_session)
        val size = dp(context, sizeDp)
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        return view
    }

    /**
     * One of the 16-icon category set (design system, 2026-09 pass), sized and tinted for use as
     * a `listRow` leading icon or a section-header glyph. Nothing is baked into the vector - tint
     * is always applied here, `tokens.accent` for an emphasised row, `tokens.textSecondary` (the
     * default) otherwise.
     */
    fun categoryIcon(
        context: Context,
        tokens: UiPrefs.Tokens,
        @DrawableRes resId: Int,
        tint: Int = tokens.textSecondary,
        sizeDp: Int = 24
    ): ImageView {
        val view = ImageView(context)
        view.setImageResource(resId)
        view.setColorFilter(tint)
        // Always beside a label that says the same thing.
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = dp(context, sizeDp)
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        return view
    }

    // ── Slider ────────────────────────────────────────────────────

    fun sliderRow(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, dp(context, 10), 0, dp(context, 10))

        val header = row(context)
        val labelView = body(context, tokens, label)
        labelView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val valueView = valueLabel(context, tokens, format(value))
        header.addView(labelView)
        header.addView(valueView)
        container.addView(header)

        val seek = SeekBar(context)
        seek.max = max - min
        seek.progress = (value - min).coerceIn(0, max - min)
        seek.progressDrawable = sliderTrack(context, tokens)
        seek.thumb = sliderThumb(context, tokens)
        seek.splitTrack = false
        seek.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 4) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val next = min + progress
                valueView.text = format(next)
                if (fromUser) onChange(next)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        container.addView(seek)
        return container
    }

    /**
     * The Components doc's exact slider spec (4dp track, 20dp knob) instead
     * of the platform SeekBar's own drawable, which no amount of tint list
     * ever actually resized - the track stayed platform-thick and the thumb
     * stayed platform-sized no matter what colour it was given.
     */
    private fun sliderTrack(context: Context, tokens: UiPrefs.Tokens): LayerDrawable {
        val backgroundTrack = roundedShape(context, tokens.track, 3)
        val progressTrack = ClipDrawable(
            roundedShape(context, tokens.accent, 3),
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )
        val layer = LayerDrawable(arrayOf<Drawable>(backgroundTrack, progressTrack))
        layer.setId(0, android.R.id.background)
        layer.setId(1, android.R.id.progress)
        val trackHeightPx = dp(context, 6)
        layer.setLayerHeight(0, trackHeightPx)
        layer.setLayerHeight(1, trackHeightPx)
        layer.setLayerGravity(0, Gravity.CENTER_VERTICAL)
        layer.setLayerGravity(1, Gravity.CENTER_VERTICAL)
        return layer
    }

    private fun sliderThumb(context: Context, tokens: UiPrefs.Tokens): GradientDrawable {
        val size = dp(context, 24)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(tokens.accent)
            setStroke(dp(context, 3), tokens.surface)
            setSize(size, size)
        }
    }

    // ── Chips ─────────────────────────────────────────────────────

    /**
     * Selected fills `surfaceAlt`, not the accent - a deliberate correction
     * from the old accent-filled chip. Chips are usually a row of filter
     * options (All / Blocked / Paused / ...), and an accent-filled one reads
     * as "this is the important choice" rather than "this is what's showing
     * right now" - surfaceAlt plus the bolder text says the latter.
     */
    fun chip(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView {
        val view = TextView(context)
        view.text = label
        view.isAllCaps = false
        view.gravity = Gravity.CENTER
        // Quiet Pine: a chip is a choice, so the chosen one is filled with the
        // accent, and the rest are outlined paper.
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(14.5f))
        view.setTextColor(if (selected) tokens.onAccent else tokens.textSecondary)
        applyFont(view, tokens, weight = 600)
        val horizontal = dp(context, 15)
        val vertical = dp(context, 9)
        view.setPadding(horizontal, vertical, horizontal, vertical)
        view.minHeight = dp(context, 38)

        val fill = if (selected) tokens.accent else tokens.surface
        val stroke = if (selected) null else UiPrefs.blend(tokens.divider, tokens.textMuted, 0.25f)
        view.background = withRipple(context, roundedShape(context, fill, tokens.chipRadiusDp, stroke), tokens)
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(context, 8) }
        return view
    }

    /** A single-choice chip strip that scrolls sideways rather than wrapping. */
    fun chipStrip(
        context: Context,
        tokens: UiPrefs.Tokens,
        labels: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ): View {
        val strip = row(context)
        strip.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        labels.forEachIndexed { index, label ->
            val view = chip(context, tokens, label, index == selectedIndex) { onSelect(index) }
            // The gap belongs between chips. Left on the last one it made the
            // strip 6dp wider than its contents, so scrolling to the end
            // stopped 6dp short of the edge and the strip never quite lined up
            // with the card it sits in.
            if (index == labels.size - 1) {
                (view.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
            }
            strip.addView(view)
        }
        val scroll = horizontalScroll(context, strip)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 4) }
        return scroll
    }

    /** Mono, 14%-tint fill of its own status colour - never a solid fill. */
    fun pill(context: Context, tokens: UiPrefs.Tokens, label: CharSequence, color: Int): TextView {
        val view = TextView(context)
        view.text = label
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(12.5f))
        view.setTextColor(color)
        applyFont(view, tokens, weight = 650)
        val horizontal = dp(context, 10)
        val vertical = dp(context, 4)
        view.setPadding(horizontal, vertical, horizontal, vertical)
        view.background = roundedShape(context, UiPrefs.withAlpha(color, 34), 999)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return view
    }

    // ── Data display ──────────────────────────────────────────────

    /**
     * A row of equal-width, equal-height tiles with even gaps and no margin
     * hanging off the end.
     *
     * Both of those were wrong everywhere, and they are why the app's grids
     * looked subtly crooked:
     *
     * - every tile carried its own trailing margin, including the last one, so
     *   a row of three tiles sat 8dp further from the right edge of its card
     *   than the left. The gap only belongs *between* tiles.
     * - tiles were WRAP_CONTENT, so one two-line label made its tile taller
     *   than the one beside it and the "grid" stopped being a grid. Giving the
     *   children MATCH_PARENT height inside a WRAP_CONTENT row makes
     *   LinearLayout measure the tallest and then stretch the rest to match.
     */
    fun tileRow(context: Context, tiles: List<View>, gapDp: Int = 8): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tiles.forEachIndexed { index, tile ->
            val existing = tile.layoutParams as? LinearLayout.LayoutParams
            val weight = existing?.weight?.takeIf { it > 0f } ?: 1f
            tile.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight
            ).apply { if (index < tiles.size - 1) marginEnd = dp(context, gapDp) }
            row.addView(tile)
        }
        return row
    }

    fun statTile(
        context: Context,
        tokens: UiPrefs.Tokens,
        value: CharSequence,
        label: CharSequence,
        weight: Float = 1f
    ): LinearLayout {
        val tile = LinearLayout(context)
        tile.orientation = LinearLayout.VERTICAL
        // Centred on both axes so a stretched tile keeps its contents in the
        // middle instead of pinning them to the top of the taller neighbour.
        tile.gravity = Gravity.CENTER
        // Less padding at the sides than top and bottom: three of these share
        // a phone's width, and 16dp a side left a label about 60dp to live in.
        val padding = dp(context, 16)
        val side = dp(context, 8)
        tile.setPadding(side, padding, side, padding)
        tile.background = roundedShape(
            context,
            tokens.surface,
            (tokens.cardRadiusDp - 4).coerceAtLeast(4),
            if (tokens.isLight) null else tokens.divider
        )

        // Mono numeral - a count being reported, not a word. One line,
        // shrunk to fit: "2h 25m" broke into two lines in a third of a
        // phone's width, and the tile beside it did not.
        val valueView = text(context, tokens, value, 25f, tokens.textPrimary, 650)
        applyDisplayFont(valueView, tokens)
        valueView.gravity = Gravity.CENTER
        valueView.maxLines = 1
        fitOneLine(valueView)
        val labelView = text(context, tokens, label, 13f, tokens.textMuted, 400)
        labelView.gravity = Gravity.CENTER
        labelView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 4) }
        keepWordsWhole(labelView)

        tile.addView(valueView)
        tile.addView(labelView)
        // No trailing margin of its own - [tileRow] owns the gaps, so the last
        // tile in a row actually reaches the edge its neighbours start from.
        tile.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        return tile
    }

    /**
     * Shrinks [view]'s text until its longest word fits on one line.
     *
     * A TextView breaks a word that is wider than its line in the middle, and
     * with the monospace theme's wide glyphs in a third of a phone's width that
     * turned "Sessions finished" into "Session / s finis / hed". Wrapping
     * between words is fine; splitting one is not. Shrinks in half-sp steps,
     * never below [minSp], and only ever when a word actually overflows.
     */
    fun keepWordsWhole(view: TextView, minSp: Float = 9f) {
        view.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val tv = v as TextView
            val available = (right - left) - tv.totalPaddingLeft - tv.totalPaddingRight
            if (available <= 0) return@addOnLayoutChangeListener
            val words = tv.text.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return@addOnLayoutChangeListener
            val density = tv.resources.displayMetrics.scaledDensity
            var sp = tv.textSize / density
            val paint = android.text.TextPaint(tv.paint)
            fun widest(): Float {
                paint.textSize = sp * density
                return words.maxOf { paint.measureText(it) }
            }
            var changed = false
            while (sp > minSp && widest() > available) {
                sp -= 0.5f
                changed = true
            }
            if (changed) tv.post { tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) }
        }
    }

    /** Shrinks [view]'s single line of text until it fits its width, never below [minSp]. */
    fun fitOneLine(view: TextView, minSp: Float = 14f) {
        view.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val tv = v as TextView
            val available = (right - left) - tv.totalPaddingLeft - tv.totalPaddingRight
            if (available <= 0) return@addOnLayoutChangeListener
            val density = tv.resources.displayMetrics.scaledDensity
            var sp = tv.textSize / density
            val paint = android.text.TextPaint(tv.paint)
            val text = tv.text.toString()
            var changed = false
            while (sp > minSp) {
                paint.textSize = sp * density
                if (paint.measureText(text) <= available) break
                sp -= 1f
                changed = true
            }
            if (changed) tv.post { tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) }
        }
    }

    /** A labelled proportion bar: used for category breakdowns and budgets. */
    fun meter(
        context: Context,
        tokens: UiPrefs.Tokens,
        label: CharSequence,
        value: CharSequence,
        fraction: Float,
        color: Int
    ): LinearLayout {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, dp(context, 8), 0, dp(context, 8))

        val header = row(context)
        val labelView = secondary(context, tokens, label)
        labelView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val valueView = body(context, tokens, value)
        valueView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        header.addView(labelView)
        header.addView(valueView)
        container.addView(header)

        val trackView = FrameLayout(context)
        trackView.background = roundedShape(context, tokens.track, 999)
        trackView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 8)
        ).apply { topMargin = dp(context, 6) }

        val fill = View(context)
        fill.background = roundedShape(context, color, 999)
        val safeFraction = fraction.coerceIn(0f, 1f)
        trackView.addView(
            fill,
            FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        trackView.post {
            val width = (trackView.width * safeFraction).toInt().coerceAtLeast(if (safeFraction > 0f) dp(context, 6) else 0)
            fill.layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT)
            fill.requestLayout()
        }
        container.addView(trackView)
        return container
    }

    /**
     * Sizes 96/152/236 (an unrecognised value falls back to 152), stroke
     * 8/8/10 to match - and always sweeps clockwise from 12 o'clock, in
     * every locale: this is a session's own remaining time, not text, so it
     * never mirrors for RTL.
     */
    fun progressRing(
        context: Context,
        tokens: UiPrefs.Tokens,
        sizeDp: Int = 152
    ): CircularProgressIndicator {
        val strokeDp = if (sizeDp >= 200) 14 else 9
        val ring = CircularProgressIndicator(context)
        ring.isIndeterminate = false
        ring.max = 100
        ring.trackThickness = dp(context, strokeDp)
        ring.indicatorSize = dp(context, sizeDp)
        ring.setIndicatorColor(tokens.accent)
        ring.trackColor = tokens.track
        ring.trackCornerRadius = dp(context, strokeDp / 2)
        return ring
    }

    /** A calm empty card: centred muted text on a plain surface, never a dashed "error" box. */
    fun emptyState(context: Context, tokens: UiPrefs.Tokens, message: CharSequence): TextView {
        val view = text(context, tokens, message, 15f, tokens.textSecondary, 400)
        view.gravity = Gravity.CENTER
        view.setPadding(dp(context, 22), dp(context, 30), dp(context, 22), dp(context, 30))
        view.background = roundedShape(context, tokens.surface, tokens.cardRadiusDp, if (tokens.isLight) null else tokens.divider)
        return view
    }

    /**
     * A leading icon on a soft tinted square - the Quiet Pine row icon.
     * [tone] picks the tint: accent (default), warm for Earn, muted for neutral.
     */
    enum class IconTone { ACCENT, WARM, MUTED, SOLID }

    fun iconTile(
        context: Context,
        tokens: UiPrefs.Tokens,
        @DrawableRes resId: Int,
        tone: IconTone = IconTone.ACCENT,
        sizeDp: Int = 40
    ): FrameLayout {
        val (fill, tint) = when (tone) {
            IconTone.ACCENT -> tokens.accentSoft to tokens.accentDeep
            IconTone.WARM -> tokens.warmSoft to tokens.warmInk
            IconTone.MUTED -> tokens.surfaceAlt to tokens.textSecondary
            IconTone.SOLID -> tokens.accent to tokens.onAccent
        }
        val box = FrameLayout(context)
        box.background = roundedShape(context, fill, (sizeDp * 0.32f).toInt())
        val size = dp(context, sizeDp)
        box.layoutParams = LinearLayout.LayoutParams(size, size)
        box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val icon = categoryIcon(context, tokens, resId, tint, (sizeDp * 0.55f).toInt())
        icon.layoutParams = FrameLayout.LayoutParams(dp(context, (sizeDp * 0.55f).toInt()), dp(context, (sizeDp * 0.55f).toInt()), Gravity.CENTER)
        box.addView(icon)
        return box
    }

    /**
     * Four bars of rising height, the first [filled] solid: the strength
     * ladder glyph for Gentle (1) through Kiosk (4). Strength is told by
     * height and fill, never by colour alone.
     */
    fun strengthBars(context: Context, color: Int, filled: Int, scale: Float = 1f): LinearLayout {
        val bars = LinearLayout(context)
        bars.orientation = LinearLayout.HORIZONTAL
        bars.gravity = Gravity.BOTTOM
        val heights = intArrayOf(6, 9, 12, 16)
        heights.forEachIndexed { index, h ->
            val bar = View(context)
            bar.background = roundedShape(context, if (index < filled) color else UiPrefs.withAlpha(color, 56), 2)
            bar.layoutParams = LinearLayout.LayoutParams(dp(context, (5 * scale).toInt()), dp(context, (h * scale).toInt())).apply {
                if (index < heights.size - 1) marginEnd = dp(context, (3 * scale).toInt().coerceAtLeast(2))
            }
            bars.addView(bar)
        }
        bars.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        bars.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, (16 * scale).toInt()))
        return bars
    }

    fun appIcon(context: Context, tokens: UiPrefs.Tokens, packageName: String, sizeDp: Int = 36): View {
        val drawable = AppCatalog.icon(context, packageName)
        val size = dp(context, sizeDp)
        // radius = size x 0.31, per the doc's appIcon spec.
        val radiusDp = (sizeDp * 0.31f).toInt().coerceAtLeast(4)
        if (drawable == null) {
            val fallback = TextView(context)
            val label = AppCatalog.label(context, packageName)
            fallback.text = if (label.isNotEmpty()) label.substring(0, 1).uppercase() else "?"
            fallback.gravity = Gravity.CENTER
            fallback.setTextColor(tokens.textSecondary)
            applyFont(fallback, tokens, weight = 600)
            fallback.background = roundedShape(context, tokens.surfaceAlt, radiusDp)
            fallback.layoutParams = LinearLayout.LayoutParams(size, size)
            return fallback
        }
        val view = ImageView(context)
        view.setImageDrawable(drawable)
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return view
    }

    // ── Input ─────────────────────────────────────────────────────

    fun input(
        context: Context,
        tokens: UiPrefs.Tokens,
        hint: String,
        value: String = "",
        multiline: Boolean = false,
        numeric: Boolean = false
    ): EditText {
        val field = EditText(context)
        field.hint = hint
        field.setText(value)
        field.setTextColor(tokens.textPrimary)
        field.setHintTextColor(tokens.textMuted)
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(15f))
        // Numeric fields use mono - a value being typed in, same rule as
        // valueLabel/statTile. Text fields keep the user's prose font.
        applyFont(field, tokens, mono = numeric, weight = if (numeric) 500 else 400)
        fun fieldShape(focused: Boolean) = roundedShape(
            context,
            tokens.surface,
            16,
            if (focused) tokens.accent else UiPrefs.blend(tokens.divider, tokens.textMuted, 0.25f),
            strokeWidthDp = 2
        )
        field.background = fieldShape(false)
        field.setOnFocusChangeListener { _, hasFocus -> field.background = fieldShape(hasFocus) }
        val padding = dp(context, 14)
        field.setPadding(padding, padding, padding, padding)
        field.inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            field.gravity = Gravity.TOP or Gravity.START
            field.minLines = 4
        } else {
            field.maxLines = 1
        }
        field.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 10) }
        return field
    }

    /**
     * A number you can type into, with arrows for nudging it.
     *
     * The old stepper was arrows only: two glyphs either side of a plain
     * TextView, so setting a schedule to 6:45 meant fifteen taps and setting it
     * to 23:00 meant a lot more. The number is a real input now - tap it, type
     * it, done - and the arrows stay for the one-step adjustments they are
     * actually good at.
     *
     * [onChange] fires on every accepted value, already clamped to
     * [min]..[max]. Typing something out of range (or nothing at all) leaves
     * the stored value alone until it becomes valid, rather than snapping the
     * field out from under the person mid-keystroke.
     */
    fun numberStepper(
        context: Context,
        tokens: UiPrefs.Tokens,
        value: Int,
        min: Int,
        max: Int,
        step: Int = 1,
        wrap: Boolean = false,
        format: (Int) -> String = { it.toString().padStart(2, '0') },
        onChange: (Int) -> Unit
    ): LinearLayout {
        val box = row(context)
        box.background = roundedShape(context, tokens.surfaceAlt, 14)
        val padH = dp(context, 6)
        box.setPadding(padH, 0, padH, 0)

        val field = EditText(context)
        field.setText(format(value))
        field.gravity = Gravity.CENTER
        field.setTextColor(tokens.textPrimary)
        applyFont(field, tokens, mono = true, weight = 500)
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(20f))
        field.inputType = InputType.TYPE_CLASS_NUMBER
        field.maxLines = 1
        field.background = null
        field.setPadding(0, dp(context, 12), 0, dp(context, 12))
        field.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        /** True while we are writing the field ourselves, so the watcher doesn't echo back. */
        var writing = false

        /**
         * The last value actually reported through [onChange].
         *
         * Not the parameter: that is only ever the value the control was built
         * with. Falling back to it when the field is left empty would show the
         * number the person started from while the caller is still holding the
         * one they typed before clearing it - two different values on screen
         * and in state at once.
         */
        var committed = value

        fun show(next: Int) {
            writing = true
            field.setText(format(next))
            field.setSelection(field.text.length)
            writing = false
        }

        fun clamp(raw: Int): Int = when {
            !wrap -> raw.coerceIn(min, max)
            // A wrapping range is inclusive of both ends, so its span is
            // max - min + 1 - stepping past midnight lands on 00:00, not 00:01.
            else -> {
                val span = max - min + 1
                min + (((raw - min) % span) + span) % span
            }
        }

        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (writing) return
                val typed = s?.toString()?.trim()?.toIntOrNull() ?: return
                // Not clamped while typing: "1" on the way to "15" would
                // otherwise be rewritten to the minimum under the cursor.
                if (typed < min || typed > max) return
                committed = typed
                onChange(typed)
            }
        })

        // Whatever is in the field when focus leaves is what it means, so this
        // is where an out-of-range or empty value gets tidied up instead.
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text?.toString()?.trim()?.toIntOrNull()
            val settled = if (typed == null) committed else clamp(typed)
            show(settled)
            if (settled != committed) {
                committed = settled
                onChange(settled)
            }
        }

        fun arrow(glyph: String, delta: Int): TextView {
            val view = TextView(context)
            view.text = glyph
            view.gravity = Gravity.CENTER
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(12f))
            view.setTextColor(tokens.textSecondary)
            view.isClickable = true
            view.isFocusable = true
            val pad = dp(context, 10)
            view.setPadding(pad, pad, pad, pad)
            view.setOnClickListener {
                val current = field.text?.toString()?.trim()?.toIntOrNull() ?: committed
                val next = clamp(current + delta)
                show(next)
                committed = next
                onChange(next)
            }
            return view
        }

        box.addView(arrow("▾", -step))
        box.addView(field)
        box.addView(arrow("▴", step))
        return box
    }

    // ── Page header ───────────────────────────────────────────────

    /**
     * Consistent top of every sub-screen: a back affordance with a real label,
     * because a bare arrow is one of the icons people most often misread.
     */
    fun pageHeader(
        context: Context,
        tokens: UiPrefs.Tokens,
        titleText: String,
        subtitle: String? = null,
        @androidx.annotation.DrawableRes art: Int? = null,
        onBack: (() -> Unit)? = null
    ): LinearLayout {
        val container = column(context)
        val moon = UiPrefs.isMoon(context)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, tokens.density.gapDp) }

        if (onBack != null) {
            val back = TextView(context)
            back.text = context.getString(R.string.dialog_back)
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(14f))
            back.setTextColor(tokens.accent)
            applyFont(back, tokens, weight = 600)
            back.isClickable = true
            back.isFocusable = true
            val padding = dp(context, 8)
            back.setPadding(0, padding, padding, padding)
            back.setOnClickListener { onBack() }
            container.addView(back)
        }

        if (moon && art != null) {
            container.addView(MoonArt.painting(context, art, 240))
        }
        val titleView = title(context, tokens, titleText)
        if (moon && art != null) {
            titleView.gravity = Gravity.CENTER_HORIZONTAL
            titleView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(titleView)
        if (!subtitle.isNullOrBlank()) {
            val subtitleView = secondary(context, tokens, subtitle)
            if (moon) {
                subtitleView.typeface = android.graphics.Typeface.create(tokens.displayTypeface, android.graphics.Typeface.ITALIC)
                subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(18f))
                if (art != null) subtitleView.gravity = Gravity.CENTER_HORIZONTAL
            }
            subtitleView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 6) }
            container.addView(subtitleView)
        }
        return container
    }

    /**
     * The edit-in-place shell's persistent top bar: a close glyph, the
     * screen's title, and a Save pill that never scrolls away - the design
     * doc's "input panels" fix, and the actual cause of a Save button that
     * used to be unreachable once a dialog's body ran long. Kept outside the
     * scroll entirely (see [FocusEditorActivity]), not just pinned within it.
     */
    fun editorHeader(
        context: Context,
        tokens: UiPrefs.Tokens,
        titleText: String,
        saveLabel: String,
        saveEnabled: Boolean,
        onClose: () -> Unit,
        onSave: () -> Unit
    ): LinearLayout {
        val bar = row(context)
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val padH = dp(context, tokens.density.contentPaddingDp)
        bar.setPadding(padH, dp(context, 14), padH, dp(context, 14))

        val close = TextView(context)
        close.text = "✕"
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(16f))
        close.setTextColor(tokens.textSecondary)
        applyFont(close, tokens, weight = 500)
        close.isClickable = true
        close.isFocusable = true
        val closePad = dp(context, 8)
        close.setPadding(closePad, closePad, closePad, closePad)
        close.setOnClickListener { onClose() }
        bar.addView(close)
        bar.addView(spacerH(context, 10))

        val titleView = text(context, tokens, titleText, 17f, tokens.textPrimary, 700)
        titleView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(titleView)
        bar.addView(spacerH(context, 10))

        val save = TextView(context)
        save.text = saveLabel
        save.gravity = Gravity.CENTER
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(14f))
        save.setTextColor(if (saveEnabled) tokens.onAccent else tokens.textMuted)
        applyFont(save, tokens, weight = 600)
        val saveHorizontal = dp(context, 16)
        save.setPadding(saveHorizontal, 0, saveHorizontal, 0)
        save.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 36))
        save.background = if (saveEnabled) {
            gradientShape(context, tokens.accent, tokens.accentDeep, 12)
        } else {
            roundedShape(context, tokens.surfaceAlt, 12)
        }
        save.isClickable = saveEnabled
        save.isFocusable = saveEnabled
        save.alpha = if (saveEnabled) 1f else 0.6f
        if (saveEnabled) save.setOnClickListener { onSave() }
        bar.addView(save)

        return bar
    }
}
