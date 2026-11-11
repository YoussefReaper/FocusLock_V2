package com.focuslock.mdm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The rules every device on the account shares (laptops, phones, tablets), synced through the
 * server once a minute with the promise. Each device keeps the version it last applied and a
 * fingerprint of its own rules then:
 *  - a newer version and no edits here: take it;
 *  - edits here: send them; the server's answer (tightened while a promise runs) is taken back;
 *  - a device joining an account that already has rules adopts them.
 * Any number of devices works the same way, because they only ever meet at the server.
 *
 * On the phone: websites and pages are browser keyword rules, the Keeper list, Bedtime's hours,
 * weekly blocking windows, the session password's hash, the partner and the settings lock.
 * Fields the phone can't use (site budgets, the laptop's always-allowed sites) are carried
 * through unchanged, so a phone never erases a laptop's rules.
 */
object SettingsSync {
    private const val KEY_VERSION = "shared_version"
    private const val KEY_FP = "shared_fp"
    private const val KEY_CARRY = "shared_carry"   // budgets + allowed, kept as they came

    val BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.opera.browser", "com.brave.browser", "com.mi.globalbrowser", "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
    )

    /** A browser rule that the account shares: switched on, browser-only or everywhere, not a built-in group. */
    fun isShared(r: KeywordRule): Boolean =
        r.enabled && (r.group == null || r.group == "sync") && (r.packages.isEmpty() || BROWSERS.containsAll(r.packages))

    fun looksLikeSite(p: String): Boolean = p.contains('.') && !p.contains('/') && !p.contains(' ')

    fun site(p: String): String = p.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    // ── export / apply ────────────────────────────────────────────

    fun export(c: Context): JSONObject {
        val shared = KeywordRules.all(c).filter(::isShared).map { it.phrase.trim() }
        val o = JSONObject()
        o.put("sites", JSONArray(shared.filter(::looksLikeSite).map(::site).distinct().sorted()))
        o.put("pages", JSONArray(shared.filterNot(::looksLikeSite).distinct().sorted()))
        val carry = runCatching { JSONObject(FocusStore.getString(c, KEY_CARRY, "{}")) }.getOrDefault(JSONObject())
        o.put("allowed", carry.optJSONArray("allowed") ?: JSONArray())
        o.put("budgets", carry.optJSONArray("budgets") ?: JSONArray())
        o.put("keeperOff", JSONArray(KeeperFocus.ITEMS.map { it.id }.filterNot { KeeperFocus.isOn(c, it) }.sorted()))
        o.put("keeperCustom", JSONArray(KeeperFocus.custom(c).sorted()))
        o.put("bedtime", JSONObject().put("on", Bedtime.isEnabled(c)).put("start", Bedtime.startMinutes(c)).put("end", Bedtime.endMinutes(c)))
        val windows = ScheduleManager.getSchedules(c).filter { it.repeat != RepeatType.MONTHLY }.map { w ->
            val days = if (w.repeat == RepeatType.DAILY || w.daysOfWeek.isEmpty()) (0..6).toList() else w.daysOfWeek.map { it - 1 }.sorted()
            JSONObject().put("days", JSONArray(days)).put("start", w.startMinutes).put("end", w.endMinutes)
        }.sortedBy { it.toString() }
        o.put("windows", JSONArray(windows))
        SessionPassword.exportHash(c)?.let { (salt, hash, iter) ->
            o.put("password", JSONObject().put("salt", salt).put("hash", hash).put("iter", iter))
        }
        o.put("partner", SessionPassword.partner(c).lowercase())
        o.put("settingsLock", SessionPassword.settingsLock(c))
        return o
    }

    fun apply(c: Context, r: JSONObject) {
        SessionLock.applyingSharedRules = true
        try {
            val sites = r.optJSONArray("sites").strings()
            val pages = r.optJSONArray("pages").strings()
            val kept = KeywordRules.all(c).filterNot(::isShared)
            val synced = (sites + pages).distinct().map { phrase ->
                KeywordRule("sync-" + phrase.hashCode().toUInt().toString(16), phrase, BROWSERS, GuardAction.STEP_BACK, true, null)
            }
            KeywordRules.save(c, kept + synced)

            FocusStore.setString(c, KEY_CARRY, JSONObject()
                .put("allowed", r.optJSONArray("allowed") ?: JSONArray())
                .put("budgets", r.optJSONArray("budgets") ?: JSONArray()).toString())

            val off = r.optJSONArray("keeperOff").strings().toSet()
            KeeperFocus.ITEMS.forEach { if (KeeperFocus.isOn(c, it.id) == (it.id in off)) KeeperFocus.setOn(c, it.id, it.id !in off) }
            val custom = r.optJSONArray("keeperCustom").strings()
            KeeperFocus.custom(c).filterNot { it in custom }.forEach { KeeperFocus.removeCustom(c, it) }
            custom.filterNot { it in KeeperFocus.custom(c) }.forEach { KeeperFocus.addCustom(c, it) }

            r.optJSONObject("bedtime")?.let { b ->
                Bedtime.setWindow(c, b.optInt("start", Bedtime.startMinutes(c)), b.optInt("end", Bedtime.endMinutes(c)))
                if (Bedtime.isEnabled(c) != b.optBoolean("on")) CapabilityRegistry.setEnabled(c, Capabilities.BEDTIME_MODE, b.optBoolean("on"))
            }

            r.optJSONArray("windows")?.let { arr ->
                val current = ScheduleManager.getSchedules(c)
                val monthly = current.filter { it.repeat == RepeatType.MONTHLY }
                val next = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { w ->
                    val days = w.optJSONArray("days").ints().map { it + 1 }.sorted()
                    current.firstOrNull { o -> o.repeat != RepeatType.MONTHLY && o.startMinutes == w.optInt("start") && o.endMinutes == w.optInt("end") &&
                        (if (o.repeat == RepeatType.DAILY || o.daysOfWeek.isEmpty()) (1..7).toList() else o.daysOfWeek.sorted()) == days }
                        ?: ScheduleWindow("sync-" + w.toString().hashCode().toUInt().toString(16), w.optInt("start"), w.optInt("end"),
                            if (days.size == 7) RepeatType.DAILY else RepeatType.WEEKLY, if (days.size == 7) emptyList() else days, 1, "")
                }
                ScheduleManager.saveSchedules(c, monthly + next)
            }

            r.optJSONObject("password")?.let { p -> SessionPassword.importHash(c, p.optString("salt"), p.optString("hash"), p.optInt("iter")) }
            SessionPassword.setPartner(c, r.optString("partner"))
            SessionPassword.setSettingsLock(c, r.optBoolean("settingsLock"))
            PolicySync.request(c, "sharedRules")
        } finally {
            SessionLock.applyingSharedRules = false
        }
    }

    fun fingerprint(o: JSONObject): String =
        MessageDigest.getInstance("SHA-256").digest(o.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** One round. Blocking: call it off the main thread, signed in. */
    fun syncBlocking(c: Context, token: String) {
        val local = export(c)
        val fp = fingerprint(local)
        val version = FocusStore.getInt(c, KEY_VERSION, 0)
        val r = Account.call("GET", "/settings", null, token)
        if (!r.ok) return
        val remoteVersion = r.json.optInt("version", 0)
        val remote = r.json.optJSONObject("settings")
        val editedHere = version > 0 && fp != FocusStore.getString(c, KEY_FP, "")
        if (remote != null && remoteVersion > version && !editedHere) { take(c, remote, remoteVersion); return }
        if (remote != null && !editedHere) return
        val put = Account.call("PUT", "/settings", JSONObject().put("device", android.os.Build.MODEL).put("settings", local), token)
        if (put.ok) put.json.optJSONObject("settings")?.let { take(c, it, put.json.optInt("version")) }
    }

    private fun take(c: Context, remote: JSONObject, version: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            apply(c, remote)
            FocusStore.setInt(c, KEY_VERSION, version)
            FocusStore.setString(c, KEY_FP, fingerprint(export(c)))
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it).trim() }.filter { it.isNotEmpty() }

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }
}
