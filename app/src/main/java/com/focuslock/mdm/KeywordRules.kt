package com.focuslock.mdm

import androidx.annotation.StringRes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** What the content guard does when it recognises a screen. */
enum class GuardAction(val id: String, @StringRes val labelRes: Int, @StringRes val blurbRes: Int) {
    STEP_BACK("stepBack", R.string.guard_step_back_label, R.string.guard_step_back_blurb),
    CLOSE_APP("closeApp", R.string.guard_close_app_label, R.string.guard_close_app_blurb),
    NUDGE("nudge", R.string.guard_nudge_label, R.string.guard_nudge_blurb);

    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)

    companion object {
        fun fromId(id: String?): GuardAction = values().firstOrNull { it.id == id } ?: STEP_BACK
    }
}

/**
 * One phrase the guard watches for.
 *
 * [group] links a rule to the capability that owns it. Rules with a group are
 * the built-in guards (WhatsApp, Shorts, Reels, adult filter) and switch on and
 * off with their capability. Rules without one belong to the user and are
 * governed by the keyword capability.
 */
data class KeywordRule(
    val id: String,
    val phrase: String,
    val packages: Set<String>,
    val action: GuardAction,
    val enabled: Boolean,
    val group: String?
) {
    val appliesEverywhere: Boolean get() = packages.isEmpty()
}

object KeywordRules {

    const val GROUP_WHATSAPP = "whatsapp"
    const val GROUP_SHORTS = "shorts"
    const val GROUP_REELS = "reels"
    const val GROUP_ADULT = "adult"

    private const val KEY_RULES = "keyword_rules_json"
    private const val KEY_EXCEPTIONS = "keyword_exceptions_json"

    // ── Read ──────────────────────────────────────────────────────

