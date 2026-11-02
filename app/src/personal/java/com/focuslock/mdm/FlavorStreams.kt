package com.focuslock.mdm

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.HttpURLConnection
import java.net.URL

/**
 * Personal build only: finds a video's stream addresses with NewPipeExtractor
 * (GPL-3.0). Picks H.264 video up to 720p plus AAC audio, both progressive, so the
 * downloader can remux them into an MP4 without re-encoding.
 */
object FlavorStreams {
    const val isPresent = true
    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) { if (!ready) { NewPipe.init(PlainDownloader); ready = true } }
    }

    fun resolve(videoId: String): VideoStreams? {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val video = info.videoOnlyStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.codec.orEmpty().startsWith("avc") }
            .filter { (it.height) in 1..720 }
            .maxByOrNull { it.height }
        val audio = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.codec.orEmpty().startsWith("mp4a") }
            .maxByOrNull { it.averageBitrate }
        if (video != null && audio != null) return VideoStreams(video.content, audio.content, muxed = false)
        // Fallback: a progressive stream that already carries both (usually 360p).
        val muxed = info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && !it.isVideoOnly }
            .maxByOrNull { it.height }
        return muxed?.let { VideoStreams(it.content, null, muxed = true) }
    }

    /** NewPipe's network hook, on plain HttpURLConnection (no extra libraries). */
    private object PlainDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = 20_000; readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                request.headers().forEach { (k, values) -> values.forEach { addRequestProperty(k, it) } }
            }
            request.dataToSend()?.let { body -> conn.doOutput = true; conn.outputStream.use { it.write(body) } }
            val code = conn.responseCode
            val text = try { (if (code in 200..399) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
            val headers = conn.headerFields.filterKeys { it != null }
            return Response(code, conn.responseMessage, headers, text, conn.url.toString())
        }
    }
}
