package com.diriktv.sidekick.ui

import android.os.Build
import android.os.Parcelable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.HistoryEntry
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.LiveStream
import com.diriktv.sidekick.data.Page
import com.diriktv.sidekick.data.Prefs
import com.diriktv.sidekick.data.Video
import com.diriktv.sidekick.tv.TvHome
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Pages hosted by the main screen next to the nav rail. */
interface MainPage {
    val view: View
    fun onShown() {}
    /** Move focus into the page. Returns false if there's nothing focusable yet. */
    fun focusContent(): Boolean
}

/** Home: a hero carousel of live followed channels, then rows of live streams. */
class HomePage(private val act: FragmentActivity) : MainPage {
    private companion object {
        /** Rows loaded straight away; the others wait until they're about to be seen. */
        const val EAGER_ROWS = 4
    }

    private val prefs = Prefs(act)
    private val list = RecyclerView(act)
    private val status = TextView(act)
    private val progress = SkeletonView(act)
    private val rows = mutableListOf<Row>()
    private var lastLoad = 0L
    private var loadedVersion = -1
    private var loading = false

    /** Called once, when Home has its first content (or gave up): the launch curtain lifts then. */
    var onFirstLoad: (() -> Unit)? = null
    /** Grab focus as soon as content arrives (startup, or a reload while focused here). */
    private var pendingFocus = true
    /** Settings → Browsing → Card size. */
    private val cardWidth = act.dp(listOf(168, 200, 240)[prefs.cardSize.coerceIn(0, 2)])
    /** Rows get a fixed height; wrap_content made them re-measure (and rebind) on every image load. */
    private val rowListHeight: Int by lazy {
        val probe = FrameLayout(act).inflate(R.layout.item_stream_card)
        val lp = probe.layoutParams as ViewGroup.MarginLayoutParams
        probe.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        probe.measuredHeight + lp.topMargin + lp.bottomMargin + act.dp(16)
    }

    // Hero state
    private var heroItems: List<LiveStream> = emptyList()
    private var heroFromFollows = false
    private var heroIndex = 0
    private var heroView: View? = null

