package com.focuslock.mdm

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * A whole setup, saved and restorable.
 *
 * Exam week, term time, holidays and travel want genuinely different phones,
 * and rebuilding forty switches by hand each time is how a good setup decays
 * into no setup. A profile is the complete user-owned configuration — nothing
 * about the device, nothing about identity — so it is also the honest way to
 * move a setup to a new phone.
 */
object ProfileIo {

    private const val PREFS_PROFILES = "focuslock_profiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val FORMAT_VERSION = 1
    private const val MAX_PROFILE_BYTES = 2 * 1024 * 1024

    data class Profile(val name: String, val savedAtMs: Long, val payload: JSONObject)

    private fun readSavedArray(context: Context): JSONArray {
        val dedicated = context.getSharedPreferences(PREFS_PROFILES, Context.MODE_PRIVATE)
        val raw = dedicated.getString(KEY_PROFILES, null)
        if (raw != null) {
            return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        }
        // Seamless one-time migration from PREFS_MAIN so existing saved setups stay intact
        // without bloating PREFS_MAIN writes on every rule change.
        val legacy = FocusStore.getJsonArray(context, KEY_PROFILES)
        if (legacy.length() > 0) {
            dedicated.edit().putString(KEY_PROFILES, legacy.toString()).apply()
            FocusStore.remove(context, KEY_PROFILES)
        }
        return legacy
    }

    private fun writeSavedArray(context: Context, array: JSONArray) {
        context.getSharedPreferences(PREFS_PROFILES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, array.toString())
            .apply()
    }

    // ── Snapshot ──────────────────────────────────────────────────

    fun snapshot(context: Context): JSONObject {
        val out = JSONObject()
        out.put("format", FORMAT_VERSION)
        out.put("createdAt", System.currentTimeMillis())
        out.put("capabilities", CapabilityRegistry.exportJson(context))
        out.put("apps", AppRules.exportJson(context))
        out.put("keywords", KeywordRules.exportJson(context))
        out.put("telegramAllowed", FocusStore.stringListToJsonArray(TelegramGuard.allowedRaw(context)))
        out.put("rules", RuleStore.exportJson(context))
        out.put("places", PlaceRules.exportJson(context))
        out.put("limits", AppLimits.exportJson(context))
        out.put("schedules", ScheduleManager.exportJson(context))
        out.put("web", FocusStore.stringListToJsonArray(AllowlistStore.getWebAllowlistUrls(context)))
        out.put("bedtime", bedtimeJson(context))
        out.put("tasks", FocusTaskStore.exportJson(context))
        out.put("appearance", appearanceJson(context))
        return out
    }

    private fun bedtimeJson(context: Context): JSONObject {
        val out = JSONObject()
        out.put("start", Bedtime.startMinutes(context))
        out.put("end", Bedtime.endMinutes(context))
        out.put("dim", Bedtime.dimPercent(context))
        out.put("darkTheme", Bedtime.forcesDarkTheme(context))
        out.put("blockEverything", Bedtime.blocksEverything(context))
        out.put("overlay", Bedtime.isOverlay(context))
        out.put("bricksApp", Bedtime.bricksApp(context))
        out.put(
            "categories",
            FocusStore.stringListToJsonArray(Bedtime.blockedCategories(context).map { it.id })
        )
        return out
    }

    private fun appearanceJson(context: Context): JSONObject {
        val out = JSONObject()
        out.put("theme", UiPrefs.getTheme(context).id)
        out.put("accent", UiPrefs.getAccent(context).id)
        out.put("background", UiPrefs.getBackground(context).id)
        out.put("wallpaper", UiPrefs.getWallpaper(context).id)
        out.put("font", UiPrefs.getFont(context).id)
        out.put("density", UiPrefs.getDensity(context).id)
        out.put("radius", UiPrefs.getCardRadiusDp(context))
        out.put("textScale", UiPrefs.getTextScale(context).toDouble())
        out.put("reducedMotion", UiPrefs.reducedMotion(context))
        out.put("highContrast", UiPrefs.highContrast(context))
        return out
    }

    // ── Restore ───────────────────────────────────────────────────

    /**
     * Restores only the sections present in the payload, so a profile exported
     * from an older build never wipes settings it did not know about.
     *
     * Frozen-gated as one all-or-nothing operation, checked here rather than
     * relying on each section's own guard. Individually, capabilities and app
     * policies already refuse when frozen but schedules, places, keyword
     * rules, custom rules and limits did not (now fixed at their own source
     * too) - importing a whole profile mid-session on the old code would have
     * silently split into "some sections applied, some didn't," which is a
     * worse failure than just refusing the whole restore outright.
     */
    fun restore(context: Context, payload: JSONObject, includeAppearance: Boolean = true): Boolean {
        if (SessionLock.isFrozen(context)) return false
        payload.optJSONObject("capabilities")?.let { CapabilityRegistry.importJson(context, it) }
        payload.optJSONObject("apps")?.let { AppRules.importJson(context, it) }
        payload.optJSONObject("keywords")?.let { KeywordRules.importJson(context, it) }
        payload.optJSONArray("telegramAllowed")?.let {
            TelegramGuard.setAllowed(context, FocusStore.jsonArrayToStringList(it))
        }
        payload.optJSONArray("rules")?.let { RuleStore.importJson(context, it) }
        payload.optJSONArray("places")?.let { PlaceRules.importJson(context, it) }
        payload.optJSONObject("limits")?.let { AppLimits.importJson(context, it) }
        payload.optJSONArray("schedules")?.let { ScheduleManager.importJson(context, it) }
        payload.optJSONArray("web")?.let {
            AllowlistStore.setWebAllowlistUrls(context, FocusStore.jsonArrayToStringList(it))
        }
        payload.optJSONObject("bedtime")?.let { restoreBedtime(context, it) }
        // Tasks travel with a profile; the earned balance deliberately does not.
        // Minutes are a record of work done on this phone, not a setting.
        payload.optJSONArray("tasks")?.let { FocusTaskStore.importJson(context, it) }
        if (includeAppearance) {
            payload.optJSONObject("appearance")?.let { restoreAppearance(context, it) }
        }
        PolicySync.request(context, "profile:restore")
        return true
    }

