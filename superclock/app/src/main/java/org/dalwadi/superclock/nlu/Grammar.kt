package org.dalwadi.superclock.nlu

/**
 * Grammar-constrained vocabularies for Vosk.
 *
 * The wake word is "alarm", which is deliberately also a [CommandParser] cue: the whole
 * utterance is one phrase ("alarm for seven a m") rather than a wake word followed by a
 * separate command. [VoiceService] replays the wake chunk into the command recogniser so
 * the word counts twice — once to wake, once as the cue.
 *
 * Vosk takes a JSON array of phrases and compiles a word-level FSA from them, so a
 * constrained recogniser can only ever emit these words. `"[unk]"` gives it somewhere
 * to put out-of-grammar audio; without that escape hatch every cough and passing car
 * is forced onto a real word and the false-accept rate is unusable.
 *
 * Every word below was checked against the symbol table compiled into the shipped
 * `vosk-model-small-en-us-0.15` graph — a word the model does not know makes Vosk
 * reject the whole grammar and fall back to the full language model. Notably the
 * model has no "a.m."/"p.m." entries and spells the meridiem as the separate letters
 * "a" "m" and "p" "m", so both those letters and the joined forms are listed and
 * [CommandParser] stitches the pairs back together.
 *
 * Everything here must be understood by [CommandParser], either as meaning or as
 * filler it can safely ignore.
 */
object Grammar {

    const val WAKE_WORD = "alarm"

    private const val UNK = "[unk]"

    private val COMMAND_WORDS = listOf(
        // framing
        "set", "an", "a", "the", "my", "please",
        "alarm", "alarms", "for", "at", "wake", "me", "up", "in",
        "o'clock",
        // cardinals
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty",
        "hundred", "oh",
        // relative minutes
        "half", "quarter", "past", "to",
        // meridiem, both as whole words and as the model's spelled-out letters
        "am", "pm", "m", "p",
        "morning", "afternoon", "evening", "night", "tonight",
        // cancelling
        "cancel", "turn", "off", "delete", "remove", "stop", "no", "never", "mind",
    )

    /** JSON array of every phrase the wake-word recogniser may emit. */
    fun wakeWordJson(): String = json(listOf(WAKE_WORD, UNK))

    /** JSON array of the constrained command vocabulary. */
    fun commandJson(): String = json(COMMAND_WORDS + UNK)

    private fun json(phrases: List<String>): String =
        phrases.joinToString(separator = ",", prefix = "[", postfix = "]") { phrase ->
            "\"" + phrase.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}
