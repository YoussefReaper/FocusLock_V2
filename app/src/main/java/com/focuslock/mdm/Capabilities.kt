package com.focuslock.mdm

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONObject

/**
 * Stable ids for the detail screens a capability can open.
 * Kept as plain strings so the registry never has to know about Activity classes.
 */
object Screens {
    const val APP_RULES = "appRules"
    const val WEB_RULES = "webRules"
    const val KEYWORDS = "keywords"
    const val LIMITS = "limits"
    const val SCHEDULES = "schedules"
    const val BEDTIME = "bedtime"
    const val PLACES = "places"
    const val RULES_LIST = "rulesList"
    const val ALWAYS_ALLOWED = "alwaysAllowed"
    const val ANALYTICS = "analytics"
    const val TASKS = "tasks"
    const val EARN = "earn"
}

/**
 * Where a capability lives in the Rules -> Capabilities screen.
 * Order here is the order the user sees.
 */
enum class CapabilityGroup(@StringRes val labelRes: Int, @StringRes val blurbRes: Int) {
    MODES(R.string.capgroup_modes_label, R.string.capgroup_modes_blurb),
    BLOCKING(R.string.capgroup_blocking_label, R.string.capgroup_blocking_blurb),
    CONTENT(R.string.capgroup_content_label, R.string.capgroup_content_blurb),
    ENVIRONMENT(R.string.capgroup_environment_label, R.string.capgroup_environment_blurb),
    TOOLS(R.string.capgroup_tools_label, R.string.capgroup_tools_blurb),
    INSIGHT(R.string.capgroup_insight_label, R.string.capgroup_insight_blurb),
    HARDENING(R.string.capgroup_hardening_label, R.string.capgroup_hardening_blurb),
    ADVANCED(R.string.capgroup_advanced_label, R.string.capgroup_advanced_blurb),
    EXPERIMENTAL(R.string.capgroup_experimental_label, R.string.capgroup_experimental_blurb);

    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)
}

/**
 * Which way switching a capability on moves the lock, for [SessionLock]'s
 * tighten/loosen question.
 */
enum class CapabilityPolarity {
    GUARD,
    EXEMPTION,
    NEUTRAL
}

/**
 * One user-owned switch.
 */
data class CapabilitySpec(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val blurbRes: Int,
    val group: CapabilityGroup,
    val default: Boolean,
    val polarity: CapabilityPolarity = CapabilityPolarity.GUARD,
    @StringRes val weakenNoteRes: Int? = null,
    val needsAccessibility: Boolean = false,
    val needsDeviceOwner: Boolean = false,
    val needsUsageAccess: Boolean = false,
    val needsNotificationAccess: Boolean = false,
    val needsLocation: Boolean = false,
    val detailScreen: String? = null
) {
    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)
    fun weakenNote(context: Context): String? = weakenNoteRes?.let { context.getString(it) }
}

/**
 * The Capability Registry.
 *
 * Every behaviour in FocusLock is gated on one of these ids.
 * Display preferences (Kind wording, Gentle streaks, Offer an alternative)
 * live under Appearance (`PersonalizationActivity`).
 * Fragile accessibility screen-readers (WhatsApp, Shorts, Reels, Telegram)
 * live under Experimental and start OFF by default.
 */
object Capabilities {

    // Modes
    const val ADVISORY_MODE = "advisoryMode"
    const val SANCTUARY_MODE = "sanctuaryMode"
    const val KIOSK_MODE = "kioskMode"

    // Session behaviour flags
    const val CAN_END_EARLY = "canEndEarly"
    const val HARD_BLOCK = "hardBlock"
    const val LOCK_RULES_IN_SESSION = "lockRulesInSession"

    // Blocking
    const val APP_BLOCK = "appBlock"
    const val WEB_BLOCK = "webBlock"
    const val RULE_ENGINE = "ruleEngine"
    const val ALWAYS_ALLOWED = "alwaysAllowed"
    const val LAUNCH_FRICTION = "launchFriction"
    const val TAKE_A_BREAK = "takeABreak"
    const val PER_APP_LIMITS = "perAppLimits"
    const val OPEN_COUNT_LIMITS = "openCountLimits"

