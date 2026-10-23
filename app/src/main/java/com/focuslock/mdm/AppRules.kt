package com.focuslock.mdm

import androidx.annotation.StringRes

import android.content.Context
import org.json.JSONObject

/**
 * What FocusLock is allowed to do about one app.
 *
 * The ladder is deliberately gentle at the top: most people do better with a
 * pause than a wall, and a wall with no pause is what makes people uninstall
 * the blocker at 1am.
 */
enum class AppPolicy(val id: String, @StringRes val labelRes: Int, @StringRes val blurbRes: Int) {
    ALLOW("allow", R.string.policy_allow_label, R.string.policy_allow_blurb),
    FRICTION("friction", R.string.policy_friction_label, R.string.policy_friction_blurb),
    LIMIT("limit", R.string.policy_limit_label, R.string.policy_limit_blurb),
    BLOCK("block", R.string.policy_block_label, R.string.policy_block_blurb),
    HIDE("hide", R.string.policy_hide_label, R.string.policy_hide_blurb);

    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)

    val stopsLaunch: Boolean get() = this == BLOCK || this == HIDE

    /**
     * How strict this rung is, for [SessionLock]'s tighten/loosen question.
     *
     * Moving up the ladder (or staying put) is a tightening and goes through
     * mid-session; moving down is a loosening and waits for the session to end.
     */
    val strictness: Int
        get() = when (this) {
            ALLOW -> 0
            FRICTION -> 1
            LIMIT -> 2
            BLOCK -> 3
            HIDE -> 4
        }

    /**
     * A one-word form of [labelRes] for a pill/status chip, where "Give it a
     * budget" doesn't fit. The full phrase stays everywhere the ladder itself
     * is shown - this is additive, not a replacement.
     */
    @get:StringRes
    val shortLabelRes: Int
        get() = when (this) {
            ALLOW -> R.string.policy_allow_short
            FRICTION -> R.string.policy_friction_short
            LIMIT -> R.string.policy_limit_short
            BLOCK -> R.string.policy_block_short
            HIDE -> R.string.policy_hide_short
        }

    fun shortLabel(context: Context): String = context.getString(shortLabelRes)

    companion object {
        fun fromId(id: String?): AppPolicy =
            values().firstOrNull { it.id == id } ?: ALLOW

        /** The order the user sees when picking a policy for an app. */
        val ladder: List<AppPolicy> = listOf(ALLOW, FRICTION, LIMIT, BLOCK, HIDE)
    }
}

/**
 * The user-owned replacement for `Constants.WHITELIST` and `Constants.KILL_LIST`.
 *
 * There is exactly one source of truth for per-app policy now, and it is a file
 * the user writes. The old constants survive only as [Seed] data that is copied
 * in once, on first run, and never consulted again.
 */
object AppRules {

    private const val KEY_POLICIES = "app_policies_json"
    private const val KEY_ALWAYS_ALLOWED = "app_always_allowed"
    private const val KEY_CATEGORY_POLICIES = "category_policies_json"
    private const val KEY_KIOSK_ALLOWLIST_MODE = "kiosk_allowlist_mode"

    // ── Per-app policy ────────────────────────────────────────────

    /** The explicit policy the user set for this app, or null if they never said. */
    fun explicitPolicy(context: Context, packageName: String): AppPolicy? {
        val policies = FocusStore.getJsonObject(context, KEY_POLICIES)
        if (!policies.has(packageName)) return null
        return AppPolicy.fromId(policies.optString(packageName, ""))
    }

    /**
     * The policy actually in force: the app's own setting, else its category's
     * setting, else "open freely".
     */
    fun effectivePolicy(context: Context, packageName: String): AppPolicy {
        if (isAlwaysAllowed(context, packageName)) return AppPolicy.ALLOW
        explicitPolicy(context, packageName)?.let { return it }
        val category = AppCatalog.categoryOf(context, packageName)
        return categoryPolicy(context, category) ?: AppPolicy.ALLOW
    }

