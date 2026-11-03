package com.focuslock.mdm

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Hits the real YouTube; runs only with LIVE_NET=1 set. */
class StreamsLiveTest {
    @Test fun resolvesAndServesTheFirstBytes() {
        assumeTrue(System.getenv("LIVE_NET") == "1")
        val id = System.getenv("LIVE_VIDEO") ?: "jNQXAC9IVRw"
        val s = FlavorStreams.resolve(id)
        assertNotNull("no streams", s)
        println("muxed=${s!!.muxed} audio=${s.audioUrl != null}")
        val c = (URL(s.videoUrl).openConnection() as HttpURLConnection).apply { setRequestProperty("Range", "bytes=0-65535") }
        println("video range status=${c.responseCode} len=${c.contentLengthLong}")
        assertTrue(c.responseCode in 200..299)
    }
}