    // Content
    const val CONTENT_GUARD = "contentGuard"
    const val KEYWORD_BLOCK = "keywordBlock"
    const val WHATSAPP_GUARD = "whatsappGuard"
    const val TELEGRAM_GUARD = "telegramGuard"
    const val SHORTS_BLOCK = "shortsBlock"
    const val REELS_BLOCK = "reelsBlock"
    const val ADULT_BLOCK = "adultBlock"

    // Environment
    const val SCHEDULES = "schedules"
    const val BEDTIME_MODE = "bedtimeMode"
    const val LOCATION_BLOCK = "locationBlock"
    const val WIFI_CONDITIONS = "wifiConditions"
    const val NOTIFICATION_BLOCK = "notificationBlock"

    // Replacements & Display options
    const val SAFE_BROWSER = "safeBrowser"
    const val TEXT_SEARCH = "textSearch"
    const val SAFE_YOUTUBE = "safeYouTube"
    const val REPLACEMENT_SUGGESTIONS = "replacementSuggestions"

    // Earn
    const val EARN_MODE = "earnMode"

    // Insight & Display options
    const val ANALYTICS = "analytics"
    const val STREAKS = "streaks"
    const val SELF_COMPASSION_COPY = "selfCompassionCopy"

    // Hardening
    const val SUSPEND_BLOCKED_APPS = "suspendBlockedApps"
    const val HIDE_BLOCKED_APPS = "hideBlockedApps"
    const val STATUS_BAR_LOCK = "statusBarLock"
    const val KEYGUARD_LOCK = "keyguardLock"
    const val UNINSTALL_PROTECTION = "uninstallProtection"
    const val SAFE_BOOT_BLOCK = "safeBootBlock"
    const val PERSISTENT_HOME = "persistentHome"

    // Outside a session: one master switch for every blocker that can act on its own (see [Standalone])
    const val OUTSIDE_SESSION = "outsideSession"

    /**
     * The 10 switches most people use, shown first in CapabilitiesActivity
     * before "Show all" is tapped.
     */
    val coreIds: List<String> = listOf(
        OUTSIDE_SESSION,
        APP_BLOCK,
        SCHEDULES,
        PER_APP_LIMITS,
        LAUNCH_FRICTION,
        TAKE_A_BREAK,
        BEDTIME_MODE,
        WEB_BLOCK,
        KEYWORD_BLOCK,
        NOTIFICATION_BLOCK,
        EARN_MODE
    )

