package com.focuslock.mdm

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/**
 * The content guard.
 *
 * This is the generalisation of the old WhatsApp-only service. It reads the
 * screen of whichever app is in front, matches it against the user's keyword
 * rules, and steps back out of the surfaces they said they did not want, while
 * leaving the rest of the app working. Blocking a whole messenger to avoid its
 * Channels tab is the kind of blunt trade that gets a blocker abandoned.
 *
 * It only ever looks at text. It never types, never taps a control, and never
 * sends anything anywhere.
 *
 * ## Cost
 *
 * An accessibility service is paid for by every app on the phone, not only by
 * this one: while a service asks for content-change events, every app has to
 * build and send one for each change on its screen, and every node this
 * service reads is a round trip into that app's UI thread. This used to ask
 * for everything all the time - with or without a session, with or without a
 * single rule - and then walked up to six hundred nodes on the first event of
 * every burst, on the main thread, while a scroll was still moving. That is
 * the "the whole phone lags" the service was reported for.
 *
 * Three things keep it cheap now:
 *
 *  - **Scope.** Whenever there is nothing to guard - no session, or no rule -
 *    the service tells Android it wants window changes only (see
 *    [applyEventScope]), so apps stop generating content events for it at all.
 *  - **Asking the app to search.** Each phrase is looked up with
 *    findAccessibilityNodeInfosByText, which the app answers from its own view
 *    tree in one round trip. The node-by-node walk - hundreds of round trips on
 *    a busy screen - is kept only for view-id matching, and throttled.
 *  - **Batching.** A burst of events is read quickly once, then once more when
 *    the screen settles, instead of once per event.
 *  - **A worker thread.** All reading runs off the main thread.
 */
class ContentGuardService : AccessibilityService() {

    /** A rule with its matching strings prepared once, not once per node. */
    private class Prepared(
        val rule: KeywordRule,
        val phrase: String,
        val idPhrase: String?,
        /** Shorts and Reels: only the player's own view ids count, never the word on a tab or in a title. */
        val playerIds: List<String> = emptyList()
    ) {
        val idOnly: Boolean get() = playerIds.isNotEmpty()
    }

