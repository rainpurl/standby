package org.dalwadi.superclock.clock

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.dalwadi.superclock.R
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 24-hour HH MM readout: four Sofia Sans Extra Condensed numerals, no colon, on black.
 *
 * The colon is omitted deliberately — it is the only element that never changes, so it is the
 * one guaranteed to burn in. A wider gap between the pairs does the same separating job.
 *
 * Sizing is driven entirely by measured glyph metrics: the numerals grow until either the height
 * budget or the width budget runs out. Landscape is the primary case (a phone docked on its side),
 * where the width budget binds — and an extra-condensed face is exactly the lever for that case,
 * since narrower numerals buy height out of the same width budget.
 *
 * Layout is by measured INK, not by the font's advance width: the advance carries side bearings —
 * empty columns baked into every glyph box — and laying out by it would spend that emptiness on
 * margin instead of on digit height. The cell is the maximum ink width across all ten numerals (the
 * max, not per-digit: this face is proportional, and fixed cells keep the readout from shuffling
 * sideways every minute), and each digit is drawn centred on its cell by its own ink centre.
 */
class ClockFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val bundled = ResourcesCompat.getFont(context, R.font.sofia_sans_extra_condensed)
        if (bundled != null) {
            typeface = bundled
            // Bold comes from the face's own 'wght' axis, never from Typeface.BOLD: asking a
            // variable font for BOLD gets a synthetic faux-bold smeared on top of the real axis.
            // Set before anything measures, because the weight changes the glyph metrics.
            setFontVariationSettings("'wght' $DIGIT_WEIGHT")
        } else {
            // Resource load failed; fall back to the previous face rather than draw nothing.
            // "sans-serif-monospace" is the family alias for Roboto Mono. Do NOT "fix" this to
            // "monospace": that alias resolves to Droid Sans Mono / Cutive Mono on many builds and
            // is a different face entirely.
            typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
        }
        // LEFT, not CENTER: centring is done per digit on its own ink, not on its advance.
        textAlign = Paint.Align.LEFT
        style = Paint.Style.FILL
    }

    private val textBounds = Rect()

    private val digitCenterX = FloatArray(SLOTS)

    /** Per-digit x correction that puts that digit's ink centre on its cell centre. */
    private val digitInkOffset = FloatArray(10)
    private var baselineY = 0f
    private var laidOut = false

    private val digits = IntArray(SLOTS) { -1 }

    private var digitColor = ContextCompat.getColor(context, R.color.digit_dim)
    private var targetDigitColor = digitColor
    private var digitColorInitialised = false

    private var ticking = false
    private val tickHandler = Handler(Looper.getMainLooper())

    private var burnInEnabled = false
    private var burnInIndex = 0
    private val burnInRadius = BURN_IN_RADIUS_DP * resources.displayMetrics.density
    private var shiftX = 0f
    private var shiftY = 0f
    private var shiftFromX = 0f
    private var shiftFromY = 0f
    private var shiftToX = 0f
    private var shiftToY = 0f

    private val colorAnimator = ValueAnimator().apply {
        duration = COLOR_DURATION_MS
        setEvaluator(ArgbEvaluator())
        addUpdateListener { applyColor(it.animatedValue as Int) }
    }

    private val shiftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = BURN_IN_SLIDE_MS
        addUpdateListener {
            val f = it.animatedValue as Float
            shiftX = shiftFromX + (shiftToX - shiftFromX) * f
            shiftY = shiftFromY + (shiftToY - shiftFromY) * f
            invalidate()
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            applyTime()
            if (burnInEnabled) stepBurnIn()
            scheduleTick()
        }
    }

    init {
        applyColor(digitColor)
    }

    /** Fully opaque ARGB applied to the digits. Animates smoothly to the new colour. */
    fun setDigitColor(color: Int) {
        val opaque = color or OPAQUE_ALPHA
        if (opaque == targetDigitColor && digitColorInitialised) return
        targetDigitColor = opaque
        colorAnimator.cancel()
        if (!digitColorInitialised) {
            // Nothing has been shown yet; ramping up from the placeholder would read as a flash.
            digitColorInitialised = true
            applyColor(opaque)
            return
        }
        colorAnimator.setIntValues(digitColor, opaque)
        colorAnimator.start()
    }

    /** Starts/stops the once-per-minute tick. Call from onStart/onStop. */
    fun startTicking() {
        if (ticking) return
        ticking = true
        applyTime()
        scheduleTick()
    }

    fun stopTicking() {
        if (!ticking) return
        ticking = false
        tickHandler.removeCallbacks(tickRunnable)
        shiftAnimator.cancel()
    }

    /** Nudges the whole face a few px on a slow cycle to spare OLED pixels. */
    fun setBurnInShiftEnabled(enabled: Boolean) {
        if (burnInEnabled == enabled) return
        burnInEnabled = enabled
        shiftAnimator.cancel()
        burnInIndex = 0
        if (enabled) {
            shiftX = burnInRadius
            shiftY = 0f
        } else {
            shiftX = 0f
            shiftY = 0f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTicking()
        colorAnimator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutFace(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (!laidOut) return
        canvas.save()
        canvas.translate(shiftX, shiftY)
        for (i in 0 until SLOTS) {
            val digit = digits[i]
            if (digit < 0) continue
            canvas.drawText(
                DIGITS, digit, 1,
                digitCenterX[i] + digitInkOffset[digit], baselineY, digitPaint,
            )
        }
        canvas.restore()
    }

    private fun layoutFace(w: Int, h: Int) {
        laidOut = false
        if (w <= 0 || h <= 0) return
        val landscape = w > h

        // The burn-in cycle translates the whole face by up to burnInRadius in any direction, so
        // that much is taken off both ends of each axis before anything is sized.
        val availableW = w * (if (landscape) H_INSET_LAND else H_INSET_PORT) - 2f * burnInRadius
        val availableH = h * (if (landscape) V_INSET_LAND else V_INSET_PORT) - 2f * burnInRadius
        if (availableW <= 0f || availableH <= 0f) return

        val faceInCells = SLOTS + 2f * INTRA_GAP_RATIO + PAIR_GAP_RATIO

        // Fit iteratively rather than measure-scale-and-trust. Glyph metrics are NOT exactly
        // proportional to text size — hinting rounds them — so a size derived from a measurement
        // at MEASURE_TEXT_SIZE can come back a little wider once actually applied. Believing that
        // first estimate overflows the screen and clips the outer digits, which is precisely what
        // it did. Each pass re-measures and shrinks by the overshoot; it converges in one or two.
        var cell = 0f
        var top = 0
        var bottom = 0
        digitPaint.textSize = MEASURE_TEXT_SIZE
        var fits = false
        for (pass in 0 until FIT_PASSES) {
            cell = 0f
            top = Int.MAX_VALUE
            bottom = Int.MIN_VALUE
            for (i in 0 until 10) {
                digitPaint.getTextBounds(DIGITS, i, 1, textBounds)
                val ink = (textBounds.right - textBounds.left).toFloat()
                if (ink > cell) cell = ink
                if (textBounds.top < top) top = textBounds.top
                if (textBounds.bottom > bottom) bottom = textBounds.bottom
            }
            val glyphHeight = (bottom - top).toFloat()
            if (cell <= 0f || glyphHeight <= 0f) return

            val widthRatio = availableW / (cell * faceInCells)
            val heightRatio = availableH / glyphHeight
            val ratio = min(widthRatio, heightRatio)
            // Within half a percent of the budget is as close as the hinting grid allows; going
            // round again would only oscillate.
            if (ratio in FIT_TOLERANCE..1f) {
                fits = true
                break
            }
            digitPaint.textSize = (digitPaint.textSize * ratio).coerceAtLeast(1f)
        }
        if (!fits) {
            // Never leave the face larger than the budget: the last pass may have grown it.
            val glyphHeight = (bottom - top).toFloat()
            val ratio = min(availableW / (cell * faceInCells), availableH / glyphHeight)
            if (ratio < 1f) digitPaint.textSize = (digitPaint.textSize * ratio).coerceAtLeast(1f)
        }

        // Final measurement — the cell, the per-digit centring offsets and the vertical centring
        // all come from this pass, so they describe the size actually being drawn.
        cell = 0f
        top = Int.MAX_VALUE
        bottom = Int.MIN_VALUE
        for (i in 0 until 10) {
            digitPaint.getTextBounds(DIGITS, i, 1, textBounds)
            val ink = (textBounds.right - textBounds.left).toFloat()
            if (ink > cell) cell = ink
            if (textBounds.top < top) top = textBounds.top
            if (textBounds.bottom > bottom) bottom = textBounds.bottom
            // Align.LEFT draws the glyph origin at x, so ink spans [x + left, x + right]. Putting
            // that ink centre on the cell centre needs this correction added to the cell centre.
            digitInkOffset[i] = -(textBounds.left + textBounds.right) / 2f
        }
        if (cell <= 0f) return

        val intraGap = cell * INTRA_GAP_RATIO
        val pairGap = cell * PAIR_GAP_RATIO
        val totalW = SLOTS * cell + 2f * intraGap + pairGap
        // totalW is now measured, not predicted, so this margin is real rather than assumed.
        var x = (w - totalW) / 2f + cell / 2f
        digitCenterX[0] = x
        x += cell + intraGap
        digitCenterX[1] = x
        x += cell + pairGap
        digitCenterX[2] = x
        x += cell + intraGap
        digitCenterX[3] = x

        baselineY = h / 2f - (top + bottom) / 2f
        laidOut = true
        invalidate()
    }

    private fun applyColor(color: Int) {
        digitColor = color
        digitPaint.color = color
        invalidate()
    }

    private fun applyTime() {
        val now = LocalTime.now()
        digits[0] = now.hour / 10
        digits[1] = now.hour % 10
        digits[2] = now.minute / 10
        digits[3] = now.minute % 10
        invalidate()
    }

    private fun scheduleTick() {
        val toBoundary = MINUTE_MS - System.currentTimeMillis() % MINUTE_MS
        tickHandler.postDelayed(tickRunnable, toBoundary + TICK_CUSHION_MS)
    }

    private fun stepBurnIn() {
        burnInIndex = (burnInIndex + 1) % BURN_IN_STEPS
        val angle = 2.0 * Math.PI * burnInIndex / BURN_IN_STEPS
        shiftFromX = shiftX
        shiftFromY = shiftY
        shiftToX = (cos(angle) * burnInRadius).toFloat()
        shiftToY = (sin(angle) * burnInRadius).toFloat()
        shiftAnimator.cancel()
        shiftAnimator.start()
    }

    private companion object {
        const val SLOTS = 4
        const val OPAQUE_ALPHA = 0xFF000000.toInt()

        const val MEASURE_TEXT_SIZE = 100f

        /** Value for the face's 'wght' variation axis (min 1, default 400, max 1000). */
        const val DIGIT_WEIGHT = 700

        /** Passes allowed to converge on a size that actually fits; one or two is typical. */
        const val FIT_PASSES = 6
        const val FIT_TOLERANCE = 0.995f

        /**
         * Both gaps are fractions of one digit cell, so spacing scales with the numerals. These are
         * measured between INK, not between advances, so they are the whole of the visible gap —
         * hence smaller numbers than an advance-based layout would want. The pair gap is what
         * separates HH from MM now that the colon is gone, so it stays generous; the intra-pair gap
         * is a hairline.
         *
         * Retuned for the extra-condensed face. A cell is now roughly 0.56 of the digit height
         * where the old monospace cell was roughly 0.67, so the same fraction of a cell is a
         * visibly smaller gap beside numerals this tall: the pair gap had to grow to keep HH and MM
         * reading as two groups. The intra-pair gap shrank instead, because the cell is the widest
         * numeral ('4') while most are far narrower, and that slack already shows up as whitespace
         * between neighbours — spending the width budget on the pair gap buys more separation per
         * pixel of digit height given up.
         */
        const val INTRA_GAP_RATIO = 0.03f
        const val PAIR_GAP_RATIO = 0.33f

        /**
         * 1 - inset is the whole margin budget: half of it survives on each edge at maximum
         * burn-in shift, because 2 * burnInRadius is reserved before sizing.
         */
        const val H_INSET_LAND = 0.995f
        const val V_INSET_LAND = 0.99f
        const val H_INSET_PORT = 0.98f
        const val V_INSET_PORT = 0.96f

        const val COLOR_DURATION_MS = 400L
        const val MINUTE_MS = 60_000L
        /** Handler runs on uptime while the boundary is computed from wall time. */
        const val TICK_CUSHION_MS = 20L

        const val BURN_IN_RADIUS_DP = 4f
        const val BURN_IN_STEPS = 12
        const val BURN_IN_SLIDE_MS = 2_000L

        val DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
    }
}
