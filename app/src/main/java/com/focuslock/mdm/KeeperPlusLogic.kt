package com.focuslock.mdm

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The pure rules behind the account and Keeper Plus: no Android types, so each
 * one is unit-tested on the desktop. [Account] and [KeeperClient] do the I/O.
 */

/** One product as the server last reported it (`products.<id>` in GET /v1/me). */
data class Entitlement(val active: Boolean, val expiresAtMs: Long?)

object EntitlementRules {
    const val MOBILE_PRO = "mobile_pro"
    const val KEEPER_PLUS = "keeper_plus"
    const val KEEPER_FREE = "keeper_free"

    /** Offline, the last answer from the server counts for this long. */
    const val OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Whether a remembered entitlement still counts at [nowMs]. It must have
     * been active when fetched, not be past its own expiry, and the fetch must be
     * no older than [OFFLINE_GRACE_MS]. A fetch "in the future" (the clock was
     * moved back) does not count: the next refresh settles it.
     */
    fun isActive(e: Entitlement?, fetchedAtMs: Long, nowMs: Long): Boolean {
        if (e == null || !e.active || fetchedAtMs <= 0L) return false
        val age = nowMs - fetchedAtMs
        if (age < 0 || age > OFFLINE_GRACE_MS) return false
        return e.expiresAtMs == null || nowMs < e.expiresAtMs
    }

    /**
     * Which server tier of the Keeper counts at [nowMs]: the tier /v1/me named ("plus" or
     * "free"), only while its own entitlement passes [isActive]. Null means no server tier.
     */
    fun serverTier(tier: String?, plus: Entitlement?, free: Entitlement?, fetchedAtMs: Long, nowMs: Long): String? = when (tier) {
        "plus" -> "plus".takeIf { isActive(plus, fetchedAtMs, nowMs) }
        "free" -> "free".takeIf { isActive(free, fetchedAtMs, nowMs) }
        else -> null
    }

    /** The server writes `Date.toISOString()`: 2026-12-01T10:00:00.000Z. */
    fun parseIso(raw: String?): Long? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                return f.parse(raw)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    /** `{ "mobile_pro": { "active": true, "expiresAt": null }, ... }` → map. */
    fun parseProducts(products: JSONObject?): Map<String, Entitlement> {
        products ?: return emptyMap()
        val out = LinkedHashMap<String, Entitlement>()
        val keys = products.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val o = products.optJSONObject(id) ?: continue
            val exp = if (o.isNull("expiresAt")) null else parseIso(o.optString("expiresAt"))
            out[id] = Entitlement(o.optBoolean("active", false), exp)
        }
        return out
    }
}

/** Where one Keeper check goes. */
enum class KeeperRoute { PLUS, NONE }

object KeeperRouting {
    /** The proxy's own limit (accounts.js MAX_IMAGE_BYTES), counted on the decoded JPEG. */
    const val MAX_IMAGE_BYTES = 1_572_864
    const val IMAGE_PREFIX = "data:image/jpeg;base64,"
    const val DEFAULT_DAILY_LIMIT = 200

    /**
     * The Keeper runs only on the server (Keeper Plus or the free tier): there is no
     * own-key route. [plusActive] means "a server tier is available".
     */
    fun choose(signedIn: Boolean, plusActive: Boolean): KeeperRoute =
        if (signedIn && plusActive) KeeperRoute.PLUS else KeeperRoute.NONE

    /** The proxy takes only a JPEG data URL whose decoded size is within its limit. */
    fun imageFits(dataUrl: String?): Boolean {
        if (dataUrl == null || !dataUrl.startsWith(IMAGE_PREFIX)) return false
        val b64 = dataUrl.length - IMAGE_PREFIX.length
        val padding = when { dataUrl.endsWith("==") -> 2; dataUrl.endsWith("=") -> 1; else -> 0 }
        return b64 / 4L * 3 - padding <= MAX_IMAGE_BYTES
    }

    sealed class Proxy {
        data class Reply(val text: String, val usedToday: Int, val limit: Int) : Proxy()
        data class Fail(val why: KeeperResult.Why, val limit: Int = DEFAULT_DAILY_LIMIT) : Proxy()
        /** Not the person's fault and not final: the server could not be reached or is not set up. */
        object Fallback : Proxy()
    }

