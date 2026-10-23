package com.focuslock.mdm

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import java.util.Locale

/**
 * The site allowlist.
 *
 * The old version was one textarea holding two hundred URLs, which is fine for
 * writing and useless for finding. This is a searchable, categorised list with
 * one-tap removal, and the textarea is still there for anyone who wants to
 * paste a whole set in at once.
 *
 * During a session the list can only shrink. Being able to add sites mid-lock
 * would make the lock decorative.
 */
class WebAllowlistEditorActivity : FocusScreenActivity() {

    private var query = ""
    private var category = 0

    override fun screenTitle(): String = getString(R.string.web_allowlist_title)

    override fun screenSubtitle(): String = getString(R.string.web_allowlist_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildStateCard())
        column.addView(buildSearch())
        column.addView(buildCategoryStrip())
        column.addView(buildList())
        column.addView(sectionLabel(getString(R.string.web_allowlist_bulk_edit_section)))
        column.addView(buildBulkCard())
    }

    /** Whether *adding* to the list would be refused right now. Removing never is. */
    private fun locked(): Boolean = !SessionLock.allows(this, EditDirection.LOOSEN)

    private fun buildStateCard(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.web_allowlist_blocking_title),
                getString(R.string.web_allowlist_blocking_subtitle),
                CapabilityRegistry.isEnabled(this, Capabilities.WEB_BLOCK)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.WEB_BLOCK, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                } else if (!value) {
                    Capabilities.spec(Capabilities.WEB_BLOCK)?.let { FocusDialog.weakenNotice(this, it) }
                }
                refresh()
            }
        )

        if (locked()) {
            card.addView(FocusUi.spacer(this, 8))
            card.addView(FocusUi.caption(this, tokens, getString(R.string.web_allowlist_session_running_caption)))
        }

        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.web_allowlist_strip_media_title),
                getString(R.string.web_allowlist_strip_media_subtitle),
                AllowlistStore.isWebTextOnlyEnabled(this)
            ) { value -> AllowlistStore.setWebTextOnlyEnabled(this, value) }
        )
    }

    private fun buildSearch(): View {
        val field = FocusUi.input(this, tokens, getString(R.string.web_allowlist_search_hint), query)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                renderList()
            }
        })
        return field
    }

    private fun categories(): List<String> = listOf(getString(R.string.common_all)) + AllowlistStore.getWebCategories(this)

    private fun buildCategoryStrip(): View =
        FocusUi.chipStrip(this, tokens, categories(), category) { index ->
            category = index
            renderList()
        }

    private lateinit var listHost: LinearLayout

    private fun buildList(): View {
        listHost = FocusUi.column(this)
        renderList()
        return listHost
    }

    private fun renderList() {
        if (!this::listHost.isInitialized) return
        listHost.removeAllViews()

        val allLabel = getString(R.string.common_all)
        val cats = categories()
        val selectedCategory = cats.getOrElse(category) { allLabel }
        val needle = query.trim().lowercase(Locale.getDefault())

        val links = AllowlistStore.getWebLinks(this)
            .filter { selectedCategory == allLabel || it.category == selectedCategory }
            .filter {
                needle.isEmpty() ||
                    it.title.lowercase(Locale.getDefault()).contains(needle) ||
                    it.url.lowercase(Locale.getDefault()).contains(needle)
            }

        val card = FocusUi.card(this, tokens)

        if (links.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.web_allowlist_no_sites_match)))
        } else {
            links.forEachIndexed { index, link ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        link.title,
                        link.url.removePrefix("https://").removePrefix("http://"),
                        trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_remove)) { remove(link.url) }
                    )
                )
                if (index < links.size - 1) card.addView(FocusUi.divider(this, tokens))
            }
        }

        card.addView(FocusUi.spacer(this, 12))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.web_allowlist_add_site)) { addSite() }
        )
        listHost.addView(card)
    }

    private fun remove(url: String) {
        // Always permitted: taking a site off the allowlist is a tightening.
        AllowlistStore.removeWebUrl(this, url)
        renderList()
    }

    private fun addSite() {
        if (locked()) {
            FocusDialog.info(
                this,
                getString(R.string.web_allowlist_not_while_session_title),
                getString(R.string.web_allowlist_not_while_session_message)
            )
            return
        }
        FocusDialog.textInput(
            this,
            title = getString(R.string.web_allowlist_add_site_title),
            subtitle = getString(R.string.web_allowlist_add_site_subtitle),
            hint = getString(R.string.web_allowlist_example_hint),
            confirmLabel = getString(R.string.common_add)
        ) { value ->
            val normalized = AllowlistStore.normalizeUrl(value)
            if (!AllowlistStore.isValidUrl(normalized)) {
                FocusDialog.toast(this, getString(R.string.web_allowlist_invalid_address_toast))
                return@textInput
            }
            if (!AllowlistStore.addWebUrl(this, normalized)) {
                FocusDialog.toast(this, SessionLock.refusalMessage(this))
            }
            refresh()
        }
    }

    /** Kept for the paste-a-list case, which the row editor genuinely cannot beat. */
    private fun buildBulkCard(): View = card { card ->
        card.addView(FocusUi.secondary(this, tokens, getString(R.string.web_allowlist_bulk_intro)))
        card.addView(FocusUi.spacer(this, 10))

        val field = FocusUi.input(
            this,
            tokens,
            getString(R.string.web_allowlist_bulk_hint),
            AllowlistStore.getWebAllowlistUrls(this).sorted().joinToString("\n"),
            multiline = true
        )
        field.minLines = 8
        card.addView(field)

        card.addView(
            FocusUi.secondaryButton(this, tokens, getString(R.string.web_allowlist_save_list_button)) {
                val original = AllowlistStore.getWebAllowlistUrls(this)
                val urls = field.text.toString()
                    .split("\n")
                    .map { AllowlistStore.normalizeUrl(it) }
                    .filter { it.isNotBlank() && AllowlistStore.isValidUrl(it) }
                    .toSet()

                if (locked() && urls.any { it !in original }) {
                    FocusDialog.info(
                        this,
                        getString(R.string.web_allowlist_not_while_session_title),
                        getString(R.string.web_allowlist_bulk_session_message)
                    )
                    return@secondaryButton
                }
                if (urls.isEmpty()) {
                    FocusDialog.toast(this, getString(R.string.web_allowlist_empty_list_toast))
                    return@secondaryButton
                }

                if (AllowlistStore.setWebAllowlistUrls(this, urls)) {
                    FocusDialog.toast(this, getString(R.string.web_allowlist_sites_saved_toast, urls.size))
                } else {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )
    }
}
