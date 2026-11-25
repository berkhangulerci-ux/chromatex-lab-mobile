package org.chromatex.lab

import android.app.Activity
import android.view.WindowManager

/**
 * Dedicated / kiosk usage — keep screen on.
 * LockTask only for non-Play editions; Play stays a normal consumer app.
 */
object DedicatedMode {

    fun apply(activity: Activity) {
        if (BuildConfig.IS_PLAY_STORE) return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Avoid pin-to-screen training balloons unless we explicitly lock.
        try {
            activity.stopLockTask()
        } catch (_: Exception) {
            // already unlocked
        }
    }

    fun hideSystemBars(activity: Activity) {
        if (BuildConfig.IS_PLAY_STORE) return
        apply(activity)
    }
}