    /**
     * The view ids of the short-video players themselves. Matching these, and
     * not the words "shorts" or "reels", is what keeps the rest of the app
     * usable: the home feed, search and normal videos stay open, and only the
     * vertical player is stepped back out of.
     */
    private val PLAYER_IDS: Map<String, List<String>> = mapOf(
        KeywordRules.GROUP_SHORTS to listOf("reel_player_page_container", "reel_recycler", "reel_watch_player", "shorts_player_container"),
        KeywordRules.GROUP_REELS to listOf("clips_viewer_view_pager", "clips_video_container", "clips_viewer_root", "reels_viewer_container", "spotlight_container")
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private var workerThread: HandlerThread? = null

    private var lastActionAt = 0L

    // Written on the main thread, read on the worker.
    @Volatile
    private var pendingPackage: String? = null

    /** When the current burst began, so a screen that never settles is still read. */
    @Volatile
    private var burstStartedAt = 0L

    /** Whether a read is already queued on the worker. */
    @Volatile
    private var scanQueued = false

    /** When the worker last read the screen, and last walked the whole tree. */
    @Volatile
    private var lastScanAt = 0L

    @Volatile
    private var lastWalkAt = 0L



    /** A new window arrived since the last read; it is always walked in full. */
    @Volatile
    private var walkRequested = false

    private var lastPackage: String? = null
    private var lastRememberedTitle: String? = null
    private var lastLoggedTitle: String? = "\u0000"

    private var cachedRules: List<Prepared> = emptyList()
    private var cachedRevision = -1L
    private var cachedExceptions: List<String> = emptyList()

    /** Which event scope is currently asked of Android. See [applyEventScope]. */
    private var fullScope: Boolean? = null

    private val scan = Runnable { scanNow() }

    private val onPolicyChange: () -> Unit = { applyEventScope() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Migration.run(this)
        workerThread = HandlerThread("focuslock-content-guard").also {
            it.start()
            worker = Handler(it.looper)
        }
        PolicySync.addListener(onPolicyChange)
        fullScope = null
        applyEventScope()
    }

    override fun onDestroy() {
        PolicySync.removeListener(onPolicyChange)
        workerThread?.quitSafely()
        workerThread = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Nothing buffered, nothing to abandon.
    }

    // ── Scope ─────────────────────────────────────────────────────

    /**
     * Whether there is anything on the phone for this service to read.
     *
     * The session gate sits here as it does on every other rule (see
     * [SessionManager.isEnforcing]). TestMode counts: "test the block" used to
     * be silently a no-op for every keyword, Shorts, Reels and adult rule,
     * because this read isActive directly and a test deliberately never sets
     * that.
     */
    /** The owner's AI-site rule (personal build) runs always, but only inside browsers. */
    private fun hasWork(): Boolean = Personal.on || regularWork()

    private fun regularWork(): Boolean {
        if (!CapabilityRegistry.isEnabled(this, Capabilities.CONTENT_GUARD)) return false
        val override = TestMode.overrideFor(this)
        if (!Standalone.mayActNow(this, override)) return false
        if (!SessionManager.isGuardActive(this, override)) return false
        return rules().isNotEmpty() || TelegramGuard.isEnabled(this)
    }

    /**
     * Asks Android for content events only while something needs them.
     *
     * Window-state changes are always kept: they are rare (one per screen, not
     * one per scroll frame), and they are how a session that starts or ends
     * without a policy write - a test running out, a session expiring on its
     * clock - is noticed on the next app switch. Every write goes through
     * [PolicySync], whose listener lands here too.
     */
    private fun applyEventScope() {
        val wanted = try {
            hasWork()
        } catch (_: Exception) {
            true
        }
        if (fullScope == wanted) return
        val info = serviceInfo ?: return
        if (wanted) {
            // Scrolls too: a scroll is what brings a banned phrase into view,
            // and not every app reports it as a content change.
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = ACTIVE_NOTIFICATION_TIMEOUT_MS
        } else {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.flags = 0
            info.notificationTimeout = IDLE_NOTIFICATION_TIMEOUT_MS
        }
        try {
            serviceInfo = info
            fullScope = wanted
        } catch (_: Exception) {
            // The service is going away; the next connection sets it again.
        }
        if (!wanted) {
            worker.removeCallbacks(scan)
            pendingPackage = null
            GuardState.clear()
        }
    }

    // ── Events ────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Cheap: every lookup behind this is cached against the policy
        // revision. It is what notices a session that ended on its own clock,
        // or began without a write, and narrows or widens the scope.
        applyEventScope()
        if (fullScope != true) return
        // Battery: when the personal rule is the only reason to be awake, every screen that
        // is not a browser is dropped here, before any work is queued.
        if (Personal.on && event.packageName?.toString() !in BROWSERS && !regularWork()) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return

        // The system's own surfaces are not content to guard. The status bar,
        // the recents switcher and the permission dialogs all fire events with
        // whatever text is behind them, and reacting to those was what produced
        // the "System UI blocked" reports: a back-press aimed at a shade that
        // was never the thing the rule meant.
        if (SystemSurfaces.isCritical(packageName)) {
            GuardState.clear()
            return
        }

        // When to read.
        //
        // The first event after a quiet spell is read almost at once: that is
        // the moment something new appeared, and it is what the person sees
        // first. Waiting for the screen to settle before the first read - which
        // is what this did - put a banned tab on screen for the settle time
        // plus the walk, and on WhatsApp's Updates tab that was long enough to
        // read "Find channels" and start scrolling. Events that keep coming
        // after that push one more read to when the screen goes still, never
        // later than MAX_WAIT_MS from the start of the burst.
        //
        // Every change is treated the same, including an app merely redrawing
        // an animation. Slowing those down was tried and measured: it saved
        // about 1% of a core with WhatsApp open, and it would have delayed
        // exactly the case that matters in Telegram, which opens a chat inside
        // the same window with no "new window" event to tell it apart.
        val now = SystemClock.elapsedRealtime()
        val newWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (newWindow) walkRequested = true
        pendingPackage = packageName
        val sinceScan = now - lastScanAt

        val delay = if (!scanQueued) {
            burstStartedAt = now
            if (newWindow || sinceScan >= QUIET_MS) LEADING_DELAY_MS else minOf(SETTLE_MS, maxOf(0L, MAX_WAIT_MS - sinceScan))
        } else {
            val waited = now - burstStartedAt
            if (waited >= MAX_WAIT_MS) 0L else minOf(SETTLE_MS, MAX_WAIT_MS - waited)
        }
        worker.removeCallbacks(scan)
        scanQueued = true
        worker.postDelayed(scan, delay)
    }

