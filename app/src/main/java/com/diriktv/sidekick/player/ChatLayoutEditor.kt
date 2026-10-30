package com.diriktv.sidekick.player

import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.ChatLayout
import com.diriktv.sidekick.ui.AppFonts
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.fadeIn
import com.diriktv.sidekick.ui.font

/**
 * Arranging chat with the remote, live on the stream: OK steps through Position, Size and
 * Placement, the arrows change it, Back saves it for this channel and ☰ makes it the default for
 * every channel. A card at the top of the screen says what the arrows do right now.
 */
internal class ChatLayoutEditor(private val act: PlayerActivity) {
    private enum class Tool(val title: String) { POSITION("Position"), SIZE("Size"), PLACEMENT("Placement") }

    private var tool = Tool.POSITION
    private val card = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundResource(R.drawable.bg_osd)
        setPadding(act.dp(22), act.dp(12), act.dp(22), act.dp(12))
        elevation = act.dp(10).toFloat()
    }
    private val tabs = TextView(act).apply {
        textSize = 15f
        font(AppFonts.Weight.BOLD)
        setTextColor(act.getColor(R.color.text_primary))
    }
    private val hint = TextView(act).apply {
        textSize = 13f
        font(AppFonts.Weight.REGULAR)
        setTextColor(act.getColor(R.color.text_secondary))
        setPadding(0, act.dp(4), 0, 0)
        gravity = Gravity.CENTER
    }
    var showing = false
        private set

    init {
        card.addView(tabs)
        card.addView(hint)
    }

    fun show() {
        if (showing) return
        showing = true
        act.chat.wanted = true
        act.controls.hide()
        act.chat.applyLayout(animate = true)
        tool = Tool.POSITION
        val root = act.findViewById<ViewGroup>(android.R.id.content)
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = act.dp(24) })
        card.fadeIn(180, fromY = -act.dp(10).toFloat())
        refresh()
    }

    private fun close(message: String) {
        showing = false
        (card.parent as? ViewGroup)?.removeView(card)
        act.controls.flashOsd(message, R.drawable.ic_layout)
    }

    fun saveForChannel() {
        if (!showing) return
        act.prefs.saveChatLayout(act.slug, act.chat.layout)
        close("Chat layout saved for ${act.channel?.username ?: "this channel"}")
    }

    private fun refresh() {
        val tools = Tool.entries.filter { it != Tool.PLACEMENT || !act.chat.layout.beside }
        if (tool !in tools) tool = Tool.POSITION
        tabs.text = tools.joinToString("     ") { if (it == tool) "●  ${it.title}" else it.title }
        val l = act.chat.layout
        hint.text = when (tool) {
            Tool.POSITION -> "◀ ▶  ${if (l.left) "Left side" else "Right side"}     ▲ ▼  ${if (l.beside) "Beside the video" else "Over the video"}"
            Tool.SIZE -> if (l.beside) "◀ ▶  width ${l.width}dp" else "◀ ▶  width ${l.width}dp     ▲ ▼  height ${l.height}%"
            Tool.PLACEMENT -> "▲ ▼  move ${when { l.top <= 10 -> "(top)"; l.top >= 90 -> "(bottom)"; else -> "" }}"
        } + "\nOK next  •  BACK save for ${act.channel?.username ?: "this channel"}  •  ☰ save for all channels"
    }

    private fun update(l: ChatLayout, animate: Boolean = false) {
        act.chat.layout = l
        act.chat.applyLayout(animate)
        refresh()
    }

    /** Handles keys while open; true when used. */
    fun handle(event: KeyEvent): Boolean {
        if (!showing) return false
        val l = act.chat.layout
        // Back and ☰ act when released, so their release doesn't reach the player afterwards.
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> saveForChannel()
                KeyEvent.KEYCODE_MENU -> {
                    act.prefs.saveDefaultChatLayout(l)
                    act.prefs.clearChatLayout(act.slug)
                    close("Chat layout saved for all channels")
                }
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val tools = Tool.entries.filter { it != Tool.PLACEMENT || !l.beside }
                tool = tools[(tools.indexOf(tool) + 1) % tools.size]
                refresh()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                when (tool) {
                    Tool.POSITION -> update(l.copy(side = if (dir > 0) "right" else "left"), animate = true)
                    Tool.SIZE -> update(l.copy(width = (l.width + dir * 20).coerceIn(ChatLayout.MIN_WIDTH, ChatLayout.MAX_WIDTH)))
                    Tool.PLACEMENT -> Unit
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                when (tool) {
                    Tool.POSITION -> update(l.copy(mode = if (dir < 0) "side" else "overlay"), animate = true)
                    Tool.SIZE -> if (!l.beside) update(l.copy(height = (l.height - dir * 10).coerceIn(30, 100)))
                    Tool.PLACEMENT -> update(l.copy(top = (l.top + dir * 10).coerceIn(0, 100)))
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> Unit
            else -> return false
        }
        return true
    }
}
