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
 * Profiles: save a setup, switch between setups, move one to another phone.
 *
 * Importing always shows what is in the file first and never touches the theme
 * unless asked — a setup arriving from elsewhere should not silently repaint
 * someone's phone.
 */
class ProfilesActivity : FocusScreenActivity() {

    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri: Uri = result.data?.data ?: return@registerForActivityResult
        if (ProfileIo.writeTo(this, uri)) {
            FocusDialog.toast(this, getString(R.string.profiles_saved_toast))
        } else {
            FocusDialog.toast(this, getString(R.string.profiles_write_failed))
        }
    }

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri: Uri = result.data?.data ?: return@registerForActivityResult
        val payload = ProfileIo.readFrom(this, uri)
        if (payload == null) {
            FocusDialog.toast(this, getString(R.string.profiles_not_a_setup))
            return@registerForActivityResult
        }
        confirmRestore(getString(R.string.profiles_this_file), payload)
    }

    override fun screenArt(): Int = R.drawable.art_you

    override fun screenTitle(): String = getString(R.string.rules_profiles_title)

    override fun screenSubtitle(): String =
        getString(R.string.profiles_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildCurrentCard())
        column.addView(sectionLabel(getString(R.string.profiles_saved_setups)))
        column.addView(buildSavedList())
        column.addView(sectionLabel(getString(R.string.profiles_section_files)))
        column.addView(buildFileCard())
    }

    private fun buildCurrentCard(): View = card { card ->
        card.addView(FocusUi.heading(this, tokens, getString(R.string.profiles_right_now)))
        card.addView(FocusUi.spacer(this, 6))
        card.addView(
            FocusUi.secondary(this, tokens, ProfileIo.describe(this, ProfileIo.snapshot(this)))
        )
        card.addView(FocusUi.spacer(this, 14))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.profiles_save_as)) {
                FocusDialog.textInput(
                    this,
                    title = getString(R.string.profiles_name_title),
                    subtitle = getString(R.string.profiles_name_subtitle),
                    hint = getString(R.string.profiles_name_hint)
                ) { name ->
                    if (name.isBlank()) return@textInput
                    ProfileIo.save(this, name)
                    FocusDialog.toast(this, getString(R.string.profiles_saved_toast_short))
                    refresh()
                }
            }
        )
    }

    private fun buildSavedList(): View = card { card ->
        val profiles = ProfileIo.saved(this)

        if (profiles.isEmpty()) {
            card.addView(
                FocusUi.emptyState(
                    this,
                    tokens,
                    getString(R.string.profiles_empty)
                )
            )
            return@card
        }

        profiles.sortedByDescending { it.savedAtMs }.forEachIndexed { index, profile ->
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    profile.name,
                    getString(R.string.profiles_saved_at, TimeText.dateTime(this, (profile.savedAtMs))),
                    trailing = FocusUi.smallButton(this, tokens, getString(R.string.common_use)) {
                        confirmRestore(profile.name, profile.payload)
                    }
                ) { openProfileSheet(profile) }
            )
            if (index < profiles.size - 1) card.addView(FocusUi.divider(this, tokens))
        }
    }

    private fun openProfileSheet(profile: ProfileIo.Profile) {
        FocusDialog.custom(
            this,
            title = profile.name,
            subtitle = ProfileIo.describe(this, profile.payload),
            confirmLabel = null,
            cancelLabel = getString(R.string.common_close)
        ) { body, dialogTokens, _ ->
            body.addView(
                FocusUi.primaryButton(this, dialogTokens, getString(R.string.profiles_switch)) {
                    confirmRestore(profile.name, profile.payload)
                }
            )
            body.addView(FocusUi.spacer(this, 10))
            body.addView(
                FocusUi.secondaryButton(this, dialogTokens, getString(R.string.profiles_overwrite)) {
                    ProfileIo.save(this, profile.name)
                    FocusDialog.toast(this, getString(R.string.profiles_updated))
                    refresh()
                }
            )
            body.addView(FocusUi.spacer(this, 10))
            body.addView(
                FocusUi.dangerButton(this, dialogTokens, getString(R.string.profiles_delete)) {
                    ProfileIo.delete(this, profile.name)
                    refresh()
                }
            )
        }
    }

    /**
     * Restoring rewrites every rule the person has, so it states plainly what
     * will be replaced and offers to leave the look of the app alone.
     */
    private fun confirmRestore(name: String, payload: org.json.JSONObject) {
        var includeAppearance = false

        FocusDialog.custom(
            this,
            title = getString(R.string.profiles_switch_title, name),
            subtitle = ProfileIo.describe(this, payload),
            confirmLabel = getString(R.string.common_switch),
            cancelLabel = getString(R.string.common_cancel),
            onConfirm = {
                if (ProfileIo.restore(this, payload, includeAppearance)) {
                    FocusDialog.toast(this, getString(R.string.profiles_applied))
                } else {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                }
                refresh()
            }
        ) { body, dialogTokens, _ ->
            body.addView(
                FocusUi.secondary(
                    this,
                    dialogTokens,
                    getString(R.string.profiles_replace_message)
                )
            )
            body.addView(FocusUi.spacer(this, 10))
            body.addView(
                FocusUi.toggleRow(
                    this,
                    dialogTokens,
                    getString(R.string.profiles_also_theme),
                    getString(R.string.profiles_also_theme_subtitle),
                    false
                ) { value -> includeAppearance = value }
            )
        }
    }

    private fun buildFileCard(): View = card { card ->
        card.addView(
            FocusUi.secondary(
                this,
                tokens,
                getString(R.string.profiles_file_note)
            )
        )
        card.addView(FocusUi.spacer(this, 14))
        card.addView(
            FocusUi.primaryButton(this, tokens, getString(R.string.profiles_export)) {
                LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 2 * 60 * 1000)
                try {
                    exportPicker.launch(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                            putExtra(Intent.EXTRA_TITLE, ProfileIo.suggestedFileName())
                        }
                    )
                } catch (_: Exception) {
                    FocusDialog.toast(this, getString(R.string.profiles_no_picker))
                }
            }
        )
        card.addView(FocusUi.spacer(this, 10))
        card.addView(
            FocusUi.secondaryButton(this, tokens, getString(R.string.profiles_import)) {
                LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 2 * 60 * 1000)
                try {
                    importPicker.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    )
                } catch (_: Exception) {
                    FocusDialog.toast(this, getString(R.string.profiles_no_picker))
                }
            }
        )
    }
}