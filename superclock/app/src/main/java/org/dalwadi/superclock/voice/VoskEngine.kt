package org.dalwadi.superclock.voice

import android.util.Log
import org.dalwadi.superclock.nlu.Grammar
import org.json.JSONException
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One loaded acoustic model driving two grammar-constrained recognisers.
 *
 * The model is the expensive part (tens of megabytes, hundreds of milliseconds), so
 * both recognisers are built from it up front. Switching between scanning for the
 * wake word and transcribing a command is then just a choice of which one gets fed.
 *
 * Not thread-safe: every call must come from the single capture thread, except
 * [close], which must only run once that thread has been joined.
 */
internal class VoskEngine private constructor(
    private val model: Model,
    private val wake: Recognizer,
    private val command: Recognizer,
) : AutoCloseable {

    /** A recogniser's view of the audio so far. */
    data class Heard(val isFinal: Boolean, val text: String)

    fun feedWake(buffer: ShortArray, length: Int): Heard = feed(wake, buffer, length)

    fun feedCommand(buffer: ShortArray, length: Int): Heard = feed(command, buffer, length)

    /** Flushes the command recogniser and clears it, returning whatever it had settled on. */
    fun finishCommand(): String {
        val text = extract(command.getFinalResult(), KEY_TEXT)
        command.reset()
        return text
    }

    fun resetCommand() = command.reset()

    fun resetWake() = wake.reset()

    override fun close() {
        closeQuietly("command recognizer") { command.close() }
        closeQuietly("wake recognizer") { wake.close() }
        closeQuietly("model") { model.close() }
    }

    private fun feed(recognizer: Recognizer, buffer: ShortArray, length: Int): Heard =
        if (recognizer.acceptWaveForm(buffer, length)) {
            Heard(true, extract(recognizer.getResult(), KEY_TEXT))
        } else {
            Heard(false, extract(recognizer.getPartialResult(), KEY_PARTIAL))
        }

    private fun extract(json: String?, key: String): String {
        if (json.isNullOrEmpty()) return ""
        return try {
            JSONObject(json).optString(key, "").trim()
        } catch (e: JSONException) {
            Log.w(TAG, "Unparseable recogniser output", e)
            ""
        }
    }

    private fun closeQuietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close $what", t)
        }
    }

    companion object {
        private const val TAG = "VoskEngine"
        private const val KEY_TEXT = "text"
        private const val KEY_PARTIAL = "partial"

        private val logConfigured = AtomicBoolean(false)

        /**
         * Loads the model at [modelDir] and both recognisers. Blocking; call off the
         * main thread. Throws whatever Vosk throws when the model will not load.
         */
        fun load(modelDir: File): VoskEngine {
            if (logConfigured.compareAndSet(false, true)) {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
            }
            val model = Model(modelDir.absolutePath)
            var wake: Recognizer? = null
            var command: Recognizer? = null
            try {
                val rate = AudioPipeline.SAMPLE_RATE.toFloat()
                wake = constrained(model, rate, Grammar.wakeWordJson(), "wake")
                command = constrained(model, rate, Grammar.commandJson(), "command")
                return VoskEngine(model, wake, command)
            } catch (t: Throwable) {
                command?.close()
                wake?.close()
                model.close()
                throw t
            }
        }

        /**
         * A grammar Vosk rejects would otherwise take the whole engine down and leave
         * the clock with no recogniser at all. An unconstrained decoder is measurably
         * worse on these phrases, but [org.dalwadi.superclock.nlu.CommandParser] mines
         * keywords out of a free word stream, so degrading beats going deaf.
         */
        private fun constrained(
            model: Model,
            rate: Float,
            grammar: String,
            role: String,
        ): Recognizer = try {
            Recognizer(model, rate, grammar)
        } catch (t: Throwable) {
            Log.w(TAG, "Grammar rejected for the $role recogniser; running unconstrained", t)
            Recognizer(model, rate)
        }
    }
}
