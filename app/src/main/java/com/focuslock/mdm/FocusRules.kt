package com.focuslock.mdm

import androidx.annotation.StringRes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class RuleTargetType(val id: String, @StringRes val labelRes: Int) {
    APP("app", R.string.ruletarget_app_label),
    CATEGORY("category", R.string.ruletarget_category_label),
    ALL("all", R.string.ruletarget_all_label);

    fun label(context: Context): String = context.getString(labelRes)

    companion object {
        fun fromId(id: String?): RuleTargetType = values().firstOrNull { it.id == id } ?: APP
    }
}

enum class RuleConditionType(val id: String, @StringRes val labelRes: Int, @StringRes val blurbRes: Int) {
    ALWAYS("always", R.string.rulecond_always_label, R.string.rulecond_always_blurb),
    TIME("time", R.string.rulecond_time_label, R.string.rulecond_time_blurb),
    DAYS("days", R.string.rulecond_days_label, R.string.rulecond_days_blurb),
    PLACE("place", R.string.rulecond_place_label, R.string.rulecond_place_blurb),
    WIFI("wifi", R.string.rulecond_wifi_label, R.string.rulecond_wifi_blurb),
    USAGE_OVER("usageOver", R.string.rulecond_usage_over_label, R.string.rulecond_usage_over_blurb),
    OPENS_OVER("opensOver", R.string.rulecond_opens_over_label, R.string.rulecond_opens_over_blurb),
    SESSION_ONLY("sessionOnly", R.string.rulecond_session_only_label, R.string.rulecond_session_only_blurb);

    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)

    companion object {
        fun fromId(id: String?): RuleConditionType = values().firstOrNull { it.id == id } ?: ALWAYS
    }
}

enum class RuleAction(val id: String, @StringRes val labelRes: Int, @StringRes val blurbRes: Int) {
    ALLOW("allow", R.string.ruleaction_allow_label, R.string.ruleaction_allow_blurb),
    FRICTION("friction", R.string.ruleaction_friction_label, R.string.ruleaction_friction_blurb),
    BLOCK("block", R.string.ruleaction_block_label, R.string.ruleaction_block_blurb),
    ALLOW_TEMP("allowTemp", R.string.ruleaction_allow_temp_label, R.string.ruleaction_allow_temp_blurb);

    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)

    companion object {
        fun fromId(id: String?): RuleAction = values().firstOrNull { it.id == id } ?: BLOCK
    }
}

/**
 * One user-written rule: a target, a condition, an outcome.
 *
 * Rules are evaluated top to bottom and the first match wins, which is the only
 * ordering model people reliably predict. The list is drag-free but reorderable
 * with explicit move up / move down, so the priority is always visible.
 */
data class Rule(
    val id: String,
    val label: String,
    val targetType: RuleTargetType,
    val targetValue: String,
    val conditionType: RuleConditionType,
    val conditionValue: String,
    val conditionStart: Int,
    val conditionEnd: Int,
    val conditionDays: Set<Int>,
    val conditionNumber: Int,
    val action: RuleAction,
    val actionMinutes: Int,
    val enabled: Boolean
)

object RuleStore {

    private const val KEY_RULES = "focus_rules_json"

