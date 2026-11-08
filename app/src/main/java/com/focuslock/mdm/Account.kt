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
    private val PRODUCTS = listOf(EntitlementRules.MOBILE_PRO, "pc_pro", EntitlementRules.KEEPER_PLUS)

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

    fun email(c: Context): String = prefs(c).getString(K_EMAIL, "").orEmpty()

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

    private fun saveProducts(c: Context, products: JSONObject?) {
        val wasPro = hasMobilePro(c)
        val parsed = EntitlementRules.parseProducts(products)
        val e = prefs(c).edit()
        PRODUCTS.forEach { id ->
            val ent = parsed[id] ?: Entitlement(false, null)
            e.putBoolean("ent_${id}_active", ent.active).putLong("ent_${id}_exp", ent.expiresAtMs ?: -1L)
        }
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

    fun call(method: String, path: String, body: JSONObject?, bearer: String?, readTimeoutMs: Int = 30_000): Reply = try {
        val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = readTimeoutMs
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
        saveProducts(c, r.json.optJSONObject("products"))
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

    /** POST /redeem with a PayPal subscription id (I-…). Null on success. */
    fun redeem(c: Context, raw: String): AccountProblem? {
        val id = AccountRules.subscriptionId(raw) ?: return AccountProblem.INVALID_CODE
        val t = token(c) ?: return AccountProblem.SIGNED_OUT
        val r = call("POST", "/redeem", JSONObject().put("code", id), t)
        if (r.code == 401) { forget(c); return AccountProblem.SIGNED_OUT }
        if (!r.ok) return AccountRules.problem(r.code, r.reason)
        saveProducts(c, r.json.optJSONObject("products"))
        return null
    }

    /** POST /auth/logout if we can, then forget everything here either way. */
    fun signOut(c: Context) {
        token(c)?.let { call("POST", "/auth/logout", JSONObject(), it) }
        forget(c)
    }

    /** Bearer for the Keeper proxy; only [KeeperClient] uses it. */
    internal fun keeperToken(c: Context): String? = token(c)
}
