package com.focuslock.mdm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * The side doors: apps that are not "distractions" by name but are how people
 * get around a locked front door. While a promise runs they are closed as a
 * group, unless the person put one on their always-allowed list on purpose.
 *
 * - every browser except Pinehold's own Safe Browser (Mi Browser, Samsung
 *   Internet, Opera... whatever is installed, found by asking Android which
 *   apps open web links, not from a list that goes stale);
 * - galleries and video players (photos already on the phone);
 * - the Google app, Lens and Gemini (image search and generation);
 * - the Play Store (screenshots, and installing a new way out);
 * - theme and wallpaper stores.
 */
object SideDoors {

    private val GOOGLE = setOf(
        "com.google.android.googlequicksearchbox",
        "com.google.ar.lens",
        "com.google.android.apps.bard",
        "com.google.android.apps.googleassistant"
    )
    private val STORES = setOf(
        "com.android.vending",
        "com.xiaomi.market", "com.xiaomi.mipicks",
        "com.sec.android.app.samsungapps",
        "com.huawei.appmarket",
        "com.heytap.market", "com.oppo.market",
        "com.vivo.appstore"
    )
    private val THEMES = setOf(
        "com.android.thememanager",          // Xiaomi Themes
        "com.samsung.android.themestore",
        "com.huawei.android.thememanager",
        "com.heytap.themestore",
        "com.google.android.apps.wallpaper",
        "com.miui.miwallpaper"
    )

    @Volatile private var cache: Pair<Long, Set<String>>? = null

    /** Closed while a promise runs. Cached for a minute: package queries are not free. */
    fun closed(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        cache?.let { (at, set) -> if (now - at < 60_000L) return set }
        val pm = context.packageManager
        fun handlers(intent: Intent): Set<String> = try {
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) { emptySet() }

        val browsers = handlers(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE))
        val galleries = handlers(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/media/1"), "image/*")) +
            handlers(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/video/media/1"), "video/*"))

        val out = (browsers + galleries + GOOGLE + STORES + THEMES)
            .filter { it != context.packageName && !SystemSurfaces.isCritical(it) }
            .toSet()
        cache = now to out
        return out
    }

    fun isClosed(context: Context, packageName: String): Boolean =
        SessionManager.isEnforcing(context) && packageName in closed(context)

    /** Android 15's Private Space restriction, spelled out so it compiles against older SDKs. */
    const val DISALLOW_ADD_PRIVATE_PROFILE = "no_add_private_profile"

    val hasPrivateSpace: Boolean get() = Build.VERSION.SDK_INT >= 35
}
