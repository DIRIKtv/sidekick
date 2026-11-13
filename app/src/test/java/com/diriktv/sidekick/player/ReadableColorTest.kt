package com.diriktv.sidekick.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableColorTest {
    private val bg = ReadableColor.CHAT_BACKGROUND

    @Test
    fun brightColoursAreLeftAlone() {
        val green = 0xFF53FC18.toInt()
        assertEquals(green, ReadableColor.on(green, bg))
    }

    @Test
    fun darkColoursAreLiftedUntilReadable() {
        for (dark in listOf(0xFF000080.toInt(), 0xFF2E0854.toInt(), 0xFF000000.toInt(), 0xFF3B0000.toInt())) {
            val lifted = ReadableColor.on(dark, bg)
            assertTrue("${Integer.toHexString(dark)} -> ${Integer.toHexString(lifted)}", ReadableColor.contrast(lifted, bg) >= 4.5)
        }
    }

    @Test
    fun hueIsKept() {
        // Dark blue stays blue: blue remains the strongest channel.
        val c = ReadableColor.on(0xFF000080.toInt(), bg)
        assertTrue((c and 0xFF) > (c shr 16 and 0xFF) && (c and 0xFF) > (c shr 8 and 0xFF))
    }
}