    /** Cached per policy revision: the enforcement loop walks this every tick. */
    fun all(context: Context): List<Rule> = PolicyCache.get("focusRules") {
        val array = FocusStore.getJsonArray(context, KEY_RULES)
        val out = ArrayList<Rule>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val days = FocusStore.jsonArrayToStringList(obj.optJSONArray("days") ?: JSONArray())
                .mapNotNull { it.toIntOrNull() }
                .toSet()
            out.add(
                Rule(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    label = obj.optString("label", ""),
                    targetType = RuleTargetType.fromId(obj.optString("targetType", "")),
                    targetValue = obj.optString("targetValue", ""),
                    conditionType = RuleConditionType.fromId(obj.optString("conditionType", "")),
                    conditionValue = obj.optString("conditionValue", ""),
                    conditionStart = obj.optInt("start", 0),
                    conditionEnd = obj.optInt("end", 0),
                    conditionDays = days,
                    conditionNumber = obj.optInt("number", 0),
                    action = RuleAction.fromId(obj.optString("action", "")),
                    actionMinutes = obj.optInt("actionMinutes", 5),
                    enabled = obj.optBoolean("enabled", true)
                )
            )
        }
        out
    }

    /**
     * A custom rule can carry action ALLOW against target ALL, condition
     * ALWAYS - a standing "let everything through" that outranks the mode
     * running the session. Frozen-gated for the same reason AppRules and
     * CapabilityRegistry are: editing this list mid-session is not a
     * settings change, it is an escape hatch, and it was the one rule store
     * the original freeze work never reached.
     */
    fun save(context: Context, rules: List<Rule>): Boolean {
        if (!SessionLock.allows(context, directionOf(all(context), rules))) return false
        val array = JSONArray()
        rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("label", rule.label)
            obj.put("targetType", rule.targetType.id)
            obj.put("targetValue", rule.targetValue)
            obj.put("conditionType", rule.conditionType.id)
            obj.put("conditionValue", rule.conditionValue)
            obj.put("start", rule.conditionStart)
            obj.put("end", rule.conditionEnd)
            obj.put("days", FocusStore.stringListToJsonArray(rule.conditionDays.map { it.toString() }))
            obj.put("number", rule.conditionNumber)
            obj.put("action", rule.action.id)
            obj.put("actionMinutes", rule.actionMinutes)
            obj.put("enabled", rule.enabled)
            array.put(obj)
        }
        FocusStore.setJsonArray(context, KEY_RULES, array)
        PolicySync.request(context, "rules")
        return true
    }

    /**
     * Custom rules cut both ways, so the direction has to look at what each
     * rule *does*, not just whether the list grew.
     *
     * A loosening is: dropping or disabling a rule that was blocking something,
     * or adding a new rule that lets something through (ALLOW / "allow for a
     * few minutes"). Everything else - a new BLOCK, a new pause, re-ordering
     * two blocks - only ever narrows what is reachable, and goes through
     * mid-session.
     */
    private fun directionOf(before: List<Rule>, after: List<Rule>): EditDirection {
        val blocks = { rule: Rule -> rule.action == RuleAction.BLOCK || rule.action == RuleAction.FRICTION }
        val afterById = after.associateBy { it.id }

        val lostABlock = before.any { previous ->
            if (!previous.enabled || !blocks(previous)) return@any false
            val now = afterById[previous.id]
            now == null || !now.enabled || !blocks(now)
        }
        if (lostABlock) return EditDirection.LOOSEN

        val beforeIds = before.map { it.id }.toSet()
        val gainedAnException = after.any { rule ->
            rule.id !in beforeIds && rule.enabled && !blocks(rule)
        }
        if (gainedAnException) return EditDirection.LOOSEN

        // An existing rule flipped from blocking to allowing keeps its id, so
        // neither check above catches it on its own.
        val flippedToAllow = after.any { rule ->
            val previous = before.firstOrNull { it.id == rule.id } ?: return@any false
            rule.enabled && !blocks(rule) && previous.enabled && blocks(previous)
        }
        return if (flippedToAllow) EditDirection.LOOSEN else EditDirection.TIGHTEN
    }

    fun add(context: Context, rule: Rule): Boolean = save(context, all(context) + rule)

    fun update(context: Context, rule: Rule): Boolean =
        save(context, all(context).map { if (it.id == rule.id) rule else it })

    fun remove(context: Context, id: String): Boolean =
        save(context, all(context).filterNot { it.id == id })

    fun move(context: Context, id: String, delta: Int): Boolean {
        val rules = all(context).toMutableList()
        val index = rules.indexOfFirst { it.id == id }
        if (index < 0) return false
        val target = (index + delta).coerceIn(0, rules.size - 1)
        if (target == index) return false
        val rule = rules.removeAt(index)
        rules.add(target, rule)
        return save(context, rules)
    }

    fun newRule(
        label: String,
        targetType: RuleTargetType,
        targetValue: String,
        conditionType: RuleConditionType,
        action: RuleAction
    ): Rule = Rule(
        id = UUID.randomUUID().toString(),
        label = label,
        targetType = targetType,
        targetValue = targetValue,
        conditionType = conditionType,
        conditionValue = "",
        conditionStart = 9 * 60,
        conditionEnd = 17 * 60,
        conditionDays = emptySet(),
        conditionNumber = 30,
        action = action,
        actionMinutes = 5,
        enabled = true
    )

    fun exportJson(context: Context): JSONArray = FocusStore.getJsonArray(context, KEY_RULES)

    fun importJson(context: Context, array: JSONArray): Boolean {
        if (SessionLock.isFrozen(context)) return false
        FocusStore.setJsonArray(context, KEY_RULES, array)
        PolicySync.request(context, "rules:import")
        return true
    }
}

