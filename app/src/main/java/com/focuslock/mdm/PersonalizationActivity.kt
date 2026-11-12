package com.focuslock.mdm

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The full appearance controls.
 *
 * A phone someone is asking to live inside for weeks should look like something
 * they chose. Every control here writes a token that the whole app reads, so
 * there is no screen, dialog or block message that ignores it — which was the
 * single biggest visual problem with the old build.
 */
class PersonalizationActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_moonpine

    override fun screenTitle(): String = getString(R.string.appearance_title)

    override fun screenSubtitle(): String =
        getString(R.string.personalization_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildPreview())

        // Moon is the only look: no theme list and no wallpapers (the paintings are the background).
        column.addView(sectionLabel(getString(R.string.personalization_type_and_spacing)))
        column.addView(buildTypeCard())

        column.addView(sectionLabel(getString(R.string.appearance_section_accessibility)))
        column.addView(buildAccessibilityCard())

        column.addView(sectionLabel(getString(R.string.personalization_dashboard_shows)))
        column.addView(buildSectionsCard())

        column.addView(FocusUi.spacer(this, 8))
        column.addView(
            FocusUi.dangerButton(this, tokens, getString(R.string.personalization_reset)) {
                FocusDialog.alert(
                    this,
                    getString(R.string.personalization_reset_title),
                    getString(R.string.personalization_reset_message),
                    confirmLabel = getString(R.string.common_reset),
                    cancelLabel = getString(R.string.common_cancel),
                    onConfirm = {
                        UiPrefs.resetToDefaults(this)
                        refresh()
                    }
                )
            }
        )
    }

    /** A live sample of the real components, so nothing has to be imagined. */
    private fun buildPreview(): View = card { card ->
        card.addView(FocusUi.caption(this, tokens, getString(R.string.appearance_preview)))
        card.addView(FocusUi.spacer(this, 10))
        card.addView(FocusUi.title(this, tokens, getString(R.string.personalization_preview_greeting)))
        card.addView(FocusUi.spacer(this, 4))
        card.addView(FocusUi.secondary(this, tokens, getString(R.string.personalization_preview_streak)))
        card.addView(FocusUi.spacer(this, 12))

        card.addView(
            FocusUi.tileRow(
                this,
                listOf(
                    FocusUi.statTile(this, tokens, "2h 14m", getString(R.string.personalization_preview_screen_time)),
                    FocusUi.statTile(this, tokens, "37", getString(R.string.appearance_sample_opens))
                )
            )
        )

        card.addView(FocusUi.spacer(this, 10))
        card.addView(FocusUi.primaryButton(this, tokens, getString(R.string.personalization_preview_primary)) { })
        card.addView(FocusUi.spacer(this, 8))
        card.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.personalization_preview_secondary)) { })
    }

    private fun buildThemeCard(): View = card { card ->
        val current = UiPrefs.getTheme(this).id
        UiPrefs.themes.forEach { theme ->
            val swatchRow = FocusUi.row(this)
            swatchRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            listOf(theme.background, theme.card, theme.accent, theme.textPrimary).forEach { color ->
                val dot = View(this)
                val size = FocusUi.dp(this, 16)
                dot.background = FocusUi.roundedShape(this, color, 8, tokens.divider)
                dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = FocusUi.dp(this@PersonalizationActivity, 4)
                }
                swatchRow.addView(dot)
            }

            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    theme.label(this),
                    getString(if (theme.isLight) R.string.common_light else R.string.common_dark),
                    trailing = swatchRow,
                    leading = selectionMark(theme.id == current)
                ) {
                    UiPrefs.setThemeId(this, theme.id)
                    refresh()
                }
            )
        }

        card.addView(FocusUi.spacer(this, 10))
        card.addView(FocusUi.caption(this, tokens, getString(R.string.appearance_accent)))
        card.addView(buildAccentStrip())
    }

    private fun selectionMark(selected: Boolean): View {
        val dot = View(this)
        val size = FocusUi.dp(this, 10)
        dot.background = FocusUi.roundedShape(
            this,
            if (selected) tokens.accent else android.graphics.Color.TRANSPARENT,
            5,
            if (selected) null else tokens.divider
        )
        dot.layoutParams = LinearLayout.LayoutParams(size, size)
        return dot
    }

    private fun buildAccentStrip(): View {
        val strip = FocusUi.row(this)
        strip.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val current = UiPrefs.getAccent(this).id
        UiPrefs.accents.forEach { accent ->
            val swatch = View(this)
            val size = FocusUi.dp(this, 36)
            swatch.background = FocusUi.roundedShape(
                this,
                accent.color,
                18,
                if (accent.id == current) tokens.textPrimary else null,
                2
            )
            swatch.contentDescription = accent.label(this)
            swatch.isClickable = true
            swatch.isFocusable = true
            swatch.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = FocusUi.dp(this@PersonalizationActivity, 10)
            }
            swatch.setOnClickListener {
                UiPrefs.setAccentId(this, accent.id)
                refresh()
            }
            strip.addView(swatch)
        }
        return FocusUi.horizontalScroll(this, strip)
    }

    private fun buildBackgroundCard(): View = card { card ->
        card.addView(FocusUi.caption(this, tokens, getString(R.string.appearance_wallpaper)))
        card.addView(
            FocusUi.chipStrip(
                this,
                tokens,
                UiPrefs.wallpapers.map { it.label(this) },
                UiPrefs.wallpapers.indexOfFirst { it.id == UiPrefs.getWallpaper(this).id }
            ) { index ->
                UiPrefs.setWallpaperId(this, UiPrefs.wallpapers[index].id)
                refresh()
            }
        )

        card.addView(FocusUi.spacer(this, 12))
        card.addView(FocusUi.caption(this, tokens, getString(R.string.personalization_flat_colour)))
        card.addView(
            FocusUi.chipStrip(
                this,
                tokens,
                UiPrefs.backgrounds.map { it.label(this) },
                UiPrefs.backgrounds.indexOfFirst { it.id == UiPrefs.getBackground(this).id }
            ) { index ->
                UiPrefs.setBackgroundId(this, UiPrefs.backgrounds[index].id)
                refresh()
            }
        )

        card.addView(FocusUi.spacer(this, 8))
        card.addView(
            FocusUi.caption(
                this,
                tokens,
                getString(R.string.personalization_wallpaper_note)
            )
        )
    }

    private fun buildTypeCard(): View = card { card ->
        card.addView(FocusUi.caption(this, tokens, getString(R.string.appearance_typeface)))
        card.addView(
            FocusUi.chipStrip(
                this,
                tokens,
                UiPrefs.fonts.map { it.label(this) },
                UiPrefs.fonts.indexOfFirst { it.id == UiPrefs.getFont(this).id }
            ) { index ->
                UiPrefs.setFontId(this, UiPrefs.fonts[index].id)
                refresh()
            }
        )

        card.addView(FocusUi.spacer(this, 12))
        card.addView(FocusUi.caption(this, tokens, getString(R.string.appearance_spacing)))
        card.addView(
            FocusUi.chipStrip(
                this,
                tokens,
                UiPrefs.densities.map { it.label(this) },
                UiPrefs.densities.indexOfFirst { it.id == UiPrefs.getDensity(this).id }
            ) { index ->
                UiPrefs.setDensityId(this, UiPrefs.densities[index].id)
                refresh()
            }
        )

        card.addView(
            FocusUi.sliderRow(
                this,
                tokens,
                getString(R.string.personalization_text_size),
                80,
                140,
                (UiPrefs.getTextScale(this) * 100).toInt(),
                { it.toString() + "%" }
            ) { value -> UiPrefs.setTextScale(this, value / 100f) }
        )
        card.addView(
            FocusUi.sliderRow(
                this,
                tokens,
                getString(R.string.personalization_corner_rounding),
                0,
                32,
                UiPrefs.getCardRadiusDp(this),
                { it.toString() + "dp" }
            ) { value -> UiPrefs.setCardRadiusDp(this, value) }
        )
        card.addView(FocusUi.smallButton(this, tokens, getString(R.string.common_apply)) { refresh() })
    }

    private fun buildAccessibilityCard(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.personalization_reduce_motion),
                getString(R.string.personalization_reduce_motion_subtitle),
                UiPrefs.reducedMotion(this)
            ) { value ->
                UiPrefs.setReducedMotion(this, value)
                refresh()
            }
        )
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.personalization_high_contrast),
                getString(R.string.personalization_high_contrast_subtitle),
                UiPrefs.highContrast(this)
            ) { value ->
                UiPrefs.setHighContrast(this, value)
                refresh()
            }
        )
        card.addView(FocusUi.spacer(this, 6))
        card.addView(
            FocusUi.caption(
                this,
                tokens,
                getString(R.string.personalization_high_contrast_note)
            )
        )
    }

    private fun buildSectionsCard(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(this, tokens, getString(R.string.personalization_session_controls), null, UiPrefs.showKiosk(this)) { value ->
                UiPrefs.setShowKiosk(this, value)
            }
        )
        card.addView(
            FocusUi.toggleRow(this, tokens, getString(R.string.personalization_quick_settings_row), null, UiPrefs.showQuickSettings(this)) { value ->
                UiPrefs.setShowQuickSettings(this, value)
            }
        )
        card.addView(
            FocusUi.toggleRow(this, tokens, getString(R.string.personalization_today_numbers), null, UiPrefs.showStats(this)) { value ->
                UiPrefs.setShowStats(this, value)
            }
        )
        card.addView(
            FocusUi.toggleRow(this, tokens, getString(R.string.personalization_app_grid), null, UiPrefs.showAllowedApps(this)) { value ->
                UiPrefs.setShowAllowedApps(this, value)
            }
        )
        card.addView(
            FocusUi.toggleRow(this, tokens, getString(R.string.personalization_safe_browser_card), null, UiPrefs.showWebButton(this)) { value ->
                UiPrefs.setShowWebButton(this, value)
            }
        )
        card.addView(
            FocusUi.toggleRow(this, tokens, getString(R.string.personalization_schedule_shortcuts), null, UiPrefs.showSchedule(this)) { value ->
                UiPrefs.setShowSchedule(this, value)
            }
        )
        card.addView(FocusUi.divider(this, tokens))
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.cap_self_compassion_copy_label),
                getString(R.string.cap_self_compassion_copy_blurb),
                CapabilityRegistry.isEnabled(this, Capabilities.SELF_COMPASSION_COPY)
            ) { value ->
                CapabilityRegistry.setEnabled(this, Capabilities.SELF_COMPASSION_COPY, value)
                refresh()
            }
        )
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.cap_streaks_label),
                getString(R.string.cap_streaks_blurb),
                CapabilityRegistry.isEnabled(this, Capabilities.STREAKS)
            ) { value ->
                CapabilityRegistry.setEnabled(this, Capabilities.STREAKS, value)
                refresh()
            }
        )
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.cap_replacement_suggestions_label),
                getString(R.string.cap_replacement_suggestions_blurb),
                CapabilityRegistry.isEnabled(this, Capabilities.REPLACEMENT_SUGGESTIONS)
            ) { value ->
                CapabilityRegistry.setEnabled(this, Capabilities.REPLACEMENT_SUGGESTIONS, value)
                refresh()
            }
        )

        val note = FocusUi.caption(
            this,
            tokens,
            getString(R.string.personalization_hide_note)
        )
        note.gravity = Gravity.START
        card.addView(FocusUi.spacer(this, 8))
        card.addView(note)
    }
}