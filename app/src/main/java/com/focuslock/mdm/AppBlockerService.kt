package com.focuslock.mdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The enforcement loop.
 *
 * It does exactly two things: work out what is in front, and ask [RuleEngine]
 * what should happen about it. Every judgement about allow-lists, schedules,
 * bedtime, limits and breaks now lives in the engine reading user-owned stores,
 * so this file no longer contains any policy of its own.
 *
 * It re-reads policy the moment [PolicySync] ticks, which is what lets a switch
 * flipped in the Capabilities screen take effect without restarting anything.
 */
class AppBlockerService : Service() {

    private data class ForegroundApp(val packageName: String, val className: String?)

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var blockerOverlay: View? = null
    private var blockerOverlayMessage: TextView? = null
    private var blockerWindowManager: WindowManager? = null

    @Volatile
    private var bedtimeDimOverlay: View? = null
    private var lastBedtimeDimPercent = 0

    private var lastInterceptAt = 0L
    private var lastInterceptPackage: String? = null
    private var lastBringToFrontAt = 0L
    private var lastRecoveryLaunchAt = 0L

    /** When FocusLock itself was last genuinely in front. See the grace in
     *  handleForeground: it is what tells a real app launch apart from the
     *  stale event left behind by one of our own screen transitions. */
    private var lastOwnForegroundAt = 0L
    private var lastPolicyRevision = -1L
    private var guardWasOn = false
    private var guardDroppedAt = 0L
    private var lastPolicyRefreshAt = 0L
    private var lastNotificationText = ""

    /** The app the earned pool is currently being billed for, and since when. */
    private var poolDrainPackage: String? = null
    private var poolDrainAt = 0L

    /** Last tick's clock, for crediting tracked-task time. */
    private var lastLedgerAt = 0L

    /**
     * Counters buffered in memory between disk writes. See [flushCounters].
     *
     * Both used to be written through SharedPreferences on every tick, four
     * times a second, and SharedPreferences writes the *whole* file each time -
     * every rule, list and JSON blob the app has, rewritten to flash four
     * times a second for as long as a tracked or pool-billed app stayed open.
     */
    private var pendingLedgerPackage: String? = null
    private var pendingLedgerMs = 0L
    private var pendingPoolMs = 0L
    private var lastCounterFlushAt = 0L

    // ── Foreground cache ──
    // See getForegroundApp: the usage log is read incrementally now.
    private var cachedForeground: ForegroundApp? = null
    private var cachedForegroundAt = 0L
    private var lastEventQueryAt = 0L
    private var lastFallbackAt = 0L

    /** When the once-a-second work last ran. See [SLOW_TICK_MS]. */
    private var lastSlowTickAt = 0L

    /**
     * Whether usage access was granted at the last once-a-second check.
     *
     * The remembered foreground app must not outlive the permission that
     * produced it: with usage access revoked the log simply goes quiet, and a
     * cache would keep answering with whatever was in front at the moment it
     * was switched off. The old per-tick query returned nothing in that state,
     * and so does this.
     */
    private var usageAccessOk = true

    /**
     * Whether the screen is on, kept current by [screenReceiver].
     *
     * The loop used to run at full speed with the screen off - four usage-log
     * queries, four permission sweeps and a handful of store reads a second,
     * all night, to watch a screen nobody could see. Nothing can be opened on
     * a phone whose screen is off, so now the loop sleeps until it comes on.
     */
    @Volatile
    private var screenOn = true

    /** Wakes the loop early: the screen came on, or it went off. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            wake.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        LockManager.initLock(this)
        Migration.run(this)

        startForeground(NOTIFICATION_ID, buildNotification(statusLine()))

        // An expired session counts as work even though nothing is enforcing
        // right now: its device-owner policy and any permission emergency it
        // raised outlive the process that set them, and the loop below is what
        // tears them down.
        if (!hasWorkToDo() && !LockManager.hasKioskExpired(this)) {
            // Release before stopping, rather than just stopping.
            //
            // This is one of the two paths that left apps stuck showing
            // "blocked by your organization" after a session was over. If the
            // process was killed mid-session (a reboot, a low-memory kill) the
            // session could expire while nothing was running to notice; the
            // next start would find no work to do and stop here *without ever
            // un-suspending anything*, so every app the session had suspended
            // stayed suspended at the OS level with nothing left that would
            // ever retry.
            //
            // Via PolicySync rather than standDown(): onCreate runs on the main
            // thread, and standDown's DPM work is one synchronous binder call
            // per suspended package.
            //
            // A permission emergency is cleared on this path for the same
            // reason it exists: it is protection for a running session, and
            // there is no session. One declared before a reboot or a kill would
            // otherwise pin the phone forever - nothing else clears it, since
            // SessionManager.end is never reached once the session has already
            // expired.
            PermissionGuard.clearEmergency(this)
            PolicySync.releaseManagedAppsNow(this)
            hideBlockerOverlay()
            hideBedtimeDimOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        screenOn = try {
            (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        } catch (_: Exception) {
            true
        }
        try {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        } catch (e: Exception) {
            // Without it the loop simply never learns the screen went off and
            // runs as it always did.
            Log.w(TAG, "Could not watch the screen state", e)
        }

        // On the policy worker, not here: onCreate is the main thread, and a
        // full sync is a few dozen binder calls.
        lastPolicyRevision = PolicySync.revision()
        lastPolicyRefreshAt = System.currentTimeMillis()
        PolicySync.reapply(this)
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        flushCounters()
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
                // Already gone.
            }
            screenReceiverRegistered = false
        }
        hideBlockerOverlay()
        hideBedtimeDimOverlay()

        // Only come back if there is still something to enforce.
        if (hasWorkToDo() && !LockManager.hasKioskExpired(this)) {
            sendBroadcast(
                Intent(this, BootReceiver::class.java).apply {
                    action = Intent.ACTION_BOOT_COMPLETED
                }
            )
        }
    }

    // ── Should we be running at all ───────────────────────────────

    /**
     * Whether anything the user has configured could need a watcher today.
     *
     * This is deliberately broader than "is something blocking right now": a
     * schedule window that starts at 8pm needs the service alive at 7:59, and
     * an alarm to wake it would be both less reliable and less honest than
     * simply staying up. The cost is paid back by [isEnforcingNow].
     */
    private fun hasWorkToDo(): Boolean {
        if (SessionManager.isEnforcing(this)) return true
        if (SessionManager.hasAlwaysOnRules(this)) return true
        if (TestMode.isActive(this)) return true
        if (FocusLedger.hasAmbientTasks(this)) return true
        if (Standalone.mayActNow(this) && Bedtime.isActive(this) && Bedtime.dimPercent(this) > 0 && Settings.canDrawOverlays(this)) return true
        return false
    }

