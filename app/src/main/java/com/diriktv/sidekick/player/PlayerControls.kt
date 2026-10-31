package com.diriktv.sidekick.player

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.ui.Fmt
import com.diriktv.sidekick.ui.AppFonts
import com.diriktv.sidekick.ui.RollingCounter
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.fadeIn
import com.diriktv.sidekick.ui.fadeOut
import com.diriktv.sidekick.ui.loadAvatar
import com.diriktv.sidekick.ui.setVisible
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The controls overlay: header, seek bar, buttons and their tooltip, plus the on-screen notices. */
internal class PlayerControls(private val act: PlayerActivity) {
    companion object {
        private const val HIDE_DELAY_MS = 5000L
    }

    private val player get() = act.player
    private val prefs = act.prefs

    private val root: View = act.findViewById(R.id.controls)
    private val bottomBar: View = act.findViewById(R.id.bottomBar)
    private val osd: TextView = act.findViewById(R.id.osd)
    private val seekBar: SeekBar = act.findViewById(R.id.seekBar)
    private val position: TextView = act.findViewById(R.id.position)
    private val durationText: TextView = act.findViewById(R.id.durationText)
    private val tooltip: TextView = act.findViewById(R.id.tooltip)
    private val clock: TextView = act.findViewById(R.id.clock)
    private val btnLive: View = act.findViewById(R.id.btnLive)
    private val liveLabel: TextView = act.findViewById(R.id.liveLabel)
    private val liveSep: View = act.findViewById(R.id.liveSep)
    private val viewerCount: RollingCounter = act.findViewById<RollingCounter>(R.id.viewerCount).apply {
        weight = AppFonts.Weight.MEDIUM
        textColor = act.getColor(R.color.text_primary)
    }
    private val btnRewind: ImageButton = act.findViewById(R.id.btnRewind)
    val btnPlay: ImageButton = act.findViewById(R.id.btnPlay)
    private val btnForward: ImageButton = act.findViewById(R.id.btnForward)
    private val btnCaptions: ImageButton = act.findViewById(R.id.btnCaptions)
    val btnChat: ImageButton = act.findViewById(R.id.btnChat)
    val btnFollow: ImageButton = act.findViewById(R.id.btnFollow)
    private val btnPhone: ImageButton = act.findViewById(R.id.btnPhone)
    private val btnProfile: ImageButton = act.findViewById(R.id.btnProfile)
    private val btnQuality: TextView = act.findViewById(R.id.btnQuality)
    private var qualityLabel = "Auto"
    private val mini: android.widget.ProgressBar = act.findViewById(R.id.miniProgress)
    private val btnSettings: ImageButton = act.findViewById(R.id.btnSettings)
    private val followedHeart: View = act.findViewById(R.id.followedHeart)
    private val seekBubble: View = act.findViewById(R.id.seekBubble)
    private val seekTime: TextView = act.findViewById(R.id.seekTime)
    private val seekPreview = SeekPreview(act, act.findViewById(R.id.seekPreview))

    /** Logical controls state; the view itself may still be fading out. */
    var up = false
        private set

    private var compact: Boolean? = null
    private val hideRunnable = Runnable { if (player.isPlaying && !act.menu.isShowing) hide() else scheduleHide() }
    private val osdHide = Runnable { osd.fadeOut(200) }
    private val tooltipLabels: Map<View, () -> String>

