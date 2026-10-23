package com.focuslock.mdm

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The design tokens, and the user's choices about them.
 *
 * Every colour, radius, spacing step and typeface in FocusLock comes from here.
 * No screen defines a colour of its own, which is what makes a theme change
 * reach dialogs, the block screen and the browser rather than just the
 * dashboard.
 */
object UiPrefs {

    data class UiTheme(
        val id: String,
        @StringRes val labelRes: Int,
        val isLight: Boolean,
        val background: Int,
        val card: Int,
        val cardAlt: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val track: Int,
        val divider: Int,
        val input: Int
    )

    data class UiFont(val id: String, @StringRes val labelRes: Int, val typeface: Typeface)

    data class UiDensity(
        val id: String,
        @StringRes val labelRes: Int,
        val contentPaddingDp: Int,
        val cardPaddingDp: Int,
        val buttonHeightDp: Int,
        val quickButtonHeightDp: Int,
        val browseHeightDp: Int,
        val gapDp: Int
    )

    data class UiWallpaper(val id: String, @StringRes val labelRes: Int, @DrawableRes val drawableRes: Int)

    /**
     * An accent's flat colour, plus the deep stop its primary-button gradient
     * ends on.
     *
     * The deep stop lives here, next to the colour it belongs to, rather than
     * being recognised by magic value at the point a button is drawn. It used
     * to work the other way round - FocusUi compared the incoming colour
     * against the literal brand hex to decide whether to use the doc-specified
     * pairing - which meant the pairing silently stopped applying the moment
     * anyone retuned the blue, and put a screen in the position of knowing what
     * "the default accent" was. [deepColor] is null for every accent with no
     * doc-specified shade, and the caller darkens instead.
     */
    data class UiAccent(
        val id: String,
        @StringRes val labelRes: Int,
        val color: Int,
        val deepColor: Int? = null
    )

    data class UiBackground(val id: String, @StringRes val labelRes: Int, val color: Int)

    /**
     * A fully resolved token set: theme, accent, background, font, density and
     * the bedtime override, collapsed into the numbers a view actually needs.
     */
    data class Tokens(
        val isLight: Boolean,
        val background: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val accent: Int,
        /** The deep stop of the primary-button gradient. See [UiAccent.deepColor]. */
        val accentDeep: Int,
        val onAccent: Int,
        val accentSoft: Int,
        val divider: Int,
        val input: Int,
        val track: Int,
        val danger: Int,
        val success: Int,
        val warning: Int,
        val typeface: Typeface,
        /**
         * Numerals, timers, counts, state-chip labels and section overlines
         * use this - always IBM Plex Mono, regardless of [typeface]. That is
         * deliberate: it is what keeps the app's technical honesty legible
         * (a countdown reads as a countdown) without the whole app wearing
         * the "engineer-built" look full-mono body text gave it before.
         * Pass a weight (400/500/600) to FocusUi.applyFont(mono = true, ...)
         * to pick the matching instance out of this family.
         */
        val monoTypeface: Typeface,
        val radiusDp: Int,
        val textScale: Float,
        val density: UiDensity,
        val reducedMotion: Boolean,
        @DrawableRes val wallpaperRes: Int,
        val dimPercent: Int
    ) {
        fun scaled(sp: Float): Float = sp * textScale

        // Radius roles. The user's one slider (radiusDp, default 20 = the
        // card role below) offsets all four together, proportionally, so
        // "make corners rounder" still means one dial rather than four.
        val chipRadiusDp: Int get() = (radiusDp * 9 / 20).coerceIn(2, 18)
        val rowRadiusDp: Int get() = (radiusDp * 15 / 20).coerceIn(4, 26)
        // Not one of the doc's four named roles, but every button mockup in
        // it consistently uses r16 - close to but distinct from row(15).
        // Derived the same proportional way as the other roles so it still
        // answers to the one radius slider.
        val buttonRadiusDp: Int get() = (radiusDp * 16 / 20).coerceIn(4, 28)
        val cardRadiusDp: Int get() = radiusDp
        val heroRadiusDp: Int get() = (radiusDp * 24 / 20).coerceIn(6, 40)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_MAIN, Context.MODE_PRIVATE)

