package com.diriktv.sidekick.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class PrefetchPlaylistsTest {
    // Trimmed from a real Kick (IVS) live media playlist.
    private val live = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:3474
        #EXT-X-PROGRAM-DATE-TIME:2026-11-12T15:25:47.234Z
        #EXTINF:2.000,live
        https://example.live-video.net/v1/segment/a.ts
        #EXT-X-PROGRAM-DATE-TIME:2026-11-12T15:25:49.234Z
        #EXTINF:1.968,live
        https://example.live-video.net/v1/segment/b.ts
        #EXT-X-PREFETCH:https://example.live-video.net/v1/segment/c.ts
        #EXT-X-PREFETCH:https://example.live-video.net/v1/segment/d.ts
    """.trimIndent()

    @Test
    fun prefetchHintsBecomeSegments() {
        val out = PrefetchPlaylists.rewrite(live).lines()
        assertFalse(out.any { it.startsWith("#EXT-X-PREFETCH") })
        val c = out.indexOf("https://example.live-video.net/v1/segment/c.ts")
        val d = out.indexOf("https://example.live-video.net/v1/segment/d.ts")
        // Each takes the length of the last finished segment.
        assertEquals("#EXTINF:1.968,live", out[c - 1])
        assertEquals("#EXTINF:1.968,live", out[d - 1])
        assertEquals(4, out.count { it.startsWith("#EXTINF") })
    }

    @Test
    fun playlistsWithoutHintsAreUntouched() {
        val vod = "#EXTM3U\n#EXTINF:4.0,\nseg1.ts\n#EXT-X-ENDLIST\n"
        assertSame(vod, PrefetchPlaylists.rewrite(vod))
    }
}
