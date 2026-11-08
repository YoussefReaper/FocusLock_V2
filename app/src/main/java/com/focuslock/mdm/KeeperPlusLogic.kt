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
enum class KeeperRoute { PLUS, OWN_KEY, NONE }

object KeeperRouting {
    /** The proxy's own limit (accounts.js MAX_IMAGE_BYTES), counted on the decoded JPEG. */
    const val MAX_IMAGE_BYTES = 1_572_864
    const val IMAGE_PREFIX = "data:image/jpeg;base64,"
    const val DEFAULT_DAILY_LIMIT = 200

    /** Keeper Plus wins whenever it is active; the person's own key is used only without it. */
    fun choose(signedIn: Boolean, plusActive: Boolean, hasOwnKey: Boolean): KeeperRoute = when {
        signedIn && plusActive -> KeeperRoute.PLUS
        hasOwnKey -> KeeperRoute.OWN_KEY
        else -> KeeperRoute.NONE
    }

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
        /** Not the person's fault and not final: use their own key if they have one. */
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
            // 503: accounts or the proxy not set up yet. 401/403: the session or the
            // subscription is gone. None of these is final for someone with a key.
            code == 503 || code == 401 || code == 403 || code < 0 -> Proxy.Fallback
            else -> Proxy.Fail(KeeperResult.Why.NETWORK)
        }
    }
}

/** What went wrong with an account call, in words the screen can pick a string for. */
enum class AccountProblem {
    NOT_AVAILABLE, SUBSCRIPTIONS_NOT_AVAILABLE, INVALID_INPUT, TAKEN, BAD_CREDENTIALS, TOO_MANY,
    SIGNED_OUT, NOT_FOUND, INACTIVE, ALREADY_USED, INVALID_CODE, NETWORK
}

object AccountRules {
    private val SUBSCRIPTION_ID = Regex("^I-[A-Z0-9]{6,40}$")

    /** A PayPal subscription id as the server's /redeem accepts it, or null. */
    fun subscriptionId(raw: String?): String? =
        raw?.trim()?.uppercase()?.takeIf { SUBSCRIPTION_ID.matches(it) }

    /** Non-2xx answers from /v1 → a problem. [code] is -1 when nothing came back. */
    fun problem(code: Int, reason: String?): AccountProblem = when {
        code < 0 -> AccountProblem.NETWORK
        reason == "subscriptions_not_configured" -> AccountProblem.SUBSCRIPTIONS_NOT_AVAILABLE
        code == 503 -> AccountProblem.NOT_AVAILABLE
        code == 429 -> AccountProblem.TOO_MANY
        reason == "invalid_credentials" -> AccountProblem.BAD_CREDENTIALS
        code == 401 -> AccountProblem.SIGNED_OUT
        reason == "registration_failed" -> AccountProblem.TAKEN
        reason == "already_used" -> AccountProblem.ALREADY_USED
        reason == "subscription_inactive" || code == 402 -> AccountProblem.INACTIVE
        reason == "invalid_code" -> AccountProblem.INVALID_CODE
        code == 400 -> AccountProblem.INVALID_INPUT
        code == 404 -> AccountProblem.NOT_FOUND
        else -> AccountProblem.NETWORK
    }

    /** Checks left today, or null when the server has not reported on today (UTC). */
    fun checksLeft(usedDay: String, usedToday: Int, limit: Int, todayUtc: String): Int? =
        if (limit <= 0 || usedDay != todayUtc) null else (limit - usedToday).coerceAtLeast(0)
}
