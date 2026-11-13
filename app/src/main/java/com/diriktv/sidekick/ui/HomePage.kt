package com.diriktv.sidekick.ui

import android.graphics.Outline
import android.os.Build
import android.os.Parcelable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
        /** A row that failed to load tries again at most this often as it scrolls into view. */
        const val ROW_RETRY_MS = 15_000L
        /** While Home stays on screen, its live cards are brought up to date this often… */
        const val SOFT_REFRESH_MS = 3 * 60_000L
        /** …once nobody has moved around it for this long (so nothing changes under the viewer's feet). */
        const val IDLE_MS = 20_000L
        /** The saved Home is shown at start-up if it's no older than this. */
        const val SNAPSHOT_MAX_AGE_MS = 12 * 60 * 60_000L
    }

    private val prefs = Prefs(act)
    private val list = MemoryRecyclerView(act)
    private val status = TextView(act)
    private val progress = SkeletonView(act)
    private val rows = mutableListOf<Row>()
    private var lastLoad = 0L
    private var loadedVersion = -1
    private var loading = false

    /** Called once, when Home has its first content (or gave up): the launch curtain lifts then. */
    var onFirstLoad: (() -> Unit)? = null
        set(v) {
            // Already showing the saved Home: that counts.
            if (v != null && showingSnapshot) v() else field = v
        }
    private var showingSnapshot = false
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
    /** The round channel cards (Recently watched) are taller than a stream card: avatar plus two lines. */
    private val circleRowHeight: Int by lazy {
        val probe = FrameLayout(act).inflate(R.layout.item_channel_card)
        val lp = probe.layoutParams as ViewGroup.MarginLayoutParams
        probe.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth * 3 / 4, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // Room for the focus zoom too.
        probe.measuredHeight + lp.topMargin + lp.bottomMargin + act.dp(20)
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
        /** Round channel cards rather than stream cards. */
        val circles: Boolean = false,
        val loader: suspend (String?) -> Page<GridItem>,
    ) {
        var cursor: String? = null
        var done = false
        var busy = false
        /** When the last load failed (0 = it didn't). A failed row tries again when it's next seen. */
        var failedAt = 0L
        var scrollState: Parcelable? = null
        /** The card that last had focus, so coming back to the row lands on it. */
        var focusPos = 0
        val adapter = GridAdapter(
            cardWidthPx = cardWidth,
            onClick = { Nav.onCardClick(act, it) },
            onLongClick = { Nav.onCardLongClick(act, it) },
            onNearEnd = { loadMore() },
        )

        /** Nothing to show: it failed before loading any cards. Such a row takes no room. */
        val empty get() = adapter.itemCount == 0 && (failedAt > 0 || done)

        fun loadMore(force: Boolean = false) {
            if (busy || done) return
            // Don't hammer Kick while it's failing; scrolling back to the row after a bit retries.
            if (!force && failedAt > 0 && System.currentTimeMillis() - failedAt < ROW_RETRY_MS) return
            busy = true
            act.lifecycleScope.launch {
                val wasEmpty = empty
                try {
                    val page = loader(cursor)
                    val seen = adapter.items.map { it.key }.toSet()
                    adapter.append(page.items.filter { it.key !in seen && !prefs.isHiddenCategory(it.categoryName) })
                    cursor = page.next
                    done = page.next == null
                    failedAt = 0
                    if (pendingFocus && page.items.isNotEmpty()) list.post { if (focusContent()) pendingFocus = false }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedAt = System.currentTimeMillis()
                } finally {
                    busy = false
                }
                // Show or hide the row as it gains its first cards or turns out to have none.
                if (wasEmpty != empty) rows.indexOf(this@Row).takeIf { it >= 0 }?.let { rowAdapter.notifyItemChanged(it + heroOffset) }
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
            // A row with nothing in it (it failed, or Kick had nothing) takes no room, title and all.
            val wantHeight = if (row.empty) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            if (holder.itemView.layoutParams.height != wantHeight) {
                holder.itemView.layoutParams = holder.itemView.layoutParams.apply { height = wantHeight }
            }
            holder.itemView.visibility = if (row.empty) View.INVISIBLE else View.VISIBLE
            holder.itemView.findViewById<TextView>(R.id.rowTitle).text = row.title
            val rv = holder.itemView.findViewById<RecyclerView>(R.id.rowList)
            val height = if (row.circles) circleRowHeight else rowListHeight
            if (rv.layoutParams.height != height) rv.layoutParams = rv.layoutParams.apply { this.height = height }
            rv.adapter = row.adapter
            rv.layoutManager?.onRestoreInstanceState(row.scrollState)
            (rv as MemoryRecyclerView).apply {
                lastFocused = row.focusPos
                onFocusedPosition = { row.focusPos = it }
            }
            if (row.adapter.itemCount == 0) row.loadMore()
            holder.itemView.tag = row
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            val row = holder.itemView.tag as? Row ?: return
            row.scrollState = holder.itemView.findViewById<RecyclerView>(R.id.rowList).layoutManager?.onSaveInstanceState()
        }
    }

    /** When focus last moved on Home: the in-place refresh waits until it's been still a while. */
    private var lastMove = 0L
    private val softTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val visible = view.isShown && act.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (visible && !loading && rows.isNotEmpty() && now - lastLoad > SOFT_REFRESH_MS && now - lastMove > IDLE_MS) softRefresh()
            view.postDelayed(this, 30_000)
        }
    }

    init {
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> lastMove = System.currentTimeMillis() }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.removeCallbacks(softTick); v.postDelayed(softTick, 30_000) }
            override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(softTick) }
        })
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
        showSnapshot()
    }

    // region instant start and in-place refresh

    private val snapshotFile get() = java.io.File(act.cacheDir, "home.json")

    /**
     * The Home last seen (its hero and Top live row), shown straight away at start-up while the
     * fresh one loads, instead of the skeleton. Its cards are replaced as soon as Kick answers.
     */
    private fun showSnapshot() {
        val snap = runCatching {
            val f = snapshotFile
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > SNAPSHOT_MAX_AGE_MS) return
            HomeSnapshot.parse(f.readText())
        }.getOrNull() ?: return
        if (snap.top.isEmpty() && snap.hero.isEmpty()) return
        heroFromFollows = snap.heroFromFollows
        heroItems = snap.hero.filterNot { prefs.isHiddenCategory(it.category) }
        val lang = prefs.language.ifEmpty { null }
        loadedHistory = historyStamp()
        rows += historyRows()
        val top = snap.top.filterNot { prefs.isHiddenCategory(it.category) }
        if (top.isNotEmpty()) rows += Row(topTitle(lang)) { Page(top.map { GridItem.Stream(it) }, null) }
        rows.forEach { it.loadMore() }
        rowAdapter.notifyItemRangeInserted(0, rows.size + heroOffset)
        progress.setVisible(false)
        showingSnapshot = true
        list.post { if (focusContent()) pendingFocus = false }
        onFirstLoad?.invoke()
        onFirstLoad = null
    }

    private fun saveSnapshot(hero: List<LiveStream>, fromFollows: Boolean, top: List<LiveStream>) {
        val json = HomeSnapshot(hero, fromFollows, top.take(24)).encode()
        val file = snapshotFile
        Thread({ runCatching { file.writeText(json) } }, "home-snapshot").start()
    }

    private fun topTitle(lang: String?) = if (lang == null) "Top live" else "Top live • ${Languages.label(lang)}"

    /**
     * Home has been on screen a while: viewer counts, titles and thumbnails on its live cards
     * are brought up to date where they are. Nothing is added, removed or reordered, so focus
     * and scroll positions stay put.
     */
    private fun softRefresh() {
        lastLoad = System.currentTimeMillis()
        act.lifecycleScope.launch {
            val live = runCatching { Follows.liveNow(act) }.getOrNull()
            if (live != null) {
                liveSlugs = live.map { it.slug.lowercase() }.toSet()
                val fresh = live.associateBy { it.slug.lowercase() }
                val updated = heroItems.map { fresh[it.slug.lowercase()] ?: it }
                if (updated != heroItems) {
                    heroItems = updated
                    if (heroView?.findViewById<View>(R.id.hero)?.isFocused != true) bindHero(animate = false)
                }
            }
            for (row in rows.toList()) {
                if (row.local || row.adapter.itemCount == 0 || row.adapter.items.none { it is GridItem.Stream }) continue
                val page = runCatching { row.loader(null) }.getOrNull() ?: continue
                row.adapter.refreshItems(page.items)
            }
        }
    }

    // endregion

    // region hero

    private fun setupHero(v: View) {
        val hero = v.findViewById<View>(R.id.hero)
        // The rounded clip softens each drawing's corner pixels on its own, so anything stacked
        // (the grey backdrop, the picture, the dark fade) shows through the layer above at those
        // pixels as a faint light rim. So they're drawn as one layer, and the hero itself paints
        // nothing: it only supplies the rounded shape for the clip and the focus glow.
        v.findViewById<View>(R.id.heroArt).setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val radius = act.dp(16).toFloat()
        hero.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
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
            heroItems.getOrNull(heroIndex)?.let { previewHero(hero, it.slug, has) }
            // Back on the thumbnail: the drift picks up again.
            if (!has) v.findViewById<View>(R.id.thumbFrame).kenBurns(heroIndex)
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

    /** The live preview in the hero; the slow zoom eases back out once the video shows. */
    private fun previewHero(hero: View, slug: String, focused: Boolean) {
        FocusPreview.onFocus(hero, slug, focused, maxHeight = 720) {
            hero.findViewById<View>(R.id.thumbFrame).animate().cancel()
            hero.findViewById<View>(R.id.thumbFrame).animate().scaleX(1f).scaleY(1f).setDuration(350)
                .setInterpolator(DecelerateInterpolator()).start()
        }
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
        if (hero.isFocused) previewHero(hero, s.slug, true)
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
        if (recent.isNotEmpty()) add(Row("Recently watched", local = true, circles = true) { Page(recent, null) })
    }

    /** After watching something, update just the history rows instead of reloading Home. */
    private fun refreshHistoryRows() {
        loadedHistory = historyStamp()
        val keepFocus = list.hasFocus()
        val firstNetwork = rows.indexOfFirst { !it.local }.let { if (it < 0) rows.size else it }
        repeat(firstNetwork) { rows.removeAt(0) }
        rows.addAll(0, historyRows())
        rows.filter { it.local }.forEach { it.loadMore() }
        @Suppress("NotifyDataSetChanged") // the history rows at the top were replaced wholesale
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
                val categoriesJob = async { runCatching { KickApi.categories(1, 8).items } }
                val topJob = async { runCatching { KickApi.livestreams(language = lang, limit = 24) } }

                val liveFollows = liveFollowsJob.await()
                val topResult = topJob.await()
                val categoriesResult = categoriesJob.await()
                val top = topResult.getOrNull()
                // Nothing at all came back (offline, or Kick turning us away): say so, rather than
                // showing row titles with no cards under them.
                if (top == null && categoriesResult.isFailure && liveFollows.isEmpty()) {
                    showLoadError(topResult.exceptionOrNull())
                    return@launch
                }
                failedLoad = false
                liveSlugs = liveFollows.map { it.slug.lowercase() }.toSet()
                TvHome.publishAsync(act, liveFollows, top?.items.orEmpty())

                // Hero: live follows, or the biggest streams when none of them are on.
                heroFromFollows = liveFollows.isNotEmpty()
                heroItems = liveFollows.ifEmpty { top?.items?.filterNot { prefs.isHiddenCategory(it.category) }?.take(6).orEmpty() }
                heroIndex = heroIndex.coerceAtMost((heroItems.size - 1).coerceAtLeast(0))

                val newRows = mutableListOf<Row>()
                loadedHistory = historyStamp()
                newRows += historyRows()
                saveSnapshot(heroItems, heroFromFollows, top?.items.orEmpty())
                // The first page is already here; later asks (more cards, an in-place refresh) go to Kick.
                var firstTop = top
                newRows += Row(topTitle(lang)) { cursor ->
                    val ready = if (cursor == null) firstTop.also { firstTop = null } else null
                    (ready ?: KickApi.livestreams(cursor = cursor, language = lang)).asCards()
                }
                if (lang != null) {
                    newRows += Row("Top live • All languages") { KickApi.livestreams(cursor = it).asCards() }
                }
                newRows += Row("Top clips today") { cursor ->
                    val page = KickApi.clips(cursor = cursor, time = "day")
                    Page(page.items.map { GridItem.Clip(it) }, page.next)
                }
                for (cat in categoriesResult.getOrDefault(emptyList()).filterNot { prefs.isHiddenCategory(it.name) }) {
                    newRows += Row(cat.name) { KickApi.livestreams(cursor = it, categoryId = cat.id, language = lang).asCards() }
                }

                rows.clear()
                rows += newRows
                @Suppress("NotifyDataSetChanged") // every row is new
                rowAdapter.notifyDataSetChanged()
                // Only the rows near the top load now; the rest load as they scroll into view.
                rows.take(EAGER_ROWS).forEach { it.loadMore() }
                if (pendingFocus && heroItems.isNotEmpty()) list.post { if (focusContent()) pendingFocus = false }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoadError(e)
            } finally {
                progress.setVisible(false)
                loading = false
                onFirstLoad?.invoke()
                onFirstLoad = null
            }
        }
    }

    /** The last reload got nothing from Kick. */
    private var failedLoad = false

    private fun showLoadError(e: Throwable?) {
        failedLoad = true
        lastLoad = 0 // try again next time Home is shown
        if (rows.any { !it.local && it.adapter.itemCount > 0 }) {
            // Already showing something: keep it rather than swapping it for an error.
            android.widget.Toast.makeText(act, "Couldn't refresh Home. ${com.diriktv.sidekick.data.KickErrors.describe(e)}",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        heroItems = emptyList()
        rows.clear()
        @Suppress("NotifyDataSetChanged") // everything went
        rowAdapter.notifyDataSetChanged()
        status.gravity = android.view.Gravity.CENTER
        status.text = "Couldn't reach Kick\n\n${com.diriktv.sidekick.data.KickErrors.describe(e)}\n\n" +
            "Press ☰ to try again. Home also reloads by itself when the connection comes back."
    }

    /** The internet came back: if Home couldn't load, or rows failed, load them now. */
    fun onReconnected() {
        if (failedLoad) {
            reload()
            return
        }
        rows.filter { it.failedAt > 0 }.forEach { it.loadMore(force = true) }
    }

    override fun focusContent(): Boolean {
        // Back to the row (and card) focus last left, or the hero / first row the first time.
        if (list.childCount == 0) return false
        return list.requestFocus()
    }
}

private fun Page<LiveStream>.asCards(): Page<GridItem> = Page(items.map { GridItem.Stream(it) }, next)

/** What [HomePage] saves of itself, to show instantly at the next start. */
private class HomeSnapshot(val hero: List<LiveStream>, val heroFromFollows: Boolean, val top: List<LiveStream>) {
    fun encode(): String = org.json.JSONObject()
        .put("hero", streams(hero)).put("follows", heroFromFollows).put("top", streams(top)).toString()

    companion object {
        private fun streams(list: List<LiveStream>) = org.json.JSONArray().apply {
            list.forEach { s ->
                put(org.json.JSONObject().put("slug", s.slug).put("username", s.username).put("title", s.title)
                    .put("viewers", s.viewers).put("thumbnail", s.thumbnail).put("avatar", s.avatar)
                    .put("category", s.category).put("language", s.language).put("startTime", s.startTime))
            }
        }

        private fun org.json.JSONObject.str(k: String) = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

        private fun parseStreams(arr: org.json.JSONArray?) = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val slug = o.str("slug") ?: return@mapNotNull null
            LiveStream(slug, o.optString("username", slug), o.optString("title"), o.optInt("viewers"),
                o.str("thumbnail"), o.str("avatar"), o.str("category"), o.str("language"), o.str("startTime"))
        }

        fun parse(text: String): HomeSnapshot {
            val o = org.json.JSONObject(text)
            return HomeSnapshot(parseStreams(o.optJSONArray("hero")), o.optBoolean("follows"), parseStreams(o.optJSONArray("top")))
        }
    }
}

object Languages {
    val all = listOf(
        "" to "All languages", "en" to "English", "es" to "Spanish", "pt" to "Portuguese", "de" to "German",
        "fr" to "French", "it" to "Italian", "ar" to "Arabic", "tr" to "Turkish", "ru" to "Russian",
        "pl" to "Polish", "nl" to "Dutch", "ja" to "Japanese", "ko" to "Korean",
    )

    fun label(code: String) = all.firstOrNull { it.first == code }?.second ?: code
}
