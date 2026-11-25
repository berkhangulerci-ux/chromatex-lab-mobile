package org.chromatex.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Boot auto-launch is OFF.
 * Earlier HOME + boot launch made the tablet feel locked.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // no-op — user opens the app manually
    }
}
