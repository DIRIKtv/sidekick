package com.diriktv.sidekick.player

import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.ChannelInfo
import com.diriktv.sidekick.data.ChatClient
import com.diriktv.sidekick.data.ChatMessage
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.PhoneChat
import com.diriktv.sidekick.data.SevenTv
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.setVisible
import kotlinx.coroutines.launch

/** The chat panel: connecting, filtering and pacing messages, and placing it beside or over the video. */
internal class ChatController(private val act: PlayerActivity) {
    companion object {
        /** Calm chat shows one message per interval and lets the queue run at most this far behind. */
        private const val CALM_INTERVAL_MS = 450L
        private const val CALM_BACKLOG = 12
    }

    private val prefs = act.prefs
    private val panel: View = act.findViewById(R.id.chatPanel)
    private val list: RecyclerView = act.findViewById(R.id.chatList)
    private val stage: View = act.findViewById(R.id.stage)
    val adapter = ChatAdapter(act, prefs.chatTextSize).apply {
        showTimestamps = prefs.chatTimestamps
        spacingDp = com.diriktv.sidekick.data.Prefs.CHAT_SPACINGS[prefs.chatSpacing.coerceIn(0, 2)].first
        emoteScale = prefs.chatEmoteScale
    }
    private val pinnedView: android.widget.TextView = act.findViewById(R.id.chatPinned)
    private val pollView: android.widget.LinearLayout = act.findViewById(R.id.chatPoll)
    private var pinned: ChatMessage? = null
    private var poll: com.diriktv.sidekick.data.ChatPoll? = null
    private var liveChannel: ChannelInfo? = null
    private val pollTick = object : Runnable {
        override fun run() {
            checkPolls()
            act.ui.postDelayed(this, 15_000)
        }
    }
    private val filter = ChatFilter(prefs)

    private var client: ChatClient? = null
    private var connecting = false
    private var emotesFor = -1

    /** Whether the viewer wants chat open; it only shows on live streams. */
    var wanted = false

    private val backlog = ArrayDeque<ChatMessage>()
    private var draining = false
    private val drainNext = Runnable { drain() }
    private var animator: ValueAnimator? = null

    // New messages slide in gently; changes (emotes arriving) redraw without flashing.
    private val slideIn = androidx.recyclerview.widget.DefaultItemAnimator().apply {
        addDuration = 140
        moveDuration = 140
        removeDuration = 0
        changeDuration = 0
        supportsChangeAnimations = false
    }
    private var lastAddAt = 0L

    init {
        list.layoutManager = LinearLayoutManager(act).apply { stackFromEnd = true }
        list.adapter = adapter
        list.itemAnimator = slideIn
    }

    /** Chat is on screen: live, or replaying a past broadcast's chat, and not in picture-in-picture. */
    val shown get() = wanted && (act.mode != PlayerActivity.Mode.VOD || canReplay) && !act.inPip

    // region replay: a past broadcast's chat, played back in step with the video

    private var replayChannelId = 0
    /** Wall-clock time (UTC) of the video's first frame; 0 when there's nothing to replay. */
    private var replayStart = 0L
    /** Video position of the next five-second window of chat to fetch. */
    private var replayNext = 0L
    private var replayLastPos = -1L
    private var replayFetching = false
    private var replayRetryAt = 0L
    private var replayGen = 0
    private val replayQueue = ArrayDeque<Pair<Long, ChatMessage>>()

    /** This is a past broadcast (not a clip) whose chat Kick kept. */
    val canReplay get() = act.mode == PlayerActivity.Mode.VOD && !act.isClip && replayStart > 0 && prefs.chatReplay

    fun startReplay(c: ChannelInfo, startUtcMs: Long) {
        if (replayStart == startUtcMs && replayChannelId == c.id) return
        replayChannelId = c.id
        replayStart = startUtcMs
        replayLastPos = -1
        loadEmotes(c)
        prefetchEmotes(c)
    }

