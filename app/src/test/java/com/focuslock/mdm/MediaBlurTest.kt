package com.focuslock.mdm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBlurTest {
    @Test fun safeTilesIgnoresOutOfRangeAndJunk() {
        val json = JSONObject("""{"safe":[0, 3, 15, 20, -1]}""")
        assertEquals(setOf(0, 3), MediaBlur.safeTiles(json, 4))
        assertTrue(MediaBlur.safeTiles(JSONObject("{}"), 4).isEmpty())
        assertTrue(MediaBlur.safeTiles(null, 4).isEmpty())
    }

    @Test fun keyDropsFragment() {
        assertEquals("https://a.b/c.jpg", MediaBlur.key("https://a.b/c.jpg#x"))
    }

    @Test fun scriptCarriesClearedList() {
        val s = MediaBlur.script("[\"https://a.b/c.jpg\"]")
        assertTrue(s.contains("new Set([\"https://a.b/c.jpg\"])"))
        assertTrue(s.contains("data-ph-ok"))
    }
}
