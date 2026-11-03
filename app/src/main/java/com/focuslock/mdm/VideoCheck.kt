package com.focuslock.mdm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Checks one YouTube video before it can be watched, in the cheapest order:
 *
 *  1. Free, no AI: a verdict already remembered for this video; age-restricted
 *     videos (blocked outright); a title/description/transcript that is plainly a
 *     trigger (blocked); a channel on the trusted list (allowed).
 *  2. One request: about 24 of YouTube's own preview frames (the scrubbing
 *     storyboard, so nothing is downloaded) tiled into a single image, plus the
 *     title, description and timed transcript. The answer is allow, cut (with the
 *     time ranges to remove) or block.
 *
 * Any failure leaves the video blocked.
 */
object VideoCheck {

    const val GRID_FRAMES = 24
    private const val GRID_COLS = 6
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    data class Meta(
        val id: String, val title: String, val channel: String, val channelId: String,
        val description: String, val durationS: Int, val ageRestricted: Boolean, val playable: Boolean,
        val captionUrl: String?, val storyboardSpec: String?
    )

    data class Outcome(val verdict: KeeperVerdict?, val meta: Meta?, val failure: KeeperResult.Why? = null, val spentRequest: Boolean = false)

    enum class Step { DETAILS, FRAMES, DECIDING }

    // ── the whole check ──────────────────────────────────────────

    /** Blocking; run off the main thread. */
    fun run(context: Context, videoId: String, onStep: (Step) -> Unit = {}): Outcome {
        KeeperCache.lookupVideo(context, videoId)?.let { return Outcome(it, null) }

        onStep(Step.DETAILS)
        val meta = try { fetchMeta(videoId) } catch (_: Exception) { null }
            ?: return Outcome(null, null, KeeperResult.Why.NETWORK)

        quickVerdict(context, meta)?.let { v ->
            KeeperCache.rememberVideo(context, videoId, v)
            return Outcome(v, meta)
        }

        val transcript = meta.captionUrl?.let { try { fetchTranscript(it) } catch (_: Exception) { "" } }.orEmpty()
        localTriggerVerdict(meta.title, meta.description, transcript)?.let { v ->
            KeeperCache.rememberVideo(context, videoId, v)
            return Outcome(v, meta)
        }

        onStep(Step.FRAMES)
        val grid = meta.storyboardSpec?.let { try { buildGrid(it, meta.durationS) } catch (_: Exception) { null } }
        if (grid == null) {
            // Without frames there is nothing to look at, and words alone cannot clear a video.
            return Outcome(null, meta, KeeperResult.Why.UNREADABLE)
        }

        onStep(Step.DECIDING)
        val user = KeeperPrompts.videoUser(meta.title, meta.channel, meta.description, meta.durationS, grid.second, transcript)
        return when (val r = KeeperClient.ask(context, Keeper.Purpose.VIDEO, KeeperPrompts.videoSystem(context), user, KeeperClient.jpegDataUrl(grid.first, maxWidth = 1280, quality = 80))) {
            is KeeperResult.Answered -> {
                val v = KeeperParse.videoVerdict(r.json, meta.durationS, KeeperFocus.activeIds(context)).let { padCuts(it, meta.durationS) }
                KeeperCache.rememberVideo(context, videoId, v)
                Outcome(v, meta, spentRequest = true)
            }
            is KeeperResult.Failed -> Outcome(null, meta, r.why, spentRequest = r.why != KeeperResult.Why.NO_KEY && r.why != KeeperResult.Why.BUDGET)
        }
    }

    /** Decisions that need no AI. */
    fun quickVerdict(context: Context, meta: Meta): KeeperVerdict? = when {
        meta.ageRestricted -> KeeperVerdict(false, reason = context.getString(R.string.video_reason_age), confidence = 1.0, action = "block")
        isTrustedChannel(context, meta) -> KeeperVerdict(true, reason = context.getString(R.string.video_reason_trusted), confidence = 1.0, action = "allow")
        else -> null
    }

    private fun isTrustedChannel(context: Context, meta: Meta): Boolean =
        SafeTubeStore.getChannels(context).any { ch ->
            val handle = ch.handle.removePrefix("@").lowercase(Locale.ROOT)
            (ch.name.isNotBlank() && ch.name.equals(meta.channel, ignoreCase = true)) ||
                (meta.channelId.isNotBlank() && ch.url.contains(meta.channelId)) ||
                (handle.isNotBlank() && meta.channel.replace(" ", "").lowercase(Locale.ROOT) == handle)
        }