    /** Called a couple of times a second with the video position. */
    fun tickReplay(pos: Long) {
        if (!canReplay || !shown) return
        // First run, or a jump (seek, or chat reopened after a while): start over a little before here.
        if (replayLastPos < 0 || pos < replayLastPos - 3_000 || pos > replayLastPos + 20_000) {
            replayGen++
            replayQueue.clear()
            replayFetching = false
            adapter.clear()
            replayNext = (pos - 15_000).coerceAtLeast(0) / 5_000 * 5_000
        }
        replayLastPos = pos
        while (true) {
            val next = replayQueue.firstOrNull() ?: break
            if (next.first > pos) break
            replayQueue.removeFirst()
            if (filter.allows(next.second)) add(next.second)
        }
        if (!replayFetching && replayNext <= pos + 10_000 && System.currentTimeMillis() >= replayRetryAt) fetchReplay()
    }

    private fun fetchReplay() {
        val gen = replayGen
        val from = replayNext
        replayFetching = true
        act.lifecycleScope.launch {
            val msgs = runCatching { KickApi.chatHistory(replayChannelId, replayStart + from) }.getOrNull()
            if (gen != replayGen) return@launch
            replayFetching = false
            if (msgs == null) {
                replayRetryAt = System.currentTimeMillis() + 5_000
                return@launch
            }
            replayQueue.addAll(msgs.map { (it.receivedAt - replayStart) to it }.sortedBy { it.first })
            replayNext = from + 5_000
        }
    }

    private fun stopReplay() {
        replayGen++
        replayStart = 0
        replayQueue.clear()
        replayFetching = false
    }

    // endregion

    fun connect(c: ChannelInfo) {
        if (liveChannel?.id != c.id) {
            liveChannel = c
            act.lifecycleScope.launch {
                pinned = runCatching { KickApi.pinnedMessage(c.id) }.getOrNull()
                showPinned()
            }
            act.ui.removeCallbacks(pollTick)
            act.ui.post(pollTick)
        }
        loadEmotes(c)
        prefetchEmotes(c)
        if (c.chatroomId == 0 || client != null || connecting) return
        connecting = true
        act.lifecycleScope.launch {
            val (key, cluster) = KickApi.pusherConfig(c.id)
            connecting = false
            if (act.isDestroyed || client != null) return@launch
            client = ChatClient(
                chatroomId = c.chatroomId,
                appKey = key,
                cluster = cluster,
                onMessage = { receive(it) },
                onDeleted = { id -> backlog.removeAll { it.id == id }; adapter.remove(id) },
                onStatus = { add(ChatMessage("sys-${System.nanoTime()}", "", null, it, emptyList(), isSystem = true)) },
                channelId = c.id,
                onRaid = { act.onRaid(it) },
                onPinned = { pinned = it; showPinned() },
                onPoll = { poll = it; showPoll() },
            ).also { it.connect() }
            PhoneChat.target = PhoneChat.Target(c.slug, c.username, c.avatar, c.chatroomId, c.subscriberBadges)
            PhoneChat.sevenTv = adapter.thirdPartyEmotes
        }
    }

    /** (Re)loads 7TV emotes for [c], or clears them when they're turned off. */
    fun loadEmotes(c: ChannelInfo, force: Boolean = false) {
        if (!prefs.sevenTvEmotes) {
            emotesFor = -1
            adapter.thirdPartyEmotes = emptyMap()
            return
        }
        if (emotesFor == c.userId && !force) return
        emotesFor = c.userId
        act.lifecycleScope.launch {
            adapter.thirdPartyEmotes = SevenTv.emotes(c.userId)
            if (act.mode != PlayerActivity.Mode.VOD) PhoneChat.sevenTv = adapter.thirdPartyEmotes
            // Most channels have a modest 7TV set; warm it too (a huge one is left to load as used).
            if (adapter.thirdPartyEmotes.size <= 300) adapter.prefetch(adapter.thirdPartyEmotes.values)
        }
    }

