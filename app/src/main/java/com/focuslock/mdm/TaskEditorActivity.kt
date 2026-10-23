package com.focuslock.mdm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The task panel.
 *
 * Comprehensive on purpose. A task app that only holds a title becomes a second,
 * worse place to keep todos and gets abandoned within a week, so this carries
 * the full field set people expect from Todoist or TickTick — notes, subtasks,
 * priority, a due date and a separate hard deadline, tags, recurrence, a time
 * estimate, local attachments, a reminder — plus the four fields Earn Mode adds:
 * what counts as done, which apps the task may use, what it pays, and whether it
 * should pay at all.
 *
 * Everything saves as you go. There is no "unsaved changes" state to lose.
 */
class TaskEditorActivity : FocusEditorActivity() {

    private var task: FocusTask = FocusTaskStore.newTask("")
    private var isNew = true

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri: Uri = result.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some providers refuse a persistable grant; the reference still works this session.
        }
        val kind = if (contentResolver.getType(uri)?.startsWith("image/") == true) {
            AttachmentKind.PHOTO
        } else {
            AttachmentKind.FILE
        }
        mutate { it.copy(attachments = it.attachments + Attachment(kind, uri.toString(), fileLabel(uri))) }
    }

    override fun editorTitle(): String = if (isNew) getString(R.string.task_editor_title_new) else getString(R.string.task_editor_title_existing)

    /** Grayed out until there's a title to save - the header Save button replaces the old buried one, so this is the only place that guard lives now. */
    override fun canSave(): Boolean = task.title.isNotBlank()

    override fun onSave() {
        mutate { it }
        FocusDialog.toast(this, getString(R.string.task_editor_toast_saved))
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val id = intent.getStringExtra(EXTRA_TASK_ID)
        if (id != null) {
            val existing = FocusTaskStore.find(this, id)
            if (existing != null) {
                task = existing
                isNew = false
            }
        }
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Re-read: a session or a subtask ticked elsewhere may have moved on.
        if (!isNew) FocusTaskStore.find(this, task.id)?.let { task = it }
    }

    /** Single write path, so nothing can drift between the screen and the store. */
    private fun mutate(change: (FocusTask) -> FocusTask) {
        task = change(task)
        if (isNew) {
            if (task.title.isBlank()) return
            FocusTaskStore.add(this, task)
            isNew = false
        } else {
            FocusTaskStore.update(this, task)
        }
        AppBlockerService.startIfNeeded(this)
        refresh()
    }

    override fun buildContent(column: LinearLayout) {
        column.addView(buildBasics())
        column.addView(sectionLabel(getString(R.string.task_editor_section_steps)))
        column.addView(buildSubtasks())
        column.addView(sectionLabel(getString(R.string.task_editor_section_when)))
        column.addView(buildScheduling())
        column.addView(sectionLabel(getString(R.string.task_editor_section_details)))
        column.addView(buildDetails())

        if (EarnMode.isEnabled(this)) {
            column.addView(sectionLabel(getString(R.string.task_editor_section_earning)))
            column.addView(buildEarnCard())
        }

        column.addView(sectionLabel(getString(R.string.task_editor_section_attachments)))
        column.addView(buildAttachments())

        if (!isNew) {
            column.addView(FocusUi.spacer(this, 10))
            column.addView(buildActions())
        }

        column.addView(FocusUi.spacer(this, 14))
        val footer = FocusUi.caption(this, tokens, Copy.onDeviceFooter(this))
        footer.gravity = android.view.Gravity.CENTER
        column.addView(footer)
    }

    // ── Basics ────────────────────────────────────────────────────

    private fun buildBasics(): View = card { card ->
        val titleField = FocusUi.input(this, tokens, getString(R.string.task_editor_title_hint), task.title)
        titleField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                task = task.copy(title = s?.toString().orEmpty())
            }
        })
        card.addView(titleField)

        val notesField = FocusUi.input(this, tokens, getString(R.string.task_editor_notes_hint), task.notes, multiline = true)
        notesField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                task = task.copy(notes = s?.toString().orEmpty())
            }
        })
        card.addView(notesField)
    }

    // ── Subtasks ──────────────────────────────────────────────────

    private fun buildSubtasks(): View = card { card ->
        if (task.subtasks.isEmpty()) {
            card.addView(
                FocusUi.emptyState(
                    this,
                    tokens,
                    getString(R.string.task_editor_no_steps)
                )
            )
        } else {
            task.subtasks.forEach { subtask ->
                val toggle = FocusUi.switchControl(this, tokens, subtask.done) { done ->
                    mutate { current ->
                        current.copy(
                            subtasks = current.subtasks.map {
                                if (it.id == subtask.id) it.copy(done = done) else it
                            }
                        )
                    }
                }
                card.addView(
                    FocusUi.listRow(this, tokens, subtask.title, null, trailing = toggle) {
                        confirmRemoveSubtask(subtask)
                    }
                )
            }
            card.addView(
                FocusUi.meter(
                    this,
                    tokens,
                    getString(R.string.task_editor_steps_meter, task.subtasks.count { it.done }, task.subtasks.size),
                    getString(R.string.task_editor_percent_value, task.progressPercent),
                    task.progressPercent / 100f,
                    tokens.accent
                )
            )
        }

        card.addView(FocusUi.spacer(this, 8))
        card.addView(buildAddStepField())
    }

    /**
     * A step types itself in, rather than opening a dialog to add one - the
     * doc's "edit in place" rule applied to the one thing on this screen that
     * still handed off to a sheet. Enter (or the keyboard's own submit
     * action) adds it and clears the field for the next one immediately.
     */
    private fun buildAddStepField(): View {
        val field = FocusUi.input(this, tokens, getString(R.string.task_editor_add_step_hint))
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        field.setSingleLine(true)
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val value = field.text.toString()
                if (value.isNotBlank()) {
                    mutate { it.copy(subtasks = it.subtasks + FocusTaskStore.newSubtask(value)) }
                }
                true
            } else {
                false
            }
        }
        return field
    }

    private fun confirmRemoveSubtask(subtask: Subtask) {
        FocusDialog.alert(
            this,
            title = subtask.title,
            message = getString(R.string.task_editor_remove_step_message),
            confirmLabel = getString(R.string.task_editor_remove_step_confirm),
            cancelLabel = getString(R.string.task_editor_remove_step_cancel),
            destructive = true,
            onConfirm = {
                mutate { current -> current.copy(subtasks = current.subtasks.filterNot { it.id == subtask.id }) }
            }
        )
    }

    // ── When ──────────────────────────────────────────────────────

    /**
     * Due date and deadline are separate on purpose. "I meant to do this today"
     * and "after this it is pointless" are different facts, and only the second
     * should ever mark something missed.
     */
    private fun buildScheduling(): View = card { card ->
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.task_editor_planned_for_label),
                task.dueDate?.let { formatWhen(it) } ?: getString(R.string.task_editor_planned_for_empty),
                trailing = FocusUi.chevron(this, tokens)
            ) {
                FocusDialog.dateTimePicker(this, getString(R.string.task_editor_planned_for_label), task.dueDate) { value ->
                    mutate { it.copy(dueDate = value) }
                }
            }
        )
        card.addView(FocusUi.divider(this, tokens))
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.task_editor_deadline_label),
                task.deadline?.let { formatWhen(it) } ?: getString(R.string.task_editor_deadline_empty),
                trailing = FocusUi.chevron(this, tokens)
            ) {
                FocusDialog.dateTimePicker(this, getString(R.string.task_editor_deadline_label), task.deadline) { value ->
                    mutate { it.copy(deadline = value) }
                }
            }
        )
        card.addView(FocusUi.divider(this, tokens))

        card.addView(FocusUi.caption(this, tokens, getString(R.string.task_editor_repeats_caption)))
        card.addView(
            FocusUi.chipStrip(
                this,
                tokens,
                Recurrence.values().map { it.label(this) },
                Recurrence.values().indexOf(task.recurrence)
            ) { index ->
                mutate { it.copy(recurrence = Recurrence.values()[index]) }
            }
        )
        if (task.recurrence == Recurrence.CUSTOM) {
            card.addView(
                FocusUi.sliderRow(
                    this,
                    tokens,
                    getString(R.string.task_editor_every_label),
                    2,
                    30,
                    task.recurrenceEveryDays,
                    { getString(R.string.task_editor_every_days_value, it) }
                ) { value -> task = task.copy(recurrenceEveryDays = value) }
            )
            card.addView(
                FocusUi.smallButton(this, tokens, getString(R.string.task_editor_save_interval_button)) { mutate { it } }
            )
        }
    }

    private fun formatWhen(ms: Long): String =
        TimeText.dateTime(this, ms, withWeekday = true)

    // ── Details ───────────────────────────────────────────────────

    private fun buildDetails(): View = card { card ->
        card.addView(FocusUi.caption(this, tokens, getString(R.string.task_editor_priority_caption)))
        card.addView(
            FocusUi.chipStrip(
                this,
                tokens,
                Priority.values().map { it.label(this) },
                Priority.values().indexOf(task.priority)
            ) { index ->
                mutate { it.copy(priority = Priority.values()[index]) }
            }
        )

        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.sliderRow(
                this,
                tokens,
                getString(R.string.task_editor_time_estimate_label),
                0,
                240,
                task.timeEstimateMin ?: 0,
                { if (it == 0) getString(R.string.task_editor_time_estimate_zero) else getString(R.string.task_editor_minutes_value, it) }
            ) { value -> task = task.copy(timeEstimateMin = value.takeIf { it > 0 }) }
        )
        card.addView(FocusUi.smallButton(this, tokens, getString(R.string.task_editor_save_estimate_button)) { mutate { it } })

        card.addView(FocusUi.divider(this, tokens, 8))
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.task_editor_tags_label),
                if (task.tags.isEmpty()) getString(R.string.task_editor_tags_empty) else task.tags.joinToString(" "),
                trailing = FocusUi.chevron(this, tokens)
            ) {
                FocusDialog.textInput(
                    this,
                    getString(R.string.task_editor_tags_title),
                    getString(R.string.task_editor_tags_subtitle),
                    getString(R.string.task_editor_tags_hint),
                    task.tags.joinToString(" ")
                ) { value ->
                    val tags = value.split(",", " ")
                        .map { it.trim().removePrefix("#") }
                        .filter { it.isNotBlank() }
                        .toSet()
                    mutate { it.copy(tags = tags) }
                }
            }
        )
    }

    // ── Earning ───────────────────────────────────────────────────

    private fun buildEarnCard(): View = card { card ->
        card.addView(FocusUi.caption(this, tokens, getString(R.string.task_editor_verification_caption)))
        Verification.values().forEach { verification ->
            val available = verification != Verification.PHOTO ||
                (EarnMode.photoProofEnabled(this) && PhotoProof.isCaptureAvailable(this))
            val marker = FocusUi.pill(
                this,
                tokens,
                when {
                    verification == task.verification -> getString(R.string.task_editor_verification_now)
                    !available -> getString(R.string.task_editor_verification_off)
                    else -> getString(R.string.task_editor_verification_set)
                },
                if (verification == task.verification) tokens.accent else tokens.textMuted
            )
            val row = FocusUi.listRow(
                this,
                tokens,
                verification.label(this),
                verification.blurb(this),
                trailing = marker
            ) {
                if (available) {
                    mutate { it.copy(verification = verification) }
                } else {
                    FocusDialog.info(
                        this,
                        getString(R.string.task_editor_photo_unavailable_title),
                        if (!EarnMode.photoProofEnabled(this)) {
                            getString(R.string.task_editor_photo_unavailable_disabled)
                        } else {
                            getString(R.string.task_editor_photo_unavailable_no_camera)
                        }
                    )
                }
            }
            row.alpha = if (available) 1f else 0.5f
            card.addView(row)
        }

        if (task.verification == Verification.PHOTO) {
            card.addView(FocusUi.spacer(this, 6))
            card.addView(FocusUi.caption(this, tokens, PhotoProof.describeChecks(this)))
        }
        if (task.verification == Verification.TIMER && task.timeEstimateMin == null) {
            card.addView(FocusUi.spacer(this, 6))
            val note = FocusUi.caption(
                this,
                tokens,
                getString(R.string.task_editor_timer_no_estimate_note)
            )
            note.setTextColor(tokens.warning)
            card.addView(note)
        }
        if (task.verification == Verification.AMBIENT) {
            card.addView(FocusUi.spacer(this, 6))
            if (task.allowedApps.isEmpty()) {
                val note = FocusUi.caption(
                    this,
                    tokens,
                    getString(R.string.task_editor_ambient_no_apps_note)
                )
                note.setTextColor(tokens.warning)
                card.addView(note)
            } else {
                // The set is the whole deal for this verification: an ambient
                // task is paid for time in these apps and nothing else, so the
                // picker names them here rather than leaving the app list to be
                // discovered further down the same card.
                card.addView(
                    FocusUi.caption(
                        this,
                        tokens,
                        getString(
                            R.string.task_editor_ambient_apps_note,
                            task.allowedApps.joinToString { AppCatalog.label(this, it) }
                        )
                    )
                )
            }
        }
        if (task.verification == Verification.SUBTASKS_ALL && task.subtasks.isEmpty()) {
            card.addView(FocusUi.spacer(this, 6))
            val note = FocusUi.caption(
                this,
                tokens,
                getString(R.string.task_editor_subtasks_empty_note)
            )
            note.setTextColor(tokens.warning)
            card.addView(note)
        }

        card.addView(FocusUi.divider(this, tokens, 10))

        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.task_editor_allowed_apps_label),
                describeAllowedApps(),
                trailing = FocusUi.chevron(this, tokens)
            ) {
                pickApps(
                    title = getString(R.string.task_editor_pick_apps_title),
                    subtitle = getString(R.string.task_editor_pick_apps_subtitle),
                    selected = task.allowedApps
                ) { selected -> mutate { it.copy(allowedApps = selected) } }
            }
        )

        card.addView(FocusUi.divider(this, tokens, 8))
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.task_editor_enjoy_label),
                getString(R.string.task_editor_enjoy_desc),
                task.enjoyable
            ) { value -> mutate { it.copy(enjoyable = value) } }
        )

        if (!task.enjoyable) {
            card.addView(
                FocusUi.sliderRow(
                    this,
                    tokens,
                    getString(R.string.task_editor_reward_label),
                    0,
                    120,
                    task.rewardMin ?: 0,
                    { if (it == 0) getString(R.string.task_editor_reward_zero) else getString(R.string.task_editor_minutes_value, it) }
                ) { value -> task = task.copy(rewardMin = value.takeIf { it > 0 }) }
            )
            card.addView(FocusUi.smallButton(this, tokens, getString(R.string.task_editor_save_reward_button)) { mutate { it } })
            card.addView(FocusUi.spacer(this, 6))
            card.addView(FocusUi.caption(this, tokens, EarnMode.describeDeal(this)))
        }

        if (EarnMode.showsCredibility(this) && task.verification == Verification.PHOTO) {
            card.addView(FocusUi.divider(this, tokens, 8))
            card.addView(
                FocusUi.meter(
                    this,
                    tokens,
                    getString(R.string.task_editor_credibility_meter_label),
                    getString(R.string.task_editor_percent_value, (task.credibility * 100).toInt()),
                    task.credibility,
                    if (task.credibility < 0.6f) tokens.warning else tokens.success
                )
            )
        }
    }

    private fun describeAllowedApps(): String {
        if (task.allowedApps.isEmpty()) {
            // An ambient task counts the apps it names and nothing else, so an
            // empty set is not "FocusLock alone" - it is nothing being counted
            // at all, which is the one thing that makes this verification inert.
            return if (task.verification == Verification.AMBIENT) {
                getString(R.string.task_editor_ambient_no_apps_note)
            } else {
                getString(R.string.task_editor_allowed_apps_only_focuslock)
            }
        }
        val base = task.allowedApps.joinToString { AppCatalog.label(this, it) }
        // Nothing intersects for an ambient task: it never starts a session, so
        // its apps are not weighed against a running mode's allowlist, and the
        // "not on your standing allowlist" suffix would be describing a
        // restriction that does not apply to it.
        if (task.verification == Verification.AMBIENT) return base
        // Previewing, so ask what would happen if this task started right now.
        val rejected = EarnSession.rejectedPackages(this, task, !SessionManager.isActive(this))
        if (rejected.isEmpty()) return base
        return getString(R.string.task_editor_allowed_apps_rejected_suffix, base, rejected.size)
    }

    // ── Attachments ───────────────────────────────────────────────

    private fun buildAttachments(): View = card { card ->
        if (task.attachments.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.task_editor_no_attachments)))
        } else {
            task.attachments.forEach { attachment ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        attachment.label.ifBlank { attachment.value },
                        attachment.kind.id,
                        trailing = FocusUi.smallButton(this, tokens, getString(R.string.task_editor_remove_attachment_button)) {
                            mutate { current ->
                                current.copy(attachments = current.attachments.filterNot { it == attachment })
                            }
                        }
                    ) { openAttachment(attachment) }
                )
            }
        }

        card.addView(FocusUi.spacer(this, 10))
        val row = FocusUi.row(this)
        row.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row.addView(FocusUi.smallButton(this, tokens, getString(R.string.task_editor_add_file_button)) { pickFile("*/*") })
        row.addView(FocusUi.spacerH(this, 8))
        row.addView(FocusUi.smallButton(this, tokens, getString(R.string.task_editor_add_image_button)) { pickFile("image/*") })
        row.addView(FocusUi.spacerH(this, 8))
        row.addView(FocusUi.smallButton(this, tokens, getString(R.string.task_editor_add_link_button)) { addLink() })
        card.addView(FocusUi.horizontalScroll(this, row))
    }

    private fun pickFile(mime: String) {
        LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 2 * 60 * 1000)
        try {
            filePicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            )
        } catch (_: Exception) {
            FocusDialog.toast(this, getString(R.string.task_editor_toast_no_file_picker))
        }
    }

    private fun addLink() {
        FocusDialog.textInput(this, getString(R.string.task_editor_add_link_title), null, getString(R.string.task_editor_add_link_hint)) { value ->
            if (value.isNotBlank()) {
                val normalized = AllowlistStore.normalizeUrl(value)
                mutate {
                    it.copy(attachments = it.attachments + Attachment(AttachmentKind.LINK, normalized, normalized))
                }
            }
        }
    }

    /**
     * A link opens in the safe browser rather than anywhere else, so a task
     * attachment can never become a route around the allowlist.
     */
    private fun openAttachment(attachment: Attachment) {
        when (attachment.kind) {
            AttachmentKind.LINK -> {
                startActivity(
                    Intent(this, WebViewActivity::class.java).apply {
                        putExtra(WebViewActivity.EXTRA_INITIAL_URL, attachment.value)
                    }
                )
            }
            else -> {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(attachment.value), contentResolver.getType(Uri.parse(attachment.value)))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                } catch (_: Exception) {
                    FocusDialog.toast(this, getString(R.string.task_editor_toast_nothing_can_open))
                }
            }
        }
    }

    private fun fileLabel(uri: Uri): String = try {
        uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.task_editor_attachment_fallback_label)
    } catch (_: Exception) {
        getString(R.string.task_editor_attachment_fallback_label)
    }

    // ── Actions ───────────────────────────────────────────────────

    private fun buildActions(): View = card { card ->
        // An ambient task is never started - it counts in the background and is
        // paid by claiming from the task list - so the button that would start a
        // session is left out rather than shown and then refused.
        if (EarnMode.isEnabled(this) && !task.completed && task.verification != Verification.AMBIENT) {
            card.addView(
                FocusUi.primaryButton(this, tokens, getString(R.string.task_editor_start_task_button)) {
                    val standalone = !SessionManager.isActive(this)
                    EarnSession.start(this, task, standalone)
                    startActivity(Intent(this, EarnSessionActivity::class.java))
                    finish()
                }
            )
            card.addView(FocusUi.spacer(this, 8))
        }

        if (!task.completed) {
            card.addView(
                FocusUi.secondaryButton(this, tokens, getString(R.string.task_editor_mark_done_no_earn_button)) {
                    FocusDialog.alert(
                        this,
                        title = getString(R.string.task_editor_mark_done_title),
                        message = getString(R.string.task_editor_mark_done_message),
                        confirmLabel = getString(R.string.task_editor_mark_done_confirm),
                        cancelLabel = getString(R.string.task_editor_mark_done_cancel),
                        onConfirm = {
                            FocusTaskStore.complete(this, task)
                            Streaks.recordActivity(this)
                            finish()
                        }
                    )
                }
            )
            card.addView(FocusUi.spacer(this, 8))
        }

        card.addView(
            FocusUi.dangerButton(this, tokens, getString(R.string.task_editor_delete_task_button)) {
                FocusDialog.alert(
                    this,
                    title = getString(R.string.task_editor_delete_title, task.title),
                    message = getString(R.string.task_editor_delete_message),
                    confirmLabel = getString(R.string.task_editor_delete_confirm),
                    cancelLabel = getString(R.string.task_editor_delete_cancel),
                    destructive = true,
                    onConfirm = {
                        FocusTaskStore.remove(this, task.id)
                        finish()
                    }
                )
            }
        )
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}
