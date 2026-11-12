package com.focuslock.mdm

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/**
 * Keeps Telegram usable for the two people you actually need, and closed for
 * the forty channels you do not.
 *
 * Telegram is the hardest case the content guard has: blocking the app costs
 * you your group chats, and blocking by keyword cannot tell a study group from
 * a meme channel because both are just text. So this one works the other way
 * round — you name the chats that are allowed, and anything else gets backed
 * out of.
 *
 * ### Why this fails open, deliberately
 *
 * There is no public, stable way to read "which chat am I in" from outside
 * Telegram. This reads the title out of the toolbar by view id, and Telegram
 * are free to rename that id in any update. A guard that guessed wrong while
 * failing *closed* would back you out of every chat including the allowed ones,
 * and make the app useless with no obvious cause.
 *
 * So: it only ever acts on a title it positively read. No title, no action.
 * The cost of a missed update is that the guard quietly stops guarding, which
 * the user can see on the Keyword guard screen — not that Telegram becomes
 * unusable.
 */
object TelegramGuard {

    const val PACKAGE = "org.telegram.messenger"

    /** The other builds people actually run. */
    val packages: Set<String> = setOf(
        PACKAGE,
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "org.thunderdog.challegram"
    )

    private const val KEY_ALLOWED = "telegram_allowed_chats"
    private const val KEY_LAST_TITLE = "telegram_last_title"

    /**
     * Toolbar title ids seen across Telegram builds and forks.
     *
     * Matched as substrings against `viewIdResourceName`, so a fork that
     * prefixes its own package still lines up.
     */
    private val TITLE_ID_HINTS = listOf(
        "action_bar_title",
        "actionbartitle",
        "chat_title",
        "dialog_title",
        "toolbar_title"
    )

    fun isTelegram(packageName: String): Boolean = packageName in packages

    fun isEnabled(context: Context): Boolean =
        CapabilityRegistry.isEnabled(context, Capabilities.TELEGRAM_GUARD)