    init {
        val transport = act.findViewById<View>(R.id.transport)
        (transport.parent as View).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Fit the quality label to the room first; if it changes, the row is laid out again
            // straight away, and only then do the play buttons need to know where they go.
            if (fitQualityNeeded()) transport.post { updateQuality() } else keepTransportClear(transport)
        }
        btnPlay.setOnClickListener { act.togglePlayPause() }
        btnRewind.setOnClickListener { act.queueSeek(-act.seekStep(), 0) }
        btnForward.setOnClickListener { act.queueSeek(act.seekStep(), 0) }
        btnLive.setOnClickListener { act.goLive() }
        btnSettings.setOnClickListener { act.menu.show() }
        btnCaptions.setOnClickListener { act.menu.show(act.menu.captionsPage()) }
        btnChat.setOnClickListener {
            act.chat.wanted = !act.chat.wanted
            if (act.mode != PlayerActivity.Mode.VOD) prefs.chatEnabled = act.chat.wanted
            act.chat.applyLayout(animate = true)
            updateTooltip(btnChat)
        }
        btnFollow.setOnClickListener { act.toggleFollow() }
        btnPhone.setOnClickListener { PhoneChatDialog.show(act) }
        btnQuality.setOnClickListener { act.menu.show(act.menu.qualityPage()) }
        btnProfile.setOnClickListener {
            // Same as Settings → Go to channel: the channel page replaces the player.
            com.diriktv.sidekick.ui.Nav.channel(act, act.slug)
            act.finish()
        }

        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { act.queueSeek(-act.seekStep(), event.repeatCount); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { act.queueSeek(act.seekStep(), event.repeatCount); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { act.togglePlayPause(); true }
                else -> false
            }
        }

