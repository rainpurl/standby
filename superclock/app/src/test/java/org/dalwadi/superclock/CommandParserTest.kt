package org.dalwadi.superclock

import org.dalwadi.superclock.nlu.Command
import org.dalwadi.superclock.nlu.CommandParser
import org.dalwadi.superclock.nlu.Grammar
import org.dalwadi.superclock.nlu.NumberWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun assertAlarm(hour: Int, minute: Int, explicit: Boolean, transcript: String) {
        assertEquals(transcript, Command.SetAlarm(hour, minute, explicit), CommandParser.parse(transcript))
    }

    private fun assertCancel(transcript: String) {
        assertEquals(transcript, Command.CancelAlarm, CommandParser.parse(transcript))
    }

    private fun assertUnrecognised(transcript: String) {
        assertEquals(transcript, Command.Unrecognised, CommandParser.parse(transcript))
    }

    @Test
    fun spelledOutMeridiemIsExplicit() {
        assertAlarm(7, 0, true, "set an alarm for seven a m")
        assertAlarm(7, 0, true, "set an alarm for seven am")
        assertAlarm(7, 0, true, "set an alarm for seven a.m.")
        assertAlarm(19, 0, true, "set an alarm for seven p m")
        assertAlarm(19, 0, true, "set an alarm for seven pm")
    }

    @Test
    fun bareHourIsAmbiguous() {
        assertAlarm(7, 0, false, "set an alarm for seven")
        assertAlarm(7, 0, false, "set an alarm for seven o'clock")
        assertAlarm(7, 0, false, "set an alarm for seven oclock")
        assertAlarm(7, 0, false, "set an alarm for seven o clock")
        assertAlarm(8, 0, false, "wake me up at eight")
    }

    @Test
    fun hourAndMinute() {
        assertAlarm(6, 30, false, "set an alarm for six thirty")
        assertAlarm(6, 30, true, "set alarm for six thirty in the morning")
        assertAlarm(7, 30, false, "wake me up at seven thirty")
        assertAlarm(19, 15, true, "set an alarm for seven fifteen p m")
        assertAlarm(6, 45, false, "set an alarm for six forty five")
        assertAlarm(23, 15, true, "set an alarm for twenty three fifteen")
    }

    @Test
    fun spokenLeadingZeroIsMinutes() {
        assertAlarm(7, 5, false, "set an alarm for seven oh five")
        assertAlarm(7, 5, false, "set an alarm for seven zero five")
    }

    @Test
    fun twentyFourHourReadingIsAlreadyExplicit() {
        assertAlarm(19, 30, true, "set an alarm for nineteen thirty")
        assertAlarm(19, 0, true, "set an alarm for nineteen hundred")
        assertAlarm(21, 45, true, "set an alarm for twenty one forty five")
    }

    @Test
    fun relativeMinutes() {
        assertAlarm(6, 30, false, "set an alarm for half past six")
        assertAlarm(7, 15, false, "set an alarm for quarter past seven")
        assertAlarm(7, 15, false, "set an alarm for a quarter past seven")
        assertAlarm(6, 45, false, "set an alarm for quarter to seven")
        assertAlarm(6, 40, false, "set an alarm for twenty to seven")
        assertAlarm(12, 45, false, "set an alarm for quarter to one")
        assertAlarm(6, 30, true, "set an alarm for half past six in the morning")
    }

    @Test
    fun noonAndMidnightMapCorrectly() {
        assertAlarm(0, 0, true, "set an alarm for twelve a m")
        assertAlarm(0, 30, true, "set an alarm for twelve thirty a m")
        assertAlarm(12, 0, true, "set an alarm for twelve p m")
        assertAlarm(12, 30, true, "set an alarm for twelve thirty p m")
        assertAlarm(12, 0, false, "set an alarm for twelve")
    }

    @Test
    fun timeOfDayWordsImplyMeridiem() {
        assertAlarm(19, 0, true, "set an alarm for seven tonight")
        assertAlarm(19, 0, true, "set an alarm for seven in the evening")
        assertAlarm(19, 0, true, "set an alarm for seven at night")
        assertAlarm(14, 0, true, "set an alarm for two in the afternoon")
        assertAlarm(6, 0, true, "set an alarm for six in the morning")
    }

    @Test
    fun toleratesMissingArticlesAndTrailingNoise() {
        assertAlarm(7, 0, true, "[unk] set an alarm for seven a m [unk]")
        assertAlarm(7, 0, true, "Set An Alarm For Seven A M")
        assertAlarm(19, 30, true, "set alarm seven thirty p m please")
        assertAlarm(6, 30, false, "set a alarm for six thirty")
        assertAlarm(7, 0, false, "  set   an  alarm   for   seven  ")
    }

    @Test
    fun cancelPhrases() {
        assertCancel("cancel the alarm")
        assertCancel("turn off the alarm")
        assertCancel("delete my alarm")
        assertCancel("remove the alarm")
        assertCancel("stop the alarm")
        assertCancel("cancel")
        assertCancel("never mind")
        assertCancel("no")
    }

    @Test
    fun garbageIsUnrecognised() {
        assertUnrecognised("")
        assertUnrecognised("   ")
        assertUnrecognised("[unk]")
        assertUnrecognised("[unk] [unk]")
        assertUnrecognised("the quick brown fox")
        assertUnrecognised("seven")
        assertUnrecognised("set an alarm")
    }

    @Test
    fun impossibleTimesAreRejected() {
        assertUnrecognised("set an alarm for twenty five")
        assertUnrecognised("set an alarm for thirty")
        assertUnrecognised("set an alarm for fifty")
        assertUnrecognised("set an alarm for six thirty hundred")
    }

    @Test
    fun numberWordsGroupTheWaySpeakersDo() {
        assertEquals(listOf(7), NumberWords.parseSequence(listOf("seven")))
        assertEquals(listOf(12), NumberWords.parseSequence(listOf("twelve")))
        assertEquals(listOf(30), NumberWords.parseSequence(listOf("thirty")))
        assertEquals(listOf(45), NumberWords.parseSequence(listOf("forty", "five")))
        assertEquals(listOf(21), NumberWords.parseSequence(listOf("twenty", "one")))
        assertEquals(listOf(15), NumberWords.parseSequence(listOf("fifteen")))
        assertEquals(listOf(19), NumberWords.parseSequence(listOf("nineteen")))
        assertEquals(listOf(5), NumberWords.parseSequence(listOf("oh", "five")))
        assertEquals(listOf(5), NumberWords.parseSequence(listOf("zero", "five")))
        assertEquals(listOf(7, 5), NumberWords.parseSequence(listOf("seven", "oh", "five")))
        assertEquals(listOf(6, 30), NumberWords.parseSequence(listOf("six", "thirty")))
        assertEquals(listOf(1900), NumberWords.parseSequence(listOf("nineteen", "hundred")))
        assertEquals(listOf(700), NumberWords.parseSequence(listOf("seven", "hundred")))
        // A non-cardinal between two numbers keeps them apart.
        assertEquals(listOf(6, 30), NumberWords.parseSequence(listOf("six", "at", "thirty")))
        assertEquals(emptyList<Int>(), NumberWords.parseSequence(listOf("alarm", "for")))
    }

    @Test
    fun grammarIsJsonAndCoversTheParser() {
        val wake = Grammar.wakeWordJson()
        assertEquals("[\"alarm\",\"[unk]\"]", wake)

        // The wake word has to be a command cue too, because it is spoken as part of the
        // request rather than before it.
        assertTrue(Grammar.commandJson().contains("\"${Grammar.WAKE_WORD}\""))

        val command = Grammar.commandJson()
        assertTrue(command.startsWith("[") && command.endsWith("]"))
        assertTrue(command.contains("\"[unk]\""))
        for (word in listOf(
            "set", "an", "a", "alarm", "for", "at", "wake", "me", "up", "o'clock",
            "zero", "one", "twelve", "twenty", "thirty", "forty", "fifty", "hundred", "oh",
            "half", "past", "quarter", "to", "am", "pm", "m", "p",
            "morning", "evening", "afternoon", "night", "tonight", "in", "the",
            "cancel", "turn", "off", "delete", "remove", "stop", "no", "never", "mind",
        )) {
            assertTrue(word, command.contains("\"$word\""))
        }
    }
}