    // ── Loop ──────────────────────────────────────────────────────

    /**
     * The tick loop.
     *
     * There is no slow "idle" rate any more. The service is only running
     * because [hasWorkToDo] said something needs enforcing - a session, a
     * standalone task, or a test - so every tick of this loop is a tick where
     * something could be in front of the person. It used to have a five-second
     * idle mode for a service kept alive merely to wait for a window to open;
     * nothing keeps it alive for that any more, so the branch was unreachable
     * and the two rates had become the same question asked twice.
     */
    private fun startMonitoring() {
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()

                // `isKioskActive` *is* "not expired yet", so the old compound
                // test here could never be true and finishSession was dead
                // code. A session that ended on its own clock - a scheduled
                // lock, a 90-day kiosk, anything nobody tapped End on - was
                // therefore never torn down: no SessionManager.end (so no
                // totals, no released permission emergency, and the kiosk flag
                // left set with nothing ever clearing it) and no
                // clearDeviceOwnerKioskPolicies (so a disabled status bar, a
                // persistent home still pointed at FocusLock, and a narrowed
                // lock-task list all stayed applied across the next reboot).
                if (LockManager.hasKioskExpired(this@AppBlockerService)) {
                    finishSession()
                    break
                }

                if (!hasWorkToDo()) {
                    standDown()
                    break
                }

                if (!screenOn) {
                    // Nothing is in front of anyone. Bank what was counted,
                    // break the pool and ledger anchors so the dark stretch is
                    // never billed or credited, and sleep until the screen
                    // comes back - or for a while, so a session that runs out
                    // in the night is still torn down on time.
                    flushCounters()
                    poolDrainPackage = null
                    lastLedgerAt = 0L
                    withTimeoutOrNull(SCREEN_OFF_TICK_MS) { wake.receive() }
                    continue
                }

                // Once a second, not four times: none of these needs to be
                // quicker than that, and each one is a store read, a binder
                // call or both.
                if (now - lastSlowTickAt >= SLOW_TICK_MS) {
                    lastSlowTickAt = now
                    usageAccessOk = SetupChecks.hasUsageAccess(this@AppBlockerService)
                    if (!usageAccessOk) {
                        cachedForeground = null
                        cachedForegroundAt = 0L
                        lastEventQueryAt = 0L
                    }
                    refreshPolicy()
                    syncBedtimeDimOverlay()
                    updateNotificationIfChanged()
                    checkGuardStillOn()
                    checkPermissionRevocation()
                    TakeABreak.sweepExpired(this@AppBlockerService)
                    // A promise made on the laptop, pulled at most once a minute while signed in.
                    PromiseSync.pullAsync(this@AppBlockerService)
                }

                val foreground = getForegroundApp(now)
                if (foreground != null) {
                    // Credit the tracked-task ledger before the decision, not
                    // after: a task counts the time its apps are in front with
                    // the screen on, which is a fact about what the person did
                    // rather than about what the rules decided.
                    val delta = if (lastLedgerAt == 0L) 0L
                    else (now - lastLedgerAt).coerceIn(0L, FocusLedger.MAX_STEP_MS)
                    bufferLedger(foreground.packageName, delta)
                    handleForeground(foreground, now)
                } else {
                    hideBlockerOverlay()
                }
                lastLedgerAt = now

                if (now - lastCounterFlushAt >= COUNTER_FLUSH_MS) flushCounters()

                val activeNow = SessionManager.isGuardActive(this@AppBlockerService) ||
                    TestMode.isActive(this@AppBlockerService) ||
                    FocusLedger.hasAmbientTasks(this@AppBlockerService)
                // Fast while the app in front is new; slower once the same allowed app has
                // stayed in front a while. Opening something else still shows up within a
                // calm tick, and the next one after that change is fast again.
                val inFront = foreground?.packageName
                if (inFront != lastTickForeground) {
                    lastTickForeground = inFront
                    lastForegroundChangeAt = now
                }
                val settled = now - lastForegroundChangeAt > SETTLED_AFTER_MS
                delay(
                    when {
                        !activeNow -> IDLE_TICK_MS
                        settled -> SETTLED_TICK_MS
                        else -> ENFORCEMENT_TICK_MS
                    }
                )
            }
        }
    }

    // ── Buffered counters ─────────────────────────────────────────

    // Synchronized, all four: the loop runs on an IO thread, and onDestroy
    // flushes from the main thread while the loop may still be mid-tick.

    @Synchronized
    private fun bufferLedger(packageName: String, ms: Long) {
        if (ms <= 0L) return
        if (pendingLedgerPackage != packageName) {
            flushLedger()
            pendingLedgerPackage = packageName
        }
        pendingLedgerMs += ms
    }

    @Synchronized
    private fun flushLedger() {
        val pkg = pendingLedgerPackage
        val ms = pendingLedgerMs
        pendingLedgerMs = 0L
        if (pkg == null || ms <= 0L) return
        // Every millisecond here was counted on a tick with the screen on,
        // so the ledger does not need to ask again - and must not, when this
        // flush is the one that runs because the screen just went off.
        FocusLedger.credit(this, pkg, ms, screenKnownOn = true)
    }

    @Synchronized
    private fun flushPool() {
        val ms = pendingPoolMs
        pendingPoolMs = 0L
        if (ms > 0L) EarnPool.spendMs(this, ms)
    }

    /** Writes both buffered counters. Also runs on the way out, so nothing counted is lost. */
    @Synchronized
    private fun flushCounters() {
        lastCounterFlushAt = System.currentTimeMillis()
        try {
            flushLedger()
            flushPool()
        } catch (e: Exception) {
            Log.w(TAG, "Could not write buffered counters", e)
        }
    }

    /**
     * Notices the content guard being switched off from inside a session.
     *
     * Accessibility can be revoked in Settings at any moment, and doing so
     * quietly disarms every keyword and content rule while the session carries
     * on looking intact. That is the most inviting escape hatch in the app,
     * because it does not feel like cheating — it feels like changing a
     * setting.
     *
     * This does not fight back. It cannot: forcing someone into Kiosk to punish
     * a settings change would be a worse betrayal than the escape it prevents,
     * and could strand a person whose guard broke for an innocent reason. What
     * it does is refuse to let it happen silently — the notification says the
     * guard is down, and the dashboard says it too. The person gets to decide
     * with the fact in front of them.
     */
    private fun checkGuardStillOn() {
        if (!BuildConfig.ENFORCEMENT) return
        if (!SessionManager.isActive(this)) {
            guardWasOn = false
            return
        }
        if (!SetupChecks.needsContentGuard(this)) return

        val on = SetupChecks.isContentGuardEnabled(this)
        if (on) {
            guardWasOn = true
            if (guardDroppedAt != 0L) {
                guardDroppedAt = 0L
                updateNotificationIfChanged(force = true)
            }
            return
        }

        // Only report a guard that was actually up when the session started.
        // Someone who never granted it is not sneaking out of anything.
        if (!guardWasOn || guardDroppedAt != 0L) return
        guardDroppedAt = System.currentTimeMillis()
        updateNotificationIfChanged(force = true)
        Log.w(TAG, "Content guard switched off during an active session")
    }

    /**
     * The hard version of the check above, for the permissions the whole
     * lock depends on rather than just the content guard.
     *
     * This one does fight back: usage access, the overlay permission and
     * device owner status going away mid-session is not a settings
     * disagreement, it is the phone losing the ability to enforce anything
     * at all, and PermissionGuard only ever fires on an actual drop from
     * granted, never on a permission that was simply never set up. Once
     * declared, the emergency is released only when PermissionGuard itself
     * confirms the permission is back - nothing here clears it early.
     */
    private fun checkPermissionRevocation() {
        if (!BuildConfig.ENFORCEMENT) return
        if (!SessionManager.isActive(this)) return

        PermissionGuard.clearResolved(this)
        val revoked = PermissionGuard.checkForRevocation(this)
        if (revoked.isEmpty()) return

        PermissionGuard.declareEmergency(this, revoked)
        Log.w(TAG, "Permission revoked mid-session, entering recovery lock: $revoked")
        PolicySync.applyNow(this)
        PermissionRecoveryActivity.launch(this)
    }

    private fun handleForeground(foreground: ForegroundApp, now: Long) {
        val packageName = foreground.packageName

        // Outranks everything below, including FocusLock's own screens. A
        // missing permission is not a rule the running mode can soften, and
        // letting someone sit comfortably inside FocusLock's own tabs while it
        // is unresolved would defeat the point of it being an emergency.
        if (PermissionGuard.isEmergency(this)) {
            // The recovery screen itself is the one surface this must never
            // cover.
            //
            // It used to: the overlay went up over *everything* that was not
            // Settings, the recovery screen included, and the overlay does not
            // pass touches through. So the screen with the buttons that open
            // the right Settings page sat underneath a second screen whose only
            // button - "Go fix it" - brought forward the screen that was
            // already there, still underneath. Every route out led back to the
            // overlay.
            //
            // Read from the activity's own lifecycle rather than from the
            // usage log, which lags a transition by a tick or two and names
            // the package, not the screen.
            if (PermissionRecoveryActivity.isShowing) {
                hideBlockerOverlay()
                return
            }
            // Settings itself, plus a system surface while the recovery
            // screen's own "turn it back on" button has opened its window.
            //
            // The window is what makes the grant possible at all. The screen
            // tells the person to go and re-enable a permission; on several OEM
            // builds the screen that grants it, and the dialog that confirms
            // it, are not the Settings package - they belong to the permission
            // controller or to SystemUI - and the package-name check below
            // cannot know every one of them. Covering those with the overlay
            // and yanking the person back to the recovery screen put the one
            // action being asked for out of reach: the screen demanded a
            // permission and then would not let them give it.
            val granting = SystemSurfaces.isSettings(packageName) ||
                (LockManager.isSettingsAccessAllowed(this) && SystemSurfaces.isSystemSurface(this, packageName))
            if (granting) {
                hideBlockerOverlay()
            } else if (packageName == this.packageName) {
                // One of FocusLock's other screens. Bring the recovery screen
                // over it rather than drawing the overlay on top: the overlay
                // would only be in the way of the screen it is asking for.
                hideBlockerOverlay()
                bringRecoveryScreenToFront()
            } else {
                showPermissionEmergencyOverlay()
                bringRecoveryScreenToFront()
            }
            return
        }

        if (packageName == this.packageName) {
            lastOwnForegroundAt = now
            hideBlockerOverlay()
            return
        }

        // Transition grace.
        //
        // getForegroundApp reads the last foreground event in a 15-second
        // window. While one FocusLock screen hands off to another — Library to
        // the video player, a tab to a settings screen — the incoming activity's
        // RESUMED event has not landed yet, so the newest event still names
        // whatever was in front before. If that package happens to be blocked,
        // the loop "catches" it and the block screen flashes over our own UI.
        //
        // So: for a moment after we were genuinely in front, believe a foreign
        // package only if it is still there on the next tick. A real app launch
        // is intercepted a fraction of a second later than before; a phantom one
        // never gets intercepted at all, because it is gone by then.
        if (now - lastOwnForegroundAt < TRANSITION_GRACE_MS) {
            return
        }

        // The demo tier. It has no Device Owner, so none of the OS-level acts
        // below are available to it anyway - but the loop intercepts and
        // overlays using only UsageStatsManager and SYSTEM_ALERT_WINDOW, and
        // neither of those needs Device Owner. So this gate is the demo's own,
        // and it is about *when* rather than *whether*: the demo shows the
        // block screen, once, inside its single session. See [DemoMode].
        if (!BuildConfig.ENFORCEMENT && !DemoMode.canShowBlockScreen(this)) {
            hideBlockerOverlay()
            return
        }

        // A live content-guard hit outranks the app's own policy: the app is
        // allowed, this particular screen inside it is not.
        //
        // Gated on the tier, because everything it does is an act on another
        // app - including the forced return to FocusLock below. In the demo the
        // content guard still recognises the screen and still shows its own
        // intercept; it just does not press anything on the person's behalf.
        val guardHit = GuardState.activeFor(packageName)
        if (BuildConfig.ENFORCEMENT && guardHit != null && guardHit.action != GuardAction.NUDGE) {
            if (guardHit.action == GuardAction.CLOSE_APP) {
                bringToFront(throttled = true)
            }
            return
        }

        val decision = decide(packageName, foreground.className, now)

        // Bill the earned pool for real foreground time, but only for an app
        // the decision actually let through. Charging before the decision would
        // make a blocked app cost minutes it never got to use.
        if (decision.outcome == GuardOutcome.ALLOW) {
            chargeEarnPool(packageName, now)
        } else {
            chargeEarnPool("", now)
            // Whatever was being billed stops here, so bank it now rather than
            // leaving the pool looking fuller than it is until the next flush.
            flushPool()
        }

        // In the demo, every non-allow decision is a *screen*, never an action.
        //
        // That is the whole difference between the tiers at this point in the
        // code: the full build may shove someone back to FocusLock, and the
        // demo may not. It still shows the real decision with the real words -
        // a demo that showed a second, gentler copy would not be showing the
        // product - but it never takes the screen away from anyone.
        if (!BuildConfig.ENFORCEMENT) {
            if (decision.outcome != GuardOutcome.ALLOW && shouldIntercept(packageName, now)) {
                lastInterceptAt = now
                lastInterceptPackage = packageName
                showIntercept(decision)
            } else if (decision.outcome == GuardOutcome.ALLOW) {
                hideBlockerOverlay()
            }
            return
        }

        when (decision.outcome) {
            GuardOutcome.ALLOW -> hideBlockerOverlay()

            GuardOutcome.PAUSE -> {
                if (shouldIntercept(packageName, now)) {
                    lastInterceptAt = now
                    lastInterceptPackage = packageName
                    showIntercept(decision)
                }
            }

            GuardOutcome.BLOCK -> {
                // A real block is allowed to silently shove someone back to
                // FocusLock - that is the whole point of enforcement. A test
                // is not: nothing here should ever act without a screen
                // explaining why, since the person testing is not actually
                // trying to escape anything, and a silent forced navigation
                // with no visible cause is exactly the kind of surprise
                // "test the block" exists to prevent, not produce.
                val testing = TestMode.isActive(this)
                if (SystemSurfaces.isLauncher(packageName)) {
                    if (testing) {
                        // Same cooldown as everything else. Without it this
                        // re-launched the intercept screen every single tick.
                        if (shouldIntercept(packageName, now)) {
                            lastInterceptAt = now
                            lastInterceptPackage = packageName
                            showIntercept(decision)
                        }
                    } else {
                        // Launcher escapes are countered instantly: a throttle
                        // here is a visible half-second of the home screen.
                        hideBlockerOverlay()
                        bringToFront(throttled = false)
                    }
                    return
                }
                // The uninstall dialog is a critical surface, except while a Sealed promise holds it.
                if (SystemSurfaces.isCritical(packageName) && decision.source != "removalGuard") {
                    hideBlockerOverlay()
                    return
                }
                // A test shows the screen instead of shoving the person back to
                // FocusLock, but it is still subject to the cooldown.
                //
                // `testing ||` here - short-circuiting the cooldown entirely -
                // is the actual cause of "the screen kept getting a black
                // screen and closing lots of times": the loop runs four times a
                // second, so a test fired a fresh InterceptActivity every 250ms
                // at whatever app was in front, and the two fought each other
                // for the foreground until one of them lost. It is a cooldown,
                // not a mode, and it was never optional.
                if (shouldIntercept(packageName, now)) {
                    lastInterceptAt = now
                    lastInterceptPackage = packageName
                    showIntercept(decision)
                } else if (!testing) {
                    // A package Android refuses to suspend (the Play Store is
                    // the usual one - it is the installer of record, and the
                    // platform will not let even a Device Owner suspend it) has
                    // no OS-level stop behind this loop at all. For those the
                    // intercept is the entire enforcement, so it is countered
                    // instantly, the same as a launcher escape, rather than
                    // being left open for the throttle's second and a half.
                    bringToFront(throttled = !KioskPolicy.isUnsuspendable(this, packageName))
                }
            }
        }
    }

    /** The last decision, and what it was made for. See [decide]. */
    private data class CachedDecision(
        val packageName: String,
        val className: String?,
        val override: Boolean?,
        val revision: Long,
        val at: Long,
        val decision: GuardDecision
    )

    private var cachedDecision: CachedDecision? = null

    /**
     * [RuleEngine.decide], reused for up to [DECISION_TTL_MS] for the same app.
     *
     * The loop asks four times a second, and with another app in front the
     * answer is the same every time: measured on a phone with WhatsApp open,
     * re-deciding on every tick was the largest single cost left in the
     * process. Any settings change ticks the revision and forces a fresh
     * decision at once, and nothing time-based needs better than a second.
     */
    private fun decide(packageName: String, className: String?, now: Long): GuardDecision {
        val override = TestMode.overrideFor(this)
        val revision = PolicySync.revision()
        cachedDecision?.let { c ->
            if (c.packageName == packageName && c.className == className && c.override == override &&
                c.revision == revision && now - c.at < DECISION_TTL_MS
            ) {
                return c.decision
            }
        }
        val decision = RuleEngine.decide(this, packageName, className, override)
        cachedDecision = CachedDecision(packageName, className, override, revision, now, decision)
        return decision
    }

    private fun shouldIntercept(packageName: String, now: Long): Boolean {
        if (packageName != lastInterceptPackage) return true
        // A test gets a much longer cooldown on the same app. A real block
        // re-asserts itself every few seconds because it is actually stopping
        // you; a test has already made its point, and putting the same preview
        // back every 2.5 seconds while you carry on using the app would be
        // indistinguishable from the loop this cooldown exists to prevent.
        val cooldown = if (TestMode.isActive(this)) TEST_INTERCEPT_COOLDOWN_MS else INTERCEPT_COOLDOWN_MS
        return now - lastInterceptAt >= cooldown
    }

    // ── Policy ────────────────────────────────────────────────────

    /**
     * Keeps this loop's view of policy current.
     *
     * A store write is already applied by [PolicySync.request] on its own
     * worker; all this loop has to do about one is drop its usage cache. It
     * used to also run a full sync of its own, inline on this thread, on every
     * revision - so each toggle was applied twice, by two threads at once.
     *
     * The timer is for what changes with the clock rather than with a write -
     * an overlay window opening mid-session, a bedtime starting - and it now
     * goes through the worker too, where an unchanged policy costs nothing
     * (see KioskPolicy.Applied).
     */
    private fun refreshPolicy() {
        val revision = PolicySync.revision()
        if (revision != lastPolicyRevision) {
            lastPolicyRevision = revision
            AppLimits.invalidate()
        }
        val now = System.currentTimeMillis()
        if (now - lastPolicyRefreshAt >= POLICY_REFRESH_MS) {
            lastPolicyRefreshAt = now
            PolicySync.reapply(this)
        }
    }

    // ── Earned pool ───────────────────────────────────────────────

    /**
     * Bills the earned pool for the app that is actually in front.
     *
     * Only a Budgeted app past its free daily minutes is on pool time, and it
     * is charged for the time between two consecutive ticks in which it was the
     * package in front - so switching away stops the meter immediately, and
     * there is no window left open in the background quietly spending.
     *
     * The gap check is what makes that true in practice rather than in theory.
     * This function only runs on the path where a *foreign* app was decided
     * about, so a tick spent inside FocusLock's own UI - or inside the
     * transition grace, or behind a content-guard hit, or with the process
     * asleep - never reaches it, and the anchor would still be pointing at the
     * app that was open before. Charging that gap would bill pool minutes for
     * time the person spent in FocusLock. A gap wider than a few ticks is
     * therefore treated as "we were not watching" and costs nothing, which also
     * covers the phone having slept through it.
     */
    private fun chargeEarnPool(packageName: String, now: Long) {
        if (packageName.isEmpty() || !EarnPool.isOnPoolTime(this, packageName)) {
            poolDrainPackage = null
            return
        }
        if (poolDrainPackage != packageName) {
            poolDrainPackage = packageName
            poolDrainAt = now
            return
        }
        val elapsed = now - poolDrainAt
        poolDrainAt = now
        if (elapsed <= 0L || elapsed > POOL_DRAIN_GAP_MS) return
        synchronized(this) { pendingPoolMs += elapsed }
    }

    // ── Ending ────────────────────────────────────────────────────

    /** A session that reached its own end. */
    private fun finishSession() {
        flushCounters()
        hideBlockerOverlay()
        val releaseOwner = SessionManager.releasesOwnerOnEnd(this)

        if (dpm.isDeviceOwnerApp(packageName)) {
            KioskPolicy.releaseAllManagedApps(this, dpm, adminComponent)
            KioskPolicy.clearDeviceOwnerKioskPolicies(this, dpm, adminComponent)
        }

        SessionManager.end(this)

        if (releaseOwner && dpm.isDeviceOwnerApp(packageName)) {
            // The one-shot sprint: hand the phone back completely. Only ever
            // reached when the user asked for it before starting.
            FocusStore.setBool(this, Constants.KEY_SECURITY_BASELINE_READY, false)
            FocusStore.setBool(this, Constants.KEY_ADB_DISABLED, false)
            try {
                dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear debugging restriction", e)
            }
            KioskPolicy.forgetAppliedState()
            try {
                dpm.clearDeviceOwnerApp(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release device owner", e)
            }
        }

        standDown()
    }

    /** Nothing left to watch: release everything and go away quietly. */
    private fun standDown() {
        flushCounters()
        hideBlockerOverlay()
        hideBedtimeDimOverlay()
        if (dpm.isDeviceOwnerApp(packageName) && !SessionManager.isActive(this)) {
            KioskPolicy.releaseAllManagedApps(this, dpm, adminComponent)
            KioskPolicy.applyRestrictions(this, dpm, adminComponent)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Foreground detection ──────────────────────────────────────

    /**
     * What is in front, read incrementally from the usage log.
     *
     * This was the hottest code in the app. Every tick - four a second - it
     * asked for the last fifteen seconds of the usage log, and whenever that
     * window held no app switch (which is simply "the person has been in the
     * same app for fifteen seconds", i.e. almost always) it fell back to
     * [UsageStatsManager.queryUsageStats] for the whole day, which makes the
     * system aggregate today's usage for every app on the phone. Four times a
     * second, for the length of a session. That is the heat.
     *
     * Now it reads only what was logged since the last look (plus a small
     * overlap, since an event can land a moment after its own timestamp),
     * remembers the answer, and keeps it until a newer switch appears. The
     * daily table is consulted only when there is no answer at all, or rarely
     * as a cross-check for the phones that log sparsely.
     */
    private fun getForegroundApp(now: Long): ForegroundApp? {
        if (!usageAccessOk) return null
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null

        try {
            val from = if (lastEventQueryAt == 0L) {
                now - INITIAL_EVENT_WINDOW_MS
            } else {
                maxOf(lastEventQueryAt - EVENT_OVERLAP_MS, now - MAX_EVENT_WINDOW_MS)
            }
            val events = usm.queryEvents(from, now)
            lastEventQueryAt = now
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                // `>=`, and against the remembered time rather than this
                // batch's: the overlap re-reads events already seen, and an
                // older one must never replace a newer answer.
                if (isForeground && event.timeStamp >= cachedForegroundAt) {
                    val pkg = event.packageName
                    if (!pkg.isNullOrBlank()) {
                        cachedForeground = ForegroundApp(pkg, event.className)
                        cachedForegroundAt = event.timeStamp
                    }
                }
            }

            // Some OEMs report events sparsely. Consult the usage table when
            // there is no answer yet, or - rarely - when the log has been
            // silent long enough that it may have missed a switch.
            val silentFor = now - cachedForegroundAt
            val fallbackDue = cachedForeground == null ||
                (silentFor > FALLBACK_SILENCE_MS && now - lastFallbackAt >= FALLBACK_INTERVAL_MS)
            if (fallbackDue && now - lastFallbackAt >= FALLBACK_MIN_GAP_MS) {
                lastFallbackAt = now
                val newest = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
                    ?.asSequence()
                    ?.filter { !it.packageName.isNullOrBlank() }
                    ?.maxByOrNull { it.lastTimeUsed }
                if (newest != null && newest.lastTimeUsed > cachedForegroundAt) {
                    cachedForeground = ForegroundApp(newest.packageName, null)
                    cachedForegroundAt = newest.lastTimeUsed
                }
            }
            return cachedForeground
        } catch (e: Exception) {
            Log.w(TAG, "Usage query failed", e)
            return null
        }
    }

    // ── Surfacing the block ───────────────────────────────────────

    private var lastTickForeground: String? = null
    private var lastForegroundChangeAt = 0L

    private fun showIntercept(decision: GuardDecision) {
        runOnMain {
            try {
                val testing = TestMode.isActive(this)
                startActivity(
                    Intent(this, InterceptActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        putExtra(InterceptActivity.EXTRA_PACKAGE, decision.packageName)
                        putExtra(InterceptActivity.EXTRA_HEADLINE, decision.headline)
                        putExtra(InterceptActivity.EXTRA_DETAIL, decision.detail)
                        putExtra(InterceptActivity.EXTRA_SOURCE, decision.source)
                        putExtra(InterceptActivity.EXTRA_PAUSE, decision.isPause)
                        putExtra(InterceptActivity.EXTRA_TEST_MODE, testing)
                        // A test never spends anything real: a break pass would
                        // eat into the actual daily allowance, and the earned
                        // pool is a real reward that must not be charged for a
                        // screen that was never a real block to begin with.
                        putExtra(InterceptActivity.EXTRA_OFFERS_BREAK, !testing && decision.offersBreak)
                        putExtra(InterceptActivity.EXTRA_POOL_MINUTES, decision.poolMinutes)
                        putExtra(InterceptActivity.EXTRA_BREAK_MINUTES, decision.breakMinutesOverride)
                    }
                )
                hideBlockerOverlay()
            } catch (e: Exception) {
                Log.w(TAG, "Could not show intercept screen", e)
                showBlockerOverlay(decision)
            }
        }
    }

    private fun bringToFront(throttled: Boolean) {
        val now = System.currentTimeMillis()
        if (throttled && now - lastBringToFrontAt < BRING_TO_FRONT_THROTTLE_MS) return
        lastBringToFrontAt = now
        runOnMain {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not return to FocusLock", e)
            }
        }
    }

    /** Same throttle idea as [bringToFront], its own timestamp so the two never starve each other. */
    private fun bringRecoveryScreenToFront() {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryLaunchAt < BRING_TO_FRONT_THROTTLE_MS) return
        lastRecoveryLaunchAt = now
        runOnMain {
            try {
                PermissionRecoveryActivity.launch(this)
            } catch (e: Exception) {
                Log.w(TAG, "Could not surface the permission recovery screen", e)
            }
        }
    }

    /**
     * The overlay reinforcement for a permission emergency: drawn above
     * whatever is on screen, FocusLock's own activities included, which is
     * the one deliberate exception to this overlay mechanism otherwise never
     * covering the app's own UI. On a Device-Owner phone the lock task pin in
     * KioskPolicy is what actually holds the line; this is what a phone
     * without Device Owner has instead, and belt-and-braces everywhere else.
     */
    private fun showPermissionEmergencyOverlay() {
        runOnMain {
            if (!Settings.canDrawOverlays(this)) return@runOnMain

            val missing = PermissionGuard.activeEmergency(this)
            val headline = if (missing.size == 1) {
                getString(R.string.permission_lost_one, missing.first().label(this))
            } else {
                getString(R.string.permission_lost_many, missing.size)
            }

            if (blockerOverlay != null) {
                blockerOverlayMessage?.text = headline
                return@runOnMain
            }

            val wm = (getSystemService(WINDOW_SERVICE) as? WindowManager) ?: return@runOnMain
            blockerWindowManager = wm

            val theme = UiPrefs.resolve(this)
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(scrimColor(theme.background))
                setPadding(48, 48, 48, 48)
            }

            val title = TextView(this).apply {
                text = headline
                textSize = 24f
                setTextColor(theme.textPrimary)
                gravity = Gravity.CENTER
                typeface = theme.typeface
            }

            val message = TextView(this).apply {
                text = getString(R.string.blocker_emergency_lock)
                textSize = 15f
                setTextColor(theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 24)
                typeface = theme.typeface
            }

            val button = Button(this).apply {
                text = getString(R.string.blocker_emergency_fix)
                setTextColor(theme.onAccent)
                backgroundTintList = android.content.res.ColorStateList.valueOf(theme.accent)
                typeface = theme.typeface
                // Unthrottled, and the overlay comes down with it. This went
                // through the same throttle as the loop's own relaunches, and
                // the loop re-arms that throttle every second and a half - so
                // a tap usually landed inside it and did nothing at all.
                setOnClickListener {
                    hideBlockerOverlay()
                    lastRecoveryLaunchAt = System.currentTimeMillis()
                    try {
                        PermissionRecoveryActivity.launch(this@AppBlockerService)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not open the permission recovery screen", e)
                    }
                }
            }

            root.addView(title)
            root.addView(message)
            root.addView(button)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            try {
                wm.addView(root, params)
                blockerOverlay = root
                blockerOverlayMessage = title
            } catch (_: Exception) {
                blockerOverlay = null
                blockerOverlayMessage = null
            }
        }
    }

    /**
     * The last-resort surface, used only when Android refuses the activity
     * start. It is intentionally plain: it exists so a block is never silent.
     */
    private fun showBlockerOverlay(decision: GuardDecision) {
        runOnMain {
            if (!Settings.canDrawOverlays(this)) return@runOnMain

            if (blockerOverlay != null) {
                blockerOverlayMessage?.text = decision.headline
                return@runOnMain
            }

            val wm = (getSystemService(WINDOW_SERVICE) as? WindowManager) ?: return@runOnMain
            blockerWindowManager = wm

            val theme = UiPrefs.resolve(this)
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(scrimColor(theme.background))
                setPadding(48, 48, 48, 48)
            }

            val title = TextView(this).apply {
                text = decision.headline.ifBlank { "FocusLock" }
                textSize = 24f
                setTextColor(theme.textPrimary)
                gravity = Gravity.CENTER
                typeface = theme.typeface
            }

            val message = TextView(this).apply {
                text = decision.detail
                textSize = 15f
                setTextColor(theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 24)
                typeface = theme.typeface
            }

            val button = Button(this).apply {
                text = getString(R.string.intercept_back_to_focuslock)
                setTextColor(theme.onAccent)
                backgroundTintList = android.content.res.ColorStateList.valueOf(theme.accent)
                typeface = theme.typeface
                setOnClickListener { bringToFront(throttled = false) }
            }

            root.addView(title)
            root.addView(message)
            root.addView(button)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            try {
                wm.addView(root, params)
                blockerOverlay = root
                blockerOverlayMessage = title
            } catch (_: Exception) {
                blockerOverlay = null
                blockerOverlayMessage = null
            }
        }
    }

    private fun scrimColor(base: Int): Int = Color.argb(
        235,
        Color.red(base),
        Color.green(base),
        Color.blue(base)
    )

    private fun hideBlockerOverlay() {
        // Most ticks have no overlay to hide; do not post an empty job to the
        // main thread four times a second to find that out.
        if (blockerOverlay == null) return
        runOnMain {
            val view = blockerOverlay ?: return@runOnMain
            val wm = blockerWindowManager ?: return@runOnMain
            try {
                wm.removeView(view)
            } catch (_: Exception) {
                // Already gone.
            } finally {
                blockerOverlay = null
                blockerOverlayMessage = null
                blockerWindowManager = null
            }
        }
    }

    /**
     * System-wide touch-passthrough screen dimmer for Bedtime.
     * Dims every allowed app and launcher on the phone when Bedtime is active,
     * rather than only FocusLock's own activities.
     */
    private fun syncBedtimeDimOverlay() {
        val wantedPercent = if (Standalone.mayActNow(this) && Bedtime.isActive(this) && Settings.canDrawOverlays(this)) {
            Bedtime.dimPercent(this)
        } else {
            0
        }
        if (wantedPercent <= 0) {
            hideBedtimeDimOverlay()
            return
        }
        if (bedtimeDimOverlay != null && lastBedtimeDimPercent == wantedPercent) return
        runOnMain {
            val wm = (getSystemService(WINDOW_SERVICE) as? WindowManager) ?: return@runOnMain
            val colour = UiPrefs.withAlpha(Color.BLACK, wantedPercent * 255 / 100)
            val existing = bedtimeDimOverlay
            if (existing != null) {
                existing.setBackgroundColor(colour)
                lastBedtimeDimPercent = wantedPercent
                isBedtimeDimOverlayShowing = true
                return@runOnMain
            }
            val dimView = View(this).apply {
                setBackgroundColor(colour)
                isClickable = false
                isFocusable = false
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                wm.addView(dimView, params)
                bedtimeDimOverlay = dimView
                lastBedtimeDimPercent = wantedPercent
                isBedtimeDimOverlayShowing = true
            } catch (_: Exception) {
                bedtimeDimOverlay = null
                lastBedtimeDimPercent = 0
                isBedtimeDimOverlayShowing = false
            }
        }
    }

    private fun hideBedtimeDimOverlay() {
        if (bedtimeDimOverlay == null) {
            isBedtimeDimOverlayShowing = false
            lastBedtimeDimPercent = 0
            return
        }
        runOnMain {
            val view = bedtimeDimOverlay ?: return@runOnMain
            val wm = (getSystemService(WINDOW_SERVICE) as? WindowManager)
            try {
                wm?.removeView(view)
            } catch (_: Exception) {
                // Already gone.
            } finally {
                bedtimeDimOverlay = null
                lastBedtimeDimPercent = 0
                isBedtimeDimOverlayShowing = false
            }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    // ── Notification ──────────────────────────────────────────────

    private fun statusLine(): String {
        // A dropped guard outranks everything else in the notification: it is
        // the one state where the session looks fine and quietly is not.
        if (guardDroppedAt != 0L && SessionManager.isActive(this)) {
            return getString(R.string.status_guard_off)
        }
        EarnSession.activeTask(this)?.let { task ->
            return getString(
                R.string.status_on_task,
                task.title,
                SessionManager.formatDuration(EarnSession.elapsedMs(this))
            )
        }
        if (EarnPool.hasMinutes(this)) {
            return getString(
                R.string.status_earned_pool,
                SessionManager.formatDuration(EarnPool.balanceMs(this))
            )
        }
        if (SessionManager.isActive(this)) {
            val mode = SessionManager.mode(this)
            return getString(R.string.mode_notification_line, mode.label(this), SessionManager.formatRemaining(this))
        }
        ScheduleManager.activeWindowIfEnabled(this)?.let { window ->
            return getString(R.string.status_scheduled_until, ScheduleManager.formatTime(this, window.endMinutes))
        }
        if (Bedtime.isActive(this)) {
            return getString(R.string.status_bedtime_until, Bedtime.formatTime(this, Bedtime.endMinutes(this)))
        }
        if (AppLimits.hasEnforceableBudgets(this)) return getString(R.string.status_watching_limits)
        ScheduleManager.nextWindow(this)?.let { window ->
            return getString(R.string.status_next_window, ScheduleManager.formatTime(this, window.startMinutes))
        }
        if (Bedtime.isEnabled(this)) {
            return getString(R.string.status_bedtime_starts, Bedtime.formatTime(this, Bedtime.startMinutes(this)))
        }
        return getString(R.string.status_waiting)
    }

    private fun updateNotificationIfChanged(force: Boolean = false) {
        val line = statusLine()
        if (!force && line == lastNotificationText) return
        lastNotificationText = line
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(line))
        } catch (_: Exception) {
            // A notification we cannot refresh is not worth crashing over.
        }
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "focuslock_guard"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "FocusLock", NotificationManager.IMPORTANCE_MIN)
                    .also { it.setShowBadge(false) }
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("FocusLock")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "FocusLockGuard"
        private const val NOTIFICATION_ID = 9001
        private const val ENFORCEMENT_TICK_MS = 250L
        /** The tick once the same app has been in front for [SETTLED_AFTER_MS]. */
        private const val SETTLED_TICK_MS = 600L
        private const val SETTLED_AFTER_MS = 8_000L
        private const val IDLE_TICK_MS = 3_000L
        private const val POLICY_REFRESH_MS = 15_000L

        /** How long a decision about the app in front is reused. See decide(). */
        private const val DECISION_TTL_MS = 1_000L

        /** The once-a-second work: permissions, notification, policy timer. */
        private const val SLOW_TICK_MS = 1_000L

        /** How long the loop sleeps with the screen off before checking the session clock. */
        private const val SCREEN_OFF_TICK_MS = 60_000L

        /** How often buffered ledger and pool time is written to the store. */
        private const val COUNTER_FLUSH_MS = 10_000L

        /** The usage log window on the first read, before anything is remembered. */
        private const val INITIAL_EVENT_WINDOW_MS = 15_000L

        /** Re-read of the previous window's tail, for events logged late. */
        private const val EVENT_OVERLAP_MS = 2_000L

        /** Never read further back than this, even after a long sleep. */
        private const val MAX_EVENT_WINDOW_MS = 60_000L

        /** The usage-table cross-check: only after this much log silence... */
        private const val FALLBACK_SILENCE_MS = 30_000L

        /** ...at most this often... */
        private const val FALLBACK_INTERVAL_MS = 30_000L

        /** ...and never more often than this, even with no answer at all. */
        private const val FALLBACK_MIN_GAP_MS = 5_000L
        private const val INTERCEPT_COOLDOWN_MS = 2_500L
        private const val TEST_INTERCEPT_COOLDOWN_MS = 45_000L

        /** Long enough to cover an activity handoff, short enough that a real
         *  app launch is still caught almost immediately. Two ticks. */
        private const val TRANSITION_GRACE_MS = 500L
        private const val BRING_TO_FRONT_THROTTLE_MS = 1_500L

        /**
         * The widest gap between two ticks that still counts as "the app was in
         * front the whole time".
         *
         * Six ticks at [ENFORCEMENT_TICK_MS]. Anything wider means something
         * else held the foreground, or the process was not running, and the
         * pool is not charged for it. See [chargeEarnPool].
         */
        private const val POOL_DRAIN_GAP_MS = 1_500L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AppBlockerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start guard service", e)
            }
        }

        /**
         * Starts the service only when it would find something to do.
         *
         * For callers that ask on a whim rather than because something just
         * began - FocusLock coming to the front, most of all. Starting the
         * service with nothing to enforce is not free: it goes foreground (a
         * notification that appears and vanishes), finds no work, and runs its
         * full release on the way out, every time the app is opened. The
         * conditions here are exactly the ones its own startup keeps it alive
         * for; everything else it would have released, the policy sync that
         * runs on the same resume already releases.
         */
        @Volatile
        var isBedtimeDimOverlayShowing: Boolean = false
            private set

        fun startIfNeeded(context: Context) {
            val needed = SessionManager.isEnforcing(context) ||
                SessionManager.hasAlwaysOnRules(context) ||
                TestMode.isActive(context) ||
                FocusLedger.hasAmbientTasks(context) ||
                (Standalone.mayActNow(context) && Bedtime.isActive(context) && Bedtime.dimPercent(context) > 0 && Settings.canDrawOverlays(context)) ||
                LockManager.hasKioskExpired(context) ||
                PermissionGuard.isEmergency(context)
            if (needed) start(context)
        }
    }
}
