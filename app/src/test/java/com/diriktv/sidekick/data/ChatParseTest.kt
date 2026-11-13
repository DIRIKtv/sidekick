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

class ChatEventParseTest {
    @Test
    fun timeoutAndBan() {
        val (user, line) = parseBan(JSONObject("""{"id":"x","user":{"id":1,"username":"Spammer","slug":"spammer"},
            "banned_by":{"username":"mod"},"permanent":false,"duration":10}"""))!!
        assertEquals("Spammer", user)
        assertEquals("Spammer was timed out for 10 minutes", line)
        assertEquals("Troll was banned", parseBan(JSONObject("""{"user":{"username":"Troll"},"permanent":true}"""))!!.second)
        assertEquals("A was timed out for 1 day", parseBan(JSONObject("""{"user":{"username":"A"},"duration":1440}"""))!!.second)
    }

    @Test
    fun chatModesFromTheChannelApi() {
        // The flat shape /api/v2/channels/<slug> returns for its chatroom.
        val m = parseChatModes(JSONObject("""{"id":1,"slow_mode":true,"followers_mode":true,"subscribers_mode":false,
            "emotes_mode":false,"message_interval":7,"following_min_duration":1440}"""))
        assertEquals("Slow mode 7s  •  Followers only (1440 min)", m.describe())
    }

    @Test
    fun chatModesFromAnUpdateEvent() {
        val m = parseChatModes(JSONObject("""{"id":1,"slow_mode":{"enabled":false,"message_interval":5},
            "subscribers_mode":{"enabled":true},"followers_mode":{"enabled":false,"min_duration":0},"emotes_mode":{"enabled":false}}"""))
        assertEquals("Subscribers only", m.describe())
        assertNull(parseChatModes(JSONObject("""{"slow_mode":{"enabled":false}}""")).describe())
    }

    @Test
    fun replyKeepsWhatItAnswers() {
        val m = parseChatMessage(JSONObject("""{"id":"r","content":"same","type":"reply",
            "sender":{"username":"b","identity":{"badges":[]}},
            "metadata":{"original_sender":{"id":1,"username":"a"},"original_message":{"id":"o","content":"hello"}}}"""))!!
        assertEquals("a", m.replyTo)
        assertEquals("hello", m.replyText)
        val fromString = parseChatMessage(JSONObject().put("id", "s").put("content", "x")
            .put("sender", JSONObject().put("username", "c"))
            .put("metadata", """{"original_sender":{"username":"z"},"original_message":{"content":"q"}}"""))!!
        assertEquals("z", fromString.replyTo)
    }

    @Test
    fun subsGiftsAndHosts() {
        assertEquals("ann subscribed for 3 months",
            parseChatEvent("App\\Events\\SubscriptionEvent", JSONObject("""{"username":"ann","months":3}"""))?.text)
        assertEquals("bob gifted 5 subs", parseChatEvent("App\\Events\\GiftedSubscriptionsEvent",
            JSONObject("""{"gifter_username":"bob","gifted_usernames":["a","b","c","d","e"]}"""))?.text)
        assertEquals("cat is hosting with 40 viewers",
            parseChatEvent("App\\Events\\StreamHostEvent", JSONObject("""{"host_username":"cat","number_viewers":40}"""))?.text)
        assertNull(parseChatEvent("App\\Events\\Other", JSONObject("{}")))
    }
}

class MutedWordsTest {
    private val p = com.diriktv.sidekick.player.ChatFilter.mutedPattern(listOf("spoiler", "game over", "c++"))!!

    @Test
    fun wholeWordsAndPhrasesAnyCase() {
        org.junit.Assert.assertTrue(p.containsMatchIn("big SPOILER ahead"))
        org.junit.Assert.assertTrue(p.containsMatchIn("it's game over, gg"))
        org.junit.Assert.assertTrue(p.containsMatchIn("learning c++ today"))
        // Not inside other words.
        org.junit.Assert.assertFalse(p.containsMatchIn("spoilers"))
        org.junit.Assert.assertFalse(p.containsMatchIn("the gameover screen"))
    }

    @Test
    fun emoteCodesCountAsTheirNames() {
        val text = com.diriktv.sidekick.player.ChatFilter.visibleText("lol [emote:123:spoiler]")
        org.junit.Assert.assertTrue(p.containsMatchIn(text))
        org.junit.Assert.assertNull(com.diriktv.sidekick.player.ChatFilter.mutedPattern(listOf(" ", "")))
    }
}
