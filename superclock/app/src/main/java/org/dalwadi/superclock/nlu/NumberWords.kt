package org.dalwadi.superclock.nlu

/**
 * Spoken cardinals to integers, over the small range a clock needs (0..59, plus the
 * "nineteen hundred" military form).
 *
 * The interesting part is grouping. A speaker saying a time runs two numbers together
 * with no pause — "six thirty" is 6 and 30, but "forty five" is one 45 and "oh five"
 * is one 5. [parseSequence] applies the rules a listener applies: a tens word can
 * absorb a following unit, a spoken leading zero can absorb a following unit, and
 * anything else starts a new number.
 */
object NumberWords {

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )

    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19,
    )

    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)

    /** A spoken leading zero, as in "seven oh five". */
    private const val OH = "oh"

    private const val HUNDRED = "hundred"

    fun isNumberWord(word: String): Boolean =
        word == OH || word == HUNDRED ||
            UNITS.containsKey(word) || TEENS.containsKey(word) || TENS.containsKey(word)

    /** The value of a single cardinal word, or null if [word] is not one. */
    fun valueOf(word: String): Int? =
        if (word == OH) 0 else UNITS[word] ?: TEENS[word] ?: TENS[word]

    /**
     * Reads [words] left to right and returns the numbers a speaker meant. Any word
     * that is not a cardinal closes the current number, so filler between two numbers
     * keeps them apart.
     *
     * "six thirty" -> [6, 30]; "forty five" -> [45]; "seven oh five" -> [7, 5];
     * "nineteen hundred" -> [1900].
     */
    fun parseSequence(words: List<String>): List<Int> {
        val out = ArrayList<Int>(4)
        // -1 means no number is open; canAbsorb means the open number is a tens word
        // or a leading zero and can still swallow a following unit.
        var current = -1
        var canAbsorb = false

        for (word in words) {
            val unit = UNITS[word]
            val teen = TEENS[word]
            val ten = TENS[word]
            when {
                word == HUNDRED -> {
                    if (current in 1..99) out += current * 100 else if (current >= 0) out += current
                    current = -1
                    canAbsorb = false
                }

                word == OH || unit == 0 -> {
                    if (current >= 0) out += current
                    current = 0
                    canAbsorb = true
                }

                unit != null -> {
                    if (canAbsorb && current >= 0) {
                        current += unit
                    } else {
                        if (current >= 0) out += current
                        current = unit
                    }
                    canAbsorb = false
                }

                teen != null -> {
                    if (current >= 0) out += current
                    current = teen
                    canAbsorb = false
                }

                ten != null -> {
                    if (current >= 0) out += current
                    current = ten
                    canAbsorb = true
                }

                else -> {
                    if (current >= 0) out += current
                    current = -1
                    canAbsorb = false
                }
            }
        }
        if (current >= 0) out += current
        return out
    }
}