    private const val DEFAULT_THEME_ID = "obsidian"
    private const val DEFAULT_FONT_ID = "figtree"
    private const val DEFAULT_DENSITY_ID = "comfortable"
    private const val DEFAULT_WALLPAPER_ID = "none"
    private const val DEFAULT_ACCENT_ID = "accent_blue"
    private const val DEFAULT_BACKGROUND_ID = "bg_default"
    // Card radius default moved 18->20 to match the design system's card
    // role exactly; chip/row/hero are derived proportionally from whatever
    // this slider is set to - see chipRadiusDp/rowRadiusDp/heroRadiusDp.
    private const val DEFAULT_CARD_RADIUS_DP = 20
    private const val DEFAULT_TEXT_SCALE = 1.0f

    // ── Palettes ──────────────────────────────────────────────────
    //
    // Every pair below clears 4.5:1 for body text and 3:1 for large text on its
    // own surfaces. The light theme exists because "focus app" does not have to
    // mean "black rectangle", and because a bright room is a real use case.
    //
    // Cut from 7 themes to 3 (2026-09, design system pass): Sage, Sand, Dawn
    // and Mist were the same dark-or-light theme with a tinted background,
    // which the separate `backgrounds` list already does as its own token -
    // 7 themes x 8 accents x 6 fonts x 3 densities was over a thousand
    // combinations to keep contrast-safe for very little real difference.
    // Migration.kt maps anyone still on the old ids onto the nearest of
    // these three, keeping their look as close as a straight retint allows.
    // Nocturne is untouched - it already had a distinct, deliberately quiet
    // job (the auto theme at bedtime) and didn't need retuning.

    val themes: List<UiTheme> = listOf(
        UiTheme(
            id = "obsidian",
            labelRes = R.string.appearance_theme_obsidian,
            isLight = false,
            background = Color.parseColor("#0A0C10"),
            card = Color.parseColor("#12151B"),
            cardAlt = Color.parseColor("#1A1F27"),
            textPrimary = Color.parseColor("#EDF1F7"),
            textSecondary = Color.parseColor("#98A3B4"),
            accent = Color.parseColor("#4C93FF"),
            track = Color.parseColor("#1E2532"),
            divider = Color.parseColor("#232935"),
            input = Color.parseColor("#1E2532")
        ),
        UiTheme(
            id = "nocturne",
            labelRes = R.string.appearance_theme_nocturne,
            isLight = false,
            background = Color.parseColor("#000000"),
            card = Color.parseColor("#0D0D10"),
            cardAlt = Color.parseColor("#141419"),
            textPrimary = Color.parseColor("#D8D8DC"),
            textSecondary = Color.parseColor("#7A7A82"),
            accent = Color.parseColor("#8B7CF6"),
            track = Color.parseColor("#17171C"),
            divider = Color.parseColor("#17171C"),
            input = Color.parseColor("#101014")
        ),
        UiTheme(
            id = "paper",
            labelRes = R.string.appearance_theme_paper,
            isLight = true,
            background = Color.parseColor("#F7F8FA"),
            card = Color.parseColor("#FFFFFF"),
            cardAlt = Color.parseColor("#EFF2F6"),
            textPrimary = Color.parseColor("#10141A"),
            textSecondary = Color.parseColor("#5A6472"),
            accent = Color.parseColor("#1D6FE8"),
            track = Color.parseColor("#EDF1F6"),
            divider = Color.parseColor("#E2E7EE"),
            input = Color.parseColor("#EDF1F6")
        )
    )