    /**
     * POST /v1/keeper's answer → what the Keeper does next. [code] is -1 when no
     * response arrived at all (offline, timeout, a cold server).
     */
    fun mapProxy(code: Int, body: String): Proxy {
        val o = try { JSONObject(body) } catch (_: Exception) { null }
        return when {
            code in 200..299 -> {
                val ok = o?.takeIf { it.optBoolean("ok") && it.has("reply") && !it.isNull("reply") }
                if (ok == null) Proxy.Fail(KeeperResult.Why.UNREADABLE)
                else Proxy.Reply(ok.optString("reply"), ok.optInt("usedToday", 0), ok.optInt("limit", DEFAULT_DAILY_LIMIT))
            }
            code == 429 -> Proxy.Fail(KeeperResult.Why.DAILY_LIMIT, o?.optInt("limit", DEFAULT_DAILY_LIMIT) ?: DEFAULT_DAILY_LIMIT)
            // The free tier's upstream rate limit; the count was refunded. Say so, don't switch keys.
            code == 503 && (o?.optString("error") == "upstream_busy" || o?.optString("reason") == "upstream_busy") ->
                Proxy.Fail(KeeperResult.Why.BUSY)
            // 503: accounts or the proxy not set up yet. 401/403: the session or the
            // subscription is gone. None of these is the person's fault.
            code == 503 || code == 401 || code == 403 || code < 0 -> Proxy.Fallback
            else -> Proxy.Fail(KeeperResult.Why.NETWORK)
        }
    }
}

/** What went wrong with an account call, in words the screen can pick a string for. */
enum class AccountProblem {
    NOT_AVAILABLE, SUBSCRIPTIONS_NOT_AVAILABLE, INVALID_INPUT, TAKEN, BAD_CREDENTIALS, TOO_MANY,
    SIGNED_OUT, NOT_FOUND, INACTIVE, ALREADY_USED, INVALID_CODE, NETWORK,
    PASSWORD_TOO_SHORT, PASSWORD_TOO_COMMON, WRONG_EMAIL_CODE, CODE_EXPIRED, CODE_LOCKED,
    MAIL_NOT_CONFIGURED, PROVIDER_NOT_CONFIGURED, EMAIL_UNVERIFIED,
    // Only from the browser sign-in's pinehold://auth?error=<code>.
    SIGN_IN_EXPIRED, SIGN_IN_CANCELLED, PROVIDER_ERROR, PROVIDER_EMAIL_UNVERIFIED, ACCOUNT_UNVERIFIED
}

/** A problem plus what the server added to it: tries left on a code, seconds until a resend. */
data class AccountFailure(val problem: AccountProblem, val attemptsLeft: Int? = null, val retryAfterSec: Int? = null)

object AccountRules {
    private val SUBSCRIPTION_ID = Regex("^I-[A-Z0-9]{6,40}$")

    /** A PayPal subscription id as the server's /redeem accepts it, or null. */
    fun subscriptionId(raw: String?): String? =
        raw?.trim()?.uppercase()?.takeIf { SUBSCRIPTION_ID.matches(it) }

    /**
     * What /redeem takes on the Keeper screen: a subscription id, or the PC code from an
     * Everywhere purchase, which carries months of Keeper Plus. Null for anything else.
     */
    fun redeemable(raw: String?): String? {
        subscriptionId(raw)?.let { return it }
        val t = raw?.trim()?.uppercase() ?: return null
        val compact = t.replace(Regex("[^A-Z0-9]"), "")
        // KP-: Keeper Plus paid once (a month or a year), as the access page shows it.
        if (compact.startsWith("KP") && compact.length in 12..42) return "KP-" + compact.substring(2)
        return t.takeIf { it.startsWith("FLP") && compact.length >= 12 }
    }

    /** The server's own minimum (auth.js); checked here first so a short one never leaves the phone. */
    const val MIN_PASSWORD = 10

    /**
     * Non-2xx answers from /v1 → a problem. [code] is -1 when nothing came back.
     * [emailCode] is set by the calls that send a 6-digit code (verify, reset),
     * where `invalid_code` means that code, not a subscription id.
     */
    fun problem(code: Int, reason: String?, emailCode: Boolean = false): AccountProblem = when {
        code < 0 -> AccountProblem.NETWORK
        reason == "subscriptions_not_configured" -> AccountProblem.SUBSCRIPTIONS_NOT_AVAILABLE
        reason == "mail_not_configured" -> AccountProblem.MAIL_NOT_CONFIGURED
        reason == "provider_not_configured" -> AccountProblem.PROVIDER_NOT_CONFIGURED
        code == 503 -> AccountProblem.NOT_AVAILABLE
        reason == "too_many_attempts" -> AccountProblem.CODE_LOCKED
        code == 429 -> AccountProblem.TOO_MANY
        reason == "invalid_credentials" -> AccountProblem.BAD_CREDENTIALS
        code == 401 -> AccountProblem.SIGNED_OUT
        reason == "email_unverified" -> AccountProblem.EMAIL_UNVERIFIED
        reason == "registration_failed" -> AccountProblem.TAKEN
        reason == "password_too_short" -> AccountProblem.PASSWORD_TOO_SHORT
        reason == "password_too_common" -> AccountProblem.PASSWORD_TOO_COMMON
        reason == "code_expired" -> AccountProblem.CODE_EXPIRED
        reason == "already_used" -> AccountProblem.ALREADY_USED
        reason == "subscription_inactive" || code == 402 -> AccountProblem.INACTIVE
        reason == "invalid_code" -> if (emailCode) AccountProblem.WRONG_EMAIL_CODE else AccountProblem.INVALID_CODE
        code == 400 -> AccountProblem.INVALID_INPUT
        code == 404 -> AccountProblem.NOT_FOUND
        else -> AccountProblem.NETWORK
    }