    /** Chat titles the user named as allowed, lowercased for comparison. */
    fun allowed(context: Context): List<String> =
        FocusStore.jsonArrayToStringList(FocusStore.getJsonArray(context, KEY_ALLOWED))
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }

    fun allowedRaw(context: Context): List<String> =
        FocusStore.jsonArrayToStringList(FocusStore.getJsonArray(context, KEY_ALLOWED))

    /** An allowed-chats list is an exemption set: shortening it tightens, adding to it waits. */
    fun setAllowed(context: Context, titles: List<String>): Boolean {
        val cleaned = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val direction = SessionLock.forExemptionSet(
            allowed(context).toSet(),
            cleaned.map { it.lowercase(Locale.US) }.toSet()
        )
        if (!SessionLock.allows(context, direction)) return false
        FocusStore.setJsonArray(context, KEY_ALLOWED, FocusStore.stringListToJsonArray(cleaned))
        PolicySync.request(context, "telegramGuard")
        return true
    }

    fun addAllowed(context: Context, title: String): Boolean =
        setAllowed(context, allowedRaw(context) + title)

    fun removeAllowed(context: Context, title: String): Boolean =
        setAllowed(context, allowedRaw(context).filterNot { it.equals(title, ignoreCase = true) })

    /**
     * The last chat title the guard managed to read.
     *
     * Shown on the settings screen so "add the chat you are in" is a tap
     * instead of a typing exercise, and so a user can tell at a glance whether
     * the guard can still see anything at all after a Telegram update.
     */
    fun lastSeenTitle(context: Context): String? =
        FocusStore.getString(context, KEY_LAST_TITLE, "").takeIf { it.isNotBlank() }

    private const val KEY_BACKED_OUT = "telegram_backed_out"

    /** The last chats the guard backed out of, newest first, so one can be allowed by its exact title. */
    fun backedOut(context: Context): List<String> =
        FocusStore.getString(context, KEY_BACKED_OUT, "").split('\n').filter { it.isNotBlank() }

    fun recordBackedOut(context: Context, title: String) {
        val list = (listOf(title) + backedOut(context).filterNot { it == title }).take(10)
        FocusStore.setString(context, KEY_BACKED_OUT, list.joinToString("\n"))
    }

    fun rememberTitle(context: Context, title: String) {
        FocusStore.setString(context, KEY_LAST_TITLE, title)
    }

    /**
     * Reads the chat title out of the visible tree, or null if it cannot.
     *
     * Null is a normal answer, not an error: the chat list has no chat title,
     * and neither does a settings screen.
     */
    fun readChatTitle(root: AccessibilityNodeInfo): String? {
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        // The chat header lives in the top strip of the screen. Nothing below
        // it can be the title, so those subtrees are not walked at all - which
        // is most of a chat screen, and most of what this used to cost.
        val headerBottom = rootBounds.top + (rootBounds.height() * HEADER_FRACTION).toInt()
        val minHeaderWidth = (rootBounds.width() * MIN_HEADER_WIDTH_FRACTION).toInt()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val bounds = Rect()

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1
            node.getBoundsInScreen(bounds)
            if (node !== root && bounds.top > headerBottom) continue

            val viewId = node.viewIdResourceName?.lowercase(Locale.US)
            if (viewId != null && TITLE_ID_HINTS.any { viewId.contains(it) }) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) return text
            }

            titleFromHeaderDescription(
                node.contentDescription,
                bounds.width(),
                bounds.bottom <= headerBottom + bounds.height() / 2,
                minHeaderWidth
            )?.let { title ->
                // Confirmed by the title's own text view underneath it. The
                // header is see-through and messages scroll behind it, and a
                // message's description can contain a line break too - but a
                // message bubble has no child views (Telegram draws its text
                // itself), while the header shows the name in a child.
                if (hasDescendantText(node, title, 2)) return title
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * The official Telegram app's chat header, read from its description.
     *
     * Found on a real phone: Telegram gives none of its views a resource id -
     * every one is blank - so the id hints above never matched anything, and
     * the guard never once read a chat title on the official app. What it does
     * give is the header's description, "Title\nsubtitle": "Lumosel
     * Remastred\n6.6K subscribers", "Name\nlast seen recently", "Group\n12
     * members". A wide element in the header strip whose description has that
     * shape is the chat header, and its first line is the chat's name. Pure,
     * so the rule is unit-tested.
     */
    fun titleFromHeaderDescription(
        description: CharSequence?,
        width: Int,
        inHeader: Boolean,
        minWidth: Int
    ): String? {
        if (!inHeader || width < minWidth) return null
        val text = description?.toString() ?: return null
        val newline = text.indexOf('\n')
        if (newline <= 0) return null
        val title = text.substring(0, newline).trim()
        return title.ifEmpty { null }
    }

    private fun hasDescendantText(node: AccessibilityNodeInfo, text: String, depth: Int): Boolean {
        if (depth <= 0) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.text?.toString()?.trim() == text) return true
            if (hasDescendantText(child, text, depth - 1)) return true
        }
        return false
    }

    /** The top share of the screen the chat header sits in. */
    private const val HEADER_FRACTION = 0.15

    /** A header spans most of the width; a message bubble's sender line does not start in the strip. */
    private const val MIN_HEADER_WIDTH_FRACTION = 0.4

    /**
     * Whether this chat should be backed out of.
     *
     * An empty allowlist means "not configured yet" and blocks nothing — the
     * alternative is a guard that locks the user out of all of Telegram the
     * instant they switch it on, before they have had any chance to say what
     * matters to them.
     */
    fun blocks(context: Context, title: String): Boolean {
        if (!isEnabled(context)) return false
        val allowed = allowed(context)
        if (allowed.isEmpty()) return false
        val needle = title.trim().lowercase(Locale.US)
        if (needle.isEmpty()) return false
        return allowed.none { needle == it || needle.contains(it) }
    }

    private const val MAX_NODES = 400
}
