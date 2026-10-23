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
    HARDENING(R.string.capgroup_hardening_label, R.string.capgroup_hardening_blurb);

    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)
}

/**
 * Which way switching a capability on moves the lock, for [SessionLock]'s
 * tighten/loosen question.
 *
 * Most switches are [GUARD]s: on means stricter, so they can be switched on
 * mid-session and only switching them off has to wait. A few are the other way
 * round - "let me end early", "take a break", the always-allowed list, the
 * replacement surfaces and Earn Mode all *widen* what is reachable when they
 * are on, so for those it is switching on that waits. [NEUTRAL] ones do not
 * touch enforcement at all and stay editable at every moment.
 */
enum class CapabilityPolarity {
    GUARD,
    EXEMPTION,
    NEUTRAL
}

/**
 * One user-owned switch.
 *
 * [weakenNote] is shown exactly once, as a single honest line, when the user
 * turns a safety capability OFF. It never nags and never blocks the change:
 * the point is informed autonomy, not guilt.
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
 * Every behaviour in FocusLock is gated on one of these ids. Nothing is
 * hardcoded on: the app reads the registry, the user writes it, and onboarding
 * only ever proposes a starting set.
 */
object Capabilities {

    // Modes
    const val ADVISORY_MODE = "advisoryMode"
    const val SANCTUARY_MODE = "sanctuaryMode"
    const val KIOSK_MODE = "kioskMode"

    // How a running session behaves. These used to be decided by the FocusMode
    // enum, which meant a mode was a cage: pick Kiosk and you could not soften
    // any part of it. They are ordinary switches now, so a mode is a starting
    // point you can edit afterwards in You -> Advanced.
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

    // Replacements
    const val SAFE_BROWSER = "safeBrowser"
    const val TEXT_SEARCH = "textSearch"
    const val SAFE_YOUTUBE = "safeYouTube"
    const val YOUTUBE_MODESTY_BLUR = "youtubeModestyBlur"
    const val REPLACEMENT_SUGGESTIONS = "replacementSuggestions"

    // Earn
    const val EARN_MODE = "earnMode"

    // Insight
    const val ANALYTICS = "analytics"
    const val STREAKS = "streaks"
    const val SELF_COMPASSION_COPY = "selfCompassionCopy"
    // "profiles" and "social" used to be switches here. Profiles was never
    // gated on (the Rules tab always shows it) and "social" was one share
    // button, now a plain button on the You tab. Their old ids may still sit
    // in saved settings and exported profile files; unknown ids are ignored,
    // so nothing needs migrating.

    // Hardening
    const val SUSPEND_BLOCKED_APPS = "suspendBlockedApps"
    const val HIDE_BLOCKED_APPS = "hideBlockedApps"
    const val STATUS_BAR_LOCK = "statusBarLock"
    const val KEYGUARD_LOCK = "keyguardLock"
    const val UNINSTALL_PROTECTION = "uninstallProtection"
    const val SAFE_BOOT_BLOCK = "safeBootBlock"
    const val PERSISTENT_HOME = "persistentHome"

    val all: List<CapabilitySpec> = listOf(

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
            weakenNoteRes = R.string.cap_can_end_early_weaken_note,
        ),
        CapabilitySpec(
            id = LOCK_RULES_IN_SESSION,
            labelRes = R.string.cap_lock_rules_in_session_label,
            blurbRes = R.string.cap_lock_rules_in_session_blurb,
            group = CapabilityGroup.MODES,
            default = true,
            weakenNoteRes = R.string.cap_lock_rules_in_session_weaken_note,
        ),
        CapabilitySpec(
            id = HARD_BLOCK,
            labelRes = R.string.cap_hard_block_label,
            blurbRes = R.string.cap_hard_block_blurb,
            group = CapabilityGroup.MODES,
            default = true,
            weakenNoteRes = R.string.cap_hard_block_weaken_note,
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
            id = RULE_ENGINE,
            labelRes = R.string.cap_rule_engine_label,
            blurbRes = R.string.cap_rule_engine_blurb,
            group = CapabilityGroup.BLOCKING,
            default = true,
            detailScreen = Screens.RULES_LIST
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
            weakenNoteRes = R.string.cap_take_a_break_weaken_note,
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
            id = OPEN_COUNT_LIMITS,
            labelRes = R.string.cap_open_count_limits_label,
            blurbRes = R.string.cap_open_count_limits_blurb,
            group = CapabilityGroup.BLOCKING,
            default = false,
            needsUsageAccess = true,
            detailScreen = Screens.LIMITS
        ),

