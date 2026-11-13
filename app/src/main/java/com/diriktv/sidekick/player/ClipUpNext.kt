package com.diriktv.sidekick.player

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Clip
import com.diriktv.sidekick.ui.AppFonts
import com.diriktv.sidekick.ui.Fmt
import com.diriktv.sidekick.ui.Nav
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.font
import com.diriktv.sidekick.ui.loadThumb

/**
 * A clip finished: the next one from the row it was opened from, in a small card in the corner
 * that counts down and then plays it. OK plays it now; Back stays on the finished clip.
 */
internal class ClipUpNext(private val act: PlayerActivity, stage: FrameLayout) {
    private companion object {
        const val COUNTDOWN_S = 6
    }

    private val card = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(act.getColor(R.color.glass))
            setStroke(act.dp(1), act.getColor(R.color.glass_edge))
            cornerRadius = act.dp(18).toFloat()
        }
        setPadding(act.dp(12), act.dp(12), act.dp(18), act.dp(12))
        visibility = View.GONE
        elevation = act.dp(8).toFloat()
    }
    private val thumb = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = GradientDrawable().apply { setColor(act.getColor(R.color.surface2)); cornerRadius = act.dp(10).toFloat() }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val label = TextView(act).apply {
        textSize = 12f
        letterSpacing = 0.1f
        font(AppFonts.Weight.BOLD)
        setTextColor(act.getColor(R.color.kick_green))
    }
    private val title = TextView(act).apply {
        textSize = 16f
        font(AppFonts.Weight.BOLD)
        setTextColor(act.getColor(R.color.text_primary))
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val meta = TextView(act).apply {
        textSize = 13f
        font(AppFonts.Weight.REGULAR)
        setTextColor(act.getColor(R.color.text_secondary))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        text = ""
    }
    private val hint = TextView(act).apply {
        textSize = 12f
        font(AppFonts.Weight.MEDIUM)
        setTextColor(act.getColor(R.color.text_secondary))
        text = "OK  play now     Back  stay here"
    }

    private var next: Clip? = null
    private var secondsLeft = 0
    private val tick = object : Runnable {
        override fun run() {
            secondsLeft--
            if (secondsLeft <= 0) return play()
            label.text = "UP NEXT IN $secondsLeft"
            act.ui.postDelayed(this, 1000)
        }
    }

    val showing get() = card.visibility == View.VISIBLE

    init {
        val text = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(title, LinearLayout.LayoutParams(act.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(4) })
            addView(meta, LinearLayout.LayoutParams(act.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(2) })
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(8) })
        }
        card.addView(thumb, LinearLayout.LayoutParams(act.dp(176), act.dp(99)))
        card.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = act.dp(16) })
        stage.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(act.dp(40), act.dp(40), act.dp(40), act.dp(40))
        })
    }

    /** Offers [clip] after the one that just ended. */
    fun show(clip: Clip) {
        next = clip
        thumb.loadThumb(clip.thumbnail)
        title.text = clip.title.ifBlank { "Clip" }
        meta.text = listOf(clip.channelName, "${Fmt.count(clip.views)} views", Fmt.time(clip.durationMs)).filter { it.isNotEmpty() }.joinToString(" • ")
        secondsLeft = COUNTDOWN_S
        label.text = "UP NEXT IN $secondsLeft"
        card.alpha = 0f
        card.translationX = act.dp(24).toFloat()
        card.visibility = View.VISIBLE
        card.animate().alpha(1f).translationX(0f).setDuration(220).start()
        act.ui.removeCallbacks(tick)
        act.ui.postDelayed(tick, 1000)
    }

    fun hide() {
        act.ui.removeCallbacks(tick)
        next = null
        card.visibility = View.GONE
    }

    fun play() {
        val clip = next ?: return hide()
        hide()
        Nav.watchClip(act, clip)
    }
}
