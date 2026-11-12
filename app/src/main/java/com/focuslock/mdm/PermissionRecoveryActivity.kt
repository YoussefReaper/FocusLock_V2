package com.focuslock.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * The screen that owns the phone when a permission the running session
 * depends on was just taken away.
 *
 * Nothing here is punitive framing - the headline says plainly what happened
 * and how to undo it - but there is deliberately no way past it except
 * fixing the permission. Back does nothing. There is no "later". The one
 * route out is the Settings screen for whichever permission is missing,
 * which stays reachable because [KioskPolicy] admits the Settings family of
 * packages into the lock-task allowlist for exactly this state and nothing
 * else.
 */
class PermissionRecoveryActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var column: LinearLayout

    /** What the screen was last drawn for. See the ticker. */
    private var renderedFor: Set<PermissionGuard.Guarded>? = null

    private val ticker = object : Runnable {
        override fun run() {
            PermissionGuard.clearResolved(this@PermissionRecoveryActivity)
            if (!PermissionGuard.isEmergency(this@PermissionRecoveryActivity)) {
                KioskPolicy.exitPermissionEmergency(this@PermissionRecoveryActivity)
                finish()
                return
            }
            // Redrawn only when the list of missing permissions changes.
            //
            // It used to tear the whole screen down and rebuild it every 1.5
            // seconds. A tap is a press and a release, and a button removed
            // between the two never sees its click - so the "Turn it back on"
            // buttons on the one screen whose entire job is to be tapped
            // missed a share of their taps, and the page jumped back to the top
            // each time.
            if (PermissionGuard.activeEmergency(this@PermissionRecoveryActivity) != renderedFor) render()
            handler.postDelayed(this, 1_500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = UiPrefs.resolve(this)

        // Consumes back outright. There is nothing behind this screen to go
        // to - the phone was already somewhere, and that somewhere is exactly
        // what this is holding.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* deliberately does nothing */ }
        })

        // The background on the scroller and fillViewport on it, not only on
        // the column: a column shorter than the screen otherwise left the
        // bottom half of the one screen that owns the phone plain black.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(tokens.background)
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
            setBackgroundColor(tokens.background)
        }
        scroll.addView(column)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
        KioskPolicy.enterPermissionEmergency(this)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        isShowing = false
        handler.removeCallbacks(ticker)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        column.removeAllViews()
        val missing = PermissionGuard.activeEmergency(this)
        renderedFor = missing
        if (missing.isEmpty()) {
            finish()
            return
        }

        if (UiPrefs.isMoon(this)) {
            column.addView(MoonArt.painting(this, R.drawable.art_key, 200))
            column.addView(FocusUi.spacer(this, 8))
        }
        column.addView(
            TextView(this).apply {
                text = getString(R.string.permission_recovery_headline)
                textSize = 22f
                setTextColor(tokens.textPrimary)
                gravity = Gravity.CENTER
                typeface = tokens.typeface
                if (UiPrefs.isMoon(context)) FocusUi.applyDisplayFont(this, tokens)
            }
        )
        column.addView(
            TextView(this).apply {
                text = getString(R.string.permission_recovery_body)
                textSize = 15f
                setTextColor(tokens.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(24))
                typeface = tokens.typeface
            }
        )

        missing.forEach { g ->
            column.addView(
                TextView(this).apply {
                    text = g.label(this@PermissionRecoveryActivity)
                    textSize = 17f
                    setTextColor(tokens.textPrimary)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(4))
                    typeface = tokens.typeface
                }
            )
            column.addView(
                TextView(this).apply {
                    text = g.explanation(this@PermissionRecoveryActivity)
                    textSize = 14f
                    setTextColor(tokens.textSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(10))
                    typeface = tokens.typeface
                }
            )
            val intents = settingsIntentsFor(g)
            if (intents.isNotEmpty()) {
                column.addView(
                    Button(this).apply {
                        text = getString(R.string.permission_recovery_turn_back_on)
                        setTextColor(tokens.onAccent)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(tokens.accent)
                        typeface = tokens.typeface
                        setOnClickListener {
                            // A window, because the rest of this screen's
                            // behaviour is to cover anything that is not the
                            // Settings package and pull the person back here.
                            // The screen they are being sent to, and the
                            // system dialog that confirms the toggle, are not
                            // that package on most OEM builds. This is opened
                            // only by this button - so only from inside the
                            // emergency - and it is long enough to find one
                            // toggle, not to browse.
                            openForGrant(intents)
                        }
                    }
                )
                // The accessibility switch is greyed out for a sideloaded app
                // on Android 13 and later until "Allow restricted settings" is
                // turned on in its App info - which is on a different screen,
                // behind a menu, and named nowhere on the accessibility page
                // itself. Without this the button above opened a page whose
                // one relevant switch could not be moved, and the screen had
                // no other advice.
                if (g == PermissionGuard.Guarded.ACCESSIBILITY && PermissionIntents.mayBeRestricted()) {
                    column.addView(
                        TextView(this).apply {
                            text = getString(R.string.permission_recovery_restricted_hint)
                            textSize = 13f
                            setTextColor(tokens.textSecondary)
                            gravity = Gravity.CENTER
                            setPadding(0, dp(12), 0, dp(6))
                            typeface = tokens.typeface
                        }
                    )
                    column.addView(
                        Button(this).apply {
                            text = getString(R.string.permission_recovery_open_app_info)
                            setTextColor(tokens.textPrimary)
                            backgroundTintList = android.content.res.ColorStateList.valueOf(tokens.surfaceAlt)
                            typeface = tokens.typeface
                            setOnClickListener {
                                openForGrant(PermissionIntents.appInfo(this@PermissionRecoveryActivity))
                            }
                        }
                    )
                }
            } else {
                column.addView(
                    TextView(this).apply {
                        text = getString(R.string.permission_recovery_needs_reprovisioning)
                        textSize = 13f
                        setTextColor(tokens.textSecondary)
                        gravity = Gravity.CENTER
                        typeface = tokens.typeface
                    }
                )
            }
        }

        // The one deliberate way out, and only because it already is one
        // everywhere else in the app: ending the session is never blocked by
        // anything, this included. If early exit is off, this screen offers
        // exactly as little as Kiosk itself already does in that
        // configuration - nothing new is being taken away, and nothing new
        // is being granted either.
        // Device owner is the exception to "only if early exit is on". It
        // cannot be restored from the phone at all - that is a provisioning
        // step, from a computer - so without this a lost device owner was a
        // screen with no way past it, for good.
        if (SessionManager.canEndEarly(this) || PermissionGuard.Guarded.DEVICE_OWNER in missing) {
            column.addView(
                TextView(this).apply {
                    text = getString(R.string.permission_recovery_end_session_note)
                    textSize = 12f
                    setTextColor(tokens.textSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(28), 0, dp(6))
                    typeface = tokens.typeface
                }
            )
            column.addView(
                Button(this).apply {
                    text = getString(R.string.permission_recovery_end_session_button)
                    setTextColor(tokens.textPrimary)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(tokens.surfaceAlt)
                    typeface = tokens.typeface
                    setOnClickListener { confirmEndSession() }
                }
            )
        }
    }

    private fun confirmEndSession() {
        FocusDialog.alert(
            this,
            title = getString(R.string.permission_recovery_end_session_title),
            message = getString(R.string.permission_recovery_end_session_message),
            confirmLabel = getString(R.string.permission_recovery_end_session_confirm),
            cancelLabel = getString(R.string.permission_recovery_end_session_cancel),
            onConfirm = {
                SessionManager.end(this)
                KioskPolicy.syncLockTaskState(this)
                finish()
            }
        )
    }

    /**
     * Opens a Settings screen, with the grant window open behind it.
     *
     * A window, because the rest of this screen's behaviour is to cover
     * anything that is not the Settings package and pull the person back
     * here. The screen they are being sent to, and the system dialog that
     * confirms the toggle, are not that package on most OEM builds. This is
     * opened only by this screen's own buttons - so only from inside the
     * emergency - and it is long enough to find one toggle, not to browse.
     */
    private fun openForGrant(intents: List<Intent>) {
        LockManager.allowSettingsUntil(this, System.currentTimeMillis() + GRANT_WINDOW_MS)
        // False only when no matching settings screen exists on this build of
        // Android at all - nothing more a button can do about that.
        PermissionIntents.launchFirst(this, intents)
    }

    private fun settingsIntentsFor(g: PermissionGuard.Guarded): List<Intent> = when (g) {
        PermissionGuard.Guarded.USAGE_ACCESS -> PermissionIntents.usageAccess(this)
        PermissionGuard.Guarded.OVERLAY -> PermissionIntents.overlay(this)
        // Straight to FocusLock's own switch where Android offers that, and
        // the list it lives in where it does not.
        PermissionGuard.Guarded.ACCESSIBILITY -> PermissionIntents.accessibility(this)
        PermissionGuard.Guarded.DEVICE_OWNER -> listOf(
            // Nothing on the phone can make someone a device owner again - that
            // is a provisioning step - which is why this used to be the one
            // guard with no button at all, and therefore the one way this
            // screen could be a dead end. What Settings *can* do is put the
            // app's device-admin registration back, and that is the part a
            // person can actually restore by hand, so the screen at least
            // leaves them somewhere to stand.
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this, AdminReceiver::class.java)
                )
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.permission_recovery_needs_reprovisioning)
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        /**
         * How long the "go grant it" window stays open. Long enough to find one
         * toggle, read the warning behind it and answer the system's
         * confirmation dialog; short enough that it is not a way to browse the
         * phone with the emergency still running.
         */
        private const val GRANT_WINDOW_MS = 5 * 60 * 1000L

        /**
         * Whether this screen is the one in front, for the guard service.
         *
         * Same process, so a plain flag is exact where the usage log is not:
         * the log lags a transition by a tick or two, and names only the
         * package - which is FocusLock either way.
         */
        @Volatile
        var isShowing = false
            private set

        fun launch(context: Context) {
            context.startActivity(
                Intent(context, PermissionRecoveryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