/** Why an app was stopped, and what the person can do about it. */
enum class GuardOutcome {
    ALLOW,
    PAUSE,
    BLOCK
}

data class GuardDecision(
    val packageName: String,
    val outcome: GuardOutcome,
    val headline: String,
    val detail: String,
    val source: String,
    val offersBreak: Boolean,
    val breakSuggestion: Boolean = false,
    /**
     * Minutes left in the earned pool, or -1 when the pool has nothing to do
     * with this decision.
     *
     * Only ever set for a Budgeted app. That block is always raised *because*
     * the pool is empty, but the screen still shows the number rather than a
     * bare refusal: someone who can see they are three minutes short knows
     * exactly what to do about it, and someone told only "no" does not.
     */
    val poolMinutes: Int = -1,
    val breakMinutesOverride: Int = 0
) {
    val isBlocked: Boolean get() = outcome == GuardOutcome.BLOCK
    val isPause: Boolean get() = outcome == GuardOutcome.PAUSE
    val isAllowed: Boolean get() = outcome == GuardOutcome.ALLOW
}

/**
 * The one place that answers "can this app be in front right now".
 *
 * Everything that used to be scattered through `AppBlockerService.isAllowed`
 * lives here, reads from user-owned stores, and returns a decision carrying the
 * words the intercept screen will show. Enforcement never writes its own copy.
 */
object RuleEngine {

