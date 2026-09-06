package org.dalwadi.superclock.voice

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.dalwadi.superclock.R
import org.dalwadi.superclock.SuperclockApp
import org.dalwadi.superclock.alarm.Alarm
import org.dalwadi.superclock.alarm.AlarmScheduler
import org.dalwadi.superclock.clock.ClockActivity
import org.dalwadi.superclock.nlu.AlarmTimeResolver
import org.dalwadi.superclock.nlu.Command
import org.dalwadi.superclock.nlu.CommandParser
import org.dalwadi.superclock.nlu.Grammar
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The always-listening half of Superclock.
 *
 * Runs the wake word and command recognisers over one continuously open microphone
 * and turns whatever the user said into a scheduled alarm. Everything happens on
 * device; the app holds no network permission.
 */
class VoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var audio: AudioPipeline? = null

    @Volatile
    private var engine: VoskEngine? = null

    @Volatile
    private var speaker: Speaker? = null

    @Volatile
    private var phase = VoicePhase.IDLE

    @Volatile
    private var destroyed = false

    @Volatile
    private var lastWakeAtMs = 0L

    /** When [phase] last changed, for the stuck-phase watchdog. */
    @Volatile
    private var phaseSinceMs = 0L

    /** Serialises capture recovery so a burst of faults produces one retry loop. */
    private val recovering = AtomicBoolean(false)

    /** Index into [CAPTURE_BACKOFF_MS]; survives a successful re-open on purpose. */
    @Volatile
    private var captureAttempt = 0

    /** When audio last started flowing again, 0 while capture is down. */
    @Volatile
    private var captureFlowingSinceMs = 0L

    private val released = AtomicBoolean(false)

    // Capture-thread only.
    private var wokeAtMs = 0L
    private var lastChangeAtMs = 0L
    private var partial = ""

    override fun onCreate() {
        super.onCreate()

        if (!hasMicPermission()) {
            // Going foreground with a microphone type without the permission is itself
            // a SecurityException on recent releases, so bail before that happens.
            fail(getString(R.string.perm_mic_rationale))
            return
        }

        try {
            goForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start in the foreground", e)
            fail(getString(R.string.perm_mic_rationale))
            return
        }

        speaker = Speaker(this)
        initialise()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        phase = VoicePhase.IDLE
        VoiceState.setReady(false)
        VoiceState.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseOffMainThread()
        super.onDestroy()
    }

    /**
     * Joining the capture thread and freeing the native decoder graph together cost the
     * better part of a second. onDestroy() runs on the main thread during activity
     * teardown, so that is jank at best and an ANR at worst.
     */
    private fun releaseOffMainThread() {
        if (!released.compareAndSet(false, true)) return
        val pipeline = audio
        val loaded = engine
        val voice = speaker
        audio = null
        engine = null
        speaker = null
        if (pipeline == null && loaded == null && voice == null) return
        Thread({
            // Unbinding from the system's TTS engine is a binder round trip, so it goes
            // on this thread for the same reason everything else here does.
            voice?.shutdown()
            val quiet = pipeline?.stop() ?: true
            if (quiet) {
                loaded?.close()
            } else {
                // The mic thread may still be inside feedCommand(); closing the
                // recognisers out from under it is a native crash. Leak the model.
                Log.e(TAG, "Capture thread still alive; leaving the speech model loaded")
            }
        }, "superclock-voice-release").start()
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SuperclockApp.NOTIF_ID_VOICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(SuperclockApp.NOTIF_ID_VOICE, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, ClockActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SuperclockApp.CHANNEL_VOICE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_voice_title))
            .setContentText(getString(R.string.notif_voice_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun initialise() {
        scope.launch {
            val modelDir = try {
                ModelInstaller.ensureInstalled(this@VoiceService)
            } catch (e: IOException) {
                Log.e(TAG, "Speech model install failed", e)
                fail(FAULT_MODEL)
                return@launch
            }

            val loaded = loadEngine(modelDir)
            if (loaded == null) {
                fail(FAULT_MODEL)
                return@launch
            }

            if (destroyed) {
                loaded.close()
                return@launch
            }
            engine = loaded

            if (!openCapture()) return@launch

            VoiceState.setFault(null)
            Log.i(TAG, "Speech model loaded; listening for \"${Grammar.WAKE_WORD}\"")
            VoiceState.setReady(true)
        }
    }

    /**
     * A power cut during the very first unpack leaves a valid-looking install marker
     * over a model Vosk cannot open, and nothing else ever invalidates it — which would
     * brick voice across every future boot. So a load failure earns exactly one wipe
     * and re-unpack before we give up.
     */
    private fun loadEngine(modelDir: File): VoskEngine? {
        try {
            return VoskEngine.load(modelDir)
        } catch (t: Throwable) {
            Log.e(TAG, "Speech model load failed; reinstalling once", t)
        }
        if (destroyed) return null
        return try {
            ModelInstaller.wipe(this)
            VoskEngine.load(ModelInstaller.ensureInstalled(this))
        } catch (t: Throwable) {
            Log.e(TAG, "Speech model load failed after reinstall", t)
            null
        }
    }

    /** Terminal. Only for faults that no amount of retrying can clear. */
    private fun fail(reason: String) {
        VoiceState.setReady(false)
        VoiceState.setFault(reason)
        stopSelf()
    }

    /**
     * A capture fault is transient far more often than not: ERROR_DEAD_OBJECT after an
     * audioserver restart, a route change onto or off the charger's dock, a call or the
     * assistant stealing the mic. stopSelf() here would be permanent — it suppresses the
     * START_STICKY restart, and the only thing that ever starts this service is
     * ClockActivity.onStart(), which on a charger may not run again for a month.
     */
    private fun onAudioFault(reason: String) {
        if (destroyed) return
        Log.e(TAG, "Capture fault: $reason")
        if (recovering.compareAndSet(false, true)) {
            scope.launch { recoverCapture(reason) }
        }
    }

    private suspend fun recoverCapture(reason: String) {
        try {
            // A fault after a long healthy stretch is a fresh event, not an escalation:
            // one next month must start at 1s, not at the 30s cap it ended on.
            val up = captureFlowingSinceMs
            if (up != 0L && SystemClock.elapsedRealtime() - up >= CAPTURE_HEALTHY_MS) {
                captureAttempt = 0
            }
            VoiceState.setReady(false)
            VoiceState.setFault(reason)

            while (!destroyed) {
                if (!hasMicPermission()) {
                    // Revoked mid-flight. There is nothing left to retry.
                    fail(getString(R.string.perm_mic_rationale))
                    return
                }
                val index = captureAttempt.coerceAtMost(CAPTURE_BACKOFF_MS.lastIndex)
                captureAttempt++
                delay(CAPTURE_BACKOFF_MS[index])
                if (destroyed) return

                if (openCapture()) {
                    Log.i(TAG, "Microphone recovered after $captureAttempt attempt(s)")
                    VoiceState.setFault(null)
                    VoiceState.setReady(true)
                    return
                }
            }
        } finally {
            recovering.set(false)
        }
    }

    /**
     * Opens, or re-opens, the microphone. The loaded [VoskEngine] is deliberately kept
     * across this: the model is the expensive part and it is not what failed.
     */
    private fun openCapture(): Boolean {
        val previous = audio
        audio = null
        captureFlowingSinceMs = 0L
        previous?.stop()
        VoiceState.setLevel(0f)
        if (destroyed) return false

        val pipeline = AudioPipeline(::onAudio, ::onAudioFault)
        audio = pipeline
        if (!pipeline.start() || destroyed) {
            audio = null
            pipeline.stop()
            return false
        }
        return true
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Runs on the capture thread, once per ~150 ms of audio. */
    private fun onAudio(buffer: ShortArray, length: Int) {
        val engine = engine ?: return
        if (captureFlowingSinceMs == 0L) captureFlowingSinceMs = SystemClock.elapsedRealtime()

        when (guardedPhase(engine)) {
            VoicePhase.IDLE -> {
                val heard = engine.feedWake(buffer, length)
                if (isWake(heard.text)) beginListening(engine, buffer, length)
            }

            VoicePhase.LISTENING -> listen(engine, buffer, length)

            // Parsing and the confirmation hold own the pipeline; audio is discarded so
            // the "alarm" the speaker just said cannot immediately re-trigger a wake.
            else -> Unit
        }
    }

    /**
     * Watchdog. Every timed exit from a non-IDLE phase is somebody else's coroutine, so
     * one dropped continuation would pin the state machine and make the clock ignore
     * every wake word for the life of the process. The capture thread is the one thing
     * still running, so it enforces a ceiling.
     */
    private fun guardedPhase(engine: VoskEngine): VoicePhase {
        val current = phase
        if (current == VoicePhase.IDLE) return current
        val stuckFor = SystemClock.elapsedRealtime() - phaseSinceMs
        if (stuckFor < PHASE_CEILING_MS) return current

        Log.w(TAG, "Phase stuck at $current for ${stuckFor}ms; forcing IDLE")
        engine.resetWake()
        engine.resetCommand()
        partial = ""
        lastWakeAtMs = SystemClock.elapsedRealtime()
        enterPhase(VoicePhase.IDLE)
        VoiceState.reset()
        return VoicePhase.IDLE
    }

    private fun enterPhase(next: VoicePhase) {
        phase = next
        phaseSinceMs = SystemClock.elapsedRealtime()
        VoiceState.setPhase(next)
    }

    private fun isWake(text: String): Boolean =
        text.isNotEmpty() && text.split(' ').any { it == Grammar.WAKE_WORD }

    private fun beginListening(engine: VoskEngine, buffer: ShortArray, length: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAtMs < WAKE_DEBOUNCE_MS) {
            // Without this the rejected wake stays in the wake recogniser's partial and
            // matches again on every following chunk, firing a phantom wake the moment
            // the hold-off expires.
            engine.resetWake()
            return
        }
        lastWakeAtMs = now
        wokeAtMs = now
        lastChangeAtMs = now
        partial = ""

        engine.resetWake()
        engine.resetCommand()

        // The wake word and the first command word normally land in the same 150 ms
        // chunk, and that chunk only ever reached the wake recogniser. Replaying the audio
        // leading up to it and the chunk itself matters twice over now that the wake word
        // is "alarm": it is itself the cue CommandParser looks for, so dropping the chunk
        // that woke us would make every utterance Unrecognised.
        audio?.replayPreRoll { chunk, n -> engine.feedCommand(chunk, n) }
        engine.feedCommand(buffer, length)

        VoiceState.setHeard("")
        VoiceState.setMessage("")
        VoiceState.setLevel(0f)
        enterPhase(VoicePhase.LISTENING)
    }

    private fun listen(engine: VoskEngine, buffer: ShortArray, length: Int) {
        audio?.let { VoiceState.setLevel(it.level) }

        val now = SystemClock.elapsedRealtime()
        val heard = engine.feedCommand(buffer, length)

        if (heard.isFinal) {
            if (heard.text.isNotEmpty()) {
                dispatch(engine, heard.text, alreadyFinal = true)
                return
            }
            // An empty final is a silence segment; the recogniser has dropped its partial.
            partial = ""
        } else if (heard.text != partial) {
            partial = heard.text
            lastChangeAtMs = now
            VoiceState.setHeard(partial)
        }

        val spoke = partial.isNotEmpty()
        val settled = spoke && now - lastChangeAtMs >= TRAILING_SILENCE_MS
        val expired = now - wokeAtMs >= COMMAND_LIMIT_MS
        if (settled || expired) dispatch(engine, partial, alreadyFinal = false)
    }

    private fun dispatch(engine: VoskEngine, spoken: String, alreadyFinal: Boolean) {
        val transcript = if (alreadyFinal) {
            engine.resetCommand()
            spoken
        } else {
            engine.finishCommand().ifEmpty { spoken }
        }
        // The wake recogniser has been running on the same audio; clear it so the
        // command's own words cannot show up as a wake the moment we return to IDLE.
        engine.resetWake()
        partial = ""

        VoiceState.setLevel(0f)
        VoiceState.setHeard(transcript)
        enterPhase(VoicePhase.WORKING)
        scope.launch { complete(transcript) }
    }

    private suspend fun complete(transcript: String) {
        try {
            val outcome = try {
                execute(transcript)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Could not act on \"$transcript\"", t)
                Outcome(false, getString(R.string.voice_unrecognised))
            }

            VoiceState.setMessage(outcome.message)
            VoiceState.setPhase(if (outcome.ok) VoicePhase.SUCCESS else VoicePhase.FAILURE)

            // Speaking into a microphone that never stops listening is the whole risk in
            // this feature: the wake word is "alarm" and the confirmation opens with it,
            // so an unguarded confirmation would wake the clock and it would talk to
            // itself. Three things stop that, and all three are needed.
            //
            // Only VoiceState's phase went to SUCCESS above; the [phase] field the capture
            // thread reads is still WORKING, and onAudio() feeds neither recogniser in
            // that state. So while we are here the microphone is drained and discarded.
            // Waiting on the utterance below keeps us here until the engine reports the
            // speech has actually stopped, rather than for a fixed guess.
            //
            // The finally block then resets both recognisers before handing the mic back,
            // clearing anything half-decoded, and re-arms lastWakeAtMs so WAKE_DEBOUNCE_MS
            // also swallows the room's reverb tail of our own voice.
            val spoken = outcome.speak?.let { say(it) }
            delay(RESULT_HOLD_MS)
            if (spoken != null) {
                // Bounded well under PHASE_CEILING_MS so a wedged engine cannot let the
                // watchdog reclaim the phase out from under us.
                if (withTimeoutOrNull(SPEECH_LIMIT_MS) { spoken.await() } == null) {
                    Log.w(TAG, "Confirmation did not finish within ${SPEECH_LIMIT_MS}ms")
                }
            }
        } finally {
            // This is the only path back to IDLE. Anything thrown above — including a
            // cancellation — must still hand the microphone back, or every later wake
            // word is ignored until the process dies.
            //
            // The recognisers are only ours to touch while the phase is still WORKING:
            // once the watchdog has reclaimed it the capture thread owns them again, and
            // Vosk recognisers are not thread-safe.
            if (!destroyed && phase == VoicePhase.WORKING) {
                engine?.resetWake()
                engine?.resetCommand()
            }
            lastWakeAtMs = SystemClock.elapsedRealtime()
            enterPhase(VoicePhase.IDLE)
            VoiceState.reset()
        }
    }

    private fun execute(transcript: String): Outcome =
        when (val command = CommandParser.parse(transcript)) {
            is Command.SetAlarm -> setAlarm(command)

            Command.CancelAlarm ->
                if (AlarmScheduler.cancel(this)) Outcome(true, getString(R.string.voice_cancelled))
                else Outcome(false, getString(R.string.voice_no_alarm))

            Command.Unrecognised -> Outcome(false, getString(R.string.voice_unrecognised))
        }

    private fun setAlarm(command: Command.SetAlarm): Outcome {
        if (!AlarmScheduler.canScheduleExact(this)) return Outcome(false, FAULT_EXACT_ALARMS)

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val resolved = AlarmTimeResolver.resolve(command, now, zone)
        AlarmScheduler.schedule(
            this,
            Alarm(
                id = Alarm.PRIMARY_ID,
                triggerAtMillis = resolved.triggerAtMillis,
                hour = resolved.hour,
                minute = resolved.minute,
            ),
        )
        val label = clockLabel(resolved.hour, resolved.minute)
        return Outcome(
            ok = true,
            message = "Alarm set for $label · ${relative(resolved.triggerAtMillis - now)}",
            // Built from the instant that was actually armed, so if the resolver pushed
            // the alarm past midnight the weekday spoken is the one it will really ring on.
            speak = Speaker.confirmationFor(resolved.triggerAtMillis, zone),
        )
    }

    /**
     * Starts speaking [phrase] and returns something that completes when the engine says
     * it has stopped. Never blocks: TextToSpeech synthesises on its own thread and the
     * completion arrives on its callback.
     */
    private fun say(phrase: String): CompletableDeferred<Unit>? {
        val voice = speaker ?: return null
        val finished = CompletableDeferred<Unit>()
        voice.speak(phrase) { finished.complete(Unit) }
        return finished
    }

    private fun clockLabel(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val meridiem = if (hour < 12) "AM" else "PM"
        return String.format(Locale.US, "%d:%02d %s", h12, minute, meridiem)
    }

    private fun relative(deltaMillis: Long): String {
        val minutes = ((deltaMillis + 30_000L) / 60_000L).coerceAtLeast(0L)
        if (minutes < 1L) return "in under a minute"
        val hours = minutes / 60L
        val rest = minutes % 60L
        return when {
            hours == 0L -> "in ${rest}m"
            rest == 0L -> "in ${hours}h"
            else -> "in ${hours}h ${rest}m"
        }
    }

    /** @param speak non-null only when the outcome is worth saying out loud. */
    private data class Outcome(
        val ok: Boolean,
        val message: String,
        val speak: String? = null,
    )

    companion object {
        private const val TAG = "VoiceService"

        /** Hard cap on a single command, measured from the wake word. */
        private const val COMMAND_LIMIT_MS = 6_000L

        /** Silence after speech that ends a command early. */
        private const val TRAILING_SILENCE_MS = 1_200L

        /** Rejects the echo of a wake word that is still decaying in the recogniser. */
        private const val WAKE_DEBOUNCE_MS = 1_500L

        /** How long the confirmation or error stays on screen. */
        private const val RESULT_HOLD_MS = 2_500L

        /**
         * Ceiling on waiting for the spoken confirmation to finish. Generous for a
         * six-word phrase, and RESULT_HOLD_MS plus this stays clear of PHASE_CEILING_MS.
         */
        private const val SPEECH_LIMIT_MS = 6_000L

        /**
         * Absolute ceiling on any non-IDLE phase. Comfortably above COMMAND_LIMIT_MS
         * plus RESULT_HOLD_MS, so it only ever fires on a genuine stall.
         */
        private const val PHASE_CEILING_MS = 15_000L

        /** Capped exponential backoff for re-opening the microphone. */
        private val CAPTURE_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        /** Audio flowing this long counts as recovered, and rearms the backoff at 1s. */
        private const val CAPTURE_HEALTHY_MS = 60_000L

        private const val FAULT_MODEL = "Speech model unavailable"
        private const val FAULT_EXACT_ALARMS =
            "Allow exact alarms for Superclock in Settings to set an alarm"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
