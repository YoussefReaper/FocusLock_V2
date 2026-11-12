package com.focuslock.mdm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One promise across the person's devices, through their Pinehold account.
 *
 * A promise made here is posted to the server; a promise made on the laptop (or another phone)
 * is pulled and held here too. Nothing happens when signed out. The server never lets a promise
 * loosen: it keeps the later end and the stronger strength, and refuses to end a Sealed one.
 */
object PromiseSync {
    private const val KEY_ID = "promise_sync_id"
    private const val KEY_ENDED = "promise_sync_ended_id"
    private const val KEY_LAST_PULL = "promise_sync_last_pull"
    private const val PULL_EVERY_MS = 60_000L

    fun strengthOf(mode: FocusMode, noExit: Boolean): String = when {
        noExit -> "sealed"
        mode == FocusMode.SOFT -> "soft"
        else -> "firm"
    }

    /** The local mode and early exit for a strength pulled from another device. */
    fun modeFor(strength: String): Pair<FocusMode, Boolean> = when (strength) {
        "sealed" -> FocusMode.SANCTUARY to true
        "soft" -> FocusMode.SOFT to false
        else -> FocusMode.BLOCK to false
    }

    /** Pure, for tests: whether a pulled promise should start here. */
    fun shouldStart(remoteId: String, remoteEndAt: Long, now: Long, localActive: Boolean, endedHereId: String?): Boolean =
        !localActive && remoteEndAt - now > 60_000L && remoteId != endedHereId

    fun pushAsync(c: Context, mode: FocusMode, durationMs: Long, noExit: Boolean) {
        if (!Account.signedIn(c)) return
        val app = c.applicationContext
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        FocusStore.setString(app, KEY_ID, id)
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("id", id).put("strength", strengthOf(mode, noExit))
            .put("startAt", now).put("endAt", now + durationMs)
            .put("words", Promise.words(app))
            .put("protects", JSONArray(Promise.protects(app).toList()))
            .put("origin", "android")
        Thread { Account.sessionToken(app)?.let { Account.call("POST", "/promise", body, it) } }.start()
    }

    fun endAsync(c: Context) {
        if (!Account.signedIn(c)) return
        val app = c.applicationContext
        FocusStore.setString(app, KEY_ENDED, FocusStore.getString(app, KEY_ID, ""))
        Thread { Account.sessionToken(app)?.let { Account.call("POST", "/promise/end", JSONObject(), it) } }.start()
    }

    /** Pulls at most once a minute. A promise from another device starts here if none is running. */
    fun pullAsync(c: Context, onStarted: (() -> Unit)? = null) {
        if (!Account.signedIn(c)) return
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        if (now - FocusStore.getLong(app, KEY_LAST_PULL, 0L) < PULL_EVERY_MS) return
        FocusStore.setLong(app, KEY_LAST_PULL, now)
        Thread {
            val token = Account.sessionToken(app) ?: return@Thread
            try { SettingsSync.syncBlocking(app, token) } catch (_: Exception) { /* offline: try next minute */ }
            // Presence: only a flag and a time, so an alarm on another device can tell you're up.
            val interactive = app.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
            Account.call("POST", "/presence", JSONObject().put("device", "phone").put("active", interactive), token)
            val r = Account.call("GET", "/promise", null, token)
            val p = r.json.optJSONObject("promise") ?: return@Thread
            val id = p.optString("id")
            val endAt = p.optLong("endAt")
            val serverNow = r.json.optLong("now", System.currentTimeMillis())
            if (!shouldStart(id, endAt, serverNow, SessionManager.isActive(app), FocusStore.getString(app, KEY_ENDED, "").ifBlank { null })) return@Thread
            if (id == FocusStore.getString(app, KEY_ID, "")) return@Thread
            val (mode, noExit) = modeFor(p.optString("strength"))
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (SessionManager.isActive(app)) return@post
                p.optString("words").takeIf { it.isNotBlank() }?.let { Promise.setWords(app, it) }
                FocusStore.setString(app, KEY_ID, id)
                if (SessionManager.start(app, mode, endAt - serverNow, noExit, fromSync = true)) {
                    SessionPassword.setFirmSession(app, p.optString("strength") == "firm")
                    onStarted?.invoke()
                }
            }
        }.start()
    }

    /** Approves a laptop's link code. Runs on the caller's thread: call it off the main thread. */
    fun approveLink(c: Context, code: String): Boolean {
        val token = Account.sessionToken(c) ?: return false
        return Account.call("POST", "/link/approve", JSONObject().put("code", code.trim()), token).ok
    }
}
