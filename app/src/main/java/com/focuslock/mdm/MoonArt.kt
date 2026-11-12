package com.focuslock.mdm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * The Moon look's paintings: one per screen, every one a disc with a dark swirl
 * around it, drawn with its edge fading into the page so it never sits in a box.
 */
object MoonArt {

    /** The pine's seven stages over a promise. Stages 2, 3, 5, 6 fall back to their neighbours until painted. */
    private val STAGES = intArrayOf(
        R.drawable.art_stage1, R.drawable.art_stage1, R.drawable.art_stage4, R.drawable.art_stage4,
        R.drawable.art_stage4, R.drawable.art_moonpine, R.drawable.art_stage7
    )

    /** 0..6 for how far through a promise is. The last stage is only reached at the very end. */
    fun stageIndex(fraction: Float): Int = when {
        fraction >= 1f -> 6
        else -> (fraction.coerceIn(0f, 0.999f) * 6).toInt()
    }

    @DrawableRes
    fun stage(fraction: Float): Int = STAGES[stageIndex(fraction)]

    /** The day's moon for how much of the day's goal was held. */
    @DrawableRes
    fun moon(fraction: Float): Int = when {
        fraction <= 0.05f -> R.drawable.moon_0
        fraction < 0.375f -> R.drawable.moon_25
        fraction < 0.625f -> R.drawable.moon_50
        fraction < 0.9f -> R.drawable.moon_75
        else -> R.drawable.moon_100
    }

    /** A painting whose square edges fade into the background. */
    fun painting(context: Context, @DrawableRes res: Int, sizeDp: Int, description: String? = null): View {
        val view = PaintingView(context, res)
        val px = FocusUi.dp(context, sizeDp)
        view.layoutParams = LinearLayout.LayoutParams(px, px).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        view.contentDescription = description
        view.importantForAccessibility = if (description == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        return view
    }

    /** A small round crop of a painting: modes, moons, stones. [ring] draws the selected outline. */
    fun round(context: Context, @DrawableRes res: Int, sizeDp: Int, ring: Int? = null, dim: Boolean = false, zoom: Float = 1f): View {
        val view = RoundView(context, res, ring, zoom)
        val px = FocusUi.dp(context, sizeDp)
        view.layoutParams = LinearLayout.LayoutParams(px, px)
        view.alpha = if (dim) 0.55f else 1f
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return view
    }

    private fun bitmap(context: Context, res: Int): Bitmap? =
        (ContextCompat.getDrawable(context, res) as? BitmapDrawable)?.bitmap

    private class PaintingView(context: Context, res: Int) : View(context) {
        private val bmp = bitmap(context, res)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        private val matrix = Matrix()

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val layer = canvas.saveLayer(0f, 0f, w, h, null)
            matrix.reset()
            val s = maxOf(w / b.width, h / b.height)
            matrix.postScale(s, s)
            matrix.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
            canvas.drawBitmap(b, matrix, paint)
            val r = minOf(w, h) / 2f
            mask.shader = RadialGradient(w / 2f, h / 2f, r * 1.4f,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.525f, 0.707f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, mask)
            canvas.restoreToCount(layer)
        }
    }

    private class RoundView(context: Context, res: Int, private val ring: Int?, private val zoom: Float) : View(context) {
        private val bmp = bitmap(context, res)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            val w = width.toFloat(); val h = height.toFloat()
            val gap = if (ring != null) FocusUi.dpf(context, 4) else 0f
            val r = minOf(w, h) / 2f - gap
            val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            val s = (2 * r / minOf(b.width, b.height)) * zoom
            m.postScale(s, s)
            m.postTranslate(w / 2f - b.width * s / 2f, h / 2f - b.height * s / 2f)
            shader.setLocalMatrix(m)
            paint.shader = shader
            canvas.drawCircle(w / 2f, h / 2f, r, paint)
            if (ring != null) {
                ringPaint.color = ring
                ringPaint.strokeWidth = FocusUi.dpf(context, 1).coerceAtLeast(2f)
                canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f - ringPaint.strokeWidth, ringPaint)
            }
        }
    }
}
