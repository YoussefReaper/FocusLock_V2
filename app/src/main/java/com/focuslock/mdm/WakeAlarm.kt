package com.focuslock.mdm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * The smart alarm. Each alarm has a priority that decides how hard it is to
 * stop: Must and Should check five minutes later that you are really up, and
 * if you are not, the second ring is much harder (a long phrase, three sums,
 * full volume, no snooze). Fajr can wake you by itself, at the Adhan.
 *
 * Sounds are never music: the phone's alarm tone, a file you choose, or one of
 * the Adhan voices, downloaded once when you pick it.
 */
object WakeAlarm {

    enum class Priority { MUST, SHOULD, GENTLE, NUDGE }

    data class Alarm(
        val id: String,
        val hour: Int,
        val minute: Int,
        /** Calendar.DAY_OF_WEEK values; empty means every day. */
        val days: Set<Int>,
        val priority: Priority,
        val sound: String,
        val label: String,
        val on: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("h", hour).put("m", minute)
            .put("d", JSONArray(days.toList())).put("p", priority.name).put("s", sound).put("l", label).put("on", on)

        companion object {
            fun from(o: JSONObject): Alarm = Alarm(
                o.getString("id"), o.getInt("h"), o.getInt("m"),
                o.optJSONArray("d")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                runCatching { Priority.valueOf(o.getString("p")) }.getOrDefault(Priority.SHOULD),
                o.optString("s", SOUND_TONE), o.optString("l"), o.optBoolean("on", true)
            )
        }
    }

    const val SOURCE = "alarm"
    const val SOUND_TONE = "tone"
    const val SOUND_SOFT = "soft"
    const val FAJR_ID = "fajr"

    /** The Adhan voices offered (aladhan.com's set; a3 is left out). */
    val ADHANS = listOf("a1", "a2", "a4", "a5", "a6", "a7", "a8", "a9", "a10")
    fun adhanUrl(id: String) = "https://cdn.aladhan.com/audio/adhans/$id.mp3"

    const val EXTRA_ID = "wake_alarm_id"
    const val EXTRA_SECOND = "wake_alarm_second"
    const val ACTION_CHECK = "com.focuslock.mdm.WAKE_CHECK"
    const val ACTION_AWAKE = "com.focuslock.mdm.WAKE_AWAKE"
    const val ACTION_PRAYER = "com.focuslock.mdm.WAKE_PRAYER"
    const val PRAYER_ID = "prayer"

    private const val K_ALARMS = "wake_alarms"
    private const val K_FAJR = "wake_fajr"
    private const val K_FAJR_SOUND = "wake_fajr_sound"
    private const val K_RINGING_UNTIL = "wake_ringing_until"
    private const val K_SNOOZED = "wake_snoozed_at"
    private const val RC_NEXT = 7100
    private const val RC_SECOND = 7101
    private const val RC_CHECK = 7102
    private const val RC_SNOOZE = 7103
    private const val RC_PRAYER = 7104
    private const val RC_PRAYER_RING = 7105
    private const val K_PRAYERS_ASLEEP = "wake_prayers_if_asleep"
    /** Asleep means the screen has been dark at least this long and no other device is in use. */
    const val ASLEEP_MIN = 30
    private const val NOTE_CHECK = 7110

    const val RECHECK_MIN = 5
    const val ANSWER_MIN = 2
    const val SNOOZE_MIN = 9

    // ── Store ─────────────────────────────────────────────────────

