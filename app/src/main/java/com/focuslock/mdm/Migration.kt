package com.focuslock.mdm

import android.content.Context

/**
 * The one-way door out of hardcoded behaviour.
 *
 * Old builds kept the allow list, the kill list and the WhatsApp phrases in
 * `Constants`. This runs once and copies all of it into the user-owned stores,
 * preserving anything the person had already customised. Afterwards the app
 * never reads those constants for policy again, and every one of those entries
 * is an ordinary row the user can edit or delete.
 */
object Migration {

    private const val KEY_VERSION = "store_migration_version"
    private const val CURRENT_VERSION = 10

    fun run(context: Context) {
        val version = FocusStore.getInt(context, KEY_VERSION, 0)
        if (version >= CURRENT_VERSION) return

        if (version < 1) {
            migrateAppLists(context)
            migrateAlwaysAllowed(context)
            KeywordRules.seedIfEmpty(context)
        }
        if (version < 2) {
            seedCategoryDefaults(context)
        }
        if (version < 3) {
            seedSessionBehaviourFlags(context)
        }
        if (version < 4) {
            migrateRetiredThemes(context)
        }
        if (version < 5) {
            EarnPool.migrateLegacyBalance(context)
        }
        if (version < 6) {
            KeywordRules.addMissingWhatsAppSeeds(context)
        }
        if (version < 7) {
            if (CapabilityRegistry.isUserSet(context, Capabilities.OPEN_COUNT_LIMITS) &&
                !CapabilityRegistry.isUserSet(context, Capabilities.PER_APP_LIMITS)
            ) {
                val oldVal = FocusStore.getJsonObject(context, "cap_enabled_json")
                    .optBoolean(Capabilities.OPEN_COUNT_LIMITS, false)
                if (oldVal) {
                    CapabilityRegistry.writeEnabled(context, Capabilities.PER_APP_LIMITS, true)
                }
            }
        }

        if (version in 1..7) {
            keepGuardsForUpgraders(context)
        }
        if (version in 1..8) {
            outsideSessionOffForUpgraders(context)
        }

        if (version < 10) {
            // The Moon redesign (2026-11) becomes everyone's look once; the old themes stay pickable.
            FocusStore.setString(context, Constants.KEY_UI_THEME, UiPrefs.MOON_THEME_ID)
        }

        FocusStore.setInt(context, KEY_VERSION, CURRENT_VERSION)
    }

    /**
     * 2.0 moved the WhatsApp, Shorts and Reels guards to Experimental and
     * switched their defaults from on to off. A switch nobody touched follows
     * its default, so without this a 1.x user who relied on them would update
     * and silently lose them. Upgraders keep the old "on" as an explicit
     * choice; fresh installs (version 0) get the new defaults; anything the
     * person set themselves is left alone.
     */
    private fun keepGuardsForUpgraders(context: Context) {
        for (id in listOf(Capabilities.WHATSAPP_GUARD, Capabilities.SHORTS_BLOCK, Capabilities.REELS_BLOCK)) {
            if (!CapabilityRegistry.isUserSet(context, id)) {
                CapabilityRegistry.writeEnabled(context, id, true)
            }
        }
    }

    /**
     * Blockers that can act with no session now sit behind one master switch,
     * "Let blockers work outside a session". An existing install starts with it
     * written off, so nothing keeps blocking on its own after the update.
     */
    private fun outsideSessionOffForUpgraders(context: Context) {
        CapabilityRegistry.writeEnabled(context, Capabilities.OUTSIDE_SESSION, false)
    }

    /**
     * The 7-theme-to-3 cut (2026-09, design system pass). Sage, Sand and Dawn
     * were dark themes distinguished mostly by a tinted background - which
     * the separate `backgrounds` token already does - so they land on
     * Obsidian plus the closest existing tint rather than losing their
     * character entirely. Mist was the second light theme and maps onto
     * Paper, the one that survived, since falling through silently would
     * otherwise land a light-theme person on a dark theme with no warning.
     *
     * Anyone not on one of these four retired ids is untouched -
     * `UiPrefs.getTheme` already falls back to Obsidian for any id it
     * doesn't recognise, so this only needs to handle the cases where that
     * generic fallback would pick the wrong brightness or throw away a tint
     * worth keeping.
     */
    private fun migrateRetiredThemes(context: Context) {
        val storedId = FocusStore.getString(context, Constants.KEY_UI_THEME, "")
        val (newTheme, newBackground) = when (storedId) {
            "sage" -> "obsidian" to "bg_emerald"
            "sand" -> "obsidian" to "bg_sand"
            "dawn" -> "obsidian" to "bg_ink"
            "mist" -> "paper" to null
            else -> return
        }
        UiPrefs.setThemeId(context, newTheme)
        // Only overrides the background if the person hadn't already picked
        // one of their own - a deliberate choice always wins over a
        // migration's guess at what their old theme's colour was standing in for.
        if (newBackground != null && UiPrefs.getBackground(context).id == "bg_default") {
            UiPrefs.setBackgroundId(context, newBackground)
        }
    }

