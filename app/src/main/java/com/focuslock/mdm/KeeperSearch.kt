package com.focuslock.mdm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The Keeper's search: the web (DuckDuckGo, strict SafeSearch) and YouTube
 * (videos only, never Shorts) in one list. Nothing here opens anything: each
 * result is shown with what the Keeper already knows about it, and tapping it
 * goes through the Keeper like any other link.
 */
object KeeperSearch {

    enum class Kind { VIDEO, PAGE }

    /** LIT opens at once, DARK is checked when tapped, CLOSED stays shut. */
    enum class State { LIT, DARK, CLOSED }

    data class Result(
        val kind: Kind,
        val title: String,
        val url: String,
        val source: String,
        val videoId: String? = null,
        val duration: String? = null,
        val thumb: String? = null
    )

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    /** Runs on a background thread. Either half may fail on its own; the other still shows. */
    fun search(query: String): List<Result> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val videos = runCatching { youtube(q) }.getOrDefault(emptyList())
        val pages = runCatching { web(q) }.getOrDefault(emptyList())
        // Interleave so neither kind buries the other.
        val out = ArrayList<Result>()
        val n = maxOf(videos.size, pages.size)
        for (i in 0 until n) {
            pages.getOrNull(i)?.let { out.add(it) }
            videos.getOrNull(i)?.let { out.add(it) }
        }
        return out
    }

    fun state(context: Context, r: Result): State {
        if (r.kind == Kind.VIDEO) {
            val v = r.videoId?.let { KeeperCache.lookupVideo(context, it) } ?: return State.DARK
            return if (v.allow) State.LIT else State.CLOSED
        }
        if (onOwnList(context, r.url)) return State.LIT
        val v = KeeperCache.lookupPage(context, r.url) ?: return State.DARK
        return if (v.allow) State.LIT else State.CLOSED
    }

    fun onOwnList(context: Context, url: String): Boolean {
        val host = KeeperCache.siteKey(url) ?: return false
        return AllowlistStore.getWebAllowlistUrls(context).any { entry ->
            val h = KeeperCache.siteKey(if (entry.contains("://")) entry else "https://$entry") ?: entry
            host == h || host.endsWith(".$h")
        }
    }

    // ── Web ───────────────────────────────────────────────────────

    private fun web(q: String): List<Result> {
        val html = request(
            "https://html.duckduckgo.com/html/",
            "q=" + URLEncoder.encode(q, "UTF-8") + "&kp=1",
            "application/x-www-form-urlencoded"
        )
        val link = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        return link.findAll(html).mapNotNull { m ->
            val href = unwrap(m.groupValues[1]) ?: return@mapNotNull null
            if (href.contains("duckduckgo.com/y.js")) return@mapNotNull null // ads
            val host = Uri.parse(href).host?.removePrefix("www.") ?: return@mapNotNull null
            if (host.endsWith("youtube.com") || host == "youtu.be") return@mapNotNull null
            Result(Kind.PAGE, clean(m.groupValues[2]), href, host)
        }.distinctBy { it.url }.take(10).toList()
    }

    private fun unwrap(raw: String): String? {
        val href = raw.replace("&amp;", "&").let { if (it.startsWith("//")) "https:$it" else it }
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
        val target = if (uddg != null) URLDecoder.decode(uddg, "UTF-8") else href
        return target.takeIf { it.startsWith("http") }
    }

    // ── YouTube ───────────────────────────────────────────────────

    private fun youtube(q: String): List<Result> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", "2.20241101.00.00").put("hl", "en")))
            .put("query", q)
            .put("params", "EgIQAQ==") // videos only
        val json = JSONObject(request(
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
            body.toString(),
            "application/json"
        ))
        val out = ArrayList<Result>()
        collect(json, out)
        return out.take(10)
    }

    private fun collect(node: Any?, out: MutableList<Result>) {
        if (out.size >= 12) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { v -> video(v)?.let { out.add(it) }; return }
                for (k in node.keys()) collect(node.opt(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun video(v: JSONObject): Result? {
        val id = v.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val length = v.optJSONObject("lengthText")?.optString("simpleText") ?: return null // live: no length
        if (seconds(length) in 0..60) return null // a Short in disguise
        val title = runs(v.optJSONObject("title")) ?: return null
        val channel = runs(v.optJSONObject("ownerText")) ?: "YouTube"
        return Result(Kind.VIDEO, title, "https://www.youtube.com/watch?v=$id", channel, id, length,
            "https://i.ytimg.com/vi/$id/mqdefault.jpg")
    }

    private fun runs(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val a = o.optJSONArray("runs") ?: return null
        return (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() }.takeIf { it.isNotBlank() }
    }

    private fun seconds(length: String): Int =
        length.split(':').fold(0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: return -1) }

    // ── Plumbing ──────────────────────────────────────────────────

    private fun request(url: String, body: String, type: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Content-Type", type)
            c.setRequestProperty("Accept-Language", "en")
            c.outputStream.use { it.write(body.toByteArray()) }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun clean(html: String): String =
        html.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").trim()

    private val thumbs = LruCache<String, Bitmap>(40)

    /** Background thread only. */
    fun thumb(url: String): Bitmap? {
        thumbs.get(url)?.let { return it }
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.inputStream.use { BitmapFactory.decodeStream(it) }.also { c.disconnect() }
        }.getOrNull()?.also { thumbs.put(url, it) }
    }
}
