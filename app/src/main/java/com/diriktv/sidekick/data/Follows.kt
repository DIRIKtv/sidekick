package com.diriktv.sidekick.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Following" = channels the signed-in Kick account follows, plus favorites saved on this TV.
 * Account follows are cached briefly so screens don't refetch them on every visit.
 */
object Follows {
    private var cached: List<Favorite> = emptyList()
    private var cachedAt = 0L
    /** The account's follows, saved between runs so follow state is right before the list loads. */
    private val accountSlugs: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private lateinit var app: Context

    val signedIn get() = KickApi.authToken != null

    /** Signed in, but Kick no longer accepts the session: offer "Sign in again". */
    @Volatile var sessionExpired = false
        private set

    /** Called on the main thread when [sessionExpired] changes. */
    val sessionListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Bumped whenever the follow list may have changed, so screens know to reload. */
    @Volatile var version = 0
        private set

    fun init(context: Context) {
        app = context.applicationContext
        val prefs = Prefs(app)
        KickApi.authToken = prefs.sessionToken
        sessionExpired = KickApi.authToken != null && prefs.sessionExpired
        if (KickApi.authToken != null) accountSlugs += prefs.accountFollows
        KickApi.onSessionRejected = { markExpired() }
        Network.onReconnected += { if (signedIn && !sessionExpired) { invalidate() } }
    }

    private fun markExpired() {
        if (sessionExpired || !signedIn) return
        sessionExpired = true
        Prefs(app).sessionExpired = true
        mainHandler.post { sessionListeners.forEach { it() } }
    }

    fun signIn(context: Context, token: String, name: String, avatar: String?) {
        Prefs(context).apply { sessionToken = token; accountName = name; accountAvatar = avatar; sessionExpired = false }
        KickApi.authToken = token
        sessionExpired = false
        mainHandler.post { sessionListeners.forEach { it() } }
        invalidate()
    }

    fun signOut(context: Context) {
        Prefs(context).apply {
            sessionToken = null; accountName = null; accountAvatar = null
            sessionExpired = false; accountFollows = emptySet(); pendingFollows = emptyMap()
        }
        KickApi.authToken = null
        sessionExpired = false
        accountSlugs.clear()
        cached = emptyList()
        clearWebSession(context)
        mainHandler.post { sessionListeners.forEach { it() } }
        invalidate()
    }

    /** Everything the sign-in page kept: cookies, and kick.com's local storage, cache and form data. */
    private fun clearWebSession(context: Context) {
        runCatching {
            android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
            android.webkit.WebStorage.getInstance().deleteAllData()
            @Suppress("DEPRECATION")
            android.webkit.WebViewDatabase.getInstance(context).clearFormData()
            android.webkit.WebView(context.applicationContext).apply { clearCache(true); clearHistory(); destroy() }
        }
    }

    fun invalidate() {
        cachedAt = 0
        version++
    }

    suspend fun all(context: Context): List<Favorite> {
        val prefs = Prefs(context)
        val local = prefs.favorites
        if (!signedIn) return local
        if (System.currentTimeMillis() - cachedAt > 120_000) {
            retryPending(context)
            runCatching { KickApi.followed() }.onSuccess { list ->
                // Follows still waiting to reach Kick keep the state chosen on the TV.
                val pending = prefs.pendingFollows
                cached = list.filter { pending[it.slug.lowercase()] != false }
                cachedAt = System.currentTimeMillis()
                val slugs = list.map { it.slug.lowercase() }.toSet() - pending.filterValues { !it }.keys + pending.filterValues { it }.keys
                accountSlugs.retainAll(slugs)
                accountSlugs += slugs
                prefs.accountFollows = slugs
                if (sessionExpired) {
                    // It works again (a passing hiccup, or signed in elsewhere): clear the warning.
                    sessionExpired = false
                    prefs.sessionExpired = false
                    mainHandler.post { sessionListeners.forEach { it() } }
                }
            }
        }
        // Favorites saved on the TV come first, but keep the account's live hint for them.
        val hints = cached.associateBy({ it.slug.lowercase() }, { it.live })
        val seen = HashSet<String>()
        return (local + cached)
            .filter { seen.add(it.slug.lowercase()) }
            .map { f -> if (f.live == null) f.copy(live = hints[f.slug.lowercase()]) else f }
    }

