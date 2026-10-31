package com.diriktv.sidekick.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(
    val slug: String,
    val username: String,
    val avatar: String?,
    /** Live state from the account's follow list; null when unknown (favorites saved on the TV). */
    val live: Boolean? = null,
)

/** Local settings, favorites and watch history. */
class Prefs(context: Context) {
    companion object {
        val CHAT_TEXT_SIZES = listOf(13 to "Small", 15 to "Medium", 17 to "Large", 20 to "Extra large")
        val CHAT_WIDTHS = listOf(250 to "Narrow", 300 to "Normal", 360 to "Wide")

        /** Space above and below each chat message, in dp. */
        val CHAT_SPACINGS = listOf(1 to "Compact", 3 to "Comfortable", 6 to "Roomy")
        val EMOTE_SIZES = listOf(1.0f to "Small", 1.25f to "Medium", 1.6f to "Large", 2.0f to "Extra large")

        fun emoteSizeLabel(scale: Float) = EMOTE_SIZES.firstOrNull { it.first == scale }?.second ?: "Medium"

        fun chatWidthLabel(dp: Int) = CHAT_WIDTHS.firstOrNull { it.first == dp }?.second ?: "${dp}dp"
    }

    private val sp: SharedPreferences = context.getSharedPreferences("sidekick", Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString("language", "en") ?: "en"
        set(v) = sp.edit().putString("language", v).apply()

    /** Preferred max video height; 0 = automatic. */
    var preferredQuality: Int
        get() = sp.getInt("quality", 0)
        set(v) = sp.edit().putInt("quality", v).apply()

    var chatEnabled: Boolean
        get() = sp.getBoolean("chat", true)
        set(v) = sp.edit().putBoolean("chat", v).apply()

    /** "side" shrinks the video next to chat, "overlay" floats chat on top. */
    var chatMode: String
        get() = sp.getString("chat_mode", "side") ?: "side"
        set(v) = sp.edit().putString("chat_mode", v).apply()

    var chatTextSize: Int
        get() = sp.getInt("chat_text", 15)
        set(v) = sp.edit().putInt("chat_text", v).apply()

    var lowLatency: Boolean
        get() = sp.getBoolean("low_latency_v2", false)
        set(v) = sp.edit().putBoolean("low_latency_v2", v).apply()

    /** kick.com session token from signing in; sent as a Bearer token. */
    var sessionToken: String?
        get() {
            sp.getString("session_token_enc", null)?.let { return SecureStore.decrypt(it) }
            // Saved unencrypted by an older build: encrypt it now.
            val legacy = sp.getString("session_token", null) ?: return null
            sessionToken = legacy
            return legacy
        }
        set(v) {
            val enc = v?.let { runCatching { SecureStore.encrypt(it) }.getOrNull() }
            sp.edit().remove("session_token").putString("session_token_enc", enc).apply()
        }

    var accountName: String?
        get() = sp.getString("account_name", null)
        set(v) = sp.edit().putString("account_name", v).apply()

    var accountAvatar: String?
        get() = sp.getString("account_avatar", null)
        set(v) = sp.edit().putString("account_avatar", v).apply()

    /** Hide messages from chat bots (bot badge or a well-known bot name). */
    var chatHideBots: Boolean
        get() = sp.getBoolean("chat_hide_bots", false)
        set(v) = sp.edit().putBoolean("chat_hide_bots", v).apply()

    /** Hide "!command" messages. */
    var chatHideCommands: Boolean
        get() = sp.getBoolean("chat_hide_commands", false)
        set(v) = sp.edit().putBoolean("chat_hide_commands", v).apply()

    /** Pace busy chats so messages stay readable, skipping some when chat runs far ahead. */
    var chatCalm: Boolean
        get() = sp.getBoolean("chat_calm", false)
        set(v) = sp.edit().putBoolean("chat_calm", v).apply()

    var sevenTvEmotes: Boolean
        get() = sp.getBoolean("seventv", true)
        set(v) = sp.edit().putBoolean("seventv", v).apply()

    /** Pop up a notice when a followed channel goes live while the app is open. */
    var liveAlerts: Boolean
        get() = sp.getBoolean("live_alerts", true)
        set(v) = sp.edit().putBoolean("live_alerts", v).apply()

    /** Even out volume between streams and between quiet and loud moments. */
    var loudnessEq: Boolean
        get() = sp.getBoolean("loudness_eq", false)
        set(v) = sp.edit().putBoolean("loudness_eq", v).apply()

    var updateSchedule: Updater.Schedule
        get() = Updater.Schedule.of(sp.getString("update_schedule", null))
        set(v) = sp.edit().putString("update_schedule", v.key).apply()

    /** Shrink the player into a corner window when leaving it with Home. */
    var autoPip: Boolean
        get() = sp.getBoolean("auto_pip", true)
        set(v) = sp.edit().putBoolean("auto_pip", v).apply()

    var seekStepSeconds: Int
        get() = sp.getInt("seek_step", 10)
        set(v) = sp.edit().putInt("seek_step", v).apply()

    /** Show the heart in the player for channels already followed, so they can be unfollowed there. */
    var playerUnfollow: Boolean
        get() = sp.getBoolean("player_unfollow", false)
        set(v) = sp.edit().putBoolean("player_unfollow", v).apply()

    /** Play a past broadcast's chat alongside it. */
    var chatReplay: Boolean
        get() = sp.getBoolean("chat_replay", true)
        set(v) = sp.edit().putBoolean("chat_replay", v).apply()

    /** When a channel you're watching raids another: "ask", "auto" or "off". */
    var raidMode: String
        get() = sp.getString("raid_mode", "ask") ?: "ask"
        set(v) = sp.edit().putString("raid_mode", v).apply()

    /** Seconds a card waits in focus before playing a preview; 0 turns previews off. */
    var previewDelay: Int
        get() = sp.getInt("preview_delay", 2)
        set(v) = sp.edit().putInt("preview_delay", v).apply()

    /** A thin progress line on past broadcasts while the controls are hidden. */
    var miniProgress: Boolean
        get() = sp.getBoolean("mini_progress", true)
        set(v) = sp.edit().putBoolean("mini_progress", v).apply()

    /** Home card size: 0 small, 1 medium, 2 large. */
    var cardSize: Int
        get() = sp.getInt("card_size", 1)
        set(v) = sp.edit().putInt("card_size", v).apply()

    /** Space between chat messages: 0 compact, 1 comfortable, 2 roomy. */
    var chatSpacing: Int
        get() = sp.getInt("chat_spacing", 1)
        set(v) = sp.edit().putInt("chat_spacing", v).apply()

    /** Emote size in chat, as a multiple of the text's line height. */
    var chatEmoteScale: Float
        get() = sp.getFloat("chat_emote_scale", 1.25f)
        set(v) = sp.edit().putFloat("chat_emote_scale", v).apply()

    /** Show polls and predictions above chat. */
    var chatPolls: Boolean
        get() = sp.getBoolean("chat_polls", true)
        set(v) = sp.edit().putBoolean("chat_polls", v).apply()

    /** Show the channel's pinned message above chat. */
    var chatPinned: Boolean
        get() = sp.getBoolean("chat_pinned", true)
        set(v) = sp.edit().putBoolean("chat_pinned", v).apply()

    /** Ask before resuming a past broadcast part-way through. */
    var askToResume: Boolean
        get() = sp.getBoolean("ask_resume", true)
        set(v) = sp.edit().putBoolean("ask_resume", v).apply()

    /** Which side chat sits on by default: "right" or "left". */
    var chatSide: String
        get() = sp.getString("chat_side", "right") ?: "right"
        set(v) = sp.edit().putString("chat_side", v).apply()

    /** The chat layout for [slug], or the default one when it has none of its own. */
    fun chatLayout(slug: String?): ChatLayout =
        slug?.let { sp.getString("chat_layout_${it.lowercase()}", null) }?.let { ChatLayout.parse(it) }
            ?: ChatLayout(mode = chatMode, side = chatSide, width = chatWidth,
                height = sp.getInt("chat_height", 100), top = sp.getInt("chat_top", 100),
                x = sp.getInt("chat_x", if (chatSide == "left") 0 else 100), backing = sp.getInt("chat_backing", 60),
                textSize = sp.getInt("chat_layout_text", 0))

    fun saveChatLayout(slug: String, layout: ChatLayout) =
        sp.edit().putString("chat_layout_${slug.lowercase()}", layout.encode()).apply()

    /** Makes [layout] the default for every channel without one of its own. */
    fun saveDefaultChatLayout(layout: ChatLayout) {
        chatMode = layout.mode
        chatSide = layout.side
        chatWidth = layout.width
        sp.edit().putInt("chat_height", layout.height).putInt("chat_top", layout.top)
            .putInt("chat_x", layout.x).putInt("chat_backing", layout.backing)
            .putInt("chat_layout_text", layout.textSize).apply()
    }

    fun hasChatLayout(slug: String) = sp.contains("chat_layout_${slug.lowercase()}")

    fun clearChatLayout(slug: String) = sp.edit().remove("chat_layout_${slug.lowercase()}").apply()

    val channelChatLayouts: Int get() = sp.all.keys.count { it.startsWith("chat_layout_") }

    fun clearChannelChatLayouts() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("chat_layout_") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Pages slide in from the direction you moved in the side menu. */
    var pageTransitions: Boolean
        get() = sp.getBoolean("page_transitions", true)
        set(v) = sp.edit().putBoolean("page_transitions", v).apply()

    /** App typeface key (see AppFonts.Font). */
    var font: String
        get() = sp.getString("font", "figtree") ?: "figtree"
        set(v) = sp.edit().putString("font", v).apply()

    /** Main-screen page the app opens on: 0 Home, 1 Following, 2 Browse, 3 Search. */
    var startPage: Int
        get() = sp.getInt("start_page", 0)
        set(v) = sp.edit().putInt("start_page", v).apply()

    var chatTimestamps: Boolean
        get() = sp.getBoolean("chat_timestamps", false)
        set(v) = sp.edit().putBoolean("chat_timestamps", v).apply()

    /** Width of chat beside the video, in dp. */
    var chatWidth: Int
        get() = sp.getInt("chat_width", 300)
        set(v) = sp.edit().putInt("chat_width", v).apply()

    /** Category names hidden from Home, Browse and Search (lowercase). */
    var hiddenCategories: Set<String>
        get() = sp.getStringSet("hidden_categories", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden_categories", v.map { it.lowercase() }.toSet()).apply()

    fun isHiddenCategory(name: String?) = name != null && name.lowercase() in hiddenCategories

    /** Most recent first. */
    var searchHistory: List<String>
        get() = runCatching {
            val arr = JSONArray(sp.getString("search_history", "[]"))
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        set(list) = sp.edit().putString("search_history", JSONArray(list).toString()).apply()

    fun recordSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        searchHistory = (listOf(t) + searchHistory.filterNot { it.equals(t, true) }).take(12)
    }

    var favorites: List<Favorite>
        get() {
            val arr = JSONArray(sp.getString("favorites", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Favorite(o.getString("slug"), o.optString("username"), o.optStringOrNull("avatar"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("slug", it.slug).put("username", it.username).put("avatar", it.avatar)) }
            sp.edit().putString("favorites", arr.toString()).apply()
        }

    fun isFavorite(slug: String) = favorites.any { it.slug.equals(slug, true) }

    /** Returns the new favorite state. */
    fun toggleFavorite(slug: String, username: String, avatar: String?): Boolean {
        val list = favorites.toMutableList()
        val existing = list.indexOfFirst { it.slug.equals(slug, true) }
        return if (existing >= 0) {
            list.removeAt(existing); favorites = list; false
        } else {
            list.add(0, Favorite(slug, username, avatar)); favorites = list; true
        }
    }

    fun vodPosition(uuid: String): Long = sp.getLong("vod_$uuid", 0L)

    fun saveVodPosition(uuid: String, pos: Long) {
        sp.edit().putLong("vod_$uuid", pos).apply()
    }

    /** Most recent first. */
    var history: List<HistoryEntry>
        get() = runCatching {
            val arr = JSONArray(sp.getString("history", "[]"))
            (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            sp.edit().putString("history", arr.toString()).apply()
        }

    fun recordHistory(entry: HistoryEntry) {
        history = (listOf(entry) + history.filter { it.key != entry.key }).take(60)
    }

    fun clearHistory() {
        val editor = sp.edit().remove("history")
        sp.all.keys.filter { it.startsWith("vod_") }.forEach { editor.remove(it) }
        editor.apply()
    }
}

/** Something watched: a live stream, a past broadcast or a clip. */
data class HistoryEntry(
    val kind: String,
    val slug: String,
    val username: String,
    val avatar: String?,
    val title: String,
    val thumbnail: String?,
    /** VOD uuid or clip id; null for live. */
    val videoId: String? = null,
    val source: String? = null,
    val durationMs: Long = 0,
    val createdAt: String? = null,
    val category: String? = null,
    val watchedAt: Long = System.currentTimeMillis(),
) {
    val key get() = "$kind:${videoId ?: slug.lowercase()}"

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind).put("slug", slug).put("username", username).put("avatar", avatar)
        .put("title", title).put("thumbnail", thumbnail).put("videoId", videoId).put("source", source)
        .put("durationMs", durationMs).put("createdAt", createdAt).put("category", category)
        .put("watchedAt", watchedAt)

    companion object {
        const val LIVE = "live"
        const val VOD = "vod"
        const val CLIP = "clip"

        fun fromJson(o: JSONObject) = HistoryEntry(
            kind = o.optString("kind", LIVE),
            slug = o.getString("slug"),
            username = o.optString("username"),
            avatar = o.optStringOrNull("avatar"),
            title = o.optString("title"),
            thumbnail = o.optStringOrNull("thumbnail"),
            videoId = o.optStringOrNull("videoId"),
            source = o.optStringOrNull("source"),
            durationMs = o.optLong("durationMs"),
            createdAt = o.optStringOrNull("createdAt"),
            category = o.optStringOrNull("category"),
            watchedAt = o.optLong("watchedAt"),
        )
    }
}

/**
 * Where chat sits and how big it is. [mode] "side" puts it beside the video (the video shrinks),
 * "overlay" floats it on top. [height] (percent of the screen) and [top] (percent of the space
 * left over, 0 = top, 100 = bottom) only apply over the video.
 */
data class ChatLayout(
    val mode: String = "side",
    val side: String = "right",
    val width: Int = 300,
    val height: Int = 100,
    /** Over the video: how far down the free space the chat sits, 0 (top) to 100 (bottom). */
    val top: Int = 100,
    /** Over the video: how far across the free space, 0 (left edge) to 100 (right edge). */
    val x: Int = if (side == "left") 0 else 100,
    /** Over the video: how dark the chat's backing is, 0 (none) to 100. */
    val backing: Int = 60,
    /** Message text size in sp for this layout; 0 follows the app-wide chat text size. */
    val textSize: Int = 0,
) {
    val beside get() = mode == "side"
    val left get() = side == "left"

    fun encode(): String = JSONObject().put("mode", mode).put("side", side).put("width", width)
        .put("height", height).put("top", top).put("x", x).put("backing", backing)
        .put("textSize", textSize).toString()

    companion object {
        const val MIN_WIDTH = 200
        const val MAX_WIDTH = 520
        /** Over the video chat can be smaller, like the little chat boxes streamers put on screen. */
        const val MIN_OVERLAY_WIDTH = 140
        const val MIN_HEIGHT = 15
        const val MIN_TEXT = 10
        const val MAX_TEXT = 24

        fun parse(s: String): ChatLayout? = runCatching {
            val o = JSONObject(s)
            val side = o.optString("side", "right")
            ChatLayout(o.optString("mode", "side"), side, o.optInt("width", 300),
                o.optInt("height", 100), o.optInt("top", 100),
                o.optInt("x", if (side == "left") 0 else 100), o.optInt("backing", 60), o.optInt("textSize", 0))
        }.getOrNull()
    }
}
