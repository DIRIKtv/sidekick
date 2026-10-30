package com.diriktv.sidekick.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.diriktv.sidekick.R

/**
 * The one highlight behind the side menu's items. It glides to the focused item (green) or rests
 * on the current page (grey) when focus is out in the page, easing to a stop without overshoot.
 */
class NavIndicator @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val focusedColor = context.getColor(R.color.kick_green)
    private val restingColor = context.getColor(R.color.surface2)
    private val radius = context.dp(10).toFloat()
    private val argb = ArgbEvaluator()

    private var top = -1f
    private var itemHeight = 0f
    private var color = restingColor
    private var anim: ValueAnimator? = null

    /** Moves to [item] (a sibling of this view's sibling list, laid out in the same parent). */
    fun moveTo(item: View, focused: Boolean, animate: Boolean = true) {
        val toTop = item.top.toFloat()
        val toColor = if (focused) focusedColor else restingColor
        itemHeight = item.height.toFloat()
        anim?.cancel()
        if (!animate || top < 0 || context.motionOff()) {
            top = toTop
            color = toColor
            invalidate()
            return
        }
        val fromTop = top
        val fromColor = color
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val f = it.animatedValue as Float
                top = fromTop + (toTop - fromTop) * f
                color = argb.evaluate(f, fromColor, toColor) as Int
                invalidate()
            }
            start()
        }
    }

    // It sits behind the items, so it takes their size rather than asking for room of its own
    // (a plain View would claim all the height on offer and push the rest of the menu down).
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        if (top < 0 || itemHeight <= 0f) return
        paint.color = color
        rect.set(0f, top, width.toFloat(), top + itemHeight)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}