    /**
     * Cut from 6 to 3 (2026-09, design system pass): Sans, Serif, Light and
     * Condensed were all still just the one job - carry prose - and Figtree
     * does that job better than any of them while giving the app an actual
     * typographic identity instead of "whatever Android ships." System and
     * Mono stay because they are real, different choices: System for
     * "match my phone," Mono for someone who wants the engineer-built look
     * on purpose. See [resolveMonoTypeface] for the *structural* mono use
     * (numerals, timers, chip labels) that applies regardless of this choice.
     *
     * The `typeface` field on the Figtree entry is a placeholder only -
     * loading the real bundled font needs a Context, which this static list
     * does not have. Every real read goes through [resolve], which swaps in
     * the actual font-family Typeface for whichever id is selected. Nothing
     * outside this file reads `.typeface` off a `fonts` list entry directly
     * (confirmed: PersonalizationActivity/YouTab's font pickers only read
     * `.id`/`.label`), so this placeholder is never seen by a screen.
     */
    val fonts: List<UiFont> = listOf(
        UiFont("figtree", R.string.appearance_font_figtree, Typeface.DEFAULT),
        UiFont("system", R.string.appearance_font_system, Typeface.DEFAULT),
        UiFont("mono", R.string.appearance_font_mono, Typeface.MONOSPACE)
    )

    val densities: List<UiDensity> = listOf(
        UiDensity(
            id = "comfortable",
            labelRes = R.string.appearance_density_comfortable,
            contentPaddingDp = 20,
            cardPaddingDp = 18,
            buttonHeightDp = 52,
            quickButtonHeightDp = 44,
            browseHeightDp = 60,
            gapDp = 12
        ),
        UiDensity(
            id = "compact",
            labelRes = R.string.appearance_density_compact,
            contentPaddingDp = 14,
            cardPaddingDp = 13,
            buttonHeightDp = 44,
            quickButtonHeightDp = 38,
            browseHeightDp = 50,
            gapDp = 8
        ),
        UiDensity(
            id = "roomy",
            labelRes = R.string.appearance_density_roomy,
            contentPaddingDp = 26,
            cardPaddingDp = 22,
            buttonHeightDp = 58,
            quickButtonHeightDp = 50,
            browseHeightDp = 68,
            gapDp = 16
        )
    )

    val wallpapers: List<UiWallpaper> = listOf(
        UiWallpaper("none", R.string.appearance_wallpaper_none, 0),
        UiWallpaper("slate", R.string.appearance_wallpaper_slate, R.drawable.bg_wallpaper_slate),
        UiWallpaper("sunset", R.string.appearance_wallpaper_sunset, R.drawable.bg_wallpaper_sunset),
        UiWallpaper("ocean", R.string.appearance_wallpaper_ocean, R.drawable.bg_wallpaper_ocean)
    )

    val accents: List<UiAccent> = listOf(
        // Retinted to the brand blue (2026-09, design system pass): neon
        // pink on near-black read as a gaming utility. #4C93FF is the flat
        // accent the design doc uses everywhere - icons, active states,
        // borders, text - across every mockup in the set. #1D4ED8 (the
        // marketing site/app-icon shade) is the doc-specified deep stop of the
        // primary-button gradient and only ever appears there; the other seven
        // accents have no doc-specified deep tone, so they darken instead.
        UiAccent("accent_blue", R.string.appearance_accent_blue, Color.parseColor("#4C93FF"), Color.parseColor("#1D4ED8")),
        UiAccent("accent_green", R.string.appearance_accent_green, Color.parseColor("#22C55E")),
        UiAccent("accent_orange", R.string.appearance_accent_orange, Color.parseColor("#F97316")),
        UiAccent("accent_pink", R.string.appearance_accent_pink, Color.parseColor("#EC4899")),
        UiAccent("accent_teal", R.string.appearance_accent_teal, Color.parseColor("#14B8A6")),
        UiAccent("accent_gold", R.string.appearance_accent_gold, Color.parseColor("#F59E0B")),
        UiAccent("accent_violet", R.string.appearance_accent_violet, Color.parseColor("#8B5CF6")),
        UiAccent("accent_slate", R.string.appearance_accent_slate, Color.parseColor("#64748B"))
    )

