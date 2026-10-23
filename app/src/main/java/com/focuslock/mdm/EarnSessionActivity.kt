package com.focuslock.mdm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * The screen you sit on while a task is running.
 *
 * It shows one task, one clock, and exactly one way to finish it — whichever the
 * task asked for. No progress bars filling with points, no streak in the corner,
 * nothing to optimise except the work.
 *
 * Stopping early is a plain button with no penalty attached. A reward system
 * that punishes quitting is a reward system people stop opening.
 */
class EarnSessionActivity : FocusScreenActivity() {

    private var task: FocusTask? = null
    private var pendingProof: File? = null

    private var timerView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            timerView?.text = SessionManager.formatCountdown(this@EarnSessionActivity, EarnSession.elapsedMs(this@EarnSessionActivity))
            handler.postDelayed(this, 1_000L)
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val file = pendingProof
        pendingProof = null
        if (file == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            PhotoProof.discard(file)
            return@registerForActivityResult
        }
        handleProof(file)
    }

    override fun screenTitle(): String = task?.title ?: getString(R.string.earn_session_no_task_title)

    override fun screenSubtitle(): String? = task?.notes?.takeIf { it.isNotBlank() }

    override fun onCreate(savedInstanceState: Bundle?) {
        task = EarnSession.activeTask(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        task = EarnSession.activeTask(this)
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun buildContent(column: LinearLayout) {
        val current = task
        if (current == null) {
            column.addView(buildNoSessionCard())
            return
        }

        column.addView(buildClockCard(current))
        column.addView(sectionLabel(getString(R.string.earn_session_section_finishing)))
        column.addView(buildVerificationCard(current))

        if (EarnMode.showsBudgetWhileActive(this)) {
            column.addView(sectionLabel(getString(R.string.earn_session_section_banked)))
            column.addView(buildBudgetCard())
        }

        column.addView(sectionLabel(getString(R.string.earn_session_section_open_now)))
        column.addView(buildAllowedApps(current))

        column.addView(FocusUi.spacer(this, 10))
        column.addView(
            FocusUi.ghostButton(this, tokens, getString(R.string.earn_session_stop_button)) { confirmStop(current) }
        )

        column.addView(FocusUi.spacer(this, 14))
        val footer = FocusUi.caption(this, tokens, Copy.onDeviceFooter(this))
        footer.gravity = Gravity.CENTER
        column.addView(footer)
    }

    private fun buildNoSessionCard(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, getString(R.string.earn_session_nothing_running_heading)))
        card.addView(FocusUi.spacer(this, 6))
        card.addView(FocusUi.secondary(this, tokens, getString(R.string.earn_session_nothing_running_body)))
        card.addView(FocusUi.spacer(this, 12))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.earn_session_back_to_tasks)) {
                MainActivity.open(this, MainActivity.TAB_TASKS)
                finish()
            }
        )
    }

    // ── Clock ─────────────────────────────────────────────────────

    private fun buildClockCard(current: FocusTask): View = card { card ->
        val elapsed = FocusUi.display(
            this,
            tokens,
            SessionManager.formatCountdown(this, EarnSession.elapsedMs(this))
        )
        elapsed.gravity = Gravity.CENTER
        timerView = elapsed
        card.addView(elapsed)

        val caption = FocusUi.caption(this, tokens, getString(R.string.earn_session_on_this_task))
        caption.gravity = Gravity.CENTER
        card.addView(caption)

        if (current.verification == Verification.TIMER) {
            val target = EarnSession.timerTargetMinutes(this, current)
            val done = EarnSession.elapsedMinutes(this)
            card.addView(FocusUi.spacer(this, 12))
            card.addView(
                FocusUi.meter(
                    this,
                    tokens,
                    getString(R.string.earn_session_timer_meter_label, done, target),
                    if (done >= target) {
                        getString(R.string.earn_session_timer_ready)
                    } else {
                        getString(R.string.earn_session_timer_to_go, target - done)
                    },
                    done.toFloat() / target.toFloat(),
                    if (done >= target) tokens.success else tokens.accent
                )
            )
        }

        val mode = if (EarnSession.isStandalone(this)) {
            if (EarnMode.hasHardEnforcement(this)) {
                getString(R.string.earn_session_mode_locked)
            } else {
                getString(R.string.earn_session_mode_friction)
            }
        } else {
            getString(R.string.earn_session_mode_narrowing, SessionManager.mode(this).label(this).lowercase())
        }
        card.addView(FocusUi.spacer(this, 10))
        val note = FocusUi.caption(this, tokens, mode)
        note.gravity = Gravity.CENTER
        card.addView(note)
    }

    // ── Verification ──────────────────────────────────────────────

    private fun buildVerificationCard(current: FocusTask): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, current.verification.label(this)))
        card.addView(FocusUi.spacer(this, 6))
        card.addView(FocusUi.secondary(this, tokens, current.verification.blurb(this)))
        card.addView(FocusUi.spacer(this, 14))

        when (current.verification) {
            Verification.MANUAL -> card.addView(
                FocusUi.primaryButton(this, tokens, getString(R.string.earn_session_manual_button)) { completeTask(current) }
            )

            // An ambient task never starts a session, so this screen should not
            // be reachable for one. It says so rather than showing a button that
            // would do nothing.
            Verification.AMBIENT -> card.addView(
                FocusUi.caption(this, tokens, getString(R.string.earn_session_ambient_note))
            )

            Verification.TIMER -> {
                val ready = EarnSession.timerSatisfied(this, current)
                val button = FocusUi.primaryButton(
                    this,
                    tokens,
                    if (ready) {
                        getString(R.string.earn_session_finish_collect_button)
                    } else {
                        getString(R.string.earn_session_keep_going_button)
                    }
                ) {
                    if (ready) completeTask(current) else refresh()
                }
                button.isEnabled = ready
                button.alpha = if (ready) 1f else 0.5f
                card.addView(button)

                if (!ready) {
                    card.addView(FocusUi.spacer(this, 8))
                    card.addView(
                        FocusUi.ghostButton(this, tokens, getString(R.string.earn_session_finish_early_button)) {
                            completeTask(current)
                        }
                    )
                }
            }

            Verification.PHOTO -> {
                card.addView(
                    FocusUi.primaryButton(this, tokens, getString(R.string.earn_session_take_photo_button)) { capture(current) }
                )
                card.addView(FocusUi.spacer(this, 10))
                card.addView(FocusUi.caption(this, tokens, PhotoProof.describeChecks(this)))

                val attempts = EarnSession.photoAttempts(this)
                if (attempts > 0) {
                    card.addView(FocusUi.spacer(this, 8))
                    card.addView(
                        FocusUi.caption(this, tokens, Copy.earnPhotoRetry(this, attempts))
                    )
                }
                if (EarnMode.showsCredibility(this)) {
                    card.addView(FocusUi.spacer(this, 8))
                    card.addView(
                        FocusUi.meter(
                            this,
                            tokens,
                            getString(R.string.earn_session_verification_trust_label),
                            getString(R.string.task_editor_percent_value, (current.credibility * 100).toInt()),
                            current.credibility,
                            if (current.credibility < 0.6f) tokens.warning else tokens.success
                        )
                    )
                }
            }

            Verification.SUBTASKS_ALL -> {
                if (current.subtasks.isEmpty()) {
                    card.addView(FocusUi.secondary(this, tokens, getString(R.string.earn_session_subtasks_empty)))
                } else {
                    current.subtasks.forEach { subtask ->
                        val toggle = FocusUi.switchControl(this, tokens, subtask.done) { done ->
                            val updated = current.copy(
                                subtasks = current.subtasks.map {
                                    if (it.id == subtask.id) it.copy(done = done) else it
                                }
                            )
                            FocusTaskStore.update(this, updated)
                            task = updated
                            refresh()
                        }
                        card.addView(FocusUi.listRow(this, tokens, subtask.title, null, trailing = toggle))
                    }

                    val allDone = current.subtasks.all { it.done }
                    card.addView(FocusUi.spacer(this, 12))
                    val button = FocusUi.primaryButton(
                        this,
                        tokens,
                        if (allDone) {
                            getString(R.string.earn_session_finish_collect_button)
                        } else {
                            getString(R.string.earn_session_tick_steps_first_button)
                        }
                    ) { if (allDone) completeTask(current) }
                    button.isEnabled = allDone
                    button.alpha = if (allDone) 1f else 0.5f
                    card.addView(button)
                }
            }
        }
    }

    // ── Photo proof ───────────────────────────────────────────────

    private fun capture(current: FocusTask) {
        if (!PhotoProof.isCaptureAvailable(this)) {
            FocusDialog.info(
                this,
                getString(R.string.earn_session_no_camera_title),
                getString(R.string.earn_session_no_camera_message)
            )
            return
        }

        val file = PhotoProof.newProofFile(this)
        pendingProof = file
        try {
            cameraLauncher.launch(PhotoProof.captureIntent(this, PhotoProof.uriFor(this, file)))
        } catch (_: Exception) {
            pendingProof = null
            PhotoProof.discard(file)
            FocusDialog.toast(this, getString(R.string.earn_session_camera_not_opening_toast))
        }
    }

    /**
     * Checked here, on this phone, and then deleted. The only thing that outlives
     * a proof photo is a 64-bit fingerprint that stops the same shot being used
     * twice — and that is stated on screen rather than assumed.
     */
    private fun handleProof(file: File) {
        val current = task ?: return
        val result = PhotoProof.verify(this, current, file, EarnSession.startedAt(this))
        PhotoProof.discard(file)

        val updated = PhotoProof.applyCredibility(this, current, result)
        task = updated

        if (result.accepted) {
            FocusDialog.alert(
                this,
                title = result.headline,
                message = result.detail,
                confirmLabel = getString(R.string.earn_session_collect_button),
                onConfirm = { completeTask(updated) }
            )
        } else {
            EarnSession.recordPhotoAttempt(this)
            FocusDialog.alert(
                this,
                title = result.headline,
                message = result.detail + "\n\n" + Copy.earnPhotoRetry(this, EarnSession.photoAttempts(this)),
                confirmLabel = getString(R.string.earn_session_try_again_button),
                cancelLabel = getString(R.string.earn_session_later_button),
                onConfirm = { capture(updated) }
            )
            refresh()
        }
    }

    // ── Budget and apps ───────────────────────────────────────────

    private fun buildBudgetCard(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, EarnPool.formatBalance(this)))
        card.addView(FocusUi.spacer(this, 6))

        val projected = task?.let { EarnMode.rewardFor(this, it, EarnSession.elapsedMinutes(this)) } ?: 0
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                when {
                    task?.enjoyable == true -> getString(R.string.earn_session_budget_enjoyable)
                    EarnPool.capReachedToday(this) -> Copy.earnCapReached(this)
                    projected > 0 -> getString(R.string.earn_session_budget_projected, projected)
                    else -> getString(R.string.earn_session_budget_keep_going)
                }
            )
        )
        card.addView(FocusUi.spacer(this, 8))
        card.addView(FocusUi.caption(this, tokens, getString(R.string.earn_session_pool_explainer)))
    }

    private fun buildAllowedApps(current: FocusTask): View = card { card ->
        val allowed = EarnSession.allowedPackages(this, current)
            .filter { it != packageName }
            .filter { packageManager.getLaunchIntentForPackage(it) != null }
            .distinct()
            .sortedBy { AppCatalog.label(this, it) }

        if (allowed.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.earn_session_no_apps_open)))
        } else {
            val strip = FocusUi.row(this)
            strip.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            allowed.forEach { packageName ->
                strip.addView(
                    FocusUi.chip(this, tokens, AppCatalog.label(this, packageName), false) {
                        launch(packageName)
                    }
                )
            }
            card.addView(FocusUi.horizontalScroll(this, strip))
        }

        val rejected = EarnSession.rejectedPackages(this, current)
        if (rejected.isNotEmpty()) {
            card.addView(FocusUi.spacer(this, 10))
            val warning = FocusUi.caption(
                this,
                tokens,
                getString(R.string.earn_session_rejected_apps, rejected.joinToString { AppCatalog.label(this, it) })
            )
            warning.setTextColor(tokens.warning)
            card.addView(warning)
        }
    }

    private fun launch(target: String) {
        val intent = packageManager.getLaunchIntentForPackage(target) ?: return
        try {
            startActivity(intent)
        } catch (_: Exception) {
            FocusDialog.toast(this, getString(R.string.earn_session_lock_holding_toast))
        }
    }

    // ── Finishing ─────────────────────────────────────────────────

    private fun completeTask(current: FocusTask) {
        // A task that counts its apps is paid for the time those apps were
        // actually in front, not for how long the session stayed open.
        val focusedMinutes = if (current.tracksApps) {
            EarnSession.trackedMinutes(this, current)
        } else {
            EarnSession.elapsedMinutes(this)
        }
        // The deal frozen when this session started, not the task as it stands
        // now. Editing the running task must not be able to change what it pays.
        val owed = EarnMode.rewardForDeal(this, EarnSession.deal(this), focusedMinutes)
        val granted = EarnPool.credit(this, owed)

        FocusTaskStore.complete(this, current)
        Streaks.recordActivity(this)
        EarnSession.stop(this)

        val capped = owed > 0 && granted < owed
        FocusDialog.alert(
            this,
            title = getString(R.string.earn_session_task_done_title),
            message = Copy.earnCompleted(this, granted) +
                (if (capped) "\n\n" + Copy.earnCapReached(this) else "") +
                "\n\n" + EarnPool.formatBalance(this),
            confirmLabel = getString(R.string.earn_session_good_button),
            onConfirm = { leave() }
        )
    }

    /**
     * Stopping early is not a failure state. The task stays exactly where it
     * was, nothing is deducted, and the streak is untouched.
     */
    private fun confirmStop(current: FocusTask) {
        FocusDialog.alert(
            this,
            title = getString(R.string.earn_session_stop_confirm_title),
            message = getString(R.string.earn_session_stop_confirm_message),
            confirmLabel = getString(R.string.earn_session_stop_confirm_button),
            cancelLabel = getString(R.string.earn_session_keep_going_button),
            onConfirm = {
                EarnSession.stop(this)
                leave()
            }
        )
    }

    private fun leave() {
        MainActivity.open(this, MainActivity.TAB_TASKS)
        finish()
    }
}
