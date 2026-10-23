package com.focuslock.mdm

import androidx.annotation.StringRes
import android.content.Context

/**
 * Broad buckets used for per-category rules and for the analytics breakdown.
 */
enum class AppCategory(val id: String, @StringRes val labelRes: Int, @StringRes val blurbRes: Int) {
    SOCIAL("social", R.string.category_social_label, R.string.category_social_blurb),
    VIDEO("video", R.string.category_video_label, R.string.category_video_blurb),
    GAMES("games", R.string.category_games_label, R.string.category_games_blurb),
    BROWSING("browsing", R.string.category_browsing_label, R.string.category_browsing_blurb),
    MESSAGING("messaging", R.string.category_messaging_label, R.string.category_messaging_blurb),
    ESSENTIAL("essential", R.string.category_essential_label, R.string.category_essential_blurb),
    PRODUCTIVITY("productivity", R.string.category_productivity_label, R.string.category_productivity_blurb),
    SYSTEM("system", R.string.category_system_label, R.string.category_system_blurb),
    OTHER("other", R.string.category_other_label, R.string.category_other_blurb);

    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)

    companion object {
        fun fromId(id: String?): AppCategory =
            values().firstOrNull { it.id == id } ?: OTHER

        /** Categories a user would sensibly write a bulk rule against. */
        val ruleTargets: List<AppCategory> =
            listOf(SOCIAL, VIDEO, GAMES, BROWSING, MESSAGING, PRODUCTIVITY, OTHER)
    }
}

/**
 * Default data.
 *
 * Nothing here is enforced directly. These lists are copied into user-owned
 * stores once, on first run, and are the user's to edit from that moment on.
 * If a list here changes in a later build, existing users keep their edits.
 */
object Seed {

