package com.diriktv.sidekick.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatParseTest {
    @Test
    fun raidTargetFromHostedChannel() {
        val t = parseRaid(JSONObject("""{"channel":{"id":1,"slug":"lospollostv"},"slug":"xqc",
            "hosted":{"id":2,"username":"xQc","slug":"xqc","viewers_count":12345,"is_live":true}}"""))!!
        assertEquals("xqc", t.slug)
        assertEquals("xQc", t.username)
        assertEquals(12345, t.viewers)
    }

    @Test
    fun raidTargetFromSlugOnly() {
        val t = parseRaid(JSONObject("""{"slug":"buddha"}"""))!!
        assertEquals("buddha", t.slug)
        assertEquals("buddha", t.username)
    }

    @Test
    fun raidWithoutTargetIsIgnored() {
        assertNull(parseRaid(JSONObject("""{"channel":{"id":1}}""")))
    }

    @Test
    fun historyMessageKeepsItsTime() {
        val m = parseChatMessage(JSONObject("""{"id":"a","content":"hi","created_at":"2026-10-29T03:00:01Z",
            "sender":{"username":"MarkV26","identity":{"color":"#FF0000","badges":[{"type":"subscriber","count":3}]}}}"""))!!
        assertEquals("MarkV26", m.username)
        assertEquals("#FF0000", m.color)
        assertEquals(1, m.badges.size)
        assertEquals(1793242801000L, m.receivedAt)
    }
}
