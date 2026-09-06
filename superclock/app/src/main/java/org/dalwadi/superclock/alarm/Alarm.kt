package org.dalwadi.superclock.alarm

/**
 * A single scheduled alarm.
 *
 * Superclock keeps at most one alarm at a time — it is a bedside clock, not a
 * calendar — but the store is keyed by id so snooze can chain without racing the
 * alarm it replaces.
 */
data class Alarm(
    val id: Int,
    /** Absolute wall-clock instant the alarm fires, in epoch millis. */
    val triggerAtMillis: Long,
    /** Hour of day 0..23 the alarm reads as on screen. */
    val hour: Int,
    /** Minute 0..59. */
    val minute: Int,
    /** True when this instance came from the snooze button rather than speech. */
    val isSnooze: Boolean = false,
) {
    companion object {
        /** The one user-set alarm. Snoozes reuse it so only one can ever be pending. */
        const val PRIMARY_ID = 1
    }
}
