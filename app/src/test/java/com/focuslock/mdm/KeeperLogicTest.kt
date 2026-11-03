package com.focuslock.mdm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeeperLogicTest {

    // ── reading the model's reply ──

    @Test fun jsonIsFoundAfterThinkingAndFences() {
        val reply = "<think>looks like a school form {not json}</think>\n```json\n{\"verdict\":\"allow\",\"scope\":\"site\",\"confidence\":0.9,\"reason\":\"A quiz form {safe}.\"}\n```"
        val o = KeeperParse.firstJsonObject(reply)
        assertNotNull(o)
        assertEquals("allow", o!!.getString("verdict"))
        assertEquals("A quiz form {safe}.", o.getString("reason"))
    }

    @Test fun noJsonMeansNull() = assertNull(KeeperParse.firstJsonObject("I think it is fine"))

    // ── web verdicts: unsure means no ──

    @Test fun confidentAllowOpens() {
        val v = KeeperParse.webVerdict(JSONObject("""{"verdict":"allow","scope":"site","confidence":0.92,"reason":"Exam portal."}"""))
        assertTrue(v.allow); assertEquals("site", v.scope)
    }

    @Test fun lowConfidenceAllowStaysClosed() =
        assertFalse(KeeperParse.webVerdict(JSONObject("""{"verdict":"allow","confidence":0.5}""")).allow)

    @Test fun missingConfidenceStaysClosed() =
        assertFalse(KeeperParse.webVerdict(JSONObject("""{"verdict":"allow"}""")).allow)

    @Test fun anythingButAllowIsDeny() =
        assertFalse(KeeperParse.webVerdict(JSONObject("""{"verdict":"maybe","confidence":0.99}""")).allow)

    @Test fun deniedPagesNeverGetSiteScope() =
        assertEquals("page", KeeperParse.webVerdict(JSONObject("""{"verdict":"deny","scope":"site","confidence":0.9}""")).scope)

    // ── video verdicts ──

    @Test fun cutsAreClampedMergedAndKept() {
        val v = KeeperParse.videoVerdict(JSONObject("""{"verdict":"cut","confidence":0.8,
            "cuts":[{"from":100,"to":110},{"from":108,"to":120},{"from":590,"to":700}]}"""), 600)
        assertTrue(v.allow); assertEquals("cut", v.action)
        assertEquals(listOf(100 to 120, 590 to 600), v.cuts)
    }

    @Test fun cutWithNoUsableRangesIsBlock() {
        val v = KeeperParse.videoVerdict(JSONObject("""{"verdict":"cut","confidence":0.9,"cuts":[{"from":"x","to":5}]}"""), 300)
        assertFalse(v.allow); assertEquals("block", v.action)
    }

    @Test fun cuttingMostOfTheVideoIsBlock() {
        val v = KeeperParse.videoVerdict(JSONObject("""{"verdict":"cut","confidence":0.9,"cuts":[{"from":0,"to":200}]}"""), 300)
        assertFalse(v.allow)
    }

    @Test fun unsureAllowIsBlock() =
        assertFalse(KeeperParse.videoVerdict(JSONObject("""{"verdict":"allow","confidence":0.4}"""), 300).allow)

    @Test fun cutsGetPaddedASecondEachSide() {
        val v = VideoCheck.padCuts(KeeperVerdict(true, action = "cut", cuts = listOf(0 to 10, 50 to 60)), 60)
        assertEquals(listOf(0 to 11, 49 to 60), v.cuts)
    }

    // ── the budget ──

    @Test fun videosStopAtTheirCapButLinksDoNot() {
        assertFalse(KeeperBudget.allowedToSpend(850, 850, Keeper.Purpose.VIDEO))
        assertTrue(KeeperBudget.allowedToSpend(850, 850, Keeper.Purpose.UNLOCK))
    }

    @Test fun nothingPastTheDailyLimit() =
        assertFalse(KeeperBudget.allowedToSpend(1000, 10, Keeper.Purpose.UNLOCK))

    // ── verdicts survive storage ──

    @Test fun verdictRoundTrips() {
        val v = KeeperVerdict(true, "page", "ok", 0.8, "cut", listOf(1 to 5))
        val back = KeeperVerdict.fromJson(v.toJson())
        assertEquals(v.cuts, back.cuts); assertEquals("cut", back.action); assertTrue(back.allow)
    }
}

