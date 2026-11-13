package com.focuslock.mdm

import android.content.Intent
import android.view.Gravity
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

        // Moon: the canvas's round back button, like every other page.
        if (tokens.moon) {
            add(FocusUi.roundButton(activity, tokens, "\u2190", activity.getString(R.string.nav_back_to_focus)) {
                activity.selectTab(MainActivity.TAB_FOCUS)
            })
        } else add(
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
                activity.getString(R.string.keeper_gate_title),
                activity.getString(R.string.keeper_gate_subtitle),
                art = R.drawable.art_keeper
            )
        )

        var anything = false

        // The Keeper is the way in: one search for the web and YouTube. What is
        // already known to be safe opens at once; anything else is looked at first.
        if (CapabilityRegistry.isEnabled(activity, Capabilities.SAFE_BROWSER)) {
            anything = true
            add(buildKeeperGateway())
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

    // ── Keeper gateway ────────────────────────────────────────────

    private var query = ""
    private var results: List<KeeperSearch.Result> = emptyList()
    private var filter = 0
    private var searching = false
    private var failed = false
    private var searchSeq = 0
    private var resultsBox: LinearLayout? = null

    private fun buildKeeperGateway(): View {
        val column = FocusUi.column(activity)
        column.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = FocusUi.dp(activity, 20) }

        val field = FocusUi.input(activity, tokens, activity.getString(R.string.keeper_gate_hint), query)
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        field.isSingleLine = true
        field.background = FocusUi.roundedShape(activity, tokens.surface, 999, tokens.divider, 1)
        field.setPadding(FocusUi.dp(activity, 22), 0, FocusUi.dp(activity, 22), 0)
        field.minimumHeight = FocusUi.dp(activity, 56)
        field.setOnEditorActionListener { v, _, _ ->
            runSearch(v.text.toString())
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        column.addView(field)
        val or = FocusUi.caption(activity, tokens, activity.getString(R.string.keeper_gate_or_share))
        or.gravity = Gravity.CENTER_HORIZONTAL
        or.setPadding(0, FocusUi.dp(activity, 8), 0, FocusUi.dp(activity, 6))
        column.addView(or)

        val settings = FocusUi.smallButton(activity, tokens, activity.getString(R.string.keeper_settings_title)) {
            activity.startActivity(Intent(activity, KeeperSettingsActivity::class.java))
        }
        settings.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(settings)

        val box = FocusUi.column(activity)
        box.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(activity, 14) }
        resultsBox = box
        fillResults()
        column.addView(box)
        return column
    }

    private var checkingThumbs = false

    private fun runSearch(text: String) {
        query = text.trim()
        if (query.isEmpty()) return
        val seq = ++searchSeq
        searching = true
        failed = false
        fillResults()
        Thread {
            val found = KeeperSearch.shaped(activity, KeeperSearch.search(query))
            activity.runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                searching = false
                results = found
                failed = found.isEmpty()
                fillResults()
            }
        }.start()
    }

    private fun fillResults() {
        val box = resultsBox ?: return
        box.removeAllViews()
        if (searching) {
            box.addView(FocusUi.secondary(activity, tokens, activity.getString(R.string.keeper_gate_searching)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            return
        }
        if (query.isEmpty()) return
        if (failed) {
            box.addView(FocusUi.emptyState(activity, tokens, activity.getString(R.string.keeper_gate_nothing)))
            return
        }
        val chips = FocusUi.row(activity)
        listOf(R.string.keeper_gate_all, R.string.keeper_gate_videos, R.string.keeper_gate_pages).forEachIndexed { i, res ->
            chips.addView(FocusUi.chip(activity, tokens, activity.getString(res), filter == i) { filter = i; fillResults() }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = FocusUi.dp(activity, 8)
            })
        }
        box.addView(chips)
        box.addView(FocusUi.spacer(activity, 6))
        // One Keeper check looks at up to 16 dark thumbnails as a grid and uncovers the ones it clears.
        // It only uncovers the picture; opening the video still goes through the Keeper.
        val cleared = MediaBlur.cleared(activity)
        val darkThumbs = results.filter { it.kind == KeeperSearch.Kind.VIDEO && KeeperSearch.state(activity, it) == KeeperSearch.State.DARK }
            .mapNotNull { it.thumb }.filter { MediaBlur.key(it) !in cleared }.take(MediaBlur.GRID)
        if (darkThumbs.isNotEmpty() && Keeper.isReady(activity)) {
            box.addView(FocusUi.smallButton(activity, tokens, if (checkingThumbs) activity.getString(R.string.keeper_gate_thumbs_checking)
                else activity.getString(R.string.keeper_gate_thumbs, darkThumbs.size)) {
                if (checkingThumbs) return@smallButton
                checkingThumbs = true; fillResults()
                Thread {
                    val ok = MediaBlur.check(activity.applicationContext, darkThumbs)
                    activity.runOnUiThread {
                        checkingThumbs = false
                        if (ok == null) FocusDialog.toast(activity, activity.getString(R.string.media_keeper_unavailable))
                        fillResults()
                    }
                }.start()
            })
            box.addView(FocusUi.spacer(activity, 6))
        }
        results.filter { filter == 0 || (filter == 1) == (it.kind == KeeperSearch.Kind.VIDEO) }
            .forEach { box.addView(buildResultRow(it)) }
        val legend = FocusUi.caption(activity, tokens, activity.getString(R.string.keeper_gate_legend))
        legend.setPadding(0, FocusUi.dp(activity, 12), 0, 0)
        box.addView(legend)
    }

    private fun buildResultRow(r: KeeperSearch.Result): View {
        val state = KeeperSearch.state(activity, r)
        val row = FocusUi.row(activity)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, FocusUi.dp(activity, 10), 0, FocusUi.dp(activity, 10))
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (state == KeeperSearch.State.CLOSED) row.alpha = 0.45f

        val thumb = android.widget.ImageView(activity)
        thumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        thumb.background = FocusUi.roundedShape(activity, tokens.surfaceAlt, 12)
        thumb.clipToOutline = true
        thumb.layoutParams = LinearLayout.LayoutParams(FocusUi.dp(activity, 112), FocusUi.dp(activity, 63)).apply { rightMargin = FocusUi.dp(activity, 12) }
        row.addView(thumb)
        r.thumb?.let { url ->
            val sharp = state == KeeperSearch.State.LIT || MediaBlur.key(url) in MediaBlur.cleared(activity)
            Thread {
                KeeperSearch.thumb(url)?.let { raw ->
                    val shown = if (sharp) raw else KeeperSearch.smeared(raw)
                    activity.runOnUiThread { thumb.setImageBitmap(shown) }
                }
            }.start()
        }

        val text = FocusUi.column(activity)
        text.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        text.addView(FocusUi.body(activity, tokens, r.title).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val meta = listOfNotNull(r.source, r.duration).joinToString(" · ")
        text.addView(FocusUi.caption(activity, tokens,
            if (state == KeeperSearch.State.CLOSED) activity.getString(R.string.keeper_gate_closed) else meta))
        row.addView(text)

        val dot = View(activity)
        val size = FocusUi.dp(activity, 10)
        dot.layoutParams = LinearLayout.LayoutParams(size, size).apply { leftMargin = FocusUi.dp(activity, 10) }
        dot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            if (state == KeeperSearch.State.LIT) setColor(tokens.accent) else setStroke(FocusUi.dp(activity, 1).coerceAtLeast(2), tokens.textMuted)
        }
        if (state != KeeperSearch.State.CLOSED) row.addView(dot)

        row.contentDescription = r.title + ", " + activity.getString(when (state) {
            KeeperSearch.State.LIT -> R.string.keeper_gate_state_lit
            KeeperSearch.State.DARK -> R.string.keeper_gate_state_dark
            KeeperSearch.State.CLOSED -> R.string.keeper_gate_closed
        })
        if (state != KeeperSearch.State.CLOSED) {
            row.isClickable = true
            row.isFocusable = true
            row.background = FocusUi.withRipple(activity, android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), tokens)
            row.setOnClickListener { openResult(r, state) }
        }
        row.setOnLongClickListener { resultChoices(r); true }
        return row
    }

    /** Long press: always open, or never show, this channel or site. */
    private fun resultChoices(r: KeeperSearch.Result) {
        val video = r.kind == KeeperSearch.Kind.VIDEO
        if (video && r.channelId == null) return
        val labels = if (video) arrayOf(
            activity.getString(R.string.keeper_gate_always_channel, r.source),
            activity.getString(R.string.keeper_gate_never_channel, r.source)
        ) else arrayOf(
            activity.getString(R.string.keeper_gate_always_site, r.source),
            activity.getString(R.string.keeper_gate_never_site, r.source)
        )
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setItems(labels) { _, which ->
                when {
                    video && which == 0 -> KeeperSearch.allowChannel(activity, r.channelId!!, r.source)
                    video -> KeeperSearch.blockChannel(activity, r.channelId!!, r.source)
                    which == 0 -> AllowlistStore.addWebUrl(activity, r.source)
                    else -> KeeperSearch.blockSite(activity, r.source)
                }
                if (!video || which == 1) results = KeeperSearch.shaped(activity, results)
                fillResults()
            }
            .show()
    }

    private fun openResult(r: KeeperSearch.Result, state: KeeperSearch.State) {
        val intent = when {
            r.kind == KeeperSearch.Kind.VIDEO && r.videoId != null ->
                Intent(activity, VideoCheckActivity::class.java).putExtra(VideoCheckActivity.EXTRA_VIDEO_ID, r.videoId)
            state == KeeperSearch.State.LIT ->
                Intent(activity, WebViewActivity::class.java).putExtra(WebViewActivity.EXTRA_INITIAL_URL, r.url)
            else -> Intent(activity, KeeperActivity::class.java).putExtra(KeeperActivity.EXTRA_URL, r.url)
        }
        activity.startActivity(intent)
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
