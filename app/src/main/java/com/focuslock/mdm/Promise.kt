package com.focuslock.mdm

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.VideoView
import androidx.annotation.StringRes
import java.io.File

/**
 * A promise is what a session is to the person making it: what they are protecting, how firm
 * it is, their own words for why, and optionally a picture or short clip that reminds them.
 * It sits on top of the session engine; the three strengths are named starting points for the
 * existing modes, and everything finer stays under You > Advanced.
 */
object Promise {
    /** Whether a session can be ended early: the promise's own word if it gave one, else [fallback]. */
    fun endsEarly(noExit: Boolean?, fallback: Boolean): Boolean = noExit?.let { !it } ?: fallback


    enum class Strength(
        val id: String,
        val mode: FocusMode,
        /** True removes the early exit for this promise; false puts it back. */
        val noExit: Boolean,
        @StringRes val labelRes: Int,
        @StringRes val lineRes: Int
    ) {
        SOFT("soft", FocusMode.SOFT, false, R.string.strength_soft, R.string.strength_soft_line),
        FIRM("firm", FocusMode.BLOCK, false, R.string.strength_firm, R.string.strength_firm_line),
        SEALED("sealed", FocusMode.SANCTUARY, true, R.string.strength_sealed, R.string.strength_sealed_line);

        companion object {
            fun of(id: String?): Strength = values().firstOrNull { it.id == id } ?: FIRM
        }
    }

    /** What a promise can protect: id to label. The ids are stored, the labels are not. */
    val PROTECTS: List<Pair<String, Int>> = listOf(
        "study" to R.string.protect_study,
        "sleep" to R.string.protect_sleep,
        "salah" to R.string.protect_salah,
        "eyes" to R.string.protect_eyes,
        "family" to R.string.protect_family,
    )

    const val MAX_WORDS = 90
    const val MAX_MEDIA_BYTES = 40L * 1024 * 1024
    private const val PREFS = "promise"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun words(c: Context): String = prefs(c).getString("words", "").orEmpty()
    fun setWords(c: Context, value: String) = prefs(c).edit().putString("words", clean(value)).apply()

    fun strength(c: Context): Strength = Strength.of(prefs(c).getString("strength", null))
    fun setStrength(c: Context, s: Strength) = prefs(c).edit().putString("strength", s.id).apply()

    fun protects(c: Context): Set<String> = prefs(c).getStringSet("protects", setOf("study")).orEmpty()
    fun setProtects(c: Context, ids: Set<String>) = prefs(c).edit().putStringSet("protects", ids.toSet()).apply()

    /** One line, trimmed and capped: it is shown large on the gate. */
    fun clean(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_WORDS)

    // ── picture, GIF or short clip ───────────────────────────────

    private fun mediaFile(c: Context) = File(c.filesDir, "promise_media")
    fun mediaType(c: Context): String = prefs(c).getString("mediaType", "").orEmpty()
    fun hasMedia(c: Context): Boolean = mediaType(c).isNotEmpty() && mediaFile(c).length() > 0

    fun clearMedia(c: Context) {
        mediaFile(c).delete()
        prefs(c).edit().remove("mediaType").apply()
    }

    /** Copies the picked file into the app, so it keeps showing whatever happens to the original. */
    fun importMedia(c: Context, uri: Uri): Boolean = try {
        val type = c.contentResolver.getType(uri).orEmpty()
        if (!acceptsType(type)) false else {
            val tmp = File(c.filesDir, "promise_media.tmp")
            var total = 0L
            var ok = true
            c.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_MEDIA_BYTES) { ok = false; break }
                        out.write(buf, 0, n)
                    }
                }
            } ?: run { ok = false }
            if (ok && total > 0 && tmp.renameTo(mediaFile(c))) {
                prefs(c).edit().putString("mediaType", type).apply()
                true
            } else { tmp.delete(); false }
        }
    } catch (_: Exception) { false }

    fun acceptsType(type: String): Boolean = type.startsWith("image/") || type.startsWith("video/")

    /** The saved picture or clip as a rounded view, or null when there is none or it cannot be shown. */
    fun mediaView(c: Context, heightDp: Int = 180, round: Boolean = false): View? {
        if (!hasMedia(c)) return null
        return try {
            val file = mediaFile(c)
            val radius = FocusUi.dpf(c, 20)
            val holder = FrameLayout(c)
            holder.layoutParams = if (round) {
                // Moon: their picture sits in the painting's round window.
                LinearLayout.LayoutParams(FocusUi.dp(c, heightDp), FocusUi.dp(c, heightDp)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            } else LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, FocusUi.dp(c, heightDp)
            )
            holder.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    if (round) outline.setOval(0, 0, view.width, view.height)
                    else outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            if (round) holder.elevation = FocusUi.dpf(c, 12)
            holder.clipToOutline = true
            if (mediaType(c).startsWith("video/")) {
                val video = VideoView(c)
                video.setVideoURI(Uri.fromFile(file))
                video.setOnPreparedListener { player ->
                    player.isLooping = true
                    player.setVolume(0f, 0f)   // always silent
                    video.start()
                }
                video.setOnErrorListener { _, _, _ -> holder.visibility = View.GONE; true }
                holder.addView(video, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
            } else {
                val image = ImageView(c)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    // Large photos are decoded small enough for a card.
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1600) decoder.setTargetSampleSize(longest / 1600 + 1)
                }
                image.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.start()
                holder.addView(image, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            holder
        } catch (_: Exception) { null }
    }
}
