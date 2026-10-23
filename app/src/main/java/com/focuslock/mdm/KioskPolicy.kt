package com.focuslock.mdm

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.util.Log

/**
 * Everything FocusLock asks the Device-Owner API to do.
 *
 * Each call is gated on a capability the user can see and switch off in
 * Rules -> Capabilities. Nothing in here fires because the app decided it was a
 * good idea: if a restriction is applied, the person turned it on.
 */
object KioskPolicy {

    private const val TAG = "FocusLockPolicy"

    private val CHROME_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser"
    )

    // ── Write-only-on-change ──────────────────────────────────────

    /**
     * What each Device-Owner setting was last successfully set to.
     *
     * The policy sync runs on every store write, every FocusLock resume and on
     * a timer while the guard service is up, and it used to re-issue every one
     * of its calls each time whether anything had changed or not: the lock-task
     * list, its features, the status bar, the persistent home, five user
     * restrictions, the uninstall block and a restrictions bundle for each of
     * five browsers. Those are not reads. Each one is a write that
     * system_server persists to disk, and several broadcast to other apps - a
     * browser sent new restrictions reloads its whole policy, a persistent
     * preferred activity rewrites the package manager's settings file. Repeated
     * every few seconds for as long as a session lasted, that was a phone that
     * ran warm doing nothing, with a system_server busy enough to make every
     * app on it stutter.
     *
     * So each write is remembered, and a write whose value is already the one
     * in place is skipped. Memory only, and dropped on a timer: FocusLock is
     * the only thing that sets these, but if anything ever does change one
     * underneath it, the first sync after [TTL_MS] puts it back - which is the
     * self-healing the unconditional version was paying for on every call.
     */
    private object Applied {
        private const val TTL_MS = 10 * 60 * 1000L
        private val values = HashMap<String, String>()
        private var since = 0L

        /** Runs [write] unless [key] already holds [value]; [write] returns whether it took. */
        @Synchronized
        fun ifChanged(key: String, value: String, write: () -> Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (since == 0L || now - since > TTL_MS) {
                values.clear()
                since = now
            }
            if (values[key] == value) return
            if (write()) values[key] = value else values.remove(key)
        }

        @Synchronized
        fun forgetAll() {
            values.clear()
        }
    }

    /**
     * For code outside this file that writes a Device-Owner setting directly,
     * so the next sync does not skip a write it believes is already in place.
     */
    fun forgetAppliedState() = Applied.forgetAll()

    // ── Stray sweeps ──────────────────────────────────────────────

    /**
     * When the OS was last asked directly which apps it still has suspended
     * or hidden. See [strays] and [hiddenStrays].
     *
     * Those sweeps exist to recover from bookkeeping and reality disagreeing,
     * which happens around a session ending or the process dying - not on an
     * ordinary sync. They ran on every sync regardless, and the hidden one
     * asks about every installed package, one binder call each: a couple of
     * hundred round trips to system_server every time FocusLock was opened, a
     * rule was edited or the timer fired. Now they run on the first sync of a
     * process, after every release, and otherwise at most every
     * [STRAY_SWEEP_INTERVAL_MS].
     */
    @Volatile
    private var suspendedSweepAt = 0L

    @Volatile
    private var hiddenSweepAt = 0L

    @Volatile
    private var refusalRetryAt = 0L

    private const val STRAY_SWEEP_INTERVAL_MS = 30 * 60 * 1000L

    /** Makes the next sync ask the OS directly, whatever the interval says. */
    fun requestStraySweep() {
        suspendedSweepAt = 0L
        hiddenSweepAt = 0L
    }

    private fun sweepDue(last: Long): Boolean =
        last == 0L || SystemClock.elapsedRealtime() - last >= STRAY_SWEEP_INTERVAL_MS

    // ── Lock task ─────────────────────────────────────────────────

    /**
     * Which packages may run inside the kiosk shell.
     *
     * Pure so it can be reasoned about and tested without a device: the caller
     * supplies the user's lists and the answer is a function of them.
     */
    fun buildLockTaskPackages(
        ownPackage: String,
        userAllowed: Set<String>,
        alwaysAllowed: Set<String>,
        scheduleAllowed: Set<String>,
        baselineReady: Boolean,
        allowLauncherEscape: Boolean,
        /**
         * Whether a capability the person switched on is still missing its
         * permission.
         *
         * Settings stay reachable in this state for exactly the reason they
         * stay reachable before the baseline is complete: a phone that cannot
         * be fully enforced must not also be a phone with no route to the
         * toggle that would fix it. This used to close at the end of setup, so
         * a session started with Usage Access (or accessibility, or overlay)
         * missing pinned itself to FocusLock and nothing else - and because
         * [PermissionGuard] deliberately ignores a permission that was never
         * granted, no recovery screen appeared either. The person could see the
         * permission was needed and could not get to it.
         */
        permissionsMissing: Boolean = false,
        earnAllowed: Set<String>? = null,
        /**
         * The active overlay window's own ceiling (always-allowed union that
         * window's allowedApps), or null when no overlay window is running.
         * See [ScheduleWindow.overlay]. Absolute on purpose: unlike
         * [earnAllowed], which only ever narrows a mode that was already
         * running, this replaces [userAllowed] and every other window's
         * [scheduleAllowed] entirely - a window a person did not choose to
         * make absolute must never widen one they did.
         */
        scheduleOverlayAllowed: Set<String>? = null
    ): Set<String> {
        val allowed = LinkedHashSet<String>()
        allowed.add(ownPackage)

        when {
            scheduleOverlayAllowed != null -> {
                // An Earn task can still narrow further inside an overlay
                // window, but it can never widen past what the window itself
                // allows - the same "narrows, never widens" rule as always,
                // just with the window as the new ceiling instead of the
                // user's own standing allowlist.
                allowed.addAll(
                    if (earnAllowed != null) earnAllowed.intersect(scheduleOverlayAllowed) else scheduleOverlayAllowed
                )
            }
            earnAllowed != null -> {
                // An Earn task narrows rather than widens. Whatever the standing
                // allowlist was, the live set is now only what the task needs, which
                // is the whole point: a task cannot open a door the running mode had
                // shut. The intersection with the standing allowlist happened in
                // EarnSession.allowedPackages before this was called.
                allowed.addAll(earnAllowed)
            }
            else -> {
                allowed.addAll(userAllowed)
                allowed.addAll(alwaysAllowed)
                allowed.addAll(scheduleAllowed)
            }
        }

        allowed.addAll(SystemSurfaces.critical)

        // Before the permission baseline is complete the user still has to reach
        // Settings to grant things, so those routes stay open exactly until then -
        // including inside an overlay window, or a schedule set up before setup
        // finished could brick the phone's own recovery route. A permission that
        // is missing later, while a session runs, reopens the same route for the
        // same reason.
        if (!baselineReady || permissionsMissing) {
            allowed.addAll(SystemSurfaces.settings)
        }

        if (allowLauncherEscape) {
            allowed.addAll(SystemSurfaces.launchers)
        }

        return allowed
    }

    fun applyDeviceOwnerKioskPolicies(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName
    ) {
        if (!SetupChecks.isDeviceOwner(context)) return

        // What Android says when someone taps an app FocusLock has paused.
        // Without it the system dialog reads "Blocked by work policy - For
        // more info, contact your IT admin", which on a personal phone reads
        // as though the phone belongs to an employer - and which is what
        // people reported as "apps are blocked by work policy".
        val support = context.getString(R.string.dpm_support_short)
        Applied.ifChanged("supportMessage", support) {
            try {
                dpm.setShortSupportMessage(admin, support)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set the support message", e)
                false
            }
        }

        // A revoked permission the running session depends on wins over
        // every normal computation below - including whatever the user's own
        // allowlist says - until it is restored. Checked first and returns,
        // so nothing later in this function can ever re-widen the emergency
        // allowlist back out from underneath it on the next sync tick.
        if (PermissionGuard.isEmergency(context)) {
            applyPermissionEmergencyLock(context, dpm, admin)
            return
        }

        val kioskWanted = SessionManager.shouldLockTask(context)
        val scheduleOverlay = ScheduleManager.activeWindowIfEnabled(context)
            ?.takeIf { it.overlay }
        val bedtimeOverlay = Bedtime.requiresLockTask(context)
        val persistentHome = kioskWanted &&
            CapabilityRegistry.isEnabled(context, Capabilities.PERSISTENT_HOME)

        val lockTaskPackages = buildLockTaskPackages(
            ownPackage = context.packageName,
            userAllowed = AppRules.kioskAllowlist(context),
            alwaysAllowed = AppRules.alwaysAllowed(context),
            scheduleAllowed = ScheduleManager.getAllScheduleAllowedApps(context),
            baselineReady = LockManager.isSecurityBaselineReady(context),
            // A capability the person switched on that Android has not actually
            // granted keeps Settings open. See buildLockTaskPackages.
            permissionsMissing = SetupChecks.missingForCurrentCapabilities(context).isNotEmpty(),
            // An overlay window means the phone, not even its own launcher.
            // Android's own lock-task home fallback (a bare list of the allowed
            // apps) is what a person sees on Home instead - there just isn't a
            // real launcher in the allowlist to escape to.
            allowLauncherEscape = scheduleOverlay == null && !bedtimeOverlay && !persistentHome,
            earnAllowed = earnNarrowing(context),
            scheduleOverlayAllowed = when {
                scheduleOverlay != null -> AppRules.alwaysAllowed(context) + scheduleOverlay.allowedApps
                // An overlay bedtime narrows to always-allowed and nothing
                // else: it has no per-window app list of its own, and "the
                // essentials only" is what a hard bedtime means.
                bedtimeOverlay -> AppRules.alwaysAllowed(context)
                else -> null
            }
        ).filter { pkg ->
            pkg == context.packageName || isPackageInstalled(context, pkg)
        }.toTypedArray()

        Applied.ifChanged("lockTaskPackages", lockTaskPackages.sorted().joinToString(",")) {
            try {
                dpm.setLockTaskPackages(admin, lockTaskPackages)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set lock task packages", e)
                false
            }
        }

        val features = lockTaskFeatures(context)
        Applied.ifChanged("lockTaskFeatures", features.toString()) {
            try {
                dpm.setLockTaskFeatures(admin, features)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set lock task features", e)
                false
            }
        }

        applyStatusBar(context, dpm, admin)
        applyPersistentHome(context, dpm, admin, persistentHome)
    }

    /**
     * The lock task allowlist while a required permission is missing:
     * FocusLock and the Settings family, nothing else. No always-allowed
     * apps, no schedule exceptions, no launcher escape - those all belong to
     * the normal running session, and the whole point here is that the
     * session's own rules do not get a vote on this one.
     */
    private fun applyPermissionEmergencyLock(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        // Through the same memo and under the same keys as the normal sync, so
        // the end of the emergency reads as a change and the normal values go
        // back on rather than being skipped as "already applied".
        val packages = (setOf(context.packageName) + SystemSurfaces.settings)
            .filter { it == context.packageName || isPackageInstalled(context, it) }
            .toTypedArray()
        Applied.ifChanged("lockTaskPackages", packages.sorted().joinToString(",")) {
            try {
                dpm.setLockTaskPackages(admin, packages)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set emergency lock task packages", e)
                false
            }
        }
        // Global actions keeps the power/volume keys and the emergency
        // dialler shortcut on the lock screen working; system info keeps
        // the clock. Nothing here offers an exit: no home, no overview,
        // no notifications to swipe into something else.
        val features = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
        Applied.ifChanged("lockTaskFeatures", features.toString()) {
            try {
                dpm.setLockTaskFeatures(admin, features)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set emergency lock task features", e)
                false
            }
        }
        Applied.ifChanged("statusBarDisabled", "true") {
            try {
                dpm.setStatusBarDisabled(admin, true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable status bar for emergency lock", e)
                false
            }
        }
    }

    /** Called from [PermissionRecoveryActivity] itself so it self-pins the moment it's on screen. */
    fun enterPermissionEmergency(activity: Activity) {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(activity, AdminReceiver::class.java)
        if (dpm != null && SetupChecks.isDeviceOwner(activity)) {
            applyPermissionEmergencyLock(activity, dpm, admin)
        }
        syncLockTaskState(activity)
    }

    /** Releases the emergency pin immediately, rather than waiting on the normal debounce. */
    fun exitPermissionEmergency(activity: Activity) {
        PolicySync.applyNow(activity)
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                activity.stopLockTask()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exit emergency lock task", e)
            }
        }
    }

    /**
     * The live narrowing an active Earn task imposes, or null when there is none.
     *
     * A merged task that names no apps returns null on purpose: the underlying
     * mode's allowlist then stands exactly as it was, which is what the user
     * asked for by leaving the field empty.
     */
    private fun earnNarrowing(context: Context): Set<String>? {
        val task = EarnSession.activeTask(context) ?: return null
        val standalone = EarnSession.isStandalone(context)
        if (!standalone && task.allowedApps.isEmpty()) return null
        return EarnSession.allowedPackages(context, task)
    }

    /**
     * The shade, overview and keyguard stay on unless the user asked for them
     * to go. Kiosk with a reachable shade is still a real kiosk; kiosk that
     * silently removed the clock and the notifications would be a surprise.
     */
    private fun lockTaskFeatures(context: Context): Int {
        var features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO

        if (!CapabilityRegistry.isEnabled(context, Capabilities.STATUS_BAR_LOCK)) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
        }
        if (!CapabilityRegistry.isEnabled(context, Capabilities.KEYGUARD_LOCK)) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
        }
        return features
    }

    private fun applyStatusBar(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val disable = SessionManager.shouldLockTask(context) &&
            CapabilityRegistry.isEnabled(context, Capabilities.STATUS_BAR_LOCK)
        Applied.ifChanged("statusBarDisabled", disable.toString()) {
            try {
                dpm.setStatusBarDisabled(admin, disable)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set status bar state", e)
                false
            }
        }
    }

    private fun applyPersistentHome(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        enable: Boolean
    ) {
        Applied.ifChanged("persistentHome", enable.toString()) {
            if (!enable) {
                try {
                    dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear persistent home", e)
                    false
                }
            } else {
                val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                try {
                    dpm.addPersistentPreferredActivity(
                        admin,
                        homeFilter,
                        ComponentName(context, MainActivity::class.java)
                    )
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set persistent home", e)
                    false
                }
            }
        }
    }

    // ── User restrictions ─────────────────────────────────────────

    fun applyRestrictions(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (!SetupChecks.isDeviceOwner(context)) return

        val sessionActive = SessionManager.isActive(context)

        setRestriction(
            dpm, admin, UserManager.DISALLOW_SAFE_BOOT,
            sessionActive && CapabilityRegistry.isEnabled(context, Capabilities.SAFE_BOOT_BLOCK)
        )
        setRestriction(
            dpm, admin, UserManager.DISALLOW_UNINSTALL_APPS,
            sessionActive && CapabilityRegistry.isEnabled(context, Capabilities.UNINSTALL_PROTECTION)
        )
        setRestriction(
            dpm, admin, UserManager.DISALLOW_APPS_CONTROL,
            sessionActive && CapabilityRegistry.isEnabled(context, Capabilities.SUSPEND_BLOCKED_APPS)
        )
        setRestriction(
            dpm, admin, UserManager.DISALLOW_CONFIG_DATE_TIME,
            sessionActive && CapabilityRegistry.isEnabled(context, Capabilities.LOCK_RULES_IN_SESSION)
        )

        // ADB stays exactly where the user's own button left it. Provisioning
        // never fires this, and neither does starting a session.
        setRestriction(
            dpm, admin, UserManager.DISALLOW_DEBUGGING_FEATURES,
            FocusStore.getBool(context, Constants.KEY_ADB_DISABLED, false)
        )

        // Factory reset is the ultimate exit and stays available by design.
        setRestriction(dpm, admin, UserManager.DISALLOW_FACTORY_RESET, false)

        val uninstallBlocked = sessionActive &&
            CapabilityRegistry.isEnabled(context, Capabilities.UNINSTALL_PROTECTION)
        Applied.ifChanged("uninstallBlocked", uninstallBlocked.toString()) {
            try {
                dpm.setUninstallBlocked(admin, context.packageName, uninstallBlocked)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set uninstall block", e)
                false
            }
        }
    }

    private fun setRestriction(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        restriction: String,
        enabled: Boolean
    ) {
        Applied.ifChanged("restriction:" + restriction, enabled.toString()) {
            try {
                if (enabled) dpm.addUserRestriction(admin, restriction)
                else dpm.clearUserRestriction(admin, restriction)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set restriction " + restriction, e)
                false
            }
        }
    }

    // ── Suspension and hiding ─────────────────────────────────────

    fun syncSuspendedApps(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (!SetupChecks.isDeviceOwner(context)) return

        val shouldSuspend = SessionManager.shouldSuspendApps(context)
        val targets = if (shouldSuspend) blockedInstalled(context) else emptySet()
        val previous = FocusStore.getSet(context, KEY_SUSPENDED)

        // A package Android has already refused (the Play Store, as installer
        // of record, on most phones) is retried on the sweep interval, not on
        // every sync: the answer does not change, and each attempt is a binder
        // call and a warning in the log for nothing.
        val retryRefusals = sweepDue(refusalRetryAt)
        if (retryRefusals) refusalRetryAt = SystemClock.elapsedRealtime()
        val refused = if (retryRefusals) emptySet() else unsuspendable(context)
        val toSuspend = targets.filterNot { it in previous || it in refused }
        val toRelease = (previous + strays(context, dpm, admin, targets, shouldSuspend))
            .filterNot { it in targets }
            .filter { isPackageInstalled(context, it) }
        val unchanged = previous.intersect(targets)

        val newlySuspended = if (toSuspend.isNotEmpty()) setSuspended(context, dpm, admin, toSuspend, true) else emptySet()
        val actuallyReleased = if (toRelease.isNotEmpty()) setSuspended(context, dpm, admin, toRelease, false) else emptySet()

        // A package Android refused to release stays tracked so the next sync
        // (every app open triggers one via PolicySync.reapply)
        // tries again. Recording it as released here - which the old code did
        // unconditionally - is exactly what left a real user's app suspended
        // at the OS level forever while FocusLock believed it was clear: the
        // failure was real (setPackagesSuspended's own documented per-package
        // failure list), just silently discarded.
        val stillStuck = toRelease.toSet() - actuallyReleased
        if (stillStuck.isNotEmpty()) {
            Log.w(TAG, "Still suspended after a release attempt, will retry next sync: $stillStuck")
        }
        FocusStore.setSet(context, KEY_SUSPENDED, unchanged + newlySuspended + stillStuck)
    }

    /**
     * Packages the OS still has suspended that FocusLock has forgotten about.
     *
     * The release path walks the set FocusLock *remembers* suspending, which
     * is fine right up until that memory and reality disagree - a suspend that
     * landed but whose bookkeeping never did because the process was killed in
     * between, or state from a build before the return value of
     * setPackagesSuspended was being checked at all. Once a package falls out
     * of that set nothing ever looks at it again, so it stays suspended
     * forever, showing "blocked by your organization" with no session running
     * and nothing in the app that can undo it.
     *
     * So when nothing should be suspended, ask the OS directly rather than
     * trusting the bookkeeping. Scoped to the blocklist rather than every
     * installed app: those are the only packages FocusLock could ever have
     * suspended, and sweeping two hundred packages on every sync would cost
     * two hundred binder calls to fix a rare case.
     */
    private fun strays(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        targets: Set<String>,
        shouldSuspend: Boolean
    ): Set<String> {
        if (shouldSuspend) return emptySet()
        if (!sweepDue(suspendedSweepAt)) return emptySet()
        suspendedSweepAt = SystemClock.elapsedRealtime()
        val candidates = AppRules.blockedPackages(context) - targets
        if (candidates.isEmpty()) return emptySet()
        val found = LinkedHashSet<String>()
        for (pkg in candidates) {
            if (!isPackageInstalled(context, pkg)) continue
            try {
                if (dpm.isPackageSuspended(admin, pkg)) found.add(pkg)
            } catch (_: Exception) {
                // NameNotFoundException, or an OEM that refuses the query.
            }
        }
        if (found.isNotEmpty()) {
            Log.w(TAG, "Found suspended packages FocusLock had lost track of, releasing: $found")
        }
        return found
    }

    /**
     * Returns the subset of [packages] Android actually confirmed changed.
     *
     * One package at a time, deliberately. A single batched call means one
     * package the OS won't touch (or a stale/uninstalled one that slipped
     * past the installed-check in a race) can throw for the whole array,
     * silently taking every other package in the batch down with it - a
     * second, independent way the earlier "not allowed by your organization"
     * bug could have happened even after the return-value fix at the call
     * site.
     */
    private fun setSuspended(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packages: Collection<String>,
        suspended: Boolean
    ): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val changed = LinkedHashSet<String>()
        val refused = LinkedHashSet<String>()
        for (pkg in packages) {
            try {
                val failed = dpm.setPackagesSuspended(admin, arrayOf(pkg), suspended)
                if (failed.isNullOrEmpty()) {
                    changed.add(pkg)
                } else {
                    if (suspended) refused.add(pkg)
                    Log.w(TAG, "Android refused to " + (if (suspended) "suspend " else "un-suspend ") + pkg)
                }
            } catch (e: Exception) {
                if (suspended) refused.add(pkg)
                Log.w(TAG, "Failed to change suspension state for $pkg", e)
            }
        }
        if (suspended) recordUnsuspendable(context, refused, changed)
        return changed
    }

    /**
     * Remembers which packages Android will not let us suspend.
     *
     * Android protects a handful of packages from suspension outright - the
     * active launcher, the default dialler and SMS app, and the installer of
     * record, which on almost every phone means the Play Store. The call
     * returns them in its failure list rather than throwing, so for years the
     * only symptom was "I blocked the Play Store and it still opens": the
     * suspend quietly did nothing and the UI carried on saying Blocked.
     *
     * It is still blocked - the enforcement loop intercepts it like any other
     * app - it just cannot be stopped at the OS level, and now the app knows
     * which apps those are so it can both say so and stop throttling their
     * intercept. See [isUnsuspendable].
     */
    private fun recordUnsuspendable(context: Context, refused: Set<String>, succeeded: Set<String>) {
        val known = FocusStore.getSet(context, KEY_UNSUSPENDABLE)
        val next = (known + refused) - succeeded
        if (next != known) FocusStore.setSet(context, KEY_UNSUSPENDABLE, next)
    }

    /** Packages the OS has refused to suspend, so enforcement knows not to rely on it. */
    fun unsuspendable(context: Context): Set<String> = FocusStore.getSet(context, KEY_UNSUSPENDABLE)

    fun isUnsuspendable(context: Context, packageName: String): Boolean =
        packageName in FocusStore.getSet(context, KEY_UNSUSPENDABLE)

    /** What a hidden-app sweep should do about one package. */
    enum class HiddenAction { HIDE, REVEAL, KEEP }

    /**
     * The hidden-app reconciliation, as a pure function of what is desired and
     * what the phone actually reports.
     *
     * Pure for the same reason [buildLockTaskPackages] is: this is the rule that
     * went wrong. The old version diffed the desired set against FocusLock's own
     * *memory* of what it had asked for, so when the memory and reality
     * disagreed it did not correct anything - it revealed the app, decided on
     * the next sweep that it should be hidden, hid it, and looped forever while
     * the icon blinked on the launcher.
     *
     * Two properties fall out of computing it this way, and both are checked in
     * `HiddenReconciliationTest`:
     *
     *  - a package whose real state already matches the desired state yields
     *    [HiddenAction.KEEP], so a sweep that changes nothing makes no binder
     *    calls at all;
     *  - the plan depends only on the *current* desired set and the *current*
     *    reported state, never on what a previous sweep believed, so running it
     *    twice cannot produce two different answers.
     *
     * [isHidden] returns null when the platform refuses the query for that
     * package; those are left alone rather than guessed at.
     */
    fun planHiddenReconciliation(
        desired: Set<String>,
        previous: Set<String>,
        isInstalled: (String) -> Boolean,
        isHidden: (String) -> Boolean?
    ): List<Pair<String, HiddenAction>> {
        val candidates = LinkedHashSet<String>()
        candidates.addAll(desired)
        candidates.addAll(previous)

        val plan = ArrayList<Pair<String, HiddenAction>>(candidates.size)
        for (pkg in candidates) {
            if (!isInstalled(pkg)) continue
            val hidden = isHidden(pkg) ?: continue
            val want = pkg in desired
            plan.add(pkg to when {
                hidden == want -> HiddenAction.KEEP
                want -> HiddenAction.HIDE
                else -> HiddenAction.REVEAL
            })
        }
        return plan
    }

    /**
     * Reconciles the phone's hidden-app set against what the user actually
     * asked for.
     *
     * [KEY_HIDDEN] is *intent* (what should be hidden), plus anything the OS
     * refused to reveal. Recording intent rather than "what we asked for and
     * think worked" is what stops a refused reveal from being forgotten: the
     * package stays a candidate, so a later sweep retries it instead of leaving
     * it invisible with nothing left to bring it back.
     */
    fun syncHiddenApps(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (!SetupChecks.isDeviceOwner(context)) return

        val shouldHide = SessionManager.shouldHideApps(context)
        val desired = if (shouldHide) {
            AppRules.hiddenPackages(context).filter { AppCatalog.isInstalled(context, it) }.toSet()
        } else {
            emptySet()
        }

        val previous = FocusStore.getSet(context, KEY_HIDDEN)

        // Only sweep the whole phone when we are *not* actively hiding. While a
        // session runs, the desired set is authoritative and enumerating two
        // hundred packages on every policy tick would be pure cost.
        val strays = if (shouldHide || !sweepDue(hiddenSweepAt)) {
            emptySet()
        } else {
            hiddenSweepAt = SystemClock.elapsedRealtime()
            hiddenStrays(context, dpm, admin, desired)
        }

        val plan = planHiddenReconciliation(
            desired = desired,
            previous = previous + strays,
            isInstalled = { AppCatalog.isInstalled(context, it) },
            isHidden = { pkg ->
                try {
                    dpm.isApplicationHidden(admin, pkg)
                } catch (_: Exception) {
                    null
                }
            }
        )

        val tracked = LinkedHashSet<String>()
        for ((pkg, action) in plan) {
            when (action) {
                HiddenAction.KEEP ->
                    // Already exactly as we want it. Track it only if it is
                    // hidden, so a later sweep can still reveal it.
                    if (pkg in desired) tracked.add(pkg)
                HiddenAction.HIDE -> {
                    if (setHidden(dpm, admin, pkg, true)) tracked.add(pkg)
                    else Log.w(TAG, "Could not hide $pkg, will retry next sync")
                }
                HiddenAction.REVEAL -> {
                    if (!setHidden(dpm, admin, pkg, false)) {
                        // Keep tracking it: the next sweep tries again rather
                        // than abandoning an app in an invisible state.
                        tracked.add(pkg)
                        Log.w(TAG, "Could not reveal $pkg, will retry next sync")
                    }
                }
            }
        }

        FocusStore.setSet(context, KEY_HIDDEN, tracked)
    }

    /**
     * Packages the OS still has hidden that FocusLock is not currently tracking.
     *
     * Only meaningful when we are *not* hiding: while a session is running the
     * desired set is authoritative and this would be pure extra binder calls.
     *
     * The candidate universe is deliberately wider than "the current
     * blocklist". A package can be hidden and then stop being blocked - the
     * person re-labels it, or the category rule that caught it is cleared - and
     * at that moment it is no longer in [AppRules.hiddenPackages], so a sweep
     * that only looked at the blocklist would walk straight past an app that is
     * still invisible on the phone with nothing left that would ever reveal it.
     * Anything with *any* explicit policy is worth asking about, because any of
     * them could have been a HIDE or a category-HIDE at some earlier point.
     */
    private fun hiddenStrays(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        desired: Set<String>
    ): Set<String> {
        val candidates = LinkedHashSet<String>()
        candidates.addAll(AppRules.allPolicies(context).keys)
        candidates.addAll(AppRules.blockedPackages(context))
        // Belt and braces: a package can be hidden and then have its policy
        // deleted outright, at which point no policy-derived list mentions it
        // any more. Enumerating the phone is the only way to guarantee nothing
        // stays invisible. This is ~200 binder-free PackageManager entries and
        // only runs when we are not hiding, so it is not on the hot path.
        candidates.addAll(AppCatalog.allPackageNames(context))
        candidates.removeAll(desired)
        candidates.remove(context.packageName)
        if (candidates.isEmpty()) return emptySet()

        val found = LinkedHashSet<String>()
        for (pkg in candidates) {
            if (!AppCatalog.isInstalled(context, pkg)) continue
            try {
                if (dpm.isApplicationHidden(admin, pkg)) found.add(pkg)
            } catch (_: Exception) {
                // An OEM that refuses the query; nothing to recover here.
            }
        }
        if (found.isNotEmpty()) {
            Log.w(TAG, "Found hidden packages FocusLock had lost track of, revealing: $found")
        }
        return found
    }

    private fun setHidden(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
        hidden: Boolean
    ): Boolean {
        return try {
            dpm.setApplicationHidden(admin, packageName, hidden)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to change hidden state for " + packageName, e)
            false
        }
    }

    /** Blocked apps that are actually on the phone, never including our own. */
    private fun blockedInstalled(context: Context): Set<String> =
        AppRules.blockedPackages(context)
            .filter { it != context.packageName && isPackageInstalled(context, it) }
            .toSet()

    // ── Browser sandbox ───────────────────────────────────────────

    /**
     * Holds every managed browser to the user's own allowlist.
     *
     * The safe browser inside FocusLock enforces the same list in-process; this
     * is the belt to that pair of braces, for the case where a stock browser is
     * still reachable.
     */
    fun syncBrowserSandbox(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (!SetupChecks.isDeviceOwner(context)) return

        // Gated on a session, like every other rule.
        //
        // This was the last piece of enforcement still running on the
        // capability switch alone, and it is the most invisible one: Chrome's
        // URLBlocklist/URLAllowlist is applied through setApplicationRestrictions,
        // which *persists inside Chrome* until something changes it. So once
        // Web blocking had ever been switched on, Chrome stayed pinned to the
        // allowlist permanently - at breakfast, on a Sunday, with nothing
        // running - and no amount of "there is no session" made any difference,
        // because nothing was ever asking. That is "the web block works before
        // the session starts".
        val enforce = CapabilityRegistry.isEnabled(context, Capabilities.WEB_BLOCK) &&
            SessionManager.isEnforcing(context)
        val allowlist = if (enforce) AllowlistStore.getWebAllowlistDomains(context) else emptyArray()
        val restrictions = Bundle()
        if (enforce) {
            restrictions.putStringArray("URLBlocklist", arrayOf("*"))
            restrictions.putStringArray("URLAllowlist", allowlist)
        }
        // Each of these makes the browser reload its whole policy, and it used
        // to go to all five browsers on every sync. See [Applied].
        val signature = if (enforce) "on:" + allowlist.joinToString(",") else "off"

        CHROME_PACKAGES.forEach { pkg ->
            Applied.ifChanged("browser:" + pkg, signature) {
                try {
                    dpm.setApplicationRestrictions(admin, pkg, restrictions)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set browser restrictions for " + pkg, e)
                    false
                }
            }
        }
    }

    /**
     * Removes the browser sandbox outright, synchronously.
     *
     * [syncBrowserSandbox] already clears the restrictions when nothing is
     * enforcing, but it only runs inside the debounced policy sync - 250ms
     * later on a worker thread. `setApplicationRestrictions` persists *inside
     * the browser* until something changes it, and ending a session is exactly
     * when the foreground service that kept the process alive is going away, so
     * a process killed inside that window leaves Chrome (and Edge, and Samsung
     * Internet) pinned to the allowlist with every other site blocked, no
     * session running, and nothing in the app that would ever clear it.
     *
     * Same class of bug as the suspension release, and the same answer: on
     * teardown, ask the platform directly rather than trusting a scheduled job
     * to still be alive when it fires.
     */
    fun clearBrowserSandbox(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val empty = Bundle()
        CHROME_PACKAGES.forEach { pkg ->
            // Unconditional on purpose - this is the teardown that must never
            // be skipped - but recorded, so the syncs after it do not repeat it.
            try {
                dpm.setApplicationRestrictions(admin, pkg, empty)
                Applied.ifChanged("browser:" + pkg, "off") { true }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear browser restrictions for " + pkg, e)
            }
        }
    }

    // ── Teardown ──────────────────────────────────────────────────

    fun clearDeviceOwnerKioskPolicies(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName
    ) {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        // Everything below writes directly, so the memo no longer describes
        // the phone.
        Applied.forgetAll()

        try {
            dpm.setStatusBarDisabled(admin, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-enable status bar", e)
        }

        try {
            val features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
            dpm.setLockTaskFeatures(admin, features)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset lock task features", e)
        }

        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear persistent home", e)
        }

        try {
            dpm.setLockTaskPackages(admin, emptyArray())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear lock task packages", e)
        }

        releaseAllManagedApps(context, dpm, admin)
        // Also on teardown, and also synchronously: browser restrictions live
        // inside the browser, so leaving this to the debounced sync is how a
        // phone ends a session with every site still blocked.
        clearBrowserSandbox(context, dpm, admin)
    }

    /** Puts every suspended or hidden app back exactly as it was found. */
    fun releaseAllManagedApps(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        // A release is exactly when bookkeeping and reality are most likely to
        // have drifted, so the sync that follows asks the OS directly.
        requestStraySweep()

        // Same rule as syncSuspendedApps/syncHiddenApps: don't mark a package
        // released just because we asked - only because Android confirmed it.
        // Unconditionally clearing to emptySet() here (the old behaviour) is
        // exactly how a package the OS refused to un-suspend got permanently
        // lost track of at session end, with no sync ever retrying it again.
        val suspended = FocusStore.getSet(context, KEY_SUSPENDED).filter { isPackageInstalled(context, it) }
        val stillSuspended = if (suspended.isNotEmpty()) {
            suspended.toSet() - setSuspended(context, dpm, admin, suspended, false)
        } else {
            emptySet()
        }
        FocusStore.setSet(context, KEY_SUSPENDED, stillSuspended)

        val hidden = FocusStore.getSet(context, KEY_HIDDEN).filter { isPackageInstalled(context, it) }
        val stillHidden = hidden.filterNot { setHidden(dpm, admin, it, false) }.toSet()
        FocusStore.setSet(context, KEY_HIDDEN, stillHidden)
    }

    // ── Activity-side lock task ───────────────────────────────────

    fun syncLockTaskState(activity: Activity) {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return
        val kioskEnforced = SessionManager.shouldLockTask(activity) || PermissionGuard.isEmergency(activity)
        val activityManager = activity.getSystemService(ActivityManager::class.java)
        val isInLockTask = activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        val canManageLockTask =
            SetupChecks.isDeviceOwner(activity) && dpm.isLockTaskPermitted(activity.packageName)

        if (isInLockTask && !canManageLockTask) {
            try {
                activity.stopLockTask()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exit non-permitted lock task", e)
            }
            return
        }

        if (kioskEnforced && !isInLockTask && canManageLockTask) {
            try {
                activity.startLockTask()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enter lock task", e)
            }
            return
        }

        if (!kioskEnforced && isInLockTask) {
            try {
                activity.stopLockTask()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exit lock task", e)
            }
        }
    }

    /**
     * Delegates to [AppCatalog.isInstalled], which is the one implementation
     * that can see a package FocusLock has itself hidden. Kept as a named
     * forwarder because every sweep in this file reads better for it.
     */
    fun isPackageInstalled(context: Context, pkg: String): Boolean =
        AppCatalog.isInstalled(context, pkg)

    private const val KEY_SUSPENDED = "policy_suspended_packages"
    private const val KEY_HIDDEN = "policy_hidden_packages"
    private const val KEY_UNSUSPENDABLE = "policy_unsuspendable_packages"
}