    /**
     * [sessionActiveOverride], when non-null, replaces every internal read of
     * whether a session is running - used only by [TestMode]'s "test the
     * block" preview, so it can ask "what would this decide right now if a
     * session were active" using this exact, real decision logic rather than
     * a second copy of it that could drift out of sync. Every other caller
     * passes nothing and gets the real, unmodified answer; nothing about
     * physical enforcement (lock-task, suspend/hide, the rule freeze) reads
     * this parameter or is reachable from it.
     */
    fun decide(
        context: Context,
        packageName: String,
        className: String? = null,
        sessionActiveOverride: Boolean? = null
    ): GuardDecision {
        val allow = { source: String ->
            GuardDecision(packageName, GuardOutcome.ALLOW, "", "", source, false)
        }

        if (packageName == context.packageName) return allow("self")
        if (SystemSurfaces.isCritical(packageName)) return allow("system")

        // The owner's own rules (personal build only): AI chat apps never open, and around
        // every prayer only calls and the Quran do.
        if (Personal.on) {
            if (packageName in Personal.AI_PACKAGES) {
                return GuardDecision(packageName, GuardOutcome.BLOCK, "AI chat is off on this phone",
                    "Use it on the laptop, 8 am to 4 pm, or any time on Thursday and Friday.", "personalAi", offersBreak = false)
            }
            Personal.salahWindow()?.let { (name, at) ->
                if (!Personal.allowedInSalah(packageName)) {
                    val back = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        .format(java.util.Date(at + Personal.AFTER_MIN * 60_000L))
                    return GuardDecision(packageName, GuardOutcome.BLOCK, "Time for $name",
                        "The phone opens again at $back. Calls and the Quran stay available.", "personalSalah", offersBreak = false)
                }
            }
        }

        // Settings pages the user opened from inside FocusLock, plus the USB
        // page, stay reachable: locking someone out of their own USB settings
        // during a 90-day session is how a phone becomes a brick.
        if (SystemSurfaces.isUsbSettingsScreen(packageName, className)) return allow("usb")
        if (LockManager.isSettingsAccessAllowed(context) && SystemSurfaces.isSettings(packageName)) {
            return allow("settingsWindow")
        }
        if (SystemSurfaces.isOverlayPermissionScreen(packageName, className)) {
            return GuardDecision(
                packageName,
                GuardOutcome.BLOCK,
                Copy.overlayGuardHeadline(context),
                Copy.overlayGuardDetail(context),
                "overlayGuard",
                offersBreak = false
            )
        }

        if (AppRules.isAlwaysAllowed(context, packageName)) return allow("alwaysAllowed")

        // Outside a session every blocker below - schedule windows, bedtime,
        // places, limits, budgets and custom rules - waits on the one master
        // switch "Let blockers work outside a session" (default off). Inside
        // a session the switch is irrelevant and nothing here changes.
        val mayAct = Standalone.mayActNow(context, sessionActiveOverride)

        // An overlay window is absolute: not a break pass (even one granted
        // before the window started), not Earn, not a place or a custom rule -
        // nothing below this gets a vote. Checked before everything else that
        // could otherwise let something through, which is the one thing that
        // actually makes "overlay" mean dead phone rather than a slightly
        // stronger version of the ordinary schedule block further down. See
        // [ScheduleWindow.overlay] and [KioskPolicy.buildLockTaskPackages] for
        // the matching Device-Owner lock-task side of this.
        if (mayAct) ScheduleManager.activeWindowIfEnabled(context)?.takeIf { it.overlay }?.let { window ->
            val allowed = AppRules.alwaysAllowed(context) + window.allowedApps
            if (packageName !in allowed) {
                return GuardDecision(
                    packageName,
                    GuardOutcome.BLOCK,
                    Copy.scheduleOverlayHeadline(context, window),
                    Copy.scheduleOverlayDetail(context, window),
                    "scheduleOverlay",
                    offersBreak = false
                )
            }
            return allow("scheduleOverlayAllowed")
        }

        // An overlay bedtime is the same absolute thing as an overlay window,
        // so it is checked in the same place - above the break pass, Earn and
        // everything else that could otherwise let something through. Checking
        // it down with the ordinary bedtime block (where it used to be) would
        // have left a break pass granted at 9pm still working at midnight.
        if (mayAct && Bedtime.isOverlay(context) && Bedtime.blocks(context, packageName)) {
            return GuardDecision(
                packageName,
                GuardOutcome.BLOCK,
                Copy.bedtimeHeadline(context),
                Copy.bedtimeDetail(context),
                "bedtimeOverlay",
                offersBreak = false
            )
        }

        if (TakeABreak.hasActivePass(context, packageName)) return allow("break")

        // An Earn task narrows before anything else gets a say. Checking it here
        // rather than last gives intersection semantics: the task can only ever
        // subtract from what the running mode already permits, never add to it,
        // and the message the person sees names the task they chose.
        EarnSession.activeTask(context)?.let { task ->
            val narrows = EarnSession.isStandalone(context) || task.allowedApps.isNotEmpty()
            if (narrows && packageName !in EarnSession.allowedPackages(context, task)) {
                return GuardDecision(
                    packageName,
                    GuardOutcome.BLOCK,
                    Copy.earnHeadline(context, packageName),
                    Copy.earnDetail(context, task),
                    "earn",
                    offersBreak = false
                )
            }
        }

        // Schedules and bedtime run on the clock, but only inside a session -
        // the gate above has already turned everything below into a no-op when
        // nothing is running. An overlay window already returned above,
        // absolutely; this is only ever reached for the ordinary, escapable kind.
        if (mayAct) ScheduleManager.activeWindowIfEnabled(context)?.takeIf { !it.overlay }?.let { window ->
            val allowed = AppRules.alwaysAllowed(context) + window.allowedApps
            if (packageName !in allowed) {
                return GuardDecision(
                    packageName,
                    GuardOutcome.BLOCK,
                    Copy.scheduleHeadline(context, window),
                    Copy.scheduleDetail(context, window),
                    "schedule",
                    offersBreak = TakeABreak.canStart(context)
                )
            }
            return allow("scheduleAllowed")
        }

        if (mayAct && Bedtime.blocks(context, packageName)) {
            return GuardDecision(
                packageName,
                GuardOutcome.BLOCK,
                Copy.bedtimeHeadline(context),
                Copy.bedtimeDetail(context),
                "bedtime",
                // An overlay bedtime is absolute, the same as an overlay
                // schedule window: no break pass out of it, or "overlay" would
                // just mean "a slightly firmer bedtime".
                offersBreak = !Bedtime.isOverlay(context) && TakeABreak.canStart(context)
            )
        }

        if (mayAct) PlaceRules.blocks(context, packageName)?.let { place ->
            return GuardDecision(
                packageName,
                GuardOutcome.BLOCK,
                Copy.placeHeadline(context, place),
                Copy.placeDetail(context, place),
                "place",
                offersBreak = TakeABreak.canStart(context)
            )
        }

        // A daily budget still counts every minute of the day - the usage it
        // reads is the phone's own - it just cannot *act* outside a session,
        // same as everything else here.
        //
        // A **Budgeted** app ([AppPolicy.LIMIT]) is the deliberate exception.
        // Its daily minutes are only a free allowance, so running them out is
        // not the end of the app, it is the moment the earned pool starts
        // paying. Letting this block fire for a Budgeted app would make the
        // pool unreachable, which is the whole thing the pool exists for.
        val budgeted = AppRules.effectivePolicy(context, packageName) == AppPolicy.LIMIT
        if (!budgeted && mayAct) {
            AppLimits.exhaustedReason(context, packageName)?.let { reason ->
                return GuardDecision(
                    packageName,
                    GuardOutcome.BLOCK,
                    Copy.limitHeadline(context, packageName),
                    reason,
                    "limit",
                    offersBreak = TakeABreak.canStart(context)
                )
            }
        } else if (budgeted && EarnMode.isEnabled(context) && mayAct) {
            val budgetCheck = budgetedDecision(context, packageName)
            if (!budgetCheck.isAllowed) return budgetCheck
        }

        if (CapabilityRegistry.isEnabled(context, Capabilities.RULE_ENGINE) && mayAct) {
            matchRule(context, packageName, sessionActiveOverride)?.let { match ->
                when (match.action) {
                    RuleAction.ALLOW -> return allow("rule:" + match.id)
                    RuleAction.ALLOW_TEMP -> {
                        if (TakeABreak.hasActivePass(context, packageName)) return allow("rule:temp")
                        return GuardDecision(
                            packageName,
                            GuardOutcome.BLOCK,
                            Copy.ruleHeadline(context, match),
                            Copy.ruleDetail(context, match),
                            "rule",
                            offersBreak = true,
                            breakSuggestion = true,
                            breakMinutesOverride = match.actionMinutes.coerceIn(1, 60)
                        )
                    }
                    RuleAction.FRICTION -> return GuardDecision(
                        packageName,
                        GuardOutcome.PAUSE,
                        Copy.pauseHeadline(context, packageName),
                        Copy.ruleDetail(context, match),
                        "rule",
                        offersBreak = false
                    )
                    RuleAction.BLOCK -> return GuardDecision(
                        packageName,
                        GuardOutcome.BLOCK,
                        Copy.ruleHeadline(context, match),
                        Copy.ruleDetail(context, match),
                        "rule",
                        offersBreak = TakeABreak.canStart(context)
                    )
                }
            }
        }

        // Standing app rules (Blocked apps list, Launch Friction, and Kiosk
        // allowlist inversion) apply when a Focus Session or Earn task is running.
        if (!SessionManager.isEnforcing(context, sessionActiveOverride)) return allow("noSession")

        // Kiosk inverts the model: only what you named stays open. Driven by
        // the lock-task primitive rather than the enum directly, so softening a
        // mode in Advanced cannot accidentally strand someone inside an
        // allowlist they can no longer edit.
        if (SessionManager.usesAllowlistModel(context, sessionActiveOverride) && AppRules.isKioskAllowlistMode(context)) {
            if (packageName !in AppRules.kioskAllowlist(context)) {
                if (SystemSurfaces.isLauncher(packageName)) {
                    return GuardDecision(
                        packageName,
                        GuardOutcome.BLOCK,
                        Copy.kioskHomeHeadline(context),
                        Copy.kioskHomeDetail(context),
                        "kioskHome",
                        offersBreak = false
                    )
                }
                return GuardDecision(
                    packageName,
                    GuardOutcome.BLOCK,
                    Copy.blockHeadline(context, packageName),
                    Copy.kioskDetail(context),
                    "kiosk",
                    offersBreak = false
                )
            }
            return allow("kioskAllowlist")
        }

        if (SystemSurfaces.isLauncher(packageName)) return allow("launcher")

        if (!CapabilityRegistry.isEnabled(context, Capabilities.APP_BLOCK)) return allow("appBlockOff")

        return when (AppRules.effectivePolicy(context, packageName)) {
            AppPolicy.ALLOW -> allow("policyAllow")
            AppPolicy.LIMIT -> budgetedDecision(context, packageName)
            AppPolicy.FRICTION -> {
                if (!CapabilityRegistry.isEnabled(context, Capabilities.LAUNCH_FRICTION)) {
                    allow("frictionOff")
                } else {
                    GuardDecision(
                        packageName,
                        GuardOutcome.PAUSE,
                        Copy.pauseHeadline(context, packageName),
                        Copy.pauseDetail(context),
                        "friction",
                        offersBreak = false
                    )
                }
            }
            AppPolicy.BLOCK, AppPolicy.HIDE -> {
                if (!SessionManager.blocksOutright(context, sessionActiveOverride)) {
                    GuardDecision(
                        packageName,
                        GuardOutcome.PAUSE,
                        Copy.pauseHeadline(context, packageName),
                        Copy.softDetail(context),
                        "soft",
                        offersBreak = false
                    )
                } else {
                    GuardDecision(
                        packageName,
                        GuardOutcome.BLOCK,
                        Copy.blockHeadline(context, packageName),
                        Copy.blockDetail(context, packageName),
                        "policy",
                        offersBreak = TakeABreak.canStart(context)
                    )
                }
            }
        }
    }

