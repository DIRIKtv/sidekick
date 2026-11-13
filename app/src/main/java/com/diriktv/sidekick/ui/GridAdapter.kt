package com.diriktv.sidekick.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Category
import com.diriktv.sidekick.data.LiveStream
import com.diriktv.sidekick.data.Video

sealed class GridItem {
    data class Header(val text: String) : GridItem()
    data class Stream(val stream: LiveStream) : GridItem()
    data class Channel(
        val slug: String,
        val username: String,
        val avatar: String?,
        val isLive: Boolean,
        val subtitle: String,
    ) : GridItem()
    data class Cat(val category: Category) : GridItem()
    /** [channelName] set shows whose broadcast it is (rows mixing channels, like Continue watching). */
    data class Vod(val video: Video, val channelSlug: String, val progress: Float, val channelName: String? = null) : GridItem()
    data class Clip(val clip: com.diriktv.sidekick.data.Clip) : GridItem()
    /** A recent search on the Search page. */
    data class Query(val text: String) : GridItem()

    /** The category a live stream, clip or category card belongs to (for hiding categories). */
    val categoryName: String?
        get() = when (this) {
            is Stream -> stream.category
            is Clip -> clip.category
            is Cat -> category.name
            else -> null
        }

    /** Identity for de-duplicating pages as they load. */
    val key: String
        get() = when (this) {
            is Header -> "h:$text"
            is Stream -> "s:${stream.slug.lowercase()}"
            is Channel -> "c:${slug.lowercase()}"
            is Cat -> "k:${category.id}"
            is Vod -> "v:${video.uuid}"
            is Clip -> "p:${clip.id}"
            is Query -> "q:${text.lowercase()}"
        }
}