        tooltipLabels = mapOf(
            btnRewind to { "Back ${prefs.seekStepSeconds}s" },
            btnPlay to { if (player.playWhenReady) "Pause" else "Play" },
            btnForward to { "Forward ${prefs.seekStepSeconds}s" },
            btnLive to { if (act.isAtLive()) "You're live" else "Jump to live" },
            btnCaptions to { "Captions • ${act.menu.currentCaptionLabel()}" },
            btnChat to { if (act.chat.wanted) "Hide chat" else "Show chat" },
            btnFollow to { if (Follows.isFollowing(act, act.slug)) "Unfollow" else "Follow" },
            btnPhone to { "Chat from your phone" },
            btnQuality to { "Quality • $qualityLabel" },
            btnProfile to { "Go to ${act.channel?.username ?: "channel"}" },
            btnSettings to { "Settings" },
        )
        tooltipLabels.keys.forEach { v ->
            v.setOnFocusChangeListener { view, has -> if (has) updateTooltip(view) }
        }
        seekBubble.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (seekBubble.visibility == View.VISIBLE) positionBubble() }
        seekBar.setOnFocusChangeListener { _, has ->
            if (has) tooltip.visibility = View.INVISIBLE
            // Thicker while focused, so it's clear the arrows now scrub.
            val pos = seekBar.progress
            val second = seekBar.secondaryProgress
            seekBar.progressDrawable = act.getDrawable(if (has) R.drawable.seek_progress_focused else R.drawable.seek_progress)
            seekBar.progress = pos
            seekBar.secondaryProgress = second
            if (!has && act.pendingSeek == null) seekPreview.release()
            placeBubble()
        }
        // The count is drawn by hand, so it follows the pill's dark-on-green focus colours itself.
        btnLive.setOnFocusChangeListener { view, has ->
            if (has) updateTooltip(view)
            viewerCount.textColor = if (has) 0xFF000000.toInt() else act.getColor(R.color.text_primary)
        }
    }

    /** Show the label centered just above the focused control. */
    fun updateTooltip(v: View) {
        val label = tooltipLabels[v]?.invoke() ?: return
        tooltip.text = label
        tooltip.visibility = View.INVISIBLE
        tooltip.post {
            if (!v.hasFocus() || !up) return@post
            val loc = IntArray(2)
            val base = IntArray(2)
            v.getLocationInWindow(loc)
            root.getLocationInWindow(base)
            val cx = loc[0] - base[0] + v.width / 2f
            val maxX = (root.width - tooltip.width - act.dp(8)).toFloat()
            tooltip.translationX = (cx - tooltip.width / 2f).coerceIn(act.dp(8).toFloat(), maxX)
            // The bar may still be sliding up into place; aim for where it comes to rest.
            var slide = 0f
            var p: View? = v
            while (p != null && p !== root) { slide += p.translationY; p = p.parent as? View }
            tooltip.translationY = loc[1] - base[1] - slide - tooltip.height - act.dp(4)
            tooltip.alpha = 0f
            tooltip.visibility = View.VISIBLE
            tooltip.animate().alpha(1f).translationYBy(-act.dp(4).toFloat()).setDuration(140).start()
        }
    }

    fun refreshTooltip(v: View) {
        if (v.hasFocus()) updateTooltip(v)
    }

    fun show(focus: View? = btnPlay) {
        if (act.inPip) return
        val wasVisible = up
        up = true
        if (!wasVisible) {
            root.fadeIn(180)
            bottomBar.apply {
                translationY = act.dp(16).toFloat()
                animate().translationY(0f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
        act.chat.onControlsShown()
        osd.fadeOut(120)
        updateProgress()
        updateMeta()
        updateQuality()
        mini.setVisible(false)
        clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        if (!wasVisible) focus?.requestFocus()
        act.currentFocus?.let { updateTooltip(it) }
        scheduleHide()
    }

    /** Seeking from the remote with the controls hidden: bring up the bar, focused, to scrub on. */
    fun showForSeek() {
        if (up) return
        show(seekBar)
    }

    fun hide() {
        up = false
        seekPreview.release()
        seekBubble.visibility = View.INVISIBLE
        updateMini()
        root.fadeOut(200)
        tooltip.visibility = View.INVISIBLE
        act.chat.onControlsHidden()
        act.ui.removeCallbacks(hideRunnable)
    }

    fun scheduleHide() {
        act.ui.removeCallbacks(hideRunnable)
        act.ui.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    fun cancelHide() {
        act.ui.removeCallbacks(hideRunnable)
    }

    /** A message drops in at the top centre: one style for everything the player tells you. */
    fun flashOsd(text: String, icon: Int, duration: Long = 1300) {
        if (act.inPip) return
        if (up && act.pendingSeek != null) return // the seek bar already shows it
        osd.text = text
        osd.setCompoundDrawablesRelative(toastIcon(icon), null, null, null)
        // Below the header card while the controls are up, so it doesn't cover the title.
        val top = if (up) act.dp(112).toFloat() else 0f
        if (osd.visibility != View.VISIBLE) {
            osd.translationY = top - act.dp(12)
            osd.animate().translationY(top).setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            osd.translationY = top
        }
        osd.fadeIn(160)
        act.ui.removeCallbacks(osdHide)
        act.ui.postDelayed(osdHide, duration)
    }

    /** The message's icon, dark on a small green disc. */
    private fun toastIcon(icon: Int): android.graphics.drawable.Drawable {
        val size = act.dp(32)
        val disc = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(act.getColor(R.color.kick_green))
        }
        val glyph = act.getDrawable(icon)!!.mutate().apply { setTint(0xFF0B0E0F.toInt()) }
        return android.graphics.drawable.LayerDrawable(arrayOf(disc, glyph)).apply {
            setLayerInset(1, act.dp(7), act.dp(7), act.dp(7), act.dp(7))
            setBounds(0, 0, size, size)
        }
    }

    fun hideOsd() {
        act.ui.removeCallbacks(osdHide)
        osd.setVisible(false)
    }

    /** The quality button reads like the menu: "Auto • 720p", or the quality picked. */
    fun updateQuality() {
        val label = if (player.videoFormat == null) "Auto" else act.menu.currentQualityLabel()
        qualityLabel = label
        val shown = if (compact == true) "" else fitQuality(label)
        if (btnQuality.text.toString() != shown || shapedCompact != compact) shapeQuality(shown)
        btnQuality.setVisible(act.menu.videoTracks().size > 1 || player.videoFormat != null)
    }

    private fun fitQualityNeeded(): Boolean {
        if (compact == true || btnQuality.visibility != View.VISIBLE) return false
        return btnQuality.text.toString() != fitQuality(qualityLabel)
    }

    private val qualityIcon = btnQuality.compoundDrawablesRelative[0]
    private var shapedCompact: Boolean? = null

    /**
     * A pill with [text], or with no text a round button exactly like the others: same size,
     * same icon size, the icon centred.
     */
    private fun shapeQuality(text: String) {
        shapedCompact = compact
        val on = compact == true
        val size = act.dp(if (on) 42 else 48)
        val pad = act.dp(if (on) 11 else 12)
        val round = text.isEmpty()
        btnQuality.text = text
        val lp = btnQuality.layoutParams
        val w = if (round) size else LinearLayout.LayoutParams.WRAP_CONTENT
        if (lp.width != w) { lp.width = w; btnQuality.layoutParams = lp }
        val bg = if (round) R.drawable.bg_ctrl_button else R.drawable.bg_ctrl_pill
        if (btnQuality.getTag(R.id.btnQuality) != bg) {
            btnQuality.setBackgroundResource(bg)
            // A fresh selector starts mid-fade; settle it, or it can stay see-through until focused.
            btnQuality.jumpDrawablesToCurrentState()
            btnQuality.setTag(R.id.btnQuality, bg)
        }
        qualityIcon?.let {
            val iconSize = if (round) size - 2 * pad else act.dp(24)
            it.setBounds(0, 0, iconSize, iconSize)
            btnQuality.setCompoundDrawablesRelative(it, null, null, null)
        }
        btnQuality.compoundDrawablePadding = if (round) 0 else act.dp(8)
        btnQuality.setPadding(if (round) pad else act.dp(14), 0, if (round) 0 else act.dp(18), 0)
    }

    /**
     * The longest form of [label] whose pill leaves a gap before the play buttons in the middle:
     * "Auto • 1080p60", then "1080p60", then just the icon. The right-hand buttons grow towards
     * the centre, so a long label with captions showing would otherwise run into them.
     */
    private fun fitQuality(label: String): String {
        val actions = btnQuality.parent as ViewGroup
        val transport = act.findViewById<View>(R.id.transport)
        val row = transport.parent as View
        if (row.width == 0) return label
        var others = 0
        for (i in 0 until actions.childCount) {
            val c = actions.getChildAt(i)
            if (c === btnQuality || c.visibility != View.VISIBLE) continue
            others += c.width + (c.layoutParams as ViewGroup.MarginLayoutParams).marginStart
        }
        val lp = btnQuality.layoutParams as ViewGroup.MarginLayoutParams
        val room = row.width / 2 - transport.width / 2 - act.dp(24) - others - lp.marginStart
        fun width(t: String) = act.dp(14) + act.dp(24) + act.dp(8) + btnQuality.paint.measureText(t).toInt() + act.dp(18)
        val short = label.substringAfter("• ")
        return listOf(label, short).firstOrNull { width(it) <= room } ?: ""
    }

    /**
     * The play buttons sit in the middle unless the groups on either side reach them (chat
     * beside the video, with every button showing); then they move into the middle of the gap
     * that's left, so nothing overlaps.
     */
    private fun keepTransportClear(transport: View) {
        val left = btnLive.parent as View
        val actions = btnQuality.parent as View
        val gap = act.dp(16)
        val from = left.right + gap
        val to = actions.left - gap
        val half = transport.width / 2f
        val centre = (transport.left + transport.right) / 2f
        val target = when {
            centre - half >= from && centre + half <= to -> centre
            else -> (from + to) / 2f
        }
        val dx = target - centre
        if (transport.translationX != dx) transport.translationX = dx
    }

    /** The thin line along the bottom: past broadcasts and rewound live, controls hidden. */
    fun updateMini() {
        val show = !up && !act.inPip && act.mode != PlayerActivity.Mode.LIVE && prefs.miniProgress
        if (show) {
            val max = act.timelineMax()
            mini.max = (max / 1000).toInt().coerceAtLeast(1)
            mini.progress = (act.timelinePos() / 1000).toInt()
        }
        if (show != (mini.visibility == View.VISIBLE)) if (show) mini.fadeIn(200) else mini.setVisible(false)
    }

    /** Called twice a second. */
    fun tick() {
        updateMini()
        if (!up) return
        updateQuality()
        if (act.pendingSeek == null) updateProgress()
        clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        updateMeta()
    }

    /**
     * With chat beside the video the controls only get ~2/3 of the screen, so shrink the
     * buttons and padding and move captions into Settings (the CC key still works).
     */
    fun setCompact(on: Boolean) {
        if (compact == on) return
        compact = on
        val side = act.dp(if (on) 24 else 40)
        bottomBar.setPadding(side, bottomBar.paddingTop, side, bottomBar.paddingBottom)
        val small = act.dp(if (on) 42 else 48)
        val gap = act.dp(if (on) 6 else 10)
        val actions = act.findViewById<View>(R.id.actions)
        listOf(btnRewind, btnForward, btnChat, btnPhone, btnFollow, btnSettings, btnCaptions, btnProfile).forEach { b ->
            b.layoutParams = (b.layoutParams as LinearLayout.LayoutParams).apply {
                width = small; height = small
                if (b.parent === actions) marginStart = gap
            }
            val pad = act.dp(if (on) 11 else 12)
            b.setPadding(pad, pad, pad, pad)
        }
        // Beside chat there's no room for the label: it becomes a round button like the others.
        btnQuality.layoutParams = (btnQuality.layoutParams as LinearLayout.LayoutParams).apply {
            height = small; marginStart = gap
        }
        updateQuality()
        btnPlay.layoutParams = (btnPlay.layoutParams as LinearLayout.LayoutParams).apply {
            val big = act.dp(if (on) 54 else 64)
            width = big; height = big
            marginStart = act.dp(if (on) 10 else 14); marginEnd = act.dp(if (on) 10 else 14)
        }
        val playPad = act.dp(if (on) 14 else 16)
        btnPlay.setPadding(playPad, playPad, playPad, playPad)
        btnLive.setPadding(act.dp(if (on) 12 else 16), 0, act.dp(if (on) 14 else 18), 0)
        liveLabel.textSize = if (on) 13f else 14f
        updateCaptionButton()
    }

    fun updateMode() {
        val isVod = act.mode == PlayerActivity.Mode.VOD
        btnLive.setVisible(!isVod)
        btnChat.setVisible(!isVod || act.chat.canReplay)
        btnPhone.setVisible(!isVod)
        updateFollowButton()
        updateProgress()
    }

    fun updateFollowButton() {
        val on = Follows.isFollowing(act, act.slug)
        btnFollow.setImageResource(if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        btnFollow.isActivated = on
        followedHeart.setVisible(on)
        // Unless unfollowing from the player is turned on, the heart only shows for channels not yet followed.
        val show = !on || act.prefs.playerUnfollow
        if (!show && btnFollow.hasFocus()) btnSettings.requestFocus()
        btnFollow.setVisible(show)
    }

    fun updatePlayButton() {
        btnPlay.setImageResource(
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    fun updateCaptionButton() {
        btnCaptions.isActivated = !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        btnCaptions.setVisible(compact != true && act.menu.textTracks().isNotEmpty())
    }

    fun bindHeader() {
        val c = act.channel
        act.findViewById<ImageView>(R.id.avatar).loadAvatar(c?.avatar, c?.username)
        act.findViewById<TextView>(R.id.channelName).text = (c?.username ?: act.slug) + if (c?.verified == true) " ✓" else ""
        act.findViewById<TextView>(R.id.streamTitle).text =
            if (act.mode == PlayerActivity.Mode.VOD) act.vodTitle.orEmpty() else c?.liveTitle.orEmpty()
        updateMeta()
        updateFollowButton()
    }

    fun updateMeta() {
        val c = act.channel
        val live = act.mode != PlayerActivity.Mode.VOD && c != null
        viewerCount.setVisible(live)
        liveSep.setVisible(live)
        if (c != null && live) viewerCount.setValue(c.liveViewers)
        val meta = if (act.mode == PlayerActivity.Mode.VOD) {
            listOfNotNull(if (act.isClip) "Clip" else "Past broadcast", c?.let { "${Fmt.count(it.followers)} followers" })
        } else {
            listOfNotNull(c?.liveCategory)
        } + listOfNotNull(if (act.sleepTimer.active) "sleep ${act.sleepTimer.label()}" else null)
        act.findViewById<TextView>(R.id.streamMeta).text = meta.joinToString("  •  ")
    }

    fun updateProgress() {
        val max = act.timelineMax()
        val pos = act.pendingSeek ?: act.timelinePos()
        seekBar.max = (max / 1000).toInt().coerceAtLeast(1)
        seekBar.progress = (pos / 1000).toInt()
        val buffered = when (act.mode) {
            PlayerActivity.Mode.LIVE -> pos + player.totalBufferedDuration
            else -> player.bufferedPosition
        }
        seekBar.secondaryProgress = (buffered / 1000).toInt()
        position.text = Fmt.time(pos)
        placeBubble()
        if (act.mode == PlayerActivity.Mode.VOD) {
            durationText.text = Fmt.time(max)
        } else {
            val atLive = act.isAtLive()
            durationText.text = if (atLive) Fmt.time(max) else "−${Fmt.time(max - pos)}"
            val label = if (atLive) "LIVE" else "GO LIVE"
            if (liveLabel.text != label) {
                liveLabel.text = label
                val dot = act.getDrawable(R.drawable.dot)!!.mutate().apply {
                    setBounds(0, 0, act.dp(8), act.dp(8))
                    setTint(act.getColor(if (atLive) R.color.live_red else R.color.text_secondary))
                }
                liveLabel.setCompoundDrawablesRelative(dot, null, null, null)
                liveLabel.compoundDrawablePadding = act.dp(8)
            }
        }
    }

    /**
     * While the seek bar has focus or a seek is lined up, a bubble above the thumb says where you'd
     * land; on past broadcasts it carries a frame of that spot too.
     */
    private fun placeBubble() {
        val pending = act.pendingSeek
        if (!up || (!seekBar.hasFocus() && pending == null)) {
            seekBubble.visibility = View.INVISIBLE
            return
        }
        val pos = pending ?: act.timelinePos()
        val max = act.timelineMax()
        seekTime.text = when {
            act.mode == PlayerActivity.Mode.VOD -> Fmt.time(pos)
            max - pos < PlayerActivity.LIVE_EDGE_MS -> "Live"
            else -> "−${Fmt.time(max - pos)}"
        }
        val source = act.vodUrl
        if (pending != null && source != null && act.mode == PlayerActivity.Mode.VOD && prefs.seekPreviews) {
            seekPreview.at(source, pending)
        } else {
            seekPreview.hideFrame()
        }
        if (seekBubble.width == 0) {
            seekBubble.visibility = View.INVISIBLE
            seekBubble.post { placeBubble() }
            return
        }
        positionBubble()
        seekBubble.visibility = View.VISIBLE
    }

    /** Puts the bubble over the thumb; again whenever its size changes (the frame comes or goes). */
    private fun positionBubble() {
        val track = seekBar.width - seekBar.paddingLeft - seekBar.paddingRight
        val frac = if (seekBar.max > 0) seekBar.progress / seekBar.max.toFloat() else 0f
        val cx = bottomBar.left + seekBar.left + seekBar.paddingLeft + track * frac
        val edge = act.dp(16).toFloat()
        seekBubble.translationX = (cx - seekBubble.width / 2f).coerceIn(edge, root.width - seekBubble.width - edge)
        seekBubble.translationY = bottomBar.top + bottomBar.translationY + seekBar.top - seekBubble.height - act.dp(10).toFloat()
    }
}
