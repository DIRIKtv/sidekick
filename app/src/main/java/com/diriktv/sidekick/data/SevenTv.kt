package com.diriktv.sidekick.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 7TV emotes (global plus the channel's set), as emote name -> image URL. */
object SevenTv {
    /** Sets are looked up again after this, so emotes a channel adds show up without a restart. */
    private const val TTL_MS = 30 * 60_000L

    private class Cached(val at: Long, val emotes: Map<String, String>) {
        val fresh get() = System.currentTimeMillis() - at < TTL_MS
    }

    @Volatile private var global: Cached? = null
    private val channels = java.util.concurrent.ConcurrentHashMap<Int, Cached>()

    /** Settings → Clear cache. */
    fun clear() {
        global = null
        channels.clear()
    }

    /** 7TV has no account for this channel: a real answer (cached), unlike a failed request. */
    private class NoSet : java.io.IOException()

    /**
     * Emotes for the Kick user [kickUserId]; the channel's own set wins over globals with the same
     * name. Only answers are cached: a failed request leaves the last good set (or none) and is
     * tried again next time, so one hiccup doesn't switch a channel's 7TV emotes off.
     */
    suspend fun emotes(kickUserId: Int): Map<String, String> {
        val g = global?.takeIf { it.fresh }?.emotes
            ?: runCatching { parseSet(fetch("https://7tv.io/v3/emote-sets/global")) }.getOrNull()
                ?.also { global = Cached(System.currentTimeMillis(), it) }
            ?: global?.emotes.orEmpty()
        if (kickUserId <= 0) return g
        val cached = channels[kickUserId]
        val own = cached?.takeIf { it.fresh }?.emotes ?: run {
            val result = runCatching {
                JSONObject(fetch("https://7tv.io/v3/users/kick/$kickUserId")).optJSONObject("emote_set")
                    ?.let { parseSet(it.toString()) }.orEmpty()
            }
            val fetched = result.getOrNull() ?: if (result.exceptionOrNull() is NoSet) emptyMap() else null
            fetched?.also { channels[kickUserId] = Cached(System.currentTimeMillis(), it) } ?: cached?.emotes.orEmpty()
        }
        return g + own
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        KickApi.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.code == 404) throw NoSet()
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun parseSet(json: String): Map<String, String> {
        val arr = JSONObject(json).optJSONArray("emotes") ?: JSONArray()
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optStringOrNull("name") ?: continue
            val host = e.optJSONObject("data")?.optJSONObject("host") ?: continue
            val base = host.optStringOrNull("url") ?: continue
            val files = host.optJSONArray("files") ?: JSONArray()
            val names = (0 until files.length()).mapNotNull { files.optJSONObject(it)?.optStringOrNull("name") }
            val file = listOf("2x.webp", "1x.webp", "2x.png").firstOrNull { it in names } ?: names.firstOrNull() ?: continue
            out[name] = "https:$base/$file"
        }
        return out
    }
}
