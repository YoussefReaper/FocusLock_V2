package com.focuslock.mdm

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * The checkpoint between tapping a video and watching it. Shows what the Keeper is
 * doing, then one of three outcomes: watch it, watch it with parts removed, or not
 * this one. A blocked video offers no appeal: the decision was about the video.
 */
class VideoCheckActivity : AppCompatActivity() {

    companion object { const val EXTRA_VIDEO_ID = "video_id" }

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var column: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens)
        column = FocusUi.column(this).apply {
            setBackgroundColor(tokens.background)
            gravity = Gravity.CENTER
            val p = FocusUi.dp(this@VideoCheckActivity, 28)
            setPadding(p, p, p, p)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(column)

        val id = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (id.isNullOrBlank()) { finish(); return }
        if (!Keeper.isReady(this) && KeeperCache.lookupVideo(this, id) == null) { showNeedsKey(); return }

        showStep(VideoCheck.Step.DETAILS)
        thread(name = "video-check") {
            val outcome = VideoCheck.run(this, id) { step -> main.post { showStep(step) } }
            main.post { if (!isFinishing && !isDestroyed) show(id, outcome) }
        }
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun showStep(step: VideoCheck.Step) {
        column.removeAllViews()
        column.addView(FocusUi.title(this, tokens, getString(R.string.video_checking_title)).apply { gravity = Gravity.CENTER })
        column.addView(FocusUi.spacer(this, 12))
        column.addView(FocusUi.secondary(this, tokens, getString(when (step) {
            VideoCheck.Step.DETAILS -> R.string.video_step_details
            VideoCheck.Step.FRAMES -> R.string.video_step_frames
            VideoCheck.Step.DECIDING -> R.string.video_step_deciding
        })).apply { gravity = Gravity.CENTER })
    }

    private fun show(id: String, outcome: VideoCheck.Outcome) {
        column.removeAllViews()
        val v = outcome.verdict
        val title = outcome.meta?.title.orEmpty()
        if (title.isNotBlank()) {
            column.addView(FocusUi.caption(this, tokens, title).apply { gravity = Gravity.CENTER })
            column.addView(FocusUi.spacer(this, 10))
        }
        when {
            v == null -> {
                column.addView(FocusUi.title(this, tokens, getString(R.string.video_failed_title)).apply { gravity = Gravity.CENTER })
                column.addView(FocusUi.spacer(this, 10))
                column.addView(FocusUi.body(this, tokens, when (outcome.failure) {
                    KeeperResult.Why.BUDGET, KeeperResult.Why.QUOTA -> getString(R.string.keeper_failed_budget)
                    KeeperResult.Why.UNREADABLE -> getString(R.string.video_failed_frames)
                    KeeperResult.Why.DAILY_LIMIT -> getString(R.string.keeper_plus_daily_limit, Account.keeperLimit(this))
                    else -> getString(R.string.keeper_failed_network)
                }).apply { gravity = Gravity.CENTER })
            }
            !v.allow -> {
                column.addView(FocusUi.title(this, tokens, getString(R.string.video_blocked_title)).apply { gravity = Gravity.CENTER })
                column.addView(FocusUi.spacer(this, 10))
                if (v.reason.isNotBlank()) column.addView(FocusUi.body(this, tokens, v.reason).apply { gravity = Gravity.CENTER })
            }
            else -> {
                val cut = v.action == "cut"
                column.addView(FocusUi.title(this, tokens, getString(if (cut) R.string.video_cut_title else R.string.video_allowed_title)).apply { gravity = Gravity.CENTER })
                column.addView(FocusUi.spacer(this, 10))
                if (cut) {
                    val secs = v.cuts.sumOf { it.second - it.first }
                    column.addView(FocusUi.body(this, tokens, getString(R.string.video_cut_body, v.cuts.size, VideoCheck.secondsLabel(secs))).apply { gravity = Gravity.CENTER })
                }
                column.addView(FocusUi.spacer(this, 22))
                column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.video_watch)) {
                    startActivity(Intent(this, SafeTubePlayerActivity::class.java)
                        .putExtra(SafeTubePlayerActivity.EXTRA_VIDEO_ID, id)
                        .putExtra(SafeTubePlayerActivity.EXTRA_CUTS, v.cuts.flatMap { listOf(it.first, it.second) }.toIntArray()))
                    finish()
                })
                if (SafeTubeDownloads.available) {
                    column.addView(FocusUi.spacer(this, 8))
                    column.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.video_download)) {
                        if (FocusStore.getString(this, KeeperSettingsActivity.KEY_SAVE_FOLDER, "").isBlank()) {
                            // No folder yet: send them to pick one and keep this screen, instead
                            // of a toast and a closed screen they have to find their way back to.
                            FocusDialog.toast(this, getString(R.string.safetube_folder_none))
                            startActivity(Intent(this, KeeperSettingsActivity::class.java))
                        } else {
                            SafeTubeDownloads.start(this, id, outcome.meta?.title ?: id, v.cuts)
                            // Progress and "saved" live in a notification; without the permission
                            // the download runs invisibly, so ask, and stay on screen while asking.
                            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4108)
                            } else finish()
                        }
                    })
                }
            }
        }
        column.addView(FocusUi.spacer(this, 16))
        column.addView(FocusUi.ghostButton(this, tokens, getString(R.string.common_close)) { finish() })
    }

    private fun showNeedsKey() {
        column.removeAllViews()
        column.addView(FocusUi.title(this, tokens, getString(R.string.keeper_needs_key_title)).apply { gravity = Gravity.CENTER })
        column.addView(FocusUi.spacer(this, 12))
        column.addView(FocusUi.body(this, tokens, getString(R.string.keeper_needs_key_body)).apply { gravity = Gravity.CENTER })
        column.addView(FocusUi.spacer(this, 22))
        column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.keeper_settings_title)) {
            startActivity(Intent(this, KeeperSettingsActivity::class.java)); finish()
        })
    }
}
