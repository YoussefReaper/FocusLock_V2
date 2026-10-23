package com.focuslock.mdm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * What the modes actually do, and how to disagree with them.
 *
 * This screen exists because the app changed shape: a mode used to be a cage,
 * and is now a template you can edit. That is a better deal, but only if the
 * person can find out how it works — including the parts that are unpleasant
 * to write down, like what happens when you lock yourself into Kiosk and mean
 * it. Bullets, not paragraphs: this gets read by someone who is mid-problem.
 */
class AdvancedHelpActivity : FocusScreenActivity() {

    // Lazy, not a plain field initializer: this runs in the constructor, before
    // Activity.attachBaseContext() gives getString() a Context to resolve
    // against, and would throw at construction time otherwise.
    private val stopCommand by lazy { getString(R.string.advanced_help_stop_command) }

    override fun screenTitle(): String = getString(R.string.advanced_help_title)

    override fun screenSubtitle(): String = getString(R.string.advanced_help_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildIdeaCard())
        column.addView(sectionLabel(getString(R.string.advanced_help_section_modes)))
        FocusMode.values().forEach { column.addView(buildModeCard(it)) }
        column.addView(sectionLabel(getString(R.string.advanced_help_section_ending)))
        column.addView(buildEndEarlyCard())
        column.addView(sectionLabel(getString(R.string.advanced_help_section_breaks)))
        column.addView(buildBreakCard())
        column.addView(sectionLabel(getString(R.string.advanced_help_section_escape)))
        column.addView(buildEscapeCard())
    }

    private fun buildIdeaCard(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, getString(R.string.advanced_help_idea_heading)))
        card.addView(FocusUi.spacer(this, 8))
        card.addView(
            FocusUi.body(
                this,
                tokens,
                getString(R.string.advanced_help_idea_body)
            )
        )
        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.advanced_help_idea_secondary)
            )
        )
    }

    private fun buildModeCard(mode: FocusMode): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, mode.label(this)))
        card.addView(FocusUi.spacer(this, 6))
        card.addView(FocusUi.secondary(this, tokens, mode.oneLiner(this)))
        card.addView(FocusUi.spacer(this, 8))
        val preset = FocusUi.caption(this, tokens, mode.presetSummary(this))
        preset.setTextColor(tokens.accent)
        card.addView(preset)

        if (mode.isHard) {
            card.addView(FocusUi.spacer(this, 8))
            card.addView(
                FocusUi.caption(
                    this,
                    tokens,
                    getString(R.string.advanced_help_mode_kiosk_note)
                )
            )
        }
    }

    private fun buildEndEarlyCard(): View = card { card ->
        card.addView(
            FocusUi.body(
                this,
                tokens,
                getString(R.string.advanced_help_end_early_intro)
            )
        )
        card.addView(FocusUi.spacer(this, 12))

        bullet(card, getString(R.string.advanced_help_end_early_on_title), getString(R.string.advanced_help_end_early_on_body))
        bullet(card, getString(R.string.advanced_help_end_early_off_title), getString(R.string.advanced_help_end_early_off_body))
        bullet(
            card,
            getString(R.string.advanced_help_end_early_kiosk_title),
            getString(R.string.advanced_help_end_early_kiosk_body)
        )

        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.caption(
                this,
                tokens,
                getString(R.string.advanced_help_end_early_footer)
            )
        )
    }

    private fun buildBreakCard(): View = card { card ->
        card.addView(
            FocusUi.body(
                this,
                tokens,
                getString(R.string.advanced_help_break_intro)
            )
        )
        card.addView(FocusUi.spacer(this, 12))
        bullet(card, getString(R.string.advanced_help_break_why_title), getString(R.string.advanced_help_break_why_body))
        bullet(card, getString(R.string.advanced_help_break_not_failure_title), getString(R.string.advanced_help_break_not_failure_body))
        bullet(card, getString(R.string.advanced_help_break_numbers_title), getString(R.string.advanced_help_break_numbers_body))
    }

    private fun buildEscapeCard(): View = card { card ->
        card.addView(
            FocusUi.body(
                this,
                tokens,
                getString(R.string.advanced_help_escape_intro)
            )
        )
        card.addView(FocusUi.spacer(this, 12))

        bullet(card, getString(R.string.advanced_help_escape_wait_title), getString(R.string.advanced_help_escape_wait_body))
        bullet(card, getString(R.string.advanced_help_escape_switch_title), getString(R.string.advanced_help_escape_switch_body))
        bullet(card, getString(R.string.advanced_help_escape_computer_title), getString(R.string.advanced_help_escape_computer_body))

        card.addView(FocusUi.spacer(this, 10))
        card.addView(commandBlock(stopCommand))
        card.addView(FocusUi.spacer(this, 12))

        bullet(
            card,
            getString(R.string.advanced_help_escape_reset_title),
            getString(R.string.advanced_help_escape_reset_body)
        )
    }

    private fun bullet(card: LinearLayout, title: String, body: String) {
        val heading = FocusUi.body(this, tokens, title)
        heading.setTextColor(tokens.textPrimary)
        card.addView(heading)
        card.addView(FocusUi.secondary(this, tokens, body))
        card.addView(FocusUi.spacer(this, 12))
    }

    private fun commandBlock(command: String): View {
        val holder = FocusUi.column(this, 0)
        val text = FocusUi.body(this, tokens, command)
        text.setTextColor(tokens.accent)
        text.background = FocusUi.roundedShape(this, tokens.input, tokens.radiusDp, tokens.divider, 1)
        val pad = FocusUi.dp(this, 12)
        text.setPadding(pad, pad, pad, pad)
        holder.addView(text)
        holder.addView(FocusUi.spacer(this, 8))
        holder.addView(
            FocusUi.secondaryButton(this, tokens, getString(R.string.advanced_help_copy_command)) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("FocusLock", command))
                FocusDialog.toast(this, getString(R.string.advanced_help_copied_toast))
            }
        )
        return holder
    }
}
