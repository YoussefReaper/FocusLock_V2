package com.focuslock.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The You tab: the app itself, and the way out.
 *
 * Personalisation lives here rather than buried three levels down, because the
 * look of a tool you are asking to live inside for ninety days is not a footnote.
 * So does the release path: a factory reset is a legitimate decision and hiding
 * it would be its own kind of dishonesty.
 */
class YouTab(activity: MainActivity, tokens: UiPrefs.Tokens) : FocusTab(activity, tokens) {

    private lateinit var container: LinearLayout

    override fun build(): View {
        container = FocusUi.column(activity, tokens.density.contentPaddingDp)
        return hostScroll(FocusUi.scroll(activity, container), container)
    }

    override fun onShow() {
        render()
    }

    private fun render(): Unit = redraw {
        val added = ArrayList<View>()

        fun add(view: View) {
            container.addView(view)
            added.add(view)
        }

        add(FocusUi.pageHeader(activity, tokens, activity.getString(R.string.tab_you), activity.getString(R.string.youtab_subtitle)))

        add(buildProgressCard())

        add(FocusUi.spacer(activity, 12))
        add(buildProCard())

        if (UiPrefs.showStats(activity) && UsageAnalytics.isEnabled(activity)) {
            add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.youtab_section_today)))
            add(buildTodayStats())
        }

        add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.youtab_make_it_yours)))
        add(buildAppearanceCard())

        add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.youtab_setup_and_help)))
        add(buildHelpCard())

        add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.youtab_section_release)))
        add(buildReleaseCard())

        add(FocusUi.spacer(activity, 8))
        add(buildAbout())

        Motion.stagger(added, tokens)
    }

    // ── FocusLock Pro status & unlock ─────────────────────────────

    private fun buildProCard(): View {
        // Quiet Pine: one row, not three stacked buttons. The Pro sheet it opens
        // already holds Buy and "I already have a code", so the choice is made
        // there, once, with the price and the list in front of the person.
        val pro = DemoMode.isPro(activity)
        val card = FocusUi.card(activity, tokens)
        card.setPadding(0, 0, 0, 0)
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(if (pro) R.string.pro_card_title_unlocked else R.string.pro_card_title_free),
                activity.getString(if (pro) R.string.pro_card_subtitle_unlocked else R.string.pro_card_subtitle_free),
                trailing = if (pro) FocusUi.pill(activity, tokens, activity.getString(R.string.pro_badge), tokens.success)
                else FocusUi.chevron(activity, tokens),
                leading = FocusUi.iconTile(
                    activity,
                    tokens,
                    R.drawable.ic_glyph_kiosk,
                    if (pro) FocusUi.IconTone.ACCENT else FocusUi.IconTone.SOLID
                )
            ) {
                if (!pro) DemoMode.showUpgradeDialog(activity) { render() }
            }
        )
        return card
    }

    // ── Progress ──────────────────────────────────────────────────

    private fun buildProgressCard(): View {
        // A labelled row of tiles, the same shape as "Today" beneath it. It
        // used to be tiles inside a card: two nested outlines for one row of
        // numbers, and the only block on the screen built that way.
        val card = FocusUi.column(activity)
        card.addView(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.youtab_so_far)))

        val tiles = ArrayList<View>()
        tiles.add(
            FocusUi.statTile(
                activity,
                tokens,
                SessionManager.totalSessions(activity).toString(),
                activity.getString(R.string.youtab_sessions_finished)
            )
        )
        tiles.add(
            FocusUi.statTile(
                activity,
                tokens,
                UsageAnalytics.formatDuration(SessionManager.totalFocusMs(activity)),
                activity.getString(R.string.youtab_time_held)
            )
        )
        if (Streaks.isEnabled(activity)) {
            tiles.add(
                FocusUi.statTile(activity, tokens, Streaks.best(activity).toString(), activity.getString(R.string.youtab_best_run))
            )
        }
        card.addView(FocusUi.tileRow(activity, tiles))

        if (Streaks.isEnabled(activity) && Streaks.isPaused(activity)) {
            card.addView(FocusUi.spacer(activity, 10))
            card.addView(FocusUi.secondary(activity, tokens, Copy.relapseNote(activity)))
        }

        // "Share my week": one text message of the numbers above, built on this
        // phone and sent only when the person picks an app. It used to be a
        // "Study friend" switch promising a live shared status it never had.
        card.addView(FocusUi.spacer(activity, 12))
        card.addView(
            FocusUi.secondaryButton(activity, tokens, activity.getString(R.string.youtab_share_button)) { shareSummary() }
        )
        return card
    }

    /**
     * Moved here from the Focus tab (design doc, "one shared line ... today's
     * stats move to Your time - they are not a decision"). activity.getString(R.string.youtab_so_far) above is
     * the lifetime total; this is just today, which is what most people
     * actually glance at.
     */
    private fun buildTodayStats(): View {
        val report = UsageAnalytics.today(activity)
        val row = FocusUi.tileRow(
            activity,
            listOf(
                FocusUi.statTile(activity, tokens, UsageAnalytics.formatDuration(report.totalMs), activity.getString(R.string.youtab_screen_time)),
                FocusUi.statTile(activity, tokens, report.opens.toString(), activity.getString(R.string.youtab_app_opens)),
                FocusUi.statTile(
                    activity,
                    tokens,
                    AppRules.blockedPackages(activity).size.toString(),
                    activity.getString(R.string.youtab_apps_blocked)
                )
            )
        )
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(activity, tokens.density.gapDp) }

        val wrapper = FocusUi.column(activity)
        wrapper.addView(row)
        wrapper.addView(
            FocusUi.ghostButton(activity, tokens, activity.getString(R.string.youtab_see_where_time_went)) {
                activity.startActivity(Intent(activity, AnalyticsActivity::class.java))
            }
        )
        return wrapper
    }

    // ── Appearance ────────────────────────────────────────────────

    /**
     * Theme, accent, and a way into everything else.
     *
     * This was four stacked chip strips, two sliders, an Apply button and three
     * toggles - the tallest card in the app, for settings most people touch
     * once. Theme and accent stay inline because they are the two you actually
     * browse and they preview themselves live; type, spacing, text size,
     * rounding, motion and contrast are one row away, on the screen that
     * already existed for "the rest of the appearance settings".
     */
    private fun buildAppearanceCard(): View {
        val card = FocusUi.card(activity, tokens)

        card.addView(FocusUi.caption(activity, tokens, activity.getString(R.string.appearance_theme)))
        card.addView(
            FocusUi.chipStrip(
                activity,
                tokens,
                UiPrefs.themes.map { it.label(activity) },
                UiPrefs.themes.indexOfFirst { it.id == UiPrefs.getTheme(activity).id }
            ) { index ->
                UiPrefs.setThemeId(activity, UiPrefs.themes[index].id)
                activity.requestShellRebuild()
            }
        )

        card.addView(FocusUi.spacer(activity, 10))
        card.addView(FocusUi.caption(activity, tokens, activity.getString(R.string.appearance_accent)))
        card.addView(buildAccentRow())

        card.addView(FocusUi.spacer(activity, 12))
        card.addView(FocusUi.divider(activity, tokens))

        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.youtab_type_and_spacing),
                UiPrefs.getFont(activity).label(activity) + " · " + UiPrefs.getDensity(activity).label(activity) +
                    " · " + (UiPrefs.getTextScale(activity) * 100).toInt() + "%",
                trailing = FocusUi.chevron(activity, tokens)
            ) { activity.startActivity(Intent(activity, PersonalizationActivity::class.java)) }
        )

        card.addView(FocusUi.divider(activity, tokens))
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.youtab_language),
                languageLabel(UiPrefs.getAppLanguageTag()),
                trailing = FocusUi.chevron(activity, tokens)
            ) { pickLanguage() }
        )

        card.addView(FocusUi.divider(activity, tokens))
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.youtab_clock),
                clockLabel(UiPrefs.getClockFormat(activity)),
                trailing = FocusUi.chevron(activity, tokens)
            ) { pickClockFormat() }
        )
        return card
    }

    private fun clockLabel(value: String): String = when (value) {
        TimeText.CLOCK_12 -> activity.getString(R.string.youtab_clock_12)
        TimeText.CLOCK_24 -> activity.getString(R.string.youtab_clock_24)
        else -> activity.getString(R.string.youtab_clock_match)
    }

    /**
     * The app used to print 24-hour times whatever the phone said, which is
     * what "why is it in weird 24 hours format" was about. It follows the
     * system setting now; this is for the case where the phone and the person
     * disagree.
     */
    private fun pickClockFormat() {
        FocusDialog.singleChoice(
            activity,
            activity.getString(R.string.youtab_clock),
            activity.getString(R.string.youtab_clock_subtitle),
            listOf(
                FocusDialog.Choice(TimeText.CLOCK_SYSTEM, clockLabel(TimeText.CLOCK_SYSTEM)),
                FocusDialog.Choice(TimeText.CLOCK_12, clockLabel(TimeText.CLOCK_12)),
                FocusDialog.Choice(TimeText.CLOCK_24, clockLabel(TimeText.CLOCK_24))
            ),
            UiPrefs.getClockFormat(activity)
        ) { selected ->
            UiPrefs.setClockFormat(activity, selected)
            render()
        }
    }

    private fun languageLabel(tag: String): String = when (tag) {
        "ar" -> "العربية"
        "en" -> "English"
        else -> activity.getString(R.string.youtab_font_system_default)
    }

    /**
     * The app's own switch, independent of system Settings -> App info ->
     * Language (which reaches the same place via `android:localeConfig` -
     * this is just the faster, in-app route to it).
     */
    private fun pickLanguage() {
        FocusDialog.singleChoice(
            activity,
            activity.getString(R.string.youtab_language),
            activity.getString(R.string.youtab_theme_subtitle),
            listOf(
                FocusDialog.Choice("system", activity.getString(R.string.youtab_font_system_default)),
                FocusDialog.Choice("en", "English"),
                FocusDialog.Choice("ar", "العربية")
            ),
            UiPrefs.getAppLanguageTag()
        ) { selected ->
            UiPrefs.setAppLanguage(selected)
        }
    }

    private fun buildAccentRow(): View {
        val strip = FocusUi.row(activity)
        strip.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val currentId = UiPrefs.getAccent(activity).id

        UiPrefs.accents.forEach { accent ->
            val swatch = View(activity)
            val size = FocusUi.dp(activity, 34)
            swatch.background = FocusUi.roundedShape(
                activity,
                accent.color,
                17,
                if (accent.id == currentId) tokens.textPrimary else null,
                2
            )
            swatch.isClickable = true
            swatch.isFocusable = true
            swatch.contentDescription = accent.label(activity)
            swatch.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = FocusUi.dp(activity, 10)
            }
            swatch.setOnClickListener {
                UiPrefs.setAccentId(activity, accent.id)
                activity.requestShellRebuild()
            }
            strip.addView(swatch)
        }
        return FocusUi.horizontalScroll(activity, strip)
    }

    // ── Help ──────────────────────────────────────────────────────

    private fun buildHelpCard(): View {
        val card = FocusUi.card(activity, tokens)

        // The switchboard, as one row.
        //
        // This tab used to carry its own copy of it: a prose paragraph, four
        // capability toggles each with its full blurb, the break settings with
        // two sliders, a consequence line under every switch that was off, and
        // a reset button - about two hundred lines of screen. Every one of
        // those switches already lives in CapabilitiesActivity, which is the
        // screen built for them. Two places to change the same flag is not a
        // convenience, it is a second place to look when one of them is wrong.
        val enabledCount = Capabilities.all.count { CapabilityRegistry.isEnabled(activity, it.id) }
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.youtab_capabilities_title),
                advancedSummary(enabledCount),
                trailing = FocusUi.chevron(activity, tokens)
            ) { activity.startActivity(Intent(activity, CapabilitiesActivity::class.java)) }
        )
        card.addView(FocusUi.divider(activity, tokens))

        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.perms_title),
                permissionSummary(),
                trailing = FocusUi.chevron(activity, tokens)
            ) { activity.startActivity(Intent(activity, SetupPermissionsActivity::class.java)) }
        )
        card.addView(FocusUi.divider(activity, tokens))

        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.youtab_device_owner_title),
                if (SetupChecks.isDeviceOwner(activity)) {
                    activity.getString(R.string.youtab_device_owner_active)
                } else {
                    activity.getString(R.string.youtab_device_owner_inactive)
                },
                trailing = FocusUi.chevron(activity, tokens)
            ) { activity.startActivity(Intent(activity, DeviceOwnerHelpActivity::class.java)) }
        )
        card.addView(FocusUi.divider(activity, tokens))

        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.youtab_advanced_title),
                activity.getString(R.string.youtab_advanced_subtitle),
                trailing = FocusUi.chevron(activity, tokens)
            ) { activity.startActivity(Intent(activity, AdvancedHelpActivity::class.java)) }
        )
        card.addView(FocusUi.divider(activity, tokens))

        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.youtab_rerun_quiz),
                activity.getString(R.string.youtab_rerun_quiz_subtitle),
                trailing = FocusUi.chevron(activity, tokens)
            ) {
                if (SessionLock.isFrozen(activity)) {
                    FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                } else {
                    activity.startActivity(
                        Intent(activity, OnboardingActivity::class.java)
                            .putExtra(OnboardingActivity.EXTRA_RERUN, true)
                    )
                }
            }
        )
        card.addView(FocusUi.divider(activity, tokens))

        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.youtab_start_over_title),
                activity.getString(R.string.youtab_start_over_subtitle),
                trailing = FocusUi.chevron(activity, tokens)
            ) {
                if (SessionLock.isFrozen(activity)) {
                    FocusDialog.toast(activity, SessionLock.refusalMessage(activity))
                } else {
                    FocusDialog.alert(
                        activity,
                        title = activity.getString(R.string.youtab_start_over_title),
                        message = activity.getString(R.string.youtab_start_over_confirm),
                        confirmLabel = activity.getString(R.string.onboarding_start_over),
                        cancelLabel = activity.getString(R.string.common_cancel),
                        onConfirm = {
                            CapabilityRegistry.resetToDefaults(activity)
                            ScheduleManager.saveSchedules(activity, emptyList())
                            FocusStore.setBool(activity, Constants.KEY_ONBOARDING_DONE, false)
                            activity.startActivity(
                                Intent(activity, OnboardingActivity::class.java)
                                    .putExtra(OnboardingActivity.EXTRA_RERUN, true)
                            )
                        }
                    )
                }
            }
        )
        card.addView(FocusUi.divider(activity, tokens))

        val adbDisabled = FocusStore.getBool(activity, Constants.KEY_ADB_DISABLED, false)
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                if (adbDisabled) activity.getString(R.string.youtab_adb_off_title) else activity.getString(R.string.youtab_adb_turn_off),
                if (adbDisabled) {
                    activity.getString(R.string.youtab_adb_off_detail)
                } else {
                    activity.getString(R.string.youtab_adb_subtitle)
                },
                trailing = if (adbDisabled) {
                    FocusUi.pill(activity, tokens, activity.getString(R.string.common_done), tokens.success)
                } else {
                    FocusUi.chevron(activity, tokens)
                }
            ) { if (!adbDisabled) confirmDisableAdb() }
        )
        card.addView(FocusUi.divider(activity, tokens))

        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.diag_report_title),
                Diagnostics.lastCrashAt(activity)?.let {
                    activity.getString(R.string.diag_report_subtitle_crash, TimeText.dateTime(activity, it, withWeekday = false))
                } ?: activity.getString(R.string.diag_report_subtitle),
                trailing = FocusUi.chevron(activity, tokens)
            ) { confirmReport(activity) }
        )
        card.addView(FocusUi.divider(activity, tokens))

        val newer = UpdateCheck.knownNewer(activity)
        card.addView(
            FocusUi.listRow(
                activity,
                tokens,
                activity.getString(R.string.update_title),
                if (newer != null) {
                    activity.getString(R.string.update_subtitle_available, newer.versionName)
                } else {
                    activity.getString(R.string.update_subtitle_current, BuildConfig.VERSION_NAME)
                },
                trailing = FocusUi.chevron(activity, tokens)
            ) { checkForUpdate(activity) { render() } }
        )
        card.addView(
            FocusUi.toggleRow(
                activity,
                tokens,
                activity.getString(R.string.update_auto_title),
                activity.getString(R.string.update_auto_subtitle),
                UpdateCheck.isAutoEnabled(activity)
            ) { UpdateCheck.setAutoEnabled(activity, it) }
        )
        return card
    }

    companion object {

        /**
         * Says what a report contains before anything is gathered, then hands
         * it to the share sheet. Shared with the dashboard's crash card.
         */
        fun confirmReport(activity: MainActivity) {
            FocusDialog.alert(
                activity,
                title = activity.getString(R.string.diag_confirm_title),
                message = activity.getString(R.string.diag_confirm_message),
                confirmLabel = activity.getString(R.string.diag_confirm_send),
                cancelLabel = activity.getString(R.string.common_cancel),
                onConfirm = {
                    Diagnostics.acknowledgeCrashes(activity)
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    Thread {
                        val report = Diagnostics.buildReport(activity)
                        main.post {
                            try {
                                Diagnostics.share(activity, report)
                            } catch (_: Exception) {
                                FocusDialog.toast(activity, activity.getString(R.string.common_page_not_available))
                            }
                        }
                    }.start()
                }
            )
        }

        /** The manual check. Also opens the update page when there is one. */
        fun checkForUpdate(activity: MainActivity, onDone: () -> Unit) {
            FocusDialog.toast(activity, activity.getString(R.string.update_checking))
            UpdateCheck.checkNow(activity) { result ->
                when (result) {
                    is UpdateCheck.Result.Newer -> showUpdate(activity, result.release)
                    UpdateCheck.Result.UpToDate ->
                        FocusDialog.toast(activity, activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
                    UpdateCheck.Result.Failed ->
                        FocusDialog.toast(activity, activity.getString(R.string.update_failed))
                }
                onDone()
            }
        }

        fun showUpdate(activity: MainActivity, release: UpdateCheck.Release) {
            FocusDialog.alert(
                activity,
                title = activity.getString(R.string.update_available_title, release.versionName),
                message = release.notes.ifBlank { activity.getString(R.string.update_available_message) },
                confirmLabel = activity.getString(R.string.update_get_it),
                cancelLabel = activity.getString(R.string.update_later),
                onConfirm = {
                    // A browser may be held to the allowlist during a session;
                    // the update page is for between sessions, and says so.
                    try {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        FocusDialog.toast(activity, activity.getString(R.string.common_page_not_available))
                    }
                }
            )
        }
    }

    /** One line where a paragraph used to be: the template, and whether it still matches. */
    private fun advancedSummary(enabledCount: Int): String {
        val mode = SessionManager.mode(activity)
        val base = activity.getString(R.string.mode_template_line, enabledCount, Capabilities.all.size, mode.label(activity))
        return when {
            SessionLock.isFrozen(activity) ->
                activity.getString(R.string.youtab_suffix_held, base)
            !SessionManager.matchesPreset(activity, mode) -> activity.getString(R.string.youtab_suffix_edited, base)
            else -> base
        }
    }

    private fun permissionSummary(): String {
        val missing = SetupChecks.missingForCurrentCapabilities(activity)
        return if (missing.isEmpty()) {
            activity.getString(R.string.youtab_permissions_all_granted)
        } else {
            activity.getString(
                if (missing.size == 1) R.string.youtab_permissions_waiting_one
                else R.string.youtab_permissions_waiting_many,
                missing.size
            )
        }
    }

    /**
     * Kept exactly as it was: an explicit, user-fired button. Provisioning never
     * triggers it, and starting a session never triggers it.
     */
    private fun confirmDisableAdb() {
        if (!SetupChecks.isDeviceOwner(activity)) {
            FocusDialog.info(
                activity,
                activity.getString(R.string.youtab_device_owner_needed_title),
                activity.getString(R.string.youtab_device_owner_needed_detail)
            )
            return
        }

        FocusDialog.alert(
            activity,
            title = activity.getString(R.string.youtab_adb_confirm_title),
            message = activity.getString(R.string.youtab_adb_confirm_message),
            confirmLabel = activity.getString(R.string.youtab_adb_confirm_button),
            cancelLabel = activity.getString(R.string.common_cancel),
            destructive = true,
            onConfirm = {
                FocusStore.setBool(activity, Constants.KEY_ADB_DISABLED, true)
                try {
                    val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    dpm.addUserRestriction(
                        ComponentName(activity, AdminReceiver::class.java),
                        UserManager.DISALLOW_DEBUGGING_FEATURES
                    )
                    KioskPolicy.forgetAppliedState()
                } catch (_: Exception) {
                    FocusDialog.toast(activity, activity.getString(R.string.youtab_restriction_refused))
                }
                render()
            }
        )
    }

    // ── Share my week ─────────────────────────────────────────────

    private fun shareSummary() {
        val report = UsageAnalytics.last7Days(activity)
        val summary = buildString {
            append(activity.getString(R.string.youtab_share_header)).append("\n")
            append(activity.getString(R.string.youtab_share_sessions, SessionManager.totalSessions(activity))).append("\n")
            if (Streaks.isEnabled(activity)) {
                append(activity.getString(R.string.youtab_share_run, Streaks.current(activity))).append("\n")
            }
            append(activity.getString(R.string.youtab_share_screen_time, UsageAnalytics.formatDuration(report.totalMs)))
        }
        try {
            activity.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, summary)
                    },
                    activity.getString(R.string.youtab_share_dialog_title)
                )
            )
        } catch (_: Exception) {
            FocusDialog.toast(activity, activity.getString(R.string.youtab_share_no_app))
        }
    }

    // ── Release ───────────────────────────────────────────────────

    private fun buildReleaseCard(): View {
        val card = FocusUi.card(activity, tokens)

        card.addView(
            FocusUi.secondary(
                activity,
                tokens,
                activity.getString(R.string.youtab_reset_subtitle)
            )
        )
        card.addView(FocusUi.spacer(activity, 14))
        card.addView(
            FocusUi.dangerButton(activity, tokens, activity.getString(R.string.youtab_reset_button)) { confirmFactoryReset() }
        )
        return card
    }

    private fun confirmFactoryReset() {
        FocusDialog.confirmWithCountdown(
            activity,
            title = activity.getString(R.string.youtab_reset_confirm_title),
            message = activity.getString(R.string.youtab_reset_confirm_message),
            confirmLabel = activity.getString(R.string.youtab_reset_confirm_button),
            seconds = 8
        ) {
            performFactoryReset()
        }
    }

    private fun performFactoryReset() {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(activity.packageName)) {
            try {
                dpm.wipeData(0)
                return
            } catch (_: Exception) {
                // Fall through to the Settings route below.
            }
        }
        LockManager.allowSettingsUntil(activity, System.currentTimeMillis() + 5 * 60 * 1000)
        val intents = listOf(
            Intent("android.settings.FACTORY_RESET"),
            Intent("android.settings.MASTER_CLEAR"),
            Intent(Settings.ACTION_PRIVACY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        intents.forEach { intent ->
            try {
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                // Try the next one.
            }
        }
        FocusDialog.toast(activity, activity.getString(R.string.youtab_reset_settings_only))
    }

    private fun buildAbout(): View {
        val column = FocusUi.column(activity)
        val text = FocusUi.caption(
            activity,
            tokens,
            activity.getString(R.string.youtab_about)
        )
        text.gravity = android.view.Gravity.CENTER
        text.setPadding(0, FocusUi.dp(activity, 8), 0, FocusUi.dp(activity, 24))
        column.addView(text)
        return column
    }
}