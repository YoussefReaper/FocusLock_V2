package com.focuslock.mdm

import android.view.View
import android.widget.LinearLayout
import java.util.Calendar

/**
 * Custom rules: target, condition, outcome.
 *
 * Deliberately first-match-wins, top to bottom, with explicit move up and move
 * down. Priority systems people cannot see are priority systems people get
 * wrong, and a blocker that behaves unpredictably is one that gets blamed and
 * then removed.
 */
class RuleEditorActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_rules

    override fun screenTitle(): String = getString(R.string.rule_editor_title)

    override fun screenSubtitle(): String = getString(R.string.rule_editor_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildToggle())
        if (!CapabilityRegistry.isEnabled(this, Capabilities.RULE_ENGINE)) return

        column.addView(sectionLabel(getString(R.string.rule_editor_section_your_rules)))
        column.addView(buildList())
        column.addView(sectionLabel(getString(R.string.rule_editor_section_examples)))
        column.addView(buildTemplates())
    }

    private fun buildToggle(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.rule_editor_toggle_title),
                getString(R.string.rule_editor_toggle_subtitle),
                CapabilityRegistry.isEnabled(this, Capabilities.RULE_ENGINE)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.RULE_ENGINE, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )
    }

    private fun buildList(): View = card { card ->
        val rules = RuleStore.all(this)
        val frozen = SessionLock.isFrozen(this)

        if (rules.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, Copy.emptyRules(this)))
        } else {
            rules.forEachIndexed { index, rule ->
                val controls = FocusUi.row(this)
                controls.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // Reordering has no useful "refused" state to explain, so it
                // simply isn't offered while frozen rather than toasting on
                // every tap.
                if (index > 0 && !frozen) {
                    controls.addView(
                        FocusUi.smallButton(this, tokens, "↑") {
                            RuleStore.move(this, rule.id, -1)
                            refresh()
                        }
                    )
                    controls.addView(FocusUi.spacerH(this, 8))
                }
                if (index < rules.size - 1 && !frozen) {
                    controls.addView(
                        FocusUi.smallButton(this, tokens, "↓") {
                            RuleStore.move(this, rule.id, 1)
                            refresh()
                        }
                    )
                    controls.addView(FocusUi.spacerH(this, 8))
                }
                controls.addView(
                    FocusUi.switchControl(this, tokens, rule.enabled) { value ->
                        if (!RuleStore.update(this, rule.copy(enabled = value))) {
                            FocusDialog.toast(this, SessionLock.refusalMessage(this))
                        }
                        refresh()
                    }
                )

                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        getString(R.string.rule_editor_indexed_row_title, index + 1, describeRule(rule)),
                        describeCondition(rule),
                        trailing = controls
                    ) { editRule(rule) }
                )
                if (index < rules.size - 1) card.addView(FocusUi.divider(this, tokens))
            }
        }

        card.addView(FocusUi.spacer(this, 12))
        card.addView(FocusUi.primaryButton(this, tokens, getString(R.string.rule_editor_new_rule)) { createRule() })
    }

    private fun describeRule(rule: Rule): String {
        if (rule.label.isNotBlank()) return rule.label
        val target = when (rule.targetType) {
            RuleTargetType.ALL -> getString(R.string.rule_editor_target_everything)
            RuleTargetType.APP -> AppCatalog.label(this, rule.targetValue)
            RuleTargetType.CATEGORY -> AppCategory.fromId(rule.targetValue).label(this)
        }
        return getString(R.string.rule_editor_describe_rule, target, rule.action.label(this).lowercase())
    }

    private fun describeCondition(rule: Rule): String = when (rule.conditionType) {
        RuleConditionType.ALWAYS -> getString(R.string.rule_editor_condition_always)
        RuleConditionType.SESSION_ONLY -> getString(R.string.rule_editor_condition_session_only)
        RuleConditionType.TIME ->
            getString(
                R.string.rule_editor_condition_time,
                ScheduleManager.formatTime(this, rule.conditionStart),
                ScheduleManager.formatTime(this, rule.conditionEnd)
            )
        RuleConditionType.DAYS ->
            if (rule.conditionDays.isEmpty()) {
                getString(R.string.rule_editor_condition_any_day)
            } else {
                rule.conditionDays.joinToString { dayName(it) }
            }
        RuleConditionType.PLACE ->
            getString(
                R.string.rule_editor_condition_at_place,
                PlaceRules.all(this).firstOrNull { it.id == rule.conditionValue }?.label
                    ?: getString(R.string.rule_editor_a_place_fallback)
            )
        RuleConditionType.WIFI -> getString(R.string.rule_editor_condition_wifi, rule.conditionValue)
        RuleConditionType.USAGE_OVER -> getString(R.string.rule_editor_condition_usage_over, rule.conditionNumber)
        RuleConditionType.OPENS_OVER -> getString(R.string.rule_editor_condition_opens_over, rule.conditionNumber)
    }

    private fun dayName(day: Int): String = when (day) {
        Calendar.MONDAY -> getString(R.string.common_day_mon)
        Calendar.TUESDAY -> getString(R.string.common_day_tue)
        Calendar.WEDNESDAY -> getString(R.string.common_day_wed)
        Calendar.THURSDAY -> getString(R.string.common_day_thu)
        Calendar.FRIDAY -> getString(R.string.common_day_fri)
        Calendar.SATURDAY -> getString(R.string.common_day_sat)
        else -> getString(R.string.common_day_sun)
    }

    // ── Creating ──────────────────────────────────────────────────

    private fun createRule() {
        // A new rule is usually a tightening; RuleStore.save works out the direction
        // of this particular one and refuses only if it actually weakens something.
        val rule = RuleStore.newRule(
            label = "",
            targetType = RuleTargetType.CATEGORY,
            targetValue = AppCategory.SOCIAL.id,
            conditionType = RuleConditionType.ALWAYS,
            action = RuleAction.BLOCK
        )
        RuleStore.add(this, rule)
        refresh()
        editRule(rule)
    }

    /**
     * Ready-made rules for the situations people actually describe. Each one is
     * added as an ordinary editable rule, never as hidden behaviour.
     */
    private fun buildTemplates(): View = card { card ->
        val templates = listOf(
            Template(
                getString(R.string.rule_editor_template_school_hours),
                RuleTargetType.CATEGORY,
                AppCategory.SOCIAL.id,
                RuleConditionType.TIME,
                RuleAction.BLOCK,
                8 * 60,
                15 * 60
            ),
            Template(
                getString(R.string.rule_editor_template_pause_video),
                RuleTargetType.CATEGORY,
                AppCategory.VIDEO.id,
                RuleConditionType.USAGE_OVER,
                RuleAction.FRICTION,
                0,
                0,
                30
            ),
            Template(
                getString(R.string.rule_editor_template_tenth_open),
                RuleTargetType.CATEGORY,
                AppCategory.SOCIAL.id,
                RuleConditionType.OPENS_OVER,
                RuleAction.ALLOW_TEMP,
                0,
                0,
                10
            )
        )

        templates.forEach { template ->
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    template.label,
                    getString(R.string.rule_editor_adds_editable_rule),
                    trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_add)) { applyTemplate(template) }
                ) { applyTemplate(template) }
            )
        }
    }

    private data class Template(
        val label: String,
        val targetType: RuleTargetType,
        val targetValue: String,
        val conditionType: RuleConditionType,
        val action: RuleAction,
        val start: Int = 0,
        val end: Int = 0,
        val number: Int = 0
    )

    private fun applyTemplate(template: Template) {
        // Same as createRule: the store judges the rule that comes out of it.
        val rule = RuleStore.newRule(
            label = template.label,
            targetType = template.targetType,
            targetValue = template.targetValue,
            conditionType = template.conditionType,
            action = template.action
        ).copy(
            conditionStart = if (template.start > 0) template.start else 9 * 60,
            conditionEnd = if (template.end > 0) template.end else 17 * 60,
            conditionNumber = if (template.number > 0) template.number else 30
        )
        RuleStore.add(this, rule)
        refresh()
        FocusDialog.toast(this, getString(R.string.rule_editor_rule_added_toast))
    }

    // ── Editing ───────────────────────────────────────────────────

    private fun editRule(rule: Rule) {
        // Opening the editor is free; RuleStore.save refuses an edit that loosens.
        var working = rule

        FocusDialog.custom(
            this,
            title = if (rule.label.isBlank()) getString(R.string.rule_editor_untitled_rule) else rule.label,
            subtitle = getString(R.string.rule_editor_edit_subtitle),
            confirmLabel = getString(R.string.common_save),
            cancelLabel = getString(R.string.common_cancel),
            onConfirm = {
                RuleStore.update(this, working)
                refresh()
            }
        ) { body, dialogTokens, refreshBody ->

            body.addView(
                FocusUi.listRow(
                    this,
                    dialogTokens,
                    getString(R.string.rule_editor_name_label),
                    working.label.ifBlank { getString(R.string.rule_editor_unnamed) },
                    trailing = FocusUi.chevron(this, dialogTokens)
                ) {
                    FocusDialog.textInput(
                        this,
                        getString(R.string.rule_editor_name_rule_title),
                        getString(R.string.rule_editor_name_rule_subtitle),
                        getString(R.string.common_name_hint),
                        working.label
                    ) { value ->
                        working = working.copy(label = value)
                        RuleStore.update(this, working)
                        refreshBody()
                    }
                }
            )

            body.addView(FocusUi.divider(this, dialogTokens, 8))
            body.addView(FocusUi.caption(this, dialogTokens, getString(R.string.rule_editor_caption_covers)))
            body.addView(
                FocusUi.listRow(
                    this,
                    dialogTokens,
                    describeTarget(working),
                    getString(R.string.common_tap_to_change),
                    trailing = FocusUi.chevron(this, dialogTokens)
                ) { pickTarget(working) { updated -> working = updated; refreshBody() } }
            )

            body.addView(FocusUi.divider(this, dialogTokens, 8))
            body.addView(FocusUi.caption(this, dialogTokens, getString(R.string.rule_editor_caption_when)))
            body.addView(
                FocusUi.listRow(
                    this,
                    dialogTokens,
                    working.conditionType.label(this),
                    describeCondition(working),
                    trailing = FocusUi.chevron(this, dialogTokens)
                ) { pickCondition(working) { updated -> working = updated; refreshBody() } }
            )

            body.addView(FocusUi.divider(this, dialogTokens, 8))
            body.addView(FocusUi.caption(this, dialogTokens, getString(R.string.rule_editor_caption_what_happens)))
            RuleAction.values().forEach { action ->
                val marker = FocusUi.pill(
                    this,
                    dialogTokens,
                    if (action == working.action) getString(R.string.common_now) else getString(R.string.common_set),
                    if (action == working.action) dialogTokens.accent else dialogTokens.textMuted
                )
                body.addView(
                    FocusUi.listRow(this, dialogTokens, action.label(this), action.blurb(this), trailing = marker) {
                        working = working.copy(action = action)
                        RuleStore.update(this, working)
                        refreshBody()
                    }
                )
            }

            if (working.action == RuleAction.ALLOW_TEMP) {
                body.addView(FocusUi.spacer(this, 6))
                body.addView(
                    FocusUi.sliderRow(
                        this,
                        dialogTokens,
                        getString(R.string.rule_editor_allow_minutes_label),
                        1,
                        30,
                        working.actionMinutes.coerceIn(1, 30),
                        { getString(R.string.task_editor_minutes_value, it) }
                    ) { mins ->
                        working = working.copy(actionMinutes = mins)
                        RuleStore.update(this, working)
                    }
                )
            }

            body.addView(FocusUi.divider(this, dialogTokens, 8))
            body.addView(
                FocusUi.dangerButton(this, dialogTokens, getString(R.string.rule_editor_delete_rule)) {
                    RuleStore.remove(this, working.id)
                    refresh()
                }
            )
        }
    }

    private fun describeTarget(rule: Rule): String = when (rule.targetType) {
        RuleTargetType.ALL -> getString(R.string.rule_editor_every_app)
        RuleTargetType.APP -> AppCatalog.label(this, rule.targetValue)
        RuleTargetType.CATEGORY -> getString(R.string.rule_editor_category_apps_suffix, AppCategory.fromId(rule.targetValue).label(this))
    }

    private fun pickTarget(rule: Rule, onChange: (Rule) -> Unit) {
        val choices = listOf(
            FocusDialog.Choice(
                "all",
                getString(R.string.rule_editor_every_app),
                getString(R.string.rule_editor_every_app_subtitle)
            )
        ) +
            AppCategory.ruleTargets.map { category ->
                FocusDialog.Choice(
                    "cat:" + category.id,
                    getString(R.string.rule_editor_category_apps_suffix, category.label(this)),
                    category.blurb(this)
                )
            } +
            AppCatalog.launchable(this).map { app ->
                FocusDialog.Choice("app:" + app.packageName, app.label, app.category.label(this), app.packageName)
            }

        FocusDialog.singleChoice(this, getString(R.string.rule_editor_pick_target_title), null, choices, null) { key ->
            val updated = when {
                key == "all" -> rule.copy(targetType = RuleTargetType.ALL, targetValue = "")
                key.startsWith("cat:") ->
                    rule.copy(targetType = RuleTargetType.CATEGORY, targetValue = key.removePrefix("cat:"))
                else ->
                    rule.copy(targetType = RuleTargetType.APP, targetValue = key.removePrefix("app:"))
            }
            RuleStore.update(this, updated)
            onChange(updated)
            refresh()
        }
    }

    private fun pickCondition(rule: Rule, onChange: (Rule) -> Unit) {
        val choices = RuleConditionType.values()
            .filter { it != RuleConditionType.SESSION_ONLY }
            .map { type ->
                FocusDialog.Choice(type.id, type.label(this), type.blurb(this))
            }

        FocusDialog.singleChoice(this, getString(R.string.rule_editor_pick_condition_title), null, choices, rule.conditionType.id) { key ->
            val type = RuleConditionType.fromId(key)
            var updated = rule.copy(conditionType = type)
            RuleStore.update(this, updated)
            onChange(updated)

            when (type) {
                RuleConditionType.TIME -> FocusDialog.timePicker(this, getString(R.string.common_starts_at), rule.conditionStart) { start ->
                    FocusDialog.timePicker(this, getString(R.string.common_ends_at), rule.conditionEnd) { end ->
                        updated = updated.copy(conditionStart = start, conditionEnd = end)
                        RuleStore.update(this, updated)
                        onChange(updated)
                        refresh()
                    }
                }
                RuleConditionType.USAGE_OVER, RuleConditionType.OPENS_OVER -> FocusDialog.textInput(
                    this,
                    if (type == RuleConditionType.USAGE_OVER) {
                        getString(R.string.rule_editor_after_minutes_title)
                    } else {
                        getString(R.string.rule_editor_after_opens_title)
                    },
                    null,
                    getString(R.string.common_number_hint),
                    rule.conditionNumber.toString(),
                    numeric = true
                ) { value ->
                    updated = updated.copy(conditionNumber = value.toIntOrNull() ?: 30)
                    RuleStore.update(this, updated)
                    onChange(updated)
                    refresh()
                }
                RuleConditionType.PLACE -> {
                    val places = PlaceRules.all(this)
                    if (places.isEmpty()) {
                        FocusDialog.info(
                            this,
                            getString(R.string.rule_editor_no_places_title),
                            getString(R.string.rule_editor_no_places_message)
                        )
                    } else {
                        FocusDialog.singleChoice(
                            this,
                            getString(R.string.rule_editor_which_place_title),
                            null,
                            places.map { FocusDialog.Choice(it.id, it.label) },
                            rule.conditionValue
                        ) { placeId ->
                            updated = updated.copy(conditionValue = placeId)
                            RuleStore.update(this, updated)
                            onChange(updated)
                            refresh()
                        }
                    }
                }
                RuleConditionType.WIFI -> FocusDialog.textInput(
                    this,
                    getString(R.string.rule_editor_which_network_title),
                    PlaceRules.currentWifiSsid(this)?.let { getString(R.string.rule_editor_currently_on, it) },
                    getString(R.string.rule_editor_wifi_name_hint),
                    rule.conditionValue.ifBlank { PlaceRules.currentWifiSsid(this).orEmpty() }
                ) { value ->
                    updated = updated.copy(conditionValue = value)
                    RuleStore.update(this, updated)
                    onChange(updated)
                    refresh()
                }
                RuleConditionType.DAYS -> pickDays(updated) { result ->
                    updated = result
                    onChange(updated)
                    refresh()
                }
                else -> refresh()
            }
        }
    }

    private fun pickDays(rule: Rule, onChange: (Rule) -> Unit) {
        val days = listOf(
            Calendar.MONDAY to getString(R.string.common_day_monday),
            Calendar.TUESDAY to getString(R.string.common_day_tuesday),
            Calendar.WEDNESDAY to getString(R.string.common_day_wednesday),
            Calendar.THURSDAY to getString(R.string.common_day_thursday),
            Calendar.FRIDAY to getString(R.string.common_day_friday),
            Calendar.SATURDAY to getString(R.string.common_day_saturday),
            Calendar.SUNDAY to getString(R.string.common_day_sunday)
        )
        FocusDialog.multiChoice(
            this,
            getString(R.string.common_which_days_title),
            getString(R.string.rule_editor_pick_days_subtitle),
            days.map { FocusDialog.Choice(it.first.toString(), it.second) },
            rule.conditionDays.map { it.toString() }.toSet()
        ) { selected ->
            val updated = rule.copy(conditionDays = selected.mapNotNull { it.toIntOrNull() }.toSet())
            RuleStore.update(this, updated)
            onChange(updated)
        }
    }
}
