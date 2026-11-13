package com.diriktv.sidekick.data

import com.diriktv.sidekick.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kick's answers, saved from the real endpoints (trimmed, with signed playback links removed),
 * parsed the way the app does. When Kick changes a response shape, these fail first.
 */
class KickParseTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("kick/$name")!!.bufferedReader().readText()

    @Test
    fun livestreams() {
        val page = KickParse.livestreams(fixture("livestreams.json"))
        assertTrue(page.items.isNotEmpty())
        page.items.forEach {
            assertTrue(it.slug.isNotEmpty())
            assertTrue(it.username.isNotEmpty())
            assertTrue(it.viewers > 0)
            assertNotNull(it.thumbnail)
            assertNotNull(Fmt.parseUtc(it.startTime))
        }
        assertNotNull(page.next)
    }

    @Test
    fun channel() {
        val c = KickParse.channel(fixture("channel.json"))
        assertEquals("xqc", c.slug)
        assertEquals("xQc", c.username)
        assertTrue(c.id > 0 && c.userId > 0 && c.chatroomId > 0)
        assertTrue(c.followers > 0)
        assertNotNull(c.avatar)
        assertTrue(c.subscriberBadges.isNotEmpty())
        // Socials come as handles or paths and are made into links.
        assertTrue(c.socials.any { it.first == "X" && it.second == "https://x.com/xqc" })
        assertTrue(c.socials.none { it.second.endsWith("/") })
    }

    @Test
    fun liveStream() {
        val s = KickParse.liveStream(fixture("livestream.json"), "someone", "Someone", null)!!
        assertTrue(s.viewers > 0)
        assertNotNull(s.thumbnail)
        assertNotNull(s.category)
        assertNull(KickParse.liveStream("""{"data":null}""", "x", "x", null))
    }

    @Test
    fun videos() {
        val v = KickParse.videos(fixture("videos.json"))
        assertEquals(3, v.size)
        v.forEach {
            assertTrue(it.uuid.isNotEmpty())
            assertTrue(it.source.startsWith("https://"))
            assertTrue(it.durationMs > 0)
            assertNotNull(Fmt.parseUtc(it.createdAt))
        }
        val last = KickParse.lastLive(v)!!
        assertEquals(v.maxOf { Fmt.parseUtc(it.createdAt)!! + it.durationMs }, last)
    }

    @Test
    fun clips() {
        val page = KickParse.clips(fixture("clips.json"), null)
        assertEquals(3, page.items.size)
        page.items.forEach {
            assertTrue(it.source.startsWith("https://"))
            assertEquals("xqc", it.channelSlug.lowercase())
            assertTrue(it.durationMs > 0)
        }
        assertNotNull("more clips can be loaded", page.next)
    }

    @Test
    fun search() {
        val r = KickParse.search(fixture("search.json"))
        assertTrue(r.channels.any { it.slug == "xqc" })
    }

    @Test
    fun categories() {
        val page = KickParse.categories(fixture("subcategories.json"), 1)
        assertTrue(page.items.isNotEmpty())
        page.items.forEach { assertTrue(it.id > 0 && it.name.isNotEmpty()) }
    }

    @Test
    fun recentChat() {
        val r = KickParse.recentChat(fixture("messages.json"))
        assertEquals(4, r.messages.size)
        // Oldest first, whatever order Kick sends.
        assertEquals(r.messages.sortedBy { it.receivedAt }, r.messages)
        r.messages.forEach { assertTrue(it.username.isNotEmpty()) }
    }

    @Test
    fun predictionsGoAwayWhenStale() {
        val text = fixture("prediction.json")
        // Saved resolved at 23:51:38 UTC on 12 Nov 2026.
        val decided = Fmt.parseUtc("2026-11-12T23:51:38Z")!!
        val p = KickParse.prediction(text, now = decided + 60_000)!!
        assertEquals("RESOLVED", p.state)
        assertEquals(2, p.options.size)
        assertEquals(1, p.options.count { it.won })
        assertNull(KickParse.prediction(text, now = decided + KickParse.DECIDED_SHOW_MS + 1))
        // From a broadcast before the current one.
        assertNull(KickParse.prediction(text, now = decided + 60_000, streamStartMs = decided))
    }

    @Test
    fun forgottenLockedPrediction() {
        val locked = """{"data":{"prediction":{"id":"p","title":"t","duration":300,"state":"LOCKED",
            "created_at":"2026-11-12T10:00:00Z","updated_at":"2026-11-12T10:05:00Z","locked_at":"2026-11-12T10:05:00Z",
            "outcomes":[{"id":"a","title":"A","total_vote_amount":5},{"id":"b","title":"B","total_vote_amount":3}]}}}"""
        val at = Fmt.parseUtc("2026-11-12T10:05:00Z")!!
        assertNotNull(KickParse.prediction(locked, now = at + 60 * 60_000))
        assertNull(KickParse.prediction(locked, now = at + KickParse.LOCKED_SHOW_MS + 1))
        assertFalse(KickParse.prediction(locked, now = at)!!.options.any { it.won })
    }
}
