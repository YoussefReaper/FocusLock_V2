package com.focuslock.mdm

import androidx.annotation.StringRes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * How a task proves it was actually done.
 *
 * Ticking a box is the weakness of every quest-style task app: Habitica's whole
 * economy runs on an honour system, so the reward means nothing the first time
 * you lie to yourself. Habit Doom's answer is an on-device photo checked by an
 * on-device model, which is the approach worth copying — with the important
 * caveat that a photo check is only as good as what it can actually verify (see
 * [PhotoProof]).
 */
enum class Verification(val id: String, @StringRes val labelRes: Int, @StringRes val blurbRes: Int) {
    MANUAL("manual", R.string.verify_manual_label, R.string.verify_manual_blurb),
    TIMER("timer", R.string.verify_timer_label, R.string.verify_timer_blurb),
    PHOTO("photo", R.string.verify_photo_label, R.string.verify_photo_blurb),
    SUBTASKS_ALL("subtasks", R.string.verify_subtasks_label, R.string.verify_subtasks_blurb),

    /**
     * Never started, never completed - it just runs.
     *
     * The task names the apps it counts, time in those apps accrues in the
     * background, and the person claims the balance when they want it. There is
     * no session to begin and nothing to "finish", because the work is the
     * studying and the app should not be asking to be told about it.
     */
    AMBIENT("ambient", R.string.verify_ambient_label, R.string.verify_ambient_blurb);

    fun label(context: Context): String = context.getString(labelRes)
    fun blurb(context: Context): String = context.getString(blurbRes)

    companion object {
        fun fromId(id: String?): Verification = values().firstOrNull { it.id == id } ?: MANUAL
    }
}

/**
 * Whether this task counts time spent in the apps it names.
 *
 * True for both the kinds that do it, which is why it is one property rather
 * than two checks scattered around: TIMER is satisfied by accrued time, AMBIENT
 * is paid by it.
 */
val FocusTask.tracksApps: Boolean
    get() = verification == Verification.TIMER || verification == Verification.AMBIENT

enum class Priority(val id: String, @StringRes val labelRes: Int) {
    NONE("none", R.string.prio_none_label),
    LOW("low", R.string.prio_low_label),
    MED("med", R.string.prio_med_label),
    HIGH("high", R.string.prio_high_label);

    fun label(context: Context): String = context.getString(labelRes)

    companion object {
        fun fromId(id: String?): Priority = values().firstOrNull { it.id == id } ?: NONE
    }
}

enum class Recurrence(val id: String, @StringRes val labelRes: Int) {
    NONE("none", R.string.recur_none_label),
    DAILY("daily", R.string.recur_daily_label),
    WEEKLY("weekly", R.string.recur_weekly_label),
    CUSTOM("custom", R.string.recur_custom_label);

    fun label(context: Context): String = context.getString(labelRes)

    companion object {
        fun fromId(id: String?): Recurrence = values().firstOrNull { it.id == id } ?: NONE
    }
}

enum class AttachmentKind(val id: String) {
    PHOTO("photo"), FILE("file"), LINK("link");

    companion object {
        fun fromId(id: String?): AttachmentKind = values().firstOrNull { it.id == id } ?: LINK
    }
}

data class Attachment(val kind: AttachmentKind, val value: String, val label: String = "")

data class Subtask(val id: String, val title: String, val done: Boolean = false)

/**
 * One task.
 *
 * The field set is the baseline people expect from a real task app (Todoist,
 * TickTick, SingularityApp): title, notes, one level of subtasks, priority, a
 * due date *and* a hard deadline, tags, recurrence, a time estimate, local
 * attachments and a reminder. Anything thinner and the panel becomes a second,
 * worse place to keep todos, which nobody uses.
 *
 * [enjoyable] is the one field none of those apps have, and it exists because
 * of the research this feature is built on: Deci, Koestner and Ryan's 1999
 * meta-analysis of 128 experiments found tangible rewards undermine intrinsic
 * motivation most for tasks people already find interesting. So a task the user
 * marks as genuinely enjoyable pays no reward at all, and says why.
 */
