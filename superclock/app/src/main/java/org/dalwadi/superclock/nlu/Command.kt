package org.dalwadi.superclock.nlu

/** The complete set of things Superclock understands. */
sealed interface Command {

    /**
     * A time to wake up at, as spoken.
     *
     * @param hour   0..23. When [meridiemExplicit] is false this is the 1..12 reading
     *               as spoken ("seven" -> 7) and the resolver picks AM or PM.
     * @param minute 0..59.
     * @param meridiemExplicit true when the speaker said "a m" / "p m" / "in the morning"
     *               / "tonight" and [hour] is therefore already unambiguous 24-hour.
     */
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val meridiemExplicit: Boolean,
    ) : Command

    /** "cancel the alarm", "turn off the alarm". */
    data object CancelAlarm : Command

    /** Heard speech, understood none of it. */
    data object Unrecognised : Command
}
