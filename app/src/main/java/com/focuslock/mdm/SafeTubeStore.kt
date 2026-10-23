package com.focuslock.mdm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent storage for FocusLock Approved YouTube Channels.
 *
 * Stores the user's approved channels list and provides optional educational
 * suggestions that the user can add with one tap.
 */
data class SafeChannel(
    val id: String,
    val name: String,
    val handle: String = "",
    val url: String = ""
)

object SafeTubeStore {

    private const val KEY_CHANNELS = "safetube_channels_v2_json"

    val suggestedChannels = listOf(
        SafeChannel("khanacademy", "Khan Academy", "@khanacademy", "https://www.youtube.com/@khanacademy"),
        SafeChannel("mitocw", "MIT OpenCourseWare", "@mitocw", "https://www.youtube.com/@mitocw"),
        SafeChannel("3blue1brown", "3Blue1Brown", "@3blue1brown", "https://www.youtube.com/@3blue1brown"),
        SafeChannel("crashcourse", "CrashCourse", "@crashcourse", "https://www.youtube.com/@crashcourse"),
        SafeChannel("stanfordonline", "Stanford Online", "@stanfordonline", "https://www.youtube.com/@stanfordonline"),
        SafeChannel("freecodecamp", "freeCodeCamp", "@freecodecamp", "https://www.youtube.com/@freecodecamp")
    )

    fun getChannels(context: Context): List<SafeChannel> {
        val array = FocusStore.getJsonArray(context, KEY_CHANNELS)
        val out = ArrayList<SafeChannel>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) continue
            out.add(
                SafeChannel(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    name = name,
                    handle = obj.optString("handle", ""),
                    url = obj.optString("url", "")
                )
            )
        }
        return out
    }

    fun addChannel(context: Context, channel: SafeChannel): Boolean {
        val current = getChannels(context).toMutableList()
        if (current.any {
                it.name.equals(channel.name, ignoreCase = true) ||
                    (it.handle.isNotBlank() && it.handle.equals(channel.handle, ignoreCase = true))
            }
        ) {
            return true
        }
        current.add(channel)
        saveChannels(context, current)
        return true
    }

    fun removeChannel(context: Context, id: String): Boolean {
        val current = getChannels(context).filterNot { it.id == id }
        saveChannels(context, current)
        return true
    }

    private fun saveChannels(context: Context, channels: List<SafeChannel>) {
        val array = JSONArray()
        channels.forEach { ch ->
            val obj = JSONObject()
            obj.put("id", ch.id)
            obj.put("name", ch.name)
            obj.put("handle", ch.handle)
            obj.put("url", ch.url)
            array.put(obj)
        }
        FocusStore.setJsonArray(context, KEY_CHANNELS, array)
    }
}