data class FocusTask(
    val id: String,
    val title: String,
    val notes: String = "",
    val subtasks: List<Subtask> = emptyList(),
    val priority: Priority = Priority.NONE,
    val dueDate: Long? = null,
    val deadline: Long? = null,
    val tags: Set<String> = emptySet(),
    val recurrence: Recurrence = Recurrence.NONE,
    val recurrenceEveryDays: Int = 3,
    val timeEstimateMin: Int? = null,
    val attachments: List<Attachment> = emptyList(),
    val reminderAt: Long? = null,
    val verification: Verification = Verification.MANUAL,
    val allowedApps: Set<String> = emptySet(),
    val rewardMin: Int? = null,
    val enjoyable: Boolean = false,
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val missedCount: Int = 0,
    val credibility: Float = 1.0f
) {
    val progressPercent: Int
        get() {
            if (subtasks.isEmpty()) return if (completed) 100 else 0
            return (subtasks.count { it.done } * 100) / subtasks.size
        }

    val isOverdue: Boolean
        get() {
            if (completed) return false
            val cutoff = deadline ?: dueDate ?: return false
            return System.currentTimeMillis() > cutoff
        }

    val isDueToday: Boolean
        get() {
            if (completed) return false
            val due = dueDate ?: deadline ?: return false
            return FocusTaskStore.isSameDay(due, System.currentTimeMillis())
        }

    /** A task that pays nothing, either because it earns nothing or by choice. */
    val paysReward: Boolean get() = !enjoyable && (rewardMin ?: 0) > 0
}

/**
 * Where tasks live.
 *
 * Same storage as every other FocusLock store: JSON in [FocusStore], on this
 * phone, never uploaded. Attachments are stored as local URIs; the bytes stay
 * wherever the user's own file picker put them.
 */
object FocusTaskStore {

    private const val KEY_TASKS = "focus_tasks_json"

