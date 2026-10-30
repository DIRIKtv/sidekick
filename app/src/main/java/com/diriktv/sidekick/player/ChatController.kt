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
    val adapter = ChatAdapter(act, prefs.chatTextSize).apply { showTimestamps = prefs.chatTimestamps }
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
            ).also { it.connect() }
            PhoneChat.target = PhoneChat.Target(c.slug, c.username, c.avatar, c.chatroomId)
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
        adapter.clear()
        adapter.subscriberBadges = emptyList()
        adapter.thirdPartyEmotes = emptyMap()
    }

    fun close() {
        act.ui.removeCallbacks(drainNext)
        backlog.clear()
        draining = false
        client?.close()
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

    /** With overlay chat, the panel steps aside while the controls are up. */
    fun onControlsShown() {
        if (prefs.chatMode != "side") panel.setVisible(false)
    }

    fun onControlsHidden() {
        panel.setVisible(shown)
    }

    /**
     * Places chat beside or over the video. With [animate], chat slides in from the right while
     * the video eases over to make room (and the reverse when closing).
     */
    fun applyLayout(animate: Boolean = false) {
        val show = shown
        val side = prefs.chatMode == "side"
        val panelVisible = show && (side || !act.controls.up)
        panel.setBackgroundColor(act.getColor(if (side) R.color.chat_bg else R.color.chat_overlay_bg))
        act.controls.btnChat.isActivated = show
        if (show) list.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
        act.controls.setCompact(show && side)

        val chatWidth = act.dp(prefs.chatWidth)
        if (panel.layoutParams.width != chatWidth) panel.layoutParams = panel.layoutParams.apply { width = chatWidth }
        val lp = stage.layoutParams as FrameLayout.LayoutParams
        val targetMargin = if (show && side) chatWidth else 0
        animator?.cancel()
        if (!animate) {
            panel.setVisible(panelVisible)
            panel.translationX = 0f
            panel.alpha = 1f
            lp.marginEnd = targetMargin
            stage.layoutParams = lp
            return
        }
        val width = chatWidth.toFloat()
        val startMargin = lp.marginEnd
        val startShift = if (panel.visibility == View.VISIBLE) panel.translationX else width
        val endShift = if (panelVisible) 0f else width
        panel.visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                panel.translationX = startShift + (endShift - startShift) * f
                panel.alpha = if (side) 1f else 1f - (panel.translationX / width)
                if (startMargin != targetMargin) {
                    lp.marginEnd = (startMargin + (targetMargin - startMargin) * f).toInt()
                    stage.layoutParams = lp
                }
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