    /** The same mapping, keeping `attemptsLeft` and `retryAfter` when the server sent them. */
    fun failure(code: Int, json: JSONObject, emailCode: Boolean = false): AccountFailure = AccountFailure(
        problem(code, json.optString("reason").takeIf { it.isNotBlank() }, emailCode),
        if (json.has("attemptsLeft")) json.optInt("attemptsLeft") else null,
        if (json.has("retryAfter")) json.optInt("retryAfter") else null
    )

    /** `pinehold://auth?error=<code>` → a problem. Anything unknown reads as the provider failing. */
    fun oauthProblem(error: String?): AccountProblem = when (error) {
        "invalid_state", "try_again" -> AccountProblem.SIGN_IN_EXPIRED
        "access_denied" -> AccountProblem.SIGN_IN_CANCELLED
        "email_unverified" -> AccountProblem.PROVIDER_EMAIL_UNVERIFIED
        "account_unverified" -> AccountProblem.ACCOUNT_UNVERIFIED
        "provider_not_configured" -> AccountProblem.PROVIDER_NOT_CONFIGURED
        else -> AccountProblem.PROVIDER_ERROR
    }

    /** Checks left today, or null when the server has not reported on today (UTC). */
    fun checksLeft(usedDay: String, usedToday: Int, limit: Int, todayUtc: String): Int? =
        if (limit <= 0 || usedDay != todayUtc) null else (limit - usedToday).coerceAtLeast(0)
}

/** What `pinehold://auth?…` carried, once checked. */
sealed class AuthLink {
    data class Token(val token: String) : AuthLink()
    data class Error(val problem: AccountProblem) : AuthLink()
    object Invalid : AuthLink()

    companion object {
        /** The session is 32 random bytes in a URL-safe encoding; anything far longer is not ours. */
        const val MAX_TOKEN = 256
        private const val MIN_TOKEN = 16
        private val TOKEN = Regex("^[A-Za-z0-9_.~-]+$")
        private val ERROR = Regex("^[a-z_]{1,40}$")

        /**
         * Any app can open this link, so nothing in it is trusted: exactly one of
         * `token` or `error`, a token of URL-safe characters within [MAX_TOKEN],
         * an error of lowercase words. Percent-escapes are refused, not decoded.
         */
        fun parse(raw: String?): AuthLink {
            if (raw == null || raw.length > MAX_TOKEN + 64) return Invalid
            val uri = try { java.net.URI(raw) } catch (_: Exception) { return Invalid }
            if (uri.scheme != "pinehold" || uri.host != "auth") return Invalid
            val pairs = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.map { it.substringBefore('=') to it.substringAfter('=', "") }
                ?: return Invalid
            if (pairs.size != 1) return Invalid
            val (key, value) = pairs[0]
            return when {
                key == "token" && value.length in MIN_TOKEN..MAX_TOKEN && TOKEN.matches(value) -> Token(value)
                key == "error" && ERROR.matches(value) -> Error(AccountRules.oauthProblem(value))
                else -> Invalid
            }
        }
    }
}

/** The "Resend code" countdown. Times are [android.os.SystemClock.elapsedRealtime] milliseconds. */
object ResendRules {
    const val COOLDOWN_SEC = 60
    private const val MAX_WAIT_SEC = 600

    /** When the next resend may go: the server's `retryAfter` if it sent one, else the usual minute. */
    fun availableAt(nowMs: Long, retryAfterSec: Int? = null): Long =
        nowMs + (retryAfterSec ?: COOLDOWN_SEC).coerceIn(1, MAX_WAIT_SEC) * 1000L

    /** Whole seconds left, rounded up; 0 means the button is live. */
    fun secondsLeft(availableAtMs: Long, nowMs: Long): Int {
        val left = availableAtMs - nowMs
        if (left <= 0) return 0
        return ((left + 999) / 1000).coerceAtMost(MAX_WAIT_SEC.toLong()).toInt()
    }
}
