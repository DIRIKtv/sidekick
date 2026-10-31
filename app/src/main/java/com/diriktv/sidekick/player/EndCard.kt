package com.diriktv.sidekick.player

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.LiveStream
import com.diriktv.sidekick.ui.AppFonts
import com.diriktv.sidekick.ui.Fmt
import com.diriktv.sidekick.ui.Img
import com.diriktv.sidekick.ui.Nav
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.fadeIn
import com.diriktv.sidekick.ui.focusZoom
import com.diriktv.sidekick.ui.font
import com.diriktv.sidekick.ui.loadAvatar
import com.diriktv.sidekick.ui.loadThumb
import com.diriktv.sidekick.ui.setVisible
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What's on when a live stream ends (or the channel is already offline): the channel it raided,
 * else one of your live followed channels, else the most-watched stream in the same category.
 * When a stream ends while you watch it, Up next starts by itself after a short countdown (unless
 * that's turned off in Settings); any key stops it.
 */
internal class EndCard(private val act: PlayerActivity) {
    private class Next(val stream: LiveStream, val reason: String)

    private val root = FrameLayout(act).apply {
        setBackgroundColor(0xF20B0E0F.toInt())
        isClickable = true
    }
    private val column = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val eyebrow = text(13f, AppFonts.Weight.BOLD, act.getColor(R.color.kick_green)).apply { letterSpacing = 0.12f }
    private val heading = text(30f, AppFonts.Weight.BLACK, act.getColor(R.color.text_primary))
    private val sub = text(15f, AppFonts.Weight.REGULAR, act.getColor(R.color.text_secondary))
    private val nextLabel = text(13f, AppFonts.Weight.BOLD, act.getColor(R.color.text_secondary)).apply { letterSpacing = 0.12f }
    private val nextCard = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(act.getColor(R.color.glass))
            setStroke(act.dp(1), act.getColor(R.color.glass_edge))
            cornerRadius = act.dp(22).toFloat()
        }
        setPadding(act.dp(14), act.dp(14), act.dp(22), act.dp(14))
    }
    private val thumb = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = GradientDrawable().apply { setColor(act.getColor(R.color.surface2)); cornerRadius = act.dp(12).toFloat() }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val avatar = ImageView(act).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val nextName = text(20f, AppFonts.Weight.BOLD, act.getColor(R.color.text_primary)).apply { gravity = Gravity.START }
    private val nextTitle = text(15f, AppFonts.Weight.REGULAR, act.getColor(R.color.text_secondary)).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.START
    }
    private val nextMeta = text(14f, AppFonts.Weight.MEDIUM, act.getColor(R.color.kick_green)).apply { gravity = Gravity.START }
    /** Drains from full to empty over the countdown. */
    private val countdownBar = View(act).apply {
        background = GradientDrawable().apply { setColor(act.getColor(R.color.kick_green)); cornerRadius = act.dp(2).toFloat() }
        pivotX = 0f
    }
    private val buttons = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
    }
    private val watch = pill(R.drawable.ic_play)
    private val open = pill(R.drawable.ic_account)
    private val stay = pill(R.drawable.ic_close)

    private var next: Next? = null
    /** The stream finished while being watched (rather than being offline when opened). */
    private var ended = false
    private var load: Job? = null
    private var secondsLeft = 0
    private val tick = object : Runnable {
        override fun run() {
            secondsLeft--
            if (secondsLeft <= 0) { go(); return }
            showCountdown()
            root.postDelayed(this, 1000)
        }
    }

    var showing = false
        private set

    init {
        val textCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(nextName)
            addView(nextTitle, LinearLayout.LayoutParams(act.dp(380), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(4) })
            addView(nextMeta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(8) })
        }
        val thumbFrame = FrameLayout(act).apply {
            addView(thumb, FrameLayout.LayoutParams(act.dp(256), act.dp(144)))
            addView(avatar, FrameLayout.LayoutParams(act.dp(56), act.dp(56), Gravity.CENTER))
        }
        nextCard.addView(thumbFrame)
        nextCard.addView(textCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = act.dp(20) })

        column.addView(eyebrow)
        column.addView(heading, wrap(top = 8))
        column.addView(sub, wrap(top = 6))
        column.addView(nextLabel, wrap(top = 34))
        column.addView(nextCard, wrap(top = 12))
        column.addView(countdownBar, LinearLayout.LayoutParams(act.dp(300), act.dp(4)).apply { topMargin = act.dp(14) })
        column.addView(buttons, wrap(top = 26))
        buttons.addView(watch)
        buttons.addView(open)
        buttons.addView(stay)
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        watch.setOnClickListener { go() }
        open.setOnClickListener { stopCountdown(); Nav.channel(act, act.slug); act.finish() }
        stay.setOnClickListener { stay() }
    }

    /**
     * The stream on [slug] isn't live: [ended] when it finished while being watched, rather than
     * being offline when opened. [raid] is where it sent its viewers, if it did.
     */
    fun show(ended: Boolean, raid: com.diriktv.sidekick.data.RaidTarget?) {
        this.ended = ended
        val name = act.channel?.username ?: act.slug
        eyebrow.text = if (ended) "STREAM ENDED" else "OFFLINE"
        heading.text = if (ended) "$name has gone offline" else "$name isn't live right now"
        val streamed = act.streamedFor()
        sub.text = when {
            ended && streamed != null -> "Thanks for watching. They streamed for $streamed."
            ended -> "Thanks for watching."
            else -> "Check back later, or catch up on a past broadcast."
        }
        sub.setVisible(true)
        open.text = "Open $name"
        stay.text = "Stay here"
        nextLabel.setVisible(false)
        nextCard.setVisible(false)
        countdownBar.setVisible(false)
        watch.setVisible(false)
        next = null

        if (!showing) {
            showing = true
            act.findViewById<ViewGroup>(android.R.id.content).addView(root,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(260).start()
            column.fadeIn(320, fromY = act.dp(16).toFloat())
        }
        open.requestFocus()

        load?.cancel()
        load = act.lifecycleScope.launch {
            val found = runCatching { pick(raid) }.getOrNull() ?: return@launch
            if (!showing) return@launch
            showNext(found)
        }
    }

    private suspend fun pick(raid: com.diriktv.sidekick.data.RaidTarget?): Next? {
        val here = act.slug.lowercase()
        raid?.let { r ->
            val c = runCatching { KickApi.channel(r.slug) }.getOrNull()
            if (c != null && c.isLive) {
                val s = LiveStream(c.slug, c.username, c.liveTitle.orEmpty(), c.liveViewers, null, c.avatar, c.liveCategory, null, c.liveStartTime)
                return Next(s, "${act.channel?.username ?: "They"} raided ${c.username}")
            }
        }
        val followed = runCatching { Follows.liveNow(act) }.getOrDefault(emptyList())
            .filter { it.slug.lowercase() != here && !act.prefs.isHiddenCategory(it.category) }
        followed.maxByOrNull { it.viewers }?.let { return Next(it, "Live from your Following") }
        val top = KickApi.livestreams(language = act.prefs.language.ifEmpty { null }, limit = 24).items
            .filter { it.slug.lowercase() != here && !act.prefs.isHiddenCategory(it.category) }
        val category = act.channel?.liveCategory
        top.firstOrNull { category != null && it.category.equals(category, true) }?.let { return Next(it, "Popular in $category") }
        return top.firstOrNull()?.let { Next(it, "Popular on Kick right now") }
    }

    private fun showNext(n: Next) {
        next = n
        val s = n.stream
        nextLabel.text = "UP NEXT  ·  ${n.reason.uppercase()}"
        nextName.text = s.username
        nextTitle.text = Fmt.cleanTitle(s.title)
        nextTitle.setVisible(s.title.isNotBlank())
        nextMeta.text = listOfNotNull("● LIVE", if (s.viewers > 0) Fmt.viewers(s.viewers) else null, s.category).joinToString("  ·  ")
        // Kick's channel lookup has no usable picture of the stream; show the streamer instead.
        if (s.thumbnail != null) {
            thumb.loadThumb(Img.thumb(s.thumbnail))
            avatar.setVisible(false)
        } else {
            thumb.setImageDrawable(null)
            avatar.setVisible(true)
            avatar.loadAvatar(s.avatar, s.username)
        }
        watch.text = "Watch ${s.username}"
        nextLabel.setVisible(true)
        nextCard.setVisible(true)
        watch.setVisible(true)
        nextLabel.fadeIn(220)
        nextCard.fadeIn(260, fromY = act.dp(10).toFloat())
        watch.requestFocus()
        // Only when a stream ends under you: a channel opened while offline was chosen on purpose.
        if (ended && act.prefs.upNextAutoplay && !act.inPip) startCountdown()
    }

    private fun startCountdown() {
        secondsLeft = COUNTDOWN_S
        countdownBar.setVisible(true)
        countdownBar.scaleX = 1f
        countdownBar.animate().scaleX(0f).setDuration(COUNTDOWN_S * 1000L)
            .setInterpolator(android.view.animation.LinearInterpolator()).start()
        showCountdown()
        root.postDelayed(tick, 1000)
    }

    private fun showCountdown() {
        val s = next?.stream ?: return
        watch.text = "Watch ${s.username} in $secondsLeft"
    }

    /** A key was pressed while the card is up: the countdown stops, so nothing happens behind your back. */
    fun onKey() {
        if (secondsLeft > 0) stopCountdown()
    }

    private fun stopCountdown() {
        if (secondsLeft <= 0) return
        secondsLeft = 0
        root.removeCallbacks(tick)
        countdownBar.animate().cancel()
        countdownBar.setVisible(false)
        next?.stream?.let { watch.text = "Watch ${it.username}" }
    }

    private fun go() {
        val s = next?.stream ?: return
        stopCountdown()
        hide()
        Nav.watchLive(act, s.slug)
    }

    /** Back or "Stay here": the card goes and the plain "stream has ended" note stays. */
    fun stay() {
        stopCountdown()
        hide()
        act.showOfflineNote(ended)
    }

    fun hide() {
        if (!showing) return
        showing = false
        load?.cancel()
        root.removeCallbacks(tick)
        countdownBar.animate().cancel()
        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun text(size: Float, weight: AppFonts.Weight, color: Int) = TextView(act).apply {
        textSize = size
        font(weight)
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun pill(icon: Int) = TextView(act, null, 0, R.style.Pill).apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(44)).apply { marginEnd = act.dp(14) }
        focusZoom(1.05f)
    }

    private fun wrap(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = act.dp(top) }

    private companion object {
        const val COUNTDOWN_S = 10
    }
}