class VideoCheckParsingTest {

    private val spec = "https://i.ytimg.com/sb/abcdefghijk/storyboard3_L\$L/\$N.jpg?sqp=xyz" +
        "|48#27#100#10#10#0#default#rs\$A" +
        "|80#45#100#10#10#6000#M\$M#rs\$B" +
        "|160#90#100#5#5#6000#M\$M#rs\$C"

    @Test fun picksTheLargestStoryboardLevel() {
        val l = VideoCheck.parseStoryboard(spec)!!
        assertEquals(160, l.w); assertEquals(90, l.h); assertEquals(5, l.cols); assertEquals(6000, l.intervalMs)
        assertEquals("https://i.ytimg.com/sb/abcdefghijk/storyboard3_L2/M3.jpg?sqp=xyz&sigh=rs\$C", VideoCheck.sheetUrl(l, 3))
    }

    @Test fun framesSpreadAcrossTheWholeVideo() {
        val l = VideoCheck.parseStoryboard(spec)!!
        val f = VideoCheck.sampleFrames(l, 600)
        assertEquals(24, f.size)
        assertTrue(f.first().second < 30)
        assertTrue(f.last().second > 560)
        assertEquals(f.map { it.second }.sorted(), f.map { it.second })
    }

    @Test fun badSpecIsNull() = assertNull(VideoCheck.parseStoryboard("https://x|1#2"))

    @Test fun transcriptGetsTimeMarks() {
        val json = """{"events":[{"tStartMs":0,"segs":[{"utf8":"hello"}]},{"tStartMs":4000,"segs":[{"utf8":"world"}]},
            {"tStartMs":15000,"segs":[{"utf8":"[Music]"}]},{"tStartMs":16000}]}"""
        assertEquals("[0] hello world [15] [Music]", VideoCheck.parseJson3(json))
    }

    @Test fun videoIdFromEveryAddressShape() {
        listOf(
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=3", "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ", "https://www.youtube.com/embed/dQw4w9WgXcQ"
        ).forEach { assertEquals(it, "dQw4w9WgXcQ", VideoCheck.videoIdFrom(it)) }
        assertNull(VideoCheck.videoIdFrom("https://m.youtube.com/@khanacademy"))
    }

    @Test fun obviousTitlesAreBlockedForFree() {
        assertNotNull(VideoCheck.localTriggerVerdict("Summer BIKINI try on haul", "", ""))
        assertNull(VideoCheck.localTriggerVerdict("Organic chemistry: SN1 vs SN2", "Lecture 4", "today we look at"))
    }

    @Test fun playerResponseIsReadFromTheWatchPage() {
        val html = """<script>var ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"},
            "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Krebs cycle {explained}","author":"Bio Prof","channelId":"UC1","lengthSeconds":"615","shortDescription":"x"},
            "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://www.youtube.com/api/timedtext?v=1&lang=ar","languageCode":"ar"},{"baseUrl":"https://www.youtube.com/api/timedtext?v=1&lang=en","languageCode":"en","kind":"asr"}]}},
            "storyboards":{"playerStoryboardSpecRenderer":{"spec":"https://i.ytimg.com/sb/x/L${'$'}L/${'$'}N.jpg|48#27#10#10#10#0#default#s"}}};var meta = {};</script>"""
        val m = VideoCheck.parsePlayerResponse("dQw4w9WgXcQ", html)!!
        assertEquals("Krebs cycle {explained}", m.title); assertEquals(615, m.durationS); assertFalse(m.ageRestricted)
        assertTrue(m.captionUrl!!.endsWith("lang=en")); assertNotNull(m.storyboardSpec)
    }

