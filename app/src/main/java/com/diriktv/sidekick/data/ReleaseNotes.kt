package com.diriktv.sidekick.data

/** Turns a GitHub release's markdown notes into the short plain text the TV's update prompt shows. */
object ReleaseNotes {
    private const val MAX_CHARS = 360

    /**
     * Just the "- " items above the install instructions (which follow a "---" line), as "• "
     * bullets without markdown. Notes with no bullets show line by line, minus headings.
     */
    fun forTv(markdown: String): String {
        val lines = markdown.lines().map { it.trim() }.takeWhile { it != "---" }.filter { it.isNotEmpty() }
        val bullets = lines.filter { it.startsWith("- ") || it.startsWith("* ") }.map { it.drop(2) }
        val text = bullets.ifEmpty { lines.filterNot { it.startsWith("#") } }.joinToString("\n") { "• " + plain(it) }
        return if (text.length > MAX_CHARS) text.take(MAX_CHARS - 3) + "…" else text.ifEmpty { "A newer version of Sidekick is ready." }
    }

    /** One change in What's new: its short name (the bold lead-in, if any) and the rest. */
    data class Change(val title: String, val detail: String?)

    /**
     * Every "- " item above the install instructions, for the What's new sheet: "**Name:** what
     * it does" becomes a title and a detail; a plain item is just a title.
     */
    fun whatsNew(markdown: String): List<Change> =
        markdown.lines().map { it.trim() }.takeWhile { it != "---" }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.drop(2) }
            .map { item ->
                val m = Regex("""^\*\*(.+?)\*\*\s*(.*)$""").find(item)
                if (m != null) Change(plain(m.groupValues[1]).trim().trimEnd(':'), plain(m.groupValues[2]).trim().ifEmpty { null })
                else Change(plain(item), null)
            }

    /** Strips **bold**, `code` and [links](url) down to their text. */
    private fun plain(s: String) = s
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace("**", "")
        .replace("`", "")
}