    // ── zero-cost word screen ────────────────────────────────────

    private val TITLE_TRIGGERS = listOf(
        "nsfw", "onlyfans", "only fans", "lingerie", "bikini", "twerk", "thirst trap", "try on haul", "try-on haul",
        "sexy", "seductive", "strip tease", "striptease", "hot girls", "hottest girls", "booty", "18+", "xxx",
        "nude", "naked", "make out", "makeout", "sensual", "lap dance", "girlfriend asmr", "boyfriend asmr"
    )

    /** Plainly a trigger in its own words: blocked without spending a request. */
    fun localTriggerVerdict(title: String, description: String, transcript: String): KeeperVerdict? {
        val t = title.lowercase(Locale.ROOT)
        val d = description.lowercase(Locale.ROOT).take(1500)
        val hit = TITLE_TRIGGERS.firstOrNull { t.contains(it) } ?: TITLE_TRIGGERS.firstOrNull { d.contains(it) }
        if (hit != null) return KeeperVerdict(false, reason = "\"$hit\" in the video's own title or description.", confidence = 1.0, action = "block")
        val s = transcript.lowercase(Locale.ROOT)
        val count = TITLE_TRIGGERS.sumOf { w -> Regex("\\b" + Regex.escape(w) + "\\b").findAll(s).count() }
        return if (count >= 3) KeeperVerdict(false, reason = "The transcript repeatedly talks about it.", confidence = 1.0, action = "block") else null
    }

    /** One extra second either side of every cut: frames are seconds apart. */
    fun padCuts(v: KeeperVerdict, durationS: Int): KeeperVerdict =
        if (v.action != "cut") v
        else v.copy(cuts = KeeperParse.mergeRanges(v.cuts.map { (a, b) -> (a - 1).coerceAtLeast(0) to (b + 1).coerceAtMost(durationS) }))