    fun all(context: Context): List<KeywordRule> {
        val array = FocusStore.getJsonArray(context, KEY_RULES)
        val out = ArrayList<KeywordRule>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val phrase = obj.optString("phrase", "").trim()
            if (phrase.isEmpty()) continue
            val packagesArray = obj.optJSONArray("packages") ?: JSONArray()
            out.add(
                KeywordRule(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    phrase = phrase,
                    packages = FocusStore.jsonArrayToStringList(packagesArray).toSet(),
                    action = GuardAction.fromId(obj.optString("action", "")),
                    enabled = obj.optBoolean("enabled", true),
                    group = obj.optString("group", "").takeIf { it.isNotBlank() }
                )
            )
        }
        return out
    }

    /** The rules the user wrote themselves. */
    fun userRules(context: Context): List<KeywordRule> = all(context).filter { it.group == null }

    fun rulesInGroup(context: Context, group: String): List<KeywordRule> =
        all(context).filter { it.group == group }

    /**
     * The rules in force right now, with every capability gate already applied.
     * The guard service calls only this.
     */
    fun activeRules(context: Context): List<KeywordRule> {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.CONTENT_GUARD)) return emptyList()

        val keywordsOn = CapabilityRegistry.isEnabled(context, Capabilities.KEYWORD_BLOCK)
        val whatsappOn = CapabilityRegistry.isEnabled(context, Capabilities.WHATSAPP_GUARD)
        val shortsOn = CapabilityRegistry.isEnabled(context, Capabilities.SHORTS_BLOCK)
        val reelsOn = CapabilityRegistry.isEnabled(context, Capabilities.REELS_BLOCK)
        val adultOn = CapabilityRegistry.isEnabled(context, Capabilities.ADULT_BLOCK)

        return all(context).filter { rule ->
            if (!rule.enabled) return@filter false
            when (rule.group) {
                null -> keywordsOn
                GROUP_WHATSAPP -> whatsappOn
                GROUP_SHORTS -> shortsOn
                GROUP_REELS -> reelsOn
                GROUP_ADULT -> adultOn
                else -> keywordsOn
            }
        }
    }

    /**
     * Whether a node's (normalised) text is covered by one exception phrase.
     *
     * Either the text contains the whole exception, or the text is the start
     * of it - a shortened copy of the same control. Found on a real phone: in
     * WhatsApp's home screen the search bar is two nodes, "Ask Meta AI or
     * Search" and a shorter "Ask Meta AI". The exception named the first; the
     * second matched the blocked phrase "meta ai", and the guard pressed Back
     * on WhatsApp's home screen every time it opened - WhatsApp could not be
     * used at all during a session.
     *
     * The prefix case needs [MIN_PREFIX] characters, so a bare "Meta AI" - the
     * title of the chat the rule actually exists to keep closed - is never
     * mistaken for a piece of the search bar. Pure, and unit-tested.
     */
    fun coveredByException(text: String, exception: String): Boolean {
        if (exception.isEmpty()) return false
        if (text.contains(exception)) return true
        return text.length >= MIN_PREFIX && exception.startsWith(text)
    }

    private const val MIN_PREFIX = 8

    /** Phrases that mean "this looks like a hit but is not" — kept out of the way. */
    fun exceptions(context: Context): List<String> =
        FocusStore.jsonArrayToStringList(FocusStore.getJsonArray(context, KEY_EXCEPTIONS))
            .map { it.lowercase(Locale.US) }

    /**
     * An exception is a hole in the guard, so the direction is inverted from
     * the rules themselves: *removing* one tightens and goes through
     * mid-session, *adding* one is a loosening and waits.
     */
    fun setExceptions(context: Context, phrases: Collection<String>): Boolean {
        val cleaned = phrases.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val direction = SessionLock.forExemptionSet(
            exceptions(context).toSet(),
            cleaned.map { it.lowercase(Locale.US) }.toSet()
        )
        if (!SessionLock.allows(context, direction)) return false
        FocusStore.setJsonArray(context, KEY_EXCEPTIONS, FocusStore.stringListToJsonArray(cleaned))
        PolicySync.request(context, "keywordExceptions")
        return true
    }

    // ── Write ─────────────────────────────────────────────────────

    /**
     * Adding a phrase to watch for goes through at any time. Removing one, or
     * switching one off, is exactly the moment the freeze exists to prevent -
     * that waits for the session to end.
     */
    fun save(context: Context, rules: List<KeywordRule>): Boolean {
        if (!SessionLock.allows(context, directionOf(all(context), rules))) return false
        val array = JSONArray()
        rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("phrase", rule.phrase)
            obj.put("packages", FocusStore.stringListToJsonArray(rule.packages))
            obj.put("action", rule.action.id)
            obj.put("enabled", rule.enabled)
            if (rule.group != null) obj.put("group", rule.group)
            array.put(obj)
        }
        FocusStore.setJsonArray(context, KEY_RULES, array)
        PolicySync.request(context, "keywordRules")
        return true
    }

    /**
     * A keyword list only got stricter if every phrase that was being watched
     * before is still being watched now. A phrase that was deleted, switched
     * off, or narrowed from "everywhere" to a named app list all count as
     * loosening it.
     */
    private fun directionOf(before: List<KeywordRule>, after: List<KeywordRule>): EditDirection {
        val afterById = after.associateBy { it.id }
        val stillCovered = before.filter { it.enabled }.all { previous ->
            val now = afterById[previous.id] ?: return@all false
            now.enabled &&
                now.phrase.equals(previous.phrase, ignoreCase = true) &&
                (now.appliesEverywhere || now.packages.containsAll(previous.packages))
        }
        return if (stillCovered) EditDirection.TIGHTEN else EditDirection.LOOSEN
    }

    fun add(context: Context, rule: KeywordRule): Boolean = save(context, all(context) + rule)

    fun update(context: Context, rule: KeywordRule): Boolean =
        save(context, all(context).map { if (it.id == rule.id) rule else it })

    fun remove(context: Context, id: String): Boolean =
        save(context, all(context).filterNot { it.id == id })

    fun setGroupEnabled(context: Context, group: String, enabled: Boolean): Boolean =
        save(context, all(context).map { if (it.group == group) it.copy(enabled = enabled) else it })

    fun newRule(
        phrase: String,
        packages: Collection<String> = emptySet(),
        action: GuardAction = GuardAction.STEP_BACK,
        group: String? = null
    ): KeywordRule = KeywordRule(
        id = UUID.randomUUID().toString(),
        phrase = phrase.trim(),
        packages = packages.map { it.trim() }.filter { it.isNotBlank() }.toSet(),
        action = action,
        enabled = true,
        group = group
    )

    // ── Seeding ───────────────────────────────────────────────────

    /**
     * Brings an existing install's WhatsApp guard up to date with the seed
     * list, adding what is missing and touching nothing already there.
     *
     * Seeding runs once, so a phrase added to [Seed] later never reached anyone
     * who had already set up - and WhatsApp rewording its channel directory is
     * exactly that case. New rules follow the group's current switch: if the
     * person turned the WhatsApp guard off, what is added stays off too.
     * Adding a rule only ever tightens, so this is allowed mid-session.
     */
    fun addMissingWhatsAppSeeds(context: Context) {
        if (!FocusStore.has(context, KEY_RULES)) return
        val current = all(context)
        val existing = current.map { it.phrase.trim().lowercase(Locale.US) }.toSet()
        val groupRules = current.filter { it.group == GROUP_WHATSAPP }
        val enabled = groupRules.isEmpty() || groupRules.any { it.enabled }
        val added = Seed.whatsappBlockedPhrases
            .filter { it.lowercase(Locale.US) !in existing }
            .map {
                newRule(it, Seed.whatsappPackages, GuardAction.STEP_BACK, GROUP_WHATSAPP).copy(enabled = enabled)
            }
        if (added.isNotEmpty()) save(context, current + added)

        // Written directly rather than through setExceptions: an exception
        // counts as loosening and would be refused if this happens to run
        // mid-session - and a migration runs once. This one is a fix (the
        // guard was closing WhatsApp's home screen), not someone relaxing a
        // rule.
        val exceptions = exceptions(context)
        val missing = Seed.whatsappAllowedPhrases.filter { it.lowercase(Locale.US) !in exceptions }
        if (missing.isNotEmpty()) {
            FocusStore.setJsonArray(context, KEY_EXCEPTIONS, FocusStore.stringListToJsonArray(exceptions + missing))
            PolicySync.request(context, "keywordExceptions:seed")
        }
    }

    /**
     * Turns the old hardcoded guard lists into ordinary, visible, editable rules.
     * Runs once; after that the user's copy is the only copy.
     */
    fun seedIfEmpty(context: Context) {
        if (FocusStore.has(context, KEY_RULES)) return

        val seeded = ArrayList<KeywordRule>()

        Seed.whatsappBlockedPhrases.forEach { phrase ->
            seeded.add(
                newRule(
                    phrase = phrase,
                    packages = Seed.whatsappPackages,
                    action = GuardAction.STEP_BACK,
                    group = GROUP_WHATSAPP
                )
            )
        }

        Seed.shortsPhrases.forEach { phrase ->
            seeded.add(
                newRule(
                    phrase = phrase,
                    packages = Seed.shortsPackages,
                    action = GuardAction.STEP_BACK,
                    group = GROUP_SHORTS
                )
            )
        }

        Seed.reelsPhrases.forEach { phrase ->
            seeded.add(
                newRule(
                    phrase = phrase,
                    packages = Seed.reelsPackages,
                    action = GuardAction.STEP_BACK,
                    group = GROUP_REELS
                )
            )
        }

        Seed.adultKeywords.forEach { phrase ->
            seeded.add(
                newRule(
                    phrase = phrase,
                    packages = emptySet(),
                    action = GuardAction.CLOSE_APP,
                    group = GROUP_ADULT
                )
            )
        }

        save(context, seeded)
        setExceptions(context, Seed.whatsappAllowedPhrases)
    }

    // ── Export / import ───────────────────────────────────────────

    fun exportJson(context: Context): JSONObject {
        val out = JSONObject()
        out.put("rules", FocusStore.getJsonArray(context, KEY_RULES))
        out.put("exceptions", FocusStore.getJsonArray(context, KEY_EXCEPTIONS))
        return out
    }

    fun importJson(context: Context, json: JSONObject): Boolean {
        if (SessionLock.isFrozen(context)) return false
        json.optJSONArray("rules")?.let { FocusStore.setJsonArray(context, KEY_RULES, it) }
        json.optJSONArray("exceptions")?.let { FocusStore.setJsonArray(context, KEY_EXCEPTIONS, it) }
        PolicySync.request(context, "keywordRules:import")
        return true
    }
}