    fun all(context: Context): List<FocusTask> = PolicyCache.get("focusTasks") {
        val array = FocusStore.getJsonArray(context, KEY_TASKS)
        val out = ArrayList<FocusTask>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val title = obj.optString("title", "").trim()
            if (title.isEmpty()) continue
            out.add(parse(obj, title))
        }
        out
    }

    private fun parse(obj: JSONObject, title: String): FocusTask {
        val subtasks = ArrayList<Subtask>()
        obj.optJSONArray("subtasks")?.let { array ->
            for (i in 0 until array.length()) {
                val sub = array.optJSONObject(i) ?: continue
                val subTitle = sub.optString("title", "").trim()
                if (subTitle.isEmpty()) continue
                subtasks.add(
                    Subtask(
                        id = sub.optString("id", UUID.randomUUID().toString()),
                        title = subTitle,
                        done = sub.optBoolean("done", false)
                    )
                )
            }
        }

        val attachments = ArrayList<Attachment>()
        obj.optJSONArray("attachments")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val value = item.optString("value", "").trim()
                if (value.isEmpty()) continue
                attachments.add(
                    Attachment(
                        kind = AttachmentKind.fromId(item.optString("kind", "")),
                        value = value,
                        label = item.optString("label", "")
                    )
                )
            }
        }

        return FocusTask(
            id = obj.optString("id", UUID.randomUUID().toString()),
            title = title,
            notes = obj.optString("notes", ""),
            subtasks = subtasks,
            priority = Priority.fromId(obj.optString("priority", "")),
            dueDate = obj.optLong("dueDate", 0L).takeIf { it > 0L },
            deadline = obj.optLong("deadline", 0L).takeIf { it > 0L },
            tags = FocusStore.jsonArrayToStringList(obj.optJSONArray("tags") ?: JSONArray()).toSet(),
            recurrence = Recurrence.fromId(obj.optString("recurrence", "")),
            recurrenceEveryDays = obj.optInt("recurrenceEveryDays", 3),
            timeEstimateMin = obj.optInt("timeEstimateMin", 0).takeIf { it > 0 },
            attachments = attachments,
            reminderAt = obj.optLong("reminderAt", 0L).takeIf { it > 0L },
            verification = Verification.fromId(obj.optString("verification", "")),
            allowedApps = FocusStore.jsonArrayToStringList(
                obj.optJSONArray("allowedApps") ?: JSONArray()
            ).toSet(),
            rewardMin = obj.optInt("rewardMin", 0).takeIf { it > 0 },
            enjoyable = obj.optBoolean("enjoyable", false),
            completed = obj.optBoolean("completed", false),
            completedAt = obj.optLong("completedAt", 0L).takeIf { it > 0L },
            missedCount = obj.optInt("missedCount", 0),
            credibility = obj.optDouble("credibility", 1.0).toFloat()
        )
    }

    fun save(context: Context, tasks: List<FocusTask>) {
        val array = JSONArray()
        tasks.forEach { task -> array.put(serialize(task)) }
        FocusStore.setJsonArray(context, KEY_TASKS, array)
        PolicySync.request(context, "tasks")
    }

    private fun serialize(task: FocusTask): JSONObject {
        val obj = JSONObject()
        obj.put("id", task.id)
        obj.put("title", task.title)
        obj.put("notes", task.notes)

        val subtasks = JSONArray()
        task.subtasks.forEach { sub ->
            val item = JSONObject()
            item.put("id", sub.id)
            item.put("title", sub.title)
            item.put("done", sub.done)
            subtasks.put(item)
        }
        obj.put("subtasks", subtasks)

        val attachments = JSONArray()
        task.attachments.forEach { attachment ->
            val item = JSONObject()
            item.put("kind", attachment.kind.id)
            item.put("value", attachment.value)
            item.put("label", attachment.label)
            attachments.put(item)
        }
        obj.put("attachments", attachments)

        obj.put("priority", task.priority.id)
        task.dueDate?.let { obj.put("dueDate", it) }
        task.deadline?.let { obj.put("deadline", it) }
        obj.put("tags", FocusStore.stringListToJsonArray(task.tags))
        obj.put("recurrence", task.recurrence.id)
        obj.put("recurrenceEveryDays", task.recurrenceEveryDays)
        task.timeEstimateMin?.let { obj.put("timeEstimateMin", it) }
        task.reminderAt?.let { obj.put("reminderAt", it) }
        obj.put("verification", task.verification.id)
        obj.put("allowedApps", FocusStore.stringListToJsonArray(task.allowedApps))
        task.rewardMin?.let { obj.put("rewardMin", it) }
        obj.put("enjoyable", task.enjoyable)
        obj.put("completed", task.completed)
        task.completedAt?.let { obj.put("completedAt", it) }
        obj.put("missedCount", task.missedCount)
        obj.put("credibility", task.credibility.toDouble())
        return obj
    }

    // ── CRUD ──────────────────────────────────────────────────────

    fun find(context: Context, id: String): FocusTask? = all(context).firstOrNull { it.id == id }

    /**
     * How much proof a verification method demands.
     *
     * Ordered by how hard it is to satisfy without doing the work, which is the
     * only axis that matters for the freeze: dropping to a weaker method is a
     * loosening, and moving to a stronger one is a tightening.
     */
    private fun proofStrength(v: Verification): Int = when (v) {
        Verification.MANUAL -> 0
        // AMBIENT and TIMER both require real time in the task's apps, which is
        // more than a tap. AMBIENT sits below TIMER only because it has no
        // session holding anyone to anything.
        Verification.AMBIENT -> 1
        Verification.TIMER -> 2
        Verification.SUBTASKS_ALL -> 3
        Verification.PHOTO -> 4
    }

    /**
     * Whether this edit makes the task easier to cash in.
     *
     * Task editing is not frozen wholesale - renaming a task, writing notes,
     * adding a subtask and ticking one off are all normal things to do whenever
     * you like. What waits is only the three fields that decide what the task
     * *pays*: how it proves itself, its explicit reward, and whether it is
     * marked enjoyable (which pays nothing).
     *
     * Public so it can be checked without a device, like the other rules that
     * matter most - see TaskPayoutFreezeTest.
     */
    fun loosensPayout(before: FocusTask?, after: FocusTask): Boolean {
        if (before == null) return false
        if (before.enjoyable && !after.enjoyable) return true
        if (proofStrength(after.verification) < proofStrength(before.verification)) return true
        val b = before.rewardMin
        val a = after.rewardMin
        return when {
            a == null -> false            // falling back to the rate, not a raise
            b == null -> true             // pinning an explicit reward can only add
            a > b -> true
            else -> false
        }
    }

    fun add(context: Context, task: FocusTask): Boolean {
        // A brand-new task has no payout to loosen, but starting to earn from a
        // task invented mid-session is the same loophole by another door, so it
        // waits for the session to end like every other loosening.
        if (!SessionLock.allows(context, EditDirection.LOOSEN)) return false
        save(context, all(context) + task)
        return true
    }

    fun update(context: Context, task: FocusTask): Boolean {
        val before = find(context, task.id)
        if (loosensPayout(before, task) && !SessionLock.allows(context, EditDirection.LOOSEN)) {
            return false
        }
        save(context, all(context).map { if (it.id == task.id) task else it })
        return true
    }

    fun remove(context: Context, id: String): Boolean {
        // Deleting a task cannot make anything pay more, but it can erase a
        // task you were part-way through to dodge having to finish it. Removal
        // waits only while a session is actually holding the rules still.
        if (!SessionLock.allows(context, EditDirection.LOOSEN)) return false
        save(context, all(context).filterNot { it.id == id })
        return true
    }

    fun newTask(title: String): FocusTask = FocusTask(
        id = UUID.randomUUID().toString(),
        title = title.trim().ifBlank { "Untitled task" }
    )

    fun newSubtask(title: String): Subtask =
        Subtask(id = UUID.randomUUID().toString(), title = title.trim())

    // ── Queries ───────────────────────────────────────────────────

    fun open(context: Context): List<FocusTask> = all(context).filterNot { it.completed }

    fun dueToday(context: Context): List<FocusTask> =
        open(context).filter { it.isDueToday || (it.dueDate == null && it.deadline == null) }
            .sortedWith(compareByDescending<FocusTask> { it.priority.ordinal }.thenBy { it.title })

    fun overdue(context: Context): List<FocusTask> =
        open(context).filter { it.isOverdue }.sortedBy { it.deadline ?: it.dueDate ?: 0L }

    fun completedToday(context: Context): List<FocusTask> =
        all(context).filter { task ->
            task.completed && task.completedAt != null &&
                isSameDay(task.completedAt, System.currentTimeMillis())
        }

    /** The average trust score across tasks that have ever been photo-checked. */
    fun credibility(context: Context): Float {
        val scored = all(context).filter { it.verification == Verification.PHOTO }
        if (scored.isEmpty()) return 1f
        return scored.map { it.credibility }.average().toFloat()
    }

    // ── Completion ────────────────────────────────────────────────

    /**
     * Marks a task done and, if it recurs, rolls it forward instead of leaving a
     * completed husk in the list.
     */
    fun complete(context: Context, task: FocusTask): FocusTask {
        val now = System.currentTimeMillis()
        val finished = task.copy(completed = true, completedAt = now)

        if (task.recurrence == Recurrence.NONE) {
            update(context, finished)
            return finished
        }

        val next = task.copy(
            id = UUID.randomUUID().toString(),
            completed = false,
            completedAt = null,
            subtasks = task.subtasks.map { it.copy(done = false) },
            dueDate = task.dueDate?.let { nextOccurrence(it, task) },
            deadline = task.deadline?.let { nextOccurrence(it, task) }
        )
        save(context, all(context).map { if (it.id == task.id) finished else it } + next)
        return finished
    }

    private fun nextOccurrence(from: Long, task: FocusTask): Long {
        val days = when (task.recurrence) {
            Recurrence.DAILY -> 1
            Recurrence.WEEKLY -> 7
            Recurrence.CUSTOM -> task.recurrenceEveryDays.coerceAtLeast(1)
            Recurrence.NONE -> return from
        }
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = from
        // Roll forward past today, so a task missed for a week does not reappear
        // six times over.
        val now = System.currentTimeMillis()
        while (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, days)
        }
        return calendar.timeInMillis
    }

    /**
     * A deadline came and went. Recorded, never punished: the count exists so
     * the user can notice a pattern, and it never touches the streak.
     */
    fun markMissed(context: Context, task: FocusTask) {
        update(context, task.copy(missedCount = task.missedCount + 1))
    }

    fun isSameDay(a: Long?, b: Long): Boolean {
        if (a == null) return false
        val first = Calendar.getInstance().apply { timeInMillis = a }
        val second = Calendar.getInstance().apply { timeInMillis = b }
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    // ── Export ────────────────────────────────────────────────────

    fun exportJson(context: Context): JSONArray = FocusStore.getJsonArray(context, KEY_TASKS)

    fun importJson(context: Context, array: JSONArray) {
        FocusStore.setJsonArray(context, KEY_TASKS, array)
        PolicySync.request(context, "tasks:import")
    }
}