    val backgrounds: List<UiBackground> = listOf(
        UiBackground("bg_default", R.string.appearance_bg_default, Color.TRANSPARENT),
        UiBackground("bg_midnight", R.string.appearance_bg_midnight, Color.parseColor("#08080C")),
        UiBackground("bg_ink", R.string.appearance_bg_ink, Color.parseColor("#0D1219")),
        UiBackground("bg_emerald", R.string.appearance_bg_emerald, Color.parseColor("#0B1A15")),
        UiBackground("bg_sand", R.string.appearance_bg_sand, Color.parseColor("#181209")),
        UiBackground("bg_pure_black", R.string.appearance_bg_pure_black, Color.parseColor("#000000"))
    )

    // ── Getters and setters ───────────────────────────────────────

    fun getTheme(context: Context): UiTheme {
        val id = prefs(context).getString(Constants.KEY_UI_THEME, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
        return themes.firstOrNull { it.id == id } ?: themes.first()
    }

    fun setThemeId(context: Context, id: String) {
        prefs(context).edit().putString(Constants.KEY_UI_THEME, id).apply()
    }

    fun getFont(context: Context): UiFont {
        val id = prefs(context).getString(Constants.KEY_UI_FONT, DEFAULT_FONT_ID) ?: DEFAULT_FONT_ID
        return fonts.firstOrNull { it.id == id } ?: fonts.first()
    }

    fun setFontId(context: Context, id: String) {
        prefs(context).edit().putString(Constants.KEY_UI_FONT, id).apply()
    }

    /**
     * The app's current per-app language, or `"system"` when nothing has
     * been chosen and it's following the device's own setting.
     *
     * Backed by [AppCompatDelegate]'s own locale storage (auto-persisted
     * since AppCompat 1.6, which this app already depends on) rather than a
     * separate SharedPreferences key - one source of truth for what locale
     * is actually applied, instead of two that could drift out of sync.
     */
    fun getAppLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return "system"
        return locales[0]?.language ?: "system"
    }