    // ── Reading (worker thread) ───────────────────────────────────

    private fun scanNow() {
        scanQueued = false
        val eventPackage = pendingPackage ?: return
        pendingPackage = null
        lastScanAt = SystemClock.elapsedRealtime()
        if (!hasWork()) return

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        try {
            // The window in front now, not the one that fired the event: by
            // the time a burst settles the person may have moved on.
            val packageName = root.packageName?.toString() ?: eventPackage
            if (packageName == applicationContext.packageName || SystemSurfaces.isCritical(packageName)) return

            // Telegram is handled by allowlist rather than by phrase: naming
            // every channel you do not want is endless, naming the few you do
            // is not.
            if (TelegramGuard.isTelegram(packageName) && TelegramGuard.isEnabled(this)) {
                val title = TelegramGuard.readChatTitle(root)
                // Logged when it changes: Telegram is the one app whose screen
                // this reads structurally rather than by phrase, and "the guard
                // never reacts" has to be answerable from a log.
                if (title != lastLoggedTitle) {
                    lastLoggedTitle = title
                    Log.i(TAG, "Telegram chat title: " + (title ?: "(none on screen)"))
                }
                if (title != null) {
                    // Written only when it changes - it is shown in the
                    // Telegram settings screen, not needed on every read.
                    if (title != lastRememberedTitle) {
                        lastRememberedTitle = title
                        TelegramGuard.rememberTitle(this, title)
                    }
                    if (TelegramGuard.blocks(this, title)) {
                        lastPackage = packageName
                        main.post { act(GuardAction.STEP_BACK, packageName, title) }
                        return
                    }
                }
            }

            val rules = rules().filter { it.rule.appliesEverywhere || packageName in it.rule.packages }
            if (rules.isEmpty()) {
                if (lastPackage == packageName) GuardState.clear()
                lastPackage = packageName
                return
            }
            lastPackage = packageName

            val hit = try {
                findTextHit(root, rules) ?: walkIfDue(root, rules, packageName)
            } catch (_: Exception) {
                null
            }
            if (hit == null) {
                GuardState.clear()
                return
            }

            GuardState.record(packageName, hit.phrase, hit.action)
            main.post { act(hit.action, packageName, hit.phrase) }
        } finally {
            recycle(root)
        }
    }

    // ── Rules ─────────────────────────────────────────────────────

