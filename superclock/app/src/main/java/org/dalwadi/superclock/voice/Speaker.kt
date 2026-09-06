package org.dalwadi.superclock.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Speaks the alarm confirmation out loud.
 *
 * The engine binds asynchronously, so a phrase asked for before it is ready is held
 * — exactly one, replacing any earlier pending one — and spoken the moment init
 * succeeds. If init or the voice data fails, the held phrase is dropped and its
 * completion callback still runs, so nothing upstream waits on a voice that will
 * never arrive.
 *
 * TextToSpeech synthesises inside the *system's* engine process, not ours, so this
 * gives Superclock no network access and does not need the INTERNET permission the
 * app deliberately omits. It does mean the installed engine must have offline voice
 * data for [Locale.US]; without it there is simply no confirmation.
 */
class Speaker(context: Context) {

    private val closed = AtomicBoolean(false)
    private val ids = AtomicLong(0L)

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var ready = false

    private val lock = Any()

    /** The one phrase held while the engine is still binding. Guarded by [lock]. */
    private var pending: Phrase? = null

    /** Utterances handed to the engine, awaiting a terminal callback. */
    private val inFlight = ConcurrentHashMap<String, Phrase>()

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = settle(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = settle(utteranceId)

        override fun onError(utteranceId: String?) = settle(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "Synthesis failed for $utteranceId (code $errorCode)")
            settle(utteranceId)
        }
    }

    init {
        // The OnInitListener is posted back on this thread's looper after the engine
        // service binds, so it never runs before this assignment completes.
        engine = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    /**
     * Speaks [text], replacing anything currently being spoken rather than queueing
     * behind it. [onFinished] runs exactly once — when the utterance finishes, is cut
     * short, fails, or is refused — on whatever thread the engine calls back on.
     */
    fun speak(text: String, onFinished: () -> Unit) {
        val phrase = Phrase("utt-${ids.incrementAndGet()}", text, onFinished)
        val tts = engine
        if (closed.get()) {
            phrase.settle()
            return
        }
        if (!ready || tts == null) {
            val displaced = synchronized(lock) { pending.also { pending = phrase } }
            displaced?.settle()
            // A shutdown that raced past the check above has already drained pending, so
            // drain again rather than strand a caller waiting on a phrase nobody owns.
            if (closed.get()) takePending()?.settle()
            return
        }
        enqueue(tts, phrase)
    }

    /** Stops any speech and releases the engine. Safe to call more than once. */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        val tts = engine
        engine = null
        ready = false

        takePending()?.settle()
        for (id in inFlight.keys.toList()) inFlight.remove(id)?.settle()

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech shutdown failed", t)
        }
    }

    private fun onInit(status: Int) {
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            Log.e(TAG, "TextToSpeech unavailable (status $status); confirmations stay silent")
            abandon()
            return
        }
        if (closed.get()) {
            // shutdown() ran while the engine was still binding.
            try {
                tts.shutdown()
            } catch (t: Throwable) {
                Log.w(TAG, "TextToSpeech shutdown failed", t)
            }
            abandon()
            return
        }

        val language = try {
            tts.setLanguage(Locale.US)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "setLanguage rejected en-US", e)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (language == TextToSpeech.LANG_MISSING_DATA ||
            language == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.e(TAG, "No offline en-US voice data; confirmations stay silent")
            abandon()
            return
        }

        // USAGE_ASSISTANT keeps the confirmation on the assistant volume. It must never
        // ride the alarm stream: this is a two-second acknowledgement, not the alarm, and
        // nobody wants it at alarm loudness at one in the morning.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(progress)
        ready = true

        takePending()?.let { enqueue(tts, it) }
    }

    private fun enqueue(tts: TextToSpeech, phrase: Phrase) {
        inFlight[phrase.id] = phrase
        val result = try {
            tts.speak(phrase.text, TextToSpeech.QUEUE_FLUSH, null, phrase.id)
        } catch (t: Throwable) {
            Log.e(TAG, "speak() threw", t)
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Engine refused \"${phrase.text}\"")
            inFlight.remove(phrase.id)?.settle()
        } else if (closed.get()) {
            inFlight.remove(phrase.id)?.settle()
        }
    }

    private fun settle(utteranceId: String?) {
        inFlight.remove(utteranceId ?: return)?.settle()
    }

    private fun takePending(): Phrase? = synchronized(lock) { pending.also { pending = null } }

    /** Gives up on speech entirely; anything waiting is released rather than stranded. */
    private fun abandon() {
        ready = false
        takePending()?.settle()
        for (id in inFlight.keys.toList()) inFlight.remove(id)?.settle()
    }

    private class Phrase(val id: String, val text: String, private val onFinished: () -> Unit) {
        private val done = AtomicBoolean(false)

        fun settle() {
            if (done.compareAndSet(false, true)) onFinished()
        }
    }

    companion object {
        private const val TAG = "Speaker"

        /**
         * The confirmation for an alarm that fires at [triggerAtMillis], read in [zone].
         *
         * Pure — the instant and the zone both come in, so it never touches the system
         * clock and the weekday is whatever the resolver actually settled on.
         */
        fun confirmationFor(triggerAtMillis: Long, zone: ZoneId): String {
            val at = Instant.ofEpochMilli(triggerAtMillis).atZone(zone)
            val weekday = at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            return "Alarm set for $weekday, ${spokenTime(at.hour, at.minute)}"
        }

        /**
         * A 24-hour time as a TTS engine reads it naturally: "7 AM" rather than
         * "seven zero", and "7 oh 5 AM" rather than the "7 5" a bare number gives.
         */
        fun spokenTime(hour: Int, minute: Int): String {
            val face = if (hour % 12 == 0) 12 else hour % 12
            val meridiem = if (hour < 12) "AM" else "PM"
            return when {
                minute == 0 -> "$face $meridiem"
                minute < 10 -> "$face oh $minute $meridiem"
                else -> "$face $minute $meridiem"
            }
        }
    }
}
