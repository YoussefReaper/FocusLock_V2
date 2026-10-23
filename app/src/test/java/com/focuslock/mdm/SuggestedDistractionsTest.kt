package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestedDistractionsTest {

    private val none = emptySet<String>()

    @Test
    fun browsersAndStoresAreNeverPreselected() {
        val usage = listOf(
            "com.android.chrome" to AppCategory.BROWSING,
            "com.android.vending" to AppCategory.BROWSING,
            "com.instagram.android" to AppCategory.SOCIAL,
        )
        assertEquals(listOf("com.instagram.android"), UsageAnalytics.pickSuggestions(usage, none, emptyList(), 8))
    }

    @Test
    fun seededBrowsersAreNotPreselectedEither() {
        val seeds = listOf(
            "com.android.vending" to AppCategory.BROWSING,
            "com.android.chrome" to AppCategory.BROWSING,
            "com.spotify.music" to AppCategory.OTHER,
        )
        assertEquals(listOf("com.spotify.music"), UsageAnalytics.pickSuggestions(emptyList(), none, seeds, 8))
    }

    @Test
    fun essentialsAreSkippedAndSeedsFillTheRest() {
        val usage = listOf("com.whatsapp" to AppCategory.SOCIAL, "com.tiktok" to AppCategory.VIDEO)
        val picked = UsageAnalytics.pickSuggestions(usage, setOf("com.whatsapp"), listOf("com.tiktok" to AppCategory.VIDEO, "com.reddit" to AppCategory.SOCIAL), 8)
        assertEquals(listOf("com.tiktok", "com.reddit"), picked)
    }

    @Test
    fun limitIsRespected() {
        val usage = (1..12).map { "app.$it" to AppCategory.GAMES }
        assertEquals(8, UsageAnalytics.pickSuggestions(usage, none, emptyList(), 8).size)
    }
}
