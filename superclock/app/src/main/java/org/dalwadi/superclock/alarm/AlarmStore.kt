package org.dalwadi.superclock.alarm

import android.content.Context
import android.content.SharedPreferences

/**
 * The one persisted alarm.
 *
 * Backed by device-protected storage so [BootReceiver] can re-arm the alarm during
 * direct boot, long before the user unlocks the phone for the first time.
 */
object AlarmStore {

    private const val PREFS = "superclock_alarms"

    private const val KEY_ID = "id"
    private const val KEY_TRIGGER_AT = "triggerAtMillis"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_SNOOZE = "isSnooze"

    fun get(context: Context): Alarm? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_TRIGGER_AT)) return null

        val triggerAt = prefs.getLong(KEY_TRIGGER_AT, 0L)
        val hour = prefs.getInt(KEY_HOUR, -1)
        val minute = prefs.getInt(KEY_MINUTE, -1)
        if (triggerAt <= 0L || hour !in 0..23 || minute !in 0..59) return null

        return Alarm(
            id = prefs.getInt(KEY_ID, Alarm.PRIMARY_ID),
            triggerAtMillis = triggerAt,
            hour = hour,
            minute = minute,
            isSnooze = prefs.getBoolean(KEY_SNOOZE, false),
        )
    }

    /** Blocks until the alarm is on disk; the caller schedules against it immediately after. */
    fun put(context: Context, alarm: Alarm) {
        prefs(context).edit()
            .putInt(KEY_ID, alarm.id)
            .putLong(KEY_TRIGGER_AT, alarm.triggerAtMillis)
            .putInt(KEY_HOUR, alarm.hour)
            .putInt(KEY_MINUTE, alarm.minute)
            .putBoolean(KEY_SNOOZE, alarm.isSnooze)
            .commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
