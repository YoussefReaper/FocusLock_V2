package com.focuslock.mdm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * The side doors: apps that are not "distractions" by name but can be a way around a
 * locked front door. Each group closes during a layer only if the person switched it on
 * (Rules, Side doors); nothing here is closed by default.
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

    /**
     * The groups a person can choose to close during a layer. All are off until switched on:
     * a gallery or the Play Store is ordinary daily life for most people, so closing them is
     * their call, never a default. Picture notifications fold only when that switch is on too.
     */
    enum class Group(val key: String) { BROWSERS("browsers"), GALLERY("gallery"), GOOGLE("google"), STORES("stores"), THEMES("themes"), PICTURE_NOTIFICATIONS("pictures"), KEYBOARD_GIFS("gifs") }

    private fun k(g: Group) = "side_door_" + g.key
    fun isOn(c: Context, g: Group): Boolean = FocusStore.getBool(c, k(g), false)
    fun setOn(c: Context, g: Group, v: Boolean) { FocusStore.setBool(c, k(g), v); cache = null }

    @Volatile private var cache: Pair<Long, Set<String>>? = null

    /** Closed while a layer runs: only the groups the person switched on. Cached for a minute. */
    fun closed(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        cache?.let { (at, set) -> if (now - at < 60_000L) return set }
        val pm = context.packageManager
        fun handlers(intent: Intent): Set<String> = try {
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) { emptySet() }

        val out = HashSet<String>()
        if (isOn(context, Group.BROWSERS)) out += handlers(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE))
        if (isOn(context, Group.GALLERY)) {
            out += handlers(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/media/1"), "image/*"))
            out += handlers(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/video/media/1"), "video/*"))
        }
        if (isOn(context, Group.GOOGLE)) out += GOOGLE
        if (isOn(context, Group.STORES)) out += STORES
        if (isOn(context, Group.THEMES)) out += THEMES
        val result = out.filter { it != context.packageName && !SystemSurfaces.isCritical(it) }.toSet()
        cache = now to result
        return result
    }

    fun isClosed(context: Context, packageName: String): Boolean =
        SessionManager.isEnforcing(context) && packageName in closed(context)

    /** Android 15's Private Space restriction, spelled out so it compiles against older SDKs. */
    const val DISALLOW_ADD_PRIVATE_PROFILE = "no_add_private_profile"

    val hasPrivateSpace: Boolean get() = Build.VERSION.SDK_INT >= 35
}
