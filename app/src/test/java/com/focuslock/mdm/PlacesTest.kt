package com.focuslock.mdm

import com.focuslock.mdm.Places.Verdict.ASK
import com.focuslock.mdm.Places.Verdict.CLOSED
import com.focuslock.mdm.Places.Verdict.OPEN
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesTest {
    private val subs = setOf("physics")
    private val servers = setOf("123456789012345678")
    private fun v(url: String) = Places.verdict(url, subs, servers)

    @Test fun redditOnlyApprovedSubs() {
        assertEquals(OPEN, v("https://www.reddit.com/r/physics/comments/abc/x"))
        assertEquals(CLOSED, v("https://www.reddit.com/r/memes/"))
        assertEquals(CLOSED, v("https://www.reddit.com/"))
        assertEquals(CLOSED, v("https://www.reddit.com/r/popular"))
        assertEquals(CLOSED, v("https://old.reddit.com/r/all"))
    }

    @Test fun discordDmsOpenServersApproved() {
        assertEquals(OPEN, v("https://discord.com/channels/@me/999"))
        assertEquals(OPEN, v("https://discord.com/channels/123456789012345678/1"))
        assertEquals(CLOSED, v("https://discord.com/channels/876543210987654321/1"))
        assertEquals(CLOSED, v("https://discord.com/discovery"))
        assertEquals(CLOSED, v("https://discord.gg/abc"))
    }

    @Test fun otherSitesGoToTheKeeper() {
        assertEquals(ASK, v("https://khanacademy.org/x"))
    }

    @Test fun cleansSubredditNames() {
        assertEquals("physics", Places.cleanSub("https://www.reddit.com/r/Physics/"))
        assertEquals("physics", Places.cleanSub("r/physics"))
    }
}
