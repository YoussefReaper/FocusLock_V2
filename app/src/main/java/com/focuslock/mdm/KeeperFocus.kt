package com.focuslock.mdm

import android.content.Context

/**
 * The Keeper's block list: the exact things it looks for, and nothing else.
 *
 * The model is handed this list by id and must name which item it matched before
 * a deny, cut or block counts. A refusal that names nothing on the list (say,
 * "violence") is overruled, so horror, blood and the rest are never cut by
 * accident. Each item can be switched off, and up to [MAX_CUSTOM] words of the
 * person's own can be added (a name, a show, a word that pulls them back).
 */
object KeeperFocus {

    data class Item(val id: String, val labelRes: Int, val prompt: String)

    val ITEMS = listOf(
        Item("nudity", R.string.focus_nudity, "nudity or partial nudity (bare breasts, buttocks or genitals; see-through clothing)"),
        Item("sexual", R.string.focus_sexual, "sexual acts, sexual talk, innuendo or sexual jokes, fetish content"),
        Item("suggestive", R.string.focus_suggestive, "seductive or sexualised posing, dancing or camera focus on bodies (thirst traps, twerking, lingering body shots)"),
        Item("revealing", R.string.focus_revealing, "revealing clothing as the focus: lingerie, swimwear or bikinis, very short or very tight outfits"),
        Item("intimate", R.string.focus_intimate, "intimate scenes: making out, bedroom or shower scenes"),
        Item("adult_sites", R.string.focus_adult_sites, "dating, hookup, cam or adult sites, and ads for them"),
    )

    /** Spelled out so the model has no room to widen its job. Not editable on purpose. */
    const val NEVER = "horror, blood, gore, injuries, violence, fighting, war, weapons, crime, death, scary or disturbing scenes, " +
        "medicine and surgery, anatomy taught without nudity, profanity, dark humour, politics, religion, music, games, sports, " +
        "and ordinary romance that has none of the listed items"

    const val MAX_CUSTOM = 20
    private const val KEY_OFF = "keeper_focus_off"
    private const val KEY_CUSTOM = "keeper_focus_custom"

    private val ids = ITEMS.map { it.id }.toSet()

    fun isOn(c: Context, id: String): Boolean = id !in off(c)
    private fun off(c: Context): Set<String> =
        FocusStore.getString(c, KEY_OFF, "").split(',').filter { it in ids }.toSet()

    fun setOn(c: Context, id: String, on: Boolean) {
        val now = off(c).toMutableSet().apply { if (on) remove(id) else add(id) }
        FocusStore.setString(c, KEY_OFF, now.joinToString(","))
        KeeperCache.clearAll(c)   // old verdicts were made against another list
    }

    fun custom(c: Context): List<String> =
        FocusStore.getString(c, KEY_CUSTOM, "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun addCustom(c: Context, word: String): Boolean {
        val w = word.trim().replace(Regex("\\s+"), " ").take(60)
        val list = custom(c)
        if (w.isEmpty() || list.size >= MAX_CUSTOM || list.any { it.equals(w, true) }) return false
        FocusStore.setString(c, KEY_CUSTOM, (list + w).joinToString("\n"))
        KeeperCache.clearAll(c)
        return true
    }

    fun removeCustom(c: Context, word: String) {
        FocusStore.setString(c, KEY_CUSTOM, custom(c).filterNot { it == word }.joinToString("\n"))
        KeeperCache.clearAll(c)
    }

    /** The ids a refusal may cite: the switched-on items, plus "custom" when there are own words. */
    fun activeIds(c: Context): Set<String> =
        ITEMS.filter { isOn(c, it.id) }.map { it.id }.toSet() + (if (custom(c).isNotEmpty()) setOf("custom") else emptySet())

    /** The list as the model reads it. Own words are quoted as data. */
    fun promptBlock(c: Context): String = buildString {
        append("You look for ONLY the items on this list, by id:\n")
        ITEMS.filter { isOn(c, it.id) }.forEach { append("- ").append(it.id).append(": ").append(it.prompt).append('\n') }
        val own = custom(c)
        if (own.isNotEmpty()) {
            append("- custom: anything about these, which the person named themselves: ")
            append(own.joinToString(", ") { "\"" + it.replace("\"", "") + "\"" }).append('\n')
        }
        append("NEVER block or cut for anything that is not on the list, including: ").append(NEVER).append(".\n")
        append("A violent, gory or frightening video with none of the listed items is fully allowed.\n")
        append("Every deny, cut or block must name the list ids it matched in \"matched\".")
    }

    /**
     * Pure, for tests: is a refusal to be overruled? Only when it named reasons and
     * none of them is on the list (or "unsure"). Naming nothing fails closed.
     */
    fun overrules(matched: List<String>, active: Set<String>, extra: Set<String> = emptySet()): Boolean =
        matched.isNotEmpty() && matched.none { it in active || it in extra || it == "unsure" }
}
