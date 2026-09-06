package org.dalwadi.superclock

import org.dalwadi.superclock.nlu.AlarmTimeResolver
import org.dalwadi.superclock.nlu.Command
import org.dalwadi.superclock.nlu.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * End-to-end checks on the exact wordings a person actually uses at a bedside, run
 * through the whole chain: transcript -> [CommandParser] -> [AlarmTimeResolver].
 *
 * These are the sentences the app was asked for, plus the mangled spellings Vosk
 * really emits ("a m" as two tokens, "o'clock" with the apostrophe).
 */
class SpokenPhrasesTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 9, 6, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun fire(transcript: String, nowHour: Int, nowMinute: Int = 0): ZonedDateTime {
        val command = CommandParser.parse(transcript)
        assertTrue("did not parse as SetAlarm: $transcript", command is Command.SetAlarm)
        val resolved = AlarmTimeResolver.resolve(
            command as Command.SetAlarm,
            at(nowHour, nowMinute),
            zone,
        )
        return ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(resolved.triggerAtMillis),
            zone,
        )
    }

    private fun assertFires(
        transcript: String,
        nowHour: Int,
        expectHour: Int,
        expectMinute: Int,
        expectDay: Int,
    ) {
        val f = fire(transcript, nowHour)
        assertEquals("$transcript -> hour", expectHour, f.hour)
        assertEquals("$transcript -> minute", expectMinute, f.minute)
        assertEquals("$transcript -> day", expectDay, f.dayOfMonth)
    }

    @Test
    fun `set an alarm for seven a m`() =
        assertFires("set an alarm for seven a m", nowHour = 22, 7, 0, expectDay = 7)

    @Test
    fun `set an alarm for seven am joined`() =
        assertFires("set an alarm for seven am", nowHour = 22, 7, 0, expectDay = 7)

    @Test
    fun `set an alarm for seven o'clock`() =
        assertFires("set an alarm for seven o'clock", nowHour = 22, 7, 0, expectDay = 7)

    @Test
    fun `set an alarm for six thirty in the morning`() =
        assertFires("set an alarm for six thirty in the morning", nowHour = 22, 6, 30, expectDay = 7)

    @Test
    fun `wake me up at seven thirty`() =
        assertFires("wake me up at seven thirty", nowHour = 22, 7, 30, expectDay = 7)

    @Test
    fun `half past six`() =
        assertFires("set an alarm for half past six", nowHour = 22, 6, 30, expectDay = 7)

    @Test
    fun `quarter to seven`() =
        assertFires("set an alarm for quarter to seven", nowHour = 22, 6, 45, expectDay = 7)

    @Test
    fun `seven oh five`() =
        assertFires("set an alarm for seven oh five", nowHour = 22, 7, 5, expectDay = 7)

    @Test
    fun `nineteen thirty is twenty four hour`() =
        assertFires("set an alarm for nineteen thirty", nowHour = 8, 19, 30, expectDay = 6)

    @Test
    fun `seven fifteen p m`() =
        assertFires("set an alarm for seven fifteen p m", nowHour = 8, 19, 15, expectDay = 6)

    @Test
    fun `twelve a m is midnight`() =
        assertFires("set an alarm for twelve a m", nowHour = 22, 0, 0, expectDay = 7)

    @Test
    fun `twelve p m is noon`() =
        assertFires("set an alarm for twelve p m", nowHour = 8, 12, 0, expectDay = 6)

    /**
     * The case from the brief: it is 1am and the alarm is "for the morning", so it must
     * ring in a few hours, not in a day and a few hours.
     */
    @Test
    fun `at one in the morning a bare seven means this same morning`() =
        assertFires("set an alarm for seven", nowHour = 1, 7, 0, expectDay = 6)

    @Test
    fun `at one am an explicit seven a m is also this same morning`() =
        assertFires("set an alarm for seven a m", nowHour = 1, 7, 0, expectDay = 6)

    @Test
    fun `at eight am a bare seven means this evening`() =
        assertFires("set an alarm for seven", nowHour = 8, 19, 0, expectDay = 6)

    @Test
    fun `at eleven pm a bare seven means tomorrow morning`() =
        assertFires("set an alarm for seven", nowHour = 23, 7, 0, expectDay = 7)

    @Test
    fun `cancellations are understood`() {
        for (phrase in listOf(
            "cancel the alarm",
            "turn off the alarm",
            "delete my alarm",
            "remove the alarm",
        )) {
            assertEquals(phrase, Command.CancelAlarm, CommandParser.parse(phrase))
        }
    }

    @Test
    fun `noise is refused rather than guessed`() {
        for (phrase in listOf(
            "",
            "[unk]",
            "the weather looks nice today",
            "computer",
        )) {
            assertEquals(phrase, Command.Unrecognised, CommandParser.parse(phrase))
        }
    }

    /**
     * The wake word is "alarm", and it is deliberately also the cue CommandParser looks
     * for, so the whole request is one phrase. VoiceService replays the chunk that woke
     * it into the command recogniser, which is what puts "alarm" in front of the parser.
     */
    @Test
    fun `wake word doubles as the cue in a single phrase`() {
        assertFires("alarm for seven a m", nowHour = 22, 7, 0, expectDay = 7)
        assertFires("alarm for six thirty in the morning", nowHour = 22, 6, 30, expectDay = 7)
        assertFires("alarm seven thirty", nowHour = 22, 7, 30, expectDay = 7)
        assertFires("alarm for half past six", nowHour = 22, 6, 30, expectDay = 7)
        assertFires("alarm for nineteen thirty", nowHour = 8, 19, 30, expectDay = 6)
    }

    /** The longer phrasing still has to work — "alarm" is mid-sentence rather than leading. */
    @Test
    fun `the longer phrasing still works with the new wake word`() {
        assertFires("set an alarm for seven a m", nowHour = 22, 7, 0, expectDay = 7)
        assertFires("wake me up at seven thirty", nowHour = 22, 7, 30, expectDay = 7)
    }

    /** The wake word alone is not a request; it must not schedule anything. */
    @Test
    fun `the wake word on its own sets nothing`() {
        for (phrase in listOf("alarm", "alarm for", "the alarm")) {
            assertEquals(phrase, Command.Unrecognised, CommandParser.parse(phrase))
        }
    }

    /**
     * A wrong alarm is worse than no alarm, so an hour that cannot exist is refused
     * rather than clamped. An out-of-range *minute* is unreachable by construction:
     * the grammar's tens stop at "fifty", so no utterance can name a minute above 59.
     */
    @Test
    fun `impossible hours are refused`() {
        for (phrase in listOf(
            "set an alarm for twenty five o'clock",
            "set an alarm for thirty o'clock",
        )) {
            assertEquals(phrase, Command.Unrecognised, CommandParser.parse(phrase))
        }
    }

    /**
     * Vosk staples noise onto the edges of a real utterance, so a trailing token that
     * is not part of the time is dropped rather than sinking the whole command. The
     * time already heard wins.
     */
    @Test
    fun `trailing noise does not sink a good time`() {
        assertEquals(
            Command.SetAlarm(6, 30, false),
            CommandParser.parse("set an alarm for six thirty please"),
        )
        assertEquals(
            Command.SetAlarm(7, 0, false),
            CommandParser.parse("set an alarm for seven [unk]"),
        )
    }
}