        // Earn Mode sits in Modes because that is what it is: a fifth way to run
        // the phone, layered on any of the other four. Off by default, always.
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
            default = true,
            weakenNoteRes = R.string.cap_content_guard_weaken_note,
            needsAccessibility = true
        ),
        CapabilitySpec(
            id = KEYWORD_BLOCK,
            labelRes = R.string.cap_keyword_block_label,
            blurbRes = R.string.cap_keyword_block_blurb,
            group = CapabilityGroup.CONTENT,
            default = true,
            needsAccessibility = true,
            detailScreen = Screens.KEYWORDS
        ),
        CapabilitySpec(
            id = WHATSAPP_GUARD,
            labelRes = R.string.cap_whatsapp_guard_label,
            blurbRes = R.string.cap_whatsapp_guard_blurb,
            group = CapabilityGroup.CONTENT,
            default = true,
            needsAccessibility = true
        ),
        CapabilitySpec(
            id = TELEGRAM_GUARD,
            labelRes = R.string.cap_telegram_guard_label,
            blurbRes = R.string.cap_telegram_guard_blurb,
            group = CapabilityGroup.CONTENT,
            default = false,
            weakenNoteRes = R.string.cap_telegram_guard_weaken_note,
            needsAccessibility = true,
            detailScreen = Screens.KEYWORDS
        ),
        CapabilitySpec(
            id = SHORTS_BLOCK,
            labelRes = R.string.cap_shorts_block_label,
            blurbRes = R.string.cap_shorts_block_blurb,
            group = CapabilityGroup.CONTENT,
            default = true,
            needsAccessibility = true
        ),
        CapabilitySpec(
            id = REELS_BLOCK,
            labelRes = R.string.cap_reels_block_label,
            blurbRes = R.string.cap_reels_block_blurb,
            group = CapabilityGroup.CONTENT,
            default = true,
            needsAccessibility = true
        ),
        CapabilitySpec(
            id = ADULT_BLOCK,
            labelRes = R.string.cap_adult_block_label,
            blurbRes = R.string.cap_adult_block_blurb,
            group = CapabilityGroup.CONTENT,
            default = true,
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
            id = LOCATION_BLOCK,
            labelRes = R.string.cap_location_block_label,
            blurbRes = R.string.cap_location_block_blurb,
            group = CapabilityGroup.ENVIRONMENT,
            default = false,
            needsLocation = true,
            detailScreen = Screens.PLACES
        ),
        CapabilitySpec(
            id = WIFI_CONDITIONS,
            labelRes = R.string.cap_wifi_conditions_label,
            blurbRes = R.string.cap_wifi_conditions_blurb,
            group = CapabilityGroup.ENVIRONMENT,
            default = false,
            needsLocation = true,
            detailScreen = Screens.PLACES
        ),
        CapabilitySpec(
            id = NOTIFICATION_BLOCK,
            labelRes = R.string.cap_notification_block_label,
            blurbRes = R.string.cap_notification_block_blurb,
            group = CapabilityGroup.ENVIRONMENT,
            default = true,
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
            id = YOUTUBE_MODESTY_BLUR,
            labelRes = R.string.cap_youtube_modesty_blur_label,
            blurbRes = R.string.cap_youtube_modesty_blur_blurb,
            group = CapabilityGroup.CONTENT,
            default = false
        ),
        CapabilitySpec(
            id = REPLACEMENT_SUGGESTIONS,
            labelRes = R.string.cap_replacement_suggestions_label,
            blurbRes = R.string.cap_replacement_suggestions_blurb,
            group = CapabilityGroup.TOOLS,
            default = true,
            polarity = CapabilityPolarity.NEUTRAL
        ),

        CapabilitySpec(
            id = ANALYTICS,
            labelRes = R.string.cap_analytics_label,
            blurbRes = R.string.cap_analytics_blurb,
            group = CapabilityGroup.INSIGHT,
            default = true,
            // Nothing in Insight changes what is reachable, so none of it is
            // part of the freeze - these stay editable mid-session.
            polarity = CapabilityPolarity.NEUTRAL,
            needsUsageAccess = true,
            detailScreen = Screens.ANALYTICS
        ),
        CapabilitySpec(
            id = STREAKS,
            labelRes = R.string.cap_streaks_label,
            blurbRes = R.string.cap_streaks_blurb,
            group = CapabilityGroup.INSIGHT,
            default = true,
            polarity = CapabilityPolarity.NEUTRAL
        ),
        CapabilitySpec(
            id = SELF_COMPASSION_COPY,
            labelRes = R.string.cap_self_compassion_copy_label,
            blurbRes = R.string.cap_self_compassion_copy_blurb,
            group = CapabilityGroup.INSIGHT,
            default = true,
            polarity = CapabilityPolarity.NEUTRAL
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
        )
    )

    private val byId: Map<String, CapabilitySpec> = all.associateBy { it.id }

    fun spec(id: String): CapabilitySpec? = byId[id]

    fun grouped(): List<Pair<CapabilityGroup, List<CapabilitySpec>>> =
        CapabilityGroup.values()
            .map { group -> group to all.filter { it.group == group } }
            .filter { it.second.isNotEmpty() }
}