    /** Apps most people install FocusLock to get away from. Seeds the block list. */
    /**
     * The apps nearly everyone means by "distracting": short video and social
     * feeds. The only apps suggested for blocking when someone skips the
     * "what pulls hardest" question - no browsers, no stores, no music, and
     * nothing whose only claim is a category the app declared about itself.
     */
    val obviousDistractions: Set<String> = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.instagram.lite",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.snapchat.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.pinterest",
        "tv.twitch.android.app",
        "com.netflix.mediaclient"
    )

    /**
     * Exact packages whose declared category is wrong for this purpose.
     * Checked before everything else. Found on a real phone: Gmail and
     * Truecaller declare themselves "social", which put email and caller ID on
     * the list of things suggested for blocking.
     */
    val categoryOverrides: Map<String, AppCategory> = mapOf(
        "com.google.android.gm" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.outlook" to AppCategory.PRODUCTIVITY,
        "com.samsung.android.email.provider" to AppCategory.PRODUCTIVITY,
        "com.android.email" to AppCategory.PRODUCTIVITY,
        "com.truecaller" to AppCategory.ESSENTIAL,
        "com.google.android.apps.authenticator2" to AppCategory.ESSENTIAL,
        "com.azure.authenticator" to AppCategory.ESSENTIAL,
        "com.google.android.apps.walletnfcrel" to AppCategory.ESSENTIAL
    )

    val distractions: List<String> = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.instagram.android",
        "com.instagram.lite",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.snapchat.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.pinterest",
        "com.discord",
        "com.tumblr",
        "com.linkedin.android",
        "tv.twitch.android.app",
        "com.netflix.mediaclient",
        "com.spotify.music",
        "com.android.vending",
        "com.android.chrome",
        "com.miui.browser",
        "com.mi.globalbrowser",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "org.torproject.torbrowser",
        "com.duckduckgo.mobile.android",
        "com.xiaomi.mipicks",
        "com.miui.videoplayer",
        "com.miui.player",
        "com.einnovation.temu",
        "com.amazon.mShop.android.shopping",
        "com.shein",
        "com.alibaba.aliexpresshd",
        "com.ebay.mobile",
        "com.ubercab",
        "com.talabat",
        "com.microsoft.copilot"
    )

    /**
     * Apps that stay reachable no matter what. A blocker that cuts off a phone
     * call is a blocker that gets uninstalled, so this list wins over everything.
     */
    val essentials: List<String> = listOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.google.android.contacts",
        "com.android.contacts",
        "com.samsung.android.app.contacts",
        "com.google.android.apps.messaging",
        "com.android.messaging",
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.google.android.apps.maps",
        "com.android.camera",
        "com.android.camera2",
        "org.codeaurora.snapcam",
        "com.google.android.calendar",
        "com.android.calendar",
        "com.google.android.keep"
    )

    /**
     * Package-name fragments used to guess a category when the system does not
     * report one. Checked in order; first hit wins.
     */
    val categoryHints: List<Pair<String, AppCategory>> = listOf(
        // Before the broad ones below, so "mail" wins over anything else in a
        // mail app's package name.
        "truecaller" to AppCategory.ESSENTIAL,
        "authenticator" to AppCategory.ESSENTIAL,
        "bank" to AppCategory.ESSENTIAL,
        "wallet" to AppCategory.ESSENTIAL,
        ".email" to AppCategory.PRODUCTIVITY,
        "mail" to AppCategory.PRODUCTIVITY,
        "youtube" to AppCategory.VIDEO,
        "netflix" to AppCategory.VIDEO,
        "twitch" to AppCategory.VIDEO,
        "primevideo" to AppCategory.VIDEO,
        "disney" to AppCategory.VIDEO,
        "videoplayer" to AppCategory.VIDEO,
        "musically" to AppCategory.VIDEO,
        "ugc.trill" to AppCategory.VIDEO,
        "instagram" to AppCategory.SOCIAL,
        "facebook" to AppCategory.SOCIAL,
        "snapchat" to AppCategory.SOCIAL,
        "twitter" to AppCategory.SOCIAL,
        "reddit" to AppCategory.SOCIAL,
        "pinterest" to AppCategory.SOCIAL,
        "tumblr" to AppCategory.SOCIAL,
        "linkedin" to AppCategory.SOCIAL,
        "discord" to AppCategory.SOCIAL,
        "whatsapp" to AppCategory.MESSAGING,
        "telegram" to AppCategory.MESSAGING,
        "signal" to AppCategory.MESSAGING,
        "messenger" to AppCategory.MESSAGING,
        "messaging" to AppCategory.MESSAGING,
        "dialer" to AppCategory.ESSENTIAL,
        "telecom" to AppCategory.ESSENTIAL,
        "contacts" to AppCategory.ESSENTIAL,
        "deskclock" to AppCategory.ESSENTIAL,
        "clockpackage" to AppCategory.ESSENTIAL,
        "camera" to AppCategory.ESSENTIAL,
        "maps" to AppCategory.ESSENTIAL,
        "calendar" to AppCategory.ESSENTIAL,
        "chrome" to AppCategory.BROWSING,
        "browser" to AppCategory.BROWSING,
        "firefox" to AppCategory.BROWSING,
        "vending" to AppCategory.BROWSING,
        "mipicks" to AppCategory.BROWSING,
        "duckduckgo" to AppCategory.BROWSING,
        "game" to AppCategory.GAMES,
        "gameloft" to AppCategory.GAMES,
        "supercell" to AppCategory.GAMES,
        "chess" to AppCategory.GAMES,
        "lichess" to AppCategory.GAMES,
        "docs" to AppCategory.PRODUCTIVITY,
        "keep" to AppCategory.PRODUCTIVITY,
        "notion" to AppCategory.PRODUCTIVITY,
        "outlook" to AppCategory.PRODUCTIVITY,
        "classroom" to AppCategory.PRODUCTIVITY,
        "duolingo" to AppCategory.PRODUCTIVITY,
        "khanacademy" to AppCategory.PRODUCTIVITY,
        "anki" to AppCategory.PRODUCTIVITY,
        "udemy" to AppCategory.PRODUCTIVITY,
        "zoom" to AppCategory.PRODUCTIVITY,
        "ticktick" to AppCategory.PRODUCTIVITY
    )

    /**
     * The WhatsApp guard, expressed as ordinary keyword rules so it is visible
     * and editable rather than buried in a constant.
     */
    val whatsappBlockedPhrases: List<String> = listOf(
        "find channels to follow",
        "find channels",
        "explore channels",
        // WhatsApp's own rewording (seen 2026-09): the channel directory lost
        // its "Find channels to follow" header and became a list of suggested
        // channels ending in "Explore more" and "Create channel". The phrase
        // "newsletter directory" is never on screen - it matches the view id
        // WhatsApp gives each suggested channel (newsletter_directory_row_...;
        // channels are "newsletters" inside WhatsApp), which survives a change
        // of wording where visible text does not.
        "explore more",
        "create channel",
        "newsletter directory",
        "meta ai",
        "app language"
    )

    val whatsappAllowedPhrases: List<String> = listOf(
        "ask meta ai or search",
        "ask meta ai"
    )

    val whatsappPackages: List<String> = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    /** Screen text that means "you are standing in the short-video firehose". */
    val shortsPhrases: List<String> = listOf(
        "shorts"
    )

    val shortsPackages: List<String> = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube.tv"
    )

    val reelsPhrases: List<String> = listOf(
        "reels",
        "reels and short videos",
        "spotlight",
        "for you page"
    )

    val reelsPackages: List<String> = listOf(
        "com.instagram.android",
        "com.instagram.lite",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.snapchat.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill"
    )

    /**
     * Deliberately clinical rather than explicit. These are matched against
     * on-screen text and against hostnames in the browser.
     */
    val adultKeywords: List<String> = listOf(
        "porn",
        "pornhub",
        "xvideos",
        "xnxx",
        "onlyfans",
        "nsfw",
        "hentai",
        "camgirl",
        "escort service",
        "adult video chat"
    )

    val adultDomains: List<String> = listOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "onlyfans.com",
        "redtube.com",
        "youporn.com",
        "xhamster.com",
        "chaturbate.com",
        "stripchat.com",
        "spankbang.com"
    )

    /**
     * Calm things to offer instead of a dead end. Each entry is a label string
     * resource (so the block screen follows the app language) and the intent
     * key the intercept screen knows how to open.
     */
    val replacements: List<Pair<Int, String>> = listOf(
        R.string.alt_read_instead to "textSearch",
        R.string.alt_safe_browser to "safeBrowser",
        R.string.alt_where_time_went to "analytics"
    )
}
