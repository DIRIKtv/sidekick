package com.diriktv.sidekick.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.diriktv.sidekick.R

/**
 * Grey outlines of what's about to load, with a band of light sweeping across them, in place of
 * a spinner. [HOME] sketches the banner and a couple of rows; a grid sketches [columns] cards of
 * the given shape. It fades out when hidden.
 */
@android.annotation.SuppressLint("ViewConstructor") // only ever built in code, never from XML
class SkeletonView(
    context: Context,
    private val columns: Int = 0,
    /** Card height / width for grids. */
    private val ratio: Float = 9f / 16f,
    /** Grids of video cards get two text lines under each picture. */
    private val captions: Boolean = true,
) : View(context) {
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private var sheenFor = -1f
    private val sheenAt = android.graphics.Matrix()
    private val r = RectF()
    private val still = context.motionOff()
    private val born = SystemClock.uptimeMillis()
    private val corner = context.dp(10).toFloat()

    override fun setVisibility(visibility: Int) {
        if (visibility == VISIBLE) {
            animate().cancel()
            alpha = 1f
            super.setVisibility(VISIBLE)
        } else if (getVisibility() == VISIBLE && isAttachedToWindow && !still) {
            animate().alpha(0f).setDuration(220).withEndAction { super.setVisibility(visibility) }.start()
        } else {
            super.setVisibility(visibility)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        if (columns == 0) drawHome(canvas, w, h) else drawGrid(canvas, w, h)
        if (!still) {
            // A diagonal band of light every 1.6 seconds.
            val t = ((SystemClock.uptimeMillis() - born) % 1600) / 1600f
            val band = w * 0.25f
            val x = -band + (w + band * 2) * t
            // Built for this size once; each frame only slides it along.
            if (sheenFor != w + h) {
                sheen.shader = LinearGradient(-band, 0f, band, h * 0.3f,
                    intArrayOf(0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                sheenFor = w + h
            }
            sheenAt.setTranslate(x, 0f)
            sheen.shader.setLocalMatrix(sheenAt)
            canvas.drawRect(0f, 0f, w, h, sheen)
            postInvalidateOnAnimation()
        }
        canvas.restoreToCount(layer)
    }

    private fun box(canvas: Canvas, l: Float, t: Float, rr: Float, b: Float, radius: Float = corner) {
        r.set(l, t, rr, b)
        canvas.drawRoundRect(r, radius, radius, shape)
    }

    /** The banner, then two rows of cards with a title above each. */
    private fun drawHome(canvas: Canvas, w: Float, h: Float) {
        val pad = context.dp(36).toFloat()
        var y = context.dp(28).toFloat()
        val heroH = context.dp(384).toFloat()
        box(canvas, pad, y, w - pad, y + heroH, context.dp(22).toFloat())
        y += heroH + context.dp(34)
        val cardW = context.dp(200).toFloat()
        val gap = context.dp(10).toFloat()
        repeat(2) {
            box(canvas, pad, y, pad + context.dp(150), y + context.dp(16), context.dp(8).toFloat())
            y += context.dp(30)
            var x = pad
            while (x < w) {
                box(canvas, x, y, x + cardW, y + cardW * 9 / 16)
                box(canvas, x, y + cardW * 9 / 16 + context.dp(10), x + cardW * 0.8f, y + cardW * 9 / 16 + context.dp(24), context.dp(6).toFloat())
                x += cardW + gap
            }
            y += cardW * 9 / 16 + context.dp(70)
            if (y > h) return
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val pad = context.dp(29).toFloat()
        val gap = context.dp(10).toFloat()
        val cardW = (w - pad * 2 - gap * (columns - 1)) / columns
        val cardH = cardW * ratio
        var y = context.dp(30).toFloat()
        box(canvas, pad, y, pad + context.dp(170), y + context.dp(18), context.dp(8).toFloat())
        y += context.dp(36)
        while (y < h) {
            for (c in 0 until columns) {
                val x = pad + c * (cardW + gap)
                box(canvas, x, y, x + cardW, y + cardH)
                box(canvas, x, y + cardH + context.dp(9), x + cardW * 0.75f, y + cardH + context.dp(23), context.dp(6).toFloat())
                if (captions) box(canvas, x, y + cardH + context.dp(29), x + cardW * 0.5f, y + cardH + context.dp(40), context.dp(6).toFloat())
            }
            y += cardH + context.dp(if (captions) 64 else 50)
        }
    }
}
