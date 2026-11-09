package com.focuslock.mdm

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Where the browser sign-in lands: `pinehold://auth?token=…` or `?error=<code>`.
 * No UI of its own. It checks the link ([AuthLink]), keeps the session through
 * [Account], and hands over to [AccountActivity], which refreshes the account
 * and says how it went.
 *
 * Exported because the browser must reach it, so the link is never trusted: it
 * is acted on only if a sign-in was started from this phone moments ago.
 */
class AuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val link = AuthLink.parse(intent?.dataString)
        if (link is AuthLink.Invalid || !Account.consumeOAuthStarted(this)) return
        val next = Intent(this, AccountActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when (link) {
            is AuthLink.Token -> {
                try {
                    Account.adoptToken(this, link.token)
                    next.putExtra(AccountActivity.EXTRA_OAUTH_DONE, true)
                } catch (_: Exception) {
                    next.putExtra(AccountActivity.EXTRA_OAUTH_PROBLEM, AccountProblem.PROVIDER_ERROR.name)
                }
            }
            is AuthLink.Error -> next.putExtra(AccountActivity.EXTRA_OAUTH_PROBLEM, link.problem.name)
            AuthLink.Invalid -> return
        }
        startActivity(next)
    }
}
