package com.diriktv.sidekick.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import com.diriktv.sidekick.R
import kotlin.math.cos
import kotlin.math.pow

/**
 * Covers the main screen while Home loads. It starts exactly where the launch screen leaves off
 * (the logo, centered, on the app's background), so there's no jump. A band of light passes
 * over the logo while it waits, and a small loader fades in only if loading takes a while.
 * [reveal] flies the logo into its place at the top of the side menu as the curtain lifts.
 */
class LaunchCurtain(context: Context) : View(context) {
    private val logo: Drawable = context.getDrawable(R.drawable.splash_logo)!!
    private val still = context.motionOff()
    private val born = SystemClock.uptimeMillis()
    private val bgColor = context.getColor(R.color.bg)
    private val bg = Paint().apply { color = bgColor }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private var shineFor = -1f
    private val shineAt = android.graphics.Matrix()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface2) }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.kick_green) }
    private val bar = RectF()
    private val home = Rect()
    private val now = RectF()
    private var target: RectF? = null
    private var revealAt = -1L
    private var onDone: (() -> Unit)? = null

    init {
        isClickable = true // swallow touches while covering
    }

    /** Fly the logo to [to] (window coordinates; null just fades) and lift the curtain. */
    fun reveal(to: Rect?, done: () -> Unit) {
        if (revealAt >= 0) return
        target = to?.let { RectF(it) }
        onDone = done
        revealAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis()
        val w = width
        val h = height
        // Where the launch screen draws it: centered at its natural size.
        val lw = logo.intrinsicWidth
        val lh = logo.intrinsicHeight
        home.set((w - lw) / 2, (h - lh) / 2, (w + lw) / 2, (h + lh) / 2)

        val p = if (revealAt < 0) 0f else if (still) 1f else ((t - revealAt) / REVEAL_MS.toFloat()).coerceIn(0f, 1f)
        val fly = easeInOut(p)
        val fade = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)

        bg.alpha = (255 * (1 - fade)).toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val to = target
        if (to != null) {
            now.set(mix(home.left.toFloat(), to.left, fly), mix(home.top.toFloat(), to.top, fly),
                mix(home.right.toFloat(), to.right, fly), mix(home.bottom.toFloat(), to.bottom, fly))
        } else {
            now.set(home)
        }
        val logoAlpha = if (to == null) 1 - fade else 1f
        logo.setBounds(now.left.toInt(), now.top.toInt(), now.right.toInt(), now.bottom.toInt())
        logo.alpha = (255 * logoAlpha).toInt()

        // The logo, with a band of light passing over it while it waits.
        val layer = canvas.saveLayer(now.left - 2, now.top - 2, now.right + 2, now.bottom + 2, null)
        logo.draw(canvas)
        if (!still && revealAt < 0) {
            val cycle = ((t - born) % SWEEP_MS) / SWEEP_MS.toFloat()
            val band = now.width() * 0.35f
            val x = now.left - band + (now.width() + band * 2) * easeInOut(cycle)
            // Built for the logo's size once; each frame only slides it along.
            if (shineFor != band) {
                shine.shader = LinearGradient(-band / 2, now.top, band / 2, now.bottom,
                    intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                shineFor = band
            }
            shineAt.setTranslate(x, 0f)
            shine.shader.setLocalMatrix(shineAt)
            canvas.drawRect(now, shine)
        }
        canvas.restoreToCount(layer)

        // A loader under the logo, only if Home is taking a while.
        val waited = t - born
        if (!still && revealAt < 0 && waited > LOADER_AFTER_MS) {
            val a = ((waited - LOADER_AFTER_MS) / 300f).coerceIn(0f, 1f)
            val bw = context.dp(84).toFloat()
            val bh = context.dp(3).toFloat()
            val top = home.bottom + context.dp(28).toFloat()
            bar.set(w / 2f - bw / 2, top, w / 2f + bw / 2, top + bh)
            track.alpha = (255 * a).toInt()
            canvas.drawRoundRect(bar, bh / 2, bh / 2, track)
            // A short dash gliding back and forth along it.
            val k = ((waited % 1200) / 1200f)
            val pos = (1 - cos(k * 2 * Math.PI).toFloat()) / 2
            val dw = bw * 0.3f
            val left = bar.left + (bw - dw) * pos
            dash.alpha = (255 * a).toInt()
            canvas.drawRoundRect(left, top, left + dw, top + bh, bh / 2, bh / 2, dash)
        }

        if (p >= 1f) {
            post {
                onDone?.invoke()
                onDone = null
                (parent as? android.view.ViewGroup)?.removeView(this)
            }
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeInOut(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2

    companion object {
        private const val SWEEP_MS = 1600L
        private const val REVEAL_MS = 700L
        private const val LOADER_AFTER_MS = 900L
    }
}
