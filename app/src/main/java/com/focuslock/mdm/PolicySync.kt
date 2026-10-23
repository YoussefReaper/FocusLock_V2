package com.focuslock.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The bridge between "the user flipped a switch" and "the phone behaves differently".
 *
 * Every store calls [request] after a write. Two things then happen:
 *
 *  1. A revision counter ticks. [AppBlockerService] compares it every tick, so
 *     the in-process enforcement loop picks the change up within ~200ms with no
 *     restart, which is what makes the Capabilities screen feel live.
 *  2. Device-Owner policy (lock-task packages, suspension, restrictions, Chrome
 *     sandbox) is re-applied on a background thread, debounced, because those
 *     calls are the slow part and must never run on the UI thread.
 */
object PolicySync {

    private const val TAG = "FocusLockPolicy"
    private const val DEBOUNCE_MS = 250L

    @Volatile
    private var revision: Long = 0L

    @Volatile
    private var lastReason: String = ""

    private val worker: Handler by lazy {
        val thread = HandlerThread("focuslock-policy")
        thread.start()
        Handler(thread.looper)
    }

    private var pending: Runnable? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * In-process listeners told, on the main thread, that the policy changed.
     *
     * Used by the content guard, which narrows what Android sends it while
     * nothing needs guarding and has to hear the moment that changes rather
     * than polling for it.
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun revision(): Long = revision

    fun lastReason(): String = lastReason

    @Synchronized
    fun request(context: Context, reason: String) {
        revision += 1
        lastReason = reason
        val appContext = context.applicationContext

        pending?.let { worker.removeCallbacks(it) }
        val runnable = Runnable { applyNow(appContext) }
        pending = runnable
        worker.postDelayed(runnable, DEBOUNCE_MS)

        mainHandler.post {
            runCatching { AppBlockerService.startIfNeeded(appContext) }
            listeners.forEach { runCatching { it() } }
        }
    }

    /**
     * Re-applies policy on the worker without claiming that anything changed.
     *
     * For the callers whose reason is "time passed" or "just in case" - the
     * guard service's timer, FocusLock coming to the front - rather than a
     * store write. [request] bumps the revision, and the revision is what every
     * cache in the app is keyed on, so using it for a check-in threw away every
     * cached rule, capability and keyword list in the process each time
     * FocusLock was opened, and made the next few hundred reads re-parse them
     * all from JSON. Nothing had changed; there was nothing to re-read.
     */
    fun reapply(context: Context) {
        val appContext = context.applicationContext
        worker.post { applyNow(appContext) }
    }

    /**
     * Hands every suspended and hidden app straight back, ahead of any pending
     * debounce.
     *
     * Used by [SessionManager.end] and by the guard service's own startup path.
     * Leaving an app suspended is the one failure a person reads as "FocusLock
     * broke my phone", so this jumps the 250ms queue that everything else
     * waits in - the moment a session ends is exactly when the process is most
     * likely to be killed, and a release still sitting in a debounce when that
     * happens never runs at all.
     *
     * Posted to the worker rather than run inline, even though the caller is
     * usually in a hurry. `setPackagesSuspended` is a synchronous binder call
     * made once per package, and both callers are on the main thread - the End
     * button, and Service.onCreate - so a phone with thirty blocked apps would
     * be doing thirty round trips to system_server with the UI frozen behind
     * them. The remaining gap is covered: the guard service releases on startup
     * whenever it finds nothing to do, and MainActivity re-syncs on every
     * resume, both of which retry anything left behind.
     */
    @Synchronized
    fun releaseManagedAppsNow(context: Context) {
        val appContext = context.applicationContext
        // Cancel the pending debounce first: it was scheduled from the state
        // *before* the session ended, and letting it run after this would be
        // harmless but pointless duplicate work.
        pending?.let { worker.removeCallbacks(it) }
        pending = null
        worker.post {
            val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return@post
            if (!SetupChecks.isDeviceOwner(appContext)) return@post
            try {
                KioskPolicy.releaseAllManagedApps(
                    appContext,
                    dpm,
                    ComponentName(appContext, AdminReceiver::class.java)
                )
                // The browser sandbox is applied *inside the browser* and stays
                // there until something clears it, so it has to be released on
                // this path too - not only on the AppBlockerService teardown.
                // Ending a session from the UI is exactly when every site stayed
                // blocked afterwards.
                KioskPolicy.clearBrowserSandbox(
                    appContext,
                    dpm,
                    ComponentName(appContext, AdminReceiver::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Immediate release of managed apps failed", e)
            }
        }

        // Cancelling the debounce above must not be the end of policy work.
        // The debounce can be an unrelated, newer request rather than the stale
        // one this path means to drop - a reboot, for one: BootReceiver asks
        // for a sync, the guard service then starts, finds nothing to enforce
        // and lands here, and the phone keeps whatever the last session applied
        // (a disabled status bar, a persistent home still pointed at FocusLock,
        // a narrowed lock-task list) because nothing re-applies the resting
        // state afterwards. Re-requesting is cheap and idempotent, and it also
        // ticks the revision the guard loop watches.
        request(appContext, "release")
    }

    /**
     * Re-applies every Device-Owner policy that the current capability set asks for.
     *
     * Synchronized because it has more than one caller thread - the worker,
     * the guard service's own loop when it declares a permission emergency,
     * the recovery screen as it lets go - and two overlapping runs diffing the
     * same suspended and hidden sets against each other is how an app ends up
     * suspended with nothing recording it.
     *
     * Its own lock rather than @Synchronized: that would share a monitor with
     * [request], which the UI thread calls on every toggle, and a toggle would
     * then sit waiting behind however many binder calls the sync was making.
     */
    fun applyNow(context: Context) = synchronized(applyLock) { applyNowLocked(context) }

    private val applyLock = Any()

    private fun applyNowLocked(context: Context) {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return
        if (!SetupChecks.isDeviceOwner(appContext)) return
        val admin = ComponentName(appContext, AdminReceiver::class.java)

        try {
            KioskPolicy.applyDeviceOwnerKioskPolicies(appContext, dpm, admin)
        } catch (e: Exception) {
            Log.w(TAG, "Lock-task sync failed", e)
        }

        try {
            KioskPolicy.applyRestrictions(appContext, dpm, admin)
        } catch (e: Exception) {
            Log.w(TAG, "Restriction sync failed", e)
        }

        try {
            KioskPolicy.syncSuspendedApps(appContext, dpm, admin)
        } catch (e: Exception) {
            Log.w(TAG, "Suspension sync failed", e)
        }

        try {
            KioskPolicy.syncHiddenApps(appContext, dpm, admin)
        } catch (e: Exception) {
            Log.w(TAG, "Hidden-app sync failed", e)
        }

        try {
            KioskPolicy.syncBrowserSandbox(appContext, dpm, admin)
        } catch (e: Exception) {
            Log.w(TAG, "Browser sandbox sync failed", e)
        }
    }
}
