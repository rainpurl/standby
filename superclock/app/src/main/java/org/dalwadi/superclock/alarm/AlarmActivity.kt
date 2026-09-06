package org.dalwadi.superclock.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dalwadi.superclock.databinding.ActivityAlarmBinding
import java.time.LocalTime
import java.util.Locale

/**
 * The full-screen ringing alarm. Shown over the lock screen by [AlarmService]'s
 * full-screen intent; the audio itself belongs to the service, so this screen is
 * only ever a decision between Stop and Snooze.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    /** True once Stop or Snooze has been taken, so the ringing collector stops interfering. */
    private var decided = false

    /** The service flips [AlarmService.ringing] a beat after the activity is launched. */
    private var sawRinging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareWindow()

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepControlsClearOfCutout()

        showTime(intent)
        playEntrance()

        binding.alarmStop.setOnClickListener { stopAlarm() }
        binding.alarmSnooze.setOnClickListener { snoozeAlarm() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // A sleeping hand must not be able to dismiss the alarm by accident.
            override fun handleOnBackPressed() = Unit
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlarmService.ringing.collect { ringing ->
                    if (ringing) {
                        sawRinging = true
                    } else if (sawRinging && !decided) {
                        Log.i(TAG, "Alarm silenced elsewhere; closing")
                        finish()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        decided = false
        binding.alarmStatus.visibility = View.INVISIBLE
        binding.alarmStop.isEnabled = true
        binding.alarmSnooze.isEnabled = true
        showTime(intent)
        playEntrance()
    }

    private fun prepareWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // On a secure lock screen requestDismissKeyguard does not unlock anything - it
        // raises the PIN/pattern bouncer over this window, burying Stop and Snooze behind
        // a credential prompt. setShowWhenLocked already puts the alarm in front of the
        // keyguard, so only an insecure keyguard is worth dismissing.
        getSystemService<KeyguardManager>()?.let { keyguard ->
            if (!keyguard.isDeviceSecure) keyguard.requestDismissKeyguard(this, null)
        }

        // The clock face dims itself to near zero in a dark room and that brightness
        // survives into this window, so pin something legible for the duration.
        window.attributes = window.attributes.apply {
            screenBrightness = WAKE_BRIGHTNESS
            // Letting the window be inset by the cutout costs 81px of width on an S23 and
            // lands all of it on one side in landscape, so the alarm sits visibly off
            // centre. Take the whole display instead; keepControlsClearOfCutout() pads the
            // notch back out of the content.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** Adds the cutout and system-bar insets to the layout's own padding. */
    private fun keepControlsClearOfCutout() {
        val root = binding.root
        val basePadding = Rect(
            root.paddingLeft,
            root.paddingTop,
            root.paddingRight,
            root.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val edges = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(
                basePadding.left + edges.left,
                basePadding.top + edges.top,
                basePadding.right + edges.right,
                basePadding.bottom + edges.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showTime(source: Intent) {
        val hour = source.getIntExtra(EXTRA_HOUR, -1)
        val minute = source.getIntExtra(EXTRA_MINUTE, -1)
        binding.alarmTime.text = if (hour in 0..23 && minute in 0..59) {
            format(hour, minute)
        } else {
            Log.w(TAG, "Ring intent carried no valid time; showing the current time")
            LocalTime.now().let { format(it.hour, it.minute) }
        }
    }

    private fun playEntrance() {
        binding.alarmTimeGroup.apply {
            alpha = 0f
            scaleX = ENTRANCE_SCALE
            scaleY = ENTRANCE_SCALE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ENTRANCE_MILLIS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun stopAlarm() {
        if (decided) return
        decided = true
        lockControls()
        startService(AlarmService.stopIntent(this))
        finish()
    }

    private fun snoozeAlarm() {
        if (decided) return
        decided = true
        lockControls()
        startService(AlarmService.snoozeIntent(this))

        lifecycleScope.launch {
            // startService is asynchronous, and the service refuses a snooze that arrives
            // after auto-silence or a Stop from the shade. Wait for the store to actually
            // hold a snooze before promising the user one; a false "Snoozed until ..."
            // sends them back to sleep with nothing armed.
            if (!awaitSnoozeArmed()) {
                Log.w(TAG, "Snooze was not accepted; closing without a confirmation")
                finish()
                return@launch
            }
            val next = LocalTime.now().plusMinutes(AlarmService.SNOOZE_MINUTES.toLong())
            binding.alarmStatus.text = "Snoozed until ${format(next.hour, next.minute)}"
            binding.alarmStatus.alpha = 0f
            binding.alarmStatus.visibility = View.VISIBLE
            binding.alarmStatus.animate().alpha(1f).setDuration(CONFIRM_FADE_MILLIS).start()
            delay(CONFIRM_HOLD_MILLIS)
            finish()
        }
    }

    private suspend fun awaitSnoozeArmed(): Boolean {
        repeat(SNOOZE_CHECK_ATTEMPTS) {
            delay(SNOOZE_CHECK_MILLIS)
            val armed = AlarmStore.get(this)
            // The alarm that just rang may itself have been a snooze and may still be in
            // the store, so a future trigger time is what marks this snooze as the new one.
            if (armed != null && armed.isSnooze &&
                armed.triggerAtMillis > System.currentTimeMillis()
            ) {
                return true
            }
        }
        return false
    }

    private fun lockControls() {
        binding.alarmStop.isEnabled = false
        binding.alarmSnooze.isEnabled = false
    }

    private fun format(hour: Int, minute: Int) = String.format(Locale.US, "%02d:%02d", hour, minute)

    companion object {
        private const val TAG = "AlarmActivity"

        private const val EXTRA_HOUR = "org.dalwadi.superclock.alarm.HOUR"
        private const val EXTRA_MINUTE = "org.dalwadi.superclock.alarm.MINUTE"

        private const val WAKE_BRIGHTNESS = 0.85f
        private const val ENTRANCE_SCALE = 0.94f
        private const val ENTRANCE_MILLIS = 250L
        private const val CONFIRM_FADE_MILLIS = 150L
        private const val CONFIRM_HOLD_MILLIS = 800L
        private const val SNOOZE_CHECK_MILLIS = 60L
        private const val SNOOZE_CHECK_ATTEMPTS = 8

        fun intent(context: Context, hour: Int, minute: Int): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(EXTRA_HOUR, hour)
                .putExtra(EXTRA_MINUTE, minute)
    }
}
