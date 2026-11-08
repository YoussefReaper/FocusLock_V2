package com.focuslock.mdm

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The switchboard - shows the 10 switches most people use by default,
 * with a "Show all" button for Advanced, Experimental, and Hardening switches.
 */
class CapabilitiesActivity : FocusScreenActivity() {

    private val collapsed = HashMap<String, Boolean>()
    private var showAll = false

    override fun screenTitle(): String = getString(R.string.capabilities_title)

    override fun screenSubtitle(): String = getString(R.string.rules_capability_intro)

    override fun buildContent(column: LinearLayout) {
        if (!showAll) {
            column.addView(buildCoreCard())
            column.addView(FocusUi.spacer(this, 10))
            column.addView(
                FocusUi.secondaryButton(this, tokens, getString(R.string.capabilities_show_all_button, Capabilities.all.size)) {
                    showAll = true
                    refresh()
                }
            )
        } else {
            Capabilities.grouped().forEach { (group, specs) ->
                column.addView(buildGroup(group, specs))
            }
            column.addView(FocusUi.spacer(this, 8))
            column.addView(
                FocusUi.secondaryButton(this, tokens, getString(R.string.capabilities_show_fewer_button)) {
                    showAll = false
                    refresh()
                }
            )
        }
        column.addView(FocusUi.spacer(this, 8))
        column.addView(buildResetCard())
    }

    /** The 10 switches most people use, shown cleanly on first open. */
    private fun buildCoreCard(): View {
        val card = FocusUi.card(this, tokens)
        val specs = Capabilities.coreSpecs()
        specs.forEachIndexed { index, spec ->
            card.addView(buildCapabilityRow(spec))
            if (index < specs.size - 1) card.addView(FocusUi.divider(this, tokens))
        }
        return card
    }

    /** Groups start collapsed except the first, so the list reads as decisions rather than a wall. */
    private fun buildGroup(group: CapabilityGroup, specs: List<CapabilitySpec>): View {
        val card = FocusUi.card(this, tokens)
        val isCollapsed = collapsed[group.name] ?: (group != CapabilityGroup.MODES)
        val enabledCount = specs.count { CapabilityRegistry.isEnabled(this, it.id) }

        val header = FocusUi.row(this)
        header.isClickable = true
        header.isFocusable = true
        header.setOnClickListener {
            collapsed[group.name] = !isCollapsed
            refresh()
        }

        val titleColumn = FocusUi.column(this)
        titleColumn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val titleRow = FocusUi.row(this)
        titleRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        titleRow.addView(
            FocusUi.heading(this, tokens, group.label(this)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )
        titleRow.addView(FocusUi.spacerH(this, 6))
        titleRow.addView(buildInfoGlyph { showExplainSheet(group, specs) })
        titleColumn.addView(titleRow)
        titleColumn.addView(FocusUi.caption(this, tokens, group.blurb(this)))
        header.addView(titleColumn)

        header.addView(
            FocusUi.pill(
                this,
                tokens,
                enabledCount.toString() + "/" + specs.size,
                if (enabledCount > 0) tokens.accent else tokens.textMuted
            )
        )
        val marker = FocusUi.chevron(this, tokens)
        marker.text = if (isCollapsed) "›" else "⌄"
        marker.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = FocusUi.dp(this@CapabilitiesActivity, 10) }
        header.addView(marker)
        card.addView(header)

        if (isCollapsed) return card

        card.addView(FocusUi.spacer(this, 8))
        specs.forEachIndexed { index, spec ->
            card.addView(buildCapabilityRow(spec))
            if (index < specs.size - 1) card.addView(FocusUi.divider(this, tokens))
        }
        return card
    }

    private fun buildInfoGlyph(onClick: () -> Unit): View {
        val view = TextView(this)
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

    private fun showExplainSheet(group: CapabilityGroup, specs: List<CapabilitySpec>) {
        FocusDialog.custom(
            this,
            title = group.label(this),
            subtitle = group.blurb(this),
            confirmLabel = null,
            cancelLabel = getString(R.string.common_close)
        ) { body, dialogTokens, _ ->
            specs.forEachIndexed { index, spec ->
                body.addView(FocusUi.heading(this, dialogTokens, spec.label(this)))
                val blurb = FocusUi.secondary(this, dialogTokens, spec.blurb(this))
                blurb.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = FocusUi.dp(this@CapabilitiesActivity, 3) }
                body.addView(blurb)
                if (index < specs.size - 1) body.addView(FocusUi.spacer(this, 15))
            }
        }
    }

    private fun buildCapabilityRow(spec: CapabilitySpec): View {
        val enabled = CapabilityRegistry.isEnabled(this, spec.id)
        val blocker = permissionBlocker(spec)
        val frozen = !CapabilityRegistry.canToggle(this, spec.id, enabled)

        val control = FocusUi.switchControl(this, tokens, enabled) { value ->
            if (!CapabilityRegistry.setEnabled(this, spec.id, value)) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
                refresh()
                return@switchControl
            }
            if (value && permissionBlocker(spec) != null) {
                promptForPermission(spec)
            }
            refresh()
        }
        control.isEnabled = !frozen

        val trailing: View = if (enabled && blocker != null) {
            val wrap = FocusUi.row(this)
            wrap.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val chip = FocusUi.pill(this, tokens, getString(R.string.capabilities_needs_access_chip), tokens.warning)
            chip.isClickable = true
            chip.isFocusable = true
            chip.setOnClickListener { promptForPermission(spec) }
            wrap.addView(chip)
            wrap.addView(FocusUi.spacerH(this, 10))
            wrap.addView(control)
            wrap
        } else {
            control
        }