    // ── fetching ─────────────────────────────────────────────────

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.8")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun getBitmap(url: String): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; setRequestProperty("User-Agent", UA)
        }
        return conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }

    fun fetchMeta(videoId: String): Meta? = parsePlayerResponse(videoId, get("https://www.youtube.com/watch?v=$videoId&hl=en"))

    /** Pulls the player JSON out of the watch page. Pure, tested on saved pages. */
    fun parsePlayerResponse(videoId: String, html: String): Meta? {
        val marker = html.indexOf("ytInitialPlayerResponse")
        if (marker < 0) return null
        val json = KeeperParse.firstJsonObject(html.substring(marker)) ?: return null
        val details = json.optJSONObject("videoDetails") ?: JSONObject()
        val playability = json.optJSONObject("playabilityStatus") ?: JSONObject()
        val status = playability.optString("status")
        val reason = (playability.optString("reason") + " " + playability.optJSONObject("errorScreen")?.toString().orEmpty()).lowercase(Locale.ROOT)
        val ageRestricted = (status == "LOGIN_REQUIRED" && reason.contains("age")) || status == "AGE_CHECK_REQUIRED" || reason.contains("confirm your age")
        val tracks = json.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
        var caption: String? = null
        if (tracks != null && tracks.length() > 0) {
            // Prefer English, then the first track. Auto-captions ("asr") are fine.
            var pick = tracks.optJSONObject(0)
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                if (t.optString("languageCode").startsWith("en")) { pick = t; break }
            }
            caption = pick?.optString("baseUrl")?.takeIf { it.isNotBlank() }
        }
        val spec = json.optJSONObject("storyboards")?.optJSONObject("playerStoryboardSpecRenderer")?.optString("spec")?.takeIf { it.isNotBlank() }
        return Meta(
            id = details.optString("videoId", videoId),
            title = details.optString("title"),
            channel = details.optString("author"),
            channelId = details.optString("channelId"),
            description = details.optString("shortDescription"),
            durationS = details.optString("lengthSeconds").toIntOrNull() ?: 0,
            ageRestricted = ageRestricted,
            playable = status == "OK",
            captionUrl = caption,
            storyboardSpec = spec
        )
    }

    /** "[12] words words [15] more words" from YouTube's json3 caption format. */
    fun fetchTranscript(baseUrl: String): String = parseJson3(get("$baseUrl&fmt=json3"))

    fun parseJson3(body: String): String {
        if (body.isBlank()) return ""
        val events = JSONObject(body).optJSONArray("events") ?: return ""
        val sb = StringBuilder()
        var lastMark = -10
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val segs = e.optJSONArray("segs") ?: continue
            val sec = (e.optLong("tStartMs") / 1000).toInt()
            val text = buildString { for (j in 0 until segs.length()) append(segs.optJSONObject(j)?.optString("utf8").orEmpty()) }
                .replace('\n', ' ').trim()
            if (text.isEmpty()) continue
            if (sec - lastMark >= 10) { sb.append(" [").append(sec).append("] "); lastMark = sec } else sb.append(' ')
            sb.append(text)
        }
        return sb.toString().trim()
    }

    // ── storyboard frames → one grid ─────────────────────────────

    data class Level(val url: String, val w: Int, val h: Int, val count: Int, val cols: Int, val rows: Int, val intervalMs: Int, val name: String, val sigh: String)

    /** The largest storyboard level YouTube offers for this video. Pure. */
    fun parseStoryboard(spec: String): Level? {
        val parts = spec.split('|')
        if (parts.size < 2) return null
        val base = parts[0]
        val levelIndex = parts.size - 2
        val f = parts.last().split('#')
        if (f.size < 8) return null
        return Level(
            url = base.replace("\$L", levelIndex.toString()), w = f[0].toIntOrNull() ?: return null, h = f[1].toIntOrNull() ?: return null,
            count = f[2].toIntOrNull() ?: return null, cols = f[3].toIntOrNull() ?: return null, rows = f[4].toIntOrNull() ?: return null,
            intervalMs = f[5].toIntOrNull() ?: 0, name = f[6], sigh = f[7]
        )
    }

    /** Which storyboard frames to sample, evenly across the video, and their times in seconds. Pure. */
    fun sampleFrames(level: Level, durationS: Int, n: Int = GRID_FRAMES): List<Pair<Int, Int>> {
        val count = level.count.coerceAtLeast(1)
        val intervalMs = if (level.intervalMs > 0) level.intervalMs else (durationS * 1000 / count).coerceAtLeast(1)
        val picks = (0 until minOf(n, count)).map { j -> (((j + 0.5) * count / minOf(n, count)).toInt()).coerceIn(0, count - 1) }.distinct()
        return picks.map { k -> k to (k.toLong() * intervalMs / 1000).toInt().coerceAtMost(durationS) }
    }

    fun sheetUrl(level: Level, sheet: Int): String =
        level.url.replace("\$N", level.name.replace("\$M", sheet.toString())) + "&sigh=" + level.sigh

    /** Downloads only the sheets it needs; returns the grid image and the frame times in grid order. */
    fun buildGrid(spec: String, durationS: Int): Pair<Bitmap, List<Int>>? {
        val level = parseStoryboard(spec) ?: return null
        val frames = sampleFrames(level, durationS)
        if (frames.isEmpty()) return null
        val perSheet = level.cols * level.rows
        val sheets = HashMap<Int, Bitmap?>()
        val rows = (frames.size + GRID_COLS - 1) / GRID_COLS
        val grid = Bitmap.createBitmap(GRID_COLS * level.w, rows * level.h, Bitmap.Config.RGB_565)
        val canvas = Canvas(grid)
        var drawn = 0
        frames.forEachIndexed { i, (k, _) ->
            val sheetIdx = k / perSheet
            val sheet = sheets.getOrPut(sheetIdx) { try { getBitmap(sheetUrl(level, sheetIdx)) } catch (_: Exception) { null } } ?: return@forEachIndexed
            val pos = k % perSheet
            val sx = (pos % level.cols) * level.w; val sy = (pos / level.cols) * level.h
            if (sx + level.w > sheet.width || sy + level.h > sheet.height) return@forEachIndexed
            val dx = (i % GRID_COLS) * level.w; val dy = (i / GRID_COLS) * level.h
            canvas.drawBitmap(sheet, Rect(sx, sy, sx + level.w, sy + level.h), Rect(dx, dy, dx + level.w, dy + level.h), null)
            drawn++
        }
        // Too few frames came back to judge the whole video honestly.
        if (drawn < frames.size * 2 / 3) return null
        return grid to frames.map { it.second }
    }

    /** "45s", "3m 05s": short spans need seconds, not "0m". */
    fun secondsLabel(s: Int): String = if (s < 60) "${s}s" else "${s / 60}m ${String.format(Locale.ROOT, "%02d", s % 60)}s"

    /** "dQw4w9WgXcQ" from any watch/shorts/youtu.be/embed address. Pure. */
    fun videoIdFrom(url: String): String? {
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"), Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }
}
