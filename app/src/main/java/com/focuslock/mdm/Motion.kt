package com.focuslock.mdm

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator

/**
 * Calm motion.
 *
 * Short, eased, and never bouncy: this is an app people open when they are
 * already agitated, and springy animation reads as noise. Every helper checks
 * the reduced-motion token first and simply sets the end state when it is on,
 * so turning motion off never leaves a view half-animated.
 */
object Motion {

    private const val QUICK_MS = 140L
    private const val STANDARD_MS = 220L
    private const val SLOW_MS = 320L

    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun fadeIn(view: View, tokens: UiPrefs.Tokens, delayMs: Long = 0L) {
        if (tokens.reducedMotion) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = FocusUi.dpf(view.context, 8)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(STANDARD_MS)
            .setInterpolator(easing)
            .start()
    }

    /**
     * Staggers a column of cards so the eye lands on the top one first. The
     * stagger is capped so a long list never turns into a wave.
     */
    fun stagger(views: List<View>, tokens: UiPrefs.Tokens) {
        if (tokens.reducedMotion) {
            views.forEach {
                it.alpha = 1f
                it.translationY = 0f
            }
            return
        }
        views.forEachIndexed { index, view ->
            fadeIn(view, tokens, delayMs = (index * 28L).coerceAtMost(220L))
        }
    }

    fun tap(view: View, tokens: UiPrefs.Tokens) {
        if (tokens.reducedMotion) return
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(QUICK_MS / 2)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(QUICK_MS)
                    .setInterpolator(easing)
                    .start()
            }
            .start()
    }

    fun crossfade(outgoing: View?, incoming: View, tokens: UiPrefs.Tokens) {
        incoming.visibility = View.VISIBLE
        if (tokens.reducedMotion) {
            outgoing?.visibility = View.GONE
            incoming.alpha = 1f
            return
        }
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(STANDARD_MS).setInterpolator(easing).start()
        outgoing?.animate()
            ?.alpha(0f)
            ?.setDuration(QUICK_MS)
            ?.withEndAction {
                outgoing.visibility = View.GONE
                outgoing.alpha = 1f
            }
            ?.start()
    }

    /** Eases a progress ring to its new value instead of jumping. */
    fun animateProgress(
        indicator: com.google.android.material.progressindicator.CircularProgressIndicator,
        target: Int,
        tokens: UiPrefs.Tokens
    ) {
        if (tokens.reducedMotion) {
            indicator.progress = target
            return
        }
        val start = indicator.progress
        if (start == target) return
        ValueAnimator.ofInt(start, target).apply {
            duration = SLOW_MS
            interpolator = easing
            addUpdateListener { animator ->
                indicator.progress = animator.animatedValue as Int
            }
            start()
        }
    }

    /**
     * The breathing pause on the intercept screen. Deliberately slow: the whole
     * point is that the few seconds are felt, not skipped past.
     */
    fun breathe(view: View, tokens: UiPrefs.Tokens) {
        if (tokens.reducedMotion) return
        view.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .alpha(0.85f)
            .setDuration(2_200L)
            .setInterpolator(easing)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(2_200L)
                    .setInterpolator(easing)
                    .withEndAction { breathe(view, tokens) }
                    .start()
            }
            .start()
    }
}