    fun setSubscriberBadges(c: ChannelInfo) {
        if (adapter.subscriberBadges.isEmpty()) {
            adapter.subscriberBadges = c.subscriberBadges
            adapter.prefetch(c.subscriberBadges.map { it.second })
        }
    }

    private var prefetchedFor: String? = null

    /** Warms the image cache with the channel's Kick emotes so busy chat isn't waiting on each one. */
    private fun prefetchEmotes(c: ChannelInfo) {
        if (prefetchedFor == c.slug) return
        prefetchedFor = c.slug
        act.lifecycleScope.launch {
            runCatching { KickApi.emoteUrls(c.slug) }.getOrNull()?.let { adapter.prefetch(it) }
        }
    }

    /** Spacing or emote size changed in the menu. */
    fun refreshStyle() {
        adapter.spacingDp = com.diriktv.sidekick.data.Prefs.CHAT_SPACINGS[prefs.chatSpacing.coerceIn(0, 2)].first
        adapter.emoteScale = prefs.chatEmoteScale
        adapter.notifyDataSetChanged()
        showPinned()
    }

    /** The pinned message or poll settings changed. */
    fun refreshExtras() {
        showPinned()
        showPoll()
        if (prefs.chatPolls) checkPolls()
    }

    private fun showPinned() {
        val m = pinned
        val show = m != null && prefs.chatPinned && act.mode != PlayerActivity.Mode.VOD
        pinnedView.setVisible(show)
        if (!show || m == null) return
        pinnedView.text = adapter.renderMessage(m, pinnedView.lineHeight)
        val pin = act.getDrawable(R.drawable.ic_pin)!!.mutate()
        val size = act.dp(15)
        pin.setBounds(0, 0, size, size)
        pinnedView.setCompoundDrawablesRelative(pin, null, null, null)
    }

    /** Checks Kick for a running poll and the latest prediction (live events also update it). */
    private fun checkPolls() {
        val c = liveChannel ?: return
        if (!prefs.chatPolls || act.mode == PlayerActivity.Mode.VOD) return
        act.lifecycleScope.launch {
            val p = runCatching { KickApi.poll(c.slug) }.getOrNull()
                ?: runCatching { KickApi.prediction(c.slug) }.getOrNull()
            if (liveChannel?.id != c.id) return@launch
            poll = p
            showPoll()
        }
    }

