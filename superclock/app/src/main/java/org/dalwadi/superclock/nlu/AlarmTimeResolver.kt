package org.dalwadi.superclock.nlu

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Places a spoken time on the calendar. Pure: every instant it needs comes in as an
 * argument, so the whole thing is testable without a clock.
 */
object AlarmTimeResolver {

    data class Resolved(val triggerAtMillis: Long, val hour: Int, val minute: Int)

    /**
     * Returns the next occurrence of [command], strictly after [nowMillis].
     *
     * When the speaker named a meridiem the hour is taken as given. When they said a
     * bare "seven" both readings are candidates and the sooner one wins, which is what
     * a person means: at 1am "seven" is this morning, at 8am it is this evening.
     */
    fun resolve(
        command: Command.SetAlarm,
        nowMillis: Long,
        zone: ZoneId,
    ): Resolved {
        val now = Instant.ofEpochMilli(nowMillis)
        val here = now.atZone(zone)
        val minute = command.minute.coerceIn(0, 59)

        val hours = if (command.meridiemExplicit) {
            listOf(command.hour.coerceIn(0, 23))
        } else {
            val face = command.hour.coerceIn(0, 12) % 12
            listOf(face, face + 12)
        }

        val chosen = hours
            .map { nextOccurrence(here, it, minute, zone, now) }
            .reduce { a, b -> if (b.toInstant().isBefore(a.toInstant())) b else a }

        return Resolved(chosen.toInstant().toEpochMilli(), chosen.hour, chosen.minute)
    }

    /**
     * ZonedDateTime.of pushes a local time that a spring-forward gap deleted later by
     * the length of the gap, so an alarm set inside the gap still rings once that
     * morning rather than being skipped or fired an hour early.
     */
    private fun nextOccurrence(
        here: ZonedDateTime,
        hour: Int,
        minute: Int,
        zone: ZoneId,
        now: Instant,
    ): ZonedDateTime {
        val time = LocalTime.of(hour, minute)
        val today = ZonedDateTime.of(here.toLocalDate(), time, zone)
        if (today.toInstant().isAfter(now)) return today
        return ZonedDateTime.of(here.toLocalDate().plusDays(1), time, zone)
    }
}
