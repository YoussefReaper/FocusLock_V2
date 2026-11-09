package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRulesTest {

    // ── server reason → problem → message ──

    @Test fun authReasonsGetTheirOwnProblem() {
        assertEquals(AccountProblem.PASSWORD_TOO_SHORT, AccountRules.problem(400, "password_too_short"))
        assertEquals(AccountProblem.PASSWORD_TOO_COMMON, AccountRules.problem(400, "password_too_common"))
        assertEquals(AccountProblem.CODE_EXPIRED, AccountRules.problem(400, "code_expired", emailCode = true))
        assertEquals(AccountProblem.CODE_LOCKED, AccountRules.problem(429, "too_many_attempts", emailCode = true))
        assertEquals(AccountProblem.TOO_MANY, AccountRules.problem(429, "rate_limited"))
        assertEquals(AccountProblem.MAIL_NOT_CONFIGURED, AccountRules.problem(503, "mail_not_configured"))
        assertEquals(AccountProblem.PROVIDER_NOT_CONFIGURED, AccountRules.problem(503, "provider_not_configured"))
        assertEquals(AccountProblem.EMAIL_UNVERIFIED, AccountRules.problem(403, "email_unverified"))
        assertEquals(AccountProblem.TAKEN, AccountRules.problem(409, "registration_failed"))
        assertEquals(AccountProblem.BAD_CREDENTIALS, AccountRules.problem(401, "invalid_credentials"))
        assertEquals(AccountProblem.INVALID_INPUT, AccountRules.problem(400, "invalid_input"))
    }

    @Test fun invalidCodeDependsOnTheCall() {
        assertEquals(AccountProblem.WRONG_EMAIL_CODE, AccountRules.problem(400, "invalid_code", emailCode = true))
        assertEquals(AccountProblem.INVALID_CODE, AccountRules.problem(400, "invalid_code"))
    }

    @Test fun failureKeepsAttemptsAndRetryAfter() {
        val f = AccountRules.failure(400, org.json.JSONObject("""{"ok":false,"reason":"invalid_code","attemptsLeft":3}"""), emailCode = true)
        assertEquals(AccountFailure(AccountProblem.WRONG_EMAIL_CODE, attemptsLeft = 3), f)
        val r = AccountRules.failure(429, org.json.JSONObject("""{"ok":false,"reason":"rate_limited","retryAfter":42}"""))
        assertEquals(AccountFailure(AccountProblem.TOO_MANY, retryAfterSec = 42), r)
    }

    @Test fun oauthErrorsMap() {
        assertEquals(AccountProblem.SIGN_IN_EXPIRED, AccountRules.oauthProblem("invalid_state"))
        assertEquals(AccountProblem.SIGN_IN_EXPIRED, AccountRules.oauthProblem("try_again"))
        assertEquals(AccountProblem.SIGN_IN_CANCELLED, AccountRules.oauthProblem("access_denied"))
        assertEquals(AccountProblem.PROVIDER_ERROR, AccountRules.oauthProblem("provider_error"))
        assertEquals(AccountProblem.PROVIDER_EMAIL_UNVERIFIED, AccountRules.oauthProblem("email_unverified"))
        assertEquals(AccountProblem.ACCOUNT_UNVERIFIED, AccountRules.oauthProblem("account_unverified"))
        assertEquals(AccountProblem.PROVIDER_NOT_CONFIGURED, AccountRules.oauthProblem("provider_not_configured"))
        assertEquals(AccountProblem.PROVIDER_ERROR, AccountRules.oauthProblem("something_new"))
    }

    @Test fun everyProblemHasItsOwnMessage() {
        val ids = AccountProblem.values().map { AccountMessages.res(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(R.string.account_err_wrong_code, AccountMessages.res(AccountProblem.WRONG_EMAIL_CODE))
        assertEquals(R.string.account_err_password_common, AccountMessages.res(AccountProblem.PASSWORD_TOO_COMMON))
        assertEquals(R.string.account_err_account_unverified, AccountMessages.res(AccountProblem.ACCOUNT_UNVERIFIED))
    }

    // ── pinehold://auth ──

    private val token = "AbC-123_xyz.~AbC-123_xyz.~AbC-123_xyz.~abc"

    @Test fun tokenLinkParses() = assertEquals(AuthLink.Token(token), AuthLink.parse("pinehold://auth?token=$token"))

    @Test fun errorLinkParses() =
        assertEquals(AuthLink.Error(AccountProblem.SIGN_IN_CANCELLED), AuthLink.parse("pinehold://auth?error=access_denied"))

    @Test fun badLinksAreRefused() {
        listOf(
            null, "", "pinehold://auth", "pinehold://auth?", "focuslock://auth?token=$token",
            "pinehold://pro?token=$token", "pinehold://auth?token=short",
            "pinehold://auth?token=" + "a".repeat(AuthLink.MAX_TOKEN + 1),
            "pinehold://auth?token=abc%20def0123456789", "pinehold://auth?token=abc<script>0123456789",
            "pinehold://auth?token=$token&error=access_denied", "pinehold://auth?token=$token&token=$token",
            "pinehold://auth?error=Access-Denied", "pinehold://auth?code=$token"
        ).forEach { assertEquals("refuses $it", AuthLink.Invalid, AuthLink.parse(it)) }
    }

    @Test fun longestTokenIsAccepted() {
        val t = "a".repeat(AuthLink.MAX_TOKEN)
        assertEquals(AuthLink.Token(t), AuthLink.parse("pinehold://auth?token=$t"))
    }

    // ── resend countdown ──

    @Test fun countdownRunsAMinuteByDefault() {
        val at = ResendRules.availableAt(1_000L)
        assertEquals(60, ResendRules.secondsLeft(at, 1_000L))
        assertEquals(60, ResendRules.secondsLeft(at, 1_001L))
        assertEquals(1, ResendRules.secondsLeft(at, 60_999L))
        assertEquals(0, ResendRules.secondsLeft(at, 61_000L))
        assertEquals(0, ResendRules.secondsLeft(at, 999_000L))
    }

    @Test fun serverRetryAfterWinsAndIsBounded() {
        assertEquals(42, ResendRules.secondsLeft(ResendRules.availableAt(0L, 42), 0L))
        assertEquals(1, ResendRules.secondsLeft(ResendRules.availableAt(0L, 0), 0L))
        assertTrue(ResendRules.secondsLeft(ResendRules.availableAt(0L, 100_000), 0L) <= 600)
    }

    @Test fun neverSentMeansLive() = assertEquals(0, ResendRules.secondsLeft(0L, 5_000L))
}
