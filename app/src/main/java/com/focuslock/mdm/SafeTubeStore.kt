package com.focuslock.mdm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Data structures and persistent storage for FocusLock Safe YouTube.
 *
 * Stores the user-approved educational channels, offline downloads, and
 * on-device person silhouette modesty blur settings.
 */
data class SafeChannel(
    val id: String,
    val name: String,
    val handle: String = "",
    val url: String = ""
)

data class DownloadedVideo(
    val id: String,
    val title: String,
    val localPath: String,
    val durationSec: Long = 0,
    val fileSize: Long = 0,
    val downloadedAt: Long = System.currentTimeMillis()
)

object SafeTubeStore {

    private const val KEY_CHANNELS = "safetube_channels_json"
    private const val KEY_DOWNLOADS = "safetube_downloads_json"
    private const val KEY_SILHOUETTE_BLUR = "safetube_silhouette_blur"
    private const val KEY_BLUR_INTENSITY = "safetube_blur_intensity"

    private val defaultSeedChannels = listOf(
        SafeChannel("khanacademy", "Khan Academy", "@khanacademy", "https://www.youtube.com/@khanacademy"),
        SafeChannel("freecodecamp", "freeCodeCamp", "@freecodecamp", "https://www.youtube.com/@freecodecamp"),
        SafeChannel("mitocw", "MIT OpenCourseWare", "@mitocw", "https://www.youtube.com/@mitocw"),
        SafeChannel("3blue1brown", "3Blue1Brown", "@3blue1brown", "https://www.youtube.com/@3blue1brown"),
        SafeChannel("crashcourse", "CrashCourse", "@crashcourse", "https://www.youtube.com/@crashcourse"),
        SafeChannel("stanfordonline", "Stanford Online", "@stanfordonline", "https://www.youtube.com/@stanfordonline")
    )

    fun getChannels(context: Context): List<SafeChannel> {
        val array = FocusStore.getJsonArray(context, KEY_CHANNELS)
        if (array.length() == 0) {
            saveChannels(context, defaultSeedChannels)
            return defaultSeedChannels
        }
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
        if (current.any { it.name.equals(channel.name, ignoreCase = true) || (it.handle.isNotBlank() && it.handle.equals(channel.handle, ignoreCase = true)) }) {
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

    /**
     * Determines whether a given URL is allowed to load inside Safe YouTube.
     * All Shorts are unconditionally blocked.
     */
    fun isUrlAllowed(context: Context, url: String): Boolean {
        val lower = url.lowercase().trim()
        if (lower.contains("/shorts/") || lower.contains("youtube.com/shorts")) {
            return false
        }
        // Basic static assets, player assets, and google safe search
        if (lower.contains("googlevideo.com") || lower.contains("ytimg.com") || lower.contains("gstatic.com")) {
            return true
        }
        // Search queries and embed player
        if (lower.contains("youtube.com/results?") || lower.contains("youtube.com/embed/") || lower.contains("youtube.com/watch")) {
            return true
        }
        // Channel pages
        val channels = getChannels(context)
        return channels.any { ch ->
            (ch.handle.isNotBlank() && lower.contains(ch.handle.lowercase())) ||
                (ch.url.isNotBlank() && lower.contains(ch.url.lowercase()))
        }
    }

    // ── Silhouette Modesty Blur ───────────────────────────────────

    fun isSilhouetteBlurEnabled(context: Context): Boolean {
        return FocusStore.getBool(context, KEY_SILHOUETTE_BLUR, false)
    }

    fun setSilhouetteBlurEnabled(context: Context, enabled: Boolean) {
        FocusStore.setBool(context, KEY_SILHOUETTE_BLUR, enabled)
    }

    fun getBlurIntensity(context: Context): Int {
        return FocusStore.getInt(context, KEY_BLUR_INTENSITY, 8).coerceIn(1, 20)
    }

    fun setBlurIntensity(context: Context, intensity: Int) {
        FocusStore.setInt(context, KEY_BLUR_INTENSITY, intensity.coerceIn(1, 20))
    }

    // ── Offline Downloads ─────────────────────────────────────────

    fun getDownloads(context: Context): List<DownloadedVideo> {
        val array = FocusStore.getJsonArray(context, KEY_DOWNLOADS)
        val out = ArrayList<DownloadedVideo>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val path = obj.optString("localPath", "")
            if (path.isEmpty() || !File(path).exists()) continue
            out.add(
                DownloadedVideo(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title", "Lecture Video"),
                    localPath = path,
                    durationSec = obj.optLong("durationSec", 0L),
                    fileSize = obj.optLong("fileSize", 0L),
                    downloadedAt = obj.optLong("downloadedAt", System.currentTimeMillis())
                )
            )
        }
        return out
    }

    fun addDownload(context: Context, video: DownloadedVideo) {
        val current = getDownloads(context).filterNot { it.id == video.id }.toMutableList()
        current.add(0, video)
        saveDownloads(context, current)
    }

    fun removeDownload(context: Context, id: String) {
        val current = getDownloads(context).toMutableList()
        val item = current.firstOrNull { it.id == id }
        if (item != null) {
            try {
                File(item.localPath).delete()
            } catch (_: Exception) {}
            current.remove(item)
            saveDownloads(context, current)
        }
    }

    private fun saveDownloads(context: Context, downloads: List<DownloadedVideo>) {
        val array = JSONArray()
        downloads.forEach { dl ->
            val obj = JSONObject()
            obj.put("id", dl.id)
            obj.put("title", dl.title)
            obj.put("localPath", dl.localPath)
            obj.put("durationSec", dl.durationSec)
            obj.put("fileSize", dl.fileSize)
            obj.put("downloadedAt", dl.downloadedAt)
            array.put(obj)
        }
        FocusStore.setJsonArray(context, KEY_DOWNLOADS, array)
    }
}