    /** The poll or prediction card: its question, then each answer with a bar of its share. */
    private fun showPoll() {
        val p = poll
        val show = p != null && prefs.chatPolls && act.mode != PlayerActivity.Mode.VOD && p.options.isNotEmpty()
        pollView.setVisible(show)
        if (!show || p == null) return
        pollView.removeAllViews()
        val head = when {
            p.kind == com.diriktv.sidekick.data.ChatPoll.PREDICTION && p.state == "RESOLVED" -> "PREDICTION • DECIDED"
            p.kind == com.diriktv.sidekick.data.ChatPoll.PREDICTION && p.state == "LOCKED" -> "PREDICTION • LOCKED"
            p.kind == com.diriktv.sidekick.data.ChatPoll.PREDICTION -> "PREDICTION"
            p.state == "ENDED" -> "POLL • RESULTS"
            else -> "POLL"
        } + if (p.secondsLeft > 0) "  •  ${p.secondsLeft / 60}:${"%02d".format(p.secondsLeft % 60)} left" else ""
        pollView.addView(android.widget.TextView(act).apply {
            text = head
            textSize = 11f
            letterSpacing = 0.08f
            com.diriktv.sidekick.ui.AppFonts.get(act, com.diriktv.sidekick.ui.AppFonts.Weight.MEDIUM).let { typeface = it }
            setTextColor(act.getColor(R.color.kick_green))
        })
        pollView.addView(android.widget.TextView(act).apply {
            text = p.title
            textSize = 14f
            typeface = com.diriktv.sidekick.ui.AppFonts.get(act, com.diriktv.sidekick.ui.AppFonts.Weight.BOLD)
            setTextColor(act.getColor(R.color.text_primary))
            setPadding(0, act.dp(2), 0, act.dp(6))
        })
        val total = p.total.coerceAtLeast(1)
        val leader = p.options.maxOf { it.amount }
        for (o in p.options.take(5)) {
            val share = o.amount.toFloat() / total
            pollView.addView(PollBar(act, o.label, share, highlight = o.won || (o.amount == leader && o.amount > 0)),
                android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, act.dp(24)).apply {
                    topMargin = act.dp(4)
                })
        }
    }

    fun setTimestamps(on: Boolean) {
        adapter.showTimestamps = on
        adapter.notifyDataSetChanged()
    }

    fun setTextSize(sp: Int) {
        adapter.textSizeSp = sp
        adapter.notifyDataSetChanged()
    }

    /** Drops the connection and messages (switching to another channel). */
    fun reset() {
        close()
        client = null
        connecting = false
        emotesFor = -1
        prefetchedFor = null
        stopReplay()
        act.ui.removeCallbacks(pollTick)
        liveChannel = null
        pinned = null
        poll = null
        showPinned()
        showPoll()
        adapter.clear()
        adapter.subscriberBadges = emptyList()
        adapter.thirdPartyEmotes = emptyMap()
    }

    fun close() {
        act.ui.removeCallbacks(drainNext)
        backlog.clear()
        draining = false
        client?.close()
        act.ui.removeCallbacks(pollTick)
        if (PhoneChat.target?.slug.equals(act.slug, true)) PhoneChat.target = null
    }

    private fun receive(m: ChatMessage) {
        if (!filter.allows(m)) return
        if (!prefs.chatCalm) {
            add(m)
            return
        }
        backlog.addLast(m)
        // Far behind: skip the oldest so chat stays roughly in step with the stream.
        while (backlog.size > CALM_BACKLOG) backlog.removeFirst()
        if (!draining) drain()
    }

    private fun drain() {
        val m = backlog.removeFirstOrNull()
        if (m == null) {
            draining = false
            return
        }
        draining = true
        add(m)
        act.ui.postDelayed(drainNext, CALM_INTERVAL_MS)
    }

    private fun add(m: ChatMessage) {
        // In a fast chat the slide-ins overlap and rows land on top of each other: only animate
        // when messages arrive slowly enough for each one to finish.
        val now = android.os.SystemClock.uptimeMillis()
        val busy = now - lastAddAt < 400
        lastAddAt = now
        val wantAnimator = if (busy) null else slideIn
        if (list.itemAnimator !== wantAnimator) list.itemAnimator = wantAnimator
        adapter.add(m)
        if (act.mode != PlayerActivity.Mode.VOD) PhoneChat.record(m)
        if (panel.visibility == View.VISIBLE) list.scrollToPosition(adapter.itemCount - 1)
    }

    /** Where chat sits for this channel (its own saved layout, or the default). */
    var layout = com.diriktv.sidekick.data.ChatLayout()

    /** With overlay chat, the panel steps aside while the controls are up. */
    fun onControlsShown() {
        if (!layout.beside) panel.setVisible(false)
    }

    fun onControlsHidden() {
        panel.setVisible(shown)
    }

    /**
     * Places chat per [layout]: beside the video on either side (the video moves over to make
     * room), or floating over it at any height and position. With [animate], chat slides in from
     * its side while the video eases over (and the reverse when closing).
     */
    fun applyLayout(animate: Boolean = false) {
        val l = layout
        val show = shown
        val beside = l.beside
        val panelVisible = show && (beside || !act.controls.up)
        panel.setBackgroundColor(if (beside) act.getColor(R.color.chat_bg) else (l.backing * 255 / 100 shl 24))
        adapter.textShadow = !beside && l.backing < 50
        // A small see-through chat over the video looks like a streamer's overlay: no title bar.
        act.findViewById<View>(R.id.chatHeader).setVisible(beside || l.backing >= 50)
        act.controls.btnChat.isActivated = show
        if (show) list.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
        act.controls.setCompact(show && beside)

        val chatWidth = act.dp(l.width)
        val parentW = (panel.parent as View).width
        val parentH = (panel.parent as View).height
        if (!beside && parentH == 0) panel.post { applyLayout() } // sized once the screen is laid out
        val plp = panel.layoutParams as FrameLayout.LayoutParams
        val h = if (beside || parentH == 0) FrameLayout.LayoutParams.MATCH_PARENT else parentH * l.height / 100
        val top = if (beside || parentH == 0) 0 else (parentH - h) * l.top / 100
        // Over the video it can sit anywhere: placed from the left by its share of the free width.
        val free = !beside && parentW > 0
        val start = if (free) ((parentW - chatWidth).coerceAtLeast(0) * l.x / 100) else 0
        val gravity = (if (free || l.left) android.view.Gravity.START else android.view.Gravity.END) or android.view.Gravity.TOP
        if (plp.width != chatWidth || plp.height != h || plp.gravity != gravity || plp.topMargin != top || plp.marginStart != start) {
            plp.width = chatWidth; plp.height = h; plp.gravity = gravity; plp.topMargin = top; plp.marginStart = start
            panel.layoutParams = plp
        }

        val lp = stage.layoutParams as FrameLayout.LayoutParams
        val targetStart = if (show && beside && l.left) chatWidth else 0
        val targetEnd = if (show && beside && !l.left) chatWidth else 0
        animator?.cancel()
        if (!animate) {
            panel.setVisible(panelVisible)
            panel.translationX = 0f
            panel.alpha = 1f
            lp.marginStart = targetStart
            lp.marginEnd = targetEnd
            stage.layoutParams = lp
            return
        }
        val fromLeft = if (beside) l.left else l.x < 50
        val away = (if (fromLeft) -1 else 1) * chatWidth.toFloat()
        val startStart = lp.marginStart
        val startEnd = lp.marginEnd
        val startShift = if (panel.visibility == View.VISIBLE) panel.translationX else away
        val endShift = if (panelVisible) 0f else away
        panel.visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                panel.translationX = startShift + (endShift - startShift) * f
                panel.alpha = if (beside) 1f else 1f - kotlin.math.abs(panel.translationX / away)
                lp.marginStart = (startStart + (targetStart - startStart) * f).toInt()
                lp.marginEnd = (startEnd + (targetEnd - startEnd) * f).toInt()
                stage.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!panelVisible) panel.visibility = View.GONE
                    panel.translationX = 0f
                    panel.alpha = 1f
                }
            })
            start()
        }
    }
}

