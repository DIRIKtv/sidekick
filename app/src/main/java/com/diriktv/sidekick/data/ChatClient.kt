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
    /** For a reply: who and what it answers. */
    val replyTo: String? = null,
    val replyText: String? = null,
)

/** Chat's restrictions, as Kick reports them for the chatroom. */
data class ChatModes(
    val slowSeconds: Int = 0,
    val followersOnly: Boolean = false,
    /** Minutes someone must have followed for, in followers-only mode. */
    val followMinutes: Int = 0,
    val subsOnly: Boolean = false,
    val emotesOnly: Boolean = false,
) {
    /** "Slow mode 10s • Followers only (10 min)", or null when chat is open to everyone. */
    fun describe(): String? = listOfNotNull(
        if (slowSeconds > 0) "Slow mode ${slowSeconds}s" else null,
        if (followersOnly) "Followers only" + if (followMinutes > 0) " (${followMinutes} min)" else "" else null,
        if (subsOnly) "Subscribers only" else null,
        if (emotesOnly) "Emotes only" else null,
    ).joinToString("  •  ").ifEmpty { null }
}

/** Chat modes from a chatroom object (the channel API's "chatroom", or a ChatroomUpdatedEvent). */
fun parseChatModes(o: JSONObject): ChatModes {
    fun mode(key: String) = o.optJSONObject(key)
    val slow = mode("slow_mode")
    val followers = mode("followers_mode")
    return ChatModes(
        slowSeconds = when {
            slow != null -> if (slow.optBoolean("enabled")) slow.optInt("message_interval") else 0
            o.optBoolean("slow_mode") -> o.optInt("message_interval")
            else -> 0
        },
        followersOnly = followers?.optBoolean("enabled") ?: o.optBoolean("followers_mode"),
        followMinutes = followers?.optInt("min_duration") ?: o.optInt("following_min_duration"),
        subsOnly = mode("subscribers_mode")?.optBoolean("enabled") ?: o.optBoolean("subscribers_mode"),
        emotesOnly = mode("emotes_mode")?.optBoolean("enabled") ?: o.optBoolean("emotes_mode"),
    )
}

/** Something that happened in chat other than a message, as a line for chat to show. */
data class ChatEvent(val text: String)

/** "x was banned" / "x was timed out for 10 minutes", from a UserBannedEvent. Returns the user and the line. */
fun parseBan(data: JSONObject): Pair<String, String>? {
    val user = data.optJSONObject("user")?.optStringOrNull("username") ?: return null
    val minutes = data.optInt("duration")
    val permanent = data.optBoolean("permanent") || minutes <= 0
    val text = if (permanent) "$user was banned" else "$user was timed out for ${BanLength.of(minutes)}"
    return user to text
}

private object BanLength {
    fun of(m: Int) = when {
        m % 1440 == 0 -> "${m / 1440} day" + if (m / 1440 != 1) "s" else ""
        m % 60 == 0 -> "${m / 60} hour" + if (m / 60 != 1) "s" else ""
        else -> "$m minute" + if (m != 1) "s" else ""
    }
}

/** A line for a subscription, gifted subs or a host, or null for anything else. */
fun parseChatEvent(event: String, data: JSONObject): ChatEvent? = when (event) {
    "App\\Events\\SubscriptionEvent" -> data.optStringOrNull("username")?.let { u ->
        val months = data.optInt("months")
        ChatEvent(if (months > 1) "$u subscribed for $months months" else "$u subscribed")
    }
    "App\\Events\\GiftedSubscriptionsEvent" -> {
        val gifter = data.optStringOrNull("gifter_username") ?: "Someone"
        val n = data.optJSONArray("gifted_usernames")?.length() ?: 0
        if (n <= 0) null else ChatEvent(if (n == 1) "$gifter gifted a sub to ${data.getJSONArray("gifted_usernames").optString(0)}" else "$gifter gifted $n subs")
    }
    "App\\Events\\StreamHostEvent" -> data.optStringOrNull("host_username")?.let { h ->
        val n = data.optInt("number_viewers")
        ChatEvent(if (n > 0) "$h is hosting with $n viewers" else "$h is hosting")
    }
    else -> null
}

/** A poll or prediction shown above chat. Option amounts are votes (polls) or points (predictions). */
data class ChatPoll(
    val kind: String,
    val title: String,
    val options: List<Option>,
    val secondsLeft: Int,
    val state: String = "ACTIVE",
) {
    data class Option(val label: String, val amount: Long, val won: Boolean = false)

    val total get() = options.sumOf { it.amount }

    companion object {
        const val POLL = "poll"
        const val PREDICTION = "prediction"
    }
}

