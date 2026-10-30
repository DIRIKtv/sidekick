package com.diriktv.sidekick.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.ChatMessage
import com.diriktv.sidekick.ui.inflate

class ChatAdapter(private val context: Context, var textSizeSp: Int) : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val messages = ArrayList<ChatMessage>()
    private val images = LruCache<String, Drawable>(600)
    private val pending = HashSet<String>()
    private var refreshPosted = false
    private var recycler: RecyclerView? = null

    private val emoteRegex = Regex("""\[emote:(\d+):([^\]]*)]""")

    /** Kick's own artwork for the role badges, in the order Kick shows them. */
    private val badgeIcons = linkedMapOf(
        "broadcaster" to R.drawable.badge_broadcaster,
        "staff" to R.drawable.badge_staff,
        "moderator" to R.drawable.badge_moderator,
        "verified" to R.drawable.badge_verified,
        "vip" to R.drawable.badge_vip,
        "og" to R.drawable.badge_og,
        "founder" to R.drawable.badge_founder,
        "sidekick" to R.drawable.badge_sidekick,
        "bot" to R.drawable.badge_bot,
        "subscriber" to R.drawable.badge_subscriber,
        "sub_gifter" to R.drawable.badge_sub_gifter,
    )

    private val wordRegex = Regex("""\S+""")

    /** Space above and below each message, in dp. */
    var spacingDp = 3
    /** Emote height as a multiple of the line height. */
    var emoteScale = 1.25f

    /** Prefix each message with the time it arrived. */
    var showTimestamps = false

    private val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    private val amPm = Regex("""\s?[AaPp]\.?[Mm]\.?$""")
    private val boldFont = com.diriktv.sidekick.ui.AppFonts.get(context, com.diriktv.sidekick.ui.AppFonts.Weight.BOLD)

    /** 7TV emotes (name -> image URL); words matching one are drawn as the emote. */
    var thirdPartyEmotes: Map<String, String> = emptyMap()
        set(value) { field = value; refreshVisible() }

    /** The channel's own subscriber badges (months -> image), set once channel info loads. */
    var subscriberBadges: List<Pair<Int, String>> = emptyList()
        set(value) { field = value; refreshVisible() }

    companion object {
        const val MAX_MESSAGES = 150
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    fun add(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
        if (messages.size > MAX_MESSAGES) {
            val drop = messages.size - MAX_MESSAGES
            repeat(drop) { messages.removeAt(0) }
            notifyItemRangeRemoved(0, drop)
        }
    }

    fun remove(id: String) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx >= 0) {
            messages.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount() = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent.inflate(R.layout.item_chat) as TextView)

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
        val pad = (spacingDp * context.resources.displayMetrics.density).toInt()
        if (holder.text.paddingTop != pad) holder.text.setPadding(holder.text.paddingLeft, pad, holder.text.paddingRight, pad)
        holder.text.text = render(messages[position], holder.text.lineHeight)
    }

    /** A message as chat shows it (for the pinned message card). */
    fun renderMessage(m: ChatMessage, lineHeight: Int): CharSequence = render(m, lineHeight)

    private fun render(m: ChatMessage, lineHeight: Int): CharSequence {
        val sb = SpannableStringBuilder()
        if (m.isSystem) {
            sb.append(m.content)
            sb.setSpan(ForegroundColorSpan(0xFF9AA0A6.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        if (showTimestamps) {
            sb.append(timeFormat.format(java.util.Date(m.receivedAt)).replace(amPm, ""))
            sb.setSpan(ForegroundColorSpan(0xFF80868B.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.82f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ")
        }
        val badgeSize = (lineHeight * 0.95f).toInt().coerceAtLeast(18)
        for (b in m.badges.sortedBy { badgeIcons.keys.indexOf(it.type).let { i -> if (i < 0) 99 else i } }) {
            val drawable = if (b.type == "subscriber") {
                subscriberBadgeUrl(b.count)?.let { remoteImage(it) } ?: context.getDrawable(R.drawable.badge_subscriber)
            } else {
                badgeIcons[b.type]?.let { context.getDrawable(it) }
            } ?: continue
            appendImage(sb, drawable, badgeSize, "◆")
            sb.append(' ')
        }
        for (url in m.badgeImages) {
            remoteImage(url)?.let { appendImage(sb, it, badgeSize, "◆"); sb.append(' ') }
        }
        val nameStart = sb.length
        sb.append(m.username)
        sb.setSpan(ForegroundColorSpan(parseColor(m.color)), nameStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(com.diriktv.sidekick.ui.BoldSpan(boldFont), nameStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(": ")

        var last = 0
        val size = (lineHeight * emoteScale).toInt().coerceAtLeast(18)
        for (match in emoteRegex.findAll(m.content)) {
            appendText(sb, m.content.substring(last, match.range.first), size)
            val name = match.groupValues[2]
            val d = remoteImage("https://files.kick.com/emotes/${match.groupValues[1]}/fullsize")
            if (d != null) appendImage(sb, d, size, name) else sb.append(name)
            last = match.range.last + 1
        }
        appendText(sb, m.content.substring(last), size)
        return sb
    }

    /** Plain message text, with any 7TV emote names swapped for their images. */
    private fun appendText(sb: SpannableStringBuilder, text: String, size: Int) {
        if (thirdPartyEmotes.isEmpty()) {
            sb.append(text)
            return
        }
        var last = 0
        for (word in wordRegex.findAll(text)) {
            sb.append(text, last, word.range.first)
            val d = thirdPartyEmotes[word.value]?.let { remoteImage(it) }
            if (d != null) appendImage(sb, d, size, word.value) else sb.append(word.value)
            last = word.range.last + 1
        }
        sb.append(text, last, text.length)
    }

    /** Adds [drawable] scaled to [height] and centered on the text line; [alt] is the hidden text. */
    private fun appendImage(sb: SpannableStringBuilder, drawable: Drawable, height: Int, alt: String) {
        val copy = drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable
        val ratio = if (copy.intrinsicHeight > 0) copy.intrinsicWidth.toFloat() / copy.intrinsicHeight else 1f
        copy.setBounds(0, 0, (height * ratio).toInt(), height)
        val start = sb.length
        sb.append(alt)
        sb.setSpan(CenteredImageSpan(copy), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Emotes and badges are decoded at about the largest size chat draws them, not their full
     * size: decoding is the slow part on TV hardware.
     */
    private val decodeHeight = (40 * context.resources.displayMetrics.scaledDensity).toInt()

    /** A cached network image (emote or badge), or null while it loads; rows redraw when it arrives. */
    private fun remoteImage(url: String): Drawable? {
        images.get(url)?.let { return it }
        load(url, refresh = true)
        return null
    }

    /** Loads images ahead of time (the channel's emotes, its badges) so messages show them straight away. */
    fun prefetch(urls: Collection<String>) {
        urls.forEach { if (images.get(it) == null) load(it, refresh = false) }
    }

    private fun load(url: String, refresh: Boolean) {
        if (!pending.add(url)) return
        val req = ImageRequest.Builder(context)
            .data(url)
            .size(coil3.size.Size(coil3.size.Dimension.Undefined, coil3.size.Dimension(decodeHeight)))
            .target(
                onSuccess = { image ->
                    images.put(url, image.asDrawable(context.resources))
                    pending.remove(url)
                    if (refresh) scheduleRefresh()
                },
                // Let a later message try again rather than leaving the name as text for good.
                onError = { pending.remove(url) },
            )
            .build()
        SingletonImageLoader.get(context).enqueue(req)
    }

    private fun subscriberBadgeUrl(months: Int): String? =
        subscriberBadges.lastOrNull { it.first <= months.coerceAtLeast(1) }?.second
            ?: subscriberBadges.firstOrNull()?.second

    private fun refreshVisible() {
        refreshPosted = false
        scheduleRefresh()
    }

    /** Batch redraws as emotes arrive instead of rebinding per image. */
    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        val rv = recycler
        if (rv == null) {
            refreshPosted = false
            return
        }
        rv.postDelayed({
            refreshPosted = false
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: 0
            val last = lm?.findLastVisibleItemPosition() ?: (messages.size - 1)
            if (first >= 0 && last >= first) notifyItemRangeChanged(first, last - first + 1)
        }, 120)
    }

    private fun parseColor(c: String?): Int = try {
        if (c.isNullOrBlank()) 0xFF53FC18.toInt() else Color.parseColor(c)
    } catch (e: Exception) {
        0xFF53FC18.toInt()
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)
}

/** ImageSpan that sits centered on the text line instead of on the baseline. */
private class CenteredImageSpan(drawable: Drawable) : android.text.style.ImageSpan(drawable) {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        val bounds = drawable.bounds
        if (fm != null) {
            val pfm = paint.fontMetricsInt
            val textHeight = pfm.descent - pfm.ascent
            val extra = (bounds.height() - textHeight).coerceAtLeast(0) / 2
            fm.ascent = pfm.ascent - extra
            fm.descent = pfm.descent + extra
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
        return bounds.right
    }

    override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val d = drawable
        val pfm = paint.fontMetricsInt
        val centerY = y + (pfm.descent + pfm.ascent) / 2
        canvas.save()
        canvas.translate(x, (centerY - d.bounds.height() / 2).toFloat())
        d.draw(canvas)
        canvas.restore()
    }
}
