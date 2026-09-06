package org.dalwadi.superclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Re-arms the persisted alarm whenever the system forgets it: a reboot wipes
 * AlarmManager, an app update cancels our PendingIntents, and a clock or zone
 * change moves the target instant.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (val action = intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Re-arming after $action")
                AlarmScheduler.rescheduleFromStore(context)
            }

            // A corrected wall clock invalidates the stored absolute instant in exactly
            // the same way a zone change does, so both go through the same rebase.
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.i(TAG, "Rebasing after $action")
                rebaseToLocalTime(context)
            }

            else -> Log.w(TAG, "Ignoring unexpected action $action")
        }
    }

    /**
     * An alarm set for 07:00 means 07:00 by whatever the phone now believes the local
     * time to be, so the stored wall-clock time is re-resolved rather than the old
     * absolute instant being re-armed.
     */
    private fun rebaseToLocalTime(context: Context) {
        val alarm = AlarmStore.get(context) ?: return

        // A snooze is a duration, not a time of day, so its remaining interval is what
        // matters — but the store keeps only the absolute instant, and nothing records
        // when the snooze was pressed on the old clock, so the interval cannot be
        // recovered here. Re-arming the stored instant is the best available choice.
        if (alarm.isSnooze) {
            AlarmScheduler.rescheduleFromStore(context)
            return
        }

        val now = ZonedDateTime.now(ZoneId.systemDefault())
        var next = now
            .withHour(alarm.hour)
            .withMinute(alarm.minute)
            .withSecond(0)
            .withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val triggerAt = next.toInstant().toEpochMilli()
        Log.i(TAG, "Rebased ${alarm.hour}:${alarm.minute} to $triggerAt")
        AlarmScheduler.schedule(context, alarm.copy(triggerAtMillis = triggerAt))
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