/** A poll as Kick sends it, from its API or a PollUpdateEvent. */
fun parsePoll(p: JSONObject): ChatPoll? {
    val opts = p.optJSONArray("options") ?: return null
    return ChatPoll(
        kind = ChatPoll.POLL,
        title = p.optString("title"),
        options = (0 until opts.length()).map { i ->
            val o = opts.getJSONObject(i)
            ChatPoll.Option(o.optString("label"), o.optLong("votes"))
        },
        secondsLeft = p.optInt("remaining", 0),
        state = if (p.optInt("remaining", 1) <= 0) "ENDED" else "ACTIVE",
    )
}

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
    // The message a reply answers; Kick sends metadata as an object, or as JSON in a string.
    val meta = data.optJSONObject("metadata")
        ?: data.optStringOrNull("metadata")?.let { runCatching { JSONObject(it) }.getOrNull() }
    val replyTo = meta?.optJSONObject("original_sender")?.optStringOrNull("username")
    val replyText = meta?.optJSONObject("original_message")?.optStringOrNull("content")
    return ChatMessage(
        replyTo = replyTo,
        replyText = replyText?.takeIf { replyTo != null },
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
    /** The pinned message changed: the new one, or null when it was unpinned. */
    private val onPinned: (ChatMessage?) -> Unit = {},
    /** A poll started or its results changed; null when it ended. */
    private val onPoll: (ChatPoll?) -> Unit = {},
    /** A user was banned or timed out: their name, and a line saying so. */
    private val onBan: (String, String) -> Unit = { _, _ -> },
    /** A moderator cleared chat. */
    private val onCleared: () -> Unit = {},
    /** A sub, gifted subs or a host. */
    private val onEvent: (ChatEvent) -> Unit = {},
    /** Slow, followers-only, subscribers-only or emotes-only mode changed. */
    private val onModes: (ChatModes) -> Unit = {},
    /** The broadcast ended. */
    private val onStreamEnded: () -> Unit = {},
    /** Something about a prediction changed (its details come from the API). */
    private val onPrediction: () -> Unit = {},
    /** Back after an outage: messages may have been missed meanwhile. */
    private val onReconnected: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    // Touched from OkHttp's socket thread and the main thread.
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var retries = 0

    /** Runs [block] on the main thread, unless the client was closed before it gets there. */
    private fun post(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

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

    /** Set once "reconnecting" has been said for the current outage; cleared when chat is back. */
    @Volatile private var announcedOutage = false
    /** Lost the connection at least once since the last subscription: fetch what was missed. */
    @Volatile private var wasDown = false

    private fun reconnect() {
        if (closed) return
        // One line per outage, not one per failed attempt.
        if (!announcedOutage) {
            announcedOutage = true
            post { onStatus("Chat disconnected, reconnecting…") }
        }
        val delay = (1000L shl retries.coerceAtMost(5)).coerceAtMost(30_000L)
        retries++
        main.postDelayed({ connect() }, delay)
        wasDown = true
    }

    private fun handle(ws: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("event")) {
            "pusher:connection_established" -> {
                retries = 0
                // Predictions have a channel of their own; their events only prompt a fresh look-up.
                val channels = listOf("chatrooms.$chatroomId.v2") +
                    (if (channelId > 0) listOf("channel.$channelId", "predictions-channel-$channelId") else emptyList())
                for (name in channels) {
                    val sub = JSONObject()
                        .put("event", "pusher:subscribe")
                        .put("data", JSONObject().put("auth", "").put("channel", name))
                    ws.send(sub.toString())
                }
            }
            "pusher_internal:subscription_succeeded" ->
                if (msg.optString("channel").startsWith("chatrooms.")) {
                    announcedOutage = false
                    val missed = wasDown
                    wasDown = false
                    post { onStatus("Connected to chat") }
                    if (missed) post { onReconnected() }
                }
            "pusher:ping" -> ws.send("""{"event":"pusher:pong","data":{}}""")
            "App\\Events\\ChatMessageEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val chat = parseChatMessage(data)?.copy(receivedAt = System.currentTimeMillis()) ?: return
                post { onMessage(chat) }
            }
            "App\\Events\\PinnedMessageCreatedEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val pinned = data.optJSONObject("message")?.let { parseChatMessage(it) } ?: return
                post { onPinned(pinned) }
            }
            "App\\Events\\PinnedMessageDeletedEvent" -> post { onPinned(null) }
            "App\\Events\\PollUpdateEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val poll = data.optJSONObject("poll")?.let { parsePoll(it) } ?: return
                post { onPoll(poll) }
            }
            "App\\Events\\PollDeleteEvent" -> post { onPoll(null) }
            "App\\Events\\ChatMoveToSupportedChannelEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val target = parseRaid(data) ?: return
                post { onRaid(target) }
            }
            "App\\Events\\UserBannedEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val (user, line) = parseBan(data) ?: return
                post { onBan(user, line) }
            }
            "App\\Events\\ChatroomClearEvent" -> post { onCleared() }
            "App\\Events\\ChatroomUpdatedEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val modes = parseChatModes(data)
                post { onModes(modes) }
            }
            "App\\Events\\StopStreamBroadcast" -> post { onStreamEnded() }
            "App\\Events\\SubscriptionEvent", "App\\Events\\GiftedSubscriptionsEvent", "App\\Events\\StreamHostEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val event = parseChatEvent(msg.optString("event"), data) ?: return
                post { onEvent(event) }
            }
            "App\\Events\\MessageDeletedEvent" -> {
                val data = try { JSONObject(msg.optString("data")) } catch (e: Exception) { return }
                val id = data.optJSONObject("message")?.optString("id") ?: return
                post { onDeleted(id) }
            }
            else -> if (msg.optString("event").contains("Prediction", ignoreCase = true)) post { onPrediction() }
        }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        socket?.close(1000, null)
        socket = null
    }
}
