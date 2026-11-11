package com.focuslock.mdm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The pine slab the promise sits on: a deep green gradient with growth rings in one corner,
 * the same on the phone and the laptop.
 */
class SlabDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val radius = 28 * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.parseColor("#F4F0E6")
    }
    private val clip = Path()
    private val rect = RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(bounds)
        fill.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            Color.parseColor("#174A3A"), Color.parseColor("#0C2720"), Shader.TileMode.CLAMP
        )
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, fill)
        val save = canvas.save()
        canvas.clipPath(clip)
        val cx = rect.right - 36 * density
        val cy = rect.top + 44 * density
        RINGS.forEachIndexed { i, r ->
            ring.alpha = (255 * ALPHAS[i]).toInt()
            canvas.drawCircle(cx, cy, r * density, ring)
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val RINGS = floatArrayOf(48f, 96f, 150f, 210f, 276f, 350f)
        val ALPHAS = floatArrayOf(0.10f, 0.08f, 0.07f, 0.05f, 0.04f, 0.03f)
    }
}
