package org.dalwadi.superclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The alarm went off. Hand over to [AlarmService] and get out of the way — a
 * receiver has ten seconds before the system considers it hung, and starting the
 * foreground service is the only thing that has to happen inside that window.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val stored = AlarmStore.get(context)
        val hour = intent?.getIntExtra(AlarmService.EXTRA_HOUR, -1)?.takeIf { it in 0..23 }
            ?: stored?.hour
            ?: 0
        val minute = intent?.getIntExtra(AlarmService.EXTRA_MINUTE, -1)?.takeIf { it in 0..59 }
            ?: stored?.minute
            ?: 0

        Log.i(TAG, "Alarm fired for $hour:$minute")
        try {
            ContextCompat.startForegroundService(
                context,
                AlarmService.ringIntent(context, hour, minute),
            )
        } catch (e: Exception) {
            // On API 31+ a background foreground-service start can be refused outright
            // (ForegroundServiceStartNotAllowedException), e.g. when the alarm arrived by
            // inexact delivery and so carried no FGS-launch exemption. Leave the alarm in
            // the store so a later reschedule can still find and re-arm it.
            Log.e(TAG, "Could not start ringer; leaving alarm in store", e)
            return
        }

        // Cleared only after the start was accepted, so a snooze can write its own entry
        // without racing the one that just fired.
        AlarmStore.clear(context)
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
