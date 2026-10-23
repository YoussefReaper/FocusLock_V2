package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading version.json - a file this app trusts only as far as it has to. */
class UpdateCheckTest {

    private val json = """
        {
          "full": { "versionCode": 18, "versionName": "1.17", "notes": "Faster guard.", "url": "https://focus-lock-c0xg.onrender.com/update.html" },
          "demo": { "versionCode": 18, "versionName": "1.17-demo", "url": "https://focus-lock-c0xg.onrender.com/#download" }
        }
    """.trimIndent()

    @Test
    fun eachTierReadsItsOwnEntry() {
        val full = UpdateCheck.parse(json, "full")!!
        assertEquals(18, full.versionCode)
        assertEquals("1.17", full.versionName)
        assertEquals("Faster guard.", full.notes)
        assertEquals("1.17-demo", UpdateCheck.parse(json, "demo")!!.versionName)
    }

    @Test
    fun aMissingTierIsNoAnswer() {
        assertNull(UpdateCheck.parse("""{ "full": { "versionCode": 18, "versionName": "1.17", "url": "https://x" } }""", "demo"))
    }

    @Test
    fun anythingButAnHttpsLinkIsRefused() {
        // The link is opened in a browser; it must not be able to point anywhere odd.
        assertNull(UpdateCheck.parse("""{ "full": { "versionCode": 18, "versionName": "1.17", "url": "http://x" } }""", "full"))
        assertNull(UpdateCheck.parse("""{ "full": { "versionCode": 18, "versionName": "1.17", "url": "intent://x" } }""", "full"))
    }

    @Test
    fun incompleteEntriesAreRefused() {
        assertNull(UpdateCheck.parse("""{ "full": { "versionName": "1.17", "url": "https://x" } }""", "full"))
        assertNull(UpdateCheck.parse("""{ "full": { "versionCode": 18, "url": "https://x" } }""", "full"))
    }

    @Test
    fun onlyAHigherCodeIsAnUpdate() {
        assertTrue(UpdateCheck.isNewer(18, 17))
        assertFalse(UpdateCheck.isNewer(17, 17))
        assertFalse(UpdateCheck.isNewer(16, 17))
    }
}