        val column = FocusUi.column(this)
        column.addView(
            FocusUi.listRow(this, tokens, spec.label(this), null, trailing = trailing) {
                if (frozen) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                } else {
                    control.isChecked = !control.isChecked
                }
            }
        )

        if (!enabled) {
            val weakenNote = spec.weakenNote(this)
            if (weakenNote != null) {
                val warning = FocusUi.caption(this, tokens, weakenNote)
                warning.setTextColor(tokens.warning)
                warning.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = FocusUi.dp(this@CapabilitiesActivity, 8) }
                column.addView(warning)
            }
        }

        if (frozen) {
            column.addView(FocusUi.caption(this, tokens, Copy.rulesFrozenHint(this)))
        }

        if (enabled && spec.detailScreen != null) {
            val link = FocusUi.smallButton(this, tokens, getString(R.string.rules_set_it_up)) {
                openDetail(spec.detailScreen)
            }
            link.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                FocusUi.dp(this, tokens.density.quickButtonHeightDp)
            ).apply { bottomMargin = FocusUi.dp(this@CapabilitiesActivity, 8) }
            column.addView(link)
        }

        return column
    }

    private fun permissionBlocker(spec: CapabilitySpec): String? = when {
        spec.needsUsageAccess && !SetupChecks.hasUsageAccess(this) ->
            getString(R.string.rules_perm_usage_access)
        spec.needsAccessibility && !SetupChecks.isContentGuardEnabled(this) ->
            getString(R.string.rules_perm_accessibility)
        spec.needsNotificationAccess && !SetupChecks.isNotificationAccessGranted(this) ->
            getString(R.string.rules_perm_notification_access)
        spec.needsDeviceOwner && !SetupChecks.isDeviceOwner(this) ->
            getString(R.string.rules_perm_device_owner)
        spec.needsLocation && !SetupChecks.hasLocationAccess(this) ->
            getString(R.string.rules_perm_location)
        else -> null
    }

    /**
     * Shows one plain sentence explaining why FocusLock needs the permission
     * on FocusLock's own screen before opening Android's system permission page.
     */
    private fun promptForPermission(spec: CapabilitySpec) {
        val whySentence = permissionBlocker(spec) ?: return
        FocusDialog.alert(
            this,
            title = spec.label(this),
            message = whySentence,
            confirmLabel = getString(R.string.dashboard_grant),
            cancelLabel = getString(R.string.common_not_now),
            onConfirm = {
                launchPermissionFor(spec)
            }
        )
    }

    private fun launchPermissionFor(spec: CapabilitySpec) {
        val intents = when {
            spec.needsUsageAccess && !SetupChecks.hasUsageAccess(this) ->
                PermissionIntents.usageAccess(this)
            spec.needsAccessibility && !SetupChecks.isContentGuardEnabled(this) ->
                PermissionIntents.accessibility(this)
            spec.needsNotificationAccess && !SetupChecks.isNotificationAccessGranted(this) ->
                PermissionIntents.notificationAccess(this)
            spec.needsDeviceOwner && !SetupChecks.isDeviceOwner(this) ->
                listOf(Intent(this, DeviceOwnerHelpActivity::class.java))
            spec.needsLocation && !SetupChecks.hasLocationAccess(this) -> {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQUEST_LOCATION
                )
                null
            }
            else -> null
        } ?: return

        LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 2 * 60 * 1000)
        if (!PermissionIntents.launchFirst(this, intents)) {
            FocusDialog.toast(this, getString(R.string.common_page_not_available))
        }
    }

    private fun openDetail(screen: String) {
        if (screen == Screens.TASKS) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TASKS)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            return
        }
        val intent = when (screen) {
            Screens.APP_RULES -> Intent(this, AppRulesActivity::class.java)
            Screens.WEB_RULES -> Intent(this, WebAllowlistEditorActivity::class.java)
            Screens.KEYWORDS -> Intent(this, KeywordGuardActivity::class.java)
            Screens.LIMITS -> Intent(this, AppLimitsActivity::class.java)
            Screens.SCHEDULES -> Intent(this, ScheduleActivity::class.java)
            Screens.BEDTIME -> Intent(this, BedtimeActivity::class.java)
            Screens.PLACES -> Intent(this, PlaceRulesActivity::class.java)
            Screens.RULES_LIST -> Intent(this, RuleEditorActivity::class.java)
            Screens.ALWAYS_ALLOWED -> Intent(this, AlwaysAllowedActivity::class.java)
            Screens.ANALYTICS -> Intent(this, AnalyticsActivity::class.java)
            else -> null
        } ?: return
        startActivity(intent)
    }

    private fun buildResetCard(): View = card { card ->
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.rules_reset_title),
                getString(R.string.rules_reset_subtitle),
                trailing = FocusUi.chevron(this, tokens)
            ) {
                FocusDialog.alert(
                    this,
                    title = getString(R.string.rules_reset_confirm_title),
                    message = getString(R.string.rules_reset_confirm_message),
                    confirmLabel = getString(R.string.rules_reset_confirm_button),
                    cancelLabel = getString(R.string.common_cancel),
                    onConfirm = {
                        if (!CapabilityRegistry.resetToDefaults(this)) {
                            FocusDialog.toast(this, SessionLock.refusalMessage(this))
                        }
                        refresh()
                    }
                )
            }
        )
    }

    companion object {
        private const val REQUEST_LOCATION = 4711
    }
}
