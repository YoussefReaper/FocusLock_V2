package com.focuslock.mdm

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The application object, which exists for exactly one reason: to put the
 * bedtime dim on every screen instead of most of them.
 *
 * The dim was opt-in per screen - `FocusUi.dimOverlay` added by hand in
 * `screenRoot`-based activities - so it reached the four tabs, the settings
 * screens and the block screen, and silently missed the safe browser, text
 * search, the video library and player, onboarding and the permission recovery
 * screen. Those are the ones you are most likely to actually be looking at
 * late at night, which made "bedtime dims the screen" read as broken.
 *
 * Attaching it from a lifecycle callback instead means it covers every
 * activity, including any added later, without each one having to remember.
 */
class FocusLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is on record. Local only;
        // see Diagnostics.
        Diagnostics.install(this)
        registerActivityLifecycleCallbacks(DimAttacher())
    }

    private class DimAttacher : ActivityLifecycleCallbacks {

        override fun onActivityResumed(activity: Activity) {
            val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
            val existing = root.findViewWithTag<View>(TAG)
            val percent = if (Standalone.mayActNow(activity) && Bedtime.isActive(activity) && !AppBlockerService.isBedtimeDimOverlayShowing) {
                Bedtime.dimPercent(activity)
            } else {
                0
            }

            if (percent <= 0) {
                existing?.let { root.removeView(it) }
                return
            }

            val colour = UiPrefs.withAlpha(android.graphics.Color.BLACK, percent * 255 / 100)
            if (existing != null) {
                existing.setBackgroundColor(colour)
                // Keep it on top: the activity may have added views since.
                existing.bringToFront()
                return
            }

            val dim = View(activity)
            dim.tag = TAG
            dim.setBackgroundColor(colour)
            // Never takes a touch: this is a filter over the screen, not a
            // scrim that blocks it. A dim you cannot tap through would make
            // the whole app unusable after the cut-off.
            dim.isClickable = false
            dim.isFocusable = false
            dim.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            root.addView(dim)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit

        private companion object {
            const val TAG = "focuslock_bedtime_dim"
        }
    }
}