    private val liveLock = Mutex()
    private var live: Map<String, LiveStream> = emptyMap()
    private var liveAt = 0L
    private var liveVersion = -1

    /**
     * Followed channels that are live now, most watched first. Screens share one result for up
     * to [maxAgeMs]; channels the account's follow list reports offline aren't looked up at all.
     */
    suspend fun liveNow(context: Context, maxAgeMs: Long = 60_000): List<LiveStream> = liveLock.withLock {
        if (liveVersion == version && System.currentTimeMillis() - liveAt < maxAgeMs) return@withLock sorted()
        val favs = all(context).filter { it.live != false }.take(100)
        val previous = live
        val results = coroutineScope {
            favs.map { f ->
                async { f.slug.lowercase() to runCatching { KickApi.liveStream(f.slug, f.username, f.avatar) } }
            }.awaitAll()
        }
        live = results.mapNotNull { (key, result) ->
            // A failed lookup keeps the last known state rather than flipping the channel offline.
            val stream = if (result.isSuccess) result.getOrNull() else previous[key]
            stream?.let { key to it }
        }.toMap()
        liveAt = System.currentTimeMillis()
        liveVersion = version
        publishLiveCount()
        sorted()
    }

    private fun sorted() = live.values.sortedByDescending { it.viewers }

    /** How many followed channels were live at the last check (-1 before the first one). */
    @Volatile var liveCount = -1
        private set

    /** Called on the main thread whenever [liveCount] may have changed. */
    val liveListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun publishLiveCount() {
        liveCount = live.size
        mainHandler.post { liveListeners.forEach { it() } }
    }

    fun isFollowing(context: Context, slug: String) =
        Prefs(context).isFavorite(slug) || (signedIn && slug.lowercase() in accountSlugs)

    /**
     * The result of a follow button press: the new state on this TV, and why the Kick account
     * didn't follow along (null when it did, or when not signed in).
     */
    data class ToggleResult(val following: Boolean, val accountProblem: String? = null)

    /** Toggles follow state locally and, when signed in, on the Kick account. */
    suspend fun toggle(context: Context, slug: String, username: String, avatar: String?): ToggleResult {
        val prefs = Prefs(context)
        val key = slug.lowercase()
        val now = !isFollowing(context, slug)
        if (now != prefs.isFavorite(slug)) prefs.toggleFavorite(slug, username, avatar)
        if (now) accountSlugs += key else accountSlugs -= key
        if (!signedIn) {
            version++ // the TV's own favorites changed
            return ToggleResult(now)
        }
        val error = runCatching { KickApi.setFollow(slug, now) }.exceptionOrNull()
        prefs.accountFollows = accountSlugs.toSet()
        if (error == null) {
            prefs.pendingFollows = prefs.pendingFollows - key
            invalidate()
            return ToggleResult(now)
        }
        version++
        // An unfollow shows straight away even though the account's list still has the channel.
        if (!now) cached = cached.filterNot { it.slug.equals(slug, true) }
        if (error is KickException && error.kind == KickException.Kind.SIGNED_OUT) {
            // Kept on the TV; an expired sign-in won't take a retry, so don't queue one.
            return ToggleResult(now, "Saved on this TV, but your Kick sign-in has expired. Sign in again to update your account.")
        }
        prefs.pendingFollows = prefs.pendingFollows + (key to now)
        val what = if (now) "follow" else "unfollow"
        return ToggleResult(now, "Saved on this TV. Kick didn't take the $what yet, so Sidekick will try again later.")
    }

    /** Sends follows Kick didn't accept earlier; ones it accepts (or that no longer apply) are dropped. */
    private suspend fun retryPending(context: Context) {
        val prefs = Prefs(context)
        val pending = prefs.pendingFollows
        if (pending.isEmpty() || sessionExpired) return
        val left = pending.filter { (slug, follow) ->
            runCatching { KickApi.setFollow(slug, follow) }.exceptionOrNull()?.let { e ->
                // Kick knows nothing of that channel any more: give up on it.
                !(e is KickException && e.kind == KickException.Kind.NOT_FOUND)
            } ?: false
        }
        prefs.pendingFollows = left
    }
}
