package com.focuslock.mdm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val isSystem: Boolean,
    val isLaunchable: Boolean
)

/**
 * Knows what is on the phone and roughly what kind of thing each app is.
 *
 * Categorisation is a three-step guess: the user's own override wins, then
 * Android's declared category, then a name heuristic. The user can always
 * correct it, and their correction is what every category rule reads afterwards.
 */
object AppCatalog {

    private const val KEY_CATEGORY_OVERRIDES = "app_category_overrides_json"

    @Volatile
    private var cache: List<InstalledApp>? = null

    @Volatile
    private var cacheStamp: Long = 0L

    private const val CACHE_TTL_MS = 30_000L

    private val labelCache = HashMap<String, String>()

    /**
     * Decoded launcher icons, kept as ConstantState.
     *
     * getApplicationIcon reads and decodes from the app's APK every call. The
     * Library grid asks for up to sixty of them on the UI thread each time it
     * renders, which is where its jank came from. Caching the ConstantState
     * rather than the Drawable matters: a Drawable carries mutable bounds and a
     * callback, so handing the same instance to several ImageViews makes them
     * fight over it. newDrawable() is cheap and gives each view its own.
     */
    private val iconCache = HashMap<String, Drawable.ConstantState?>()

    fun invalidate() {
        cache = null
        labelCache.clear()
        iconCache.clear()
    }