    private val BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.opera.browser", "com.brave.browser", "com.mi.globalbrowser", "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
    )

    @Synchronized
    private fun rules(): List<Prepared> {
        val revision = PolicySync.revision()
        if (revision != cachedRevision) {
            val seenPlayers = HashSet<String>()
            cachedRules = KeywordRules.activeRules(this).mapNotNull { rule ->
                val players = PLAYER_IDS[rule.group]
                if (players != null) {
                    // One entry per guard, whatever phrases were seeded for it.
                    return@mapNotNull if (seenPlayers.add(rule.group!!)) Prepared(rule, rule.group, null, players) else null
                }
                val phrase = rule.phrase.lowercase(Locale.US)
                Prepared(
                    rule = rule,
                    phrase = phrase,
                    // View ids are only worth matching for phrases long enough
                    // not to hit by accident. See matchesId.
                    idPhrase = if (phrase.length >= 4) phrase.replace(" ", "_") else null
                )
            }.filter { it.phrase.isNotBlank() }
            cachedExceptions = KeywordRules.exceptions(this)
            cachedRevision = revision
        }
        return cachedRules
    }

    /**
     * The fast path: each phrase looked up by the app itself.
     *
     * findAccessibilityNodeInfosByText is answered inside the app's process
     * from its own view tree - case-insensitive containment over text and
     * content descriptions - so a whole screen costs one round trip per
     * phrase rather than one per node. Every result is still checked against
     * the exceptions and the normalised text before it counts.
     */
    private fun findTextHit(root: AccessibilityNodeInfo, rules: List<Prepared>): KeywordRule? {
        for (prepared in rules) {
            if (prepared.idOnly) continue
            val found = try {
                root.findAccessibilityNodeInfosByText(prepared.phrase)
            } catch (_: Exception) {
                null
            } ?: continue
            var hit: KeywordRule? = null
            for (node in found) {
                // Only what is actually on screen. The search covers the whole
                // tree, and apps keep neighbouring pages laid out off-screen -
                // WhatsApp's Updates tab exists, invisible, while you are on
                // Chats - so without this the phrase would match a tab nobody
                // had opened.
                if (hit == null && node.isVisibleToUser) {
                    val text = normalize(node.text)
                    val description = normalize(node.contentDescription)
                    val excepted = isException(text) || isException(description)
                    if (!excepted && (matches(text, prepared.phrase) || matches(description, prepared.phrase))) {
                        hit = prepared.rule
                    }
                }
                recycle(node)
            }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * The slow path, for what the fast one cannot see: view ids (Shorts has no
     * word "shorts" on screen, only in its ids) and apps whose UI toolkit does
     * not answer a text search.
     *
     * Every new window is walked at once. Within a window the walk runs at most
     * every [WALK_INTERVAL_MS], and a read that skips it queues one for when
     * it is due, so a screen that goes quiet is still walked in full.
     */
    private fun walkIfDue(root: AccessibilityNodeInfo, rules: List<Prepared>, packageName: String): KeywordRule? {
        val now = SystemClock.elapsedRealtime()
        val since = now - lastWalkAt
        if (!walkRequested && since < WALK_INTERVAL_MS) {
            if (!scanQueued) {
                pendingPackage = packageName
                scanQueued = true
                worker.postDelayed(scan, WALK_INTERVAL_MS - since)
            }
            return null
        }
        walkRequested = false
        lastWalkAt = now
        return findHit(root, rules)
    }

    private fun findHit(root: AccessibilityNodeInfo, rules: List<Prepared>): KeywordRule? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        // The root is recycled by the caller; everything obtained here is
        // recycled here.
        for (i in 0 until root.childCount) root.getChild(i)?.let { queue.add(it) }
        checkNode(root, rules)?.let { return it }
        var visited = 1

        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited += 1
                try {
                    checkNode(node, rules)?.let { return it }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                } finally {
                    recycle(node)
                }
            }
        } finally {
            while (queue.isNotEmpty()) recycle(queue.removeFirst())
        }
        return null
    }

    private fun checkNode(node: AccessibilityNodeInfo, rules: List<Prepared>): KeywordRule? {
        // Same rule as the fast path: off-screen text is not something seen.
        // The walk still descends into it; only the match is skipped.
        if (!node.isVisibleToUser) return null
        val text = normalize(node.text)
        val description = normalize(node.contentDescription)
        if (isException(text) || isException(description)) return null
        val viewId = node.viewIdResourceName?.lowercase(Locale.US)
        for (prepared in rules) {
            if (prepared.idOnly) {
                if (viewId != null && prepared.playerIds.any { viewId.endsWith(":id/$it") }) return prepared.rule
                continue
            }
            if (matches(text, prepared.phrase) ||
                matches(description, prepared.phrase) ||
                matchesId(viewId, prepared.idPhrase)
            ) {
                return prepared.rule
            }
        }
        return null
    }

    private fun matches(value: String?, phrase: String): Boolean {
        if (value.isNullOrEmpty()) return false
        return value.contains(phrase)
    }

    /**
     * View ids are the reliable signal for surfaces whose label is an icon.
     * A YouTube Shorts player has no word "shorts" on it, but its container id
     * says so, which is why this looks at ids as well as visible text.
     */
    private fun matchesId(viewId: String?, idPhrase: String?): Boolean {
        if (viewId.isNullOrEmpty() || idPhrase == null) return false
        return viewId.contains(idPhrase)
    }

    private fun isException(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        return cachedExceptions.any { KeywordRules.coveredByException(value, it) }
    }

    private fun normalize(text: CharSequence?): String? {
        val raw = text?.toString()?.trim()?.lowercase(Locale.US) ?: return null
        if (raw.isEmpty()) return null
        return WHITESPACE.replace(raw, " ")
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        // A no-op from Android 13; below it, nodes come from a pool and an
        // unreturned one is a leak for every read.
        try {
            node.recycle()
        } catch (_: Exception) {
            // Already recycled.
        }
    }

    // ── Acting (main thread) ──────────────────────────────────────

    private fun act(action: GuardAction, packageName: String, phrase: String) {
        // Same demo gate as AppBlockerService and FocusNotificationService:
        // the guard may still detect a hit (GuardState reflects it on screen
        // for the curious), but it never presses GLOBAL_ACTION_BACK for real.
        if (!BuildConfig.ENFORCEMENT) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return
        lastActionAt = now
        // One line per action, so how quickly the guard reacts can be measured
        // on a real phone rather than guessed.
        Log.i(TAG, action.name + " in " + packageName + " for \"" + phrase + "\", " +
            (now - burstStartedAt) + "ms after the screen changed")

        when (action) {
            GuardAction.STEP_BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                // A silent back-press looks like the phone glitched. Naming
                // exactly what was seen and why is the whole reason this isn't
                // a full block screen - a step-back is meant to stay quiet,
                // not stay unexplained.
                FocusDialog.toast(this, Copy.contentGuardHeadline(this, packageName, phrase))
            }
            GuardAction.CLOSE_APP -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                showIntercept(packageName, phrase)
            }
            GuardAction.NUDGE -> {
                // The gentlest tier: nothing leaves the screen, but the person
                // still gets told what was witnessed, the same as every other
                // tier - GuardState alone was never actually shown anywhere.
                FocusDialog.toast(this, Copy.contentGuardHeadline(this, packageName, phrase))
            }
        }
    }

    private fun showIntercept(packageName: String, phrase: String) {
        if (!CapabilityRegistry.isEnabled(this, Capabilities.CONTENT_GUARD)) return
        try {
            startActivity(
                Intent(this, InterceptActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(InterceptActivity.EXTRA_PACKAGE, packageName)
                    putExtra(InterceptActivity.EXTRA_SOURCE, "contentGuard")
                    putExtra(InterceptActivity.EXTRA_PHRASE, phrase)
                    // A step-back is reversible and is the whole thing being
                    // demonstrated, so a test performs it for real - it just
                    // says so, rather than letting the screen look like a
                    // session that isn't running.
                    putExtra(
                        InterceptActivity.EXTRA_TEST_MODE,
                        TestMode.isActive(this@ContentGuardService)
                    )
                }
            )
        } catch (_: Exception) {
            // If we cannot show the screen, the back press already happened.
        }
    }

    companion object {
        /**
         * Longer than a screen transition, so the read that lands while the
         * old screen is still animating away cannot press Back a second time -
         * which, one level above a banned tab, would close the app.
         */
        private const val ACTION_COOLDOWN_MS = 900L

        private const val TAG = "FocusLockContentGuard"

        /** The first read of a burst: long enough for the new frame to land. */
        private const val LEADING_DELAY_MS = 60L

        /** How long without a read before an event counts as the start of something new. */
        private const val QUIET_MS = 700L

        /** How long a screen has to be still before the follow-up read. */
        private const val SETTLE_MS = 250L

        /**
         * The longest a follow-up read waits for a screen that never settles.
         *
         * Measured on a real phone: at 700ms, scrolling WhatsApp's Updates tab
         * down to "Find channels" was stepped back from about 1.1s after the
         * swipe - the first read came before the phrase had scrolled into
         * view, and the next waited out this whole window. Reads are cheap now
         * (see findTextHit), so a scroll is read about three times a second.
         */
        private const val MAX_WAIT_MS = 300L


        /** The full node-by-node walk, at most this often within one window. */
        private const val WALK_INTERVAL_MS = 3_000L

        private const val MAX_NODES = 600

        /** Android's own batching of events to this service, while guarding. */
        private const val ACTIVE_NOTIFICATION_TIMEOUT_MS = 200L

        /** And while there is nothing to guard. */
        private const val IDLE_NOTIFICATION_TIMEOUT_MS = 500L

        private val WHITESPACE = Regex("\\s+")
    }
}