/** One adapter for every card type, so rows and grids look and behave the same. */
class GridAdapter(
    private val cardWidthPx: Int? = null,
    private val onClick: (GridItem) -> Unit,
    private val onLongClick: ((GridItem) -> Unit)? = null,
    private val onNearEnd: (() -> Unit)? = null,
) : RecyclerView.Adapter<GridAdapter.VH>() {

    val items = mutableListOf<GridItem>()

    /** New results cascade in one after another (Search) instead of the whole list fading up. */
    var cascade = false
    private var cascadeAt = 0L
    private var recycler: RecyclerView? = null

    init {
        // A refresh keeps each card's view (and its focus) instead of rebuilding the list; otherwise
        // the focused card vanishes and Android hands focus to the nav rail, switching pages.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = stableId(items[position].key)

    private companion object {
        /** How much bigger card text is with Larger text on. */
        const val LARGER_TEXT = 1.25f
    }

    /** Read when the list is attached; changing it recreates the screen. */
    private var largerText = false

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
        largerText = com.diriktv.sidekick.data.Prefs(recyclerView.context).largerText
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (recycler === recyclerView) recycler = null
    }

    fun submit(list: List<GridItem>) {
        val wasEmpty = items.isEmpty()
        items.clear()
        items.addAll(list)
        if (cascade) cascadeAt = android.os.SystemClock.uptimeMillis()
        @Suppress("NotifyDataSetChanged") // a whole new list (update() and swap() handle partial changes)
        notifyDataSetChanged()
        if (wasEmpty && list.isNotEmpty() && !cascade) revealList()
    }

    /** Swap in new contents of the same shape without the reveal animation (keeps focus in place). */
    /**
     * Swaps in a different list (another tab) as removals and insertions, so views outside this
     * adapter (a header above it in a ConcatAdapter) aren't rebound and keep their focus.
     */
    fun swap(list: List<GridItem>) {
        val old = items.size
        items.clear()
        notifyItemRangeRemoved(0, old)
        items.addAll(list)
        notifyItemRangeInserted(0, list.size)
    }

    fun update(list: List<GridItem>) {
        if (list.size != items.size) return submit(list)
        items.clear()
        items.addAll(list)
        notifyItemRangeChanged(0, list.size)
    }

    /**
     * Fresher copies of cards already showing (viewer counts, titles, thumbnails), matched by
     * key: those cards redraw in place, nothing moves, and focus stays where it is.
     */
    fun refreshItems(fresh: List<GridItem>) {
        val byKey = fresh.associateBy { it.key }
        items.forEachIndexed { i, old ->
            val new = byKey[old.key] ?: return@forEachIndexed
            if (new != old) {
                items[i] = new
                notifyItemChanged(i)
            }
        }
    }

    fun append(list: List<GridItem>) {
        val wasEmpty = items.isEmpty()
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
        if (wasEmpty && list.isNotEmpty()) revealList()
    }

    /** First content for a list fades up into place instead of popping in. */
    private fun revealList() {
        val rv = recycler ?: return
        rv.alpha = 0f
        rv.translationY = rv.resources.displayMetrics.density * 12
        rv.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun spanLookup(spanCount: Int) = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) =
            if (items.getOrNull(position) is GridItem.Header) spanCount else 1
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is GridItem.Header -> 0
        is GridItem.Stream, is GridItem.Vod, is GridItem.Clip -> 1
        is GridItem.Channel -> 2
        is GridItem.Cat -> 3
        is GridItem.Query -> 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            0 -> R.layout.item_header
            1 -> R.layout.item_stream_card
            2 -> R.layout.item_channel_card
            4 -> R.layout.item_query_chip
            else -> R.layout.item_category_card
        }
        val v = parent.inflate(layout)
        if (viewType == 1) {
            v.layoutParams = v.layoutParams.apply {
                width = cardWidthPx ?: ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        // In a horizontal row (fixed card width), channel cards get a fixed width too instead of filling it.
        if (viewType == 2 && cardWidthPx != null) {
            v.layoutParams = v.layoutParams.apply { width = cardWidthPx * 3 / 4 }
        }
        if (viewType == 3) v.findViewById<AspectImageView>(R.id.banner).ratio = 4f / 3f
        // Clip images to the rounded card so corners don't poke out under the focus outline.
        v.findViewById<View?>(R.id.thumbFrame)?.clipToOutline = true
        (v.findViewById<View?>(R.id.banner)?.parent as? View)?.clipToOutline = true
        v.findViewById<AspectImageView?>(R.id.avatar)?.ratio = 1f
        val holder = VH(v)
        // Settings → Appearance → Larger text.
        if (largerText && viewType != 0) listOf(holder.title, holder.channel, holder.category, holder.name, holder.followers).forEach { t ->
            t?.let { it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it.textSize * LARGER_TEXT) }
        }
        if (viewType != 0 && viewType != 4) v.focusZoom(if (viewType == 2) 1.08f else 1.05f) { has ->
            // Live cards carry their channel in the tag (set when bound) for the focus preview.
            (v.getTag(R.id.thumbFrame) as? String)?.let { FocusPreview.onFocus(v, it, has) }
            FocusGlow.onFocus(v, has)
        }
        // Listeners are set once here; they look up whatever the card shows when pressed.
        if (viewType != 0) {
            v.setOnClickListener {
                val item = holder.item() ?: return@setOnClickListener
                // A clip remembers its row, so the player can go on to the next one.
                if (item is GridItem.Clip) Nav.clipRow = items.mapNotNull { (it as? GridItem.Clip)?.clip }
                onClick(item)
            }
            v.setOnLongClickListener { holder.item()?.let { onLongClick?.invoke(it) }; onLongClick != null }
            // The remote's ☰ button does the same as a long press.
            v.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_MENU && onLongClick != null) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) holder.item()?.let(onLongClick)
                    true
                } else false
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val v = holder.itemView
        v.setTag(R.id.thumbFrame, (item as? GridItem.Stream)?.stream?.slug)
        if (cascade && android.os.SystemClock.uptimeMillis() - cascadeAt < 400 && !v.context.motionOff()) {
            v.alpha = 0f
            v.translationY = v.resources.displayMetrics.density * 18
            v.animate().alpha(1f).translationY(0f).setStartDelay(minOf(position * 35L, 420L)).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        } else if (v.alpha < 1f && cascade) {
            v.animate().cancel()
            v.alpha = 1f
            v.translationY = 0f
        }
        if (onNearEnd != null && position >= items.size - 6) v.post { onNearEnd.invoke() }
        when (item) {
            is GridItem.Header -> (v as TextView).text = item.text
            is GridItem.Stream -> {
                val s = item.stream
                holder.thumb?.loadThumb(Img.thumb(s.thumbnail), live = true)
                holder.avatar?.apply { setVisible(true); loadAvatar(s.avatar, s.username) }
                holder.title?.text = Fmt.cleanTitle(s.title).ifEmpty { s.title }
                holder.channel?.text = s.username
                holder.category?.apply { text = s.category.orEmpty(); setVisible(s.category != null) }
                holder.viewers?.apply { text = Fmt.viewers(s.viewers); setVisible(true) }
                holder.liveBadge?.setVisible(true)
                holder.duration?.setVisible(false)
                holder.resumeBar?.setVisible(false)
            }
            is GridItem.Vod -> {
                val video = item.video
                holder.thumb?.loadThumb(Img.thumb(video.thumbnail), live = video.isLive)
                holder.avatar?.setVisible(false)
                holder.title?.text = video.title
                holder.channel?.text =
                    listOfNotNull(item.channelName, Fmt.ago(video.createdAt), video.views.takeIf { it > 0 }?.let { "${Fmt.count(it)} views" })
                        .filter { it.isNotEmpty() }.joinToString(" • ")
                holder.category?.apply { text = video.category.orEmpty(); setVisible(video.category != null) }
                holder.viewers?.setVisible(false)
                holder.liveBadge?.setVisible(false)
                holder.duration?.apply { text = if (video.isLive) "● Recording" else Fmt.time(video.durationMs); setVisible(true) }
                holder.resumeBar?.apply {
                    max = 1000
                    progress = (item.progress * 1000).toInt()
                    setVisible(item.progress > 0.01f)
                }
            }
            is GridItem.Clip -> {
                val c = item.clip
                holder.thumb?.loadThumb(c.thumbnail)
                holder.avatar?.apply { setVisible(true); loadAvatar(c.channelAvatar, c.channelName) }
                holder.title?.text = c.title.ifBlank { "Clip" }
                holder.channel?.text =
                    listOf(c.channelName, "${Fmt.count(c.views)} views", Fmt.ago(c.createdAt)).filter { it.isNotEmpty() }.joinToString(" • ")
                holder.category?.apply { text = c.category.orEmpty(); setVisible(c.category != null) }
                holder.viewers?.setVisible(false)
                holder.liveBadge?.setVisible(false)
                holder.duration?.apply { text = Fmt.time(c.durationMs); setVisible(true) }
                holder.resumeBar?.setVisible(false)
            }
            is GridItem.Channel -> {
                holder.avatar?.loadAvatar(item.avatar, item.username)
                holder.name?.text = item.username
                holder.followers?.text = item.subtitle
                holder.liveBadge?.setVisible(item.isLive)
            }
            is GridItem.Cat -> {
                holder.banner?.loadThumb(item.category.banner)
                holder.name?.text = item.category.name
                holder.viewers?.text = Fmt.viewers(item.category.viewers)
            }
            is GridItem.Query -> holder.name?.text = item.text
        }
    }

    override fun onViewRecycled(holder: VH) {
        FocusPreview.onFocus(holder.itemView, "", false)
    }

    /** A card's views, looked up once when it's made rather than on every bind. */
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView? = v.findViewById(R.id.thumb)
        val avatar: ImageView? = v.findViewById(R.id.avatar)
        val title: TextView? = v.findViewById(R.id.title)
        val channel: TextView? = v.findViewById(R.id.channel)
        val category: TextView? = v.findViewById(R.id.category)
        val viewers: TextView? = v.findViewById(R.id.viewers)
        val liveBadge: View? = v.findViewById(R.id.liveBadge)
        val duration: TextView? = v.findViewById(R.id.duration)
        val resumeBar: ProgressBar? = v.findViewById(R.id.resumeBar)
        val name: TextView? = v.findViewById(R.id.name)
        val followers: TextView? = v.findViewById(R.id.followers)
        val banner: ImageView? = v.findViewById(R.id.banner)

        /** What this card shows now (null while it's being removed). */
        fun item(): GridItem? = items.getOrNull(bindingAdapterPosition)?.takeIf { it !is GridItem.Header }
    }
}
