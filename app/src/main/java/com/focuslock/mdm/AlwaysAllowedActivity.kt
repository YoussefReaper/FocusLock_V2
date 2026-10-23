package com.focuslock.mdm

import android.view.View
import android.widget.LinearLayout

/**
 * The list nothing can override.
 *
 * This is the safety valve that makes every other rule acceptable to live with:
 * calls, maps, the clock and anything else the user names stay open through
 * kiosk sessions, schedule windows, bedtime and place rules alike. A blocker
 * that can cut off an ambulance call is a blocker nobody keeps installed.
 */
class AlwaysAllowedActivity : FocusScreenActivity() {

    override fun screenTitle(): String = getString(R.string.always_allowed_title)

    override fun screenSubtitle(): String = getString(R.string.always_allowed_subtitle)

    override fun buildContent(column: LinearLayout) {
        val enabled = CapabilityRegistry.isEnabled(this, Capabilities.ALWAYS_ALLOWED)

        column.addView(buildStateCard(enabled))

        // Always reachable, even with the list itself switched off: this is a
        // phone control, not an app exemption, and the reason it is here at all
        // is that a session can take the quick settings shade away. See
        // [ScreenBrightness].
        column.addView(sectionLabel(getString(R.string.always_allowed_section_phone_controls)))
        column.addView(buildBrightnessCard())

        if (!enabled) return

        column.addView(sectionLabel(getString(R.string.always_allowed_section_your_list)))
        column.addView(buildList())
        column.addView(sectionLabel(getString(R.string.always_allowed_section_suggestions)))
        column.addView(buildSuggestions())
    }

    /**
     * Brightness, in the app.
     *
     * Android's lock task mode has no feature flag for quick settings - it
     * removes the panel for the whole pinned session and there is no way to ask
     * for it back, which is why "Lock the status bar" appears not to work on
     * it. Rather than leave a person unable to dim their own screen at 1am, the
     * control lives here.
     */
    private fun buildBrightnessCard(): View = card { card ->
        if (!ScreenBrightness.canControl(this)) {
            card.addView(FocusUi.rowTitle(this, tokens, getString(R.string.brightness_title)))
            card.addView(FocusUi.spacer(this, 4))
            card.addView(FocusUi.secondary(this, tokens, getString(R.string.brightness_needs_permission)))
            card.addView(FocusUi.spacer(this, 12))
            card.addView(
                FocusUi.secondaryButton(this, tokens, getString(R.string.brightness_grant_button)) {
                    try {
                        startActivity(ScreenBrightness.permissionIntent(this))
                    } catch (_: Exception) {
                        FocusDialog.toast(this, getString(R.string.brightness_no_settings_page))
                    }
                }
            )
            return@card
        }

        val automatic = ScreenBrightness.isAutomatic(this)
        card.addView(
            FocusUi.sliderRow(
                this,
                tokens,
                getString(R.string.brightness_title),
                0,
                100,
                ScreenBrightness.percent(this) ?: 50,
                { getString(R.string.brightness_percent, it) }
            ) { value ->
                // Manual first: a level set while the sensor is in charge is
                // overwritten within the second, which looks like the slider
                // simply not working.
                if (ScreenBrightness.isAutomatic(this)) ScreenBrightness.setAutomatic(this, false)
                ScreenBrightness.setPercent(this, value)
            }
        )

        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.brightness_adaptive_title),
                getString(R.string.brightness_adaptive_subtitle),
                automatic
            ) { value ->
                if (!ScreenBrightness.setAutomatic(this, value)) {
                    FocusDialog.toast(this, getString(R.string.brightness_refused))
                }
                refresh()
            }
        )

        card.addView(FocusUi.caption(this, tokens, getString(R.string.brightness_why_here)))
    }

    private fun buildStateCard(enabled: Boolean): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.always_allowed_honour_title),
                getString(R.string.always_allowed_honour_subtitle),
                enabled
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.ALWAYS_ALLOWED, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                } else if (!value) {
                    Capabilities.spec(Capabilities.ALWAYS_ALLOWED)?.let { FocusDialog.weakenNotice(this, it) }
                }
                refresh()
            }
        )
    }

    private fun buildList(): View {
        val current = AppRules.alwaysAllowedRaw(this).toList().sortedBy { AppCatalog.label(this, it) }

        return card { card ->
            if (current.isEmpty()) {
                card.addView(FocusUi.emptyState(this, tokens, getString(R.string.always_allowed_empty)))
            } else {
                current.forEachIndexed { index, packageName ->
                    card.addView(
                        FocusUi.listRow(
                            this,
                            tokens,
                            AppCatalog.label(this, packageName),
                            packageName,
                            trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_remove)) {
                                if (!AppRules.setAlwaysAllowed(this, current - packageName)) {
                                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                                }
                                refresh()
                            },
                            leading = FocusUi.appIcon(this, tokens, packageName, 34)
                        )
                    )
                    if (index < current.size - 1) card.addView(FocusUi.divider(this, tokens))
                }
            }

            card.addView(FocusUi.spacer(this, 12))
            card.addView(
                FocusUi.primaryButton(this, tokens, getString(R.string.always_allowed_choose_apps)) {
                    pickApps(
                        title = getString(R.string.always_allowed_title),
                        subtitle = getString(R.string.always_allowed_pick_subtitle),
                        selected = AppRules.alwaysAllowedRaw(this),
                        includeSystem = true
                    ) { selected ->
                        // pickApps() already calls refresh() after this runs.
                        if (!AppRules.setAlwaysAllowed(this, selected)) {
                            FocusDialog.toast(this, SessionLock.refusalMessage(this))
                        }
                    }
                }
            )
        }
    }

    /**
     * Detected from the phone's own defaults rather than a hardcoded list, so
     * it proposes the dialler this person actually uses.
     */
    private fun buildSuggestions(): View {
        val current = AppRules.alwaysAllowedRaw(this)
        val suggestions = AppCatalog.detectEssentials(this)
            .filterNot { it in current }
            .filter { AppCatalog.isInstalled(this, it) }

        return card { card ->
            if (suggestions.isEmpty()) {
                card.addView(FocusUi.secondary(this, tokens, getString(R.string.always_allowed_covered)))
                return@card
            }

            card.addView(FocusUi.secondary(this, tokens, getString(R.string.always_allowed_suggestions_intro)))
            card.addView(FocusUi.spacer(this, 8))

            suggestions.forEach { packageName ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        AppCatalog.label(this, packageName),
                        packageName,
                        trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_add)) {
                            if (!AppRules.addAlwaysAllowed(this, packageName)) {
                                FocusDialog.toast(this, SessionLock.refusalMessage(this))
                            }
                            refresh()
                        },
                        leading = FocusUi.appIcon(this, tokens, packageName, 34)
                    )
                )
            }

            card.addView(FocusUi.spacer(this, 10))
            card.addView(
                FocusUi.secondaryButton(this, tokens, getString(R.string.always_allowed_add_all)) {
                    if (!AppRules.setAlwaysAllowed(this, current + suggestions)) {
                        FocusDialog.toast(this, SessionLock.refusalMessage(this))
                    }
                    refresh()
                }
            )
        }
    }
}
