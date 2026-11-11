package com.focuslock.mdm

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The session password, as on the laptop: the only thing that ends a Firm promise early.
 * Stored as a salted PBKDF2 hash; there is no recovery, so forgetting it means waiting.
 * With none set, a Firm promise cannot be ended early by anyone.
 */
object SessionPassword {
    const val MIN_LENGTH = 8
    private const val PREFS = "session_password"
    private const val ITERATIONS = 120_000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(c: Context): Boolean = prefs(c).getString("hash", null) != null

    /** Sets or replaces it. Replacing needs the current one, so a running promise cannot be bypassed by resetting. */
    fun set(c: Context, current: String?, next: String): Boolean {
        if (next.length < MIN_LENGTH) return false
        if (isSet(c) && (current == null || !verify(c, current))) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(c).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(next, salt), Base64.NO_WRAP))
            .putInt("iter", ITERATIONS)
            .apply()
        return true
    }

    fun verify(c: Context, attempt: String): Boolean {
        val salt = prefs(c).getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val stored = prefs(c).getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(stored, hash(attempt, salt, prefs(c).getInt("iter", ITERATIONS)))
    }

    fun hash(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded

    /** The stored hash for syncing: (salt, hash, iterations), Base64. The password itself never leaves. */
    fun exportHash(c: Context): Triple<String, String, Int>? {
        val salt = prefs(c).getString("salt", null) ?: return null
        val hash = prefs(c).getString("hash", null) ?: return null
        return Triple(salt, hash, prefs(c).getInt("iter", ITERATIONS))
    }

    /** Takes a hash made on another device (the laptop uses the same PBKDF2-SHA256). */
    fun importHash(c: Context, salt: String, hash: String, iterations: Int) {
        if (salt.isBlank() || hash.isBlank() || iterations < 10_000) return
        prefs(c).edit().putString("salt", salt).putString("hash", hash).putInt("iter", iterations).apply()
    }

    /** Whether the running session was made as a Firm promise, so ending it asks for the password. */
    fun firmSession(c: Context): Boolean = prefs(c).getBoolean("firm", false)
    fun setFirmSession(c: Context, firm: Boolean) = prefs(c).edit().putBoolean("firm", firm).apply()

    // ── Settings lock ────────────────────────────────────────────
    // With it on, anything that blocks less (unblocking, removing a rule, a longer budget)
    // needs the session password first; it then stays open for ten minutes.

    fun settingsLock(c: Context): Boolean = prefs(c).getBoolean("settings_lock", false) && isSet(c)
    fun setSettingsLock(c: Context, on: Boolean) = prefs(c).edit().putBoolean("settings_lock", on).apply()
    fun settingsOpen(c: Context): Boolean = System.currentTimeMillis() < prefs(c).getLong("open_until", 0L)
    fun openSettings(c: Context, minutes: Int = 10) =
        prefs(c).edit().putLong("open_until", System.currentTimeMillis() + minutes * 60_000L).apply()
    /** True when the settings lock holds loosening writes back right now. */
    fun settingsShut(c: Context): Boolean = settingsLock(c) && !settingsOpen(c)

    // ── Accountability partner ───────────────────────────────────
    fun partner(c: Context): String = prefs(c).getString("partner", "").orEmpty()
    fun setPartner(c: Context, email: String) = prefs(c).edit().putString("partner", email.trim()).apply()

    /** What ending early needs right now: nothing, the password, or impossible (Firm with no password). */
    enum class EndNeed { NOTHING, PASSWORD, IMPOSSIBLE }
    fun endNeed(firm: Boolean, passwordSet: Boolean): EndNeed = when {
        !firm -> EndNeed.NOTHING
        passwordSet -> EndNeed.PASSWORD
        else -> EndNeed.IMPOSSIBLE
    }
}
