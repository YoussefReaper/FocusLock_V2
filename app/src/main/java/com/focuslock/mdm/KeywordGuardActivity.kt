package com.focuslock.mdm

import android.view.View
import android.widget.LinearLayout

/**
 * The keyword guard editor.
 *
 * The old WhatsApp guard was five phrases compiled into the app. Here the same
 * five are ordinary rows the user can read, edit or delete, sitting next to the
 * Shorts, Reels and adult guards and any words of their own.
 *
 * The point of a content guard is that it is surgical: it keeps the app and
 * removes the surface, so nobody has to choose between "no messenger" and "the
 * messenger's infinite video tab".
 */
class KeywordGuardActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_stone2

    override fun screenTitle(): String = getString(R.string.keyword_guard_title)

    override fun screenSubtitle(): String = getString(R.string.keyword_guard_subtitle)

    override fun buildContent(column: LinearLayout) {
        if (!SetupChecks.isContentGuardEnabled(this)) {
            column.addView(buildPermissionCard())
        }

        column.addView(buildMasterCard())
        column.addView(sectionLabel(getString(R.string.keyword_guard_section_builtin)))
        column.addView(
            buildGroupCard(KeywordRules.GROUP_WHATSAPP, Capabilities.WHATSAPP_GUARD, getString(R.string.keyword_guard_whatsapp))
        )
        column.addView(
            buildGroupCard(KeywordRules.GROUP_SHORTS, Capabilities.SHORTS_BLOCK, getString(R.string.keyword_guard_shorts))
        )
        column.addView(
            buildGroupCard(KeywordRules.GROUP_REELS, Capabilities.REELS_BLOCK, getString(R.string.keyword_guard_reels))
        )
        column.addView(
            buildGroupCard(KeywordRules.GROUP_ADULT, Capabilities.ADULT_BLOCK, getString(R.string.keyword_guard_adult))
        )

        column.addView(sectionLabel(getString(R.string.keyword_guard_section_telegram)))
        column.addView(buildTelegramCard())

        column.addView(sectionLabel(getString(R.string.keyword_guard_section_your_words)))
        column.addView(buildUserRules())

        column.addView(sectionLabel(getString(R.string.keyword_guard_section_exceptions)))
        column.addView(buildExceptions())
    }

    private fun buildPermissionCard(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, getString(R.string.keyword_guard_not_on_heading)))
        card.addView(FocusUi.spacer(this, 6))
        card.addView(FocusUi.secondary(this, tokens, getString(R.string.keyword_guard_not_on_body)))
        card.addView(FocusUi.spacer(this, 12))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.keyword_guard_open_accessibility)) {
                LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 2 * 60 * 1000)
                if (!PermissionIntents.launchFirst(this, PermissionIntents.accessibility(this))) {
                    FocusDialog.toast(this, getString(R.string.common_page_not_available))
                }
            }
        )
    }

    private fun buildMasterCard(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.keyword_guard_master_title),
                getString(R.string.keyword_guard_master_subtitle),
                CapabilityRegistry.isEnabled(this, Capabilities.CONTENT_GUARD)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.CONTENT_GUARD, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )
        card.addView(FocusUi.divider(this, tokens))
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.keyword_guard_own_keywords_title),
                getString(R.string.keyword_guard_own_keywords_subtitle),
                CapabilityRegistry.isEnabled(this, Capabilities.KEYWORD_BLOCK)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.KEYWORD_BLOCK, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )
    }

    private fun buildGroupCard(group: String, capabilityId: String, label: String): View = card { card ->
        val rules = KeywordRules.rulesInGroup(this, group)
        val enabled = CapabilityRegistry.isEnabled(this, capabilityId)

        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                label,
                resources.getQuantityString(R.plurals.keyword_guard_phrases_watched, rules.size, rules.size),
                enabled
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, capabilityId, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )

        if (!enabled) return@card

        rules.forEach { rule ->
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    rule.phrase,
                    describeScope(rule) + " - " + rule.action.label(this).lowercase(),
                    trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_edit)) { editRule(rule) }
                ) { editRule(rule) }
            )
        }

        card.addView(FocusUi.spacer(this, 8))
        card.addView(
            FocusUi.smallButton(this, tokens, getString(R.string.keyword_guard_add_phrase_to, label)) {
                addRule(group)
            }
        )
    }

    private fun buildUserRules(): View = card { card ->
        val rules = KeywordRules.userRules(this)

        if (rules.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, Copy.emptyKeywords(this)))
        } else {
            rules.forEachIndexed { index, rule ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        rule.phrase,
                        describeScope(rule) + " - " + rule.action.label(this).lowercase(),
                        trailing = FocusUi.switchControl(this, tokens, rule.enabled) { value ->
                            if (!KeywordRules.update(this, rule.copy(enabled = value))) {
                                FocusDialog.toast(this, SessionLock.refusalMessage(this))
                            }
                            refresh()
                        }
                    ) { editRule(rule) }
                )
                if (index < rules.size - 1) card.addView(FocusUi.divider(this, tokens))
            }
        }

        card.addView(FocusUi.spacer(this, 12))
        card.addView(FocusUi.primaryButton(this, tokens, getString(R.string.keyword_guard_add_word_or_phrase)) { addRule(null) })
    }

    private fun describeScope(rule: KeywordRule): String =
        if (rule.appliesEverywhere) {
            getString(R.string.common_every_app)
        } else if (rule.packages.size == 1) {
            AppCatalog.label(this, rule.packages.first())
        } else {
            getString(R.string.keyword_guard_apps_count, rule.packages.size)
        }

    // ── Editing ───────────────────────────────────────────────────

    private fun addRule(group: String?) {
        // Adding a phrase to watch for is a tightening. KeywordRules.save judges it.
        FocusDialog.textInput(
            this,
            title = getString(R.string.keyword_guard_watch_title),
            subtitle = getString(R.string.keyword_guard_watch_subtitle),
            hint = getString(R.string.keyword_guard_watch_hint),
            confirmLabel = getString(R.string.common_next)
        ) { phrase ->
            if (phrase.isBlank()) return@textInput
            val rule = KeywordRules.newRule(phrase = phrase, group = group)
            KeywordRules.add(this, rule)
            refresh()
            editRule(rule)
        }
    }

    private fun editRule(rule: KeywordRule) {
        // Opening the editor is free; KeywordRules.save refuses an edit that loosens.
        var working = rule

        FocusDialog.custom(
            this,
            title = rule.phrase,
            subtitle = getString(R.string.keyword_guard_edit_subtitle),
            confirmLabel = getString(R.string.common_save),
            cancelLabel = getString(R.string.common_cancel),
            onConfirm = {
                KeywordRules.update(this, working)
                refresh()
            }
        ) { body, dialogTokens, refreshBody ->
            body.addView(FocusUi.caption(this, dialogTokens, getString(R.string.keyword_guard_caption_where)))
            body.addView(
                FocusUi.listRow(
                    this,
                    dialogTokens,
                    if (working.appliesEverywhere) getString(R.string.common_every_app) else describeScope(working),
                    getString(R.string.keyword_guard_tap_choose_apps),
                    trailing = FocusUi.chevron(this, dialogTokens)
                ) {
                    pickApps(
                        title = getString(R.string.keyword_guard_watch_phrase_in, working.phrase),
                        subtitle = getString(R.string.keyword_guard_leave_unticked),
                        selected = working.packages
                    ) { selected ->
                        working = working.copy(packages = selected)
                        KeywordRules.update(this, working)
                        refreshBody()
                    }
                }
            )

            body.addView(FocusUi.divider(this, dialogTokens, 8))
            body.addView(FocusUi.caption(this, dialogTokens, getString(R.string.keyword_guard_caption_what_happens)))
            GuardAction.values().forEach { action ->
                val marker = FocusUi.pill(
                    this,
                    dialogTokens,
                    if (action == working.action) getString(R.string.common_now) else getString(R.string.common_set),
                    if (action == working.action) dialogTokens.accent else dialogTokens.textMuted
                )
                body.addView(
                    FocusUi.listRow(this, dialogTokens, action.label(this), action.blurb(this), trailing = marker) {
                        working = working.copy(action = action)
                        KeywordRules.update(this, working)
                        refreshBody()
                    }
                )
            }

            body.addView(FocusUi.divider(this, dialogTokens, 8))
            body.addView(
                FocusUi.dangerButton(this, dialogTokens, getString(R.string.keyword_guard_delete_phrase)) {
                    KeywordRules.remove(this, working.id)
                    refresh()
                }
            )
        }
    }

    /**
     * Telegram, by allowlist.
     *
     * The rest of this screen names things to keep out. This one names the
     * handful to let in, because listing every channel worth avoiding is a
     * losing game and listing the three chats that matter is a minute's work.
     */
    private fun buildTelegramCard(): View = card { card ->
        val on = CapabilityRegistry.isEnabled(this, Capabilities.TELEGRAM_GUARD)
        val frozen = SessionLock.isFrozen(this)

        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.keyword_guard_telegram_title),
                getString(R.string.keyword_guard_telegram_subtitle),
                on,
                enabled = !frozen
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.TELEGRAM_GUARD, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )

        if (!on) return@card

        val allowed = TelegramGuard.allowedRaw(this)

        card.addView(FocusUi.spacer(this, 6))
        card.addView(
            FocusUi.caption(
                this,
                tokens,
                if (allowed.isEmpty()) {
                    getString(R.string.keyword_guard_telegram_empty)
                } else {
                    getString(R.string.keyword_guard_telegram_nonempty)
                }
            )
        )

        allowed.forEach { title ->
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    title,
                    null,
                    trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_remove)) {
                        if (!TelegramGuard.removeAllowed(this, title)) {
                            FocusDialog.toast(this, SessionLock.refusalMessage(this))
                        }
                        refresh()
                    }
                )
            )
        }

        card.addView(FocusUi.spacer(this, 10))

        // Offering the chat they were just in beats asking them to type a name
        // exactly, and it doubles as proof the guard can still read Telegram
        // at all after an update.
        val seen = TelegramGuard.lastSeenTitle(this)
        if (seen != null && allowed.none { it.equals(seen, ignoreCase = true) }) {
            card.addView(
                FocusUi.secondaryButton(this, tokens, getString(R.string.keyword_guard_allow_chat, seen)) {
                    if (!TelegramGuard.addAllowed(this, seen)) {
                        FocusDialog.toast(this, SessionLock.refusalMessage(this))
                    }
                    refresh()
                }
            )
            card.addView(FocusUi.spacer(this, 8))
        }

        card.addView(
            FocusUi.secondaryButton(this, tokens, getString(R.string.keyword_guard_add_chat_by_name)) {
                FocusDialog.textInput(
                    this,
                    title = getString(R.string.keyword_guard_allow_chat_title),
                    subtitle = getString(R.string.keyword_guard_allow_chat_subtitle),
                    hint = getString(R.string.keyword_guard_allow_chat_hint)
                ) { value ->
                    if (!TelegramGuard.addAllowed(this, value)) {
                        FocusDialog.toast(this, SessionLock.refusalMessage(this))
                    }
                    refresh()
                }
            }
        )

        if (seen == null) {
            card.addView(FocusUi.spacer(this, 8))
            card.addView(FocusUi.caption(this, tokens, getString(R.string.keyword_guard_telegram_not_read)))
        }
    }

    private fun buildExceptions(): View = card { card ->
        val exceptions = KeywordRules.exceptions(this)

        card.addView(FocusUi.secondary(this, tokens, getString(R.string.keyword_guard_exceptions_intro)))
        card.addView(FocusUi.spacer(this, 8))

        if (exceptions.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.keyword_guard_no_exceptions)))
        } else {
            exceptions.forEach { phrase ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        phrase,
                        null,
                        trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_remove)) {
                            if (!KeywordRules.setExceptions(this, exceptions - phrase)) {
                                FocusDialog.toast(this, SessionLock.refusalMessage(this))
                            }
                            refresh()
                        }
                    )
                )
            }
        }

        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.smallButton(this, tokens, getString(R.string.keyword_guard_add_exception)) {
                if (SessionLock.isFrozen(this)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                    return@smallButton
                }
                FocusDialog.textInput(
                    this,
                    title = getString(R.string.keyword_guard_never_match_title),
                    subtitle = null,
                    hint = getString(R.string.keyword_guard_phrase_hint)
                ) { phrase ->
                    if (phrase.isNotBlank()) {
                        if (!KeywordRules.setExceptions(this, exceptions + phrase)) {
                            FocusDialog.toast(this, SessionLock.refusalMessage(this))
                        }
                        refresh()
                    }
                }
            }
        )
    }
}
