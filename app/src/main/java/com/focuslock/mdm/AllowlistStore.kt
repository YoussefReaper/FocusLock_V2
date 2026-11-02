package com.focuslock.mdm

import android.content.Context
import android.net.Uri

/**
 * The web allowlist.
 *
 * The app-facing half of this file is gone. It was a compatibility layer over
 * [AppRules] left behind when app policy moved there, documented as keeping
 * "older call sites (the ADB receiver, the safe browser's deep-link check)"
 * working - but both of those read [AppRules] directly, and the layer's own
 * forwarding methods had no callers left. A shim that nothing calls is worse
 * than no shim: it reads as the live path, so the next person edits it and
 * nothing happens.
 *
 * What is genuinely still here is the site list, which [AppRules] does not own.
 */
object AllowlistStore {

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)

    // ── Web ───────────────────────────────────────────────────────

    fun getWebAllowlistUrls(context: Context): Set<String> {
        val p = prefs(context)
        val stored = p.getStringSet(Constants.KEY_WEB_ALLOWLIST, null)
        if (stored != null) return stored.toSet()

        val seeded = Constants.WEB_LINKS.map { it.url }.toMutableSet()
        p.edit().putStringSet(Constants.KEY_WEB_ALLOWLIST, seeded).apply()
        return seeded
    }

    /**
     * This list's rule - "removing is fine, adding waits" - is the one the rest
     * of the app was eventually rebuilt around, so it now says so in the same
     * vocabulary as every other store rather than being enforced by hand in one
     * editor screen. See [EditDirection].
     *
     * Stating it here rather than in the editor also covers the routes that
     * never went through the editor at all: the bulk paste field, the ADB
     * receiver, and a restored profile.
     */
    fun setWebAllowlistUrls(context: Context, urls: Collection<String>): Boolean {
        val cleaned = urls.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val direction = SessionLock.forExemptionSet(getWebAllowlistUrls(context), cleaned)
        if (!SessionLock.allows(context, direction)) return false
        prefs(context).edit().putStringSet(Constants.KEY_WEB_ALLOWLIST, cleaned).apply()
        PolicySync.request(context, "webAllowlist")
        return true
    }

    fun addWebUrl(context: Context, url: String): Boolean {
        val cleaned = normalizeUrl(url)
        if (cleaned.isBlank()) return false
        return setWebAllowlistUrls(context, getWebAllowlistUrls(context) + cleaned)
    }

    fun removeWebUrl(context: Context, url: String): Boolean =
        setWebAllowlistUrls(context, getWebAllowlistUrls(context) - normalizeUrl(url))

    fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://" + trimmed
        }
    }

    fun isValidUrl(value: String): Boolean = try {
        val uri = Uri.parse(value)
        !uri.host.isNullOrBlank() && (uri.scheme == "http" || uri.scheme == "https")
    } catch (_: Exception) {
        false
    }

    fun isWebTextOnlyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_WEB_TEXT_ONLY, false)

    fun setWebTextOnlyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_WEB_TEXT_ONLY, enabled).apply()
    }

    fun getWebLinks(context: Context): List<Constants.WebLink> {
        val allowed = getWebAllowlistUrls(context)
        val byUrl = Constants.WEB_LINKS.associateBy { it.url }
        return allowed
            .map { url -> byUrl[url] ?: Constants.WebLink(titleFromUrl(url), url, "Custom") }
            .sortedWith(compareBy({ it.category }, { it.title }))
    }

    fun getWebCategories(context: Context): List<String> =
        getWebLinks(context).map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    fun getWebAllowlistDomains(context: Context): Array<String> {
        val hosts = getWebAllowlistUrls(context)
            .mapNotNull { url ->
                try {
                    Uri.parse(url).host?.lowercase()?.removePrefix("www.")
                } catch (_: Exception) {
                    null
                }
            }
            .filter { it.isNotBlank() }
            .distinct()

        val patterns = LinkedHashSet<String>()
        (hosts + SystemSurfaces.authDomains).forEach { host ->
            patterns.add(host)
            patterns.add("*." + host)
        }
        return patterns.toTypedArray()
    }

    /**
     * The one answer to "is this address on the user's list?", shared by the safe
     * browser and the Keeper so they can never disagree. The adult filter wins over
     * everything, including the list.
     */
    fun isListedUrl(context: Context, target: String): Boolean = try {
        val uri = Uri.parse(target)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.")
        when {
            scheme != "https" && scheme != "http" -> false
            host.isNullOrBlank() -> false
            isBlockedHost(context, host) -> false
            SystemSurfaces.authDomains.any { hostMatches(host, it) } -> true
            else -> getWebAllowlistUrls(context).any { allowed ->
                val allowedHost = try { Uri.parse(allowed).host?.lowercase()?.removePrefix("www.").orEmpty() } catch (_: Exception) { "" }
                allowedHost.isNotBlank() && hostMatches(host, allowedHost)
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun hostMatches(targetHost: String, allowedHost: String): Boolean =
        targetHost == allowedHost || targetHost.endsWith(".$allowedHost")

    /** First http(s) address in shared text ("Exam link: https://...") or null. */
    fun firstUrlIn(text: String?): String? =
        text?.let { Regex("https?://[^\\s<>\"']+").find(it)?.value?.trimEnd('.', ',', ')', ']') }

    /** Hosts the adult filter refuses even if they somehow reach the allowlist. */
    fun isBlockedHost(context: Context, host: String): Boolean {
        if (!CapabilityRegistry.isEnabled(context, Capabilities.ADULT_BLOCK)) return false
        val normalized = host.lowercase().removePrefix("www.")
        return Seed.adultDomains.any { normalized == it || normalized.endsWith("." + it) }
    }

    private fun titleFromUrl(url: String): String = try {
        Uri.parse(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }
}