    /**
     * Applies a per-app language. `"system"` clears the override and follows
     * the device's own setting. Recreates every active `AppCompatActivity`
     * automatically - that's built into [AppCompatDelegate], not something
     * this app needs to trigger itself.
     */
    fun setAppLanguage(tag: String) {
        val locales = if (tag == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun getDensity(context: Context): UiDensity {
        val id = prefs(context).getString(Constants.KEY_UI_DENSITY, DEFAULT_DENSITY_ID) ?: DEFAULT_DENSITY_ID
        return densities.firstOrNull { it.id == id } ?: densities.first()
    }

    fun setDensityId(context: Context, id: String) {
        prefs(context).edit().putString(Constants.KEY_UI_DENSITY, id).apply()
    }

    /**
     * Whether clock times read "10:30 PM" or "22:30".
     *
     * Defaults to "system", i.e. whatever the phone's own 12/24 hour setting
     * says - which is what the app should have been doing all along instead of
     * hardcoding 24-hour everywhere. See [TimeText].
     */
    fun getClockFormat(context: Context): String =
        prefs(context).getString(KEY_CLOCK_FORMAT, TimeText.CLOCK_SYSTEM) ?: TimeText.CLOCK_SYSTEM

    fun setClockFormat(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CLOCK_FORMAT, value).apply()
    }

    private const val KEY_CLOCK_FORMAT = "ui_clock_format"

    fun getWallpaper(context: Context): UiWallpaper {
        val id = prefs(context).getString(Constants.KEY_UI_WALLPAPER, DEFAULT_WALLPAPER_ID) ?: DEFAULT_WALLPAPER_ID
        return wallpapers.firstOrNull { it.id == id } ?: wallpapers.first()
    }

    fun setWallpaperId(context: Context, id: String) {
        prefs(context).edit().putString(Constants.KEY_UI_WALLPAPER, id).apply()
    }

    fun getAccent(context: Context): UiAccent {
        val id = prefs(context).getString(Constants.KEY_UI_ACCENT, DEFAULT_ACCENT_ID) ?: DEFAULT_ACCENT_ID
        return accents.firstOrNull { it.id == id } ?: accents.first()
    }

    fun setAccentId(context: Context, id: String) {
        prefs(context).edit().putString(Constants.KEY_UI_ACCENT, id).apply()
    }

    fun getBackground(context: Context): UiBackground {
        val id = prefs(context).getString(Constants.KEY_UI_BACKGROUND, DEFAULT_BACKGROUND_ID) ?: DEFAULT_BACKGROUND_ID
        return backgrounds.firstOrNull { it.id == id } ?: backgrounds.first()
    }

    fun setBackgroundId(context: Context, id: String) {
        prefs(context).edit().putString(Constants.KEY_UI_BACKGROUND, id).apply()
    }

    fun getCardRadiusDp(context: Context): Int =
        prefs(context).getInt(Constants.KEY_UI_CARD_RADIUS_DP, DEFAULT_CARD_RADIUS_DP)

    fun setCardRadiusDp(context: Context, value: Int) {
        prefs(context).edit().putInt(Constants.KEY_UI_CARD_RADIUS_DP, value).apply()
    }

    fun getTextScale(context: Context): Float =
        prefs(context).getFloat(Constants.KEY_UI_TEXT_SCALE, DEFAULT_TEXT_SCALE)

    fun setTextScale(context: Context, value: Float) {
        prefs(context).edit().putFloat(Constants.KEY_UI_TEXT_SCALE, value).apply()
    }

    /** Honours the person, not just the OS: this is a FocusLock-level choice. */
    fun reducedMotion(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_REDUCED_MOTION, false)

    fun setReducedMotion(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_REDUCED_MOTION, value).apply()
    }

    fun highContrast(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_HIGH_CONTRAST, false)

