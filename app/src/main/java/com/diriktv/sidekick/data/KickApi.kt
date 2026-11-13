package com.diriktv.sidekick.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Thin client over Kick's public web endpoints. */
object KickApi {
    /** The Chrome version claimed when the TV's own WebView can't tell us a newer one. */
    private const val FALLBACK_CHROME = 142

    /**
     * A desktop Chrome user agent. Cloudflare is warier of browsers that are years out of date, so
     * it claims the same Chrome version as the TV's (auto-updating) WebView; see [useChromeVersion].
     */
    @Volatile var userAgent = desktopAgent(FALLBACK_CHROME)
        private set

    private fun desktopAgent(major: Int) =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

    /** Called at start-up with the WebView's version name ("142.0.7444.0"); never goes older than the fallback. */
    fun useChromeVersion(versionName: String?) {
        val major = versionName?.substringBefore('.')?.toIntOrNull() ?: return
        if (major in (FALLBACK_CHROME + 1)..999) userAgent = desktopAgent(major)
    }

    /** Called (on an OkHttp thread) when Kick rejects the saved session with a 401. */
    @Volatile var onSessionRejected: (() -> Unit)? = null

    // Fallback if the realtime connection endpoint stops answering.
    const val DEFAULT_PUSHER_KEY = "34bf7a0ff419a2a775b9"
    const val DEFAULT_PUSHER_CLUSTER = "us2"

    /** Set when signed in; added to kick.com API calls. */
    @Volatile var authToken: String? = null

    /**
     * Released once the saved session has been read at start-up (it's decrypted off the main
     * thread). Calls that act for the account wait for it, so none goes out unsigned by mistake.
     */
    val sessionLoaded = java.util.concurrent.CountDownLatch(1)

    /** At most this many kick.com API calls in flight; bursts (one lookup per follow) got rate limited. */
    private val apiSlots = Semaphore(6)

    /**
     * Background lookups (follower counts for offline follows) run at most two at a time, so the
     * other slots stay free for what the viewer is waiting on, like opening a stream.
     */
    private val lowSlots = Semaphore(2)

    /** Tag for a request that can wait behind everything else; see [lowSlots]. */
    private object LowPriority

    /** After a 429, every API call waits until this time. */
    @Volatile private var coolDownUntil = 0L

    private fun isApi(url: HttpUrl) =
        (url.host == "kick.com" || url.host.endsWith(".kick.com")) && url.encodedPath.startsWith("/api/")

    /**
     * Calls that act for the signed-in account. Everything else goes out without the token, so
     * Cloudflare can answer from its cache and the calls don't count against the account's limit.
     */
    private fun needsAuth(url: HttpUrl): Boolean {
        val path = url.encodedPath
        return path.startsWith("/api/v2/channels/followed") || path.endsWith("/follow") || path == "/api/v1/user" ||
            path.startsWith("/api/v2/messages/send/") || path.endsWith("/polls/vote") || path.endsWith("/predictions/vote")
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        // Images and playlists come from a few hosts; the default of 5 per host queues thumbnails.
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder().header("User-Agent", userAgent)
            if (!isApi(req.url)) return@addInterceptor chain.proceed(b.build())
            if (req.header("Accept") == null) b.header("Accept", "application/json")
            // Only the saved session counts: sign-in checks a fresh token with its own header.
            val auth = needsAuth(req.url) && req.header("Authorization") == null
            if (auth) sessionLoaded.await(5, TimeUnit.SECONDS)
            val sentToken = if (auth) authToken else null
            sentToken?.let { b.header("Authorization", "Bearer $it") }
            val resp = throttled(chain, b.build())
            if (resp.code == 401 && sentToken != null && sentToken == authToken) onSessionRejected?.invoke()
            resp
        }
        .build()

    /**
     * Runs an API call inside the concurrency limit, waiting out and retrying 429s a couple of
     * times. A call cancelled while it waits for a slot (a search overtaken by the next keystroke)
     * gives up straight away instead of holding a slot for nothing.
     */
    private fun throttled(chain: Interceptor.Chain, req: Request): Response {
        val low = req.tag(LowPriority::class.java) != null
        if (low) acquire(lowSlots, chain)
        try {
            acquire(apiSlots, chain)
            try {
                var attempt = 0
                while (true) {
                    while (true) {
                        val wait = coolDownUntil - System.currentTimeMillis()
                        if (wait <= 0) break
                        if (chain.call().isCanceled()) throw IOException("Canceled")
                        Thread.sleep(minOf(wait, 200))
                    }
                    val resp = chain.proceed(req)
                    if (resp.code != 429 || attempt >= 2) return resp
                    val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: (2000L shl attempt)
                    resp.close()
                    coolDownUntil = maxOf(coolDownUntil, System.currentTimeMillis() + retryAfter.coerceIn(1000, 15_000))
                    attempt++
                }
            } finally {
                apiSlots.release()
            }
        } finally {
            if (low) lowSlots.release()
        }
    }