    /**
     * Which way moving [packageName] to [policy] moves the lock.
     *
     * Compared against the *effective* policy rather than the explicit one, so
     * writing an explicit BLOCK over a category that already blocked it counts
     * as a tightening (it is a no-op), and writing an explicit ALLOW over that
     * same category is correctly seen as the loosening it really is.
     */
    private fun directionFor(context: Context, packageName: String, policy: AppPolicy): EditDirection =
        if (policy.strictness >= effectivePolicy(context, packageName).strictness) {
            EditDirection.TIGHTEN
        } else {
            EditDirection.LOOSEN
        }

    fun setPolicy(context: Context, packageName: String, policy: AppPolicy): Boolean {
        if (!SessionLock.allows(context, directionFor(context, packageName, policy))) return false
        val policies = FocusStore.getJsonObject(context, KEY_POLICIES)
        policies.put(packageName, policy.id)
        FocusStore.setJsonObject(context, KEY_POLICIES, policies)
        PolicySync.request(context, "appPolicy:" + packageName)
        return true
    }

    /**
     * Dropping an app's own rule falls back to its category (or "open freely"),
     * so whether this is a tightening depends entirely on what it falls back
     * *to* - which is why it is computed rather than assumed either way.
     */
    fun clearPolicy(context: Context, packageName: String): Boolean {
        val fallback = AppCatalog.categoryOf(context, packageName)
            .let { categoryPolicy(context, it) } ?: AppPolicy.ALLOW
        if (!SessionLock.allows(context, directionFor(context, packageName, fallback))) return false
        val policies = FocusStore.getJsonObject(context, KEY_POLICIES)
        policies.remove(packageName)
        FocusStore.setJsonObject(context, KEY_POLICIES, policies)
        PolicySync.request(context, "appPolicy:" + packageName)
        return true
    }

    /** A bulk write is a loosening if it loosens *any* of its targets. */
    fun setPolicies(context: Context, packages: Collection<String>, policy: AppPolicy): Boolean {
        val direction = if (packages.any { directionFor(context, it, policy) == EditDirection.LOOSEN }) {
            EditDirection.LOOSEN
        } else {
            EditDirection.TIGHTEN
        }
        if (!SessionLock.allows(context, direction)) return false
        val policies = FocusStore.getJsonObject(context, KEY_POLICIES)
        packages.forEach { policies.put(it, policy.id) }
        FocusStore.setJsonObject(context, KEY_POLICIES, policies)
        PolicySync.request(context, "appPolicy:bulk")
        return true
    }