/** One answer of a poll: a bar filled to its share, with the answer and the percentage on top. */
private class PollBar(context: android.content.Context, private val label: String, private val share: Float, private val highlight: Boolean) :
    View(context) {
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (highlight) context.getColor(R.color.kick_green) else 0x66FFFFFF
    }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(12).toFloat()
        typeface = com.diriktv.sidekick.ui.AppFonts.get(context, com.diriktv.sidekick.ui.AppFonts.Weight.MEDIUM)
    }
    private val rect = android.graphics.RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        rect.set(0f, 0f, (w * share).coerceAtLeast(h), h)
        canvas.drawRoundRect(rect, r, r, fill)
        val y = h / 2 - (text.ascent() + text.descent()) / 2
        val pct = "${(share * 100).toInt()}%"
        text.color = if (highlight && share > 0.6f) 0xFF000000.toInt() else 0xFFF2F2F2.toInt()
        val pctW = text.measureText(pct)
        val room = w - pctW - context.dp(26)
        val shown = android.text.TextUtils.ellipsize(label, android.text.TextPaint(text), room, android.text.TextUtils.TruncateAt.END)
        canvas.drawText(shown, 0, shown.length, context.dp(10).toFloat(), y, text)
        canvas.drawText(pct, w - pctW - context.dp(10), y, text)
    }
}