    @Test fun ageGateIsDetected() {
        val html = """ytInitialPlayerResponse = {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"},"videoDetails":{"videoId":"abcdefghijk"}};"""
        assertTrue(VideoCheck.parsePlayerResponse("abcdefghijk", html)!!.ageRestricted)
    }
}

class SafeTubeCutTest {

    @Test fun cutEndsMoveToTheNextKeyframe() {
        val keys = listOf(0L, 2_000_000L, 4_000_000L, 9_000_000L)
        val c = SafeTubeDownloader.effectiveCuts(listOf(3 to 5), keys)
        assertEquals(3_000_000L, c[0].first); assertEquals(9_000_000L, c[0].second)
    }

    @Test fun samplesInsideACutAreDroppedAndLaterOnesShift() {
        val cuts = listOf(3_000_000L to 9_000_000L)
        assertEquals(0L, SafeTubeDownloader.shiftFor(1_000_000L, cuts))
        assertNull(SafeTubeDownloader.shiftFor(5_000_000L, cuts))
        assertEquals(6_000_000L, SafeTubeDownloader.shiftFor(9_000_000L, cuts))
    }

    @Test fun aCutRunningPastTheLastKeyframeRemovesTheRest() {
        val c = SafeTubeDownloader.effectiveCuts(listOf(10 to 12), listOf(0L, 5_000_000L))
        assertNull(SafeTubeDownloader.shiftFor(20_000_000L, c))
    }

    @Test fun sharedTextYieldsItsLink() {
        assertEquals("https://forms.gle/abc123", AllowlistStore.firstUrlIn("Exam today, link: https://forms.gle/abc123."))
        assertNull(AllowlistStore.firstUrlIn("no link here"))
    }
}

class KeeperFocusTest {
    private val active = setOf("nudity", "sexual", "suggestive", "revealing", "intimate", "adult_sites")

    @Test fun goreIsNotOnTheListSoACutIsOverruled() {
        val v = KeeperParse.videoVerdict(JSONObject("""{"verdict":"cut","confidence":0.9,"matched":["violence","gore"],
            "cuts":[{"from":10,"to":20}]}"""), 600, active)
        assertTrue(v.allow); assertEquals("allow", v.action); assertTrue(v.cuts.isEmpty())
    }

    @Test fun aListedItemStillCuts() {
        val v = KeeperParse.videoVerdict(JSONObject("""{"verdict":"cut","confidence":0.9,"matched":["revealing"],
            "cuts":[{"from":10,"to":20}]}"""), 600, active)
        assertEquals("cut", v.action); assertEquals(listOf(10 to 20), v.cuts)
    }

    @Test fun aRefusalThatNamesNothingStaysARefusal() =
        assertFalse(KeeperParse.videoVerdict(JSONObject("""{"verdict":"block","confidence":0.9}"""), 600, active).allow)

    @Test fun unsureStaysBlocked() =
        assertFalse(KeeperParse.webVerdict(JSONObject("""{"verdict":"deny","matched":["unsure"],"confidence":0.4}"""), active).allow)

    @Test fun feedsAreStillDenied() =
        assertFalse(KeeperParse.webVerdict(JSONObject("""{"verdict":"deny","matched":["feed"],"confidence":0.9}"""), active).allow)

    @Test fun aHorrorPageDeniedForHorrorOpens() =
        assertTrue(KeeperParse.webVerdict(JSONObject("""{"verdict":"deny","matched":["horror"],"confidence":0.9}"""), active).allow)

    @Test fun aSwitchedOffItemNoLongerCounts() {
        val v = KeeperParse.videoVerdict(JSONObject("""{"verdict":"block","confidence":0.9,"matched":["intimate"]}"""), 600, active - "intimate")
        assertTrue(v.allow)
    }

    @Test fun matchedAsAPlainStringIsRead() =
        assertEquals(listOf("nudity"), KeeperParse.matched(JSONObject("""{"matched":"Nudity"}""")))
}