    /**
     * The Budgeted ladder: free daily minutes first, then the earned pool.
     *
     * A Budgeted app is one the person has said "this costs me something". So:
     *
     *  - If they gave it a daily minute limit, that limit is a free allowance
     *    and it comes first. Thirty minutes means thirty minutes, no questions.
     *  - Past that allowance - or immediately, for an app with no limit at all -
     *    the app opens only while there are minutes in the earned pool, and
     *    every second it is in front is charged against them by
     *    [AppBlockerService], in real time.
     *  - With an empty pool it is shut, and the screen says what would open it.
     *
     * Spendable minutes are deliberately *not* counted down here. This runs
     * several times a second and must not have side effects; the drain lives in
     * the enforcement loop, which is the only place that knows whether the app
     * is genuinely still in front.
     */
    private fun budgetedDecision(context: Context, packageName: String): GuardDecision {
        val limit = AppLimits.minuteLimit(context, packageName)
        val verdict = EarnPool.budgetVerdict(
            limit,
            AppLimits.usedMinutesToday(context, packageName),
            // spendableMs, not balanceMs: with Earn Mode off the pool is worth
            // nothing, and the drain loop would refuse to bill this app anyway.
            EarnPool.spendableMs(context)
        )
        return when (verdict) {
            EarnPool.BudgetVerdict.FREE_MINUTES -> allowed(packageName, "budgetFree")
            EarnPool.BudgetVerdict.POOL -> allowed(packageName, "budgetPool")
            EarnPool.BudgetVerdict.BLOCKED -> GuardDecision(
                packageName,
                GuardOutcome.BLOCK,
                Copy.budgetHeadline(context, packageName),
                Copy.budgetDetail(context, limit),
                "budget",
                offersBreak = TakeABreak.canStart(context),
                poolMinutes = 0
            )
        }
    }

