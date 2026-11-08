package com.focuslock.mdm

import android.view.View
import android.widget.LinearLayout

/**
 * Bedtime.
 *
 * It runs on the clock, not on a session, because the whole point is that it
 * happens on the nights you would not have thought to start one. Three levers:
 * what goes quiet, how dark the screen gets, and when it lifts — and it always
 * says when it lifts, so it never feels like a trap.
 */
class BedtimeActivity : FocusScreenActivity() {

    override fun screenTitle(): String = getString(R.string.bedtime_title)

    override fun screenSubtitle(): String = getString(R.string.bedtime_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildToggle())

        if (!CapabilityRegistry.isEnabled(this, Capabilities.BEDTIME_MODE)) return

        column.addView(sectionLabel(getString(R.string.bedtime_section_when)))
        column.addView(buildWindowCard())

        column.addView(sectionLabel(getString(R.string.bedtime_section_what_quiet)))
        column.addView(buildCategoryCard())

        column.addView(sectionLabel(getString(R.string.bedtime_section_strength)))
        column.addView(buildStrengthCard())

        column.addView(sectionLabel(getString(R.string.bedtime_section_appearance)))
        column.addView(buildAppearanceCard())
    }

    private fun buildToggle(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.bedtime_title),
                getString(R.string.bedtime_toggle_subtitle) + "\n" + getString(R.string.cap_needs_outside_session),
                CapabilityRegistry.isEnabled(this, Capabilities.BEDTIME_MODE)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.BEDTIME_MODE, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                } else {
                    AppBlockerService.startIfNeeded(this)
                }
                refresh()
            }
        )

        if (Bedtime.isActive(this)) {
            card.addView(FocusUi.spacer(this, 8))
            card.addView(
                FocusUi.pill(
                    this,
                    tokens,
                    getString(R.string.bedtime_running_pill, Bedtime.formatTime(this, Bedtime.endMinutes(this))),
                    tokens.accent
                )
            )
        }
    }

    private fun buildWindowCard(): View = card { card ->
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.common_starts_label),
                Bedtime.formatTime(this, Bedtime.startMinutes(this)),
                trailing = FocusUi.chevron(this, tokens)
            ) {
                FocusDialog.timePicker(this, getString(R.string.bedtime_starts_picker_title), Bedtime.startMinutes(this)) { minutes ->
                    if (!Bedtime.setWindow(this, minutes, Bedtime.endMinutes(this))) {
                        FocusDialog.toast(this, SessionLock.refusalMessage(this))
                    }
                    refresh()
                }
            }
        )
        card.addView(FocusUi.divider(this, tokens))
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.bedtime_lifts_label),
                Bedtime.formatTime(this, Bedtime.endMinutes(this)),
                trailing = FocusUi.chevron(this, tokens)
            ) {
                FocusDialog.timePicker(this, getString(R.string.bedtime_lifts_picker_title), Bedtime.endMinutes(this)) { minutes ->
                    if (!Bedtime.setWindow(this, Bedtime.startMinutes(this), minutes)) {
                        FocusDialog.toast(this, SessionLock.refusalMessage(this))
                    }
                    refresh()
                }
            }
        )
    }

    private fun buildCategoryCard(): View = card { card ->
        val blocked = Bedtime.blockedCategories(this)
        val everything = Bedtime.blocksEverything(this)

        card.addView(FocusUi.secondary(this, tokens, getString(R.string.bedtime_always_allowed_note)))
        card.addView(FocusUi.spacer(this, 8))

        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.bedtime_block_everything_title),
                getString(R.string.bedtime_block_everything_subtitle),
                everything
            ) { checked ->
                if (!Bedtime.setBlocksEverything(this, checked)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )

        // Categories are meaningless while everything is blocked; showing them
        // live and editable would suggest they still decide something.
        if (everything) return@card

        card.addView(FocusUi.divider(this, tokens))

        // Title + switch per category, not title + a full blurb repeated for
        // every one of them - the same density fix Rules and the capability
        // switchboard already got. The one ⓘ next to the group label opens
        // every blurb at once, readable in a sheet instead of taking a line
        // under each row whether you needed it or not.
        val header = FocusUi.row(this)
        val label = FocusUi.sectionLabel(this, tokens, getString(R.string.bedtime_section_pick_categories))
        (label.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = 0; bottomMargin = 0 }
        label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(label)
        header.addView(
            buildInfoGlyph {
                FocusDialog.custom(
                    this,
                    title = getString(R.string.bedtime_section_pick_categories),
                    subtitle = null,
                    confirmLabel = null,
                    cancelLabel = getString(R.string.common_close)
                ) { body, dialogTokens, _ ->
                    AppCategory.ruleTargets.forEachIndexed { index, category ->
                        body.addView(FocusUi.heading(this, dialogTokens, category.label(this)))
                        val blurb = FocusUi.secondary(this, dialogTokens, category.blurb(this))
                        blurb.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = FocusUi.dp(this@BedtimeActivity, 3) }
                        body.addView(blurb)
                        if (index < AppCategory.ruleTargets.size - 1) body.addView(FocusUi.spacer(this, 15))
                    }
                }
            }
        )
        card.addView(header)
        card.addView(FocusUi.spacer(this, 4))

        AppCategory.ruleTargets.forEach { category ->
            val control = FocusUi.switchControl(this, tokens, category in blocked) { value ->
                val next = if (value) blocked + category else blocked - category
                if (!Bedtime.setBlockedCategories(this, next)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
            card.addView(
                FocusUi.listRow(this, tokens, category.label(this), null, trailing = control) {
                    control.isChecked = !control.isChecked
                }
            )
        }
    }

    /**
     * Bedtime as a real lock rather than a polite one.
     *
     * The same two-step the schedule editor offers: "absolute" is the
     * Device-Owner pin, and "close FocusLock too" is the difference between a
     * phone that is only this app and a phone that is genuinely put away for
     * the night. See [Lockdown].
     */
    private fun buildStrengthCard(): View = card { card ->
        val overlay = Bedtime.isOverlay(this)
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.bedtime_overlay_toggle_title),
                getString(R.string.bedtime_overlay_toggle_subtitle),
                overlay
            ) { value ->
                if (!Bedtime.setOverlay(this, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                } else if (!value) {
                    // Bricking is meaningless without the pin under it.
                    Bedtime.setBricksApp(this, false)
                }
                refresh()
            }
        )

        if (!overlay) return@card

        card.addView(FocusUi.divider(this, tokens))
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.bedtime_brick_toggle_title),
                getString(R.string.bedtime_brick_toggle_subtitle),
                Bedtime.bricksApp(this)
            ) { value ->
                if (!Bedtime.setBricksApp(this, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )
    }

    private fun buildInfoGlyph(onClick: () -> Unit): View {
        val view = android.widget.TextView(this)
        view.text = "ⓘ"
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(13f))
        view.setTextColor(tokens.textMuted)
        view.isClickable = true
        view.isFocusable = true
        val pad = FocusUi.dp(this, 6)
        view.setPadding(pad, pad, pad, pad)
        view.setOnClickListener { onClick() }
        return view
    }

    private fun buildAppearanceCard(): View = card { card ->
        card.addView(
            FocusUi.sliderRow(
                this,
                tokens,
                getString(R.string.bedtime_dim_label),
                0,
                80,
                Bedtime.dimPercent(this),
                { if (it == 0) getString(R.string.bedtime_dim_not_at_all) else getString(R.string.bedtime_dim_percent, it) }
            ) { value ->
                Bedtime.setDimPercent(this, value)
                AppBlockerService.startIfNeeded(this)
            }
        )
        card.addView(
            FocusUi.smallButton(this, tokens, getString(R.string.bedtime_preview_dimming)) {
                AppBlockerService.startIfNeeded(this)
                refresh()
            }
        )
        card.addView(FocusUi.spacer(this, 8))
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.bedtime_quiet_theme_title),
                getString(R.string.bedtime_quiet_theme_subtitle),
                Bedtime.forcesDarkTheme(this)
            ) { value ->
                Bedtime.setForcesDarkTheme(this, value)
                refresh()
            }
        )
    }
}
