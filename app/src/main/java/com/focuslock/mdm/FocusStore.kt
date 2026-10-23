package com.focuslock.mdm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single persistence surface for every piece of user-owned configuration.
 *
 * FocusLock deliberately stays on SharedPreferences + JSON rather than Room:
 * the whole config is a few kilobytes, it must be readable synchronously from
 * an AccessibilityService and a foreground Service on their own tick loops,
 * and it has to survive a device that is never allowed to run a migration
 * screen. JSON blobs give us the same "user owns the data" property Room would,
 * plus a trivially exportable profile file (see [ProfileIo]).
 */
object FocusStore {

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)

    // ── Primitives ────────────────────────────────────────────────

    fun getBool(context: Context, key: String, fallback: Boolean): Boolean =
        prefs(context).getBoolean(key, fallback)

    fun setBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun getInt(context: Context, key: String, fallback: Int): Int =
        prefs(context).getInt(key, fallback)

    fun setInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }

    fun getLong(context: Context, key: String, fallback: Long): Long =
        prefs(context).getLong(key, fallback)

    fun setLong(context: Context, key: String, value: Long) {
        prefs(context).edit().putLong(key, value).apply()
    }

    fun getString(context: Context, key: String, fallback: String): String =
        prefs(context).getString(key, fallback) ?: fallback

    fun setString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun has(context: Context, key: String): Boolean = prefs(context).contains(key)

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    // ── Sets ──────────────────────────────────────────────────────

    fun getSet(context: Context, key: String): Set<String> =
        prefs(context).getStringSet(key, null)?.toSet() ?: emptySet()

    fun getSetOrNull(context: Context, key: String): Set<String>? =
        prefs(context).getStringSet(key, null)?.toSet()

    fun setSet(context: Context, key: String, value: Collection<String>) {
        val cleaned = value.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        prefs(context).edit().putStringSet(key, cleaned).apply()
    }

    // ── JSON ──────────────────────────────────────────────────────

    fun getJsonObject(context: Context, key: String): JSONObject {
        val raw = prefs(context).getString(key, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun setJsonObject(context: Context, key: String, value: JSONObject) {
        prefs(context).edit().putString(key, value.toString()).apply()
    }

    fun getJsonArray(context: Context, key: String): JSONArray {
        val raw = prefs(context).getString(key, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun setJsonArray(context: Context, key: String, value: JSONArray) {
        prefs(context).edit().putString(key, value.toString()).apply()
    }

    /** Reads a `{ "key": <string> }` blob as a plain map. */
    fun getStringMap(context: Context, key: String): Map<String, String> {
        val obj = getJsonObject(context, key)
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k, "")
            if (k.isNotBlank()) out[k] = v
        }
        return out
    }

    fun setStringMap(context: Context, key: String, value: Map<String, String>) {
        val obj = JSONObject()
        value.forEach { (k, v) -> obj.put(k, v) }
        setJsonObject(context, key, obj)
    }

    fun getIntMap(context: Context, key: String): Map<String, Int> {
        val obj = getJsonObject(context, key)
        val out = LinkedHashMap<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optInt(k, 0)
        }
        return out
    }

    fun setIntMap(context: Context, key: String, value: Map<String, Int>) {
        val obj = JSONObject()
        value.forEach { (k, v) -> obj.put(k, v) }
        setJsonObject(context, key, obj)
    }

    fun jsonArrayToStringList(array: JSONArray): List<String> {
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }

    fun stringListToJsonArray(values: Collection<String>): JSONArray {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array
    }
}
