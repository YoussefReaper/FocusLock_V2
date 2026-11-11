package com.focuslock.mdm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

/**
 * The optional Pinehold account: one sign-in that carries Pro and Keeper Plus
 * across phone and PC. Everything here is blocking unless it says otherwise;
 * call it off the main thread.
 *
 * The session token is encrypted with a keystore key the same way the
 * OpenRouter key is ([KeeperKey]), and is never logged or shown. What the
 * server last said about the account is cached so the app works offline, for
 * up to [EntitlementRules.OFFLINE_GRACE_MS].
 */
object Account {
    const val API = "https://focuslock-payment.onrender.com/v1"
    /** Email/password sign-up and sign-in are switched off for now; the code paths stay. Browser sign-in (Google, GitHub) is the way in. */
    const val EMAIL_SIGNIN_ENABLED = false

    /** Render's free plan cold-starts in up to a minute, so connect and read both wait that long. */
    private const val TIMEOUT_MS = 60_000

    const val KEEPER_PLUS_URL = "https://focus-lock-c0xg.onrender.com/keeper-plus.html"

    private const val PREFS = "account"
    private const val ALIAS = "pinehold_account_v1"
    private const val K_CIPHER = "token_cipher"
    private const val K_IV = "token_iv"
    private const val K_EMAIL = "email"
    private const val K_FETCHED = "fetched_at"
    private const val K_USED_DAY = "keeper_used_day"
    private const val K_USED = "keeper_used"
    private const val K_LIMIT = "keeper_limit"
    private const val K_TIER = "keeper_tier"
    private const val K_VERIFIED = "verified"

