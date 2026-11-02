package com.focuslock.mdm

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The Library tab: the replacement ecosystem.
 *
 * The thesis of the whole product lives here. Blocking alone leaves a hole that
 * gets filled by finding a way around the block, so the locked phone has to
 * still offer something: a curated internet, a search that cannot become a
 * scroll, and a reward that arrives on a timer instead of on demand.
 *
 * Each surface is a capability, so a person who wants a bare phone can empty
 * this tab completely.
 */
class LibraryTab(activity: MainActivity, tokens: UiPrefs.Tokens) : FocusTab(activity, tokens) {

    private lateinit var container: LinearLayout

    private var cachedApps: List<InstalledApp>? = null
    private var cachedAppsKey = ""

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

        add(
            FocusUi.smallButton(activity, tokens, activity.getString(R.string.nav_back_to_focus)) {
                activity.selectTab(MainActivity.TAB_FOCUS)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    FocusUi.dp(activity, tokens.density.quickButtonHeightDp)
                ).apply { bottomMargin = FocusUi.dp(activity, 8) }
            }
        )

        add(
            FocusUi.pageHeader(
                activity,
                tokens,
                activity.getString(R.string.library_title),
                activity.getString(R.string.library_subtitle)
            )
        )

        var anything = false

        if (CapabilityRegistry.isEnabled(activity, Capabilities.SAFE_BROWSER) &&
            UiPrefs.showWebButton(activity)
        ) {
            anything = true
            add(buildBrowserCard())
        }

        if (CapabilityRegistry.isEnabled(activity, Capabilities.SAFE_YOUTUBE)) {
            anything = true
            add(buildSafeYouTubeCard())
        }

        if (CapabilityRegistry.isEnabled(activity, Capabilities.TEXT_SEARCH)) {
            anything = true
            add(buildTextSearchCard())
        }

        if (UiPrefs.showAllowedApps(activity)) {
            val open = openableApps()
            if (open.isNotEmpty()) {
                add(FocusUi.sectionLabel(activity, tokens, activity.getString(R.string.library_apps_you_can_open_section)))
                add(buildAppGrid(open))
            }
        }

        if (!anything) {
            add(FocusUi.emptyState(activity, tokens, activity.getString(R.string.library_all_off_empty_state)))
        }

        Motion.stagger(added, tokens)
    }

    // ── Cards ─────────────────────────────────────────────────────

    private fun buildBrowserCard(): View {
        val card = FocusUi.card(activity, tokens) {
            activity.startActivity(Intent(activity, WebViewActivity::class.java))
        }

        card.addView(FocusUi.heading(activity, tokens, activity.getString(R.string.library_browser_heading)))
        card.addView(FocusUi.spacer(activity, 6))
        card.addView(FocusUi.secondary(activity, tokens, activity.getString(R.string.library_browser_body)))

        val categories = AllowlistStore.getWebCategories(activity).take(6)
        if (categories.isNotEmpty()) {
            card.addView(FocusUi.spacer(activity, 12))
            val strip = FocusUi.row(activity)
            strip.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            categories.forEach { category ->
                strip.addView(
                    FocusUi.pill(activity, tokens, category, tokens.textSecondary).apply {
                        (layoutParams as LinearLayout.LayoutParams).marginEnd = FocusUi.dp(activity, 6)
                    }
                )
            }
            card.addView(FocusUi.horizontalScroll(activity, strip))
        }

        card.addView(FocusUi.spacer(activity, 14))
        card.addView(
            FocusUi.primaryButton(activity, tokens, activity.getString(R.string.library_browser_open_button)) {
                activity.startActivity(Intent(activity, WebViewActivity::class.java))
            }
        )
        card.addView(FocusUi.spacer(activity, 8))
        card.addView(
            FocusUi.ghostButton(
                activity,
                tokens,
                activity.getString(R.string.library_browser_sites_edit_button, AllowlistStore.getWebAllowlistUrls(activity).size)
            ) {
                activity.startActivity(Intent(activity, WebAllowlistEditorActivity::class.java))
            }
        )
        return card
    }

    private fun buildSafeYouTubeCard(): View {
        val card = FocusUi.card(activity, tokens) {
            activity.startActivity(Intent(activity, SafeTubeBrowserActivity::class.java))
        }

        card.addView(FocusUi.heading(activity, tokens, activity.getString(R.string.library_safetube_heading)))
        card.addView(FocusUi.spacer(activity, 6))
        card.addView(FocusUi.secondary(activity, tokens, activity.getString(R.string.library_safetube_body)))

        val channels = SafeTubeStore.getChannels(activity).take(6)
        if (channels.isNotEmpty()) {
            card.addView(FocusUi.spacer(activity, 12))
            val strip = FocusUi.row(activity)
            strip.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            channels.forEach { ch ->
                strip.addView(
                    FocusUi.pill(activity, tokens, ch.name, tokens.textSecondary).apply {
                        (layoutParams as LinearLayout.LayoutParams).marginEnd = FocusUi.dp(activity, 6)
                    }
                )
            }
            card.addView(FocusUi.horizontalScroll(activity, strip))
        }

        card.addView(FocusUi.spacer(activity, 14))
        card.addView(
            FocusUi.primaryButton(activity, tokens, activity.getString(R.string.library_safetube_open_button)) {
                activity.startActivity(Intent(activity, SafeTubeBrowserActivity::class.java))
            }
        )
        card.addView(FocusUi.spacer(activity, 8))
        card.addView(
            FocusUi.ghostButton(activity, tokens, activity.getString(R.string.library_safetube_trusted_button)) {
                activity.startActivity(Intent(activity, SafeYouTubeActivity::class.java))
            }
        )
        card.addView(
            FocusUi.ghostButton(activity, tokens, activity.getString(R.string.library_keeper_button)) {
                activity.startActivity(Intent(activity, KeeperSettingsActivity::class.java))
            }
        )
        return card
    }

    private fun buildTextSearchCard(): View {
        val card = FocusUi.card(activity, tokens) {
            activity.startActivity(Intent(activity, TextSearchActivity::class.java))
        }
        card.addView(FocusUi.heading(activity, tokens, activity.getString(R.string.library_text_search_heading)))
        card.addView(FocusUi.spacer(activity, 6))
        card.addView(FocusUi.secondary(activity, tokens, activity.getString(R.string.library_text_search_body)))
        card.addView(FocusUi.spacer(activity, 14))
        card.addView(
            FocusUi.secondaryButton(activity, tokens, activity.getString(R.string.library_text_search_button)) {
                activity.startActivity(Intent(activity, TextSearchActivity::class.java))
            }
        )
        return card
    }

    // ── App grid ──────────────────────────────────────────────────

    /**
     * The grid's contents, memoised against the policy revision.
     *
     * Filtering every installed app and asking for each one's effective policy
     * is not free, and this runs on every tab open. Nothing here can change
     * without PolicySync ticking, so the revision is a sound cache key.
     */
    private fun openableApps(): List<InstalledApp> {
        // Kiosk state is part of the key, not only the revision: it can flip on
        // the clock (an overlay window opening mid-session) with no store write
        // to tick the revision.
        val lockTask = SessionManager.shouldLockTask(activity)
        val key = PolicySync.revision().toString() + "|" + lockTask
        val cached = cachedApps
        if (cached != null && cachedAppsKey == key) return cached

        val blocked = AppRules.blockedPackages(activity)

        // In kiosk, the allowlist IS the answer, and it is not intersected with
        // a launchability check.
        //
        // `launchable()` requires `getLaunchIntentForPackage(pkg) != null`, and
        // hiding an app is precisely what makes that return null - so is the
        // suspended state, and so is being absent from getInstalledApplications
        // once hidden. Intersecting the two therefore drops apps the user
        // explicitly allowed, which is the report: "in Kiosk I cannot open the
        // apps I allowlisted". Everything on this list is openable by
        // definition; the grid's only job is to show it.
        //
        // FocusLock itself is excluded because it is already the screen you are
        // looking at, and the list is sorted the same way as the other branch so
        // the grid does not reshuffle between a session and no session.
        val computed = if (lockTask) {
            AppRules.kioskAllowlist(activity)
                .filter { it != activity.packageName && AppCatalog.isInstalled(activity, it) }
                .map { pkg ->
                    AppCatalog.find(activity, pkg) ?: InstalledApp(
                        packageName = pkg,
                        label = AppCatalog.label(activity, pkg),
                        category = AppCatalog.categoryOf(activity, pkg),
                        isSystem = false,
                        isLaunchable = true
                    )
                }
                .sortedBy { it.label.lowercase(java.util.Locale.getDefault()) }
        } else {
            AppCatalog.launchable(activity)
                .filter { app ->
                    if (app.packageName in blocked) return@filter false
                    AppRules.effectivePolicy(activity, app.packageName) != AppPolicy.HIDE
                }
        }.take(120)

        cachedApps = computed
        cachedAppsKey = key
        return computed
    }

    /**
     * A wrapping grid built by hand: four columns of icon plus label, so the
     * launcher-inside-the-app looks like a launcher rather than a settings list.
     */
    private fun buildAppGrid(apps: List<InstalledApp>): View {
        val card = FocusUi.card(activity, tokens)
        val columns = 4

        apps.chunked(columns).forEach { chunk ->
            val cells = chunk.map { buildAppCell(it) }
            // Short rows are padded to four so the last row's icons line up
            // under the ones above instead of spreading out across the card.
            val padded = cells + List(columns - cells.size) { View(activity) }
            val row = FocusUi.tileRow(activity, padded, gapDp = 0)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = FocusUi.dp(activity, 10) }
            card.addView(row)
        }
        return card
    }

    private fun buildAppCell(app: InstalledApp): View {
        val cell = FocusUi.column(activity)
        cell.gravity = android.view.Gravity.CENTER_HORIZONTAL
        cell.setPadding(0, FocusUi.dp(activity, 8), 0, FocusUi.dp(activity, 8))
        cell.isClickable = true
        cell.isFocusable = true
        cell.background = FocusUi.withRipple(
            activity,
            FocusUi.roundedShape(activity, UiPrefs.withAlpha(tokens.surface, 0), 14),
            tokens
        )
        cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        cell.setOnClickListener { launch(app.packageName) }

        val icon = FocusUi.appIcon(activity, tokens, app.packageName, 44)
        (icon.layoutParams as? LinearLayout.LayoutParams)?.gravity = android.view.Gravity.CENTER_HORIZONTAL
        cell.addView(icon)

        val label = FocusUi.caption(activity, tokens, app.label)
        label.gravity = android.view.Gravity.CENTER
        // Always two lines tall, even for a one-word name, and ellipsised
        // rather than wrapped to a third.
        //
        // This is the "the library apps are literally un-equal in height
        // because of their names" problem: a cell was as tall as its label
        // needed, so "Maps" sat in a short cell next to "Google Calendar" in a
        // tall one, every row was a different height, and the icons no longer
        // lined up across the grid. Reserving both lines makes every cell
        // identical whatever it is called.
        label.minLines = 2
        label.maxLines = 2
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setTextColor(tokens.textSecondary)
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 6) }
        cell.addView(label)
        return cell
    }

    private fun launch(packageName: String) {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            FocusDialog.toast(activity, activity.getString(R.string.library_no_screen_toast))
            return
        }
        try {
            activity.startActivity(intent)
        } catch (_: SecurityException) {
            FocusDialog.toast(activity, activity.getString(R.string.library_kiosk_holding_toast))
        }
    }
}
