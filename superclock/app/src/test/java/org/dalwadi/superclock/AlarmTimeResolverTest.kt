package org.dalwadi.superclock

import org.dalwadi.superclock.nlu.AlarmTimeResolver
import org.dalwadi.superclock.nlu.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Every case pins an explicit instant and zone; the resolver is pure, so nothing here may
 * read the system clock or the default zone.
 */
class AlarmTimeResolverTest {

    private val utc = ZoneId.of("UTC")
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(
        zone: ZoneId,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute, second), zone)

    /** Asserts the trigger instant and that the reported wall clock matches it in [zone]. */
    private fun assertResolves(
        zone: ZoneId,
        now: ZonedDateTime,
        command: Command.SetAlarm,
        expected: ZonedDateTime,
    ): AlarmTimeResolver.Resolved {
        val nowMillis = now.toInstant().toEpochMilli()
        val resolved = AlarmTimeResolver.resolve(command, nowMillis, zone)
        assertEquals(
            "trigger instant for $command at $now",
            expected.toInstant(),
            Instant.ofEpochMilli(resolved.triggerAtMillis),
        )
        assertTrue("must be strictly after now", resolved.triggerAtMillis > nowMillis)
        val readBack = Instant.ofEpochMilli(resolved.triggerAtMillis).atZone(zone)
        assertEquals("reported hour", readBack.hour, resolved.hour)
        assertEquals("reported minute", readBack.minute, resolved.minute)
        assertEquals("expected hour", expected.hour, resolved.hour)
        assertEquals("expected minute", expected.minute, resolved.minute)
        return resolved
    }

    @Test
    fun explicitMeridiemLaterTodayFiresToday() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 6, 0),
            command = Command.SetAlarm(7, 30, true),
            expected = at(utc, 2026, 6, 15, 7, 30),
        )
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 6, 0),
            command = Command.SetAlarm(19, 0, true),
            expected = at(utc, 2026, 6, 15, 19, 0),
        )
    }

    @Test
    fun explicitMeridiemAlreadyPastFiresTomorrow() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 9, 0),
            command = Command.SetAlarm(7, 30, true),
            expected = at(utc, 2026, 6, 16, 7, 30),
        )
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 23, 30),
            command = Command.SetAlarm(19, 0, true),
            expected = at(utc, 2026, 6, 16, 19, 0),
        )
    }

    @Test
    fun bareHourBeforeDawnMeansThisMorning() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 1, 0),
            command = Command.SetAlarm(7, 0, false),
            expected = at(utc, 2026, 6, 15, 7, 0),
        )
    }

    @Test
    fun bareHourAfterItHasPassedMeansThisEvening() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 8, 0),
            command = Command.SetAlarm(7, 0, false),
            expected = at(utc, 2026, 6, 15, 19, 0),
        )
    }

    @Test
    fun bareHourLateAtNightMeansTomorrowMorning() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 23, 0),
            command = Command.SetAlarm(7, 0, false),
            expected = at(utc, 2026, 6, 16, 7, 0),
        )
    }

    @Test
    fun bareHourCarriesItsMinutes() {
        // 06:30 has gone, so the sooner of the two readings is 18:30 the same evening.
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 8, 0),
            command = Command.SetAlarm(6, 30, false),
            expected = at(utc, 2026, 6, 15, 18, 30),
        )
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 5, 0),
            command = Command.SetAlarm(6, 30, false),
            expected = at(utc, 2026, 6, 15, 6, 30),
        )
    }

    @Test
    fun bareTwelveIsNoonOrMidnightWhicheverComesFirst() {
        // A bare "twelve" arrives as hour 12; midnight has gone, so noon wins.
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 10, 0),
            command = Command.SetAlarm(12, 0, false),
            expected = at(utc, 2026, 6, 15, 12, 0),
        )
        // After noon the next twelve is midnight.
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 13, 0),
            command = Command.SetAlarm(12, 0, false),
            expected = at(utc, 2026, 6, 16, 0, 0),
        )
    }

    @Test
    fun twelveAmIsMidnight() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 10, 0),
            command = Command.SetAlarm(0, 0, true),
            expected = at(utc, 2026, 6, 16, 0, 0),
        )
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 23, 0),
            command = Command.SetAlarm(0, 30, true),
            expected = at(utc, 2026, 6, 16, 0, 30),
        )
    }

    @Test
    fun twelvePmIsNoon() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 10, 0),
            command = Command.SetAlarm(12, 0, true),
            expected = at(utc, 2026, 6, 15, 12, 0),
        )
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 12, 1),
            command = Command.SetAlarm(12, 0, true),
            expected = at(utc, 2026, 6, 16, 12, 0),
        )
    }

    @Test
    fun midnightRollover() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 23, 59),
            command = Command.SetAlarm(0, 5, true),
            expected = at(utc, 2026, 6, 16, 0, 5),
        )
        // The last minute of a month rolls the date over correctly too.
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 30, 23, 59),
            command = Command.SetAlarm(0, 5, true),
            expected = at(utc, 2026, 7, 1, 0, 5),
        )
    }

    @Test
    fun requestedTimeEqualToNowLandsOnTheFollowingDay() {
        val now = at(utc, 2026, 6, 15, 7, 0)
        val nowMillis = now.toInstant().toEpochMilli()
        val resolved = AlarmTimeResolver.resolve(Command.SetAlarm(7, 0, true), nowMillis, utc)
        assertNotEquals("must never fire at exactly now", nowMillis, resolved.triggerAtMillis)
        assertResolves(
            zone = utc,
            now = now,
            command = Command.SetAlarm(7, 0, true),
            expected = at(utc, 2026, 6, 16, 7, 0),
        )
    }

    @Test
    fun aSecondPastTheHourAlsoRollsToTomorrow() {
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 7, 0, 1),
            command = Command.SetAlarm(7, 0, true),
            expected = at(utc, 2026, 6, 16, 7, 0),
        )
        // A second short of it still fires today.
        assertResolves(
            zone = utc,
            now = at(utc, 2026, 6, 15, 6, 59, 59),
            command = Command.SetAlarm(7, 0, true),
            expected = at(utc, 2026, 6, 15, 7, 0),
        )
    }

    @Test
    fun resultIsAlwaysStrictlyAfterNow() {
        val commands = listOf(
            Command.SetAlarm(0, 0, true),
            Command.SetAlarm(7, 0, true),
            Command.SetAlarm(12, 0, true),
            Command.SetAlarm(23, 59, true),
            Command.SetAlarm(7, 0, false),
            Command.SetAlarm(12, 30, false),
        )
        for (zone in listOf(utc, kolkata, newYork)) {
            for (hour in 0..23) {
                for (minute in listOf(0, 29, 30, 59)) {
                    val now = at(zone, 2026, 3, 8, hour, minute)
                    val nowMillis = now.toInstant().toEpochMilli()
                    for (command in commands) {
                        val resolved = AlarmTimeResolver.resolve(command, nowMillis, zone)
                        assertTrue(
                            "$command at $now in $zone resolved to ${resolved.triggerAtMillis}",
                            resolved.triggerAtMillis > nowMillis,
                        )
                        val readBack = Instant.ofEpochMilli(resolved.triggerAtMillis).atZone(zone)
                        assertEquals(readBack.hour, resolved.hour)
                        assertEquals(readBack.minute, resolved.minute)
                    }
                }
            }
        }
    }

    @Test
    fun nonDstZoneWithAnOffsetOfMinutes() {
        // Asia/Kolkata is +05:30 all year; 06:30 IST on the 16th is 01:00 UTC.
        val resolved = assertResolves(
            zone = kolkata,
            now = at(kolkata, 2026, 6, 15, 20, 15),
            command = Command.SetAlarm(6, 30, true),
            expected = at(kolkata, 2026, 6, 16, 6, 30),
        )
        assertEquals(
            Instant.parse("2026-06-16T01:00:00Z"),
            Instant.ofEpochMilli(resolved.triggerAtMillis),
        )
    }

    @Test
    fun nonDstZoneSoonestOccurrenceMatchesUtc() {
        assertResolves(
            zone = kolkata,
            now = at(kolkata, 2026, 6, 15, 8, 0),
            command = Command.SetAlarm(7, 0, false),
            expected = at(kolkata, 2026, 6, 15, 19, 0),
        )
        assertResolves(
            zone = kolkata,
            now = at(kolkata, 2026, 6, 15, 23, 0),
            command = Command.SetAlarm(7, 0, false),
            expected = at(kolkata, 2026, 6, 16, 7, 0),
        )
    }

    @Test
    fun springForwardGapStillRingsThatMorning() {
        // 2026-03-08 in New York: 02:00 EST jumps straight to 03:00 EDT, so 02:30 never
        // exists. The alarm must land in the same morning, shifted past the gap.
        val now = at(newYork, 2026, 3, 8, 0, 30)
        val nowMillis = now.toInstant().toEpochMilli()
        val resolved = AlarmTimeResolver.resolve(Command.SetAlarm(2, 30, true), nowMillis, newYork)

        val fired = Instant.ofEpochMilli(resolved.triggerAtMillis).atZone(newYork)
        assertEquals("same morning", now.toLocalDate(), fired.toLocalDate())
        assertTrue(resolved.triggerAtMillis > nowMillis)
        // Java pushes a gap time forward by the length of the gap: 02:30 EST -> 03:30 EDT.
        assertEquals(at(newYork, 2026, 3, 8, 3, 30).toInstant(), fired.toInstant())
        assertEquals(3, resolved.hour)
        assertEquals(30, resolved.minute)
        assertEquals(fired.hour, resolved.hour)
        assertEquals(fired.minute, resolved.minute)
        // Not silently an hour early (01:30 EST) and not a day late.
        assertNotEquals(at(newYork, 2026, 3, 8, 1, 30).toInstant(), fired.toInstant())
        assertNotEquals(at(newYork, 2026, 3, 9, 2, 30).toInstant(), fired.toInstant())
    }

    @Test
    fun springForwardShortensTheNightNotTheAlarm() {
        // 23:00 EST on the 7th to 07:00 EDT on the 8th is seven real hours, not eight.
        val now = at(newYork, 2026, 3, 7, 23, 0)
        val resolved = assertResolves(
            zone = newYork,
            now = now,
            command = Command.SetAlarm(7, 0, true),
            expected = at(newYork, 2026, 3, 8, 7, 0),
        )
        assertEquals(
            Duration.ofHours(7).toMillis(),
            resolved.triggerAtMillis - now.toInstant().toEpochMilli(),
        )
    }

    @Test
    fun fallBackAmbiguousHourResolvesToARealInstant() {
        // 2026-11-01 in New York: 02:00 EDT falls back to 01:00 EST, so 01:30 happens twice.
        val now = at(newYork, 2026, 11, 1, 0, 30)
        val nowMillis = now.toInstant().toEpochMilli()
        val resolved = AlarmTimeResolver.resolve(Command.SetAlarm(1, 30, true), nowMillis, newYork)

        assertTrue(resolved.triggerAtMillis > nowMillis)
        val fired = Instant.ofEpochMilli(resolved.triggerAtMillis).atZone(newYork)
        assertEquals(1, resolved.hour)
        assertEquals(30, resolved.minute)
        assertEquals(1, fired.hour)
        assertEquals(30, fired.minute)
        assertEquals("same night", now.toLocalDate(), fired.toLocalDate())
        // Java picks the earlier of the two offsets in an overlap: 01:30 EDT, an hour sooner.
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), fired.toInstant())
    }

    @Test
    fun fallBackDuringTheSecondPassStillGivesAFutureOhOneThirty() {
        // 06:15Z is 01:15 EST, the repeat of the hour, so the first 01:30 is already behind
        // us. Anchored to UTC because parsing a local time with an offset resolves the
        // overlap differently across JDKs.
        val nowMillis = Instant.parse("2026-11-01T06:15:00Z").toEpochMilli()
        val now = Instant.ofEpochMilli(nowMillis).atZone(newYork)
        assertEquals("precondition: second pass through 01:xx", 1, now.hour)
        val resolved = AlarmTimeResolver.resolve(Command.SetAlarm(1, 30, true), nowMillis, newYork)

        assertTrue(resolved.triggerAtMillis > nowMillis)
        val fired = Instant.ofEpochMilli(resolved.triggerAtMillis).atZone(newYork)
        assertEquals(1, resolved.hour)
        assertEquals(30, resolved.minute)
        assertEquals(1, fired.hour)
        assertEquals(30, fired.minute)
    }

    @Test
    fun fallBackLengthensTheNight() {
        // 23:00 EDT on Oct 31 to 07:00 EST on Nov 1 is nine real hours.
        val now = at(newYork, 2026, 10, 31, 23, 0)
        val resolved = assertResolves(
            zone = newYork,
            now = now,
            command = Command.SetAlarm(7, 0, true),
            expected = at(newYork, 2026, 11, 1, 7, 0),
        )
        assertEquals(
            Duration.ofHours(9).toMillis(),
            resolved.triggerAtMillis - now.toInstant().toEpochMilli(),
        )
    }

    @Test
    fun zoneArgumentDecidesTheAnswerNotTheHost() {
        // One instant, two zones, two different local mornings.
        val instant = Instant.parse("2026-06-15T20:00:00Z")
        val inUtc = AlarmTimeResolver.resolve(
            Command.SetAlarm(7, 0, true),
            instant.toEpochMilli(),
            utc,
        )
        val inKolkata = AlarmTimeResolver.resolve(
            Command.SetAlarm(7, 0, true),
            instant.toEpochMilli(),
            kolkata,
        )
        assertEquals(Instant.parse("2026-06-16T07:00:00Z"), Instant.ofEpochMilli(inUtc.triggerAtMillis))
        // 20:00Z is already 01:30 on the 16th in Kolkata, so 07:00 IST is later that morning.
        assertEquals(
            Instant.parse("2026-06-16T01:30:00Z"),
            Instant.ofEpochMilli(inKolkata.triggerAtMillis),
        )
        assertEquals(7, inUtc.hour)
        assertEquals(7, inKolkata.hour)
    }

    @Test
    fun resolutionIsWholeMinutes() {
        val now = at(utc, 2026, 6, 15, 6, 0, 37)
        val resolved = AlarmTimeResolver.resolve(Command.SetAlarm(7, 30, true), now.toInstant().toEpochMilli(), utc)
        val fired = Instant.ofEpochMilli(resolved.triggerAtMillis).atZone(utc)
        assertEquals(0, fired.second)
        assertEquals(0, fired.nano)
    }
}
