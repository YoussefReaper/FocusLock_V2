package com.focuslock.mdm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * The optional Pinehold account: sign in (Google, GitHub or email), create one,
 * confirm the email with a 6-digit code, reset a forgotten password, see what
 * the account carries (Pro, Keeper Plus), sign out here or everywhere. Every
 * call goes through [Account] on a worker thread; the screen only ever shows its
 * cache and the outcome of the last call.
 */
class AccountActivity : FocusScreenActivity() {

    companion object {
        /** Set by [AuthCallbackActivity]: a browser sign-in handed back a session. */
        const val EXTRA_OAUTH_DONE = "oauth_done"
        /** Set by [AuthCallbackActivity]: the [AccountProblem] name a browser sign-in ended with. */
        const val EXTRA_OAUTH_PROBLEM = "oauth_problem"

        fun problemText(c: Context, p: AccountProblem): String = c.getString(AccountMessages.res(p))

        fun dateLabel(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

        private const val CODE_LENGTH = 6
    }

    private enum class Step { FORM, FORGOT_EMAIL, FORGOT_RESET }

    /** Kept outside the views: [refresh] rebuilds them, and the typing must survive it. */
    private var email = ""
    private var password = ""
    private var code = ""
    private var newPassword = ""
    private var busy = false
    private var error: String? = null
    private var notice: String? = null
    private var step = Step.FORM
    /** The "Check your email" card, shown while the account is unverified unless put off with "Not now". */
    private var verifyOpen = true
    private var providers: Account.Providers? = null

    /** [SystemClock.elapsedRealtime] when Resend comes back; see [ResendRules]. */
    private var resendAt = 0L
    /** The live Resend button, ticked in place: a full [refresh] each second would drop the keyboard. */
    private var resendButton: TextView? = null
    private var resendLabel = R.string.account_resend
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val b = resendButton ?: return
            updateResend(b)
            if (ResendRules.secondsLeft(resendAt, SystemClock.elapsedRealtime()) > 0) ticker.postDelayed(this, 1000)
        }
    }

    override fun screenTitle(): String = getString(R.string.account_title)
    override fun screenSubtitle(): String = getString(R.string.account_subtitle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Account.refreshAsync(this) { runOnUiThread { if (!isFinishing && !isDestroyed && !busy) refresh() } }
        thread(name = "account-providers") {
            val p = try { Account.providers() } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed || p == null) return@runOnUiThread
                providers = p
                if (!busy) refresh()
            }
        }
        if (savedInstanceState == null) handleOAuth(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuth(intent)
    }

    override fun onResume() {
        super.onResume()
        startTicker()
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    /** What [AuthCallbackActivity] passed on, consumed so a rotation does not repeat it. */
    private fun handleOAuth(intent: Intent?) {
        intent ?: return
        val done = intent.getBooleanExtra(EXTRA_OAUTH_DONE, false)
        val name = intent.getStringExtra(EXTRA_OAUTH_PROBLEM)
        intent.removeExtra(EXTRA_OAUTH_DONE)
        intent.removeExtra(EXTRA_OAUTH_PROBLEM)
        val problem = name?.let { n -> AccountProblem.values().firstOrNull { it.name == n } }
        when {
            problem != null -> { error = problemText(this, problem); notice = null; refresh() }
            done -> runCall({ app -> Account.refresh(app)?.let { AccountFailure(it) } }) { f ->
                if (f == null) notice = getString(R.string.account_oauth_signed_in)
            }
        }
    }

    override fun buildContent(column: LinearLayout) {
        resendButton = null
        val signedIn = Account.signedIn(this)
        val verifying = signedIn && !Account.verified(this) && verifyOpen
        if (verifying) {
            column.addView(card { c -> verifyCard(c) })
            column.addView(FocusUi.spacer(this, 12))
        }
        column.addView(card { c ->
            when {
                signedIn -> signedIn(c, showMessages = !verifying)
                step == Step.FORGOT_EMAIL -> forgotEmail(c)
                step == Step.FORGOT_RESET -> forgotReset(c)
                else -> signInForm(c)
            }
        })
        startTicker()
    }

    // ── signed out ────────────────────────────────────────────────

    private fun signInForm(c: LinearLayout) {
        val p = providers
        if (p != null && (p.google || p.github)) {
            if (p.google) {
                c.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.account_continue_google)) { openProvider("google") })
                c.addView(FocusUi.spacer(this, 8))
            }
            if (p.github) {
                c.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.account_continue_github)) { openProvider("github") })
                c.addView(FocusUi.spacer(this, 8))
            }
            c.addView(orDivider())
            c.addView(FocusUi.spacer(this, 8))
        }
        c.addView(inputField(getString(R.string.account_email_hint), email,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) { email = it })
        c.addView(inputField(getString(R.string.account_password_hint), password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { password = it })
        c.addView(FocusUi.caption(this, tokens, getString(R.string.account_password_rule)))
        c.addView(FocusUi.spacer(this, 10))
        messages(c)
        if (busy) {
            c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_working)))
            return
        }
        c.addView(FocusUi.primaryButton(this, tokens, getString(R.string.account_sign_in)) { submit(create = false) })
        c.addView(FocusUi.spacer(this, 8))
        c.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.account_create)) { submit(create = true) })
        c.addView(FocusUi.spacer(this, 4))
        c.addView(FocusUi.ghostButton(this, tokens, getString(R.string.account_forgot)) { go(Step.FORGOT_EMAIL) })
    }

    private fun orDivider(): LinearLayout {
        val row = FocusUi.row(this)
        fun line() = FocusUi.divider(this, tokens).apply {
            layoutParams = LinearLayout.LayoutParams(0, FocusUi.dp(this@AccountActivity, 1), 1f)
        }
        row.addView(line())
        row.addView(FocusUi.caption(this, tokens, getString(R.string.account_or)).apply {
            gravity = Gravity.CENTER
            val pad = FocusUi.dp(this@AccountActivity, 12)
            setPadding(pad, 0, pad, 0)
        })
        row.addView(line())
        return row
    }

    private fun forgotEmail(c: LinearLayout) {
        c.addView(FocusUi.heading(this, tokens, getString(R.string.account_forgot_title)))
        c.addView(FocusUi.spacer(this, 6))
        c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_forgot_body)))
        c.addView(FocusUi.spacer(this, 10))
        c.addView(inputField(getString(R.string.account_email_hint), email,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) { email = it })
        c.addView(FocusUi.spacer(this, 6))
        messages(c)
        if (busy) {
            c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_working)))
            return
        }
        c.addView(FocusUi.primaryButton(this, tokens, getString(R.string.account_send_code)) { sendResetCode() })
        c.addView(FocusUi.spacer(this, 4))
        c.addView(FocusUi.ghostButton(this, tokens, getString(R.string.account_back)) { go(Step.FORM) })
    }

    private fun forgotReset(c: LinearLayout) {
        c.addView(FocusUi.heading(this, tokens, getString(R.string.account_forgot_title)))
        c.addView(FocusUi.spacer(this, 6))
        c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_reset_body, email.trim())))
        c.addView(FocusUi.spacer(this, 10))
        c.addView(codeField())
        c.addView(inputField(getString(R.string.account_new_password_hint), newPassword,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { newPassword = it })
        c.addView(FocusUi.caption(this, tokens, getString(R.string.account_password_rule)))
        c.addView(FocusUi.spacer(this, 10))
        messages(c)
        if (busy) {
            c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_working)))
            return
        }
        c.addView(FocusUi.primaryButton(this, tokens, getString(R.string.account_change_password)) { resetPassword() })
        c.addView(FocusUi.spacer(this, 8))
        c.addView(resendView { sendResetCode() })
        c.addView(FocusUi.spacer(this, 4))
        c.addView(FocusUi.ghostButton(this, tokens, getString(R.string.account_back)) { go(Step.FORGOT_EMAIL) })
    }

    // ── signed in ─────────────────────────────────────────────────

    private fun verifyCard(c: LinearLayout) {
        c.addView(FocusUi.heading(this, tokens, getString(R.string.account_verify_title)))
        c.addView(FocusUi.spacer(this, 6))
        c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_verify_body, Account.email(this))))
        c.addView(FocusUi.spacer(this, 10))
        c.addView(codeField())
        c.addView(FocusUi.spacer(this, 6))
        messages(c)
        if (busy) {
            c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_working)))
            return
        }
        c.addView(FocusUi.primaryButton(this, tokens, getString(R.string.account_verify)) { verify() })
        c.addView(FocusUi.spacer(this, 8))
        c.addView(resendView { resendVerification() })
        c.addView(FocusUi.spacer(this, 4))
        c.addView(FocusUi.ghostButton(this, tokens, getString(R.string.account_not_now)) {
            verifyOpen = false; error = null; notice = null; refresh()
        })
    }

    private fun signedIn(c: LinearLayout, showMessages: Boolean) {
        c.addView(FocusUi.heading(this, tokens, getString(R.string.account_signed_in_as, Account.email(this))))
        c.addView(FocusUi.spacer(this, 8))
        val verified = Account.verified(this)
        c.addView(FocusUi.pill(this, tokens,
            getString(if (verified) R.string.account_badge_verified else R.string.account_badge_unverified),
            if (verified) tokens.success else tokens.textMuted))
        if (!verified && !verifyOpen) {
            c.addView(FocusUi.ghostButton(this, tokens, getString(R.string.account_verify_action)) {
                verifyOpen = true; error = null; notice = null; refresh()
            })
        }
        c.addView(FocusUi.spacer(this, 10))
        c.addView(FocusUi.body(this, tokens,
            getString(if (Account.hasMobilePro(this)) R.string.account_pro_active else R.string.account_pro_inactive)))
        c.addView(FocusUi.spacer(this, 4))
        val until = Account.entitlement(this, EntitlementRules.KEEPER_PLUS)?.expiresAtMs
        c.addView(FocusUi.body(this, tokens, when {
            !Account.keeperPlusActive(this) -> getString(R.string.account_plus_inactive)
            until != null -> getString(R.string.account_plus_active_until, dateLabel(until))
            else -> getString(R.string.account_plus_active)
        }))
        c.addView(FocusUi.spacer(this, 10))
        if (showMessages) messages(c)
        c.addView(FocusUi.spacer(this, 4))
        if (busy) {
            c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_working)))
            return
        }
        c.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.account_sign_out)) { signOut() })
        c.addView(FocusUi.spacer(this, 4))
        c.addView(FocusUi.ghostButton(this, tokens, getString(R.string.account_sign_out_everywhere)) { signOutEverywhere() })
    }

    // ── pieces ────────────────────────────────────────────────────

    private fun inputField(hint: String, value: String, type: Int, onText: (String) -> Unit): EditText {
        val f = FocusUi.input(this, tokens, hint, value)
        f.inputType = type
        // A password input type swaps the face to monospace; put the app's back.
        FocusUi.applyFont(f, tokens)
        f.isEnabled = !busy
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { onText(s?.toString().orEmpty()) }
        })
        return f
    }

    private fun codeField(): EditText {
        val f = FocusUi.input(this, tokens, getString(R.string.account_code_hint), code, numeric = true)
        f.inputType = InputType.TYPE_CLASS_NUMBER
        f.filters = arrayOf(InputFilter.LengthFilter(CODE_LENGTH))
        f.isEnabled = !busy
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) = Unit
            override fun afterTextChanged(s: Editable?) { code = s?.toString().orEmpty() }
        })
        return f
    }

    /** "Resend code", greyed with a countdown until [resendAt]. */
    private fun resendView(onResend: () -> Unit): TextView {
        val b = FocusUi.secondaryButton(this, tokens, getString(resendLabel)) {
            if (!busy && ResendRules.secondsLeft(resendAt, SystemClock.elapsedRealtime()) == 0) onResend()
        }
        resendButton = b
        updateResend(b)
        return b
    }

    private fun updateResend(b: TextView) {
        val left = ResendRules.secondsLeft(resendAt, SystemClock.elapsedRealtime())
        b.text = if (left > 0) getString(R.string.account_resend_in, left) else getString(resendLabel)
        b.isEnabled = left == 0 && !busy
        b.alpha = if (b.isEnabled) 1f else 0.5f
    }

    private fun startTicker() {
        ticker.removeCallbacks(tick)
        if (resendButton != null) ticker.post(tick)
    }

    private fun messages(c: LinearLayout) {
        error?.let {
            c.addView(FocusUi.caption(this, tokens, it).apply { setTextColor(tokens.danger) })
            c.addView(FocusUi.spacer(this, 10))
        }
        notice?.let {
            c.addView(FocusUi.caption(this, tokens, it).apply { setTextColor(tokens.success) })
            c.addView(FocusUi.spacer(this, 10))
        }
    }

    private fun failureText(f: AccountFailure): String {
        val left = f.attemptsLeft
        return if (f.problem == AccountProblem.WRONG_EMAIL_CODE && left != null)
            resources.getQuantityString(R.plurals.account_err_wrong_code_left, left, left)
        else problemText(this, f.problem)
    }

    private fun go(next: Step) {
        step = next
        error = null
        notice = null
        refresh()
    }

    private fun show(errorText: String) {
        error = errorText
        notice = null
        refresh()
    }

    // ── actions ───────────────────────────────────────────────────

    /** Same opening as [DemoMode.openCheckout]: the browser gets a settings window so the blocker lets it through. */
    private fun openProvider(provider: String) {
        try {
            Account.markOAuthStarted(this)
            LockManager.allowSettingsUntil(this, System.currentTimeMillis() + 5 * 60 * 1000)
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(Account.providerStartUrl(provider))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            FocusDialog.toast(this, getString(R.string.common_page_not_available))
        }
    }

    private fun submit(create: Boolean) {
        if (email.isBlank() || password.isEmpty()) return show(getString(R.string.account_missing_fields))
        if (create && password.length < AccountRules.MIN_PASSWORD) return show(problemText(this, AccountProblem.PASSWORD_TOO_SHORT))
        val e = email
        val pw = password
        runCall({ app -> Account.signIn(app, e, pw, create)?.let { AccountFailure(it) } }) { f ->
            if (f == null && create) {
                // Registering emailed the first code.
                verifyOpen = true
                resendAt = ResendRules.availableAt(SystemClock.elapsedRealtime())
            }
        }
    }

    private fun verify() {
        val c = code.trim()
        if (c.length != CODE_LENGTH) return show(getString(R.string.account_code_format))
        runCall({ app -> Account.verify(app, Account.email(app), c) }) { f ->
            if (f == null) { code = ""; verifyOpen = false; notice = getString(R.string.account_verified_done) }
        }
    }

    private fun resendVerification() {
        val e = Account.email(this)
        runCall({ _ -> Account.resend(e) }) { f -> afterCodeSent(f, R.string.account_code_sent) }
    }

    private fun sendResetCode() {
        if (email.isBlank()) return show(getString(R.string.account_missing_fields))
        val e = email
        runCall({ _ -> Account.forgot(e) }) { f ->
            afterCodeSent(f, R.string.account_forgot_sent)
            if (f == null) step = Step.FORGOT_RESET
        }
    }

    private fun afterCodeSent(f: AccountFailure?, sentText: Int) {
        val now = SystemClock.elapsedRealtime()
        if (f == null) {
            resendAt = ResendRules.availableAt(now)
            notice = getString(sentText)
        } else if (f.retryAfterSec != null) {
            resendAt = ResendRules.availableAt(now, f.retryAfterSec)
        }
    }

    private fun resetPassword() {
        val c = code.trim()
        if (c.length != CODE_LENGTH) return show(getString(R.string.account_code_format))
        if (newPassword.length < AccountRules.MIN_PASSWORD) return show(problemText(this, AccountProblem.PASSWORD_TOO_SHORT))
        val e = email
        val pw = newPassword
        runCall({ app -> Account.reset(app, e, c, pw) }) { f ->
            if (f == null) {
                code = ""; newPassword = ""; password = ""
                step = Step.FORM
                notice = getString(R.string.account_password_changed)
            }
        }
    }

    private fun signOut() = runCall({ app -> Account.signOut(app); null })

    private fun signOutEverywhere() = runCall({ app -> Account.signOutEverywhere(app)?.let { AccountFailure(it) } }) { f ->
        if (f == null) notice = getString(R.string.account_signed_out_everywhere)
    }

    /**
     * One account call off the main thread, then the screen redrawn with its
     * outcome. [after] runs on the main thread first, to move the screen on.
     */
    private fun runCall(work: (Context) -> AccountFailure?, after: (AccountFailure?) -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        notice = null
        refresh()
        val app = applicationContext
        thread(name = "account-call") {
            val failure = try { work(app) } catch (_: Exception) { AccountFailure(AccountProblem.NETWORK) }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (failure == null) password = ""
                error = failure?.let { failureText(it) }
                after(failure)
                refresh()
            }
        }
    }
}