    fun all(c: Context): List<Alarm> = runCatching {
        val a = JSONArray(FocusStore.getString(c, K_ALARMS, "[]"))
        (0 until a.length()).map { Alarm.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(c: Context, list: List<Alarm>) {
        FocusStore.setString(c, K_ALARMS, JSONArray(list.map { it.toJson() }).toString())
        scheduleNext(c)
    }

    fun upsert(c: Context, a: Alarm) = save(c, all(c).filterNot { it.id == a.id } + a)
    fun remove(c: Context, id: String) = save(c, all(c).filterNot { it.id == id })

    fun wakesForFajr(c: Context): Boolean = FocusStore.getBool(c, K_FAJR, false)
    fun setWakesForFajr(c: Context, v: Boolean) { FocusStore.setBool(c, K_FAJR, v); scheduleNext(c) }
    fun fajrSound(c: Context): String = FocusStore.getString(c, K_FAJR_SOUND, "adhan:a1")
    fun setFajrSound(c: Context, s: String) = FocusStore.setString(c, K_FAJR_SOUND, s)

    fun find(c: Context, id: String?): Alarm? = when (id) {
        null -> null
        FAJR_ID -> Alarm(FAJR_ID, 0, 0, emptySet(), Priority.MUST, fajrSound(c), c.getString(R.string.salah_fajr))
        PRAYER_ID -> Alarm(PRAYER_ID, 0, 0, emptySet(), Priority.SHOULD, fajrSound(c), c.getString(R.string.alarm_prayer_label))
        else -> all(c).firstOrNull { it.id == id }
    }

    // ── When ──────────────────────────────────────────────────────

    /** Next time [a] rings after [nowMs]. Pure, for tests. */
    fun nextAt(a: Alarm, nowMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs; set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (cal.timeInMillis > nowMs && (a.days.isEmpty() || cal.get(Calendar.DAY_OF_WEEK) in a.days)) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** The next ring of any alarm (id, ms), Fajr included. */
    fun next(c: Context, nowMs: Long = System.currentTimeMillis()): Pair<String, Long>? {
        val list = all(c).filter { it.on }.map { it.id to nextAt(it, nowMs) }.toMutableList()
        if (wakesForFajr(c) && Salah.isOn(c)) {
            Salah.next(c, nowMs)?.takeIf { it.first == "Fajr" }?.let { list += FAJR_ID to it.second }
                ?: Salah.next(c, nowMs + 20 * 3_600_000L)?.takeIf { it.first == "Fajr" }?.let { list += FAJR_ID to it.second }
        }
        return list.minByOrNull { it.second }
    }

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun canSchedule(c: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    private fun ringIntent(c: Context, id: String, second: Boolean, rc: Int): PendingIntent =
        PendingIntent.getActivity(c, rc,
            Intent(c, AlarmActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_SECOND, second)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun setClock(c: Context, at: Long, op: PendingIntent) {
        if (!canSchedule(c)) return
        val show = PendingIntent.getActivity(c, 0, Intent(c, AlarmsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try { am(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op) } catch (_: SecurityException) { }
    }

    /** Puts the next ring on the system clock. Safe to call any time (boot, edits, after a ring). */
    fun scheduleNext(c: Context) {
        schedulePrayerCheck(c)
        val (id, at) = next(c) ?: run { am(c).cancel(ringIntent(c, "", false, RC_NEXT)); return }
        setClock(c, at, ringIntent(c, id, false, RC_NEXT))
    }

    // ── The other prayers: ring only if you're asleep ────────────

    fun wakesForPrayersIfAsleep(c: Context): Boolean = FocusStore.getBool(c, K_PRAYERS_ASLEEP, false)
    fun setWakesForPrayersIfAsleep(c: Context, v: Boolean) { FocusStore.setBool(c, K_PRAYERS_ASLEEP, v); scheduleNext(c) }

    private fun prayerCheckIntent(c: Context) = PendingIntent.getBroadcast(c, RC_PRAYER,
        Intent(c, WakeReceiver::class.java).setAction(ACTION_PRAYER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A quiet check at the next Dhuhr, Asr, Maghrib or Isha; it rings only if you're asleep then. */
    private fun schedulePrayerCheck(c: Context) {
        am(c).cancel(prayerCheckIntent(c))
        if (!wakesForPrayersIfAsleep(c) || !Salah.isOn(c) || !canSchedule(c)) return
        var t = System.currentTimeMillis()
        repeat(6) {
            val (name, at) = Salah.next(c, t) ?: return
            if (name != "Fajr") {
                try { am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, prayerCheckIntent(c)) } catch (_: SecurityException) { }
                return
            }
            t = at + 1000
        }
    }

    /** Blocking. Screen dark for [ASLEEP_MIN] and nothing else in use: ring as a Should alarm. */
    fun prayerCheck(c: Context) {
        try {
            if (isAsleep(c) && !awakeElsewhere(c)) setClock(c, System.currentTimeMillis() + 1000, ringIntent(c, PRAYER_ID, false, RC_PRAYER_RING))
        } finally { schedulePrayerCheck(c) }
    }

    private fun isAsleep(c: Context): Boolean {
        if (c.getSystemService(PowerManager::class.java).isInteractive) return false
        val now = System.currentTimeMillis()
        val usm = c.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return false
        val events = try { usm.queryEvents(now - ASLEEP_MIN * 60_000L, now) } catch (_: Exception) { return false }
        val e = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // SCREEN_INTERACTIVE (15), or any app coming to the front, means someone was using it.
            if (e.eventType == 15 || e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) return false
        }
        return true
    }

    fun snooze(c: Context, id: String) {
        FocusStore.setLong(c, K_SNOOZED, System.currentTimeMillis())
        setClock(c, System.currentTimeMillis() + SNOOZE_MIN * 60_000L, ringIntent(c, id, false, RC_SNOOZE))
    }

    // ── Ringing ───────────────────────────────────────────────────

    fun isRinging(c: Context): Boolean = System.currentTimeMillis() < FocusStore.getLong(c, K_RINGING_UNTIL, 0L)

    fun startRinging(c: Context) = FocusStore.setLong(c, K_RINGING_UNTIL, System.currentTimeMillis() + 15 * 60_000L)

    /** Called when the ring is solved. Must and Should come back in five minutes to check. */
    fun stopped(c: Context, alarm: Alarm?, second: Boolean) {
        FocusStore.setLong(c, K_RINGING_UNTIL, 0L)
        if (alarm != null && !second && (alarm.priority == Priority.MUST || alarm.priority == Priority.SHOULD)) {
            val op = PendingIntent.getBroadcast(c, RC_CHECK,
                Intent(c, WakeReceiver::class.java).setAction(ACTION_CHECK).putExtra(EXTRA_ID, alarm.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            setClock(c, System.currentTimeMillis() + RECHECK_MIN * 60_000L, op)
        }
        scheduleNext(c)
    }

    /** Five minutes later: awake if the phone is in use; otherwise ask, and ring again hard if nobody answers. */
    fun check(c: Context, id: String) {
        val pm = c.getSystemService(PowerManager::class.java)
        val km = c.getSystemService(android.app.KeyguardManager::class.java)
        if (pm.isInteractive && !km.isKeyguardLocked) return
        if (awakeElsewhere(c)) return
        setClock(c, System.currentTimeMillis() + ANSWER_MIN * 60_000L, ringIntent(c, id, true, RC_SECOND))
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("wake_check") == null) {
            nm.createNotificationChannel(NotificationChannel("wake_check", c.getString(R.string.alarm_check_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val awake = PendingIntent.getBroadcast(c, RC_CHECK + 1,
            Intent(c, WakeReceiver::class.java).setAction(ACTION_AWAKE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE_CHECK, NotificationCompat.Builder(c, "wake_check")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(c.getString(R.string.alarm_check_title))
            .setContentText(c.getString(R.string.alarm_check_body, ANSWER_MIN))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(awake)
            .addAction(0, c.getString(R.string.alarm_im_up), awake)
            .setAutoCancel(true).build())
    }

    /** Blocking. True when the laptop (or another device) was in use in the last five minutes. */
    fun awakeElsewhere(c: Context): Boolean {
        val token = Account.sessionToken(c) ?: return false
        val r = Account.call("GET", "/presence?except=phone", null, token, readTimeoutMs = 6000)
        return r.ok && r.json.optBoolean("awake", false)
    }

    fun confirmAwake(c: Context) {
        am(c).cancel(ringIntent(c, "", true, RC_SECOND))
        c.getSystemService(NotificationManager::class.java).cancel(NOTE_CHECK)
    }

    // ── Wake tasks ────────────────────────────────────────────────

    private val WORDS = listOf(
        "river", "lantern", "quiet", "morning", "window", "pine", "stone", "harbor", "candle", "meadow",
        "copper", "orbit", "valley", "silver", "thunder", "garden", "anchor", "feather", "compass", "ember"
    )

    fun phrase(words: Int, seed: Long = System.nanoTime()): String {
        val r = java.util.Random(seed)
        return (1..words).joinToString(" ") { WORDS[r.nextInt(WORDS.size)] }
    }

    /** How hard the ring is to stop: (sums, phrase words, snooze allowed). */
    fun task(p: Priority, second: Boolean): Triple<Int, Int, Boolean> = when {
        second -> Triple(3, 12, false)
        p == Priority.MUST -> Triple(3, 6, false)
        p == Priority.SHOULD -> Triple(2, 0, false)
        p == Priority.GENTLE -> Triple(0, 0, true)
        else -> Triple(0, 0, true)
    }

    // ── Adhan files ───────────────────────────────────────────────

    fun adhanFile(c: Context, id: String) = File(File(c.filesDir, "adhan"), "$id.mp3")

    /** Background thread only. */
    fun downloadAdhan(c: Context, id: String): Boolean {
        val out = adhanFile(c, id)
        if (out.exists() && out.length() > 10_000) return true
        out.parentFile?.mkdirs()
        return runCatching {
            val conn = URL(adhanUrl(id)).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 20_000
            val tmp = File(out.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            tmp.renameTo(out)
        }.getOrDefault(false)
    }
}

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WakeAlarm.ACTION_CHECK -> {
                val id = intent.getStringExtra(WakeAlarm.EXTRA_ID) ?: return
                val pending = goAsync()
                Thread { try { WakeAlarm.check(context.applicationContext, id) } finally { pending.finish() } }.start()
            }
            WakeAlarm.ACTION_AWAKE -> WakeAlarm.confirmAwake(context)
            WakeAlarm.ACTION_PRAYER -> {
                val pending = goAsync()
                Thread { try { WakeAlarm.prayerCheck(context.applicationContext) } finally { pending.finish() } }.start()
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> WakeAlarm.scheduleNext(context)
        }
    }
}
