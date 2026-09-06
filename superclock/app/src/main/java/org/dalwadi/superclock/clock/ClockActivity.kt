package org.dalwadi.superclock.clock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.dalwadi.superclock.R
import org.dalwadi.superclock.alarm.AlarmScheduler
import org.dalwadi.superclock.alarm.AlarmStore
import org.dalwadi.superclock.databinding.ActivityClockBinding
import org.dalwadi.superclock.voice.VoicePhase
import org.dalwadi.superclock.voice.VoiceService
import org.dalwadi.superclock.voice.VoiceState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The face. Runs indefinitely on a charger, so everything here is written to idle at
 * near-zero cost: the clock ticks once a minute, the overlay draws nothing while the
 * wake word has not fired, and the brightness controller only speaks when the room
 * actually changes.
 */
class ClockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClockBinding
    private lateinit var brightness: AmbientBrightnessController
    private lateinit var dial: BrightnessDialView

    private val handler = Handler(Looper.getMainLooper())

    /** The last level the room asked for, so the tap-to-brighten boost can hand back. */
    private var ambientLevel: AmbientBrightnessController.Level? = null
    private var boosted = false

    /** A voice fault outranks the mic notice, which outranks the alarm itself. */
    private var faultNotice: String? = null
    private var permissionNotice: String? = null

    private var touchSlop = 0
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var gestureValid = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var dragStartBias = 0f

    /**
     * Unit vector, in view coordinates, pointing at the device's camera-notch end.
     * Defaults to the natural-orientation answer until the first touch reads the
     * display; touch y grows downward, so "up the screen" is -1.
     */
    private var brightenX = 0
    private var brightenY = -1

    private val clearBoost = Runnable {
        boosted = false
        // Never leave the boost applied: if the sensor has not reported yet there is
        // still the controller's default to hand back to.
        applyLevel(ambientLevel ?: brightness.defaultLevel)
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            requestNotificationPermissionIfNeeded()
            VoiceService.start(this)
        } else {
            // The clock is the point; voice is a bonus. Say so once and carry on.
            permissionNotice = getString(R.string.perm_mic_rationale)
            renderAlarmLine()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Declining only costs the silent status line, so nothing to handle. */ }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first layout, so the face is measured against the whole screen and never
        // has to be re-centred after a visible first frame at the narrower cutout width.
        allowLayoutIntoCutout()
        binding = ActivityClockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        goImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.clockFace.setBurnInShiftEnabled(true)

        dial = BrightnessDialView(this)
        binding.root.addView(
            dial,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        // The click listener still owns the tap; the touch listener only decides whether
        // this gesture was a tap at all, and calls through once it is sure.
        binding.root.setOnClickListener { boostForReading() }
        binding.root.setOnTouchListener { view, event -> onFaceTouch(view, event) }

        brightness = AmbientBrightnessController(this) { level ->
            ambientLevel = level
            if (!boosted) applyLevel(level)
        }

        // Collected once for the life of the activity: repeatOnLifecycle gates on
        // STARTED by itself and only returns at DESTROYED, so re-launching this from
        // onStart would stack a duplicate set of collectors on every restart.
        observeVoice()

        promptForExactAlarmsOnce()
    }

    override fun onStart() {
        super.onStart()
        binding.clockFace.startTicking()
        binding.voiceOverlay.resumeAnimation()
        brightness.start()
        ensureVoiceRunning()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        renderAlarmLine()
    }

    override fun onStop() {
        super.onStop()
        binding.clockFace.stopTicking()
        // The overlay only stops its own ticker from onDraw, which never runs once the
        // alarm screen is on top; without this it animates for the whole ring.
        binding.voiceOverlay.pauseAnimation()
        brightness.stop()
        if (dragging) finishDrag()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clearBoost)
        // Only tear the listener down when the clock is genuinely going away; a
        // configuration change or a glance at another app should not deafen it.
        if (isFinishing) VoiceService.stop(this)
    }

    /**
     * The manifest handles orientation changes itself, so nothing else re-runs on a
     * flip: the immersive bars have to be re-hidden here or they stay up for the night.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        goImmersive()
        refreshBrightenAxis()
    }

    private fun observeVoice() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    VoiceState.phase.collectLatest { phase ->
                        binding.voiceOverlay.setPhase(phase)
                        // A successful command usually changed the alarm.
                        if (phase == VoicePhase.IDLE) renderAlarmLine()
                    }
                }
                launch { VoiceState.level.collectLatest(binding.voiceOverlay::setLevel) }
                launch { VoiceState.heard.collectLatest(binding.voiceOverlay::setHeard) }
                launch { VoiceState.message.collectLatest(binding.voiceOverlay::setMessage) }
                launch {
                    VoiceState.fault.collectLatest { fault ->
                        // Symmetric on purpose: voice recovers on its own, and a fault
                        // that is over must not sit across the face for the rest of the
                        // month.
                        faultNotice = fault
                        renderAlarmLine()
                    }
                }
            }
        }
    }

    private fun ensureVoiceRunning() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestNotificationPermissionIfNeeded()
            VoiceService.start(this)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun applyLevel(level: AmbientBrightnessController.Level) {
        val attributes = window.attributes
        attributes.screenBrightness = level.screenBrightness
        window.attributes = attributes
        binding.dimScrim.alpha = level.dimScrim
        binding.clockFace.setDigitColor(level.digitColor)
        binding.voiceOverlay.setTint(level.digitColor)
        binding.alarmLine.setTextColor(level.digitColor)
        dial.setTint(level.digitColor)
    }

    /**
     * A tap lifts the face a few stops for a few seconds so it is readable at a glance.
     *
     * Relative to the room, not absolute: the whole screen is the touch target, so a
     * sleeve brushing the glass at 3am must not turn a bedside clock into a torch.
     */
    private fun boostForReading() {
        val base = ambientLevel ?: brightness.defaultLevel
        boosted = true
        handler.removeCallbacks(clearBoost)
        applyLevel(
            AmbientBrightnessController.Level(
                screenBrightness = (base.screenBrightness * BOOST_FACTOR)
                    .coerceIn(BOOST_MIN_BRIGHTNESS, 1f),
                dimScrim = base.dimScrim * BOOST_SCRIM_KEEP,
                digitColor = brighten(base.digitColor, BOOST_FACTOR),
            ),
        )
        handler.postDelayed(clearBoost, BOOST_MILLIS)
    }

    /**
     * Swiping the face turns it into a brightness dial.
     *
     * The owner described the gesture physically — toward the camera notch is brighter,
     * toward the charging port is dimmer — and those sit at opposite short ends of the
     * phone, so the gesture runs along the phone's long axis. On a stand the phone can
     * be laid either way up and either way round, so a hard-coded screen direction
     * would be wrong half the time. [refreshBrightenAxis] asks the display which way
     * the device's natural top currently faces and the swipe is measured against that
     * instead of against raw screen coordinates.
     *
     * What it moves is a bias, not an absolute brightness: the room still changes over
     * a night and the sensor must keep working, so the swipe only shifts the ambient
     * curve up or down.
     */
    private fun onFaceTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                gestureValid = true
                dragging = false
                downX = event.x
                downY = event.y
                refreshBrightenAxis()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is a palm or a sleeve, not a dial turn.
                if (dragging) finishDrag()
                gestureValid = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureValid) return true
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                if (!dragging) {
                    // Below the slop this is still a tap; past it the tap is forfeited
                    // so a drag can never also fire the boost.
                    if (hypot(x - downX, y - downY) < touchSlop) return true
                    beginDrag(x, y)
                }
                val extent = if (brightenX != 0) view.width else view.height
                if (extent > 0) {
                    val travel = brightenX * (x - anchorX) + brightenY * (y - anchorY)
                    val target = dragStartBias + travel / extent * brightness.biasSpan
                    brightness.setBias(target, immediate = true)
                    dial.show(brightness.bias)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) finishDrag() else if (gestureValid) view.performClick()
                gestureValid = false
            }

            MotionEvent.ACTION_CANCEL -> {
                // The gesture was taken away mid-turn; keep what the finger had reached
                // rather than snapping the face back under the owner's hand.
                if (dragging) finishDrag()
                gestureValid = false
            }
        }
        return true
    }

    private fun beginDrag(x: Float, y: Float) {
        dragging = true
        // Re-anchor at the slop crossing so the dial does not jump by a slop's worth.
        anchorX = x
        anchorY = y
        dragStartBias = brightness.bias
        // A drag supersedes a tap boost; otherwise the boost's timer would stamp the
        // old level back over the dial a few seconds later.
        handler.removeCallbacks(clearBoost)
        boosted = false
        dial.setAxis(brightenX != 0, if (brightenX != 0) brightenX else brightenY)
    }

    /** Commits the bias and lets the smoothed ambient path take the face back over. */
    private fun finishDrag() {
        dragging = false
        brightness.persistBias()
        dial.fadeOut()
    }

    /**
     * Works out which on-screen direction currently points at the device's top — the
     * notch end — from the display rotation.
     *
     * ROTATION_0 is the phone's natural pose, notch at the top of the screen. The
     * rotations are counter-clockwise turns of the device, so ROTATION_90 has the
     * phone turned left and its top edge now on the left of the screen, ROTATION_270
     * puts it on the right, and ROTATION_180 flips it to the bottom. Read on every
     * touch-down because sensorLandscape can flip the phone end for end at any time
     * without the activity being recreated.
     */
    private fun refreshBrightenAxis() {
        when (currentRotation()) {
            Surface.ROTATION_90 -> { brightenX = -1; brightenY = 0 }
            Surface.ROTATION_180 -> { brightenX = 0; brightenY = 1 }
            Surface.ROTATION_270 -> { brightenX = 1; brightenY = 0 }
            else -> { brightenX = 0; brightenY = -1 }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int {
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        }
        return screen?.rotation ?: Surface.ROTATION_0
    }

    private fun brighten(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceAtMost(0xFF),
        (Color.green(color) * factor).toInt().coerceAtMost(0xFF),
        (Color.blue(color) * factor).toInt().coerceAtMost(0xFF),
    )

    /**
     * The single owner of the alarm line. Everything that wants to write there sets its
     * own slot and calls this, so the fault and phase collectors cannot race each other
     * into leaving a stale string on screen.
     */
    private fun renderAlarmLine() {
        val notice = faultNotice ?: permissionNotice
        if (notice != null) {
            binding.alarmLine.text = notice
            binding.alarmLine.visibility = View.VISIBLE
            return
        }
        val alarm = AlarmStore.get(this)
        if (alarm == null) {
            binding.alarmLine.visibility = View.GONE
            return
        }
        binding.alarmLine.text = getString(R.string.alarm_line_format, alarm.hour, alarm.minute)
        binding.alarmLine.visibility = View.VISIBLE
    }

    /**
     * On 31/32 exact alarms are a user toggle and an alarm clock is useless without
     * them. From 33 the USE_EXACT_ALARM permission covers it at install time.
     */
    private fun promptForExactAlarmsOnce() {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2) return
        if (AlarmScheduler.canScheduleExact(this)) return

        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_EXACT_ALARM, false)) return
        prefs.edit().putBoolean(KEY_ASKED_EXACT_ALARM, true).apply()

        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
    }

    /**
     * Lets the window span the physical display instead of the cutout-safe rectangle.
     *
     * Without this the window is inset by the notch — on this device 81px — and in landscape the
     * whole of that lands on one side, so a face centred in the window sits visibly off-centre on
     * the glass. The bars are hidden anyway, so there is nothing left for the cutout to collide
     * with. ALWAYS is API 30; SHORT_EDGES is the API 28 spelling and covers the same landscape
     * case; below 28 no device has a cutout to inset for.
     */
    private fun allowLayoutIntoCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val attributes = window.attributes
        attributes.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = attributes
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * A hairline track with a knob on it, shown only while the dial is being turned.
     *
     * Deliberately not part of the face: it lives at the far edge, wears whatever
     * colour the digits are currently wearing — so it is charcoal-on-black in a dark
     * room, never a white line at 3am — and fades itself away shortly after release.
     */
    private class BrightnessDialView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = RectF()

        private val density = resources.displayMetrics.density
        private val halfThickness = THICKNESS_DP * density / 2f
        private val edgeInset = EDGE_INSET_DP * density
        private val halfKnob = KNOB_DP * density / 2f

        private var horizontal = true
        private var towardBright = 1
        private var bias = 0f
        private var tint = Color.WHITE

        init {
            isClickable = false
            isFocusable = false
            visibility = GONE
            alpha = 0f
        }

        /** @param towardBright sign of the coordinate that grows toward the notch end. */
        fun setAxis(horizontal: Boolean, towardBright: Int) {
            this.horizontal = horizontal
            this.towardBright = if (towardBright >= 0) 1 else -1
        }

        fun setTint(color: Int) {
            if (color == tint) return
            tint = color
            if (visibility == VISIBLE) invalidate()
        }

        fun show(bias: Float) {
            val changed = abs(bias - this.bias) > BIAS_EPSILON || visibility != VISIBLE
            this.bias = bias
            // Cancels a fade already running, so re-grabbing the dial mid-fade does not
            // leave it dissolving under the finger.
            animate().cancel()
            visibility = VISIBLE
            alpha = 1f
            if (changed) invalidate()
        }

        fun fadeOut() {
            animate().alpha(0f)
                .setStartDelay(FADE_DELAY_MS)
                .setDuration(FADE_MS)
                .withEndAction { visibility = GONE }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val half = (if (horizontal) w else h) * TRACK_FRACTION / 2f
            if (half <= halfKnob) return
            val centre = (if (horizontal) w else h) / 2f
            val cross = (if (horizontal) h else w) - edgeInset
            val knob = centre + towardBright * bias.coerceIn(-1f, 1f) * (half - halfKnob)

            paint.color = tint

            paint.alpha = TRACK_ALPHA
            setBar(centre - half, centre + half, cross)
            canvas.drawRoundRect(bar, halfThickness, halfThickness, paint)

            paint.alpha = KNOB_ALPHA
            setBar(knob - halfKnob, knob + halfKnob, cross)
            canvas.drawRoundRect(bar, halfThickness, halfThickness, paint)
        }

        /** Reuses the one rect: this view is on screen while a finger is moving. */
        private fun setBar(from: Float, to: Float, cross: Float) {
            if (horizontal) {
                bar.set(from, cross - halfThickness, to, cross + halfThickness)
            } else {
                bar.set(cross - halfThickness, from, cross + halfThickness, to)
            }
        }

        private companion object {
            const val THICKNESS_DP = 2f
            const val EDGE_INSET_DP = 12f
            const val KNOB_DP = 22f
            const val TRACK_FRACTION = 0.5f
            const val BIAS_EPSILON = 0.0005f
            const val TRACK_ALPHA = 60
            const val KNOB_ALPHA = 220
            const val FADE_DELAY_MS = 700L
            const val FADE_MS = 500L
        }
    }

    private companion object {
        const val BOOST_MILLIS = 4_000L

        /** Roughly two stops up from wherever the room has the face sitting. */
        const val BOOST_FACTOR = 4f
        const val BOOST_MIN_BRIGHTNESS = 0.06f
        const val BOOST_SCRIM_KEEP = 0.4f

        const val SETTINGS_PREFS = "superclock_settings"
        const val KEY_ASKED_EXACT_ALARM = "asked_exact_alarm"
    }
}
