package com.diriktv.sidekick.ui

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import com.diriktv.sidekick.R
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Following someone: the button pops and a ring of green sparks flies out around it. */
object Burst {
    /** [rest] is the size the button settles back to (a focused button is slightly zoomed). */
    fun at(anchor: View, rest: Float = anchor.scaleX) {
        val act = anchor.context as? Activity ?: return
        if (act.motionOff()) return
        anchor.animate().cancel()
        anchor.scaleX = rest * 0.8f
        anchor.scaleY = rest * 0.8f
        anchor.animate().scaleX(rest).scaleY(rest).setDuration(420).setInterpolator(OvershootInterpolator(4f)).start()

        val decor = act.window.decorView as ViewGroup
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val sparks = Sparks(act, loc[0] + anchor.width / 2f, loc[1] + anchor.height / 2f, anchor.width * 0.55f)
        decor.addView(sparks, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private class Sparks(act: Activity, val cx: Float, val cy: Float, val start: Float) : View(act) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = act.getColor(R.color.kick_green) }
        private val born = SystemClock.uptimeMillis()
        private val count = 10
        private val reach = act.dp(34).toFloat()
        private val dot = act.dp(4).toFloat()

        override fun onDraw(canvas: Canvas) {
            val t = ((SystemClock.uptimeMillis() - born) / 620f).coerceIn(0f, 1f)
            val out = 1 - (1 - t).pow(3)
            for (i in 0 until count) {
                // Alternate long and short spokes so the ring doesn't look stamped out.
                val angle = (i / count.toFloat()) * 2 * Math.PI - Math.PI / 2
                val len = start + reach * out * (if (i % 2 == 0) 1f else 0.7f)
                paint.alpha = (255 * (1 - t)).toInt()
                val r = dot * (1 - t * 0.6f) * (if (i % 2 == 0) 1f else 0.75f)
                canvas.drawCircle(cx + (cos(angle) * len).toFloat(), cy + (sin(angle) * len).toFloat(), r, paint)
            }
            if (t < 1f) postInvalidateOnAnimation() else post { (parent as? ViewGroup)?.removeView(this) }
        }
    }
}
