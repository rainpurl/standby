package org.dalwadi.superclock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import org.dalwadi.superclock.clock.ClockActivity
import org.dalwadi.superclock.nlu.AlarmTimeResolver
import org.dalwadi.superclock.nlu.Command
import java.time.ZoneId

/**
 * Arms exactly one alarm with the system.
 *
 * Everything goes through `setAlarmClock`: it is the only delivery that Doze and
 * app standby never defer, and it is what puts the alarm icon in the status bar.
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    private const val ACTION_FIRE = "org.dalwadi.superclock.ALARM_FIRE"

    /** Derived from the alarm id so cancel() always matches what schedule() created. */
    private const val REQ_FIRE = Alarm.PRIMARY_ID
    private const val REQ_SHOW = Alarm.PRIMARY_ID + 100

    fun schedule(context: Context, alarm: Alarm) {
        val app = context.applicationContext
        AlarmStore.put(app, alarm)

        val manager = app.getSystemService<AlarmManager>()
        if (manager == null) {
            Log.e(TAG, "No AlarmManager; alarm persisted but not armed")
            return
        }

        val operation = firePendingIntent(app, alarm.hour, alarm.minute)
        // The PendingIntent is reused (FLAG_UPDATE_CURRENT), so cancelling it here
        // drops whatever was previously armed and leaves exactly one alarm pending.
        manager.cancel(operation)

        if (canScheduleExact(app)) {
            try {
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(alarm.triggerAtMillis, showPendingIntent(app)),
                    operation,
                )
                Log.i(TAG, "Armed exact alarm for ${alarm.triggerAtMillis} (snooze=${alarm.isSnooze})")
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm refused despite permission check", e)
            }
        } else {
            Log.w(TAG, "Exact alarms not permitted; falling back to inexact delivery")
        }

        // Approximate is far better than silent: the alarm still wakes the device,
        // just within the window the OS chooses.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, operation)
    }

    fun cancel(context: Context): Boolean {
        val app = context.applicationContext
        val stored = AlarmStore.get(app)
        val wasRinging = AlarmService.ringing.value

        val operation = firePendingIntent(app, stored?.hour ?: 0, stored?.minute ?: 0)
        app.getSystemService<AlarmManager>()?.cancel(operation)
        operation.cancel()

        AlarmStore.clear(app)

        // Only safe while the service is alive — a background startService on a dead
        // service would be refused outright.
        if (wasRinging) {
            app.startService(AlarmService.stopIntent(app))
        }

        return stored != null || wasRinging
    }

    fun rescheduleFromStore(context: Context) {
        val app = context.applicationContext
        val alarm = AlarmStore.get(app) ?: return

        val now = System.currentTimeMillis()
        if (alarm.triggerAtMillis > now) {
            schedule(app, alarm)
            return
        }

        // This runs at boot, where the wall clock comes straight off an RTC that may be
        // badly wrong, so an apparently elapsed instant is no evidence the alarm is spent.
        // A snooze, however, means nothing but its absolute instant, so a past one is done.
        if (alarm.isSnooze) {
            Log.i(TAG, "Stored snooze at ${alarm.triggerAtMillis} already elapsed; dropping")
            cancel(app)
            return
        }

        // For a normal alarm the stored hour/minute is the user's real intent, so
        // re-resolve it to the next matching future instant instead of deleting it.
        // The stored hour is already 0..23, hence meridiemExplicit.
        val resolved = AlarmTimeResolver.resolve(
            Command.SetAlarm(alarm.hour, alarm.minute, meridiemExplicit = true),
            now,
            ZoneId.systemDefault(),
        )
        Log.i(TAG, "Re-resolved ${alarm.hour}:${alarm.minute} to ${resolved.triggerAtMillis}")
        schedule(app, alarm.copy(triggerAtMillis = resolved.triggerAtMillis))
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.applicationContext.getSystemService<AlarmManager>()
            ?.canScheduleExactAlarms() ?: false
    }

    private fun firePendingIntent(context: Context, hour: Int, minute: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(AlarmService.EXTRA_HOUR, hour)
            .putExtra(AlarmService.EXTRA_MINUTE, minute)
        return PendingIntent.getBroadcast(
            context,
            REQ_FIRE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Where the status-bar alarm icon takes the user when tapped. */
    private fun showPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ClockActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            REQ_SHOW,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