    /** Cached per policy revision: the enforcement loop asks for this constantly. */
    fun allPolicies(context: Context): Map<String, AppPolicy> = PolicyCache.get("appPolicies") {
        val policies = FocusStore.getJsonObject(context, KEY_POLICIES)
        val out = LinkedHashMap<String, AppPolicy>()
        val keys = policies.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = AppPolicy.fromId(policies.optString(key, ""))
        }
        out
    }

    fun packagesWithPolicy(context: Context, policy: AppPolicy): Set<String> =
        allPolicies(context).filterValues { it == policy }.keys

    /**
     * Everything the user has told FocusLock to stop, from any route.
     *
     * A category block must not override an explicit per-app policy on one
     * of its members - effectivePolicy() already gets this precedence right
     * (always-allowed, then explicit, then category), but this function had
     * its own, different logic that unioned in every member of a blocked
     * category regardless of an explicit ALLOW on one of them. That is
     * exactly the bug that left a real user's app permanently suspended at
     * the OS level after "Allow" reported success: the per-app write went
     * through fine, but this function - which is what the Device-Owner
     * suspension in KioskPolicy actually reads - never looked at it.
     */
    fun blockedPackages(context: Context): Set<String> = PolicyCache.get("blockedPackages") {
        val out = LinkedHashSet<String>()
        allPolicies(context).forEach { entry ->
            if (entry.value.stopsLaunch) out.add(entry.key)
        }
        categoryPolicies(context).forEach { entry ->
            if (entry.value.stopsLaunch) {
                AppCatalog.packagesInCategory(context, entry.key).forEach { pkg ->
                    val explicit = explicitPolicy(context, pkg)
                    if (explicit == null || explicit.stopsLaunch) out.add(pkg)
                }
            }
        }
        out.removeAll(alwaysAllowed(context))
        out.remove(context.packageName)
        out
    }

    fun hiddenPackages(context: Context): Set<String> {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.HIDE_BLOCKED_APPS)) return emptySet()
        val out = LinkedHashSet<String>()
        allPolicies(context).forEach { entry ->
            if (entry.value == AppPolicy.HIDE) out.add(entry.key)
        }
        categoryPolicies(context).forEach { entry ->
            if (entry.value == AppPolicy.HIDE) {
                // Same precedence fix as blockedPackages(): an explicit
                // per-app policy - even just ALLOW, not necessarily HIDE -
                // overrides the category's, so it must stop a member being
                // added here rather than being unioned in unconditionally.
                AppCatalog.packagesInCategory(context, entry.key).forEach { pkg ->
                    if (explicitPolicy(context, pkg) == null) out.add(pkg)
                }
            }
        }
        out.removeAll(alwaysAllowed(context))
        out.remove(context.packageName)
        return out
    }

    // ── Category policy ───────────────────────────────────────────

    fun categoryPolicy(context: Context, category: AppCategory): AppPolicy? {
        val policies = FocusStore.getJsonObject(context, KEY_CATEGORY_POLICIES)
        if (!policies.has(category.id)) return null
        return AppPolicy.fromId(policies.optString(category.id, ""))
    }

    fun categoryPolicies(context: Context): Map<AppCategory, AppPolicy> {
        val policies = FocusStore.getJsonObject(context, KEY_CATEGORY_POLICIES)
        val out = LinkedHashMap<AppCategory, AppPolicy>()
        val keys = policies.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[AppCategory.fromId(key)] = AppPolicy.fromId(policies.optString(key, ""))
        }
        return out
    }

    fun setCategoryPolicy(context: Context, category: AppCategory, policy: AppPolicy?): Boolean {
        // Clearing a category rule (null) drops back to "open freely", which is
        // the loosest there is - so only an equal-or-stricter named policy can
        // go through mid-session.
        val before = categoryPolicy(context, category)?.strictness ?: AppPolicy.ALLOW.strictness
        val after = policy?.strictness ?: AppPolicy.ALLOW.strictness
        val direction = if (after >= before) EditDirection.TIGHTEN else EditDirection.LOOSEN
        if (!SessionLock.allows(context, direction)) return false
        val policies = FocusStore.getJsonObject(context, KEY_CATEGORY_POLICIES)
        if (policy == null) policies.remove(category.id) else policies.put(category.id, policy.id)
        FocusStore.setJsonObject(context, KEY_CATEGORY_POLICIES, policies)
        PolicySync.request(context, "categoryPolicy:" + category.id)
        return true
    }

    // ── Always allowed ────────────────────────────────────────────
    //
    // This list outranks every other rule, including an active schedule window
    // and a running kiosk session. It exists because a blocker that cuts off a
    // phone call is a blocker that gets uninstalled.

    fun alwaysAllowed(context: Context): Set<String> = PolicyCache.get("alwaysAllowed") {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.ALWAYS_ALLOWED)) {
            setOf(context.packageName)
        } else {
            FocusStore.getSet(context, KEY_ALWAYS_ALLOWED) + context.packageName
        }
    }

    /**
     * This is the purest exemption list in the app, so it gets the purest form
     * of the rule: taking an app *off* it is a tightening and goes through
     * mid-session; putting one *on* it is the classic 2am escape hatch and
     * waits for the session to end.
     */
    fun setAlwaysAllowed(context: Context, packages: Collection<String>): Boolean {
        val cleaned = packages
            .map { it.trim() }
            .filter { it.isNotBlank() && it != context.packageName }
            .toSet()
        val direction = SessionLock.forExemptionSet(alwaysAllowedRaw(context), cleaned)
        if (!SessionLock.allows(context, direction)) return false
        FocusStore.setSet(context, KEY_ALWAYS_ALLOWED, cleaned)
        PolicySync.request(context, "alwaysAllowed")
        return true
    }

    /**
     * The stored list exactly as written, without the capability gate or the
     * implicit self-entry. Editors need this so a switch-off does not look like
     * the user's list was emptied.
     */
    fun alwaysAllowedRaw(context: Context): Set<String> = FocusStore.getSet(context, KEY_ALWAYS_ALLOWED)

    fun isAlwaysAllowed(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (!CapabilityRegistry.isEnabled(context, Capabilities.ALWAYS_ALLOWED)) return false
        return packageName in FocusStore.getSet(context, KEY_ALWAYS_ALLOWED)
    }

    fun addAlwaysAllowed(context: Context, packageName: String): Boolean {
        val current = FocusStore.getSet(context, KEY_ALWAYS_ALLOWED).toMutableSet()
        current.add(packageName)
        return setAlwaysAllowed(context, current)
    }

    // ── Kiosk allowlist ───────────────────────────────────────────
    //
    // Kiosk is the one mode that inverts the model: instead of "block what you
    // named", it is "allow only what you named". That inversion is itself a
    // user setting, because some people want kiosk-strength enforcement with a
    // blocklist rather than a whitelist.

    fun isKioskAllowlistMode(context: Context): Boolean =
        FocusStore.getBool(context, KEY_KIOSK_ALLOWLIST_MODE, true)

    /** Switching the inversion off mid-session would widen Kiosk to a blocklist, so only switching it on is a tightening. */
    fun setKioskAllowlistMode(context: Context, value: Boolean): Boolean {
        val direction = if (value) EditDirection.TIGHTEN else EditDirection.LOOSEN
        if (!SessionLock.allows(context, direction)) return false
        FocusStore.setBool(context, KEY_KIOSK_ALLOWLIST_MODE, value)
        PolicySync.request(context, "kioskAllowlistMode")
        return true
    }

    /** Apps that stay open during a kiosk session. */
    fun kioskAllowlist(context: Context): Set<String> {
        val out = LinkedHashSet<String>()
        out.add(context.packageName)
        out.addAll(alwaysAllowed(context))
        allPolicies(context).forEach { entry ->
            if (!entry.value.stopsLaunch) out.add(entry.key)
        }
        return out
    }

    // ── Export / import ───────────────────────────────────────────

    fun exportJson(context: Context): JSONObject {
        val out = JSONObject()
        out.put("apps", FocusStore.getJsonObject(context, KEY_POLICIES))
        out.put("categories", FocusStore.getJsonObject(context, KEY_CATEGORY_POLICIES))
        out.put("alwaysAllowed", FocusStore.stringListToJsonArray(FocusStore.getSet(context, KEY_ALWAYS_ALLOWED)))
        out.put("kioskAllowlistMode", isKioskAllowlistMode(context))
        return out
    }

    fun importJson(context: Context, json: JSONObject) {
        json.optJSONObject("apps")?.let { FocusStore.setJsonObject(context, KEY_POLICIES, it) }
        json.optJSONObject("categories")?.let { FocusStore.setJsonObject(context, KEY_CATEGORY_POLICIES, it) }
        json.optJSONArray("alwaysAllowed")?.let {
            FocusStore.setSet(context, KEY_ALWAYS_ALLOWED, FocusStore.jsonArrayToStringList(it))
        }
        if (json.has("kioskAllowlistMode")) {
            FocusStore.setBool(context, KEY_KIOSK_ALLOWLIST_MODE, json.optBoolean("kioskAllowlistMode", true))
        }
        PolicySync.request(context, "appRules:import")
    }
}
