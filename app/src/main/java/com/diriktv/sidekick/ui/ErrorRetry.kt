package com.diriktv.sidekick.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.KickErrors

/**
 * A page's error state in the player's style: what went wrong in plain words on the page's status
 * line, and a "Try again" button just below it. The status line must sit in a FrameLayout.
 */
class ErrorRetry(private val status: TextView) {
    private var button: TextView? = null

    fun show(title: String, error: Throwable?, onRetry: () -> Unit) {
        status.text = "$title\n\n${KickErrors.describe(error)}"
        val b = button ?: TextView(status.context, null, 0, R.style.Pill).apply {
            text = "Try again"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_refresh, 0, 0, 0)
            font(AppFonts.Weight.MEDIUM)
            focusZoom(1.05f)
            (status.parent as FrameLayout).addView(this, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, context.dp(40), Gravity.CENTER))
        }.also { button = it }
        // The button stays put (and keeps focus) while it tries; hiding it would throw focus elsewhere.
        b.setOnClickListener { status.text = "$title\n\nTrying again…"; onRetry() }
        b.visibility = View.VISIBLE
        // Just under the message, which is centred on the same spot.
        status.post { b.translationY = status.height / 2f + status.context.dp(34) }
    }

    /** Takes the error away. Returns true if the button had focus, so the page can move it to the content. */
    fun hide(): Boolean {
        val b = button ?: return false
        val hadFocus = b.isFocused
        b.visibility = View.GONE
        return hadFocus
    }

    val showing get() = button?.visibility == View.VISIBLE

    val focusable get() = button?.takeIf { it.visibility == View.VISIBLE }
}
