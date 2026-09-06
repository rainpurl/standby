package org.dalwadi.superclock.clock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.dalwadi.superclock.R
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Follows the room's light level and says how the clock should be lit.
 *
 * The hard requirement is that the face never visibly pulses. A phone's light sensor
 * is noisy and reacts to headlights, a passing hand, or the screen's own glow, so the
 * raw reading is put through a slow exponential average and the mapped output is only
 * published when it has moved further than the eye would forgive.
 */
class AmbientBrightnessController(
    context: Context,
    private val onChange: (Level) -> Unit,
) {

    /**
     * @param screenBrightness for `WindowManager.LayoutParams.screenBrightness`, 0f..1f.
     * @param dimScrim alpha of a black overlay, 0f..1f. Most panels bottom out well
     *   above what a pitch-dark bedroom wants, so this takes the face below the
     *   hardware floor.
     * @param digitColor fully opaque ARGB for the digits at this light level.
     */
    data class Level(val screenBrightness: Float, val dimScrim: Float, val digitColor: Int)

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService<SensorManager>()
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val digitDim = ContextCompat.getColor(appContext, R.color.digit_dim)
    private val digitBright = ContextCompat.getColor(appContext, R.color.digit_bright)

    private val handler = Handler(Looper.getMainLooper())

    private val prefs = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    private var biasValue = prefs.getFloat(KEY_BIAS, 0f).coerceIn(MIN_BIAS, MAX_BIAS)

    /** True when the device has no light sensor and a time-of-day curve stands in. */
    val usingFallback: Boolean = lightSensor == null

    /**
     * The owner's standing preference, [MIN_BIAS]..[MAX_BIAS], added to the room's
     * position on the perceptual curve rather than replacing it. A bias is not an
     * override: a dark room still lands dim and daylight still lands bright, the whole
     * curve is simply shifted, so the sensor keeps doing its job all night.
     */
    val bias: Float get() = biasValue

    /** How far the bias can travel end to end, for callers sizing a gesture against it. */
    val biasSpan: Float get() = MAX_BIAS - MIN_BIAS

    /**
     * Published the instant [start] runs, so a caller always has a level in hand before
     * the first sensor delivery — which is hundreds of ms away on a good device and
     * never on a wedged one. Deliberately dim: starting too dark costs a moment's
     * squint, starting too bright is a torch in a dark bedroom.
     *
     * A getter rather than a stored value because [bias] can move under it.
     */
    val defaultLevel: Level get() = effectiveLevelFor(DEFAULT_T)

    private var running = false
    private var smoothedLux = Float.NaN
    private var lastEventUptime = 0L

    /** Curve position of the last published target; NaN until the first publish. */
    private var publishedT = Float.NaN

    /** The most recent level handed to [onChange], i.e. what is on screen now. */
    private var current: Level? = null

    private var rampFrom: Level? = null
    private var rampTo: Level? = null
    private var rampStart = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val lux = event.values.firstOrNull() ?: return
            if (lux.isNaN() || lux < 0f) return
            smooth(lux)
            publish(brightnessCurve(smoothedLux))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val fallbackTick = object : Runnable {
        override fun run() {
            publish(timeOfDayCurve(LocalTime.now()))
            handler.postDelayed(this, FALLBACK_INTERVAL_MS)
        }
    }

    private val rampTick = object : Runnable {
        override fun run() {
            val from = rampFrom ?: return
            val to = rampTo ?: return
            val p = ((SystemClock.uptimeMillis() - rampStart).toFloat() / RAMP_MS).coerceIn(0f, 1f)
            val eased = p * p * (3f - 2f * p)
            emit(
                Level(
                    screenBrightness = lerp(from.screenBrightness, to.screenBrightness, eased),
                    dimScrim = lerp(from.dimScrim, to.dimScrim, eased),
                    // The digit colour is handed over whole: the clock view runs its own
                    // colour animation, and re-targeting it every frame would fight it.
                    digitColor = to.digitColor,
                ),
            )
            if (p < 1f) {
                handler.postDelayed(this, RAMP_FRAME_MS)
            } else {
                rampFrom = null
                rampTo = null
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        if (publishedT.isNaN()) {
            publishedT = DEFAULT_T
            emit(defaultLevel)
        }
        if (lightSensor != null) {
            // Delivered on the main looper, which is where the caller wants them.
            sensorManager?.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            handler.post(fallbackTick)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(listener)
        handler.removeCallbacks(fallbackTick)
        handler.removeCallbacks(rampTick)
        rampFrom = null
        rampTo = null
        smoothedLux = Float.NaN
        lastEventUptime = 0L
    }

    /**
     * @param immediate skip the easing ramp. A drag is a direct manipulation: the eye
     *   expects the face to track the finger, and the 400 ms ramp that keeps the sensor
     *   from pulsing reads as lag when a human is the one asking.
     */
    fun setBias(value: Float, immediate: Boolean = false) {
        val clamped = value.coerceIn(MIN_BIAS, MAX_BIAS)
        if (clamped == biasValue) return
        biasValue = clamped
        val target = effectiveLevelFor(if (publishedT.isNaN()) DEFAULT_T else publishedT)
        if (immediate) {
            handler.removeCallbacks(rampTick)
            rampFrom = null
            rampTo = null
            emit(target)
        } else {
            ramp(target)
        }
    }

    /**
     * Writes the bias to disk. Separate from [setBias] so a drag can move it sixty
     * times a second without sixty preference commits; call it once the gesture ends.
     */
    fun persistBias() {
        prefs.edit().putFloat(KEY_BIAS, biasValue).apply()
    }

    /**
     * Exponential average with a several-second time constant, stepped by the real
     * gap between events rather than assuming a fixed sensor rate.
     */
    private fun smooth(lux: Float) {
        val now = SystemClock.uptimeMillis()
        if (smoothedLux.isNaN()) {
            smoothedLux = lux
            lastEventUptime = now
            return
        }
        val dtSeconds = ((now - lastEventUptime).coerceAtLeast(0L)).toFloat() / 1000f
        lastEventUptime = now
        // Rising light is allowed to move faster than falling light: walking into a lit
        // room should catch up quickly, while a brief glare should barely register.
        val tau = if (lux > smoothedLux) TAU_RISE_SECONDS else TAU_FALL_SECONDS
        val alpha = 1f - exp(-dtSeconds / tau)
        smoothedLux += alpha * (lux - smoothedLux)
    }

    /** Lux to a 0f..1f position on a perceptual curve; 1000 lux and up is "daylight". */
    private fun brightnessCurve(lux: Float): Float =
        (ln(1f + lux.coerceAtLeast(0f)) / LN_FULL_SCALE).coerceIn(0f, 1f)

    private fun timeOfDayCurve(now: LocalTime): Float {
        val minutes = now.hour * 60 + now.minute
        return when {
            minutes >= DAY_START && minutes < DAY_END -> 1f
            minutes >= NIGHT_START || minutes < NIGHT_END -> FALLBACK_NIGHT
            // The two ramps: dusk 20:00-22:00 down, dawn 06:00-08:00 up.
            minutes >= DAY_END -> lerp(1f, FALLBACK_NIGHT, (minutes - DAY_END).toFloat() / (NIGHT_START - DAY_END))
            else -> lerp(FALLBACK_NIGHT, 1f, (minutes - NIGHT_END).toFloat() / (DAY_START - NIGHT_END))
        }
    }

    /** The room's curve position shifted by the owner's standing preference. */
    private fun effectiveLevelFor(t: Float): Level = levelFor((t + biasValue).coerceIn(0f, 1f))

    private fun levelFor(t: Float): Level {
        val brightness = MIN_BRIGHTNESS + (1f - MIN_BRIGHTNESS) * t.pow(BRIGHTNESS_GAMMA)
        val scrim = if (t >= SCRIM_CUTOFF) 0f else MAX_SCRIM * (1f - t / SCRIM_CUTOFF)
        val colour = blend(digitDim, digitBright, t.pow(COLOUR_GAMMA))
        return Level(brightness.coerceIn(MIN_BRIGHTNESS, 1f), scrim.coerceIn(0f, MAX_SCRIM), colour)
    }

    /**
     * Publishes only a change the eye would actually notice.
     *
     * The comparison is made on the curve position rather than on the mapped outputs:
     * the outputs are perceptually spaced but numerically lopsided, so an absolute
     * deadband on brightness is loose in daylight and hair-trigger in the dark — the
     * regime this clock spends every night in. One threshold in t-space is the same
     * apparent step everywhere.
     */
    private fun publish(t: Float) {
        if (!publishedT.isNaN() && abs(t - publishedT) < T_EPSILON) return
        publishedT = t
        ramp(effectiveLevelFor(t))
    }

    /** Walks the panel and the scrim to [target] instead of cutting, which reads as a pulse. */
    private fun ramp(target: Level) {
        val from = current
        if (from == null) {
            emit(target)
            return
        }
        rampFrom = from
        rampTo = target
        rampStart = SystemClock.uptimeMillis()
        handler.removeCallbacks(rampTick)
        handler.post(rampTick)
    }

    private fun emit(level: Level) {
        current = level
        onChange(level)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r = lerp(((from shr 16) and 0xFF).toFloat(), ((to shr 16) and 0xFF).toFloat(), f)
        val g = lerp(((from shr 8) and 0xFF).toFloat(), ((to shr 8) and 0xFF).toFloat(), f)
        val b = lerp((from and 0xFF).toFloat(), (to and 0xFF).toFloat(), f)
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)

    private companion object {
        const val TAU_RISE_SECONDS = 2.5f
        const val TAU_FALL_SECONDS = 8f

        /** ln(1 + 1000): the lux at which the face is considered fully lit. */
        val LN_FULL_SCALE = ln(1001f)

        const val MIN_BRIGHTNESS = 0.01f
        const val BRIGHTNESS_GAMMA = 1.2f
        const val COLOUR_GAMMA = 1.3f

        const val MAX_SCRIM = 0.55f
        /** Above this curve position the panel alone is dim enough. */
        const val SCRIM_CUTOFF = 0.30f

        /** Smallest curve step worth showing, ~1% of the perceptual range. */
        const val T_EPSILON = 0.01f

        /** Curve position used before the room has been measured. */
        const val DEFAULT_T = 0.30f

        const val RAMP_MS = 400f
        const val RAMP_FRAME_MS = 16L

        const val MIN_BIAS = -1f
        const val MAX_BIAS = 1f

        const val SETTINGS_PREFS = "superclock_settings"
        const val KEY_BIAS = "brightness_bias"

        const val FALLBACK_INTERVAL_MS = 120_000L
        const val FALLBACK_NIGHT = 0.03f
        const val NIGHT_END = 6 * 60
        const val DAY_START = 8 * 60
        const val DAY_END = 20 * 60
        const val NIGHT_START = 22 * 60
    }
}
