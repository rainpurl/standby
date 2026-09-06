package org.dalwadi.superclock.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.dalwadi.superclock.R
import org.dalwadi.superclock.SuperclockApp
import java.util.Locale

/**
 * Plays the ringing alarm.
 *
 * Runs as a foreground service so it survives the app being closed, and holds a
 * partial wake lock so playback survives the CPU trying to sleep underneath it.
 */
class AlarmService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var hour = 0
    private var minute = 0
    private var rampStartedAt = 0L
    private var audioRetries = 0

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // An alarm must outlast whatever else is playing, so focus loss is logged and
        // then deliberately ignored - silencing here would be silencing the alarm.
        if (change != AudioManager.AUDIOFOCUS_GAIN) {
            Log.i(TAG, "Audio focus change $change; continuing to ring")
        }
    }

    private val volumeRamp = object : Runnable {
        override fun run() {
            val active = player ?: return
            val progress = (SystemClock.elapsedRealtime() - rampStartedAt)
                .toFloat() / RAMP_MILLIS
            val volume = START_VOLUME + (1f - START_VOLUME) * progress.coerceIn(0f, 1f)
            try {
                active.setVolume(volume, volume)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Volume ramp on a released player", e)
                return
            }
            if (progress < 1f) handler.postDelayed(this, RAMP_STEP_MILLIS)
        }
    }

    private val autoSilence = Runnable {
        Log.i(TAG, "Auto-silencing after $AUTO_SILENCE_MINUTES minutes")
        stopRinging()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A restart with no intent means the system recreated us, not that an alarm is due.
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_RING -> ring(intent)
            ACTION_SNOOZE -> snooze(startId)
            ACTION_STOP -> stop()
            else -> {
                Log.w(TAG, "Unknown action ${intent.action}")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun ring(intent: Intent) {
        intent.getIntExtra(EXTRA_HOUR, -1).takeIf { it in 0..23 }?.let { hour = it }
        intent.getIntExtra(EXTRA_MINUTE, -1).takeIf { it in 0..59 }?.let { minute = it }

        // Must happen within five seconds of the start request, on every delivery.
        promoteToForeground()

        if (_ringing.value) {
            Log.i(TAG, "Already ringing; ignoring duplicate RING")
            return
        }
        _ringing.value = true

        acquireWakeLock()
        audioRetries = 0
        startAudio()
        ensureVibrating()
        handler.postDelayed(autoSilence, AUTO_SILENCE_MINUTES * 60_000L)
        showAlarmActivity()
    }

    /**
     * On API 31+ this throws ForegroundServiceStartNotAllowedException when the process is
     * in the background with no FGS-launch exemption - which is exactly what happens once
     * the alarm has been delivered inexactly because exact-alarm permission was revoked.
     * Rather than let the alarm fail silently, post the same high-importance full-screen
     * notification unpromoted; the ring then continues for as long as the OS lets it, and
     * the notification alone can still wake the user.
     */
    private fun promoteToForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                SuperclockApp.NOTIF_ID_ALARM,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Foreground promotion refused; posting a plain alarm notification", e)
            try {
                NotificationManagerCompat.from(this)
                    .notify(SuperclockApp.NOTIF_ID_ALARM, buildNotification())
            } catch (fallback: RuntimeException) {
                Log.e(TAG, "Fallback alarm notification refused too", fallback)
            }
        }
    }

    private fun snooze(startId: Int) {
        if (!_ringing.value) {
            stopSelf(startId)
            return
        }
        // The stored hour/minute stay the originally spoken time so the alarm screen
        // keeps reading "07:00" rather than the snooze instant.
        AlarmScheduler.schedule(
            this,
            Alarm(
                id = Alarm.PRIMARY_ID,
                triggerAtMillis = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L,
                hour = hour,
                minute = minute,
                isSnooze = true,
            ),
        )
        Log.i(TAG, "Snoozed for $SNOOZE_MINUTES minutes")
        stopRinging()
        stopSelf()
    }

    private fun stop() {
        // A STOP is authoritative whether or not anything is sounding: the user may have
        // snoozed from the shade (arming +10 min) or auto-silence may have just fired, and
        // in both cases an alarm is still pending that they have explicitly stopped. Only
        // the audio teardown is conditional; AlarmScheduler.cancel is idempotent.
        //
        // `ringing` is cleared first so cancel cannot bounce a second stop back at us.
        if (_ringing.value) stopRinging()
        AlarmScheduler.cancel(this)
        stopSelf()
    }

    private fun stopRinging() {
        _ringing.value = false
        handler.removeCallbacks(autoSilence)
        handler.removeCallbacks(volumeRamp)
        stopAudio()
        stopVibration()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // The ring may have fallen back to a plain notification when foreground promotion
        // was refused, and stopForeground does not remove that one.
        NotificationManagerCompat.from(this).cancel(SuperclockApp.NOTIF_ID_ALARM)
    }

    override fun onDestroy() {
        try {
            stopRinging()
        } finally {
            releaseWakeLock()
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val fullScreen = PendingIntent.getActivity(
            this,
            REQ_FULL_SCREEN,
            AlarmActivity.intent(this, hour, minute).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            REQ_STOP,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getService(
            this,
            REQ_SNOOZE,
            snoozeIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, SuperclockApp.CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.notif_alarm_title))
            .setContentText(String.format(Locale.US, "%02d:%02d", hour, minute))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, getString(R.string.alarm_stop), stop)
            .addAction(0, getString(R.string.alarm_snooze), snooze)
            .build()
    }

    /** Full-screen intents are unreliable on some OEM builds, so ask directly too. */
    private fun showAlarmActivity() {
        try {
            startActivity(
                AlarmActivity.intent(this, hour, minute)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Direct activity start refused; relying on the full-screen intent", e)
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService<PowerManager>() ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Outlives auto-silence by a minute so a stuck teardown cannot pin the CPU.
            acquire((AUTO_SILENCE_MINUTES + 1) * 60_000L)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) {
            try {
                lock.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Wake lock already released", e)
            }
        }
    }

    private fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setLegacyStreamType(AudioManager.STREAM_ALARM)
        .build()

    private fun startAudio() {
        val manager = getSystemService<AudioManager>()
        audioManager = manager
        if (manager != null) {
            // TRANSIENT, not TRANSIENT_MAY_DUCK: a podcast or sleep-sounds app left running
            // overnight must pause, not merely play quietly through the alarm.
            val request = AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes())
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        }

        startPlayback(START_VOLUME)
    }

    private fun startPlayback(initialVolume: Float) {
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(playbackAttributes())
                resources.openRawResourceFd(R.raw.alarm_tone).use {
                    setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                isLooping = true
                setVolume(initialVolume, initialVolume)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    rebuildPlaybackAfterError()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start alarm audio; vibration only", e)
            releasePlayer()
            ensureVibrating()
            return
        }

        if (initialVolume < 1f) {
            // A gentle climb wakes the sleeper instead of jolting them.
            rampStartedAt = SystemClock.elapsedRealtime()
            handler.post(volumeRamp)
        }
    }

    /**
     * MEDIA_ERROR_SERVER_DIED - the media server restarting overnight, or an output route
     * change mid-ring - leaves the player permanently in the Error state. Reporting the
     * error as handled and doing nothing would leave the service believing it is ringing
     * while the room is silent, so build a fresh player instead.
     */
    private fun rebuildPlaybackAfterError() {
        // Rebuild on a later main-thread message: the failed player must not be released
        // from inside its own error callback.
        handler.post {
            if (!_ringing.value) return@post
            handler.removeCallbacks(volumeRamp)
            releasePlayer()
            if (audioRetries >= MAX_AUDIO_RETRIES) {
                Log.e(TAG, "Alarm audio unrecoverable after $audioRetries retries; vibration only")
                ensureVibrating()
                return@post
            }
            audioRetries++
            Log.w(TAG, "Rebuilding alarm audio after error (attempt $audioRetries)")
            // Full volume, not the ramp start: the sleeper has already had the gentle climb.
            startPlayback(1f)
        }
    }

    /** The last wake-up channel left when audio cannot be made to sound. */
    private fun ensureVibrating() {
        if (vibrator == null) startVibration()
    }

    private fun stopAudio() {
        releasePlayer()
        val manager = audioManager
        val request = focusRequest
        if (manager != null && request != null) {
            manager.abandonAudioFocusRequest(request)
        }
        focusRequest = null
        audioManager = null
    }

    private fun releasePlayer() {
        val active = player ?: return
        player = null
        try {
            if (active.isPlaying) active.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Player was not in a stoppable state", e)
        }
        active.release()
    }

    private fun startVibration() {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()
        }
        if (device == null || !device.hasVibrator()) return
        vibrator = device

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            device.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0), attributes)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Vibration refused", e)
            vibrator = null
        }
    }

    private fun stopVibration() {
        val device = vibrator ?: return
        vibrator = null
        try {
            device.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not cancel vibration", e)
        }
    }

    companion object {
        const val ACTION_RING = "org.dalwadi.superclock.RING"
        const val ACTION_STOP = "org.dalwadi.superclock.STOP"
        const val ACTION_SNOOZE = "org.dalwadi.superclock.SNOOZE"

        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"

        const val SNOOZE_MINUTES = 10
        const val AUTO_SILENCE_MINUTES = 10

        private const val TAG = "AlarmService"
        private const val WAKE_LOCK_TAG = "superclock:alarm"

        private const val START_VOLUME = 0.15f
        private const val RAMP_MILLIS = 45_000f
        private const val RAMP_STEP_MILLIS = 250L
        private const val MAX_AUDIO_RETRIES = 2

        private val VIBRATION_PATTERN = longArrayOf(0L, 600L, 900L)

        private const val REQ_FULL_SCREEN = 200
        private const val REQ_STOP = 201
        private const val REQ_SNOOZE = 202

        private val _ringing = MutableStateFlow(false)
        val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

        fun ringIntent(context: Context, hour: Int, minute: Int): Intent =
            Intent(context, AlarmService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_HOUR, hour)
                .putExtra(EXTRA_MINUTE, minute)

        fun stopIntent(context: Context): Intent =
            Intent(context, AlarmService::class.java).setAction(ACTION_STOP)

        fun snoozeIntent(context: Context): Intent =
            Intent(context, AlarmService::class.java).setAction(ACTION_SNOOZE)
    }
}