    /** Kept apart from [PREFS]: [forget] clears that file, and a browser sign-in in flight must survive it. */
    private const val OAUTH_PREFS = "account_oauth"
    private const val K_OAUTH_STARTED = "started_at"
    /** The server's state lives 10 minutes; a little longer here for a slow return from the browser. */
    private const val OAUTH_WINDOW_MS = 15L * 60 * 1000
    private val PRODUCTS = listOf(EntitlementRules.MOBILE_PRO, "pc_pro", EntitlementRules.KEEPER_PLUS, EntitlementRules.KEEPER_FREE)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── the token ─────────────────────────────────────────────────

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun saveToken(c: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(c).edit()
            .putString(K_CIPHER, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun token(c: Context): String? = try {
        val p = prefs(c)
        val enc = Base64.decode(p.getString(K_CIPHER, null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(p.getString(K_IV, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(enc), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun signedIn(c: Context): Boolean = prefs(c).contains(K_CIPHER)

    /** The session token, for the account-backed features outside this file (promise sync). */
    fun sessionToken(c: Context): String? = token(c)

    fun email(c: Context): String = prefs(c).getString(K_EMAIL, "").orEmpty()

    /**
     * Whether the server says the email is confirmed. Until it has said (an
     * account signed in before this field existed) it counts as verified: the
     * server still refuses what needs it with `email_unverified`.
     */
    fun verified(c: Context): Boolean = prefs(c).getBoolean(K_VERIFIED, true)

    private fun saveVerified(c: Context, json: JSONObject?) {
        if (json != null && json.has("verified")) prefs(c).edit().putBoolean(K_VERIFIED, json.optBoolean("verified", true)).apply()
    }

    // ── what the server last said ─────────────────────────────────

    fun entitlement(c: Context, product: String): Entitlement? {
        val p = prefs(c)
        if (!p.contains("ent_${product}_active")) return null
        val exp = p.getLong("ent_${product}_exp", -1L)
        return Entitlement(p.getBoolean("ent_${product}_active", false), if (exp > 0) exp else null)
    }

    fun isActive(c: Context, product: String): Boolean =
        signedIn(c) && EntitlementRules.isActive(entitlement(c, product), prefs(c).getLong(K_FETCHED, 0L), System.currentTimeMillis())

    fun hasMobilePro(c: Context): Boolean = isActive(c, EntitlementRules.MOBILE_PRO)

    fun keeperPlusActive(c: Context): Boolean = isActive(c, EntitlementRules.KEEPER_PLUS)

    /** "plus", "free", or null: the server tier the Keeper can use now (expiry and offline grace apply). */
    fun keeperTier(c: Context): String? {
        if (!signedIn(c)) return null
        val p = prefs(c)
        return EntitlementRules.serverTier(
            p.getString(K_TIER, null),
            entitlement(c, EntitlementRules.KEEPER_PLUS), entitlement(c, EntitlementRules.KEEPER_FREE),
            p.getLong(K_FETCHED, 0L), System.currentTimeMillis()
        )
    }

    /** Whether Keeper checks can go through the server: Keeper Plus or the free tier. */
    fun keeperServerAvailable(c: Context): Boolean = keeperTier(c) != null

    fun keeperLimit(c: Context): Int = prefs(c).getInt(K_LIMIT, KeeperRouting.DEFAULT_DAILY_LIMIT)

    /** Checks left on the server today, if it has told us. */
    fun checksLeftToday(c: Context): Int? {
        val p = prefs(c)
        return AccountRules.checksLeft(p.getString(K_USED_DAY, "").orEmpty(), p.getInt(K_USED, 0), p.getInt(K_LIMIT, 0), todayUtc())
    }

    /** The proxy reports usage with every answer; keep the latest. */
    fun recordUsage(c: Context, usedToday: Int, limit: Int) {
        prefs(c).edit().putString(K_USED_DAY, todayUtc()).putInt(K_USED, usedToday).putInt(K_LIMIT, limit).apply()
    }

    private fun todayUtc(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun saveProducts(c: Context, products: JSONObject?, tier: String?) {
        val wasPro = hasMobilePro(c)
        val parsed = EntitlementRules.parseProducts(products)
        val e = prefs(c).edit()
        PRODUCTS.forEach { id ->
            val ent = parsed[id] ?: Entitlement(false, null)
            e.putBoolean("ent_${id}_active", ent.active).putLong("ent_${id}_exp", ent.expiresAtMs ?: -1L)
        }
        e.putString(K_TIER, tier)
        e.putLong(K_FETCHED, System.currentTimeMillis()).commit()
        if (wasPro != hasMobilePro(c)) PolicySync.request(c, "pro_tier_change")
    }

    private fun forget(c: Context) {
        val wasPro = hasMobilePro(c)
        prefs(c).edit().clear().commit()
        if (wasPro) PolicySync.request(c, "pro_tier_change")
    }

    // ── calls ─────────────────────────────────────────────────────

    /** One /v1 answer. [code] is -1 when nothing came back. */
    class Reply(val code: Int, val json: JSONObject) {
        val ok: Boolean get() = code in 200..299
        val reason: String? get() = json.optString("reason").takeIf { it.isNotBlank() }
    }

    fun call(method: String, path: String, body: JSONObject?, bearer: String?, readTimeoutMs: Int = TIMEOUT_MS): Reply = try {
        val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = TIMEOUT_MS; readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        Reply(code, try { JSONObject(text) } catch (_: Exception) { JSONObject() })
    } catch (_: Exception) {
        Reply(-1, JSONObject())
    }

    /** POST /auth/login or /auth/register, then GET /me. Null on success. */
    fun signIn(c: Context, email: String, password: String, create: Boolean): AccountProblem? {
        val body = JSONObject().put("email", email.trim()).put("password", password).put("device", "android")
        val r = call("POST", if (create) "/auth/register" else "/auth/login", body, null)
        if (!r.ok) return AccountRules.problem(r.code, r.reason)
        val token = r.json.optString("token")
        if (token.isBlank()) return AccountProblem.NETWORK
        forget(c)
        saveToken(c, token)
        prefs(c).edit().putString(K_EMAIL, r.json.optJSONObject("user")?.optString("email") ?: email.trim().lowercase()).apply()
        saveVerified(c, r.json.optJSONObject("user"))
        refresh(c)
        return null
    }

    /** GET /me. A rejected session signs out here; offline keeps the cache. Null on success. */
    fun refresh(c: Context): AccountProblem? {
        val t = token(c) ?: run { if (signedIn(c)) forget(c); return AccountProblem.SIGNED_OUT }
        val r = call("GET", "/me", null, t)
        if (r.code == 401) { forget(c); return AccountProblem.SIGNED_OUT }
        if (!r.ok) return AccountRules.problem(r.code, r.reason)
        r.json.optString("email").takeIf { it.isNotBlank() }?.let { prefs(c).edit().putString(K_EMAIL, it).apply() }
        saveVerified(c, r.json)
        saveProducts(c, r.json.optJSONObject("products"), r.json.optJSONObject("keeper")?.let { k -> if (k.isNull("tier")) null else k.optString("tier").takeIf { it.isNotBlank() } })
        r.json.optJSONObject("keeper")?.let { recordUsage(c, it.optInt("usedToday", 0), it.optInt("limit", KeeperRouting.DEFAULT_DAILY_LIMIT)) }
        return null
    }

    /** Refresh in the background when signed in; [done] runs on that thread. */
    fun refreshAsync(c: Context, done: ((AccountProblem?) -> Unit)? = null) {
        if (!signedIn(c)) return
        val app = c.applicationContext
        thread(name = "account-refresh") {
            val problem = try { refresh(app) } catch (_: Exception) { AccountProblem.NETWORK }
            done?.invoke(problem)
        }
    }

    /** POST /redeem with a PayPal subscription id (I-…) or an Everywhere PC code (FLP-…). Null on success. */
    fun redeem(c: Context, raw: String): AccountProblem? {
        val id = AccountRules.redeemable(raw) ?: return AccountProblem.INVALID_CODE
        val t = token(c) ?: return AccountProblem.SIGNED_OUT
        val r = call("POST", "/redeem", JSONObject().put("code", id), t)
        if (r.code == 401) { forget(c); return AccountProblem.SIGNED_OUT }
        if (!r.ok) return AccountRules.problem(r.code, r.reason)
        // Success means Keeper Plus: a subscription, or the months an Everywhere code carries.
        val products = r.json.optJSONObject("products")
        val plus = products?.optJSONObject("keeper_plus")?.optBoolean("active") == true
        saveProducts(c, products, if (plus) "plus" else prefs(c).getString(K_TIER, null))
        return null
    }

    /** POST /partner/request: emails the partner a code with the person's words. Null on success, else the reason. */
    fun partnerRequest(c: Context, email: String, words: String): String? {
        val t = token(c) ?: return "signed_out"
        val r = call("POST", "/partner/request", JSONObject().put("partnerEmail", email).put("words", words), t)
        return if (r.ok) null else r.reason ?: "network"
    }

    /** POST /partner/verify. Null when the code is right, else the reason. */
    fun partnerVerify(c: Context, code: String): String? {
        val t = token(c) ?: return "signed_out"
        val r = call("POST", "/partner/verify", JSONObject().put("code", code), t)
        return if (r.ok) null else r.reason ?: "network"
    }

    /** POST /auth/logout if we can, then forget everything here either way. */
    fun signOut(c: Context) {
        token(c)?.let { call("POST", "/auth/logout", JSONObject(), it) }
        forget(c)
    }

    /** POST /auth/logout-all. Signed out here only once the server has done it everywhere. Null on success. */
    fun signOutEverywhere(c: Context): AccountProblem? {
        val t = token(c) ?: run { forget(c); return null }
        val r = call("POST", "/auth/logout-all", JSONObject(), t)
        if (!r.ok && r.code != 401) return AccountRules.problem(r.code, r.reason)
        forget(c)
        return null
    }

    // ── email codes and browser sign-in ───────────────────────────

    /** What GET /auth/providers says is switched on. */
    data class Providers(val google: Boolean, val github: Boolean, val email: Boolean, val mailConfigured: Boolean)

    /** GET /auth/providers, or null when the server did not answer. */
    fun providers(): Providers? {
        val r = call("GET", "/auth/providers", null, null)
        if (!r.ok) return null
        return Providers(r.json.optBoolean("google"), r.json.optBoolean("github"), r.json.optBoolean("email", true), r.json.optBoolean("mailConfigured"))
    }

    /** POST /auth/verify. On success the cached account is marked verified and refreshed. Null on success. */
    fun verify(c: Context, email: String, code: String): AccountFailure? {
        val r = call("POST", "/auth/verify", JSONObject().put("email", email.trim()).put("code", code.trim()), null)
        if (!r.ok) return AccountRules.failure(r.code, r.json, emailCode = true)
        if (signedIn(c)) {
            prefs(c).edit().putBoolean(K_VERIFIED, true).apply()
            refresh(c)
        }
        return null
    }

    /** POST /auth/resend: a new verification code. Null on success. */
    fun resend(email: String): AccountFailure? {
        val r = call("POST", "/auth/resend", JSONObject().put("email", email.trim()), null)
        return if (r.ok) null else AccountRules.failure(r.code, r.json)
    }

    /** POST /auth/forgot: the server answers the same whether or not the email has an account. Null on success. */
    fun forgot(email: String): AccountFailure? {
        val r = call("POST", "/auth/forgot", JSONObject().put("email", email.trim()), null)
        return if (r.ok) null else AccountRules.failure(r.code, r.json)
    }

    /**
     * POST /auth/reset. The server ends every session of that account, so if it
     * is the one signed in here, it is forgotten too. Null on success.
     */
    fun reset(c: Context, email: String, code: String, newPassword: String): AccountFailure? {
        val body = JSONObject().put("email", email.trim()).put("code", code.trim()).put("newPassword", newPassword)
        val r = call("POST", "/auth/reset", body, null)
        if (!r.ok) return AccountRules.failure(r.code, r.json, emailCode = true)
        if (signedIn(c) && email(c).equals(email.trim(), ignoreCase = true)) forget(c)
        return null
    }

    /** Where "Continue with Google/GitHub" goes; the server sends the browser back to pinehold://auth. */
    fun providerStartUrl(provider: String): String = "$API/auth/$provider/start?client=app"

    /** Called as the browser opens, so [AuthCallbackActivity] knows a sign-in was asked for. */
    fun markOAuthStarted(c: Context) {
        c.applicationContext.getSharedPreferences(OAUTH_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(K_OAUTH_STARTED, System.currentTimeMillis()).apply()
    }

    /**
     * True once, if a browser sign-in began here within [OAUTH_WINDOW_MS]. Any
     * app can fire pinehold://auth; this stops one from signing the phone into
     * an account of its choosing when no sign-in was under way.
     */
    fun consumeOAuthStarted(c: Context): Boolean {
        val p = c.applicationContext.getSharedPreferences(OAUTH_PREFS, Context.MODE_PRIVATE)
        val started = p.getLong(K_OAUTH_STARTED, 0L)
        p.edit().remove(K_OAUTH_STARTED).apply()
        val age = System.currentTimeMillis() - started
        return started > 0L && age in 0..OAUTH_WINDOW_MS
    }

    /** Keep a session the browser sign-in handed back. Accounts made that way are verified. */
    fun adoptToken(c: Context, token: String) {
        forget(c)
        saveToken(c, token)
        prefs(c).edit().putBoolean(K_VERIFIED, true).apply()
    }

    /** Bearer for the Keeper proxy; only [KeeperClient] uses it. */
    internal fun keeperToken(c: Context): String? = token(c)
}
