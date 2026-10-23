package com.focuslock.mdm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * How to make FocusLock Device Owner, and an honest account of what that buys.
 *
 * The blueprint is blunt about this and so is this screen: the sideloaded app
 * alone cannot close Safe Mode. Only a Device Owner can, and Device Owner is
 * set from a computer. Claiming otherwise would be the most consequential lie
 * the app could tell.
 */
class DeviceOwnerHelpActivity : FocusScreenActivity() {

    // Lazy, not a plain field initializer: this runs in the constructor, before
    // Activity.attachBaseContext() gives getString() a Context to resolve
    // against, and would throw at construction time otherwise.
    private val command by lazy { getString(R.string.device_owner_help_command) }

    override fun screenTitle(): String = getString(R.string.device_owner_help_title)

    override fun screenSubtitle(): String = getString(R.string.device_owner_help_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildStatusCard())
        column.addView(sectionLabel(getString(R.string.device_owner_help_section_what_changes)))
        column.addView(buildEffectCard())
        column.addView(sectionLabel(getString(R.string.device_owner_help_section_setup)))
        column.addView(buildStepsCard())
        column.addView(sectionLabel(getString(R.string.device_owner_help_section_qr)))
        column.addView(buildQrCard())
        column.addView(sectionLabel(getString(R.string.device_owner_help_section_cannot_do)))
        column.addView(buildHonestyCard())
    }

    private fun buildStatusCard(): View = card { card ->
        val isOwner = SetupChecks.isDeviceOwner(this)
        val header = FocusUi.row(this)
        val title = FocusUi.heading(
            this,
            tokens,
            if (isOwner) getString(R.string.device_owner_help_status_active) else getString(R.string.device_owner_help_status_not_set)
        )
        title.layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(title)
        header.addView(
            FocusUi.pill(
                this,
                tokens,
                if (isOwner) getString(R.string.device_owner_help_pill_device_owner) else getString(R.string.device_owner_help_pill_sideloaded),
                if (isOwner) tokens.success else tokens.warning
            )
        )
        card.addView(header)
        card.addView(FocusUi.spacer(this, 8))
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                if (isOwner) {
                    getString(R.string.device_owner_help_status_detail_owner)
                } else {
                    getString(R.string.device_owner_help_status_detail_not_owner)
                }
            )
        )
    }

    private fun buildEffectCard(): View = card { card ->
        listOf(
            getString(R.string.device_owner_help_effect_1_title) to
                getString(R.string.device_owner_help_effect_1_detail),
            getString(R.string.device_owner_help_effect_2_title) to
                getString(R.string.device_owner_help_effect_2_detail),
            getString(R.string.device_owner_help_effect_3_title) to
                getString(R.string.device_owner_help_effect_3_detail),
            getString(R.string.device_owner_help_effect_4_title) to
                getString(R.string.device_owner_help_effect_4_detail),
            getString(R.string.device_owner_help_effect_5_title) to
                getString(R.string.device_owner_help_effect_5_detail),
            getString(R.string.device_owner_help_effect_6_title) to
                getString(R.string.device_owner_help_effect_6_detail)
        ).forEach { pair ->
            card.addView(FocusUi.listRow(this, tokens, pair.first, pair.second))
        }
        card.addView(FocusUi.spacer(this, 8))
        card.addView(
            FocusUi.caption(
                this,
                tokens,
                getString(R.string.device_owner_help_effect_note)
            )
        )
    }

    private fun buildStepsCard(): View = card { card ->
        val steps = listOf(
            getString(R.string.device_owner_help_step_1),
            getString(R.string.device_owner_help_step_2),
            getString(R.string.device_owner_help_step_3),
            getString(R.string.device_owner_help_step_4),
            getString(R.string.device_owner_help_step_5),
            getString(R.string.device_owner_help_step_6),
            getString(R.string.device_owner_help_step_7)
        )

        steps.forEachIndexed { index, step ->
            val row = FocusUi.row(this)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = FocusUi.dp(this@DeviceOwnerHelpActivity, 12) }

            val number = FocusUi.pill(this, tokens, (index + 1).toString(), tokens.accent)
            row.addView(number)
            row.addView(FocusUi.spacerH(this, 12))

            val text = FocusUi.secondary(this, tokens, step)
            text.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            row.addView(text)
            card.addView(row)
        }

        card.addView(buildCommandBlock())
    }

    private fun buildCommandBlock(): View {
        val block = FocusUi.body(this, tokens, command)
        block.typeface = android.graphics.Typeface.MONOSPACE
        block.setTextIsSelectable(true)
        val padding = FocusUi.dp(this, 14)
        block.setPadding(padding, padding, padding, padding)
        block.background = FocusUi.roundedShape(
            this,
            tokens.input,
            minOf(tokens.radiusDp, 14),
            tokens.divider
        )

        val container = FocusUi.column(this)
        container.addView(block)
        container.addView(FocusUi.spacer(this, 10))
        container.addView(
            FocusUi.secondaryButton(this, tokens, getString(R.string.device_owner_help_copy_command)) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("FocusLock", command))
                FocusDialog.toast(this, getString(R.string.device_owner_help_copied_toast))
            }
        )
        return container
    }

    private fun buildQrCard(): View = card { card ->
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.device_owner_help_qr_detail)
            )
        )
    }

    private fun buildHonestyCard(): View = card { card ->
        listOf(
            getString(R.string.device_owner_help_honesty_1_title) to
                getString(R.string.device_owner_help_honesty_1_detail),
            getString(R.string.device_owner_help_honesty_2_title) to
                getString(R.string.device_owner_help_honesty_2_detail),
            getString(R.string.device_owner_help_honesty_3_title) to
                getString(R.string.device_owner_help_honesty_3_detail)
        ).forEach { pair ->
            card.addView(FocusUi.listRow(this, tokens, pair.first, pair.second))
        }
    }
}