    override val view: View = FrameLayout(act).apply {
        addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private inner class Row(
        val title: String,
        /** Built from this TV's watch history rather than loaded from Kick. */
        val local: Boolean = false,
        val loader: suspend (String?) -> Page<GridItem>,
    ) {
        var cursor: String? = null
        var done = false
        var busy = false
        var scrollState: Parcelable? = null
        val adapter = GridAdapter(
            cardWidthPx = cardWidth,
            onClick = { Nav.onCardClick(act, it) },
            onLongClick = { Nav.onCardLongClick(act, it) },
            onNearEnd = { loadMore() },
        )

        fun loadMore() {
            if (busy || done) return
            busy = true
            act.lifecycleScope.launch {
                try {
                    val page = loader(cursor)
                    val seen = adapter.items.map { it.key }.toSet()
                    adapter.append(page.items.filter { it.key !in seen && !prefs.isHiddenCategory(it.categoryName) })
                    cursor = page.next
                    done = page.next == null
                    if (pendingFocus && page.items.isNotEmpty()) list.post { if (focusContent()) pendingFocus = false }
                } catch (e: Exception) {
                    done = true
                } finally {
                    busy = false
                }
            }
        }
    }

    private val heroOffset get() = if (heroItems.isNotEmpty()) 1 else 0

    private val rowAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init {
            // Rows keep their views across reloads, so focus isn't lost (see GridAdapter).
            setHasStableIds(true)
        }

        override fun getItemId(position: Int) =
            if (position < heroOffset) Long.MIN_VALUE else stableId("row:" + rows[position - heroOffset].title)

        override fun getItemCount() = rows.size + heroOffset

        override fun getItemViewType(position: Int) = if (position < heroOffset) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val v = parent.inflate(R.layout.item_hero)
                setupHero(v)
                return object : RecyclerView.ViewHolder(v) {}
            }
            val v = parent.inflate(R.layout.item_row)
            v.findViewById<RecyclerView>(R.id.rowList).apply {
                layoutParams.height = rowListHeight
                layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
                itemAnimator = null
                setHasFixedSize(true)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position < heroOffset) {
                heroView = holder.itemView
                bindHero(animate = false)
                return
            }
            val row = rows[position - heroOffset]
            holder.itemView.findViewById<TextView>(R.id.rowTitle).text = row.title
            val rv = holder.itemView.findViewById<RecyclerView>(R.id.rowList)
            rv.adapter = row.adapter
            rv.layoutManager?.onRestoreInstanceState(row.scrollState)
            if (row.adapter.itemCount == 0) row.loadMore()
            holder.itemView.tag = row
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            val row = holder.itemView.tag as? Row ?: return
            row.scrollState = holder.itemView.findViewById<RecyclerView>(R.id.rowList).layoutManager?.onSaveInstanceState()
        }
    }

    init {
        list.layoutManager = TopAlignLayoutManager(act, act.dp(4))
        list.adapter = rowAdapter
        list.setPadding(0, act.dp(18), 0, act.dp(200))
        list.clipToPadding = false
        list.clipChildren = false // the focused hero's glow spills past its row
        list.setItemViewCacheSize(12)
        list.itemAnimator = null
        status.setTextColor(act.getColor(R.color.text_secondary))
        status.textSize = 17f
        status.font(AppFonts.Weight.REGULAR)
    }

    // region hero

    private fun setupHero(v: View) {
        val hero = v.findViewById<View>(R.id.hero)
        hero.clipToOutline = true
        hero.setOnClickListener { heroItems.getOrNull(heroIndex)?.let { Nav.watchLive(act, it.slug) } }
        hero.setOnLongClickListener { heroItems.getOrNull(heroIndex)?.let { Nav.channel(act, it.slug) }; true }
        hero.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener keyCode == KeyEvent.KEYCODE_MENU
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (heroIndex < heroItems.size - 1) { heroIndex++; bindHero(animate = true, direction = 1) }
                    true // stay on the hero at the end
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (heroIndex > 0) { heroIndex--; bindHero(animate = true, direction = -1); true }
                    else false // first item: let focus move to the nav rail
                }
                KeyEvent.KEYCODE_MENU -> { heroItems.getOrNull(heroIndex)?.let { Nav.channel(act, it.slug) }; true }
                else -> false
            }
        }
        // The step player fills the current bar and moves the carousel on, but only while the
        // viewer is looking elsewhere; focusing the hero holds it.
        val steps = v.findViewById<StepPlayerView>(R.id.heroSteps)
        steps.onAdvance = { i ->
            if (i in heroItems.indices) { heroIndex = i; bindHero(animate = true) }
        }
        // Focus lifts the hero a little with a soft green glow, and after a moment the stream
        // itself plays, muted, in place of the thumbnail.
        if (Build.VERSION.SDK_INT >= 28) {
            hero.outlineSpotShadowColor = act.getColor(R.color.kick_green)
            hero.outlineAmbientShadowColor = act.getColor(R.color.kick_green)
        }
        hero.setOnFocusChangeListener { _, has ->
            v.findViewById<TextView>(R.id.heroHint).animate().alpha(if (has) 1f else 0f).setDuration(160).start()
            steps.playing = !has
            v.findViewById<View>(R.id.heroWatch).isActivated = has
            val lift = if (has && !act.motionOff()) 1.02f else 1f
            hero.animate().scaleX(lift).scaleY(lift).translationZ(if (has) act.dp(10).toFloat() else 0f)
                .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            heroItems.getOrNull(heroIndex)?.let { FocusPreview.onFocus(hero, it.slug, has, maxHeight = 720) }
        }
        v.findViewById<TextView>(R.id.heroHint).alpha = 0f
        // The icons sit beside small text, so they're drawn smaller than their 24dp.
        fun TextView.icon(size: Int) {
            val d = compoundDrawablesRelative[0]?.mutate() ?: return
            d.setBounds(0, 0, act.dp(size), act.dp(size))
            setCompoundDrawablesRelative(d, null, null, null)
        }
        v.findViewById<TextView>(R.id.heroWatch).icon(15)
        v.findViewById<TextView>(R.id.heroViewers).icon(15)
        v.findViewById<TextView>(R.id.heroUptime).icon(14)
    }

    private fun bindHero(animate: Boolean, direction: Int = 1) {
        val v = heroView ?: return
        val s = heroItems.getOrNull(heroIndex) ?: return
        val content = listOf<View>(
            v.findViewById(R.id.heroName), v.findViewById(R.id.heroTitle),
            v.findViewById(R.id.heroMeta), v.findViewById(R.id.heroAvatar),
        )
        v.findViewById<ImageView>(R.id.heroImage).loadThumb(Img.thumb(s.thumbnail, 1080))
        // The drift runs on the frame, so the live preview inside it keeps moving too.
        v.findViewById<View>(R.id.thumbFrame).kenBurns(heroIndex)
        v.findViewById<ImageView>(R.id.heroAvatar).loadAvatar(s.avatar, s.username)
        v.findViewById<TextView>(R.id.heroName).text = s.username
        v.findViewById<TextView>(R.id.heroTitle).text = Fmt.cleanTitle(s.title)
        v.findViewById<TextView>(R.id.heroViewers).text = Fmt.viewers(s.viewers)
        v.findViewById<TextView>(R.id.heroCategory).apply {
            text = s.category
            setVisible(!s.category.isNullOrBlank())
        }
        v.findViewById<TextView>(R.id.heroUptime).apply {
            text = Fmt.uptimeShort(s.startTime)
            setVisible(text.isNotEmpty())
        }
        v.findViewById<TextView>(R.id.heroEyebrow).text = SpannableStringBuilder()
            .append("LIVE", BoldSpan(AppFonts.get(act, AppFonts.Weight.BLACK)), 0)
            .append(if (heroFromFollows) "  ·  Following" else "  ·  Featured")

        v.findViewById<StepPlayerView>(R.id.heroSteps).apply {
            bind(heroItems.size, heroIndex)
            setVisible(heroItems.size > 1)
        }
        val hero = v.findViewById<View>(R.id.hero)
        if (hero.isFocused) FocusPreview.onFocus(hero, s.slug, true, maxHeight = 720)
        if (animate) {
            content.forEach {
                it.alpha = 0f
                it.translationX = act.dp(24) * direction.toFloat()
                it.animate().alpha(1f).translationX(0f).setDuration(220).start()
            }
        }
    }

    // endregion

    override fun onShown() {
        if (System.currentTimeMillis() - lastLoad > 3 * 60_000 || loadedVersion != Follows.version) reload()
        else if (historyStamp() != loadedHistory) refreshHistoryRows()
    }

    // region watch history rows

    private var loadedHistory = 0L
    private var liveSlugs: Set<String> = emptySet()

    private fun historyStamp() = prefs.history.firstOrNull()?.watchedAt ?: 0L

    /** Past broadcasts started but not finished, most recent first. */
    private fun continueWatching(): List<GridItem> = prefs.history
        .filter { it.kind == HistoryEntry.VOD && it.source != null && it.videoId != null }
        .mapNotNull { e ->
            val pos = prefs.vodPosition(e.videoId!!)
            if (pos < 10_000 || (e.durationMs > 0 && pos > e.durationMs - 60_000)) return@mapNotNull null
            val video = Video(e.videoId, e.title, e.source!!, e.thumbnail, e.durationMs, 0, e.createdAt, e.category)
            GridItem.Vod(video, e.slug, if (e.durationMs > 0) pos.toFloat() / e.durationMs else 0f, channelName = e.username)
        }
        .take(20)

    /** Channels watched recently, live ones marked. */
    private fun recentlyWatched(): List<GridItem> {
        val seen = HashSet<String>()
        return prefs.history.filter { seen.add(it.slug.lowercase()) }.take(20).map { e ->
            val live = e.slug.lowercase() in liveSlugs
            GridItem.Channel(e.slug, e.username, e.avatar, live, if (live) "Live now" else "Watched ${Fmt.ago(e.watchedAt)}")
        }
    }

    private fun historyRows(): List<Row> = buildList {
        val cont = continueWatching()
        if (cont.isNotEmpty()) add(Row("Continue watching", local = true) { Page(cont, null) })
        val recent = recentlyWatched()
        if (recent.isNotEmpty()) add(Row("Recently watched", local = true) { Page(recent, null) })
    }

    /** After watching something, update just the history rows instead of reloading Home. */
    private fun refreshHistoryRows() {
        loadedHistory = historyStamp()
        val keepFocus = list.hasFocus()
        val firstNetwork = rows.indexOfFirst { !it.local }.let { if (it < 0) rows.size else it }
        repeat(firstNetwork) { rows.removeAt(0) }
        rows.addAll(0, historyRows())
        rows.filter { it.local }.forEach { it.loadMore() }
        rowAdapter.notifyDataSetChanged()
        if (keepFocus && !list.hasFocus()) list.post { focusContent() }
    }

    // endregion

    fun reload() {
        if (loading) return
        loading = true
        lastLoad = System.currentTimeMillis()
        loadedVersion = Follows.version
        if (list.hasFocus()) pendingFocus = true
        progress.setVisible(rows.isEmpty())
        status.text = ""
        act.lifecycleScope.launch {
            try {
                val lang = prefs.language.ifEmpty { null }

                val liveFollowsJob = async { runCatching { Follows.liveNow(act) }.getOrDefault(emptyList()) }
                val categoriesJob = async { runCatching { KickApi.categories(1, 8).items }.getOrDefault(emptyList()) }
                val topJob = async { runCatching { KickApi.livestreams(language = lang, limit = 24) }.getOrNull() }

                val liveFollows = liveFollowsJob.await()
                val top = topJob.await()
                liveSlugs = liveFollows.map { it.slug.lowercase() }.toSet()
                TvHome.publishAsync(act, liveFollows, top?.items.orEmpty())

                // Hero: live follows, or the biggest streams when none of them are on.
                heroFromFollows = liveFollows.isNotEmpty()
                heroItems = liveFollows.ifEmpty { top?.items?.filterNot { prefs.isHiddenCategory(it.category) }?.take(6).orEmpty() }
                heroIndex = heroIndex.coerceAtMost((heroItems.size - 1).coerceAtLeast(0))

                val newRows = mutableListOf<Row>()
                loadedHistory = historyStamp()
                newRows += historyRows()
                newRows += Row(if (lang == null) "Top live" else "Top live • ${Languages.label(lang)}") { cursor ->
                    (if (cursor == null && top != null) top else KickApi.livestreams(cursor = cursor, language = lang)).asCards()
                }
                if (lang != null) {
                    newRows += Row("Top live • All languages") { KickApi.livestreams(cursor = it).asCards() }
                }
                newRows += Row("Top clips today") { cursor ->
                    val page = KickApi.clips(cursor = cursor, time = "day")
                    Page(page.items.map { GridItem.Clip(it) }, page.next)
                }
                for (cat in categoriesJob.await().filterNot { prefs.isHiddenCategory(it.name) }) {
                    newRows += Row(cat.name) { KickApi.livestreams(cursor = it, categoryId = cat.id, language = lang).asCards() }
                }

                rows.clear()
                rows += newRows
                rowAdapter.notifyDataSetChanged()
                // Only the rows near the top load now; the rest load as they scroll into view.
                rows.take(EAGER_ROWS).forEach { it.loadMore() }
                if (pendingFocus && heroItems.isNotEmpty()) list.post { if (focusContent()) pendingFocus = false }
            } catch (e: Exception) {
                status.text = "Couldn't reach Kick. Check your connection.\n\nPress ☰ on the remote to try again."
            } finally {
                progress.setVisible(false)
                loading = false
                onFirstLoad?.invoke()
                onFirstLoad = null
            }
        }
    }

    override fun focusContent(): Boolean {
        val lm = list.layoutManager as LinearLayoutManager
        val first = lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        val holder = list.findViewHolderForAdapterPosition(first) ?: return false
        holder.itemView.findViewById<View?>(R.id.hero)?.let { return it.requestFocus() }
        val inner = holder.itemView.findViewById<RecyclerView>(R.id.rowList) ?: return false
        val innerLm = inner.layoutManager as LinearLayoutManager
        val target = innerLm.findViewByPosition(innerLm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0))
            ?: inner.getChildAt(0)
        return target?.requestFocus() ?: false
    }
}

private fun Page<LiveStream>.asCards(): Page<GridItem> = Page(items.map { GridItem.Stream(it) }, next)

object Languages {
    val all = listOf(
        "" to "All languages", "en" to "English", "es" to "Spanish", "pt" to "Portuguese", "de" to "German",
        "fr" to "French", "it" to "Italian", "ar" to "Arabic", "tr" to "Turkish", "ru" to "Russian",
        "pl" to "Polish", "nl" to "Dutch", "ja" to "Japanese", "ko" to "Korean",
    )

    fun label(code: String) = all.firstOrNull { it.first == code }?.second ?: code
}
