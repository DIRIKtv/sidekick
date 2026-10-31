package com.diriktv.sidekick.player

import android.graphics.DashPathEffect
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
 * Arranging chat with the remote, live on the stream: OK steps through the tools, the arrows
 * use the current one, Back saves it for this channel and ☰ makes it the default for every
 * channel. Beside the video chat takes a side and a width; over the video it can go anywhere,
 * at any size, with as much or as little dark backing as you like. A card at the top of the
 * screen says what the arrows do right now, and a dashed outline shows the chat's edges.
 */
internal class ChatLayoutEditor(private val act: PlayerActivity) {
    private enum class Tool(val title: String) { MODE("Layout"), MOVE("Move"), SIZE("Size"), BACKING("Backing") }

    private var tool = Tool.MODE
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
    private val panel: View by lazy { act.findViewById(R.id.chatPanel) }
    private val outline = ShapeDrawable(RectShape()).apply {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = act.dp(2).toFloat()
        paint.color = act.getColor(R.color.kick_green)
        paint.pathEffect = DashPathEffect(floatArrayOf(act.dp(8).toFloat(), act.dp(6).toFloat()), 0f)
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
        tool = if (act.chat.layout.beside) Tool.MODE else Tool.MOVE
        val root = act.findViewById<ViewGroup>(android.R.id.content)
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = act.dp(24) })
        card.fadeIn(180, fromY = -act.dp(10).toFloat())
        panel.foreground = outline
        refresh()
    }

    private fun close(message: String) {
        showing = false
        // Arranging chat means wanting to see it: keep it on next time too.
        act.prefs.chatEnabled = true
        panel.foreground = null
        (card.parent as? ViewGroup)?.removeView(card)
        act.controls.flashOsd(message, R.drawable.ic_layout)
    }

    fun saveForChannel() {
        if (!showing) return
        act.prefs.saveChatLayout(act.slug, act.chat.layout)
        close("Chat layout saved for ${act.channel?.username ?: "this channel"}")
    }

    /** Beside the video only the layout and the width can change. */
    private fun tools(l: ChatLayout) = if (l.beside) listOf(Tool.MODE, Tool.SIZE) else Tool.entries

    private fun refresh() {
        val l = act.chat.layout
        val tools = tools(l)
        if (tool !in tools) tool = Tool.MODE
        tabs.text = tools.joinToString("     ") { if (it == tool) "●  ${it.title}" else it.title }
        hint.text = when (tool) {
            Tool.MODE -> "▲ ▼  ${if (l.beside) "Beside the video" else "Over the video"}     ◀ ▶  " +
                if (l.beside) (if (l.left) "Left side" else "Right side") else "jump to an edge"
            Tool.MOVE -> "◀ ▶ ▲ ▼  move it anywhere (hold to go faster)"
            Tool.SIZE -> if (l.beside) "◀ ▶  width ${l.width}dp" else "◀ ▶  width ${l.width}dp     ▲ ▼  height ${l.height}%"
            Tool.BACKING -> "◀ ▶  backing ${if (l.backing == 0) "none" else "${l.backing}%"}"
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
        // Single presses make fine adjustments; holding the button speeds up.
        val fast = event.repeatCount > 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val tools = tools(l)
                tool = tools[(tools.indexOf(tool) + 1) % tools.size]
                refresh()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                when (tool) {
                    Tool.MODE -> if (l.beside) update(l.copy(side = if (dir > 0) "right" else "left"), animate = true)
                        else update(l.copy(x = if (dir > 0) 100 else 0, side = if (dir > 0) "right" else "left"))
                    Tool.MOVE -> update(l.copy(x = (l.x + dir * (if (fast) 3 else 1)).coerceIn(0, 100)))
                    Tool.SIZE -> {
                        val min = if (l.beside) ChatLayout.MIN_WIDTH else ChatLayout.MIN_OVERLAY_WIDTH
                        update(l.copy(width = (l.width + dir * (if (fast) 20 else 10)).coerceIn(min, ChatLayout.MAX_WIDTH)))
                    }
                    Tool.BACKING -> update(l.copy(backing = (l.backing + dir * 10).coerceIn(0, 100)))
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                when (tool) {
                    Tool.MODE -> {
                        val over = dir > 0
                        if (over == !l.beside) return true
                        // Beside chat is always at least the side-panel width.
                        val width = if (over) l.width else l.width.coerceAtLeast(ChatLayout.MIN_WIDTH)
                        update(l.copy(mode = if (over) "overlay" else "side", width = width), animate = true)
                    }
                    Tool.MOVE -> update(l.copy(top = (l.top + dir * (if (fast) 3 else 1)).coerceIn(0, 100)))
                    Tool.SIZE -> if (!l.beside) update(l.copy(height = (l.height - dir * (if (fast) 5 else 2)).coerceIn(ChatLayout.MIN_HEIGHT, 100)))
                    Tool.BACKING -> Unit
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> Unit
            else -> return false
        }
        return true
    }
}
