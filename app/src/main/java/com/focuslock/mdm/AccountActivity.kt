package com.focuslock.mdm

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * The optional Pinehold account: sign in or create one, see what it carries
 * (Pro, Keeper Plus), sign out. Every call goes through [Account] on a worker
 * thread; the screen only ever shows its cache and the last problem.
 */
class AccountActivity : FocusScreenActivity() {

    companion object {
        fun problemText(c: Context, p: AccountProblem): String = c.getString(when (p) {
            AccountProblem.NOT_AVAILABLE -> R.string.account_err_not_available
            AccountProblem.SUBSCRIPTIONS_NOT_AVAILABLE -> R.string.account_err_subscriptions
            AccountProblem.INVALID_INPUT -> R.string.account_err_invalid_input
            AccountProblem.TAKEN -> R.string.account_err_taken
            AccountProblem.BAD_CREDENTIALS -> R.string.account_err_bad_credentials
            AccountProblem.TOO_MANY -> R.string.account_err_too_many
            AccountProblem.SIGNED_OUT -> R.string.account_err_signed_out
            AccountProblem.NOT_FOUND -> R.string.account_err_not_found
            AccountProblem.INACTIVE -> R.string.account_err_inactive
            AccountProblem.ALREADY_USED -> R.string.account_err_already_used
            AccountProblem.INVALID_CODE -> R.string.account_err_invalid_code
            AccountProblem.NETWORK -> R.string.account_err_network
        })

        fun dateLabel(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }

    /** Kept outside the views: [refresh] rebuilds them, and the typing must survive it. */
    private var email = ""
    private var password = ""
    private var busy = false
    private var error: String? = null

    override fun screenTitle(): String = getString(R.string.account_title)
    override fun screenSubtitle(): String = getString(R.string.account_subtitle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Account.refreshAsync(this) { runOnUiThread { if (!isFinishing && !isDestroyed && !busy) refresh() } }
    }

    override fun buildContent(column: LinearLayout) {
        column.addView(card { c -> if (Account.signedIn(this)) signedIn(c) else signInForm(c) })
    }

    private fun signInForm(c: LinearLayout) {
        val emailField = inputField(getString(R.string.account_email_hint), email,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) { email = it }
        val passwordField = inputField(getString(R.string.account_password_hint), password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { password = it }
        c.addView(emailField)
        c.addView(passwordField)
        error?.let { c.addView(errorLine(it)); c.addView(FocusUi.spacer(this, 10)) }
        if (busy) {
            c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_working)))
            return
        }
        c.addView(FocusUi.spacer(this, 4))
        c.addView(FocusUi.primaryButton(this, tokens, getString(R.string.account_sign_in)) { submit(create = false) })
        c.addView(FocusUi.spacer(this, 8))
        c.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.account_create)) { submit(create = true) })
    }

    private fun signedIn(c: LinearLayout) {
        c.addView(FocusUi.heading(this, tokens, getString(R.string.account_signed_in_as, Account.email(this))))
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
        error?.let { c.addView(FocusUi.spacer(this, 10)); c.addView(errorLine(it)) }
        c.addView(FocusUi.spacer(this, 14))
        if (busy) {
            c.addView(FocusUi.secondary(this, tokens, getString(R.string.account_working)))
            return
        }
        c.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.account_sign_out)) { signOut() })
    }

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

    private fun errorLine(text: String) = FocusUi.caption(this, tokens, text).apply { setTextColor(tokens.danger) }

    private fun submit(create: Boolean) {
        if (email.isBlank() || password.isEmpty()) {
            error = getString(R.string.account_missing_fields)
            refresh()
            return
        }
        val e = email
        val pw = password
        runCall { app -> Account.signIn(app, e, pw, create) }
    }

    private fun signOut() = runCall { app -> Account.signOut(app); null }

    /** One account call off the main thread, then the screen redrawn with its outcome. */
    private fun runCall(work: (Context) -> AccountProblem?) {
        if (busy) return
        busy = true
        error = null
        refresh()
        val app = applicationContext
        thread(name = "account-call") {
            val problem = try { work(app) } catch (_: Exception) { AccountProblem.NETWORK }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (problem == null) password = ""
                error = problem?.let { problemText(this, it) }
                refresh()
            }
        }
    }
}
