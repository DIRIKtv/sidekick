package com.diriktv.sidekick.player

import com.diriktv.sidekick.data.ChatMessage
import com.diriktv.sidekick.data.Prefs

/** Decides which chat messages to show, from the chat settings. */
class ChatFilter(private val prefs: Prefs) {
    companion object {
        /** Bots that don't always carry Kick's bot badge. */
        private val KNOWN_BOTS = setOf(
            "botrix", "botrixoficial", "kickbot", "kicklet", "fossabot", "nightbot",
            "streamelements", "moobot", "wizebot", "sery_bot",
        )

        private val EMOTE = Regex("""\[emote:\d+:([^\]]*)]""")

        /** A pattern matching any of [words] as a whole word or phrase, ignoring case; null for none. */
        fun mutedPattern(words: Collection<String>): Regex? {
            val parts = words.map { it.trim() }.filter { it.isNotEmpty() }.map { Regex.escape(it) }
            if (parts.isEmpty()) return null
            return Regex("""(?<![\p{L}\p{N}_])(?:${parts.joinToString("|")})(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
        }

        /** The words a message shows: Kick emote codes count as their names. */
        fun visibleText(content: String) = EMOTE.replace(content) { it.groupValues[1] }
    }

    // Built once per change of the lists, not for every message.
    private var mutedFor: Set<String>? = null
    private var muted: Regex? = null

    fun allows(m: ChatMessage): Boolean {
        if (m.isSystem) return true
        if (m.username.lowercase() in prefs.chatBlockedUsers) return false
        if (prefs.chatHideBots && (m.badges.any { it.type == "bot" } || m.username.lowercase() in KNOWN_BOTS)) return false
        if (prefs.chatHideCommands && m.content.trimStart().startsWith("!")) return false
        val words = prefs.chatMutedWords
        if (words != mutedFor) { mutedFor = words; muted = mutedPattern(words) }
        if (muted?.containsMatchIn(visibleText(m.content)) == true) return false
        return true
    }
}