/**
 * Reads and writes the user's capability choices.
 *
 * A capability is on when the user says it is. Until they touch it, the spec
 * default applies. Nothing in the app flips a switch behind their back: the
 * only writer other than the user is onboarding, and only on an explicit tap.
 */
object CapabilityRegistry {

    private const val KEY_ENABLED = "cap_enabled_json"
    private const val KEY_PARAMS = "cap_params_json"
    private const val KEY_SEEDED = "cap_seeded_v1"

    fun isEnabled(context: Context, id: String): Boolean {
        overrides(context)[id]?.let { return it }
        return Capabilities.spec(id)?.default ?: false
    }

    /**
     * The user's switches, parsed once per policy revision.
     *
     * [isEnabled] is the most-called function in the app - the content guard
     * asks it on every accessibility event from every app, and the guard loop
     * several times a tick - and it used to parse the whole JSON blob from
     * SharedPreferences on every one of those calls. Every write to it below
     * is followed by [PolicySync.request], so the revision is a sound key.
     */
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

    /**
     * Whether the rules are currently frozen by a running session or an
     * overlay schedule window.
     *
     * A self-lock that can be edited from inside is not a lock: the moment the
     * urge arrives is exactly the moment a person will go and switch the guard
     * off, and they will feel it was reasonable at the time. So while a session
     * runs, the switches stop moving.
     *
     * It is still a capability, not a law. Someone who genuinely wants to steer
     * mid-session turns [Capabilities.LOCK_RULES_IN_SESSION] off *before*
     * starting one. It cannot be turned off from inside a session — that would
     * make it a door with the key taped to it.
     *
     * An overlay schedule window freezes unconditionally, with no capability
     * to switch it off from inside — that toggle would be the same taped-on
     * key, and "dead phone" was the one thing overlay promised. Editing the
     * window itself (moving its end time, dropping its own overlay flag,
     * widening its allowedApps) would otherwise be exactly the loophole the
     * lock-task pinning was supposed to close, since FocusLock stays reachable
     * inside every overlay window by design.
     */
    fun isFrozen(context: Context): Boolean =
        (SessionManager.isActive(context) && isEnabled(context, Capabilities.LOCK_RULES_IN_SESSION)) ||
            SessionManager.shouldLockTask(context)