    /**
     * Freezes existing behaviour before the flags take over.
     *
     * `canEndEarly` and `hardBlock` used to be decided by the mode enum. From
     * this version they are switches, and a switch has a default — which would
     * silently change what an already-installed phone does, possibly in the
     * middle of a running session. So on upgrade we write the values that
     * reproduce exactly what the person's current mode was already doing. From
     * then on the flags are theirs.
     *
     * Only writes flags the user has never set, so a deliberate choice is
     * never overwritten.
     */
    private fun seedSessionBehaviourFlags(context: Context) {
        val mode = SessionManager.mode(context)

        if (!CapabilityRegistry.isUserSet(context, Capabilities.CAN_END_EARLY)) {
            // Old rule: every mode except Kiosk could be ended early.
            CapabilityRegistry.writeEnabled(
                context,
                Capabilities.CAN_END_EARLY,
                mode != FocusMode.KIOSK
            )
        }

        if (!CapabilityRegistry.isUserSet(context, Capabilities.HARD_BLOCK)) {
            // Old rule: Soft nudged, everything else blocked outright.
            CapabilityRegistry.writeEnabled(
                context,
                Capabilities.HARD_BLOCK,
                mode != FocusMode.SOFT
            )
        }

        // Old rule: only Sanctuary and Kiosk hid apps from the launcher, even
        // when the hardening flag was on. The flag alone drives it now, so an
        // upgrading Soft/Block user would suddenly get hiding they never had.
        if (mode != FocusMode.SANCTUARY && mode != FocusMode.KIOSK &&
            CapabilityRegistry.isEnabled(context, Capabilities.HIDE_BLOCKED_APPS)
        ) {
            CapabilityRegistry.writeEnabled(context, Capabilities.HIDE_BLOCKED_APPS, false)
        }

        // Same for suspend, which Soft used to be exempt from.
        if (mode == FocusMode.SOFT &&
            CapabilityRegistry.isEnabled(context, Capabilities.SUSPEND_BLOCKED_APPS)
        ) {
            CapabilityRegistry.writeEnabled(context, Capabilities.SUSPEND_BLOCKED_APPS, false)
        }

        // Record the mode whose template these values represent, so the first
        // Start after upgrading does not immediately re-apply and undo them.
        SessionManager.markPresetApplied(context, mode)
    }

    /**
     * The old model was a single allowlist plus a kill list. The new model is a
     * policy per app, so: everything previously allowed becomes "open freely",
     * everything on the kill list becomes "blocked", and anything the user had
     * explicitly allowed wins over the seed.
     */
    private fun migrateAppLists(context: Context) {
        val legacyAllowlist = FocusStore.getSetOrNull(context, Constants.KEY_APP_ALLOWLIST)
        val alreadyHasPolicies = AppRules.allPolicies(context).isNotEmpty()
        if (alreadyHasPolicies) return

        // A phone that has never run FocusLock has nothing to carry over. Seeding the
        // built-in kill list there blocked ~40 apps the person never picked (Chrome and
        // the Play Store among them), and the Rules screen counted them as "blocked".
        // Their own choices come from onboarding instead. An upgrader still gets the
        // seed: their old build was already enforcing that list.
        if (isFreshInstall(context)) return

        val allowed = legacyAllowlist ?: Constants.LEGACY_WHITELIST
        AppRules.setPolicies(context, allowed, AppPolicy.ALLOW)

        val blocked = (Constants.LEGACY_KILL_LIST + Seed.distractions)
            .distinct()
            .filterNot { it in allowed }
        AppRules.setPolicies(context, blocked, AppPolicy.BLOCK)
    }

    /** True only when no setting of any kind has been written yet; any doubt means "upgrader". */
    internal fun isFreshInstall(context: Context): Boolean =
        isFresh(FocusStore.prefs(context).all.keys)

    internal fun isFresh(storedKeys: Set<String>): Boolean = storedKeys.all { it == KEY_VERSION }

    private fun migrateAlwaysAllowed(context: Context) {
        if (AppRules.alwaysAllowedRaw(context).isNotEmpty()) return

        val legacySchedule = FocusStore.getSet(context, Constants.KEY_SCHEDULE_ALWAYS_ALLOWED_APPS)
        val essentials = AppCatalog.detectEssentials(context)
        AppRules.setAlwaysAllowed(context, (legacySchedule + essentials).distinct())
    }

    /**
     * Categories start with no bulk policy at all. A first run that silently
     * blocked a whole category would be exactly the "app decided for you"
     * behaviour the registry exists to prevent, so this only records that the
     * step ran.
     */
    private fun seedCategoryDefaults(context: Context) {
        AppCatalog.invalidate()
    }
}
