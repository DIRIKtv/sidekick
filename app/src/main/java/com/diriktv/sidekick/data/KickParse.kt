package com.diriktv.sidekick.data

import com.diriktv.sidekick.ui.Fmt
import com.diriktv.sidekick.ui.Img
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns Kick's JSON answers into the app's models. Kept apart from the network code (and free of
 * Android) so it can be tested against saved answers: Kick changes its response shapes now and
 * then, and a test catches that sooner than a viewer does.
 */
object KickParse {
    fun livestreams(text: String): Page<LiveStream> {
        val data = JSONObject(text).getJSONObject("data")
        val arr = data.optJSONArray("livestreams") ?: JSONArray()
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ch = o.optJSONObject("channel") ?: JSONObject()
            LiveStream(
                slug = ch.optString("slug"),
                username = ch.optString("username", ch.optString("slug")),
                title = o.optString("title"),
                viewers = o.optInt("viewer_count"),
                thumbnail = o.optJSONObject("thumbnail")?.optStringOrNull("src"),
                avatar = ch.optStringOrNull("profile_pic"),
                category = o.optJSONObject("category")?.optStringOrNull("name"),
                language = o.optStringOrNull("language"),
                startTime = o.optStringOrNull("start_time"),
            )
        }.filter { it.slug.isNotEmpty() }
        val next = data.optJSONObject("pagination")?.optStringOrNull("next_cursor")
        return Page(items, if (items.isEmpty()) null else next)
    }

    fun categories(text: String, page: Int): Page<Category> {
        val json = JSONObject(text)
        val arr = json.optJSONArray("data") ?: JSONArray()
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Category(
                id = o.optInt("id"),
                name = o.optString("name"),
                slug = o.optString("slug"),
                viewers = o.optInt("viewers"),
                banner = o.optJSONObject("banner")?.let { b ->
                    Img.fromSrcset(b.optStringOrNull("responsive"), 340) ?: b.optStringOrNull("url")
                },
            )
        }
        val next = if (json.optStringOrNull("next_page_url") != null) (page + 1).toString() else null
        return Page(items, next)
    }

    fun channel(text: String): ChannelInfo {
        val o = JSONObject(text)
        val user = o.optJSONObject("user") ?: JSONObject()
        val live = o.optJSONObject("livestream")
        val liveCats = live?.optJSONArray("categories")
        return ChannelInfo(
            id = o.optInt("id"),
            userId = o.optInt("user_id", user.optInt("id")),
            slug = o.optString("slug"),
            username = user.optString("username", o.optString("slug")),
            bio = user.optStringOrNull("bio"),
            avatar = user.optStringOrNull("profile_pic"),
            banner = o.optJSONObject("banner_image")?.optStringOrNull("url")
                ?: o.optJSONObject("offline_banner_image")?.optStringOrNull("src"),
            followers = o.optInt("followers_count"),
            verified = o.opt("verified").let { it != null && it != JSONObject.NULL && it != false },
            chatroomId = o.optJSONObject("chatroom")?.optInt("id") ?: 0,
            chatModes = o.optJSONObject("chatroom")?.let { parseChatModes(it) } ?: ChatModes(),
            playbackUrl = o.optStringOrNull("playback_url"),
            isLive = live != null && live.optBoolean("is_live", true),
            liveTitle = live?.optStringOrNull("session_title"),
            liveViewers = live?.optInt("viewer_count") ?: 0,
            liveCategory = if (liveCats != null && liveCats.length() > 0) liveCats.getJSONObject(0).optStringOrNull("name") else null,
            liveStartTime = live?.optStringOrNull("start_time"),
            liveThumbnail = live?.optJSONObject("thumbnail")?.optStringOrNull("url"),
            subscriberBadges = o.optJSONArray("subscriber_badges")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val b = arr.getJSONObject(i)
                    val url = b.optJSONObject("badge_image")?.optStringOrNull("src") ?: return@mapNotNull null
                    b.optInt("months") to url
                }.sortedBy { it.first }
            }.orEmpty(),
            socials = socials(user),
        )
    }

    /** The channel's links, as (name, address) in a fixed order; Kick stores most as a handle or path. */
    private fun socials(user: JSONObject): List<Pair<String, String>> {
        fun value(key: String) = user.optStringOrNull(key)?.trim()?.trim('/')?.takeIf { it.isNotEmpty() && it != "None" }
        fun link(key: String, name: String, base: String) = value(key)?.let { v ->
            name to if (v.startsWith("http")) v else base + v
        }
        return listOfNotNull(
            link("instagram", "Instagram", "https://instagram.com/"),
            link("twitter", "X", "https://x.com/"),
            link("youtube", "YouTube", "https://youtube.com/"),
            link("tiktok", "TikTok", "https://tiktok.com/@"),
            link("discord", "Discord", "https://discord.gg/"),
            link("facebook", "Facebook", "https://facebook.com/"),
        )
    }

    /** /channels/<slug>/livestream: the current broadcast, or null when offline. */
    fun liveStream(text: String, slug: String, username: String, avatar: String?): LiveStream? =
        JSONObject(text).optJSONObject("data")?.let {
            LiveStream(
                slug = slug,
                username = username,
                title = it.optString("session_title"),
                viewers = it.optInt("viewers"),
                thumbnail = it.optJSONObject("thumbnail")?.optStringOrNull("src"),
                avatar = avatar,
                category = it.optJSONObject("category")?.optStringOrNull("name"),
                language = it.optStringOrNull("language"),
                startTime = it.optStringOrNull("created_at"),
            )
        }

    fun clips(text: String, cursor: String?): Page<Clip> {
        val json = JSONObject(text)
        val arr = json.optJSONArray("clips") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val src = o.optStringOrNull("video_url") ?: o.optStringOrNull("clip_url") ?: return@mapNotNull null
            val ch = o.optJSONObject("channel") ?: JSONObject()
            Clip(
                id = o.optString("id"),
                title = o.optString("title"),
                source = src,
                thumbnail = o.optStringOrNull("thumbnail_url"),
                durationMs = o.optLong("duration") * 1000,
                views = o.optInt("view_count", o.optInt("views")),
                createdAt = o.optStringOrNull("created_at"),
                category = o.optJSONObject("category")?.optStringOrNull("name"),
                channelSlug = ch.optString("slug"),
                channelName = ch.optString("username", ch.optString("slug")),
                channelAvatar = ch.optStringOrNull("profile_picture"),
            )
        }.filter { it.channelSlug.isNotEmpty() }
        val next = json.optStringOrNull("nextCursor")
        return Page(items, if (items.isEmpty() || next == cursor) null else next)
    }

    fun videos(text: String): List<Video> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val source = o.optStringOrNull("source") ?: return@mapNotNull null
            val video = o.optJSONObject("video")
            val cats = o.optJSONArray("categories")
            Video(
                uuid = video?.optStringOrNull("uuid") ?: o.optString("slug"),
                title = o.optString("session_title"),
                source = source,
                thumbnail = o.optJSONObject("thumbnail")?.optStringOrNull("src"),
                durationMs = o.optLong("duration"),
                views = video?.optInt("views") ?: o.optInt("views"),
                createdAt = o.optStringOrNull("start_time") ?: o.optStringOrNull("created_at"),
                category = if (cats != null && cats.length() > 0) cats.getJSONObject(0).optStringOrNull("name") else null,
                isLive = o.optBoolean("is_live"),
            )
        }
    }

    fun search(text: String): SearchResults {
        val json = JSONObject(text)
        val arr = json.optJSONArray("channels") ?: JSONArray()
        val channels = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val user = o.optJSONObject("user") ?: JSONObject()
            SearchChannel(
                slug = o.optString("slug"),
                username = user.optString("username", o.optString("slug")),
                avatar = user.optStringOrNull("profilePic") ?: user.optStringOrNull("profile_pic"),
                followers = o.optInt("followers_count", o.optInt("followersCount")),
                isLive = o.optBoolean("isLive"),
                verified = o.opt("verified").let { it != null && it != JSONObject.NULL && it != false },
            )
        }.filter { it.slug.isNotEmpty() }
        val cats = json.optJSONArray("categories") ?: JSONArray()
        val categories = (0 until cats.length()).map { i ->
            val o = cats.getJSONObject(i)
            Category(
                id = o.optInt("id"),
                name = o.optString("name"),
                slug = o.optString("slug"),
                viewers = o.optInt("viewers"),
                banner = o.optJSONObject("banner")?.let { b ->
                    Img.fromSrcset(b.optStringOrNull("srcset"), 340) ?: b.optStringOrNull("src")
                },
            )
        }.filter { it.id != 0 }
        return SearchResults(channels, categories)
    }

    fun recentChat(text: String): KickApi.RecentChat {
        val data = JSONObject(text).optJSONObject("data")
        val pinned = data?.optJSONObject("pinned_message")?.optJSONObject("message")?.let { parseChatMessage(it) }
        val arr = data?.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> runCatching { parseChatMessage(o) }.getOrNull() } }
        // Kick lists them newest first.
        return KickApi.RecentChat(pinned, messages.sortedBy { it.receivedAt })
    }

    /** How long a decided (or cancelled) prediction stays up so the result can be seen. */
    const val DECIDED_SHOW_MS = 2 * 60_000L
    /** A prediction locked this long ago without a result has been forgotten about. */
    const val LOCKED_SHOW_MS = 2 * 60 * 60_000L

    /**
     * The channel's latest prediction, or null when it isn't worth showing. Kick returns the
     * latest one however old: from an earlier broadcast, locked hours ago and never decided, or
     * decided long ago. [streamStartMs] is when the current broadcast began (0 if unknown).
     */
    fun prediction(text: String, now: Long = System.currentTimeMillis(), streamStartMs: Long = 0): ChatPoll? {
        val p = JSONObject(text).optJSONObject("data")?.optJSONObject("prediction") ?: return null
        val outcomes = p.optJSONArray("outcomes") ?: JSONArray()
        val state = p.optString("state").uppercase()
        val created = Fmt.parseUtc(p.optStringOrNull("created_at")) ?: return null
        val updated = Fmt.parseUtc(p.optStringOrNull("updated_at")) ?: created
        val locked = Fmt.parseUtc(p.optStringOrNull("locked_at"))
        val ends = created + p.optInt("duration") * 1000L
        // Made during an earlier broadcast.
        if (streamStartMs > 0 && created < streamStartMs - 60_000) return null
        when (state) {
            "RESOLVED", "CANCELLED" -> if (now - updated > DECIDED_SHOW_MS) return null
            "LOCKED" -> if (now - (locked ?: updated) > LOCKED_SHOW_MS) return null
            // Voting should have closed long ago but nothing moved it on.
            "ACTIVE" -> if (now - ends > LOCKED_SHOW_MS) return null
            else -> return null
        }
        return ChatPoll(
            kind = ChatPoll.PREDICTION,
            title = p.optString("title"),
            options = (0 until outcomes.length()).map { i ->
                val oc = outcomes.getJSONObject(i)
                ChatPoll.Option(oc.optString("title"), oc.optLong("total_vote_amount"), oc.optString("id") == p.optString("winning_outcome_id"),
                    id = oc.optString("id"))
            },
            secondsLeft = if (state == "ACTIVE") ((ends - now) / 1000).toInt().coerceAtLeast(0) else 0,
            state = state,
            id = p.optString("id"),
        )
    }

    /** The newest broadcast's end time (UTC ms) from a channel's past broadcasts, or null. */
    fun lastLive(videos: List<Video>): Long? = videos.mapNotNull { v ->
        Fmt.parseUtc(v.createdAt)?.let { it + (if (v.isLive) 0 else v.durationMs) }
    }.maxOrNull()
}
