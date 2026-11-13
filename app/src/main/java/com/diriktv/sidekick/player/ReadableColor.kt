package com.diriktv.sidekick.player

/**
 * Chat names are drawn in the colour each user picked, which can be nearly invisible on the dark
 * chat panel (navy, dark purple, black). Like Twitch's readable colours, a colour without enough
 * contrast is lightened, keeping its hue, until it reads.
 */
object ReadableColor {
    /** The darkest backdrop names are drawn on (the chat panel; over the video it's darker still). */
    const val CHAT_BACKGROUND = 0xFF0B0E0F.toInt()
    /** WCAG's contrast for ordinary text. */
    private const val MIN_CONTRAST = 4.5

    fun on(color: Int, background: Int): Int {
        if (contrast(color, background) >= MIN_CONTRAST) return color
        val hsl = FloatArray(3)
        rgbToHsl(color, hsl)
        var c = color
        var l = hsl[2]
        while (l < 0.95f && contrast(c, background) < MIN_CONTRAST) {
            l = (l + 0.04f).coerceAtMost(0.95f)
            c = hslToRgb(hsl[0], hsl[1], l)
        }
        return c
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((c shr 16 and 0xFF)) + 0.7152 * ch((c shr 8 and 0xFF)) + 0.0722 * ch((c and 0xFF))
    }

    // Plain arithmetic rather than android.graphics.Color, so this can be unit tested.
    private fun rgbToHsl(c: Int, out: FloatArray) {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        val d = max - min
        val s = if (d == 0f) 0f else d / (1 - kotlin.math.abs(2 * l - 1))
        val h = when {
            d == 0f -> 0f
            max == r -> 60 * (((g - b) / d) % 6)
            max == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        out[0] = h; out[1] = s; out[2] = l
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
