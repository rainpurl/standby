package org.dalwadi.superclock.nlu

/**
 * Turns a Vosk transcript into a [Command].
 *
 * Vosk emits a bare lowercase word stream with no punctuation and, under a
 * constrained grammar, a fair amount of noise stitched onto the edges of the real
 * utterance. So nothing here depends on a fixed sentence shape: it looks for a cue
 * that this is about an alarm, then mines the token stream for a time. Anything it
 * cannot read confidently comes back as [Command.Unrecognised] rather than a guess —
 * a wrong alarm is worse than no alarm.
 */
object CommandParser {

    /** At least one of these must appear, or a stray number is not an alarm request. */
    private val SET_CUES = setOf("set", "alarm", "alarms", "wake")

    private val CANCEL_CUES = setOf("cancel", "delete", "remove", "stop", "off")

    private val AM_WORDS = setOf("am", "morning")
    private val PM_WORDS = setOf("pm", "afternoon", "evening", "night", "tonight")

    private val FILLER = setOf(
        "a", "an", "the", "my", "please", "it", "is", "that", "this", "and", "o'clock",
    )

    fun parse(transcript: String): Command {
        val tokens = tokenize(transcript)
        if (tokens.isEmpty()) return Command.Unrecognised

        parseSetAlarm(tokens)?.let { return it }
        if (isCancel(tokens)) return Command.CancelAlarm
        return Command.Unrecognised
    }

    /**
     * Normalises to the token stream the rest of this file expects: punctuation gone,
     * `[unk]` and any other bracketed marker dropped, the model's spelled-out "a m" /
     * "p m" joined into single meridiem tokens, and "o clock" folded onto "o'clock".
     */
    private fun tokenize(transcript: String): List<String> {
        val raw = transcript
            .lowercase()
            .replace('\u2019', '\'')
            .filterNot { it == '.' || it == ',' || it == '!' || it == '?' || it == ';' || it == ':' }
            .split(' ', '\t', '\n', '\r')
            .filter { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("<") }

        val merged = ArrayList<String>(raw.size)
        var i = 0
        while (i < raw.size) {
            val word = raw[i]
            val next = raw.getOrNull(i + 1)
            when {
                word == "a" && next == "m" -> { merged += "am"; i += 2 }
                word == "p" && next == "m" -> { merged += "pm"; i += 2 }
                word == "o" && next == "clock" -> { merged += "o'clock"; i += 2 }
                word == "oclock" -> { merged += "o'clock"; i++ }
                else -> { merged += word; i++ }
            }
        }
        return merged
    }

    private fun parseSetAlarm(tokens: List<String>): Command.SetAlarm? {
        if (tokens.none { it in SET_CUES }) return null
        val spoken = readRelativeTime(tokens) ?: readPlainTime(tokens) ?: return null
        return applyMeridiem(spoken, tokens)
    }

    private fun isCancel(tokens: List<String>): Boolean {
        if (tokens.any { it in CANCEL_CUES }) return true
        for (i in 0 until tokens.size - 1) {
            if (tokens[i] == "never" && tokens[i + 1] == "mind") return true
        }
        if (tokens.contains("nevermind")) return true
        // A bare "no" is a refusal; "no" buried in a longer utterance is not.
        return tokens.filter { it !in FILLER } == listOf("no")
    }

    private data class Spoken(val hour: Int, val minute: Int)

    /** "half past six", "quarter to seven", "twenty past nine". */
    private fun readRelativeTime(tokens: List<String>): Spoken? {
        for (i in tokens.indices) {
            val pivot = tokens[i]
            if (pivot != "past" && pivot != "to") continue

            val offset = readMinuteOffset(tokens, i) ?: continue
            val spokenHour = NumberWords.parseSequence(tokens.subList(i + 1, tokens.size))
                .firstOrNull() ?: continue
            if (spokenHour !in 0..23) continue

            if (pivot == "past") return Spoken(spokenHour, offset)

            val hour = when {
                spokenHour == 0 -> 23
                spokenHour == 1 -> 12
                else -> spokenHour - 1
            }
            return Spoken(hour, 60 - offset)
        }
        return null
    }

    /** The minutes named immediately before a "past"/"to" at [pivot]. */
    private fun readMinuteOffset(tokens: List<String>, pivot: Int): Int? {
        val previous = tokens.getOrNull(pivot - 1) ?: return null
        when (previous) {
            "half" -> return 30
            "quarter" -> return 15
        }
        var start = pivot
        while (start > 0 && NumberWords.isNumberWord(tokens[start - 1])) start--
        if (start == pivot) return null
        val minutes = NumberWords.parseSequence(tokens.subList(start, pivot)).lastOrNull() ?: return null
        return if (minutes in 1..59) minutes else null
    }

    /** "seven", "six thirty", "seven oh five", "nineteen hundred". */
    private fun readPlainTime(tokens: List<String>): Spoken? {
        val numbers = NumberWords.parseSequence(tokens)
        val first = numbers.firstOrNull() ?: return null

        // "nineteen hundred" arrives as a single 1900.
        if (first >= 100) {
            val spoken = Spoken(first / 100, first % 100)
            return if (spoken.hour in 0..23 && spoken.minute in 0..59) spoken else null
        }
        if (first !in 0..23) return null

        val second = numbers.getOrNull(1)
        if (second == null) return Spoken(first, 0)
        return if (second in 0..59) Spoken(first, second) else null
    }

    /**
     * Folds any spoken meridiem into a 24-hour value. Without one, an hour the speaker
     * could only have meant as 24-hour (0, or past noon) is still unambiguous; a 1..12
     * face reading is left for [AlarmTimeResolver] to place.
     */
    private fun applyMeridiem(spoken: Spoken, tokens: List<String>): Command.SetAlarm? {
        val am = tokens.indexOfLast { it in AM_WORDS }
        val pm = tokens.indexOfLast { it in PM_WORDS }

        val hour = when {
            // If both are somehow present the later one is the speaker's correction.
            am >= 0 && am > pm -> if (spoken.hour in 1..12) spoken.hour % 12 else spoken.hour
            pm >= 0 -> if (spoken.hour in 1..12) spoken.hour % 12 + 12 else spoken.hour
            else -> spoken.hour
        }
        if (hour !in 0..23 || spoken.minute !in 0..59) return null

        val explicit = am >= 0 || pm >= 0 || spoken.hour == 0 || spoken.hour > 12
        return Command.SetAlarm(hour, spoken.minute, explicit)
    }
}
