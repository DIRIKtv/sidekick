package com.diriktv.sidekick.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun formattedNotesBecomePlainBullets() {
        val md = """
            ### New
            - **Automatic updates setting:** check every time the app opens
            - **About screen** with [privacy](https://example.com) details

            ### Fixed
            - **Stuck side menu:** focus no longer bounces back to Home

            ---

            ### Install or update
            - this footer bullet must not show
            ```
            https://github.com/DIRIKtv/sidekick/releases/latest/download/Sidekick.apk
            ```
        """.trimIndent()
        assertEquals(
            "• Automatic updates setting: check every time the app opens\n" +
                "• About screen with privacy details\n" +
                "• Stuck side menu: focus no longer bounces back to Home",
            ReleaseNotes.forTv(md),
        )
    }

    @Test
    fun plainLineNotesStillWork() {
        assertEquals("• Fixes a crash\n• Faster chat", ReleaseNotes.forTv("Fixes a crash\nFaster chat\n"))
    }

    @Test
    fun longNotesAreCutWithAnEllipsis() {
        val text = ReleaseNotes.forTv((1..40).joinToString("\n") { "- Change number $it" })
        assertTrue(text.length <= 360 && text.endsWith("…"))
    }

    @Test
    fun emptyNotesGetADefault() {
        assertEquals("A newer version of Sidekick is ready.", ReleaseNotes.forTv("   \n"))
    }
}

class WhatsNewTest {
    @Test
    fun boldLeadInsBecomeTitles() {
        val md = """
            ### <img src="x.png"> New
            - **Live now, one press away:** press ▼ while watching to see who's live
            - **Picture size:** Fit, Stretch or Zoom
            ### <img src="y.png"> Fixed
            - The player clock ignored your TV's 24-hour setting
            ---
            - install step that isn't a change
        """.trimIndent()
        val c = com.diriktv.sidekick.data.ReleaseNotes.whatsNew(md)
        org.junit.Assert.assertEquals(3, c.size)
        org.junit.Assert.assertEquals("Live now, one press away", c[0].title)
        org.junit.Assert.assertEquals("press ▼ while watching to see who's live", c[0].detail)
        org.junit.Assert.assertEquals("The player clock ignored your TV's 24-hour setting", c[2].title)
        org.junit.Assert.assertNull(c[2].detail)
    }
}
