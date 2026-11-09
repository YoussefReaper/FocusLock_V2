package com.focuslock.mdm

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/**
 * The owner's own rules. Only in the personal build (flavor "personal"); the store build never
 * runs any of this.
 *  - AI chat apps and sites are blocked on the phone at all times.
 *  - Around every prayer (Cairo, Egyptian General Authority) only calls and the Quran open,
 *    from 5 minutes before the Adhan to 35 minutes after.
 *  - Videos: the first 14 days only his science-track curriculum; after that, also one
 *    non-curriculum video a day.
 */
object Personal {
    val on: Boolean get() = BuildConfig.FLAVOR == "personal"

    // ── AI chat ──────────────────────────────────────────────────
    val AI_PACKAGES = setOf(
        "com.google.android.apps.bard", "com.anthropic.claude", "com.openai.chatgpt",
        "ai.perplexity.app.android", "com.microsoft.copilot", "com.deepseek.chat", "ai.x.grok",
        "ai.character.app", "com.poe.android", "com.quora.poe", "com.mistral.lechat", "ai.meta.app",
        "com.google.android.apps.labs.language.tailwind", // NotebookLM
    )
    val AI_HOSTS = listOf(
        "gemini.google.com", "aistudio.google.com", "bard.google.com", "notebooklm.google.com",
        "claude.ai", "chatgpt.com", "chat.openai.com", "perplexity.ai", "copilot.microsoft.com",
        "poe.com", "chat.deepseek.com", "grok.com", "character.ai", "janitorai.com", "openrouter.ai",
        "chat.mistral.ai", "meta.ai", "t3.chat", "lmarena.ai", "pi.ai", "chub.ai", "kimi.com", "chat.qwen.ai",
    )

    fun isAiHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return AI_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    // ── Salah ────────────────────────────────────────────────────
    const val BEFORE_MIN = 5
    const val AFTER_MIN = 35
    private const val LAT = 30.0444
    private const val LNG = 31.2357

    private val QURAN_HINTS = listOf("quran", "mushaf", "koran", "ayah", "muslim")
    private val CALL_PACKAGES = setOf(
        "com.android.dialer", "com.google.android.dialer", "com.android.contacts",
        "com.google.android.contacts", "com.android.incallui", "com.android.server.telecom",
        "com.android.phone", "com.samsung.android.dialer", "com.samsung.android.incallui",
        "com.android.emergency", "com.google.android.apps.safetyhub",
    )

    fun allowedInSalah(pkg: String): Boolean =
        pkg in CALL_PACKAGES || QURAN_HINTS.any { pkg.lowercase().contains(it) }

    fun inSalah(nowMs: Long = System.currentTimeMillis()): Boolean = salahWindow(nowMs) != null

