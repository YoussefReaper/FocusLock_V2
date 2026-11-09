package com.focuslock.mdm

/**
 * Every [AccountProblem] → the line the account screens show. Kept apart from
 * the activities so the mapping is unit-tested without one.
 */
object AccountMessages {
    fun res(p: AccountProblem): Int = when (p) {
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
        AccountProblem.PASSWORD_TOO_SHORT -> R.string.account_err_password_short
        AccountProblem.PASSWORD_TOO_COMMON -> R.string.account_err_password_common
        AccountProblem.WRONG_EMAIL_CODE -> R.string.account_err_wrong_code
        AccountProblem.CODE_EXPIRED -> R.string.account_err_code_expired
        AccountProblem.CODE_LOCKED -> R.string.account_err_code_locked
        AccountProblem.MAIL_NOT_CONFIGURED -> R.string.account_err_mail
        AccountProblem.PROVIDER_NOT_CONFIGURED -> R.string.account_err_provider_off
        AccountProblem.EMAIL_UNVERIFIED -> R.string.account_err_email_unverified
        AccountProblem.SIGN_IN_EXPIRED -> R.string.account_err_sign_in_expired
        AccountProblem.SIGN_IN_CANCELLED -> R.string.account_err_sign_in_cancelled
        AccountProblem.PROVIDER_ERROR -> R.string.account_err_provider
        AccountProblem.PROVIDER_EMAIL_UNVERIFIED -> R.string.account_err_provider_email
        AccountProblem.ACCOUNT_UNVERIFIED -> R.string.account_err_account_unverified
    }
}
