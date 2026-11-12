package com.focuslock.mdm

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import kotlin.random.Random

/**
 * The ringing screen. It owns the phone until its task is done: nothing else
 * opens while an alarm rings (see [Layers]). The task gets harder with the
 * alarm's priority, and much harder on the second ring.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var tokens: UiPrefs.Tokens
    private var player: MediaPlayer? = null
    private var alarm: WakeAlarm.Alarm? = null
    private var second = false
    private var sumsLeft = 0
    private var phrase = ""
    private var snooze = false
    private var savedVolume = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tokens = UiPrefs.resolve(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        start()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (!intent.hasExtra(WakeAlarm.EXTRA_ID)) return // "back to the alarm": keep ringing as it was
        setIntent(intent)
        stopSound()
        start()
    }

    private fun start() {
        alarm = WakeAlarm.find(this, intent.getStringExtra(WakeAlarm.EXTRA_ID))
        second = intent.getBooleanExtra(WakeAlarm.EXTRA_SECOND, false)
        if (second) WakeAlarm.confirmAwake(this)
        val (sums, words, canSnooze) = WakeAlarm.task(alarm?.priority ?: WakeAlarm.Priority.SHOULD, second)
        sumsLeft = sums
        phrase = if (words > 0) WakeAlarm.phrase(words) else ""
        snooze = canSnooze
        WakeAlarm.startRinging(this)
        playSound()
        render()
    }

    private fun render() {
        val root = FocusUi.screenRoot(this, tokens)
        val col = FocusUi.column(this, 24)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(FocusUi.spacer(this, 24))
        col.addView(MoonArt.painting(this, if (alarm?.id == WakeAlarm.FAJR_ID) R.drawable.art_salah else R.drawable.art_sleep, 220))
        val now = Calendar.getInstance()
        col.addView(FocusUi.display(this, tokens, TimeText.ofDay(this, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))).also { it.gravity = Gravity.CENTER })
        val label = alarm?.label?.takeIf { it.isNotBlank() } ?: getString(R.string.alarm_title)
        col.addView(FocusUi.secondary(this, tokens, if (second) getString(R.string.alarm_second_line) else label).also { it.gravity = Gravity.CENTER })
        col.addView(FocusUi.spacer(this, 20))

        when {
            sumsLeft > 0 -> {
                val a = Random.nextInt(12, 99); val b = Random.nextInt(12, 99)
                col.addView(FocusUi.title(this, tokens, "$a + $b").also { it.gravity = Gravity.CENTER })
                col.addView(FocusUi.caption(this, tokens, getString(R.string.alarm_sums_left, sumsLeft)).also { it.gravity = Gravity.CENTER })
                val field = FocusUi.input(this, tokens, getString(R.string.alarm_answer), numeric = true)
                col.addView(field)
                col.addView(FocusUi.spacer(this, 10))
                col.addView(FocusUi.primaryButton(this, tokens, getString(R.string.alarm_check_answer)) {
                    if (field.text.toString().trim().toIntOrNull() == a + b) { sumsLeft--; render() }
                    else FocusDialog.toast(this, getString(R.string.alarm_wrong))
                })
            }
            phrase.isNotEmpty() -> {
                col.addView(FocusUi.body(this, tokens, getString(R.string.alarm_type_phrase)).also { it.gravity = Gravity.CENTER })
                col.addView(FocusUi.title(this, tokens, phrase).also { it.gravity = Gravity.CENTER })
                val field = FocusUi.input(this, tokens, getString(R.string.alarm_phrase_hint), multiline = true)
                col.addView(field)
                col.addView(FocusUi.spacer(this, 10))
                col.addView(FocusUi.primaryButton(this, tokens, getString(R.string.alarm_check_answer)) {
                    val typed = field.text.toString().trim().lowercase().split(Regex("\\s+")).joinToString(" ")
                    if (typed == phrase) { phrase = ""; render() } else FocusDialog.toast(this, getString(R.string.alarm_wrong))
                })
            }
            else -> {
                col.addView(FocusUi.primaryButton(this, tokens, getString(R.string.alarm_stop)) { finishRing() })
                if (snooze) {
                    col.addView(FocusUi.spacer(this, 10))
                    col.addView(FocusUi.ghostButton(this, tokens, getString(R.string.alarm_snooze, WakeAlarm.SNOOZE_MIN)) {
                        alarm?.let { WakeAlarm.snooze(this, it.id) }
                        stopSound(); WakeAlarm.stopped(this, null, true); finish()
                    })
                }
            }
        }
        root.addView(FocusUi.scroll(this, col))
        setContentView(root)
    }

    private fun finishRing() {
        stopSound()
        WakeAlarm.stopped(this, alarm, second)
        if (!second && (alarm?.priority == WakeAlarm.Priority.MUST || alarm?.priority == WakeAlarm.Priority.SHOULD)) {
            FocusDialog.toast(this, getString(R.string.alarm_will_check, WakeAlarm.RECHECK_MIN))
        }
        finish()
    }

    private fun soundUri(): Uri? {
        val s = alarm?.sound ?: WakeAlarm.SOUND_TONE
        return when {
            s.startsWith("custom:") -> Uri.parse(s.removePrefix("custom:"))
            s.startsWith("adhan:") -> WakeAlarm.adhanFile(this, s.removePrefix("adhan:")).takeIf { it.exists() }?.let { Uri.fromFile(it) }
            s == WakeAlarm.SOUND_SOFT -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> null
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    private fun playSound() {
        val audio = getSystemService(AudioManager::class.java)
        if (second) {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AlarmActivity, soundUri()!!)
                isLooping = true
                prepare(); start()
            }
        }.getOrNull()
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
    }

    private fun stopSound() {
        runCatching { player?.stop(); player?.release() }
        player = null
        getSystemService(Vibrator::class.java)?.cancel()
        if (savedVolume >= 0) {
            getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }
}
