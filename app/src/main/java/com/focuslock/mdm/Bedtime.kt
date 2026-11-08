package com.focuslock.mdm

import android.content.Context
import java.util.Calendar

/**
 * The night shift.
 *
 * Bedtime runs on the clock. With a session running it acts as it always did.
 * With no session it acts - block, overlay lock, dimmer and quiet theme - only
 * when the master switch "Let blockers work outside a session"
 * ([Capabilities.OUTSIDE_SESSION], default off) is on. See [Standalone].
 *
 * Everything about it is a setting, including which categories.
 */
object Bedtime {

    private const val KEY_START = "bedtime_start_minutes"
    private const val KEY_END = "bedtime_end_minutes"
    private const val KEY_CATEGORIES = "bedtime_categories"
    const val PARAM_BLOCK_ALL = "blockEverything"
    const val PARAM_OVERLAY = "overlay"
    private const val KEY_DIM = "bedtime_dim_percent"
    private const val KEY_DARK_THEME = "bedtime_dark_theme"
    private const val KEY_GRAYSCALE_HINT = "bedtime_grayscale_hint"

    const val DEFAULT_START = 22 * 60
    const val DEFAULT_END = 6 * 60

    fun isEnabled(context: Context): Boolean =
        CapabilityRegistry.isEnabled(context, Capabilities.BEDTIME_MODE)

    fun startMinutes(context: Context): Int = FocusStore.getInt(context, KEY_START, DEFAULT_START)

    fun endMinutes(context: Context): Int = FocusStore.getInt(context, KEY_END, DEFAULT_END)

    /** A longer bedtime is a tightening; shortening tonight's window mid-session waits. */
    fun setWindow(context: Context, startMinutes: Int, endMinutes: Int): Boolean {
        val before = windowLength(startMinutes(context), endMinutes(context))
        val after = windowLength(startMinutes.coerceIn(0, 1439), endMinutes.coerceIn(0, 1439))
        val direction = if (after >= before) EditDirection.TIGHTEN else EditDirection.LOOSEN
        if (!SessionLock.allows(context, direction)) return false
        FocusStore.setInt(context, KEY_START, startMinutes.coerceIn(0, 1439))
        FocusStore.setInt(context, KEY_END, endMinutes.coerceIn(0, 1439))
        PolicySync.request(context, "bedtimeWindow")
        return true
    }

    fun blockedCategories(context: Context): Set<AppCategory> {
        val stored = FocusStore.getSetOrNull(context, KEY_CATEGORIES)
            ?: return setOf(AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.GAMES)
        return stored.map { AppCategory.fromId(it) }.toSet()
    }

    /** How many minutes of the day the window covers, wrapping over midnight. */
    private fun windowLength(start: Int, end: Int): Int = when {
        start == end -> 0
        end > start -> end - start
        else -> 1_440 - start + end
    }

    /** Quieting another category tightens; un-quieting one waits. */
    fun setBlockedCategories(context: Context, categories: Collection<AppCategory>): Boolean {
        val direction = SessionLock.forRestrictionSet(
            blockedCategories(context).map { it.id }.toSet(),
            categories.map { it.id }.toSet()
        )
        if (!SessionLock.allows(context, direction)) return false
        FocusStore.setSet(context, KEY_CATEGORIES, categories.map { it.id })
        PolicySync.request(context, "bedtimeCategories")
        return true
    }

    /** How far to dim, as a percentage of the screen's own brightness. */
    fun dimPercent(context: Context): Int = FocusStore.getInt(context, KEY_DIM, 45).coerceIn(0, 80)

    fun setDimPercent(context: Context, value: Int) {
        FocusStore.setInt(context, KEY_DIM, value.coerceIn(0, 80))
    }

    fun forcesDarkTheme(context: Context): Boolean = FocusStore.getBool(context, KEY_DARK_THEME, true)

    fun setForcesDarkTheme(context: Context, value: Boolean) {
        FocusStore.setBool(context, KEY_DARK_THEME, value)
    }

    fun showsGrayscaleHint(context: Context): Boolean =
        FocusStore.getBool(context, KEY_GRAYSCALE_HINT, false)

