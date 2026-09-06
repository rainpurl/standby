package org.dalwadi.superclock.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import org.dalwadi.superclock.voice.VoicePhase
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The wake animation: a scrim, a level meter and one line of text, drawn over the
 * clock while the voice pipeline is awake.
 *
 * Everything is driven from a single repeating ticker that exists only while the
 * phase is not [VoicePhase.IDLE]; in IDLE the view draws nothing and is not
 * invalidated at all, so it costs nothing overnight. Every transition is a
 * time-stepped value rather than a discrete animator, which lets a phase change
 * interrupt a fade mid-flight without a visible cut.
 */
class VoiceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val barStroke = dp(2.5f)
    private val barGap = dp(12f)
    private val barHalfMax = dp(16f)
    private val dotHalf = dp(1.25f)
    private val dashHalf = dp(8f)
    private val shakeAmp = dp(3.5f)
    private val textGapNormal = dp(24f)
    private val textGapCompact = dp(16f)
    private val bottomPadNormal = dp(26f)
    private val bottomPadCompact = dp(14f)
    private val sidePad = dp(44f)
    private val lineGap = dp(3f)

    private val scrimPaint = Paint()
    private val vignettePaint = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = barStroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val heardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.05f
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = dp(16.5f)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }

    private val fontMetrics = Paint.FontMetrics()
    private val checkPath = Path()
    private val checkSegment = Path()
    private val checkMeasure = PathMeasure()
    private var checkLength = 0f

    private val barHalf = FloatArray(BAR_COUNT)

    private var centreX = 0f
    private var indicatorY = 0f
    private var textMaxWidth = 0f
    private var heardBaseline = 0f
    private var messageBaseline = 0f
    private var messageLineHeight = 0f
    private var messageMaxLines = 2
    private var vignetteTop = 0f

    private var phase = VoicePhase.IDLE
    private var phaseStart = 0L

    private var heardRaw = ""
    private var messageRaw = ""
    private var heardLine: String? = null
    private val messageLines = arrayOfNulls<String>(2)

    private var tintColor = Color.WHITE
    private var accentColor = ACCENT
    private var warmColor = WARM_GREY
    private var scrimStrength = SCRIM_BASE + SCRIM_GAIN

    /** Linear 0..1 presence; eased on read so a reversal mid-fade stays continuous. */
    private var visibility = 0f
    private var visibilityTarget = 0f
    private var barSpread = 0f
    private var messageMix = 0f
    private var levelTarget = 0f
    private var level = 0f
    private var checkProgress = 0f
    private var failSettle = 0f
    private var clockSeconds = 0f

    private var active = false
    private var lastFrame = 0L

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    fun setPhase(phase: VoicePhase) {
        if (phase == this.phase) return
        this.phase = phase
        phaseStart = AnimationUtils.currentAnimationTimeMillis()
        when (phase) {
            VoicePhase.SUCCESS -> {
                checkProgress = 0f
                messagePaint.color = accentColor
            }
            VoicePhase.FAILURE -> {
                failSettle = 0f
                messagePaint.color = warmColor
            }
            else -> Unit
        }
        if (phase != VoicePhase.IDLE) activate()
    }

    fun setLevel(level: Float) {
        levelTarget = level.coerceIn(0f, 1f)
    }

    fun setHeard(text: String) {
        if (text == heardRaw) return
        heardRaw = text
        layoutHeard()
    }

    fun setMessage(text: String) {
        if (text == messageRaw) return
        messageRaw = text
        layoutMessage()
    }

    fun setTint(color: Int) {
        val opaque = color or ALPHA_MASK
        if (opaque == tintColor) return
        tintColor = opaque
        // Hold the accent at the tint's brightness so a dim room dims it too, rather
        // than leaving a saturated blue burning over grey digits.
        val lum = luminance(opaque).coerceIn(MIN_LUMINANCE, 1f)
        accentColor = clampToLuminance(ACCENT, lum)
        warmColor = clampToLuminance(WARM_GREY, lum)
        scrimStrength = SCRIM_BASE + SCRIM_GAIN * lum
        messagePaint.color = if (phase == VoicePhase.FAILURE) warmColor else accentColor
    }

    /**
     * Stops the frame ticker without waiting for a draw.
     *
     * The self-stop in [onDraw] only runs while the view is actually being drawn, so an
     * overlay left mid-animation when another activity covers the clock would keep
     * asking for frames indefinitely. Idempotent.
     */
    fun pauseAnimation() {
        deactivate()
    }

    /** Restarts the ticker if there is still a non-idle phase left to draw. */
    fun resumeAnimation() {
        if (phase != VoicePhase.IDLE) activate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        centreX = w / 2f
        // A landscape screen is far wider than the text needs; letting the line run the
        // whole width would stretch it into an unreadable ribbon.
        textMaxWidth = (w - 2f * sidePad).coerceIn(dp(80f), dp(TEXT_MAX_DP))

        heardPaint.getFontMetrics(fontMetrics)
        val heardAscent = fontMetrics.ascent
        val heardDescent = fontMetrics.descent
        messagePaint.getFontMetrics(fontMetrics)
        val messageAscent = fontMetrics.ascent
        val messageDescent = fontMetrics.descent
        messageLineHeight = messageDescent - messageAscent + lineGap

        // Landscape on a stand is the normal case, so height is the scarce axis. Below the
        // short-screen threshold the block collapses to one line and hugs the bottom edge
        // rather than sitting at 72% of the height, where it would cut into the digits.
        val compact = h < dp(COMPACT_HEIGHT_DP)
        messageMaxLines = if (compact) 1 else 2
        val gap = if (compact) textGapCompact else textGapNormal
        val bottomPad = if (compact) bottomPadCompact else bottomPadNormal

        val slotHeight =
            (messageDescent - messageAscent) + (messageMaxLines - 1) * messageLineHeight
        val anchored = h - (barHalfMax + gap + slotHeight + bottomPad)
        indicatorY = (if (compact) anchored else (h * 0.72f).coerceAtMost(anchored))
            .coerceAtLeast(barHalfMax + dp(8f))

        // One shared text slot. The transcript is centred on the message's first line so
        // the cross-fade swaps the two in place; a second message line grows downward
        // into space that was already reserved.
        val slotTop = indicatorY + barHalfMax + gap
        messageBaseline = slotTop - messageAscent
        val firstLineCentre = slotTop + (messageDescent - messageAscent) / 2f
        heardBaseline = firstLineCentre - (heardAscent + heardDescent) / 2f

        // Anchor the darkening to the block instead of mid-screen: over a clock face that
        // now fills the height, a mid-screen ramp both muddies the digits and leaves too
        // little contrast down here.
        vignetteTop = (indicatorY - barHalfMax - dp(32f)).coerceAtLeast(0f)
        vignettePaint.shader = LinearGradient(
            0f, vignetteTop, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP,
        )

        checkPath.reset()
        checkPath.moveTo(centreX - dp(7.5f), indicatorY + dp(0.5f))
        checkPath.lineTo(centreX - dp(2.5f), indicatorY + dp(5.5f))
        checkPath.lineTo(centreX + dp(8f), indicatorY - dp(5.5f))
        checkMeasure.setPath(checkPath, false)
        checkLength = checkMeasure.length

        layoutHeard()
        layoutMessage()
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return

        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame).toFloat().coerceIn(0f, 48f)
        lastFrame = now
        advance(now, dt)

        if (visibility <= 0f && visibilityTarget == 0f) {
            deactivate()
            return
        }

        val alpha = decelerate(visibility)
        if (alpha <= 0.002f) return

        scrimPaint.color = Color.BLACK
        scrimPaint.alpha = (255f * scrimStrength * alpha).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        vignettePaint.alpha = (255f * VIGNETTE_ALPHA * alpha).toInt()
        canvas.drawRect(0f, vignetteTop, width.toFloat(), height.toFloat(), vignettePaint)

        val shake = shakeOffset(now)
        canvas.save()
        canvas.translate(shake, 0f)
        canvas.scale(0.94f + 0.06f * alpha, 0.94f + 0.06f * alpha, centreX, indicatorY)
        drawIndicator(canvas, alpha)
        drawText(canvas, alpha)
        canvas.restore()
    }

    private fun advance(now: Long, dt: Float) {
        clockSeconds += dt / 1000f
        if (clockSeconds > CLOCK_WRAP) clockSeconds -= CLOCK_WRAP

        val elapsed = now - phaseStart
        val settled = (phase == VoicePhase.SUCCESS && elapsed > HOLD_SUCCESS_MS) ||
            (phase == VoicePhase.FAILURE && elapsed > HOLD_FAILURE_MS)
        visibilityTarget = if (phase == VoicePhase.IDLE || settled) 0f else 1f
        visibility = if (visibilityTarget > visibility) {
            (visibility + dt / ENTER_MS).coerceAtMost(1f)
        } else {
            (visibility - dt / EXIT_MS).coerceAtLeast(0f)
        }

        val spreadTarget = if (phase == VoicePhase.LISTENING) 1f else 0f
        barSpread = if (spreadTarget > barSpread) {
            (barSpread + dt / SPREAD_MS).coerceAtMost(1f)
        } else {
            (barSpread - dt / SPREAD_MS).coerceAtLeast(0f)
        }

        val mixTarget =
            if (phase == VoicePhase.SUCCESS || phase == VoicePhase.FAILURE) 1f else 0f
        messageMix = if (mixTarget > messageMix) {
            (messageMix + dt / CROSSFADE_MS).coerceAtMost(1f)
        } else {
            (messageMix - dt / CROSSFADE_MS).coerceAtLeast(0f)
        }

        if (phase == VoicePhase.SUCCESS) {
            checkProgress = (checkProgress + dt / CHECK_MS).coerceAtMost(1f)
        }
        if (phase == VoicePhase.FAILURE) {
            failSettle = (failSettle + dt / SETTLE_MS).coerceAtMost(1f)
        }

        // Meters read best rising fast and falling slowly; a symmetric filter reads
        // as lag on the way up and as chatter on the way down.
        val target = if (phase == VoicePhase.LISTENING) levelTarget else 0f
        level = approach(level, target, dt, if (target > level) LEVEL_RISE_MS else LEVEL_FALL_MS)

        for (i in 0 until BAR_COUNT) {
            val breath = 0.08f + 0.045f * sin(clockSeconds * BREATH_RATE + BAR_PHASE[i])
            val amount = (breath + level * BAR_WEIGHT[i]).coerceIn(0f, 1f)
            val want = dotHalf + (barHalfMax - dotHalf) * amount
            barHalf[i] = approach(barHalf[i], want, dt, BAR_TAU_MS)
        }
    }

    private fun drawIndicator(canvas: Canvas, alpha: Float) {
        val collapse = 1f - barSpread
        for (i in 0 until BAR_COUNT) {
            if (i == CENTRE_BAR) continue
            val a = alpha * barSpread
            if (a <= 0.01f) continue
            val half = barHalf[i] + (dotHalf - barHalf[i]) * collapse
            val x = centreX + (i - CENTRE_BAR) * barGap * (0.4f + 0.6f * barSpread)
            strokePaint.color = tintColor
            strokePaint.alpha = (255f * a * 0.8f).toInt()
            canvas.drawLine(x, indicatorY - half, x, indicatorY + half, strokePaint)
        }

        if (phase == VoicePhase.FAILURE) {
            // A collapsed dot and a zero-length dash are the same round cap, so the
            // dot simply stretches sideways with no cross-fade.
            val halfWidth = dotHalf + (dashHalf - dotHalf) * decelerate(failSettle)
            strokePaint.color = warmColor
            strokePaint.alpha = (255f * alpha).toInt()
            canvas.drawLine(
                centreX - halfWidth, indicatorY,
                centreX + halfWidth, indicatorY,
                strokePaint,
            )
            return
        }

        val pulse = 0.5f + 0.5f * sin(clockSeconds * PULSE_RATE)
        val resting = dotHalf * (1f + 1.7f * pulse)
        val half = barHalf[CENTRE_BAR] + (resting - barHalf[CENTRE_BAR]) * collapse
        val dotFade = if (phase == VoicePhase.SUCCESS) {
            1f - (checkProgress * 2.4f).coerceAtMost(1f)
        } else {
            1f - 0.35f * collapse * pulse
        }
        if (dotFade > 0.01f) {
            strokePaint.color = tintColor
            strokePaint.alpha = (255f * alpha * dotFade).toInt()
            canvas.drawLine(centreX, indicatorY - half, centreX, indicatorY + half, strokePaint)
        }

        if (phase == VoicePhase.SUCCESS && checkProgress > 0f && checkLength > 0f) {
            checkSegment.reset()
            checkMeasure.getSegment(0f, checkLength * decelerate(checkProgress), checkSegment, true)
            strokePaint.color = tintColor
            strokePaint.alpha = (255f * alpha).toInt()
            canvas.drawPath(checkSegment, strokePaint)
        }
    }

    /**
     * The transcript and the message belong to different moments, so only one is ever
     * legible: the two halves of [messageMix] are disjoint, which empties the shared slot
     * completely before the incoming string starts to appear.
     */
    private fun drawText(canvas: Canvas, alpha: Float) {
        val heardFade = decelerate((1f - 2f * messageMix).coerceIn(0f, 1f))
        val messageFade = decelerate((2f * messageMix - 1f).coerceIn(0f, 1f))

        val line = heardLine
        if (line != null && heardFade > 0.004f) {
            heardPaint.color = tintColor
            heardPaint.alpha = (255f * alpha * 0.55f * heardFade).toInt()
            canvas.drawText(line, centreX, heardBaseline, heardPaint)
        }
        if (messageFade > 0.004f) {
            val a = (255f * alpha * messageFade).toInt()
            var y = messageBaseline
            for (i in 0 until messageMaxLines) {
                val text = messageLines[i] ?: continue
                messagePaint.alpha = a
                canvas.drawText(text, centreX, y, messagePaint)
                y += messageLineHeight
            }
        }
    }

    private fun shakeOffset(now: Long): Float {
        if (phase != VoicePhase.FAILURE) return 0f
        val t = (now - phaseStart).toFloat()
        if (t >= SHAKE_MS) return 0f
        val p = t / SHAKE_MS
        val decay = (1f - p) * (1f - p)
        return sin(p * SHAKE_CYCLES * TWO_PI) * shakeAmp * decay
    }

    private fun activate() {
        if (!active) {
            active = true
            lastFrame = 0L
            level = 0f
            for (i in barHalf.indices) barHalf[i] = dotHalf
        }
        if (!ticker.isStarted) ticker.start()
        invalidate()
    }

    private fun deactivate() {
        active = false
        lastFrame = 0L
        ticker.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active && !ticker.isStarted) {
            lastFrame = 0L
            ticker.start()
        }
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    private fun layoutHeard() {
        heardLine = if (textMaxWidth <= 0f || heardRaw.isEmpty()) {
            null
        } else {
            ellipsizeStart(heardRaw, heardPaint, textMaxWidth)
        }
    }

    private fun layoutMessage() {
        messageLines[0] = null
        messageLines[1] = null
        if (textMaxWidth <= 0f || messageRaw.isEmpty()) return
        if (messageMaxLines <= 1 || messagePaint.measureText(messageRaw) <= textMaxWidth) {
            messageLines[0] = ellipsizeEnd(messageRaw, messagePaint, textMaxWidth)
            return
        }
        val fits = messagePaint.breakText(
            messageRaw, 0, messageRaw.length, true, textMaxWidth, null,
        )
        if (fits <= 0) {
            messageLines[0] = ellipsizeEnd(messageRaw, messagePaint, textMaxWidth)
            return
        }
        var cut = messageRaw.lastIndexOf(' ', fits - 1)
        if (cut <= 0) cut = fits
        messageLines[0] = messageRaw.substring(0, cut).trimEnd()
        messageLines[1] = ellipsizeEnd(
            messageRaw.substring(cut).trimStart(), messagePaint, textMaxWidth,
        )
    }

    /** Keeps the tail: a partial transcript grows at its end, so that is what matters. */
    private fun ellipsizeStart(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val room = (maxWidth - paint.measureText(ELLIPSIS)).coerceAtLeast(0f)
        val fits = paint.breakText(text, 0, text.length, false, room, null)
        return ELLIPSIS + text.substring(text.length - fits)
    }

    private fun ellipsizeEnd(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val room = (maxWidth - paint.measureText(ELLIPSIS)).coerceAtLeast(0f)
        val fits = paint.breakText(text, 0, text.length, true, room, null)
        return text.substring(0, fits).trimEnd() + ELLIPSIS
    }

    private fun dp(value: Float) = value * density

    private companion object {
        const val BAR_COUNT = 5
        const val CENTRE_BAR = 2
        val BAR_WEIGHT = floatArrayOf(0.42f, 0.78f, 1f, 0.72f, 0.38f)
        val BAR_PHASE = floatArrayOf(0f, 1.7f, 3.4f, 5.1f, 2.2f)

        /** Below this height the block goes single-line and hugs the bottom edge. */
        const val COMPACT_HEIGHT_DP = 440f
        const val TEXT_MAX_DP = 520f

        // The overlay now sits over a clock face that fills the screen, so the scrim has
        // to knock back much brighter digits than it was first tuned against.
        const val SCRIM_BASE = 0.30f
        const val SCRIM_GAIN = 0.34f
        const val VIGNETTE_ALPHA = 0.5f

        const val ENTER_MS = 220f
        const val EXIT_MS = 300f
        const val SPREAD_MS = 260f

        /** Split into two disjoint halves by [drawText], so each string gets half of it. */
        const val CROSSFADE_MS = 340f
        const val CHECK_MS = 420f
        const val SETTLE_MS = 320f
        const val SHAKE_MS = 460f
        const val SHAKE_CYCLES = 3f
        const val HOLD_SUCCESS_MS = 1600L
        const val HOLD_FAILURE_MS = 2400L

        const val LEVEL_RISE_MS = 55f
        const val LEVEL_FALL_MS = 190f
        const val BAR_TAU_MS = 60f
        const val BREATH_RATE = 1.8f
        const val PULSE_RATE = 3.4f
        const val CLOCK_WRAP = 1000f
        const val TWO_PI = 6.2831855f

        const val ALPHA_MASK = 0xFF000000.toInt()
        const val ACCENT = 0xFF4A9EFF.toInt()
        const val WARM_GREY = 0xFFB5A99B.toInt()
        const val MIN_LUMINANCE = 0.10f
        const val ELLIPSIS = "…"

        fun decelerate(t: Float): Float {
            val inv = 1f - t
            return 1f - inv * inv
        }

        fun approach(current: Float, target: Float, dt: Float, tau: Float): Float =
            current + (target - current) * (1f - exp(-dt / tau))

        fun luminance(color: Int): Float =
            (0.2126f * Color.red(color) +
                0.7152f * Color.green(color) +
                0.0722f * Color.blue(color)) / 255f

        /** Scales [color] down so its luminance matches [target]; never brightens it. */
        fun clampToLuminance(color: Int, target: Float): Int {
            val factor = (target / luminance(color).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(color) * factor).roundToInt(),
                (Color.green(color) * factor).roundToInt(),
                (Color.blue(color) * factor).roundToInt(),
            )
        }
    }
}