    val all: List<CapabilitySpec> = listOf(

        CapabilitySpec(
            id = OUTSIDE_SESSION,
            labelRes = R.string.cap_outside_session_label,
            blurbRes = R.string.cap_outside_session_blurb,
            group = CapabilityGroup.MODES,
            default = false
        ),

        CapabilitySpec(
            id = ADVISORY_MODE,
            labelRes = R.string.cap_advisory_mode_label,
            blurbRes = R.string.cap_advisory_mode_blurb,
            group = CapabilityGroup.MODES,
            default = true,
            needsUsageAccess = true
        ),
        CapabilitySpec(
            id = SANCTUARY_MODE,
            labelRes = R.string.cap_sanctuary_mode_label,
            blurbRes = R.string.cap_sanctuary_mode_blurb,
            group = CapabilityGroup.MODES,
            default = true,
            weakenNoteRes = R.string.cap_sanctuary_mode_weaken_note,
            needsDeviceOwner = true
        ),
        CapabilitySpec(
            id = KIOSK_MODE,
            labelRes = R.string.cap_kiosk_mode_label,
            blurbRes = R.string.cap_kiosk_mode_blurb,
            group = CapabilityGroup.MODES,
            default = true,
            weakenNoteRes = R.string.cap_kiosk_mode_weaken_note,
            needsDeviceOwner = true
        ),

        CapabilitySpec(
            id = CAN_END_EARLY,
            labelRes = R.string.cap_can_end_early_label,
            blurbRes = R.string.cap_can_end_early_blurb,
            group = CapabilityGroup.MODES,
            default = true,
            polarity = CapabilityPolarity.EXEMPTION,
            weakenNoteRes = R.string.cap_can_end_early_weaken_note
        ),
        CapabilitySpec(
            id = LOCK_RULES_IN_SESSION,
            labelRes = R.string.cap_lock_rules_in_session_label,
            blurbRes = R.string.cap_lock_rules_in_session_blurb,
            group = CapabilityGroup.MODES,
            default = true,
            weakenNoteRes = R.string.cap_lock_rules_in_session_weaken_note
        ),

        CapabilitySpec(
            id = APP_BLOCK,
            labelRes = R.string.cap_app_block_label,
            blurbRes = R.string.cap_app_block_blurb,
            group = CapabilityGroup.BLOCKING,
            default = true,
            weakenNoteRes = R.string.cap_app_block_weaken_note,
            needsUsageAccess = true,
            detailScreen = Screens.APP_RULES
        ),
        CapabilitySpec(
            id = WEB_BLOCK,
            labelRes = R.string.cap_web_block_label,
            blurbRes = R.string.cap_web_block_blurb,
            group = CapabilityGroup.BLOCKING,
            default = true,
            weakenNoteRes = R.string.cap_web_block_weaken_note,
            needsDeviceOwner = true,
            detailScreen = Screens.WEB_RULES
        ),
        CapabilitySpec(
            id = ALWAYS_ALLOWED,
            labelRes = R.string.cap_always_allowed_label,
            blurbRes = R.string.cap_always_allowed_blurb,
            group = CapabilityGroup.BLOCKING,
            default = true,
            polarity = CapabilityPolarity.EXEMPTION,
            weakenNoteRes = R.string.cap_always_allowed_weaken_note,
            detailScreen = Screens.ALWAYS_ALLOWED
        ),
        CapabilitySpec(
            id = LAUNCH_FRICTION,
            labelRes = R.string.cap_launch_friction_label,
            blurbRes = R.string.cap_launch_friction_blurb,
            group = CapabilityGroup.BLOCKING,
            default = true,
            weakenNoteRes = R.string.cap_launch_friction_weaken_note,
            needsUsageAccess = true
        ),
        CapabilitySpec(
            id = TAKE_A_BREAK,
            labelRes = R.string.cap_take_a_break_label,
            blurbRes = R.string.cap_take_a_break_blurb,
            group = CapabilityGroup.BLOCKING,
            default = true,
            polarity = CapabilityPolarity.EXEMPTION,
            weakenNoteRes = R.string.cap_take_a_break_weaken_note
        ),
        CapabilitySpec(
            id = PER_APP_LIMITS,
            labelRes = R.string.cap_per_app_limits_label,
            blurbRes = R.string.cap_per_app_limits_blurb,
            group = CapabilityGroup.BLOCKING,
            default = false,
            weakenNoteRes = R.string.cap_per_app_limits_weaken_note,
            needsUsageAccess = true,
            detailScreen = Screens.LIMITS
        ),

        CapabilitySpec(
            id = EARN_MODE,
            labelRes = R.string.cap_earn_mode_label,
            blurbRes = R.string.cap_earn_mode_blurb,
            group = CapabilityGroup.MODES,
            default = false,
            polarity = CapabilityPolarity.EXEMPTION,
            weakenNoteRes = R.string.cap_earn_mode_weaken_note,
            needsUsageAccess = true,
            detailScreen = Screens.TASKS
        ),

        CapabilitySpec(
            id = CONTENT_GUARD,
            labelRes = R.string.cap_content_guard_label,
            blurbRes = R.string.cap_content_guard_blurb,
            group = CapabilityGroup.CONTENT,
            default = false,
            weakenNoteRes = R.string.cap_content_guard_weaken_note,
            needsAccessibility = true
        ),
        CapabilitySpec(
            id = KEYWORD_BLOCK,
            labelRes = R.string.cap_keyword_block_label,
            blurbRes = R.string.cap_keyword_block_blurb,
            group = CapabilityGroup.CONTENT,
            default = false,
            needsAccessibility = true,
            detailScreen = Screens.KEYWORDS
        ),
        CapabilitySpec(
            id = ADULT_BLOCK,
            labelRes = R.string.cap_adult_block_label,
            blurbRes = R.string.cap_adult_block_blurb,
            group = CapabilityGroup.CONTENT,
            default = false,
            needsAccessibility = true
        ),

        CapabilitySpec(
            id = SCHEDULES,
            labelRes = R.string.cap_schedules_label,
            blurbRes = R.string.cap_schedules_blurb,
            group = CapabilityGroup.ENVIRONMENT,
            default = true,
            weakenNoteRes = R.string.cap_schedules_weaken_note,
            detailScreen = Screens.SCHEDULES
        ),
        CapabilitySpec(
            id = BEDTIME_MODE,
            labelRes = R.string.cap_bedtime_mode_label,
            blurbRes = R.string.cap_bedtime_mode_blurb,
            group = CapabilityGroup.ENVIRONMENT,
            default = false,
            weakenNoteRes = R.string.cap_bedtime_mode_weaken_note,
            detailScreen = Screens.BEDTIME
        ),
        CapabilitySpec(
            id = NOTIFICATION_BLOCK,
            labelRes = R.string.cap_notification_block_label,
            blurbRes = R.string.cap_notification_block_blurb,
            group = CapabilityGroup.ENVIRONMENT,
            default = false,
            weakenNoteRes = R.string.cap_notification_block_weaken_note,
            needsNotificationAccess = true
        ),

        CapabilitySpec(
            id = SAFE_BROWSER,
            labelRes = R.string.cap_safe_browser_label,
            blurbRes = R.string.cap_safe_browser_blurb,
            group = CapabilityGroup.TOOLS,
            default = true,
            polarity = CapabilityPolarity.EXEMPTION
        ),
        CapabilitySpec(
            id = TEXT_SEARCH,
            labelRes = R.string.cap_text_search_label,
            blurbRes = R.string.cap_text_search_blurb,
            group = CapabilityGroup.TOOLS,
            default = true,
            polarity = CapabilityPolarity.EXEMPTION
        ),
        CapabilitySpec(
            id = SAFE_YOUTUBE,
            labelRes = R.string.cap_safe_youtube_label,
            blurbRes = R.string.cap_safe_youtube_blurb,
            group = CapabilityGroup.TOOLS,
            default = true,
            polarity = CapabilityPolarity.EXEMPTION
        ),

        CapabilitySpec(
            id = ANALYTICS,
            labelRes = R.string.cap_analytics_label,
            blurbRes = R.string.cap_analytics_blurb,
            group = CapabilityGroup.INSIGHT,
            default = true,
            polarity = CapabilityPolarity.NEUTRAL,
            needsUsageAccess = true,
            detailScreen = Screens.ANALYTICS
        ),

        CapabilitySpec(
            id = SUSPEND_BLOCKED_APPS,
            labelRes = R.string.cap_suspend_blocked_apps_label,
            blurbRes = R.string.cap_suspend_blocked_apps_blurb,
            group = CapabilityGroup.HARDENING,
            default = true,
            weakenNoteRes = R.string.cap_suspend_blocked_apps_weaken_note,
            needsDeviceOwner = true
        ),
        CapabilitySpec(
            id = HIDE_BLOCKED_APPS,
            labelRes = R.string.cap_hide_blocked_apps_label,
            blurbRes = R.string.cap_hide_blocked_apps_blurb,
            group = CapabilityGroup.HARDENING,
            default = false,
            weakenNoteRes = R.string.cap_hide_blocked_apps_weaken_note,
            needsDeviceOwner = true
        ),
        CapabilitySpec(
            id = STATUS_BAR_LOCK,
            labelRes = R.string.cap_status_bar_lock_label,
            blurbRes = R.string.cap_status_bar_lock_blurb,
            group = CapabilityGroup.HARDENING,
            default = false,
            weakenNoteRes = R.string.cap_status_bar_lock_weaken_note,
            needsDeviceOwner = true
        ),
        CapabilitySpec(
            id = KEYGUARD_LOCK,
            labelRes = R.string.cap_keyguard_lock_label,
            blurbRes = R.string.cap_keyguard_lock_blurb,
            group = CapabilityGroup.HARDENING,
            default = false,
            needsDeviceOwner = true
        ),
        CapabilitySpec(
            id = UNINSTALL_PROTECTION,
            labelRes = R.string.cap_uninstall_protection_label,
            blurbRes = R.string.cap_uninstall_protection_blurb,
            group = CapabilityGroup.HARDENING,
            default = true,
            weakenNoteRes = R.string.cap_uninstall_protection_weaken_note,
            needsDeviceOwner = true
        ),
        CapabilitySpec(
            id = SAFE_BOOT_BLOCK,
            labelRes = R.string.cap_safe_boot_block_label,
            blurbRes = R.string.cap_safe_boot_block_blurb,
            group = CapabilityGroup.HARDENING,
            default = true,
            weakenNoteRes = R.string.cap_safe_boot_block_weaken_note,
            needsDeviceOwner = true
        ),
        CapabilitySpec(
            id = PERSISTENT_HOME,
            labelRes = R.string.cap_persistent_home_label,
            blurbRes = R.string.cap_persistent_home_blurb,
            group = CapabilityGroup.HARDENING,
            default = true,
            weakenNoteRes = R.string.cap_persistent_home_weaken_note,
            needsDeviceOwner = true
        ),

        // Advanced: Places, Wi-Fi conditions, and Custom rules
        CapabilitySpec(
            id = LOCATION_BLOCK,
            labelRes = R.string.cap_location_block_label,
            blurbRes = R.string.cap_location_block_blurb,
            group = CapabilityGroup.ADVANCED,
            default = false,
            needsLocation = true,
            detailScreen = Screens.PLACES
        ),
        CapabilitySpec(
            id = WIFI_CONDITIONS,
            labelRes = R.string.cap_wifi_conditions_label,
            blurbRes = R.string.cap_wifi_conditions_blurb,
            group = CapabilityGroup.ADVANCED,
            default = false,
            needsLocation = true,
            detailScreen = Screens.PLACES
        ),
        CapabilitySpec(
            id = RULE_ENGINE,
            labelRes = R.string.cap_rule_engine_label,
            blurbRes = R.string.cap_rule_engine_blurb,
            group = CapabilityGroup.ADVANCED,
            default = false,
            detailScreen = Screens.RULES_LIST
        ),

        // Experimental: Fragile screen-reading guards, off by default
        CapabilitySpec(
            id = WHATSAPP_GUARD,
            labelRes = R.string.cap_whatsapp_guard_label,
            blurbRes = R.string.cap_whatsapp_guard_blurb,
            group = CapabilityGroup.EXPERIMENTAL,
            default = false,
            needsAccessibility = true
        ),
        CapabilitySpec(
            id = SHORTS_BLOCK,
            labelRes = R.string.cap_shorts_block_label,
            blurbRes = R.string.cap_shorts_block_blurb,
            group = CapabilityGroup.EXPERIMENTAL,
            default = false,
            needsAccessibility = true
        ),
        CapabilitySpec(
            id = REELS_BLOCK,
            labelRes = R.string.cap_reels_block_label,
            blurbRes = R.string.cap_reels_block_blurb,
            group = CapabilityGroup.EXPERIMENTAL,
            default = false,
            needsAccessibility = true
        ),
        CapabilitySpec(
            id = TELEGRAM_GUARD,
            labelRes = R.string.cap_telegram_guard_label,
            blurbRes = R.string.cap_telegram_guard_blurb,
            group = CapabilityGroup.EXPERIMENTAL,
            default = false,
            weakenNoteRes = R.string.cap_telegram_guard_weaken_note,
            needsAccessibility = true,
            detailScreen = Screens.KEYWORDS
        )
    )

