package com.focuslock.mdm

import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The Keeper's settings: the OpenRouter key (stored encrypted, only ever shown
 * masked), today's request count, and where SafeTube saves videos.
 *
 * Removing the key never opens anything: without it the Keeper simply cannot say
 * yes, so blocked things stay blocked. That is why none of this is session-locked.
 */
class KeeperSettingsActivity : FocusScreenActivity() {

    companion object {
        private const val REQ_FOLDER = 4107
        const val KEY_SAVE_FOLDER = "safetube_save_folder_uri"
    }

    override fun screenTitle(): String = getString(R.string.keeper_settings_title)
    override fun screenSubtitle(): String = getString(R.string.keeper_settings_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(card { c ->
            val ready = Keeper.isReady(this)
            c.addView(FocusUi.heading(this, tokens, getString(R.string.keeper_key_heading)))
            c.addView(FocusUi.spacer(this, 6))
            c.addView(FocusUi.secondary(this, tokens,
                if (ready) getString(R.string.keeper_key_set, KeeperKey.masked(this)) else getString(R.string.keeper_key_missing)))
            c.addView(FocusUi.spacer(this, 14))
            c.addView(FocusUi.primaryButton(this, tokens, getString(if (ready) R.string.keeper_key_replace else R.string.keeper_key_add)) { promptKey() })
            if (ready) {
                c.addView(FocusUi.spacer(this, 8))
                c.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.keeper_key_check)) { checkKey() })
                c.addView(FocusUi.spacer(this, 8))
                c.addView(FocusUi.ghostButton(this, tokens, getString(R.string.keeper_key_remove)) { KeeperKey.clear(this); refresh() })
            }
        })

        column.addView(sectionLabel(getString(R.string.keeper_usage_heading)))
        column.addView(card { c ->
            val unlock = KeeperBudget.used(this, Keeper.Purpose.UNLOCK)
            val video = KeeperBudget.used(this, Keeper.Purpose.VIDEO)
            c.addView(FocusUi.body(this, tokens, getString(R.string.keeper_usage_line, unlock + video, KeeperBudget.DAILY_LIMIT)))
            c.addView(FocusUi.spacer(this, 4))
            c.addView(FocusUi.caption(this, tokens, getString(R.string.keeper_usage_detail, unlock, video, KeeperBudget.DAILY_LIMIT - KeeperBudget.VIDEO_CAP)))
        })

        column.addView(sectionLabel(getString(R.string.safetube_title)))
        column.addView(card { c ->
            val folder = FocusStore.getString(this, KEY_SAVE_FOLDER, "")
            c.addView(FocusUi.body(this, tokens, getString(R.string.safetube_folder_heading)))
            c.addView(FocusUi.spacer(this, 4))
            c.addView(FocusUi.caption(this, tokens,
                if (folder.isBlank()) getString(R.string.safetube_folder_none) else Uri.decode(Uri.parse(folder).lastPathSegment.orEmpty())))
            c.addView(FocusUi.spacer(this, 12))
            c.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.safetube_folder_pick)) { pickFolder() })
        })
        column.addView(FocusUi.spacer(this, 10))
        column.addView(FocusUi.caption(this, tokens, getString(R.string.keeper_privacy_note)))
    }

    private fun promptKey() {
        FocusDialog.textInput(
            this,
            title = getString(R.string.keeper_key_heading),
            subtitle = getString(R.string.keeper_key_prompt),
            hint = "sk-or-…",
            confirmLabel = getString(R.string.common_save)
        ) { input ->
            val key = input.trim()
            if (key.startsWith("sk-or-") && key.length > 20) {
                KeeperKey.save(this, key)
                FocusDialog.toast(this, getString(R.string.keeper_key_saved))
            } else {
                FocusDialog.toast(this, getString(R.string.keeper_key_invalid))
            }
            refresh()
        }
    }

    /** GET /api/v1/key: says whether the key works without spending a model request. */
    private fun checkKey() {
        val key = KeeperKey.read(this) ?: return
        thread(name = "keeper-keycheck") {
            val ok = try {
                val conn = (URL("https://openrouter.ai/api/v1/key").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $key")
                }
                conn.responseCode in 200..299
            } catch (_: Exception) { false }
            runOnUiThread { FocusDialog.toast(this, getString(if (ok) R.string.keeper_key_works else R.string.keeper_key_rejected)) }
        }
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_FOLDER)
        } catch (_: Exception) {
            FocusDialog.toast(this, getString(R.string.common_page_not_available))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
            FocusStore.setString(this, KEY_SAVE_FOLDER, uri.toString())
            refresh()
        }
    }
}
