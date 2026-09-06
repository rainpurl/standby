package org.dalwadi.superclock

import org.dalwadi.superclock.voice.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The sentence the phone speaks back after an alarm is set.
 *
 * [Speaker.confirmationFor] is pure: the instant and the zone both come in, so every
 * case here pins both and nothing reads the system clock or the default zone.
 */
class SpeakerPhraseTest {

    private val chicago = ZoneId.of("America/Chicago")
    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    /** 2026-09-10 is a Thursday — the owner's own example day. */
    private fun thursday(hour: Int, minute: Int, zone: ZoneId = chicago): Long =
        ZonedDateTime.of(LocalDateTime.of(2026, 9, 10, hour, minute), zone).toInstant().toEpochMilli()

    private fun assertSpeaks(expected: String, millis: Long, zone: ZoneId = chicago) {
        assertEquals(expected, Speaker.confirmationFor(millis, zone))
    }

    @Test
    fun thursdayMorningReadsAsTheOwnerWroteIt() {
        assertSpeaks("Alarm set for Thursday, 7 20 AM", thursday(7, 20))
    }

    @Test
    fun onTheHourDropsTheMinutesEntirely() {
        val phrase = Speaker.confirmationFor(thursday(7, 0), chicago)
        assertEquals("Alarm set for Thursday, 7 AM", phrase)
        assertFalse("must not read the zeroes: $phrase", phrase.contains("7 00"))
        assertFalse("must not read the zeroes: $phrase", phrase.contains("7 zero"))
        assertFalse("must not read the zeroes: $phrase", phrase.contains("7 0 "))
    }

    @Test
    fun minuteUnderTenSaysOh() {
        assertSpeaks("Alarm set for Thursday, 7 oh 5 AM", thursday(7, 5))
        assertSpeaks("Alarm set for Thursday, 7 oh 1 AM", thursday(7, 1))
        assertSpeaks("Alarm set for Thursday, 7 oh 9 AM", thursday(7, 9))
    }

    @Test
    fun tenAndAboveIsSpokenPlainly() {
        assertSpeaks("Alarm set for Thursday, 7 10 AM", thursday(7, 10))
        assertSpeaks("Alarm set for Thursday, 7 45 AM", thursday(7, 45))
    }

    @Test
    fun eveningConvertsToTwelveHourPm() {
        assertSpeaks("Alarm set for Thursday, 7 30 PM", thursday(19, 30))
        assertSpeaks("Alarm set for Thursday, 11 59 PM", thursday(23, 59))
        assertSpeaks("Alarm set for Thursday, 1 15 PM", thursday(13, 15))
    }

    @Test
    fun midnightHourIsTwelveAmNotZero() {
        val halfPast = Speaker.confirmationFor(thursday(0, 30), chicago)
        assertEquals("Alarm set for Thursday, 12 30 AM", halfPast)
        assertFalse("midnight hour must not read as zero: $halfPast", halfPast.contains("0 30 AM"))

        val onTheHour = Speaker.confirmationFor(thursday(0, 0), chicago)
        assertEquals("Alarm set for Thursday, 12 AM", onTheHour)
        assertFalse("midnight must not read as zero: $onTheHour", onTheHour.contains("0 AM"))
    }

    @Test
    fun noonIsTwelvePmNotZero() {
        val onTheHour = Speaker.confirmationFor(thursday(12, 0), chicago)
        assertEquals("Alarm set for Thursday, 12 PM", onTheHour)
        assertFalse("noon must not read as zero: $onTheHour", onTheHour.contains("0 PM"))

        assertSpeaks("Alarm set for Thursday, 12 30 PM", thursday(12, 30))
        // The hour after noon is still PM, not back to AM.
        assertSpeaks("Alarm set for Thursday, 12 oh 5 PM", thursday(12, 5))
    }

    @Test
    fun weekdayFollowsTheZoneItIsGiven() {
        // Late Thursday evening in New York is already Friday lunchtime in Tokyo.
        val instant = ZonedDateTime.of(LocalDateTime.of(2026, 9, 10, 23, 30), newYork)
            .toInstant()
            .toEpochMilli()

        assertSpeaks("Alarm set for Thursday, 11 30 PM", instant, newYork)
        assertSpeaks("Alarm set for Friday, 12 30 PM", instant, tokyo)
    }

    @Test
    fun weekdayIsSpelledOutInFullEnglish() {
        val phrase = Speaker.confirmationFor(thursday(7, 20), chicago)
        assertTrue("weekday should be the full English name: $phrase", phrase.contains("Thursday"))
        assertFalse("weekday must not be abbreviated: $phrase", phrase.contains("Thu,"))
    }

    @Test
    fun sameArgumentsAlwaysGiveTheSameSentence() {
        val millis = thursday(7, 20)
        val first = Speaker.confirmationFor(millis, chicago)
        val second = Speaker.confirmationFor(millis, chicago)
        assertEquals(first, second)
        assertEquals(first, Speaker.confirmationFor(millis, chicago))
    }
}
