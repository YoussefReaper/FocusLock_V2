package com.focuslock.mdm

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque

/**
 * Two small doors that float above whatever app is open, so app blocking alone misses them:
 *
 * - **Picture-in-picture.** A video from a blocked app can keep playing in its little window.
 *   With Pinehold as device owner, the app is suspended for a moment, which closes its window,
 *   then let go again (its own block still stands). Without device owner there is no reliable
 *   way to close another app's window, so it is left alone.
 * - **The keyboard's GIF and sticker search.** A picture search inside the keyboard. When the
 *   keyboard's GIF or Stickers tab is the selected one, the panel is closed with Back. This only
 *   acts on a tab it positively read as selected; if a keyboard update renames it, it does nothing.
 *
 * Both act only while a layer holds the phone.
 */
object FloatingDoors {

    private const val SWEEP_MS = 1500L
    @Volatile private var lastSweep = 0L
    private val main = Handler(Looper.getMainLooper())

    private val GIF_LABELS = setOf("gif", "gifs", "stickers", "sticker", "صور gif", "ملصقات")

    fun sweep(service: AccessibilityService) {
        val now = System.currentTimeMillis()
        if (now - lastSweep < SWEEP_MS) return
        lastSweep = now
        if (!MediaBlur.active(service)) return
        val windows = try { service.windows } catch (_: Exception) { return }
        for (w in windows) {
            when {
                w.isInPictureInPictureMode -> closePip(service, w)
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> if (gifTabSelected(w)) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }

    private fun closePip(service: AccessibilityService, w: AccessibilityWindowInfo) {
        val pkg = w.root?.packageName?.toString() ?: return
        if (pkg == service.packageName || SystemSurfaces.isCritical(pkg)) return
        if (RuleEngine.decide(service, pkg).outcome != GuardOutcome.BLOCK) return
        if (!SetupChecks.isDeviceOwner(service)) return
        val dpm = service.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(service, AdminReceiver::class.java)
        // Never touch a package Pinehold already holds suspended: un-suspending it would undo that.
        if (try { dpm.isPackageSuspended(admin, pkg) } catch (_: Exception) { true }) return
        try {
            dpm.setPackagesSuspended(admin, arrayOf(pkg), true)
            main.postDelayed({ try { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) } catch (_: Exception) { } }, 1200)
        } catch (_: Exception) { }
    }

    /** Pure, for tests: is one of these (label, selected) tabs a selected GIF or sticker tab? */
    fun isGifTab(label: String?, selected: Boolean): Boolean =
        selected && label != null && label.trim().lowercase() in GIF_LABELS

    private fun gifTabSelected(w: AccessibilityWindowInfo): Boolean {
        val root = w.root ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val n = queue.removeFirst(); seen++
            val label = n.contentDescription?.toString() ?: n.text?.toString()
            if (isGifTab(label, n.isSelected)) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }
}