    /** An ALLOW decision with no words, for callers outside [decide]'s lambda scope. */
    private fun allowed(packageName: String, source: String): GuardDecision =
        GuardDecision(packageName, GuardOutcome.ALLOW, "", "", source, false)

    /** Convenience for callers that only need a yes or no. */
    fun isAllowed(context: Context, packageName: String, className: String? = null): Boolean =
        decide(context, packageName, className).outcome == GuardOutcome.ALLOW

    // ── Matching ──────────────────────────────────────────────────

    private fun matchRule(context: Context, packageName: String, sessionActiveOverride: Boolean? = null): Rule? {
        val now = Calendar.getInstance()
        return RuleStore.all(context).firstOrNull { rule ->
            rule.enabled &&
                matchesTarget(context, rule, packageName) &&
                matchesCondition(context, rule, packageName, now, sessionActiveOverride)
        }
    }

    private fun matchesTarget(context: Context, rule: Rule, packageName: String): Boolean =
        when (rule.targetType) {
            RuleTargetType.ALL -> true
            RuleTargetType.APP -> rule.targetValue == packageName
            RuleTargetType.CATEGORY ->
                AppCatalog.categoryOf(context, packageName) == AppCategory.fromId(rule.targetValue)
        }

    private fun matchesCondition(
        context: Context,
        rule: Rule,
        packageName: String,
        now: Calendar,
        sessionActiveOverride: Boolean? = null
    ): Boolean = when (rule.conditionType) {
        RuleConditionType.ALWAYS -> true
        RuleConditionType.SESSION_ONLY -> sessionActiveOverride ?: SessionManager.isActive(context)
        RuleConditionType.TIME -> {
            val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            withinWindow(minutes, rule.conditionStart, rule.conditionEnd)
        }
        RuleConditionType.DAYS ->
            rule.conditionDays.isEmpty() || now.get(Calendar.DAY_OF_WEEK) in rule.conditionDays
        RuleConditionType.PLACE ->
            PlaceRules.activePlaces(context).any { it.id == rule.conditionValue }
        RuleConditionType.WIFI -> {
            val ssid = PlaceRules.currentWifiSsid(context)
            ssid != null && ssid.equals(rule.conditionValue, ignoreCase = true)
        }
        RuleConditionType.USAGE_OVER ->
            AppLimits.usedMinutesToday(context, packageName) >= rule.conditionNumber
        RuleConditionType.OPENS_OVER ->
            AppLimits.opensToday(context, packageName) >= rule.conditionNumber
    }

    private fun withinWindow(nowMinutes: Int, start: Int, end: Int): Boolean {
        if (start == end) return false
        return if (end > start) nowMinutes in start until end else nowMinutes >= start || nowMinutes < end
    }
}