    private fun acquire(slots: Semaphore, chain: Interceptor.Chain) {
        while (!slots.tryAcquire(200, TimeUnit.MILLISECONDS)) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
        }
    }

    /**
     * Runs the call and suspends until it answers. Unlike execute() inside withContext(IO), a
     * cancelled coroutine cancels the HTTP call too, freeing its connection and API slot.
     */
    internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, r, _ -> r.close() }
            }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    /** Sends [req] and returns the body, or throws a [KickException] for an error answer. */
    private suspend fun fetch(req: Request): String {
        val resp = http.newCall(req).await()
        return withContext(Dispatchers.IO) {
            resp.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw httpError(it, body)
                body
            }
        }
    }

    /** Cloudflare's "checking your browser" page rather than an answer from Kick itself. */
    private fun isChallenge(resp: Response, body: String) =
        resp.header("cf-mitigated") != null ||
            (resp.header("Server")?.contains("cloudflare", true) == true &&
                (body.contains("Just a moment", true) || body.contains("challenge-platform") || body.contains("cf-chl")))

    private fun httpError(resp: Response, body: String): KickException {
        val where = "HTTP ${resp.code} for ${resp.request.url.encodedPath}"
        val kind = when {
            resp.code == 429 -> KickException.Kind.RATE_LIMITED
            resp.code == 401 -> KickException.Kind.SIGNED_OUT
            resp.code == 403 || isChallenge(resp, body) -> KickException.Kind.BLOCKED
            resp.code == 404 -> KickException.Kind.NOT_FOUND
            resp.code >= 500 -> KickException.Kind.SERVER
            else -> KickException.Kind.OTHER
        }
        return KickException(kind, resp.code, where)
    }

    private suspend fun get(url: String, low: Boolean = false): String =
        fetch(Request.Builder().url(url).apply { if (low) tag(LowPriority::class.java, LowPriority) }.build())

    private suspend fun post(url: String, json: JSONObject, method: String = "POST"): String {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        return fetch(Request.Builder().url(url).method(method, body).build())
    }

    private val SLUG = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Whether [slug] looks like a Kick channel name (letters, digits, - and _). */
    fun isValidSlug(slug: String?) = slug != null && SLUG.matches(slug)

    /**
     * [slug] as it goes into a URL path. Anything that isn't a plain channel name (it may have come
     * from a link another app sent) is refused rather than spliced into the address.
     */
    private fun path(slug: String): String {
        if (!isValidSlug(slug)) throw KickException(KickException.Kind.NOT_FOUND, 0, "Not a channel name")
        return slug.lowercase()
    }

    private class Cached<T>(val at: Long, val value: T)
    private val channelCache = ConcurrentHashMap<String, Cached<ChannelInfo>>()
    private val liveCache = ConcurrentHashMap<String, Cached<LiveStream?>>()

    /** Nothing asks for a channel lookup older than this, so older entries are only taking memory. */
    private const val CACHE_TTL_MS = 5 * 60_000L
    private const val CACHE_MAX = 300

    /** Drops stale entries (and the oldest, if it's still too big) once [cache] passes a size worth tidying. */
    private fun <T> prune(cache: ConcurrentHashMap<String, Cached<T>>) {
        if (cache.size < 100) return
        val now = System.currentTimeMillis()
        // (Not removeIf: that's Android 7+.)
        val it = cache.entries.iterator()
        while (it.hasNext()) if (now - it.next().value.at > CACHE_TTL_MS) it.remove()
        if (cache.size > CACHE_MAX) {
            cache.entries.sortedBy { it.value.at }.take(cache.size - CACHE_MAX).forEach { cache.remove(it.key) }
        }
    }

    /** Forget every cached lookup (Settings → Clear cache). */
    fun clearCaches() {
        channelCache.clear()
        liveCache.clear()
        lastLiveCache.clear()
    }

    /** Live streams sorted by viewers. [language] null means all languages. */
    suspend fun livestreams(
        cursor: String? = null,
        categoryId: Int? = null,
        language: String? = null,
        limit: Int = 24,
    ): Page<LiveStream> {
        val url = "https://web.kick.com/api/v1/livestreams".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "viewer_count_desc")
            .apply {
                cursor?.let { addQueryParameter("after", it) }
                categoryId?.let { addQueryParameter("category_id", it.toString()) }
                language?.let { addQueryParameter("language", it) }
            }.build().toString()
        return KickParse.livestreams(get(url))
    }

    suspend fun categories(page: Int = 1, limit: Int = 32): Page<Category> =
        KickParse.categories(get("https://kick.com/api/v1/subcategories?page=$page&limit=$limit"), page)

    /** Channel details, reusing a lookup from the last [maxAgeMs] (several screens ask for the same ones). */
    suspend fun channel(slug: String, maxAgeMs: Long = 20_000): ChannelInfo {
        val key = slug.lowercase()
        channelCache[key]?.let { if (System.currentTimeMillis() - it.at < maxAgeMs) return it.value }
        return fetchChannel(key).also { channelCache[key] = Cached(System.currentTimeMillis(), it); prune(channelCache) }
    }

    /**
     * Like [channel], but waits behind everything else for an API slot: for extras such as
     * follower counts that nobody is waiting on.
     */
    suspend fun channelInBackground(slug: String, maxAgeMs: Long = 120_000): ChannelInfo {
        val key = slug.lowercase()
        channelCache[key]?.let { if (System.currentTimeMillis() - it.at < maxAgeMs) return it.value }
        return fetchChannel(key, low = true).also { channelCache[key] = Cached(System.currentTimeMillis(), it); prune(channelCache) }
    }

    private suspend fun fetchChannel(slug: String, low: Boolean = false): ChannelInfo =
        KickParse.channel(get("https://kick.com/api/v2/channels/${path(slug)}", low))

    /**
     * The channel's current broadcast as a card, or null when it's offline. One small call per
     * channel (vs. [channel]), with the name and avatar the caller already has.
     */
    suspend fun liveStream(slug: String, username: String, avatar: String?, maxAgeMs: Long = 45_000): LiveStream? {
        val key = slug.lowercase()
        liveCache[key]?.let { if (System.currentTimeMillis() - it.at < maxAgeMs) return it.value }
        val live = KickParse.liveStream(get("https://kick.com/api/v2/channels/${path(key)}/livestream"), slug, username, avatar)
        liveCache[key] = Cached(System.currentTimeMillis(), live)
        prune(liveCache)
        return live
    }

    /** Most-viewed clips, site-wide or for one channel. [time] is day, week, month or all. */
    suspend fun clips(slug: String? = null, cursor: String? = null, time: String = "day"): Page<Clip> {
        val base = if (slug == null) "https://kick.com/api/v2/clips" else "https://kick.com/api/v2/channels/${path(slug)}/clips"
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("sort", "view")
            .addQueryParameter("time", time)
            .apply { cursor?.let { addQueryParameter("cursor", it) } }
            .build().toString()
        return KickParse.clips(get(url), cursor)
    }

    suspend fun videos(slug: String, low: Boolean = false): List<Video> =
        KickParse.videos(get("https://kick.com/api/v2/channels/${path(slug)}/videos", low))

    private val lastLiveCache = ConcurrentHashMap<String, Cached<Long?>>()

    /**
     * When [slug] was last live (UTC ms), from its past broadcasts; looked up behind everything
     * else and kept for half an hour.
     */
    suspend fun lastLive(slug: String): Long? {
        val key = slug.lowercase()
        lastLiveCache[key]?.let { if (System.currentTimeMillis() - it.at < 30 * 60_000L) return it.value }
        val t = KickParse.lastLive(videos(key, low = true))
        lastLiveCache[key] = Cached(System.currentTimeMillis(), t)
        return t
    }

    suspend fun search(query: String): SearchResults {
        val url = "https://kick.com/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("searched_word", query).build().toString()
        return KickParse.search(get(url))
    }

    /** A Kick emote; [set] is "channel", "global" or "emoji". */
    data class Emote(val id: Int, val name: String, val subsOnly: Boolean, val set: String) {
        val url get() = "https://files.kick.com/emotes/$id/fullsize"
    }

    /** The emotes chat in [slug] can use: the channel's own, then Kick's global ones and emoji. */
    suspend fun emotes(slug: String): List<Emote> {
        val sets = JSONArray(get("https://kick.com/emotes/${path(slug)}"))
        return (0 until sets.length()).flatMap { i ->
            val s = sets.getJSONObject(i)
            val set = when {
                s.has("slug") -> "channel"
                s.optString("name").equals("Emojis", true) -> "emoji"
                else -> "global"
            }
            val emotes = s.optJSONArray("emotes") ?: JSONArray()
            (0 until emotes.length()).mapNotNull { j ->
                val e = emotes.getJSONObject(j)
                val id = e.optInt("id")
                if (id > 0) Emote(id, e.optString("name"), e.optBoolean("subscribers_only"), set) else null
            }
        }
    }

    /** Image URLs of the emotes chat in [slug] can use. */
    suspend fun emoteUrls(slug: String): List<String> = emotes(slug).map { it.url }

    /** Sends [text] to a chatroom as the signed-in account. Returns null on success, else why it failed. */
    suspend fun sendChat(chatroomId: Int, text: String, replyTo: ChatMessage? = null): String? = withContext(Dispatchers.IO) {
        val json = JSONObject().put("content", text).put("type", if (replyTo != null) "reply" else "message")
        if (replyTo != null) json.put("metadata", JSONObject()
            .put("original_message", JSONObject().put("id", replyTo.id).put("content", replyTo.content))
            .put("original_sender", JSONObject().put("id", replyTo.senderId).put("username", replyTo.username)))
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://kick.com/api/v2/messages/send/$chatroomId").post(body).build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) return@use null
                val msg = runCatching { JSONObject(resp.body?.string().orEmpty()).let { o ->
                    o.optJSONObject("status")?.optStringOrNull("message") ?: o.optStringOrNull("message") } }.getOrNull()
                when {
                    resp.code == 401 -> "Your Kick sign-in has expired. Sign in again on your TV (Settings → Account)."
                    msg != null -> msg
                    resp.code == 403 -> "Kick didn't accept the message. Chat may be followers- or subscribers-only."
                    resp.code == 429 -> "You're sending messages too fast."
                    else -> "Kick couldn't send that message. Try again in a moment."
                }
            }
        }.getOrElse { "Couldn't reach Kick. Check your TV's internet connection." }
    }

    /**
     * About five seconds of a past broadcast's chat, starting at [startMs] (UTC). Kick keeps
     * chat for its recordings, so it can be replayed alongside the video.
     */
    suspend fun chatHistory(channelId: Int, startMs: Long): List<ChatMessage> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val json = JSONObject(get("https://kick.com/api/v2/channels/$channelId/messages?start_time=${fmt.format(java.util.Date(startMs))}"))
        val arr = json.optJSONObject("data")?.optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { parseChatMessage(arr.getJSONObject(it)) }
    }

    /** A channel's chat as it stands: the pinned message (or null) and the latest messages, oldest first. */
    class RecentChat(val pinned: ChatMessage?, val messages: List<ChatMessage>)

    /** The pinned message and recent messages of a channel's chat, in one call. */
    suspend fun recentChat(channelId: Int): RecentChat =
        KickParse.recentChat(get("https://kick.com/api/v2/channels/$channelId/messages"))

    /** The message pinned at the top of a channel's chat, or null. */
    suspend fun pinnedMessage(channelId: Int): ChatMessage? = recentChat(channelId).pinned

    /** The poll running in [slug]'s chat, or null when there isn't one. */
    suspend fun poll(slug: String): ChatPoll? {
        val o = runCatching { JSONObject(get("https://kick.com/api/v2/channels/${path(slug)}/polls")) }.getOrNull() ?: return null
        return o.optJSONObject("data")?.optJSONObject("poll")?.let { parsePoll(it) }
    }

    /** The channel's latest prediction (running, locked or recently decided), or null. */
    suspend fun prediction(slug: String, streamStartMs: Long = 0): ChatPoll? =
        KickParse.prediction(get("https://kick.com/api/v2/channels/${path(slug)}/predictions/latest"), streamStartMs = streamStartMs)

    /** The signed-in user for [token], or null if the token isn't valid. */
    suspend fun me(token: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://kick.com/api/v1/user").header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val o = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return@withContext null
            val name = o.optStringOrNull("username") ?: return@withContext null
            name to (o.optStringOrNull("profilepic") ?: o.optStringOrNull("profile_pic"))
        }
    }

    /** Channels the signed-in account follows. */
    suspend fun followed(): List<Favorite> {
        val out = LinkedHashMap<String, Favorite>()
        var cursor: String? = "0"
        var pages = 0
        while (cursor != null && pages < 15) {
            val text = get("https://kick.com/api/v2/channels/followed-page?cursor=$cursor")
            val root: Any = runCatching { JSONObject(text) }.getOrElse { JSONArray(text) }
            val arr = when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("channels") ?: root.optJSONArray("data") ?: JSONArray()
                else -> JSONArray()
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ch = o.optJSONObject("channel")
                val user = o.optJSONObject("user") ?: ch?.optJSONObject("user")
                val slug = o.optStringOrNull("channel_slug") ?: o.optStringOrNull("slug") ?: ch?.optStringOrNull("slug")
                    ?: continue
                val name = o.optStringOrNull("user_username") ?: o.optStringOrNull("username")
                    ?: user?.optStringOrNull("username") ?: slug
                val avatar = o.optStringOrNull("profile_picture") ?: o.optStringOrNull("profile_pic")
                    ?: user?.optStringOrNull("profile_pic")
                val live = if (o.has("is_live")) o.optBoolean("is_live") else null
                out[slug.lowercase()] = Favorite(slug, name, avatar, live)
            }
            val next = (root as? JSONObject)?.let { it.optStringOrNull("nextCursor") ?: it.optStringOrNull("next_cursor") }
            cursor = if (arr.length() == 0 || next == cursor) null else next
            pages++
        }
        return out.values.toList()
    }

    /** Votes for answer [index] (0-based, in Kick's order) of the poll running in [slug]'s chat. Returns null, or why it failed. */
    suspend fun votePoll(slug: String, index: Int): String? =
        vote("https://kick.com/api/v2/channels/${path(slug)}/polls/vote", JSONObject().put("id", index))

    /** Puts [amount] channel points on [outcomeId] in [slug]'s prediction. Returns null, or why it failed. */
    suspend fun votePrediction(slug: String, predictionId: String, outcomeId: String, amount: Long): String? =
        vote("https://kick.com/api/v2/channels/${path(slug)}/predictions/vote",
            JSONObject().put("prediction_id", predictionId).put("outcome_id", outcomeId).put("amount", amount))

    private suspend fun vote(url: String, body: JSONObject): String? = try {
        post(url, body)
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: KickException) {
        when (e.kind) {
            KickException.Kind.SIGNED_OUT -> "Your Kick sign-in has expired. Sign in again in Settings → Account."
            KickException.Kind.RATE_LIMITED -> "Too many tries. Wait a moment."
            else -> if (e.code in 400..499) "Kick didn't take the vote. Voting may have closed, or you've already voted."
                else "Kick couldn't take the vote right now."
        }
    } catch (e: Exception) {
        "Couldn't reach Kick. Check your internet connection."
    }

    /** Follows or unfollows [slug] on the signed-in account. Throws when Kick doesn't accept it. */
    suspend fun setFollow(slug: String, follow: Boolean) {
        post("https://kick.com/api/v2/channels/${path(slug)}/follow", JSONObject(), if (follow) "POST" else "DELETE")
    }

    /** Asks Kick which Pusher app key/cluster serves this channel's chat. */
    suspend fun pusherConfig(channelId: Int): Pair<String, String> = try {
        val body = JSONObject()
            .put("client", JSONObject().put("id", UUID.randomUUID().toString()).put("type", "web"))
            .put("capabilities", JSONObject().put("accepted_providers", JSONArray().put(JSONObject().put("provider", "pusher"))))
        val conns = JSONObject(post("https://web.kick.com/api/v1/realtime/channels/$channelId/chat/connection", body))
            .getJSONObject("data").getJSONArray("connections")
        var result = DEFAULT_PUSHER_KEY to DEFAULT_PUSHER_CLUSTER
        for (i in 0 until conns.length()) {
            val c = conns.getJSONObject(i)
            if (c.optString("provider") == "pusher") {
                val cred = c.getJSONObject("credentials")
                result = cred.getString("app_key") to cred.optString("cluster", DEFAULT_PUSHER_CLUSTER)
                break
            }
        }
        result
    } catch (e: Exception) {
        DEFAULT_PUSHER_KEY to DEFAULT_PUSHER_CLUSTER
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
