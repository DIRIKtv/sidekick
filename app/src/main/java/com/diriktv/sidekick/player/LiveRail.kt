package com.diriktv.sidekick.player

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.LiveStream
import com.diriktv.sidekick.ui.AppFonts
import com.diriktv.sidekick.ui.FocusGlow
import com.diriktv.sidekick.ui.Fmt
import com.diriktv.sidekick.ui.Img
import com.diriktv.sidekick.ui.Nav
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.focusZoom
import com.diriktv.sidekick.ui.font
import com.diriktv.sidekick.ui.inflate
import com.diriktv.sidekick.ui.loadAvatar
import com.diriktv.sidekick.ui.loadThumb
import com.diriktv.sidekick.ui.setVisible
import kotlinx.coroutines.launch

/**
 * ▼ while watching: the bottom third of the picture slides up to show the channels you follow
 * that are live now, as cards. OK switches to one; ▲ or Back puts it away, and it tucks itself
 * away after a while untouched.
 */
internal class LiveRail(private val act: PlayerActivity, private val stage: FrameLayout) {
    private companion object {
        const val IDLE_HIDE_MS = 12_000L
    }

    private val panel = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setBackgroundResource(R.drawable.bg_live_rail)
        setPadding(act.dp(36), act.dp(18), act.dp(36), act.dp(18))
        elevation = act.dp(12).toFloat()
        visibility = View.GONE
        // Focus stays inside the rail while it's up.
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    private val heading = TextView(act).apply {
        textSize = 15f
        font(AppFonts.Weight.BOLD)
        setTextColor(act.getColor(R.color.text_primary))
        setPadding(act.dp(6), 0, 0, act.dp(10))
    }
    private val note = TextView(act).apply {
        textSize = 14f
        font(AppFonts.Weight.REGULAR)
        setTextColor(act.getColor(R.color.text_secondary))
        setPadding(act.dp(6), act.dp(6), 0, act.dp(36))
        visibility = View.GONE
    }
    private val list = RecyclerView(act).apply {
        layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        clipToPadding = false
        clipChildren = false
        // Room for the focused card's zoom.
        setPadding(0, act.dp(8), 0, act.dp(8))
        itemAnimator = null
    }
    private val adapter = Adapter()
    private val idleHide = Runnable { hide() }

    val showing get() = panel.visibility == View.VISIBLE

    init {
        panel.clipChildren = false
        panel.addView(heading)
        panel.addView(note)
        panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.adapter = adapter
        stage.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(act.dp(24), 0, act.dp(24), 0)
        })
        // A third of the picture: the cards are sized for it, the rest is the fade above them.
        stage.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) panel.minimumHeight = (bottom - top) / 3
        }
    }

    fun show() {
        if (showing || act.inPip) return
        act.controls.hide()
        // Chat steps aside (beside the video, the picture takes its room) while the rail is up.
        act.chat.covered = true
        heading.text = "Live now from Following"
        if (adapter.streams.isEmpty()) {
            note.text = "Finding live channels…"
            note.setVisible(true)
        }
        panel.minimumHeight = stage.height / 3
        panel.alpha = 0f
        panel.translationY = act.dp(40).toFloat()
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        act.captions.lift(true, (stage.height / 3).toFloat())
        load()
        touched()
    }

    fun hide() {
        if (!showing) return
        act.ui.removeCallbacks(idleHide)
        list.findFocus()?.clearFocus()
        panel.animate().alpha(0f).translationY(act.dp(40).toFloat()).setDuration(180).withEndAction {
            panel.visibility = View.GONE
        }.start()
        act.chat.covered = false
        act.captions.lift(false, 0f)
    }

    /** Any key while it's up keeps it up a little longer. */
    fun touched() {
        act.ui.removeCallbacks(idleHide)
        act.ui.postDelayed(idleHide, IDLE_HIDE_MS)
    }

    /** Keys while the rail is up. Returns true when handled here. */
    fun onKey(event: KeyEvent): Boolean {
        touched()
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { hide(); true }
            else -> false
        }
    }

    private fun load() {
        val current = act.slug
        act.lifecycleScope.launch {
            val live = runCatching { Follows.liveNow(act) }.getOrNull()
            if (!showing) return@launch
            val others = live.orEmpty().filterNot { it.slug.equals(current, true) }
            adapter.submit(others)
            note.setVisible(others.isEmpty())
            note.text = when {
                live == null -> "Couldn't check who's live. Try again in a moment."
                others.isNotEmpty() -> ""
                !Follows.signedIn && live.isEmpty() && com.diriktv.sidekick.data.Prefs(act).favorites.isEmpty() ->
                    "Follow channels (or sign in to Kick) to see who's live here."
                else -> "Nobody else you follow is live right now."
            }
            heading.text = if (others.isEmpty()) "Live now from Following" else "Live now from Following  •  ${others.size}"
            if (others.isNotEmpty()) list.post { (list.findViewHolderForAdapterPosition(0)?.itemView ?: list.getChildAt(0))?.requestFocus() }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var streams: List<LiveStream> = emptyList()
            private set

        fun submit(list: List<LiveStream>) {
            streams = list
            @Suppress("NotifyDataSetChanged") // a fresh list each time the rail opens
            notifyDataSetChanged()
        }

        override fun getItemCount() = streams.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = parent.inflate(R.layout.item_live_rail)
            v.findViewById<View>(R.id.thumbFrame).clipToOutline = true
            val holder = VH(v)
            v.focusZoom(1.06f) { has -> FocusGlow.onFocus(v, has) }
            v.setOnClickListener {
                val s = streams.getOrNull(holder.bindingAdapterPosition) ?: return@setOnClickListener
                hide()
                Nav.watchLive(act, s.slug)
            }
            return holder
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = streams[position]
            holder.thumb.loadThumb(Img.thumb(s.thumbnail), live = true)
            holder.avatar.loadAvatar(s.avatar, s.username)
            holder.channel.text = s.username
            holder.title.text = listOfNotNull(s.category, s.title.takeIf { it.isNotBlank() }).joinToString("  •  ")
            holder.viewers.text = "● ${Fmt.count(s.viewers)}"
            holder.itemView.contentDescription = "${s.username}, ${s.title}, ${Fmt.viewers(s.viewers)}"
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val avatar: ImageView = v.findViewById(R.id.avatar)
            val channel: TextView = v.findViewById(R.id.channel)
            val title: TextView = v.findViewById(R.id.title)
            val viewers: TextView = v.findViewById(R.id.viewers)
        }
    }
}