    fun setShowsGrayscaleHint(context: Context, value: Boolean) {
        FocusStore.setBool(context, KEY_GRAYSCALE_HINT, value)
    }

    // ── State ─────────────────────────────────────────────────────

    fun isActive(context: Context, now: Calendar = Calendar.getInstance()): Boolean {
        if (!isEnabled(context)) return false
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        // Bedtime repeats every night, so which night owns the moment does not
        // matter here - only whether one does.
        return TimeWindow.contains(startMinutes(context), endMinutes(context), minutes)
    }

    /**
     * Whether bedtime should stop this app right now.
     *
     * Category matching is the default and it has a real hole: an app whose
     * category the catalogue could not work out lands in OTHER and sails
     * straight through, which is why "bedtime isn't locking" gets reported by
     * people who did switch it on. [blocksEverything] closes that by inverting
     * the question — everything stops except what you named as essential.
     */
    fun blocks(context: Context, packageName: String): Boolean {
        if (!isActive(context)) return false
        if (AppRules.isAlwaysAllowed(context, packageName)) return false
        if (SystemSurfaces.isCritical(packageName)) return false
        if (blocksEverything(context)) return true
        return AppCatalog.categoryOf(context, packageName) in blockedCategories(context)
    }

    /**
     * Strict bedtime: block everything that is not always-allowed.
     *
     * Off by default, because turning it on for an existing user overnight
     * would lock them out of apps they never asked bedtime to touch.
     */
    fun blocksEverything(context: Context): Boolean =
        CapabilityRegistry.getBoolParam(context, Capabilities.BEDTIME_MODE, PARAM_BLOCK_ALL, false)

    fun setBlocksEverything(context: Context, value: Boolean): Boolean =
        CapabilityRegistry.setBoolParam(
            context,
            Capabilities.BEDTIME_MODE,
            PARAM_BLOCK_ALL,
            value,
            direction = if (value) EditDirection.TIGHTEN else EditDirection.LOOSEN
        )

    /**
     * Bedtime as a real lock rather than an escapable block.
     *
     * Same primitive a schedule window's "overlay" uses: Device-Owner lock-task
     * pinning, so nothing outside always-allowed comes forward at all. Off by
     * default - bedtime is the gentlest thing in the app and turning it into a
     * pin for existing users overnight would be a nasty surprise.
     */
    fun isOverlay(context: Context): Boolean =
        CapabilityRegistry.getBoolParam(context, Capabilities.BEDTIME_MODE, PARAM_OVERLAY, false)

    fun setOverlay(context: Context, value: Boolean): Boolean =
        CapabilityRegistry.setBoolParam(
            context,
            Capabilities.BEDTIME_MODE,
            PARAM_OVERLAY,
            value,
            direction = if (value) EditDirection.TIGHTEN else EditDirection.LOOSEN
        )

    /** Closes FocusLock's own tabs for the night too. Only meaningful with [isOverlay]. See [Lockdown]. */
    fun bricksApp(context: Context): Boolean =
        isOverlay(context) &&
            CapabilityRegistry.getBoolParam(context, Capabilities.BEDTIME_MODE, Lockdown.PARAM_BRICK, false)

    fun setBricksApp(context: Context, value: Boolean): Boolean =
        CapabilityRegistry.setBoolParam(
            context,
            Capabilities.BEDTIME_MODE,
            Lockdown.PARAM_BRICK,
            value,
            direction = if (value) EditDirection.TIGHTEN else EditDirection.LOOSEN
        )

    /** Whether tonight's window is currently demanding the hard lock. */
    fun requiresLockTask(context: Context): Boolean =
        Standalone.mayActNow(context) && isActive(context) && isOverlay(context)

    /** Minutes until bedtime lifts, for the "back at 6:00" line on the block screen. */
    fun minutesUntilEnd(context: Context, now: Calendar = Calendar.getInstance()): Int {
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val end = endMinutes(context)
        return if (end > nowMinutes) end - nowMinutes else 1_440 - nowMinutes + end
    }

    fun formatWindow(context: Context): String =
        formatTime(context, startMinutes(context)) + " to " + formatTime(context, endMinutes(context))

    fun formatTime(context: Context, minutes: Int): String = TimeText.ofDay(context, minutes)
}
