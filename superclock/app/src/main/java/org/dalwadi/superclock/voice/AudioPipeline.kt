package org.dalwadi.superclock.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The app's one and only microphone.
 *
 * A single [AudioRecord] stays open for as long as the voice service lives. Stopping
 * it to switch between wake-word and command recognition would cost a couple of
 * hundred milliseconds of route teardown and setup, which is exactly the window the
 * first word of a command lands in.
 *
 * @param onChunk called on the capture thread with `length` valid samples of the
 *   reusable buffer. Must not retain the array.
 * @param onFault called on the capture thread when capture cannot continue.
 */
internal class AudioPipeline(
    private val onChunk: (buffer: ShortArray, length: Int) -> Unit,
    private val onFault: (reason: String) -> Unit,
) {

    /** Smoothed input loudness, 0f..1f. Safe to read from any thread. */
    @Volatile
    var level: Float = 0f
        private set

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // Pre-roll ring of the chunks already handed to [onChunk]. Preallocated so the
    // steady state never allocates, and read only from the capture thread.
    private val preRoll = Array(PREROLL_CHUNKS) { ShortArray(CHUNK_SAMPLES) }
    private val preRollLengths = IntArray(PREROLL_CHUNKS)
    private var preRollNext = 0
    private var preRollHeld = 0

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBytes <= 0) {
            onFault("Microphone unavailable at ${SAMPLE_RATE}Hz")
            return false
        }
        val bufferBytes = max(minBytes * 2, CHUNK_SAMPLES * BYTES_PER_SAMPLE * 4)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferBytes,
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord rejected the requested format", e)
            onFault("Microphone unavailable")
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onFault("Microphone could not be opened")
            return false
        }

        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed", e)
            rec.release()
            onFault("Microphone is in use")
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            onFault("Microphone is in use")
            return false
        }

        record = rec
        running = true
        level = 0f
        preRollNext = 0
        preRollHeld = 0
        thread = Thread({ capture(rec) }, "superclock-mic").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        return true
    }

    /**
     * Tears the microphone down. Returns true when the capture thread is confirmed
     * gone — the caller may only then free anything that thread touches, the
     * [VoskEngine] included.
     */
    fun stop(): Boolean {
        if (!running && record == null && thread == null) return true
        running = false

        val rec = record
        record = null
        // stop() unblocks the reader's in-flight read(); the join then guarantees the
        // thread is out of the object before it is released.
        try {
            rec?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stop() on an already-stopped record", e)
        }

        val t = thread
        thread = null
        val quiet = t == null || joinCapture(t)
        if (quiet) {
            rec?.release()
        } else {
            // capture() holds its own strong reference to this AudioRecord and may still
            // be blocked inside read() on a wedged driver. Releasing it under a live
            // reader is a native use-after-free, so leak this one object instead.
            Log.e(TAG, "Capture thread would not exit; leaking the AudioRecord")
        }
        level = 0f
        return quiet
    }

    private fun joinCapture(t: Thread): Boolean {
        try {
            t.join(JOIN_TIMEOUT_MS)
            if (!t.isAlive) return true
            t.interrupt()
            t.join(JOIN_GRACE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !t.isAlive
    }

    /**
     * Replays the buffered audio that preceded the chunk currently being delivered,
     * oldest first. Capture-thread only, and valid only from inside [onChunk].
     */
    fun replayPreRoll(sink: (buffer: ShortArray, length: Int) -> Unit) {
        val oldest = (preRollNext - preRollHeld + PREROLL_CHUNKS) % PREROLL_CHUNKS
        for (i in 0 until preRollHeld) {
            val slot = (oldest + i) % PREROLL_CHUNKS
            sink(preRoll[slot], preRollLengths[slot])
        }
    }

    private fun capture(rec: AudioRecord) {
        val buffer = ShortArray(CHUNK_SAMPLES)
        var smoothed = 0f
        while (running) {
            val n = try {
                rec.read(buffer, 0, buffer.size)
            } catch (e: IllegalStateException) {
                faulted("Microphone stopped unexpectedly", e)
                return
            }
            // A deliberate stop() may have landed while read() was in flight; anything
            // past this point would be touching state the stopper is already freeing.
            if (!running) return
            if (n == 0) continue
            if (n < 0) {
                Log.e(TAG, "AudioRecord.read returned $n")
                faulted("Microphone stopped unexpectedly", null)
                return
            }
            val target = loudness(buffer, n)
            val alpha = if (target > smoothed) ATTACK else RELEASE
            smoothed += alpha * (target - smoothed)
            level = smoothed
            onChunk(buffer, n)
            remember(buffer, n)
        }
    }

    /** Files the chunk away *after* delivery, so the ring holds only what came before. */
    private fun remember(buffer: ShortArray, length: Int) {
        val slot = preRollNext
        System.arraycopy(buffer, 0, preRoll[slot], 0, length)
        preRollLengths[slot] = length
        preRollNext = (slot + 1) % PREROLL_CHUNKS
        if (preRollHeld < PREROLL_CHUNKS) preRollHeld++
    }

    private fun faulted(reason: String, cause: Throwable?) {
        // A fault raised by our own stop() is not a fault.
        if (!running) return
        running = false
        if (cause != null) Log.e(TAG, reason, cause) else Log.e(TAG, reason)
        onFault(reason)
    }

    /** RMS mapped onto a decibel curve, because linear amplitude reads as nearly flat. */
    private fun loudness(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val s = buffer[i] / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / length)
        val db = 20.0 * log10(max(rms, MIN_RMS))
        return (((db - FLOOR_DB) / -FLOOR_DB).toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "AudioPipeline"

        /** Vosk's acoustic model is trained at 16 kHz; anything else is unusable. */
        const val SAMPLE_RATE = 16_000

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        /** 150 ms per read: short enough for responsive partials, long enough to be cheap. */
        private const val CHUNK_SAMPLES = 2400

        /** Four chunks ≈ 600 ms, or 18.75 KB of ring buffer. */
        private const val PREROLL_CHUNKS = 4

        private const val JOIN_TIMEOUT_MS = 1000L
        private const val JOIN_GRACE_MS = 250L

        private const val ATTACK = 0.55f
        private const val RELEASE = 0.12f
        private const val FLOOR_DB = -60.0
        private const val MIN_RMS = 1.0e-6
    }
}
