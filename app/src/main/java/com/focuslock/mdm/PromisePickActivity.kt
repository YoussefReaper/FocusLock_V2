package com.focuslock.mdm

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity

/** Opens the system picker for a picture, GIF or short clip, copies the choice in, and closes. */
class PromisePickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val ok = Promise.importMedia(this, uri)
                FocusDialog.toast(this, getString(if (ok) R.string.promise_media_saved else R.string.promise_media_failed))
            }
            finish()
        }
        if (savedInstanceState == null) {
            // The picker is a system screen; let it through the way Settings pages are.
            LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 120_000L)
            picker.launch(arrayOf("image/*", "video/*"))
        }
    }
}
