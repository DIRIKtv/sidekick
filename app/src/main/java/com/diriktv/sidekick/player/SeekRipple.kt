package com.diriktv.sidekick.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import com.diriktv.sidekick.ui.AppFonts
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.motionOff
import kotlin.math.abs
import kotlin.math.pow

/**
 * Seeking with ◀ ▶: a ripple spreads in from that side of the picture with three chevrons
 * lighting up in turn and the total jump ("+30s") beside them, building as you keep pressing.
 */
internal class SeekRipple(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = context.dp(22).toFloat()
        typeface = AppFonts.get(context, AppFonts.Weight.BOLD)
        setShadowLayer(context.dp(6).toFloat(), 0f, 0f, 0x99000000.toInt())
    }
    private val path = Path()
    private val still = context.motionOff()
    private var side = 1
    private var pressedAt = -1L
    private var text = ""

    /** A press towards [direction] (-1 back, 1 forward); [totalMs] is the jump so far. */
    fun pulse(direction: Int, totalMs: Long, atLive: Boolean = false) {
        if (still) return
        side = if (direction < 0) -1 else 1
        pressedAt = SystemClock.uptimeMillis()
        val s = abs(totalMs) / 1000
        text = if (atLive) "Live" else (if (totalMs < 0) "−" else "+") + if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (pressedAt < 0) return
        val age = SystemClock.uptimeMillis() - pressedAt
        if (age > LINGER_MS) { pressedAt = -1; return }
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = if (side > 0) w else 0f
        val cy = h / 2

        // The ripple: a disc spreading from the edge, fading as it goes.
        val t = (age / 520f).coerceIn(0f, 1f)
        val grow = 1 - (1 - t).pow(3)
        ring.alpha = (46 * (1 - t)).toInt() + 8
        canvas.drawCircle(cx, cy, h * (0.32f + 0.38f * grow), ring)

        // Three chevrons, lighting up one after another towards the direction of travel.
        val fade = if (age > LINGER_MS - 250) (LINGER_MS - age) / 250f else 1f
        val size = context.dp(14).toFloat()
        val gap = context.dp(16).toFloat()
        val baseX = if (side > 0) w - h * 0.2f - gap * 2 else h * 0.2f
        for (i in 0 until 3) {
            val lit = ((age / 110) % 4).toInt()
            val k = if (side > 0) i else 2 - i
            chevron.alpha = ((if (k == lit) 255 else 110) * fade).toInt()
            val x = baseX + i * gap
            path.reset()
            path.moveTo(x - side * size / 2, cy - size)
            path.lineTo(x + side * size / 2, cy)
            path.lineTo(x - side * size / 2, cy + size)
            path.close()
            canvas.drawPath(path, chevron)
        }
        label.alpha = (255 * fade).toInt()
        canvas.drawText(text, baseX + gap, cy + size + context.dp(34), label)
        postInvalidateOnAnimation()
    }

    companion object {
        private const val LINGER_MS = 900L
    }
}