    private val byId: Map<String, CapabilitySpec> = all.associateBy { it.id }

    fun spec(id: String): CapabilitySpec? = byId[id]

    fun coreSpecs(): List<CapabilitySpec> = coreIds.mapNotNull { byId[it] }

    fun grouped(): List<Pair<CapabilityGroup, List<CapabilitySpec>>> =
        CapabilityGroup.values()
            .map { group -> group to all.filter { it.group == group } }
            .filter { it.second.isNotEmpty() }
}

/**
 * Reads and writes the user's capability choices.
 */
object CapabilityRegistry {

    private const val KEY_ENABLED = "cap_enabled_json"
    private const val KEY_PARAMS = "cap_params_json"
    private const val KEY_SEEDED = "cap_seeded_v1"

    fun isEnabled(context: Context, id: String): Boolean {
        if (id == Capabilities.OPEN_COUNT_LIMITS) {
            return isEnabled(context, Capabilities.PER_APP_LIMITS)
        }
        overrides(context)[id]?.let { return it }
        return when (id) {
            Capabilities.HARD_BLOCK,
            Capabilities.STREAKS,
            Capabilities.SELF_COMPASSION_COPY,
            Capabilities.REPLACEMENT_SUGGESTIONS -> true
            else -> Capabilities.spec(id)?.default ?: false
        }
    }