    fun all(context: Context): List<InstalledApp> {
        val now = System.currentTimeMillis()
        val cached = cache
        if (cached != null && now - cacheStamp < CACHE_TTL_MS) return cached

        val pm = context.packageManager
        val overrides = FocusStore.getStringMap(context, KEY_CATEGORY_OVERRIDES)

        // Hidden apps included, on purpose.
        //
        // getInstalledApplications(0) leaves out every app hidden with
        // setApplicationHidden - and FocusLock is what hides them. So an app a
        // category rule had just hidden stopped being in its category, stopped
        // counting as blocked, and was revealed by the next sync; back in the
        // list, it was hidden again by the sync after. Every flip is a
        // package-removed or package-added broadcast to every app on the phone
        // that listens for one - the launcher, the messengers, Play services -
        // and on a real phone with a Sanctuary session it ran continuously: 34
        // apps swinging between hidden and shown every 15 to 30 seconds for the
        // whole session. That was the heat.
        //
        // MATCH_UNINSTALLED_PACKAGES is what makes hidden packages visible;
        // it also returns packages removed with "keep data", which FLAG_INSTALLED
        // filters back out.
        val apps = try {
            pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .filter { (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0 }
        } catch (_: Exception) {
            emptyList<ApplicationInfo>()
        }.let { infos ->
            val launchers = launcherPackages(context)
            infos.mapNotNull { info -> build(pm, info, overrides, launchers) }
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }

        cache = apps
        cacheStamp = now
        return apps
    }

    /**
     * Every package with a home-screen icon, hidden ones included.
     *
     * One query for the whole phone, rather than getLaunchIntentForPackage per
     * app - which is also blind to hidden apps, so a hidden app read as "not
     * launchable" the moment it was hidden, for the same reason as above.
     */
    private fun launcherPackages(context: Context): Set<String> = try {
        context.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        ).mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun build(
        pm: PackageManager,
        info: ApplicationInfo,
        overrides: Map<String, String>,
        launchers: Set<String>
    ): InstalledApp? {
        return run {
            try {
                val pkg = info.packageName ?: return@run null
                val label = try {
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg
                }
                val launchable = pkg in launchers
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                InstalledApp(
                    packageName = pkg,
                    label = label,
                    category = resolveCategory(pkg, info, overrides),
                    isSystem = isSystem,
                    isLaunchable = launchable
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Apps a person can actually tap: what every picker should show. */
    fun launchable(context: Context): List<InstalledApp> =
        all(context).filter { it.isLaunchable && it.packageName != context.packageName }

    /**
     * Every package on the phone, *including* the ones FocusLock has hidden.
     *
     * Separate from [all] on purpose. `getInstalledApplications(0)` omits a
     * package hidden by `setApplicationHidden` - that is what hiding *means* -
     * so it can never be the universe for a sweep whose entire job is to find
     * apps we hid and forgot about. This is the one call that can still see
     * them, and it is deliberately not cached: it is only used by the recovery
     * sweeps, which run rarely, and a stale answer there is exactly the bug it
     * exists to fix.
     */
    fun allPackageNames(context: Context): Set<String> = try {
        context.packageManager.getInstalledApplications(
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        ).mapNotNull { it.packageName }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    fun find(context: Context, packageName: String): InstalledApp? =
        all(context).firstOrNull { it.packageName == packageName }

    fun label(context: Context, packageName: String): String {
        labelCache[packageName]?.let { return it }
        val resolved = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        labelCache[packageName] = resolved
        return resolved
    }

    fun icon(context: Context, packageName: String): Drawable? {
        synchronized(iconCache) {
            if (iconCache.containsKey(packageName)) {
                return iconCache[packageName]?.newDrawable(context.resources)
            }
        }
        val loaded = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
        synchronized(iconCache) { iconCache[packageName] = loaded?.constantState }
        // First caller gets the original; everyone after gets their own copy.
        return loaded
    }

    /**
     * Whether the package is on the phone, *including* one FocusLock itself has
     * hidden or suspended.
     *
     * The flags are the whole point. `getPackageInfo(pkg, 0)` throws
     * [android.content.pm.PackageManager.NameNotFoundException] for a package
     * hidden by `DevicePolicyManager.setApplicationHidden`, so a plain
     * existence check reports "not installed" for an app FocusLock hid a
     * moment ago. Every caller that asks "can I act on this package" - the
     * suspension sweeps, the hidden-app reconciliation, the teardown that puts
     * apps back - then treats a hidden app as gone, which is how hiding got
     * into a reveal/hide loop that never settled. See [KioskPolicy.syncHiddenApps].
     */
    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(
            packageName,
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        )
        true
    } catch (_: Exception) {
        false
    }

    // ── Categories ────────────────────────────────────────────────

    fun categoryOf(context: Context, packageName: String): AppCategory {
        find(context, packageName)?.let { return it.category }
        val overrides = FocusStore.getStringMap(context, KEY_CATEGORY_OVERRIDES)
        overrides[packageName]?.let { return AppCategory.fromId(it) }
        return guessFromName(packageName) ?: AppCategory.OTHER
    }

    /**
     * An app's category decides whether a category rule catches it, so
     * re-labelling it *out* of a blocked category mid-session is a bypass in
     * disguise rather than a correction, and waits. Moving one *into* a
     * stricter category is the person tightening their own rules and goes
     * through, the same as any other tightening.
     */
    fun setCategoryOverride(context: Context, packageName: String, category: AppCategory): Boolean {
        if (!SessionLock.allows(context, categoryDirection(context, packageName, category))) return false
        val overrides = FocusStore.getStringMap(context, KEY_CATEGORY_OVERRIDES).toMutableMap()
        overrides[packageName] = category.id
        FocusStore.setStringMap(context, KEY_CATEGORY_OVERRIDES, overrides)
        invalidate()
        PolicySync.request(context, "category:" + packageName)
        return true
    }

    /**
     * Whether re-labelling [packageName] as [category] leaves it at least as
     * restricted as it is now, judged by the policy each category carries.
     */
    private fun categoryDirection(
        context: Context,
        packageName: String,
        category: AppCategory
    ): EditDirection {
        val before = AppRules.categoryPolicy(context, categoryOf(context, packageName))
            ?: AppPolicy.ALLOW
        val after = AppRules.categoryPolicy(context, category) ?: AppPolicy.ALLOW
        return if (after.strictness >= before.strictness) EditDirection.TIGHTEN else EditDirection.LOOSEN
    }

    fun clearCategoryOverride(context: Context, packageName: String): Boolean {
        val natural = guessFromName(packageName) ?: AppCategory.OTHER
        if (!SessionLock.allows(context, categoryDirection(context, packageName, natural))) return false
        val overrides = FocusStore.getStringMap(context, KEY_CATEGORY_OVERRIDES).toMutableMap()
        overrides.remove(packageName)
        FocusStore.setStringMap(context, KEY_CATEGORY_OVERRIDES, overrides)
        invalidate()
        PolicySync.request(context, "category:" + packageName)
        return true
    }

    /**
     * The apps a category rule reaches: ones with a home-screen icon.
     *
     * A category is something a person picks in a list of apps they open.
     * Without this it also swept in whatever background component happened to
     * share the category, and on a real phone a Sanctuary session hid and
     * suspended WebView (which every in-app browser, FocusLock's own included,
     * runs on), TalkBack, Switch Access, text-to-speech, the print spooler, the
     * package verifier, Android System Intelligence and MIUI's power manager.
     * None of those is a distraction; hiding them just breaks the phone. An
     * explicit per-app rule still reaches any package, launcher icon or not.
     */
    fun packagesInCategory(context: Context, category: AppCategory): List<String> =
        all(context).filter { it.category == category && it.isLaunchable }.map { it.packageName }

    private fun resolveCategory(
        packageName: String,
        info: ApplicationInfo,
        overrides: Map<String, String>
    ): AppCategory {
        overrides[packageName]?.let { return AppCategory.fromId(it) }

        Seed.categoryOverrides[packageName]?.let { return it }

        guessFromName(packageName)?.let { return it }

        val declared = try {
            info.category
        } catch (_: Exception) {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        when (declared) {
            ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
            ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.VIDEO
            ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAMES
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppCategory.PRODUCTIVITY
            ApplicationInfo.CATEGORY_NEWS -> return AppCategory.BROWSING
            // Not VIDEO: that put every music player - Musicolet, a podcast
            // app - on the list of things suggested for blocking.
            ApplicationInfo.CATEGORY_AUDIO -> return AppCategory.OTHER
        }

        if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return AppCategory.SYSTEM
        return AppCategory.OTHER
    }

    private fun guessFromName(packageName: String): AppCategory? {
        val lower = packageName.lowercase(Locale.US)
        Seed.categoryHints.forEach { hint ->
            if (lower.contains(hint.first)) return hint.second
        }
        return null
    }

    /**
     * Apps the phone would be unusable without. Used to seed the always-allowed
     * list and to warn when someone is about to lock away their own dialler.
     */
    fun detectEssentials(context: Context): List<String> {
        val installed = all(context).map { it.packageName }.toSet()
        val found = Seed.essentials.filter { it in installed }.toMutableList()

        val pm = context.packageManager
        listOfNotNull(
            resolveDefault(pm, android.content.Intent(android.content.Intent.ACTION_DIAL)),
            resolveDefault(pm, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0")))
        ).forEach { pkg -> if (pkg !in found) found.add(pkg) }

        return found.distinct()
    }

    private fun resolveDefault(pm: PackageManager, intent: android.content.Intent): String? = try {
        pm.resolveActivity(intent, 0)?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }
}
