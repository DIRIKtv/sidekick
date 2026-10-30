package com.diriktv.sidekick.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.diriktv.sidekick.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The update download bar. The fill glides to each new value instead of stepping, a band of
 * light keeps sweeping across what's done, and the leading edge carries a soft glow. When the
 * download completes the bar draws itself in to a circle and a check is drawn inside it.
 */
class DownloadMeter @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private val still = context.motionOff()
    private val green = context.getColor(R.color.kick_green)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface2) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waiting = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF000000.toInt()
    }
    private val rect = RectF()
    private val checkPath = Path()
    private val checkPart = Path()
    private val checkMeasure = PathMeasure()
    private val clock = FrameClock()
    private val born = SystemClock.uptimeMillis()

    private var target = 0f
    private var shown = 0f
    private var velocity = 0f
    /** When the bar started closing up into the check, or -1. */
    private var doneAt = -1L
    /** The fill as drawn (0..1), each frame, so a percentage can count up with the bar. */
    var onDisplay: ((Float) -> Unit)? = null
    private var reported = -1f

    var failed = false
        set(v) { field = v; fill.color = if (v) context.getColor(R.color.live_red) else green; invalidate() }

    /** 0..1 */
    fun setProgress(p: Float) {
        target = p.coerceIn(0f, 1f)
        if (still) shown = target
        invalidate()
    }

    fun complete() {
        target = 1f
        if (doneAt < 0) doneAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun reset() {
        target = 0f; shown = 0f; velocity = 0f; doneAt = -1; failed = false
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Room above and below the bar for the circle it becomes.
        setMeasuredDimension(resolveSize(context.dp(360), widthMeasureSpec), resolveSize(context.dp(44), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val barH = context.dp(12).toFloat()
        val cy = h / 2

        // The fill follows the download on a spring, so it never jumps.
        if (!still) {
            val dt = clock.tick() * 60f
            velocity += (target - shown) * 0.12f * dt
            velocity *= 0.7f.pow(dt)
            shown = (shown + velocity * dt).coerceIn(0f, 1f)
            if (kotlin.math.abs(target - shown) < 0.001f && kotlin.math.abs(velocity) < 0.001f) shown = target
        }

        if (shown != reported) {
            reported = shown
            onDisplay?.invoke(shown)
        }

        // Finishing: the full bar draws in to a circle in the middle.
        val close = if (doneAt < 0 || shown < 0.995f) 0f else ease(((now - doneAt) / 520f).coerceIn(0f, 1f))
        val circle = h
        val left = mix(0f, (w - circle) / 2, close)
        val right = mix(w, (w + circle) / 2, close)
        val half = mix(barH / 2, circle / 2, close)
        rect.set(left, cy - half, right, cy + half)

        if (close < 1f) canvas.drawRoundRect(rect, half, half, track)

        // Before the first bytes arrive, a faint pulse glides along the empty track so it
        // doesn't look stuck.
        if (target < 0.01f && doneAt < 0 && !failed && !still) {
            val k = ((now - born) % 1300) / 1300f
            val pos = (1 - kotlin.math.cos(k * 2 * Math.PI).toFloat()) / 2
            val dw = (right - left) * 0.22f
            val x0 = left + (right - left - dw) * pos
            waiting.color = (green and 0xFFFFFF) or (0x55 shl 24)
            canvas.drawRoundRect(x0, cy - half, x0 + dw, cy + half, half, half, waiting)
        }
        val fillRight = if (close > 0f) right else left + (right - left) * shown
        if (fillRight > left + 1) {
            rect.set(left, cy - half, max(fillRight, left + half * 2), cy + half)
            canvas.drawRoundRect(rect, half, half, fill)

            if (close == 0f && !still && !failed) {
                // A band of light sweeps across what's been downloaded.
                val span = fillRight - left
                val t = ((now - born) % 1500) / 1500f
                val bandW = max(context.dp(60).toFloat(), span * 0.35f)
                val x = left - bandW + (span + bandW * 2) * t
                sheen.shader = LinearGradient(x - bandW / 2, 0f, x + bandW / 2, 0f,
                    intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                canvas.save()
                canvas.clipRect(left, cy - half, fillRight, cy + half)
                canvas.drawRoundRect(rect, half, half, sheen)
                canvas.restore()

                // A soft glow riding the leading edge.
                val r = barH * 1.6f
                glow.shader = RadialGradient(fillRight - half, cy, r, intArrayOf((green and 0xFFFFFF) or (0x88 shl 24), green and 0xFFFFFF), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(fillRight - half, cy, r, glow)
            }
        }

        // The check draws itself once the circle has formed.
        if (close >= 1f) {
            val k = ease(((now - doneAt - 520) / 380f).coerceIn(0f, 1f))
            val u = circle / 24f
            val ox = w / 2 - 12 * u
            val oy = cy - 12 * u
            checkPath.reset()
            checkPath.moveTo(ox + 7 * u, oy + 12.5f * u)
            checkPath.lineTo(ox + 10.5f * u, oy + 16 * u)
            checkPath.lineTo(ox + 17 * u, oy + 8.5f * u)
            checkMeasure.setPath(checkPath, false)
            checkPart.reset()
            checkMeasure.getSegment(0f, checkMeasure.length * k, checkPart, true)
            check.strokeWidth = 2.6f * u
            canvas.drawPath(checkPart, check)
        }

        val settling = shown != target || (doneAt >= 0 && now - doneAt < 1000)
        if (!still && (settling || (close == 0f && !failed))) postInvalidateOnAnimation() else clock.reset()
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun ease(t: Float) = 1 - (1 - t).pow(3)
}
