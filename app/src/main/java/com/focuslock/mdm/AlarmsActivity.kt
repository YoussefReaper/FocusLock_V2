package com.focuslock.mdm

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import java.util.Calendar
import java.util.UUID

/** Alarms: how hard each one is to stop, what it sounds like, and waking for Fajr. */
class AlarmsActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_sleep
    override fun screenTitle(): String = getString(R.string.alarm_title)
    override fun screenSubtitle(): String = getString(R.string.alarm_subtitle)

    private var pickingFor: String? = null

    override fun buildContent(column: LinearLayout) {
        if (!WakeAlarm.canSchedule(this) && Build.VERSION.SDK_INT >= 31) {
            column.addView(card { card ->
                card.addView(FocusUi.body(this, tokens, getString(R.string.alarm_need_exact)))
                card.addView(FocusUi.spacer(this, 10))
                card.addView(FocusUi.primaryButton(this, tokens, getString(R.string.alarm_allow_exact)) {
                    runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
                })
            })
        }

        column.addView(sectionLabel(getString(R.string.alarm_section_fajr)))
        column.addView(card { card ->
            card.addView(FocusUi.toggleRow(this, tokens, getString(R.string.alarm_fajr_title),
                if (Salah.isOn(this)) getString(R.string.alarm_fajr_subtitle) else getString(R.string.alarm_fajr_needs_salah),
                WakeAlarm.wakesForFajr(this), enabled = Salah.isOn(this)) { v -> WakeAlarm.setWakesForFajr(this, v); refresh() })
            card.addView(FocusUi.spacer(this, 6))
            card.addView(FocusUi.toggleRow(this, tokens, getString(R.string.alarm_prayers_title),
                getString(R.string.alarm_prayers_subtitle, WakeAlarm.ASLEEP_MIN),
                WakeAlarm.wakesForPrayersIfAsleep(this), enabled = Salah.isOn(this)) { v -> WakeAlarm.setWakesForPrayersIfAsleep(this, v); refresh() })
            if (WakeAlarm.wakesForFajr(this) || WakeAlarm.wakesForPrayersIfAsleep(this)) {
                card.addView(FocusUi.spacer(this, 8))
                card.addView(soundPicker(WakeAlarm.fajrSound(this)) { WakeAlarm.setFajrSound(this, it); refresh() })
            }
        })

        column.addView(sectionLabel(getString(R.string.alarm_section_list)))
        WakeAlarm.all(this).sortedBy { it.hour * 60 + it.minute }.forEach { column.addView(alarmCard(it)) }
        column.addView(FocusUi.spacer(this, 6))
        column.addView(FocusUi.primaryButton(this, tokens, getString(R.string.alarm_add)) { pickTime(null) })
    }

    private fun alarmCard(a: WakeAlarm.Alarm): View = card { card ->
        card.addView(FocusUi.toggleRow(this, tokens, TimeText.ofDay(this, a.hour, a.minute),
            priorityLine(a.priority), a.on) { v -> WakeAlarm.upsert(this, a.copy(on = v)); refresh() })
        card.addView(FocusUi.spacer(this, 8))
        val ps = WakeAlarm.Priority.values().toList()
        card.addView(FocusUi.horizontalScroll(this, FocusUi.chipStrip(this, tokens, ps.map { priorityName(it) }, ps.indexOf(a.priority)) { i ->
            WakeAlarm.upsert(this, a.copy(priority = ps[i])); refresh()
        }))
        card.addView(FocusUi.spacer(this, 8))
        val days = listOf(Calendar.SATURDAY, Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val names = resources.getStringArray(R.array.alarm_day_letters)
        val dayRow = FocusUi.row(this)
        days.forEachIndexed { i, d ->
            dayRow.addView(FocusUi.chip(this, tokens, names[i], a.days.isEmpty() || d in a.days) {
                val now = if (a.days.isEmpty()) days.toSet() else a.days
                val next = if (d in now) now - d else now + d
                WakeAlarm.upsert(this, a.copy(days = if (next.size == 7) emptySet() else next)); refresh()
            })
        }
        card.addView(FocusUi.horizontalScroll(this, dayRow))
        card.addView(FocusUi.spacer(this, 8))
        card.addView(soundPicker(a.sound) { WakeAlarm.upsert(this, a.copy(sound = it)); refresh() })
        card.addView(FocusUi.spacer(this, 8))
        val actions = FocusUi.row(this)
        actions.addView(FocusUi.smallButton(this, tokens, getString(R.string.alarm_change_time)) { pickTime(a) })
        actions.addView(FocusUi.smallButton(this, tokens, getString(R.string.common_remove)) { WakeAlarm.remove(this, a.id); refresh() })
        card.addView(actions)
    }

    private fun soundPicker(current: String, set: (String) -> Unit): View {
        val box = FocusUi.column(this)
        val kinds = listOf(WakeAlarm.SOUND_TONE, WakeAlarm.SOUND_SOFT, "adhan", "custom")
        val labels = listOf(R.string.alarm_sound_tone, R.string.alarm_sound_soft, R.string.alarm_sound_adhan, R.string.alarm_sound_file).map { getString(it) }
        val idx = when {
            current.startsWith("adhan:") -> 2
            current.startsWith("custom:") -> 3
            else -> kinds.indexOf(current).coerceAtLeast(0)
        }
        box.addView(FocusUi.horizontalScroll(this, FocusUi.chipStrip(this, tokens, labels, idx) { i ->
            when (i) {
                2 -> chooseAdhan(WakeAlarm.ADHANS.first(), set)
                3 -> pickFile(set)
                else -> set(kinds[i])
            }
        }))
        if (current.startsWith("adhan:")) {
            val id = current.removePrefix("adhan:")
            box.addView(FocusUi.spacer(this, 6))
            box.addView(FocusUi.horizontalScroll(this, FocusUi.chipStrip(this, tokens,
                WakeAlarm.ADHANS.mapIndexed { n, _ -> getString(R.string.alarm_voice, n + 1) }, WakeAlarm.ADHANS.indexOf(id)) { i ->
                chooseAdhan(WakeAlarm.ADHANS[i], set)
            }))
            if (!WakeAlarm.adhanFile(this, id).exists()) box.addView(FocusUi.caption(this, tokens, getString(R.string.alarm_adhan_missing)))
        }
        return box
    }

    private fun chooseAdhan(id: String, set: (String) -> Unit) {
        set("adhan:$id")
        Thread {
            val ok = WakeAlarm.downloadAdhan(applicationContext, id)
            runOnUiThread {
                FocusDialog.toast(this, getString(if (ok) R.string.alarm_adhan_ready else R.string.alarm_adhan_failed))
                refresh()
            }
        }.start()
    }

    private var onFile: ((String) -> Unit)? = null

    private fun pickFile(set: (String) -> Unit) {
        onFile = set
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQUEST_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onFile?.invoke("custom:$uri")
    }

    private fun pickTime(a: WakeAlarm.Alarm?) {
        val h = a?.hour ?: 6; val m = a?.minute ?: 30
        TimePickerDialog(this, { _, hour, minute ->
            WakeAlarm.upsert(this, a?.copy(hour = hour, minute = minute)
                ?: WakeAlarm.Alarm(UUID.randomUUID().toString(), hour, minute, emptySet(), WakeAlarm.Priority.SHOULD, WakeAlarm.SOUND_TONE, ""))
            refresh()
        }, h, m, TimeText.uses24Hour(this)).show()
    }

    private fun priorityName(p: WakeAlarm.Priority) = getString(when (p) {
        WakeAlarm.Priority.MUST -> R.string.alarm_p_must
        WakeAlarm.Priority.SHOULD -> R.string.alarm_p_should
        WakeAlarm.Priority.GENTLE -> R.string.alarm_p_gentle
        WakeAlarm.Priority.NUDGE -> R.string.alarm_p_nudge
    })

    private fun priorityLine(p: WakeAlarm.Priority) = getString(when (p) {
        WakeAlarm.Priority.MUST -> R.string.alarm_p_must_line
        WakeAlarm.Priority.SHOULD -> R.string.alarm_p_should_line
        WakeAlarm.Priority.GENTLE -> R.string.alarm_p_gentle_line
        WakeAlarm.Priority.NUDGE -> R.string.alarm_p_nudge_line
    })

    private companion object { const val REQUEST_FILE = 52 }
}