    /**
     * Which way flipping [id] to [enabled] moves the lock.
     *
     * A guard switched on, or an exemption switched off, both tighten and go
     * through mid-session. The reverse of each waits. Anything that does not
     * touch enforcement is neutral and always editable.
     */
    fun directionFor(id: String, enabled: Boolean): EditDirection {
        val spec = Capabilities.spec(id) ?: return EditDirection.LOOSEN
        return when (spec.polarity) {
            CapabilityPolarity.NEUTRAL -> EditDirection.TIGHTEN
            CapabilityPolarity.GUARD -> if (enabled) EditDirection.TIGHTEN else EditDirection.LOOSEN
            CapabilityPolarity.EXEMPTION -> if (enabled) EditDirection.LOOSEN else EditDirection.TIGHTEN
        }
    }

    /**
     * Writes a capability, unless a session has frozen the rules against a
     * change in this direction.
     *
     * Returns false when the write was refused, so callers can say so instead
     * of leaving a toggle that springs back with no explanation.
     */
    fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean {
        if (!SessionLock.allows(context, directionFor(id, enabled))) return false
        writeEnabled(context, id, enabled)
        return true
    }

    /** Whether the switch for [id] can be moved at all right now, in either direction. */
    fun canToggle(context: Context, id: String, currentlyEnabled: Boolean): Boolean =
        SessionLock.allows(context, directionFor(id, !currentlyEnabled))

    /**
     * The unguarded write.
     *
     * Migration and session-end cleanup both need to move flags while a session
     * is technically still active, and neither is a user changing their mind.
     */
    internal fun writeEnabled(context: Context, id: String, enabled: Boolean) {
        val overrides = FocusStore.getJsonObject(context, KEY_ENABLED)
        overrides.put(id, enabled)
        FocusStore.setJsonObject(context, KEY_ENABLED, overrides)
        PolicySync.request(context, "capability:" + id)
    }

    /**
     * Loads a mode's template.
     *
     * Same merge as [applySet] but deliberately does NOT mark the registry
     * seeded: seeding means "this person has been through setup", and picking
     * a mode on the dashboard is not that. Conflating the two would skip
     * onboarding for someone who had never seen it.
     *
     * Frozen-gated like every other capability write. This one is called from
     * two places: starting a session (always unfrozen, since no session is
     * active yet at that point) and the "put this mode's defaults back"
     * button in You -> Advanced, which - unlike every switch on that same
     * screen - had no freeze check at all until this was added. A session
     * whose rules were supposedly "held still" could be silently rewritten by
     * a single tap on that button; the freeze promise applies here too.
     */
    fun applyPreset(context: Context, values: Map<String, Boolean>): Boolean {
        if (isFrozen(context)) return false
        if (values.isEmpty()) return true
        val overrides = FocusStore.getJsonObject(context, KEY_ENABLED)
        values.forEach { entry -> overrides.put(entry.key, entry.value) }
        FocusStore.setJsonObject(context, KEY_ENABLED, overrides)
        PolicySync.request(context, "capability:preset")
        return true
    }

    /**
     * Applies a whole proposed set at once. Written for first-run onboarding,
     * where no session can possibly be active - but the setup quiz can also
     * be re-run later from You -> Advanced, which very much can happen mid-
     * session, and this call was the one place in the whole app that could
     * rewrite every capability flag at once with no freeze check at all. This
     * is that fix; OnboardingActivity's rerun entry point is also blocked
     * outright while frozen, so nobody sits through six quiz steps to be
     * refused on the last tap.
     */
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

    /**
     * Every capability switch back to factory default, in one tap. Frozen-
     * gated for the obvious reason: this is applyPreset's whole-registry
     * cousin, reachable from RulesTab's "Reset everything" and just as
     * capable of silently undoing a frozen session's rules if left unguarded.
     */
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

    /**
     * Parameters carry no polarity of their own - "8 seconds" is not inherently
     * stricter than "5" without knowing what it measures - so the caller says
     * which way it goes. Left unspecified, a parameter write is treated as a
     * loosening, which is the safe default for a freeze.
     */
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
