package com.focuslock.mdm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** Where a video's streams can be fetched from. Only the personal build can answer. */
data class VideoStreams(val videoUrl: String, val audioUrl: String?, val muxed: Boolean)

/**
 * Downloads a checked video and writes it to the chosen folder with the Keeper's
 * cuts removed.
 *
 * No re-encoding: the video and audio are copied sample by sample into a new MP4
 * (Android's own MediaExtractor/MediaMuxer), skipping everything inside a cut. A
 * cut always ends on the next keyframe, so it can only remove a little more than
 * asked, never less. Both tracks skip the same ranges, so they stay in sync.
 */
object SafeTubeDownloader {

    private const val CHANNEL = "safetube_downloads"
    private const val CHUNK = 8L * 1024 * 1024

    fun enqueue(context: Context, videoId: String, title: String, cuts: List<Pair<Int, Int>>) {
        val app = context.applicationContext
        val folder = FocusStore.getString(app, KeeperSettingsActivity.KEY_SAVE_FOLDER, "")
        if (folder.isBlank()) { FocusDialog.toast(context, context.getString(R.string.safetube_folder_none)); return }
        FocusDialog.toast(context, context.getString(R.string.download_started))
        val notifyId = videoId.hashCode()
        thread(name = "safetube-dl-$videoId") {
            val work = File(app.cacheDir, "safetube").apply { mkdirs() }
            val vFile = File(work, "$videoId.v"); val aFile = File(work, "$videoId.a"); val out = File(work, "$videoId.mp4")
            try {
                // Same gate as the player: nothing is saved that the Keeper has not cleared.
                val verdict = KeeperCache.lookupVideo(app, videoId)
                if (verdict == null || !verdict.allow) error("not cleared")
                notify(app, notifyId, title, app.getString(R.string.download_fetching), 0)
                val streams = FlavorStreams.resolve(videoId) ?: error("no streams")
                download(streams.videoUrl, vFile) { p -> notify(app, notifyId, title, app.getString(R.string.download_fetching), p / (if (streams.audioUrl != null) 2 else 1)) }
                streams.audioUrl?.let { u -> download(u, aFile) { p -> notify(app, notifyId, title, app.getString(R.string.download_fetching), 50 + p / 2) } }
                notify(app, notifyId, title, app.getString(R.string.download_cleaning), 100)
                remux(vFile, if (streams.audioUrl != null) aFile else null, out, verdict.cuts)
                saveToFolder(app, Uri.parse(folder), safeName(title) + ".mp4", out)
                done(app, notifyId, title, app.getString(R.string.download_done))
            } catch (e: Exception) {
                android.util.Log.w("SafeTube", "download $videoId failed", e)
                done(app, notifyId, title, app.getString(R.string.download_failed))
            } finally {
                vFile.delete(); aFile.delete(); out.delete()
            }
        }
    }

    // ── fetching in chunks (YouTube throttles one long request) ──

    private fun download(url: String, to: File, progress: (Int) -> Unit) {
        FileOutputStream(to).use { sink ->
            var start = 0L
            var total = -1L
            while (true) {
                val end = start + CHUNK - 1
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000
                    setRequestProperty("Range", "bytes=$start-$end")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
                }
                val code = conn.responseCode
                if (code != 206 && code != 200) error("HTTP $code")
                if (total < 0) total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: conn.contentLengthLong
                var got = 0L
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; sink.write(buf, 0, n); got += n }
                }
                start += got
                if (total > 0) progress((start * 100 / total).toInt().coerceIn(0, 100))
                if (code == 200 || got < CHUNK || (total in 1..start)) break
            }
        }
    }

    // ── remux with cuts ──────────────────────────────────────────

    /**
     * The ranges actually removed: each cut's end moved forward to the next video
     * keyframe, so playback resumes cleanly. Pure, given the keyframe times.
     */
    fun effectiveCuts(cutsS: List<Pair<Int, Int>>, keyframesUs: List<Long>): List<Pair<Long, Long>> =
        cutsS.map { (a, b) ->
            val startUs = a * 1_000_000L
            val endUs = b * 1_000_000L
            startUs to (keyframesUs.firstOrNull { it >= endUs } ?: Long.MAX_VALUE)
        }

    /** Time to subtract from a sample at [ptsUs] (the removed time before it), or null if it falls inside a cut. Pure. */
    fun shiftFor(ptsUs: Long, cuts: List<Pair<Long, Long>>): Long? {
        var removed = 0L
        for ((a, b) in cuts) {
            if (ptsUs in a until b) return null
            if (ptsUs >= b) removed += b - a
        }
        return removed
    }

    private fun keyframeTimes(file: File): List<Long> {
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        val out = ArrayList<Long>()
        try {
            val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return out
            ex.selectTrack(t)
            while (true) {
                if (ex.sampleTrackIndex < 0) break
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) out.add(ex.sampleTime)
                if (!ex.advance()) break
            }
        } finally { ex.release() }
        return out
    }

    private fun remux(video: File, audio: File?, out: File, cutsS: List<Pair<Int, Int>>) {
        val cuts = if (cutsS.isEmpty()) emptyList() else effectiveCuts(cutsS, keyframeTimes(video))
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOfNotNull(video, audio).map { f -> MediaExtractor().apply { setDataSource(f.path) } }
        try {
            // The first video and the first audio track found, wherever they live.
            val plan = ArrayList<Triple<MediaExtractor, Int, Int>>()
            for (kind in listOf("video/", "audio/")) {
                for (ex in sources) {
                    val i = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true } ?: continue
                    plan.add(Triple(ex, i, muxer.addTrack(ex.getTrackFormat(i))))
                    break
                }
            }
            if (plan.isEmpty()) error("no tracks")
            muxer.start()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((ex, track, outTrack) in plan) {
                (0 until ex.trackCount).forEach { ex.unselectTrack(it) }
                ex.selectTrack(track)
                ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    val pts = ex.sampleTime
                    val shift = shiftFor(pts, cuts)
                    if (shift != null) {
                        info.set(0, size, pts - shift, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(outTrack, buf, info)
                    }
                    if (!ex.advance()) break
                }
            }
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Exception) { }
            sources.forEach { it.release() }
        }
    }

    // ── saving & notifying ───────────────────────────────────────

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "video" }

    private fun saveToFolder(context: Context, tree: Uri, name: String, file: File) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, "video/mp4", name) ?: error("cannot create")
        context.contentResolver.openOutputStream(doc)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: error("cannot write")
    }

    private fun manager(c: Context): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        }
        return nm
    }

    private fun notify(c: Context, id: Int, title: String, text: String, percent: Int) {
        try {
            manager(c).notify(id, NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text)
                .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, percent, false).build())
        } catch (_: SecurityException) { }
    }

    private fun done(c: Context, id: Int, title: String, text: String) {
        try {
            manager(c).notify(id, NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setContentText(text).build())
        } catch (_: SecurityException) { }
    }
}
