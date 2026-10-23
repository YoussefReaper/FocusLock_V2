package com.focuslock.mdm

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Is there a newer FocusLock?", answered only when someone asks.
 *
 * The site promises no calls home, so this never runs by itself unless the
 * person has switched on the weekly check - which is off until they do. When
 * it runs it fetches one small public file, `version.json`, from the same
 * website the app was downloaded from, with an ordinary GET that carries
 * nothing about the phone or its owner beyond what any web request carries.
 * Nothing is downloaded or installed: an update is a page the person opens.
 *
 * The file:
 * ```
 * { "full": { "versionCode": 18, "versionName": "1.17", "notes": "...", "url": "https://.../update.html" },
 *   "demo": { ... same shape ... } }
 * ```
 */
object UpdateCheck {

    const val VERSION_URL = "https://focus-lock-c0xg.onrender.com/version.json"

    private const val KEY_AUTO = "update_auto_weekly"
    private const val KEY_LAST_CHECK_AT = "update_last_check_at"
    private const val KEY_LATEST_CODE = "update_latest_code"
    private const val KEY_LATEST_NAME = "update_latest_name"
    private const val KEY_LATEST_NOTES = "update_latest_notes"
    private const val KEY_LATEST_URL = "update_latest_url"

    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 8_000

    data class Release(val versionCode: Int, val versionName: String, val notes: String, val url: String)

    sealed class Result {
        data class Newer(val release: Release) : Result()
        object UpToDate : Result()
        object Failed : Result()
    }

    fun isAutoEnabled(context: Context): Boolean = FocusStore.getBool(context, KEY_AUTO, false)

    fun setAutoEnabled(context: Context, enabled: Boolean) = FocusStore.setBool(context, KEY_AUTO, enabled)

    /** The newest release seen by the last check, if it is newer than this build. */
    fun knownNewer(context: Context): Release? {
        val code = FocusStore.getInt(context, KEY_LATEST_CODE, 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        return Release(
            code,
            FocusStore.getString(context, KEY_LATEST_NAME, ""),
            FocusStore.getString(context, KEY_LATEST_NOTES, ""),
            FocusStore.getString(context, KEY_LATEST_URL, "")
        )
    }

    /** Runs the weekly check if the person turned it on and a week has passed. */
    fun maybeCheckInBackground(context: Context) {
        if (!isAutoEnabled(context)) return
        val last = FocusStore.getLong(context, KEY_LAST_CHECK_AT, 0L)
        if (System.currentTimeMillis() - last < WEEK_MS) return
        checkNow(context) { }
    }

    /** Checks now, on a background thread, and reports back on the main thread. */
    fun checkNow(context: Context, onResult: (Result) -> Unit) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                val release = fetch()
                FocusStore.setLong(appContext, KEY_LAST_CHECK_AT, System.currentTimeMillis())
                if (release == null) {
                    Result.Failed
                } else {
                    FocusStore.setInt(appContext, KEY_LATEST_CODE, release.versionCode)
                    FocusStore.setString(appContext, KEY_LATEST_NAME, release.versionName)
                    FocusStore.setString(appContext, KEY_LATEST_NOTES, release.notes)
                    FocusStore.setString(appContext, KEY_LATEST_URL, release.url)
                    if (isNewer(release.versionCode, BuildConfig.VERSION_CODE)) Result.Newer(release) else Result.UpToDate
                }
            } catch (_: Exception) {
                Result.Failed
            }
            main.post { onResult(result) }
        }.start()
    }

    /** Pure, and unit-tested: only a strictly higher version code is an update. */
    fun isNewer(remoteCode: Int, localCode: Int): Boolean = remoteCode > localCode

    /** Pure, and unit-tested: reads this build's tier out of version.json. */
    fun parse(json: String, tier: String): Release? {
        val root = JSONObject(json)
        val entry = root.optJSONObject(tier) ?: return null
        val code = entry.optInt("versionCode", 0)
        val name = entry.optString("versionName", "")
        val url = entry.optString("url", "")
        if (code <= 0 || name.isBlank() || !url.startsWith("https://")) return null
        return Release(code, name, entry.optString("notes", ""), url)
    }

    private fun fetch(): Release? {
        val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body, BuildConfig.TIER)
        } finally {
            connection.disconnect()
        }
    }
}
