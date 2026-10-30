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
