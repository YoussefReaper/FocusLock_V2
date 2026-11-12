package com.focuslock.mdm

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout

/**
 * The permission checklist.
 *
 * Two rules here. Each row says what the permission is *for* in the user's own
 * terms, not Android's — "lets FocusLock see which app is in front" rather than
 * "usage access". And nothing is demanded that the user's own capability
 * choices do not actually need, so a person running soft mode is never asked
 * for notification access.
 */
class SetupPermissionsActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_key

    private data class Requirement(
        val title: String,
        val why: String,
        val granted: Boolean,
        val required: Boolean,
        val open: () -> Unit
    )

    override fun screenTitle(): String = getString(R.string.perms_title)

    override fun screenSubtitle(): String =
        getString(R.string.perms_subtitle)

    override fun buildContent(column: LinearLayout) {
        val requirements = requirements()
        val outstanding = requirements.count { it.required && !it.granted }

        column.addView(buildSummary(outstanding, requirements.size))
        column.addView(sectionLabel(getString(R.string.perms_needed_title)))
        column.addView(buildList(requirements))
        restrictedSettingsHint(requirements)?.let { column.addView(it) }
        column.addView(sectionLabel(getString(R.string.perms_device_owner)))
        column.addView(buildDeviceOwnerCard())
    }

    private fun buildSummary(outstanding: Int, total: Int): View = card { card ->
        card.addView(
            FocusUi.heading(
                this,
                tokens,
                if (outstanding == 0) getString(R.string.perms_all_granted) else getString(R.string.perms_still_to_grant, outstanding)
            )
        )
        card.addView(FocusUi.spacer(this, 6))
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                if (outstanding == 0) {
                    getString(R.string.perms_nothing_else)
                } else {
                    getString(R.string.perms_of_total, total)
                }
            )
        )
    }

    private fun buildList(requirements: List<Requirement>): View = card { card ->
        requirements.forEachIndexed { index, requirement ->
            val trailing = if (requirement.granted) {
                FocusUi.pill(this, tokens, getString(R.string.common_granted), tokens.success)
            } else {
                FocusUi.smallButton(this, tokens, getString(R.string.dashboard_grant)) { requirement.open() }
            }
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    requirement.title,
                    requirement.why + (if (requirement.required) "" else getString(R.string.perms_not_needed_suffix)),
                    trailing = trailing
                ) { if (!requirement.granted) requirement.open() }
            )
            if (index < requirements.size - 1) card.addView(FocusUi.divider(this, tokens))
        }
    }

    /**
     * The "switch is greyed out" note, shown only while one of the two
     * permissions Android treats as a restricted setting is still missing.
     *
     * On Android 13 and later a sideloaded app's accessibility and
     * notification-access switches cannot be turned on until "Allow restricted
     * settings" is enabled from its App info page, behind the ⋮ menu. Nothing
     * on the page the Grant button opens says so; the switch just will not
     * move.
     */
    private fun restrictedSettingsHint(requirements: List<Requirement>): View? {
        if (!PermissionIntents.mayBeRestricted()) return null
        val restricted = setOf(
            getString(R.string.perms_accessibility_title),
            getString(R.string.perms_notification_title)
        )
        if (requirements.none { it.title in restricted && it.required && !it.granted }) return null
        return card { card ->
            card.addView(FocusUi.secondary(this, tokens, getString(R.string.permission_recovery_restricted_hint)))
            card.addView(FocusUi.spacer(this, 12))
            card.addView(
                FocusUi.secondaryButton(this, tokens, getString(R.string.permission_recovery_open_app_info)) {
                    open(PermissionIntents.appInfo(this))
                }
            )
        }
    }

    /** True when some capability the user has switched on actually needs this permission. */
    private fun capabilityNeeds(predicate: (CapabilitySpec) -> Boolean): Boolean =
        Capabilities.all.any { predicate(it) && CapabilityRegistry.isEnabled(this, it.id) }

    private fun requirements(): List<Requirement> {
        return listOf(
            Requirement(
                getString(R.string.perms_usage_title),
                getString(R.string.perms_usage_detail),
                SetupChecks.hasUsageAccess(this),
                capabilityNeeds { it.needsUsageAccess },
            ) { open(PermissionIntents.usageAccess(this)) },

            Requirement(
                getString(R.string.perms_accessibility_title),
                getString(R.string.perms_accessibility_detail),
                SetupChecks.isContentGuardEnabled(this),
                capabilityNeeds { it.needsAccessibility },
            ) { open(PermissionIntents.accessibility(this)) },

            Requirement(
                getString(R.string.perms_notification_title),
                getString(R.string.perms_notification_detail),
                SetupChecks.isNotificationAccessGranted(this),
                capabilityNeeds { it.needsNotificationAccess },
            ) { open(PermissionIntents.notificationAccess(this)) },

            Requirement(
                getString(R.string.perms_overlay_title),
                getString(R.string.perms_overlay_detail),
                SetupChecks.canDrawOverlays(this),
                true,
            ) { open(PermissionIntents.overlay(this)) },

            Requirement(
                getString(R.string.perms_post_notifications_title),
                getString(R.string.perms_post_notifications_detail),
                SetupChecks.canPostNotifications(this),
                true,
            ) { open(PermissionIntents.notifications(this)) },

            Requirement(
                getString(R.string.perms_battery_title),
                getString(R.string.perms_battery_detail),
                SetupChecks.isIgnoringBatteryOptimizations(this),
                true,
            ) {
                open(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:" + packageName)
                    }
                )
            },

            Requirement(
                getString(R.string.perms_location_title),
                getString(R.string.perms_location_detail),
                SetupChecks.hasLocationAccess(this),
                capabilityNeeds { it.needsLocation },
            ) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQUEST_LOCATION
                )
            },

            Requirement(
                getString(R.string.perms_device_admin_title),
                getString(R.string.perms_device_admin_detail),
                SetupChecks.isDeviceAdminActive(this),
                capabilityNeeds { it.needsDeviceOwner },
            ) {
                open(
                    Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(
                            android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            android.content.ComponentName(this@SetupPermissionsActivity, AdminReceiver::class.java)
                        )
                        putExtra(
                            android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            getString(R.string.perms_kiosk_needs_admin)
                        )
                    }
                )
            }
        )
    }

    private fun buildDeviceOwnerCard(): View = card { card ->
        val isOwner = SetupChecks.isDeviceOwner(this)

        // The demo has no device-admin receiver at all, so there is nothing here
        // to set up and no point pretending otherwise. Say so plainly instead of
        // showing a button that leads nowhere.
        if (!BuildConfig.ENFORCEMENT) {
            card.addView(FocusUi.heading(this, tokens, getString(R.string.perms_demo_title)))
            card.addView(FocusUi.spacer(this, 6))
            card.addView(
                FocusUi.secondary(
                    this,
                    tokens,
                    getString(R.string.perms_demo_body)
                )
            )
            card.addView(FocusUi.spacer(this, 14))
            card.addView(
                FocusUi.secondaryButton(this, tokens, getString(R.string.perms_full_adds)) {
                    startActivity(Intent(this, DeviceOwnerHelpActivity::class.java))
                }
            )
            return@card
        }

        card.addView(
            FocusUi.heading(this, tokens, if (isOwner) getString(R.string.perms_owner_active) else getString(R.string.perms_owner_not_set))
        )
        card.addView(FocusUi.spacer(this, 6))
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                if (isOwner) {
                    getString(R.string.perms_owner_active_detail)
                } else {
                    getString(R.string.perms_owner_inactive_detail)
                }
            )
        )
        card.addView(FocusUi.spacer(this, 14))
        card.addView(
            FocusUi.primaryButton(this, tokens, if (isOwner) getString(R.string.perms_see_allows) else getString(R.string.perms_show_how)) {
                startActivity(Intent(this, DeviceOwnerHelpActivity::class.java))
            }
        )
    }

    private fun open(intent: Intent) = open(listOf(intent))

    private fun open(intents: List<Intent>) {
        LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 3 * 60 * 1000)
        if (!PermissionIntents.launchFirst(this, intents)) {
            FocusDialog.toast(this, getString(R.string.common_page_not_available))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    companion object {
        private const val REQUEST_LOCATION = 4713
    }
}