    private fun restoreBedtime(context: Context, json: JSONObject) {
        Bedtime.setWindow(context, json.optInt("start", Bedtime.DEFAULT_START), json.optInt("end", Bedtime.DEFAULT_END))
        Bedtime.setDimPercent(context, json.optInt("dim", 45))
        Bedtime.setForcesDarkTheme(context, json.optBoolean("darkTheme", true))
        if (json.has("blockEverything")) Bedtime.setBlocksEverything(context, json.optBoolean("blockEverything", false))
        if (json.has("overlay")) Bedtime.setOverlay(context, json.optBoolean("overlay", false))
        if (json.has("bricksApp")) Bedtime.setBricksApp(context, json.optBoolean("bricksApp", false))
        json.optJSONArray("categories")?.let { array ->
            Bedtime.setBlockedCategories(
                context,
                FocusStore.jsonArrayToStringList(array).map { AppCategory.fromId(it) }
            )
        }
    }

    private fun restoreAppearance(context: Context, json: JSONObject) {
        json.optString("theme", "").takeIf { it.isNotBlank() }?.let { UiPrefs.setThemeId(context, it) }
        json.optString("accent", "").takeIf { it.isNotBlank() }?.let { UiPrefs.setAccentId(context, it) }
        json.optString("background", "").takeIf { it.isNotBlank() }?.let { UiPrefs.setBackgroundId(context, it) }
        json.optString("wallpaper", "").takeIf { it.isNotBlank() }?.let { UiPrefs.setWallpaperId(context, it) }
        json.optString("font", "").takeIf { it.isNotBlank() }?.let { UiPrefs.setFontId(context, it) }
        json.optString("density", "").takeIf { it.isNotBlank() }?.let { UiPrefs.setDensityId(context, it) }
        if (json.has("radius")) UiPrefs.setCardRadiusDp(context, json.optInt("radius", 18))
        if (json.has("textScale")) UiPrefs.setTextScale(context, json.optDouble("textScale", 1.0).toFloat())
        if (json.has("reducedMotion")) UiPrefs.setReducedMotion(context, json.optBoolean("reducedMotion", false))
        if (json.has("highContrast")) UiPrefs.setHighContrast(context, json.optBoolean("highContrast", false))
    }

    // ── Saved profiles ────────────────────────────────────────────

    fun saved(context: Context): List<Profile> {
        val array = readSavedArray(context)
        val out = ArrayList<Profile>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out.add(
                Profile(
                    name = obj.optString("name", "Profile"),
                    savedAtMs = obj.optLong("savedAt", 0L),
                    payload = obj.optJSONObject("payload") ?: JSONObject()
                )
            )
        }
        return out
    }

    fun save(context: Context, name: String) {
        val array = readSavedArray(context)
        val cleaned = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("name", "") == name) continue
            cleaned.put(obj)
        }
        val entry = JSONObject()
        entry.put("name", name)
        entry.put("savedAt", System.currentTimeMillis())
        entry.put("payload", snapshot(context))
        cleaned.put(entry)
        writeSavedArray(context, cleaned)
    }

    fun delete(context: Context, name: String) {
        val array = readSavedArray(context)
        val cleaned = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("name", "") == name) continue
            cleaned.put(obj)
        }
        writeSavedArray(context, cleaned)
    }

    // ── Files ─────────────────────────────────────────────────────

    fun writeTo(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(snapshot(context).toString(2).toByteArray(Charsets.UTF_8))
        }
        true
    } catch (_: Exception) {
        false
    }

    fun readFrom(context: Context, uri: Uri): JSONObject? = try {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_PROFILE_BYTES) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray().toString(Charsets.UTF_8)
        }
        if (text.isNullOrBlank()) null else JSONObject(text)
    } catch (_: Exception) {
        null
    }

    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return "focuslock-setup-" + stamp + ".json"
    }

    /** A plain-language summary, so importing is never a blind action. */
    fun describe(context: Context, payload: JSONObject): String {
        val capabilities = payload.optJSONObject("capabilities")?.optJSONObject("enabled")
        val enabledCount = capabilities?.length() ?: 0
        val apps = payload.optJSONObject("apps")?.optJSONObject("apps")?.length() ?: 0
        val keywords = payload.optJSONObject("keywords")?.optJSONArray("rules")?.length() ?: 0
        val rules = payload.optJSONArray("rules")?.length() ?: 0
        val schedules = payload.optJSONArray("schedules")?.length() ?: 0
        val tasks = payload.optJSONArray("tasks")?.length() ?: 0

        return listOf(
            context.getString(R.string.profile_summary_capabilities, enabledCount),
            context.getString(R.string.profile_summary_apps, apps),
            context.getString(R.string.profile_summary_keywords, keywords),
            context.getString(R.string.profile_summary_rules, rules),
            context.getString(R.string.profile_summary_schedules, schedules),
            context.getString(R.string.profile_summary_tasks, tasks)
        ).joinToString(context.getString(R.string.profile_summary_separator))
    }
}