    /** Name and Adhan time (ms) of the prayer whose window contains [nowMs], or null. */
    fun salahWindow(nowMs: Long = System.currentTimeMillis()): Pair<String, Long>? {
        for (dayOffset in -1..1) {
            val day = Calendar.getInstance(cairo()).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, dayOffset) }
            for ((name, at) in times(day)) {
                if (nowMs >= at - BEFORE_MIN * 60_000L && nowMs < at + AFTER_MIN * 60_000L) return name to at
            }
        }
        return null
    }

    private fun cairo(): TimeZone = TimeZone.getTimeZone("Africa/Cairo")

    /** Adhan times (epoch ms) for the calendar day of [day], Cairo. */
    fun times(day: Calendar): List<Pair<String, Long>> {
        val y = day.get(Calendar.YEAR); val m = day.get(Calendar.MONTH) + 1; val d = day.get(Calendar.DAY_OF_MONTH)
        val midnight = Calendar.getInstance(cairo()).apply { clear(); set(y, m - 1, d, 0, 0, 0) }.timeInMillis
        val offset = cairo().getOffset(midnight + 12 * 3_600_000L) / 3_600_000.0
        val jd = julian(y, m, d) - LNG / (15 * 24)
        val (decl, eqt) = sun(jd + 0.5)
        val noon = 12 - eqt - LNG / 15 + offset
        fun angle(a: Double): Double {
            val v = (-sin(a) - sin(decl) * sin(LAT)) / (cos(decl) * cos(LAT))
            return acos(v.coerceIn(-1.0, 1.0)) / 15
        }
        val asrAlt = -acot(1 + tan(Math.abs(LAT - decl)))
        val hours = listOf(
            "Fajr" to noon - angle(19.5),
            "Dhuhr" to noon + 1.0 / 60,
            "Asr" to noon + angle(asrAlt),
            "Maghrib" to noon + angle(0.833),
            "Isha" to noon + angle(17.5),
        )
        return hours.map { (n, h) -> n to midnight + Math.round(h * 60) * 60_000L }
    }

    private fun julian(yy: Int, mm: Int, d: Int): Double {
        var y = yy; var m = mm
        if (m <= 2) { y--; m += 12 }
        val a = Math.floor(y / 100.0); val b = 2 - a + Math.floor(a / 4)
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    private fun sun(jd: Double): Pair<Double, Double> {
        val dd = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * dd, 360.0); val q = fix(280.459 + 0.98564736 * dd, 360.0)
        val l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * dd
        val ra = atan2(cos(e) * sin(l), cos(l)) / 15
        var eqt = q / 15 - fix(ra, 24.0)
        if (eqt > 12) eqt -= 24; if (eqt < -12) eqt += 24
        return asin(sin(e) * sin(l)) to eqt
    }

    private fun fix(a: Double, b: Double): Double { val r = a - b * Math.floor(a / b); return if (r < 0) r + b else r }
    private fun sin(d: Double) = Math.sin(Math.toRadians(d))
    private fun cos(d: Double) = Math.cos(Math.toRadians(d))
    private fun tan(d: Double) = Math.tan(Math.toRadians(d))
    private fun asin(x: Double) = Math.toDegrees(Math.asin(x))
    private fun acos(x: Double) = Math.toDegrees(Math.acos(x))
    private fun atan2(y: Double, x: Double) = Math.toDegrees(Math.atan2(y, x))
    private fun acot(x: Double) = Math.toDegrees(Math.atan(1 / x))

    // ── Videos ───────────────────────────────────────────────────
    const val CURRICULUM = "Egyptian STEM science track, grade 12: Physics, Chemistry, Biology, Geology, English, Arabic"
    const val STRICT_DAYS = 14

    val promptAddendum: String
        get() = if (!on) "" else """
Also judge the curriculum: set "curriculum" to true only if this video teaches one of these school subjects: $CURRICULUM. Anything else (other subjects, general interest, entertainment, motivation, vlogs) is false. Add it to the JSON as "curriculum": true|false."""

    private const val PREFS = "personal_rules"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun startDay(c: Context): Long {
        val p = prefs(c)
        val s = p.getLong("start", 0L)
        if (s != 0L) return s
        val now = System.currentTimeMillis()
        p.edit().putLong("start", now).apply()
        return now
    }

    fun rememberCurriculum(c: Context, videoId: String, curriculum: Boolean) {
        prefs(c).edit().putBoolean("cur_$videoId", curriculum).apply()
    }

    private fun today(): String {
        val cal = Calendar.getInstance(cairo())
        return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** Pure decision, for tests: null = allow, else the reason it is held back. */
    fun videoGate(curriculum: Boolean, daysIn: Long, todaysPick: String?, videoId: String): String? = when {
        curriculum -> null
        daysIn < STRICT_DAYS -> "Only your science subjects for the first $STRICT_DAYS days (day ${daysIn + 1})."
        todaysPick == null || todaysPick == videoId -> null
        else -> "You've had today's one free video. Tomorrow brings another."
    }

    /** The owner's video rule on top of the Keeper's verdict. */
    fun gate(c: Context, videoId: String, v: KeeperVerdict, trusted: Boolean = false): KeeperVerdict {
        if (!on || !v.allow) return v
        val p = prefs(c)
        val curriculum = trusted || p.getBoolean("cur_$videoId", false)
        val daysIn = (System.currentTimeMillis() - startDay(c)) / 86_400_000L
        val key = "pick_${today()}"
        val why = videoGate(curriculum, daysIn, p.getString(key, null), videoId)
            ?: run {
                if (!curriculum) p.edit().putString(key, videoId).apply()
                return v
            }
        return KeeperVerdict(false, reason = why, confidence = 1.0, action = "block")
    }
}
