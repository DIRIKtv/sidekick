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
 * on the current page (dark glass with a fine edge, like the app's cards) when focus is out in
 * the page, easing to a stop without overshoot.
 */
class NavIndicator @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
    }
    private val edgeColor = context.getColor(R.color.glass_edge)
    /** How much of the resting edge shows: 1 resting, 0 focused (solid green needs no edge). */
    private var edge = 1f
    private val rect = RectF()
    private val focusedColor = context.getColor(R.color.kick_green)
    private val restingColor = context.getColor(R.color.glass)
    private val radius = context.dp(10).toFloat()
    private val argb = ArgbEvaluator()

    private var top = -1f
    private var itemHeight = 0f
    private var itemLeft = 0f
    private var itemRight = -1f
    private var color = restingColor
    private var anim: ValueAnimator? = null

    /** Moves to [item] (a sibling of this view's sibling list, laid out in the same parent). */
    fun moveTo(item: View, focused: Boolean, animate: Boolean = true) {
        val toTop = item.top.toFloat()
        // Items may sit inside a padded list laid over this view: line up with them sideways too.
        val parent = item.parent as? View
        val offsetX = if (parent != null && parent !== this.parent) parent.left.toFloat() else 0f
        itemLeft = offsetX + item.left
        itemRight = offsetX + item.right
        val toColor = if (focused) focusedColor else restingColor
        itemHeight = item.height.toFloat()
        anim?.cancel()
        if (!animate || top < 0 || context.motionOff()) {
            top = toTop
            color = toColor
            edge = if (focused) 0f else 1f
            invalidate()
            return
        }
        val fromTop = top
        val fromColor = color
        val fromEdge = edge
        val toEdge = if (focused) 0f else 1f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val f = it.animatedValue as Float
                top = fromTop + (toTop - fromTop) * f
                color = argb.evaluate(f, fromColor, toColor) as Int
                edge = fromEdge + (toEdge - fromEdge) * f
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
        rect.set(itemLeft, top, if (itemRight > itemLeft) itemRight else width.toFloat(), top + itemHeight)
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (edge > 0f) {
            edgePaint.color = edgeColor
            edgePaint.alpha = (android.graphics.Color.alpha(edgeColor) * edge).toInt()
            val inset = edgePaint.strokeWidth / 2
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, radius - inset, radius - inset, edgePaint)
        }
    }
}
