package com.focuslock.mdm

import android.view.View
import android.widget.LinearLayout
import java.util.Calendar

/**
 * Schedule windows.
 *
 * A window is a stretch of the week where the phone goes quiet on its own, with
 * no session to remember to start. Every window says what is still allowed
 * inside it, and always-allowed apps are open in all of them, so nobody
 * discovers at 3pm that they scheduled themselves out of a phone call.
 */
class ScheduleActivity : FocusScreenActivity() {

    override fun screenTitle(): String = getString(R.string.schedule_title)

    override fun screenSubtitle(): String = getString(R.string.schedule_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildToggle())
        if (!CapabilityRegistry.isEnabled(this, Capabilities.SCHEDULES)) return

        column.addView(sectionLabel(getString(R.string.schedule_section_windows)))
        column.addView(buildWindowList())
        column.addView(sectionLabel(getString(R.string.schedule_section_plan)))
        column.addView(buildPlanCard())
    }

    private fun buildToggle(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.schedule_title),
                getString(R.string.schedule_toggle_subtitle),
                CapabilityRegistry.isEnabled(this, Capabilities.SCHEDULES)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.SCHEDULES, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        )

        ScheduleManager.activeWindowIfEnabled(this)?.let { window ->
            card.addView(FocusUi.spacer(this, 8))
            val label = if (window.overlay) {
                getString(R.string.schedule_overlaying_until, ScheduleManager.formatTime(this, window.endMinutes))
            } else {
                getString(R.string.schedule_running_until, ScheduleManager.formatTime(this, window.endMinutes))
            }
            card.addView(FocusUi.pill(this, tokens, label, if (window.overlay) tokens.warning else tokens.accent))
        }

        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.common_always_allowed_apps_title),
                getString(R.string.schedule_always_allowed_subtitle, AppRules.alwaysAllowedRaw(this).size),
                trailing = FocusUi.chevron(this, tokens)
            ) {
                startActivity(android.content.Intent(this, AlwaysAllowedActivity::class.java))
            }
        )
    }

    private fun buildWindowList(): View = card { card ->
        val schedules = ScheduleManager.getSchedules(this)

        if (schedules.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, Copy.emptySchedules(this)))
        } else {
            val active = ScheduleManager.activeWindowIfEnabled(this)?.id
            schedules.forEachIndexed { index, schedule ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        getString(
                            R.string.schedule_window_time_range,
                            ScheduleManager.formatTime(this, schedule.startMinutes),
                            ScheduleManager.formatTime(this, schedule.endMinutes)
                        ),
                        describe(schedule),
                        trailing = if (schedule.id == active) {
                            FocusUi.pill(this, tokens, getString(R.string.common_now), tokens.accent)
                        } else {
                            FocusUi.chevron(this, tokens)
                        }
                    ) { editWindow(schedule) }
                )
                if (index < schedules.size - 1) card.addView(FocusUi.divider(this, tokens))
            }
        }

        card.addView(FocusUi.spacer(this, 12))
        card.addView(FocusUi.primaryButton(this, tokens, getString(R.string.schedule_add_window)) { editWindow(null) })
    }

    private fun describe(schedule: ScheduleWindow): String {
        val repeat = when (schedule.repeat) {
            RepeatType.DAILY -> getString(R.string.common_every_day)
            RepeatType.WEEKLY ->
                if (schedule.daysOfWeek.isEmpty()) {
                    getString(R.string.common_every_day)
                } else {
                    schedule.daysOfWeek.sorted().joinToString { dayName(it) }
                }
            RepeatType.MONTHLY -> getString(R.string.schedule_monthly_repeat, schedule.dayOfMonth)
        }
        val extras = if (schedule.allowedApps.isEmpty()) {
            getString(R.string.schedule_extras_essentials_only)
        } else {
            getString(R.string.schedule_extras_count, schedule.allowedApps.size)
        }
        val overlay = if (schedule.overlay) " · " + getString(R.string.schedule_overlay_marker) else ""
        val message = if (schedule.message.isBlank()) "" else schedule.message + " · "
        return message + repeat + " · " + extras + overlay
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

    // ── Editing ───────────────────────────────────────────────────

    /**
     * Edited on its own screen now, not a dialog - see [ScheduleEditorActivity].
     * [SessionLock] is still checked here rather than only in the editor, so
     * the "Add a window" button itself gives the refusal message instead of
     * opening a screen whose Save could never actually take effect.
     */
    private fun editWindow(existing: ScheduleWindow?) {
        // Adding a quiet window mid-session is a tightening. ScheduleManager judges the save.
        startActivity(
            android.content.Intent(this, ScheduleEditorActivity::class.java).apply {
                if (existing != null) putExtra(ScheduleEditorActivity.EXTRA_SCHEDULE_ID, existing.id)
            }
        )
    }

    // ── Plan ──────────────────────────────────────────────────────

    /** A place to write down what the windows are actually for. */
    private fun buildPlanCard(): View = card { card ->
        card.addView(FocusUi.secondary(this, tokens, getString(R.string.schedule_plan_intro)))
        card.addView(FocusUi.spacer(this, 10))

        val field = FocusUi.input(
            this,
            tokens,
            getString(R.string.schedule_plan_hint),
            FocusStore.getString(this, Constants.KEY_PLAN_TEXT, ""),
            multiline = true
        )
        card.addView(field)
        card.addView(
            FocusUi.secondaryButton(this, tokens, getString(R.string.schedule_save_plan_button)) {
                FocusStore.setString(this, Constants.KEY_PLAN_TEXT, field.text.toString())
                FocusDialog.toast(this, getString(R.string.common_saved_toast))
            }
        )
    }
}
