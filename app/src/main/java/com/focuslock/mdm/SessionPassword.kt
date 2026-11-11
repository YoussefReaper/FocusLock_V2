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
            .apply()
        return true
    }

    fun verify(c: Context, attempt: String): Boolean {
        val salt = prefs(c).getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val stored = prefs(c).getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(stored, hash(attempt, salt))
    }

    fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded

    /** Whether the running session was made as a Firm promise, so ending it asks for the password. */
    fun firmSession(c: Context): Boolean = prefs(c).getBoolean("firm", false)
    fun setFirmSession(c: Context, firm: Boolean) = prefs(c).edit().putBoolean("firm", firm).apply()

    /** What ending early needs right now: nothing, the password, or impossible (Firm with no password). */
    enum class EndNeed { NOTHING, PASSWORD, IMPOSSIBLE }
    fun endNeed(firm: Boolean, passwordSet: Boolean): EndNeed = when {
        !firm -> EndNeed.NOTHING
        passwordSet -> EndNeed.PASSWORD
        else -> EndNeed.IMPOSSIBLE
    }
}
