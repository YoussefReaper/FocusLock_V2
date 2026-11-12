package com.focuslock.mdm

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import java.util.ArrayDeque

/**
 * Experiment: inside an allowed Telegram chat, pictures and videos are covered with dark cards
 * while a layer holds the phone. Telegram draws its media as views it labels "Photo", "Video",
 * "GIF" or "Sticker"; those are the only ones covered, so text is never hidden. When Telegram
 * renames them the experiment simply stops covering (it fails open), which the person sees.
 *
 * Off unless switched on in the Telegram card, because it reads positions out of another app's
 * screen and Telegram changes often.
 */
object TelegramCover {

    private const val KEY_ON = "telegram_cover_on"
    private val MEDIA_WORDS = listOf("photo", "video", "gif", "sticker", "صورة", "فيديو", "ملصق")

    fun isOn(c: Context): Boolean = FocusStore.getBool(c, KEY_ON, false)
    fun setOn(c: Context, v: Boolean) = FocusStore.setBool(c, KEY_ON, v)

    /** Pure, for tests: does this label name a picture or a video? */
    fun isMedia(label: String?): Boolean {
        val l = label?.trim()?.lowercase() ?: return false
        return l.length <= 60 && MEDIA_WORDS.any { l == it || l.startsWith("$it,") || l.startsWith("$it ") }
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var host: FrameLayout? = null
    private var lastSig = ""

    fun update(service: AccessibilityService, root: AccessibilityNodeInfo?) {
        if (root == null || !isOn(service) || !MediaBlur.active(service) || !TelegramGuard.isTelegram(root.packageName?.toString() ?: "")) {
            if (host != null) main.post { clear(service) }; return
        }
        val min = FocusUi.dp(service, 72)
        val boxes = ArrayList<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 600 && boxes.size < 12) {
            val n = queue.removeFirst(); seen++
            if (isMedia(n.contentDescription?.toString())) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.width() >= min && r.height() >= min) boxes += r
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        val sig = boxes.joinToString("|") { it.flattenToString() }
        if (sig == lastSig) return
        lastSig = sig
        main.post { draw(service, boxes) }
    }

    private fun draw(service: AccessibilityService, boxes: List<Rect>) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        if (boxes.isEmpty()) { clear(service); return }
        val frame = host ?: FrameLayout(service).also { f ->
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            try { wm.addView(f, lp); host = f } catch (_: Exception) { return }
        }
        frame.removeAllViews()
        val loc = IntArray(2).also { frame.getLocationOnScreen(it) }
        for (r in boxes) {
            val v = View(service)
            v.background = GradientDrawable().apply { setColor(Color.parseColor("#F00A0915")); cornerRadius = FocusUi.dpf(service, 12) }
            frame.addView(v, FrameLayout.LayoutParams(r.width(), r.height()).apply {
                leftMargin = r.left - loc[0]; topMargin = r.top - loc[1]
            })
        }
    }

    fun clear(service: AccessibilityService) {
        lastSig = ""
        val f = host ?: return
        host = null
        try { service.getSystemService(WindowManager::class.java)?.removeView(f) } catch (_: Exception) { }
    }
}
