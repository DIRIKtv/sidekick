package com.diriktv.sidekick.data

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** A role badge (moderator, subscriber…); [count] is months or gifts where relevant. */
data class ChatBadge(val type: String, val count: Int = 0)

data class ChatMessage(
    val id: String,
    val username: String,
    val color: String?,
    val content: String,
    val badges: List<ChatBadge>,
    val isSystem: Boolean = false,
    /** Image URLs of the global badges the sender chose to show (events, achievements). */
    val badgeImages: List<String> = emptyList(),
    val receivedAt: Long = System.currentTimeMillis(),
)

/** Where a raid (or host) sent the channel's viewers. */
data class RaidTarget(val slug: String, val username: String, val viewers: Int)

/**
 * A chat message in the shape Kick uses both live (ChatMessageEvent) and in a past broadcast's
 * chat history; null for anything without a sender.
 */
fun parseChatMessage(data: JSONObject): ChatMessage? {
    val sender = data.optJSONObject("sender") ?: return null
    val identity = sender.optJSONObject("identity")
    val badges = mutableListOf<ChatBadge>()
    identity?.optJSONArray("badges")?.let { arr ->
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            badges += ChatBadge(b.optString("type"), b.optInt("count"))
        }
    }
    val images = mutableListOf<String>()
    identity?.optJSONArray("badges_v2")?.let { arr ->
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            if (b.optBoolean("selected")) b.optStringOrNull("image_url")?.let { images += it }
        }
    }
    return ChatMessage(
        id = data.optString("id"),
        username = sender.optString("username"),
        color = identity?.optStringOrNull("color"),
        content = data.optString("content"),
        badges = badges,
        badgeImages = images,
        receivedAt = com.diriktv.sidekick.ui.Fmt.parseUtc(data.optStringOrNull("created_at")) ?: System.currentTimeMillis(),
    )
}

/**
 * Where a ChatMoveToSupportedChannelEvent sends viewers. Kick has shipped a couple of shapes for
 * it, so this looks for the target in each of them.
 */
fun parseRaid(data: JSONObject): RaidTarget? {
    val hosted = data.optJSONObject("hosted")
    val slug = hosted?.optStringOrNull("slug") ?: data.optStringOrNull("slug")
        ?: hosted?.optStringOrNull("username")?.lowercase() ?: return null
    val username = hosted?.optStringOrNull("username") ?: slug
    val viewers = hosted?.optInt("viewers_count", hosted.optInt("number_viewers")) ?: 0
    return RaidTarget(slug, username, viewers)
}

/**
 * Read-only Kick chat over Pusher. Also listens on the channel itself so it hears raids.
 * Callbacks are delivered on the main thread.
 */
class ChatClient(
    private val chatroomId: Int,
    private val appKey: String,
    private val cluster: String,
    private val onMessage: (ChatMessage) -> Unit,
    private val onDeleted: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val channelId: Int = 0,
    private val onRaid: (RaidTarget) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var closed = false
    private var retries = 0

    fun connect() {
        if (closed) return
        val url = "wss://ws-$cluster.pusher.com/app/$appKey?protocol=7&client=js&version=8.4.0&flash=false"
        socket = KickApi.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Kick occasionally changes event shapes; skip anything unexpected.
                runCatching { handle(webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnect()

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect()
        })
    }

    private fun reconnect() {
        if (closed) return
        main.post { onStatus("Chat disconnected, reconnecting…") }
        val delay = (1000L shl retries.coerceAtMost(5)).coerceAtMost(30_000L)
        retries++
        main.postDelayed({ connect() }, delay)
    }

    private fun handle(ws: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("event")) {
            "pusher:connection_established" -> {
                retries = 0
                val channels = listOf("chatrooms.$chatroomId.v2") + (if (channelId > 0) listOf("channel.$channelId") else emptyList())
                for (name in channels) {
                    val sub = JSONObject()
                        .put("event", "pusher:subscribe")
                        .put("data", JSONObject().put("auth", "").put("channel", name))
                    ws.send(sub.toString())
                }
            }
            "pusher_internal:subscription_succeeded" ->
                if (msg.optString("channel").startsWith("chatrooms.")) main.post { onStatus("Connected to chat") }
            "pusher:ping" -> ws.send("""{"event":"pusher:pong","data":{}}""")
            "App\\Events\\ChatMessageEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val chat = parseChatMessage(data)?.copy(receivedAt = System.currentTimeMillis()) ?: return
                main.post { onMessage(chat) }
            }
            "App\\Events\\ChatMoveToSupportedChannelEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val target = parseRaid(data) ?: return
                main.post { onRaid(target) }
            }
            "App\\Events\\MessageDeletedEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val id = data.optJSONObject("message")?.optString("id") ?: return
                main.post { onDeleted(id) }
            }
        }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        socket?.close(1000, null)
        socket = null
    }
}