    private fun overrides(context: Context): Map<String, Boolean> =
        PolicyCache.get("capabilityOverrides") {
            val json = FocusStore.getJsonObject(context, KEY_ENABLED)
            val out = HashMap<String, Boolean>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = json.optBoolean(key, false)
            }
            out
        }

    fun isUserSet(context: Context, id: String): Boolean =
        FocusStore.getJsonObject(context, KEY_ENABLED).has(id)

    fun isFrozen(context: Context): Boolean =
        (SessionManager.isActive(context) && isEnabled(context, Capabilities.LOCK_RULES_IN_SESSION)) ||
            SessionManager.shouldLockTask(context)

    fun directionFor(id: String, enabled: Boolean): EditDirection {
        if (id == Capabilities.STREAKS ||
            id == Capabilities.SELF_COMPASSION_COPY ||
            id == Capabilities.REPLACEMENT_SUGGESTIONS
        ) {
            return EditDirection.TIGHTEN
        }
        if (id == Capabilities.HARD_BLOCK) {
            return if (enabled) EditDirection.TIGHTEN else EditDirection.LOOSEN
        }
        val spec = Capabilities.spec(id) ?: return EditDirection.LOOSEN
        return when (spec.polarity) {
            CapabilityPolarity.NEUTRAL -> EditDirection.TIGHTEN
            CapabilityPolarity.GUARD -> if (enabled) EditDirection.TIGHTEN else EditDirection.LOOSEN
            CapabilityPolarity.EXEMPTION -> if (enabled) EditDirection.LOOSEN else EditDirection.TIGHTEN
        }
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean {
        val targetId = if (id == Capabilities.OPEN_COUNT_LIMITS) Capabilities.PER_APP_LIMITS else id
        if (!SessionLock.allows(context, directionFor(targetId, enabled))) return false
        writeEnabled(context, targetId, enabled)
        // If turning on a specific content guard, also ensure the master contentGuard is on
        if (enabled && (targetId == Capabilities.KEYWORD_BLOCK ||
                targetId == Capabilities.ADULT_BLOCK ||
                targetId == Capabilities.WHATSAPP_GUARD ||
                targetId == Capabilities.SHORTS_BLOCK ||
                targetId == Capabilities.REELS_BLOCK ||
                targetId == Capabilities.TELEGRAM_GUARD)
        ) {
            writeEnabled(context, Capabilities.CONTENT_GUARD, true)
        }
        return true
    }

    fun canToggle(context: Context, id: String, currentlyEnabled: Boolean): Boolean =
        SessionLock.allows(context, directionFor(id, !currentlyEnabled))

    internal fun writeEnabled(context: Context, id: String, enabled: Boolean) {
        val overrides = FocusStore.getJsonObject(context, KEY_ENABLED)
        overrides.put(id, enabled)
        FocusStore.setJsonObject(context, KEY_ENABLED, overrides)
        PolicySync.request(context, "capability:" + id)
    }

    fun applyPreset(context: Context, values: Map<String, Boolean>): Boolean {
        if (isFrozen(context)) return false
        if (values.isEmpty()) return true
        val overrides = FocusStore.getJsonObject(context, KEY_ENABLED)
        values.forEach { entry -> overrides.put(entry.key, entry.value) }
        FocusStore.setJsonObject(context, KEY_ENABLED, overrides)
        PolicySync.request(context, "capability:preset")
        return true
    }

    fun applySet(context: Context, values: Map<String, Boolean>): Boolean {
        if (isFrozen(context)) return false
        val overrides = FocusStore.getJsonObject(context, KEY_ENABLED)
        values.forEach { entry -> overrides.put(entry.key, entry.value) }
        FocusStore.setJsonObject(context, KEY_ENABLED, overrides)
        FocusStore.setBool(context, KEY_SEEDED, true)
        PolicySync.request(context, "capability:set")
        return true
    }

    fun hasBeenSeeded(context: Context): Boolean = FocusStore.getBool(context, KEY_SEEDED, false)

    fun markSeeded(context: Context) {
        FocusStore.setBool(context, KEY_SEEDED, true)
    }

    fun resetToDefaults(context: Context): Boolean {
        if (isFrozen(context)) return false
        FocusStore.remove(context, KEY_ENABLED)
        PolicySync.request(context, "capability:reset")
        return true
    }

    fun enabledSnapshot(context: Context): Map<String, Boolean> =
        Capabilities.all.associate { it.id to isEnabled(context, it.id) }

    fun getIntParam(context: Context, id: String, key: String, fallback: Int): Int {
        val forId = FocusStore.getJsonObject(context, KEY_PARAMS).optJSONObject(id) ?: return fallback
        return forId.optInt(key, fallback)
    }

    fun setIntParam(
        context: Context,
        id: String,
        key: String,
        value: Int,
        direction: EditDirection = EditDirection.LOOSEN
    ): Boolean {
        if (!SessionLock.allows(context, direction)) return false
        val params = FocusStore.getJsonObject(context, KEY_PARAMS)
        val forId = params.optJSONObject(id) ?: JSONObject()
        forId.put(key, value)
        params.put(id, forId)
        FocusStore.setJsonObject(context, KEY_PARAMS, params)
        PolicySync.request(context, "capabilityParam:" + id)
        return true
    }

    fun getBoolParam(context: Context, id: String, key: String, fallback: Boolean): Boolean {
        val forId = FocusStore.getJsonObject(context, KEY_PARAMS).optJSONObject(id) ?: return fallback
        return forId.optBoolean(key, fallback)
    }

    fun setBoolParam(
        context: Context,
        id: String,
        key: String,
        value: Boolean,
        direction: EditDirection = EditDirection.LOOSEN
    ): Boolean {
        if (!SessionLock.allows(context, direction)) return false
        val params = FocusStore.getJsonObject(context, KEY_PARAMS)
        val forId = params.optJSONObject(id) ?: JSONObject()
        forId.put(key, value)
        params.put(id, forId)
        FocusStore.setJsonObject(context, KEY_PARAMS, params)
        PolicySync.request(context, "capabilityParam:" + id)
        return true
    }

    fun exportJson(context: Context): JSONObject {
        val out = JSONObject()
        out.put("enabled", FocusStore.getJsonObject(context, KEY_ENABLED))
        out.put("params", FocusStore.getJsonObject(context, KEY_PARAMS))
        return out
    }

    fun importJson(context: Context, json: JSONObject): Boolean {
        if (isFrozen(context)) return false
        json.optJSONObject("enabled")?.let { FocusStore.setJsonObject(context, KEY_ENABLED, it) }
        json.optJSONObject("params")?.let { FocusStore.setJsonObject(context, KEY_PARAMS, it) }
        PolicySync.request(context, "capability:import")
        return true
    }
}