    fun setHighContrast(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_HIGH_CONTRAST, value).apply()
    }

    // ── Section visibility ────────────────────────────────────────

    fun showKiosk(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_SHOW_KIOSK, true)

    fun setShowKiosk(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_SHOW_KIOSK, value).apply()
    }

    fun showQuickSettings(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_SHOW_QUICK, true)

    fun setShowQuickSettings(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_SHOW_QUICK, value).apply()
    }

    fun showAllowedApps(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_SHOW_ALLOWED_APPS, true)

    fun setShowAllowedApps(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_SHOW_ALLOWED_APPS, value).apply()
    }

    fun showWebButton(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_SHOW_WEB_BUTTON, true)

    fun setShowWebButton(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_SHOW_WEB_BUTTON, value).apply()
    }

    fun showVideoButton(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_SHOW_VIDEO, true)

    fun setShowVideoButton(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_SHOW_VIDEO, value).apply()
    }

    fun showEditButtons(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_SHOW_EDIT_BUTTONS, true)

    fun setShowEditButtons(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_SHOW_EDIT_BUTTONS, value).apply()
    }

    fun showSchedule(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_SHOW_SCHEDULE, true)

    fun setShowSchedule(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_SHOW_SCHEDULE, value).apply()
    }

    fun showStats(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_UI_SHOW_STATS, true)

    fun setShowStats(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_UI_SHOW_STATS, value).apply()
    }

    fun resetToDefaults(context: Context) {
        prefs(context).edit()
            .putString(Constants.KEY_UI_THEME, DEFAULT_THEME_ID)
            .putString(Constants.KEY_UI_FONT, DEFAULT_FONT_ID)
            .putString(Constants.KEY_UI_DENSITY, DEFAULT_DENSITY_ID)
            .putString(Constants.KEY_UI_WALLPAPER, DEFAULT_WALLPAPER_ID)
            .putString(Constants.KEY_UI_ACCENT, DEFAULT_ACCENT_ID)
            .putString(Constants.KEY_UI_BACKGROUND, DEFAULT_BACKGROUND_ID)
            .putInt(Constants.KEY_UI_CARD_RADIUS_DP, DEFAULT_CARD_RADIUS_DP)
            .putFloat(Constants.KEY_UI_TEXT_SCALE, DEFAULT_TEXT_SCALE)
            .putBoolean(Constants.KEY_UI_REDUCED_MOTION, false)
            .putBoolean(Constants.KEY_UI_HIGH_CONTRAST, false)
            .putBoolean(Constants.KEY_UI_SHOW_KIOSK, true)
            .putBoolean(Constants.KEY_UI_SHOW_QUICK, true)
            .putBoolean(Constants.KEY_UI_SHOW_ALLOWED_APPS, true)
            .putBoolean(Constants.KEY_UI_SHOW_WEB_BUTTON, true)
            .putBoolean(Constants.KEY_UI_SHOW_VIDEO, true)
            .putBoolean(Constants.KEY_UI_SHOW_EDIT_BUTTONS, true)
            .putBoolean(Constants.KEY_UI_SHOW_SCHEDULE, true)
            .putBoolean(Constants.KEY_UI_SHOW_STATS, true)
            .apply()
    }

    // ── Resolution ────────────────────────────────────────────────

    /**
     * The one call every screen makes. Combines the user's choices with the
     * bedtime override, so night automatically means the quiet palette without
     * the user losing their daytime theme.
     */
    fun resolve(context: Context): Tokens {
        val bedtimeNow = Bedtime.isActive(context) && Bedtime.forcesDarkTheme(context)
        val base = if (bedtimeNow) {
            themes.firstOrNull { it.id == "nocturne" } ?: getTheme(context)
        } else {
            getTheme(context)
        }

        val accentChoice = getAccent(context)
        val backgroundChoice = getBackground(context).color
        val background = if (backgroundChoice == Color.TRANSPARENT) base.background else backgroundChoice
        val highContrast = highContrast(context)

        val textPrimary = if (highContrast) {
            if (base.isLight) Color.parseColor("#000000") else Color.parseColor("#FFFFFF")
        } else {
            base.textPrimary
        }
        val textSecondary = if (highContrast) {
            blend(textPrimary, background, 0.25f)
        } else {
            base.textSecondary
        }

        return Tokens(
            isLight = base.isLight,
            background = background,
            surface = base.card,
            surfaceAlt = base.cardAlt,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textMuted = blend(textSecondary, background, 0.35f),
            accent = accentChoice.color,
            accentDeep = accentChoice.deepColor ?: blend(accentChoice.color, Color.BLACK, 0.25f),
            onAccent = readableOn(accentChoice.color),
            accentSoft = blend(accentChoice.color, background, 0.82f),
            divider = if (highContrast) blend(textPrimary, background, 0.6f) else base.divider,
            input = base.input,
            track = base.track,
            danger = if (base.isLight) Color.parseColor("#DC2626") else Color.parseColor("#F87171"),
            success = if (base.isLight) Color.parseColor("#15803D") else Color.parseColor("#4ADE80"),
            warning = if (base.isLight) Color.parseColor("#B45309") else Color.parseColor("#FBBF24"),
            typeface = resolveTypeface(context, getFont(context).id),
            monoTypeface = resolveMonoTypeface(context),
            radiusDp = getCardRadiusDp(context).coerceIn(0, 32),
            textScale = getTextScale(context).coerceIn(0.8f, 1.4f),
            density = getDensity(context),
            reducedMotion = reducedMotion(context),
            wallpaperRes = getWallpaper(context).drawableRes,
            dimPercent = if (Bedtime.isActive(context)) Bedtime.dimPercent(context) else 0
        )
    }

    /**
     * The static system typefaces need no Context; the bundled Figtree
     * family does, which is why this can't live in the [fonts] list itself.
     * `ResourcesCompat.getFont` keeps its own cache keyed by resource id, so
     * calling this on every [resolve] (i.e. most screen builds) does not
     * mean re-reading the font file each time.
     */
    /**
     * True when the app's *configured* locale (not just the device's, since
     * a per-app language override may differ from it) is Arabic. Checked
     * ahead of every other font choice below: none of Figtree/System/Mono
     * carry Arabic glyphs, and there is no automatic font-fallback chain
     * configured anywhere in the app, so Arabic text needs its own bundled
     * font regardless of which Latin font the user picked.
     */
    private fun isArabicLocale(context: Context): Boolean {
        val locales = context.resources.configuration.locales
        return !locales.isEmpty && locales[0].language == "ar"
    }

    private fun resolveTypeface(context: Context, fontId: String): Typeface {
        if (isArabicLocale(context)) {
            return androidx.core.content.res.ResourcesCompat.getFont(context, R.font.ibm_plex_sans_arabic_family)
                ?: Typeface.DEFAULT
        }
        return when (fontId) {
            "figtree" -> androidx.core.content.res.ResourcesCompat.getFont(context, R.font.figtree_family)
                ?: Typeface.DEFAULT
            "mono" -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
    }

    private fun resolveMonoTypeface(context: Context): Typeface =
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.ibm_plex_mono_family)
            ?: Typeface.MONOSPACE

    /**
     * Everything a screen's *appearance* depends on, as one comparable string.
     *
     * A screen that comes back from a sub-screen only needs its whole view tree
     * rebuilt if one of these changed; otherwise refilling the column is enough
     * and keeps the reader where they were. Deliberately excludes anything
     * about rules or sessions - those change the content, not the layout.
     */
    fun signature(context: Context): String = listOf(
        getTheme(context).id,
        getAccent(context).id,
        getBackground(context).id,
        getFont(context).id,
        getDensity(context).id,
        getWallpaper(context).id,
        getCardRadiusDp(context).toString(),
        getTextScale(context).toString(),
        highContrast(context).toString(),
        reducedMotion(context).toString(),
        Bedtime.isActive(context).toString(),
        (if (Bedtime.isActive(context)) Bedtime.dimPercent(context) else 0).toString()
    ).joinToString("|")

    /** Black or white, whichever is actually readable on the given colour. */
    fun readableOn(color: Int): Int {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return if (luminance > 0.6) Color.parseColor("#101114") else Color.parseColor("#FFFFFF")
    }

    fun blend(color: Int, towards: Int, amount: Float): Int {
        val ratio = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) * (1 - ratio) + Color.red(towards) * ratio).toInt(),
            (Color.green(color) * (1 - ratio) + Color.green(towards) * ratio).toInt(),
            (Color.blue(color) * (1 - ratio) + Color.blue(towards) * ratio).toInt()
        )
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

/**
 * The names shown on the appearance screen.
 *
 * Extensions rather than members, so the data classes stay free of Context and
 * remain simple value types - the same shape every other localized enum in the
 * app uses, just declared outside the class because these are data classes
 * rather than enums.
 */
fun UiPrefs.UiTheme.label(context: Context): String = context.getString(labelRes)
fun UiPrefs.UiFont.label(context: Context): String = context.getString(labelRes)
fun UiPrefs.UiDensity.label(context: Context): String = context.getString(labelRes)
fun UiPrefs.UiWallpaper.label(context: Context): String = context.getString(labelRes)
fun UiPrefs.UiAccent.label(context: Context): String = context.getString(labelRes)
fun UiPrefs.UiBackground.label(context: Context): String = context.getString(labelRes)
