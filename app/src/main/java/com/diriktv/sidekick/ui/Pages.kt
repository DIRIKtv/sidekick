package com.diriktv.sidekick.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared plumbing for a page that is a single grid with a status line. */
abstract class GridPage(
    protected val act: FragmentActivity,
    layout: Int,
    spanCount: Int,
    /** Shape of the outlines shown while it loads. */
    cardRatio: Float = 9f / 16f,
    captions: Boolean = true,
) : MainPage {
    override val view: View = android.view.LayoutInflater.from(act).inflate(layout, null)
    protected val list: RecyclerView = view.findViewById(R.id.list)
    protected val status: TextView = view.findViewById(R.id.status)
    protected val progress: View = view.findViewById<View>(R.id.progress).replaceWithSkeleton(spanCount, cardRatio, captions)
    protected val adapter = GridAdapter(
        onClick = { onItemClick(it) },
        onLongClick = { Nav.onCardLongClick(act, it) },
        onNearEnd = { onNearEnd() },
    )

    init {
        list.layoutManager = CenterGridLayoutManager(act, spanCount).apply { spanSizeLookup = adapter.spanLookup(spanCount) }
        list.adapter = adapter
        list.itemAnimator = null
    }

    protected val errorRetry = ErrorRetry(status)

    open fun onNearEnd() {}

    open fun onItemClick(item: GridItem) = Nav.onCardClick(act, item)

    override fun focusContent(): Boolean {
        if (errorRetry.focusable?.requestFocus() == true) return true
        val lm = list.layoutManager as CenterGridLayoutManager
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        for (i in first until adapter.itemCount) {
            val v = lm.findViewByPosition(i) ?: continue
            if (v.isFocusable && v.requestFocus()) return true
        }
        return false
    }
}

class FollowingPage(act: FragmentActivity) : GridPage(act, R.layout.page_grid, 4) {
    private var job: Job? = null
    private val prefs = Prefs(act)

    /** When each offline channel was last live (UTC ms; null = never), by lowercase slug; looked up as needed. */
    private val lastLive = HashMap<String, Long?>()
    private val asked = HashSet<String>()
    private var live: List<com.diriktv.sidekick.data.LiveStream> = emptyList()
    private var offline: List<com.diriktv.sidekick.data.Favorite> = emptyList()
    private val applyLastLive = Runnable {
        // Sorting by "recently live" waits until every offline channel's time is in, then sorts once.
        if (prefs.followingSort == "recent" && offline.all { it.slug.lowercase() in lastLive }) adapter.update(build())
        else adapter.refreshItems(build())
    }

    private companion object {
        val SORTS = listOf("viewers" to "Most viewers", "recent" to "Recently live", "name" to "A–Z")
    }

    init {
        // "Last live" for cards on screen (or for everyone, when sorting by it): one or two at a
        // time behind everything else, so opening a stream never waits on them.
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val item = adapter.items.getOrNull(list.getChildAdapterPosition(view)) as? GridItem.Channel ?: return
                if (!item.isLive) fetchLastLive(item.slug)
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun fetchLastLive(slug: String) {
        val key = slug.lowercase()
        if (key in lastLive || !asked.add(key)) return
        act.lifecycleScope.launch {
            val r = runCatching { KickApi.lastLive(slug) }
            if (r.isFailure) { asked.remove(key); return@launch }
            lastLive[key] = r.getOrNull()
            list.removeCallbacks(applyLastLive)
            list.postDelayed(applyLastLive, 300)
        }
    }

    private fun build(): List<GridItem> {
        val items = mutableListOf<GridItem>()
        // Only this TV's favorites are showing: say why the account's are missing.
        if (Follows.sessionExpired) items += GridItem.Header("Kick sign-in expired • sign in again under Settings → Account to see all your follows")
        val sort = prefs.followingSort
        items += GridItem.Query("Sort: ${SORTS.firstOrNull { it.first == sort }?.second ?: "Most viewers"}")
        if (live.isNotEmpty()) {
            items += GridItem.Header("Live now")
            items += when (sort) {
                "name" -> live.sortedBy { it.username.lowercase() }
                "recent" -> live.sortedByDescending { Fmt.parseUtc(it.startTime) ?: 0L }
                else -> live
            }.map { GridItem.Stream(it) }
        }
        if (offline.isNotEmpty()) {
            items += GridItem.Header("Offline")
            items += when (sort) {
                "recent" -> offline.sortedByDescending { lastLive[it.slug.lowercase()] ?: 0L }
                else -> offline.sortedBy { it.username.lowercase() }
            }.map { fav ->
                val key = fav.slug.lowercase()
                val subtitle = when {
                    key !in lastLive -> "Offline"
                    lastLive[key] == null -> "No past broadcasts"
                    else -> "Live ${Fmt.ago(lastLive[key]!!)}"
                }
                GridItem.Channel(fav.slug, fav.username, fav.avatar, false, subtitle)
            }
        }
        return items
    }

    override fun onItemClick(item: GridItem) {
        if (item !is GridItem.Query) return super.onItemClick(item)
        OptionSheet(act).show(OptionSheet.Page("Sort Following") {
            SORTS.map { (key, label) ->
                OptionSheet.Item(label, checked = prefs.followingSort == key) { sheet ->
                    sheet.dismiss()
                    prefs.followingSort = key
                    if (key == "recent") offline.forEach { fetchLastLive(it.slug) }
                    adapter.update(build())
                }
            }
        })
    }

    override fun onShown() = refresh()

    fun refresh() {
        job?.cancel()
        status.text = ""
        progress.setVisible(adapter.itemCount == 0)
        job = act.lifecycleScope.launch {
            val favs = Follows.all(act)
            val expiredNote = "Your Kick sign-in has expired, so your account's follows can't load.\nSign in again under Settings → Account."
            if (favs.isEmpty()) {
                progress.setVisible(false)
                adapter.submit(emptyList())
                status.text = if (Follows.sessionExpired) expiredNote
                else "Nothing followed yet.\n\nSign in under Settings to bring your Kick follows,\nor press ♥ in the player or on a channel page."
                return@launch
            }
            live = Follows.liveNow(act)
            val liveSlugs = live.map { it.slug.lowercase() }.toSet()
            offline = favs.filter { it.slug.lowercase() !in liveSlugs }
            // Went live since last time: its last-live time will be new when it ends.
            liveSlugs.forEach { lastLive.remove(it); asked.remove(it) }
            if (prefs.followingSort == "recent") offline.forEach { fetchLastLive(it.slug) }
            val hadFocus = list.hasFocus()
            adapter.submit(build())
            progress.setVisible(false)
            if (hadFocus) list.post { focusContent() }
        }
    }
}

class BrowsePage(act: FragmentActivity) : GridPage(act, R.layout.page_grid, 7, cardRatio = 4f / 3f, captions = false) {
    private val prefs = Prefs(act)
    private var page = 1
    private var done = false
    private var busy = false
    private var hidden = prefs.hiddenCategories

    /** Load the first page in the background so the grid is instant when opened. */
    fun preload() {
        if (adapter.itemCount == 0) onNearEnd()
    }

    private fun prefetch(urls: List<String>) {
        val loader = coil3.SingletonImageLoader.get(act)
        urls.forEach { url ->
            loader.enqueue(coil3.request.ImageRequest.Builder(act).data(url).size(240, 320).build())
        }
    }

    override fun onShown() {
        // Hidden categories changed in Settings: start over without them.
        if (prefs.hiddenCategories != hidden && !busy) {
            hidden = prefs.hiddenCategories
            adapter.submit(emptyList())
            page = 1
            done = false
        }
        if (adapter.itemCount == 0) onNearEnd()
    }

    override fun onNearEnd() {
        if (busy || done) return
        busy = true
        progress.setVisible(adapter.itemCount == 0 && !errorRetry.showing)
        act.lifecycleScope.launch {
            try {
                val res = KickApi.categories(page, 36)
                if (page == 1) adapter.append(listOf(GridItem.Header("Browse categories")))
                adapter.append(res.items.filterNot { prefs.isHiddenCategory(it.name) }.map { GridItem.Cat(it) })
                prefetch(res.items.mapNotNull { it.banner })
                page++
                done = res.next == null
                status.text = ""
                if (errorRetry.hide()) list.post { focusContent() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not marked done: showing the page again (or the connection coming back) retries.
                if (adapter.itemCount == 0) errorRetry.show("Couldn't load categories", e) { onNearEnd() }
            } finally {
                busy = false
                progress.setVisible(false)
            }
        }
    }

    fun onReconnected() {
        if (adapter.itemCount == 0) onNearEnd()
    }
}

/**
 * Search with its own keyboard on the left (the system one would cover the results), and results
 * in sections: live streams, categories, then channels. Empty, it shows recent searches and
 * what's popular.
 */
class SearchPage(private val act: FragmentActivity) : MainPage {
    companion object {
        private const val SPAN = 4
        private const val MIN_QUERY = 3
        private const val POPULAR_MAX_AGE_MS = 120_000L
        private val KEY_ROWS = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890")
    }

    @android.annotation.SuppressLint("InflateParams") // added to the main screen later, with its own layout params
    override val view: View = android.view.LayoutInflater.from(act).inflate(R.layout.page_search, null)
    private val prefs = Prefs(act)
    private val query: EditText = view.findViewById(R.id.query)
    private val keyboard: LinearLayout = view.findViewById(R.id.keyboard)
    private val list: RecyclerView = view.findViewById(R.id.list)
    private val status: TextView = view.findViewById(R.id.status)
    private val progress: View = view.findViewById(R.id.progress)
    private val adapter = GridAdapter(
        onClick = { open(it) },
        onLongClick = { if (it !is GridItem.Query) { remember(); Nav.onCardLongClick(act, it) } },
    )
    private val errorRetry = ErrorRetry(status)
    private var job: Job? = null
    private var popularJob: Job? = null
    private var popular: List<com.diriktv.sidekick.data.LiveStream> = emptyList()
    private var popularAt = 0L
    private var firstKey: View? = null
    private var lastKey: View? = null

    private val voiceIntent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search Kick")
    private val voiceAvailable = voiceIntent.resolveActivity(act.packageManager) != null
    private val voice by lazy {
        // Registered on first use: the page is built after the activity has started.
        act.activityResultRegistry.register("search-voice", androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { setQuery(it, 0) }
        }
    }

    init {
        view.findViewById<SearchPill>(R.id.pill).alwaysOpen = true
        list.layoutManager = CenterGridLayoutManager(act, SPAN).apply {
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = when (adapter.items.getOrNull(position)) {
                    is GridItem.Header -> SPAN
                    is GridItem.Stream, is GridItem.Query -> 2
                    else -> 1
                }
            }
        }
        adapter.cascade = true
        list.adapter = adapter
        list.itemAnimator = null
        buildKeyboard()
        showStart()
    }

    // region keyboard

    private fun buildKeyboard() {
        val gap = act.dp(3)
        fun row() = LinearLayout(act).also { keyboard.addView(it) }
        fun place(r: LinearLayout, key: View, weight: Float = 1f) {
            r.addView(key, LinearLayout.LayoutParams(0, act.dp(40), weight).apply { setMargins(gap, gap, gap, gap) })
            key.setOnFocusChangeListener { v, has -> if (has) lastKey = v }
            key.setOnKeyListener { _, keyCode, event -> typedKey(keyCode, event) }
            if (firstKey == null) firstKey = key
        }
        fun textKey(label: String, onPress: () -> Unit) = TextView(act).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            textSize = 17f
            font(AppFonts.Weight.MEDIUM)
            setTextColor(act.getColorStateList(R.color.on_focus_primary))
            setBackgroundResource(R.drawable.bg_key)
            isFocusable = true
            setOnClickListener { onPress() }
        }
        fun iconKey(icon: Int, description: String, onPress: () -> Unit) = ImageView(act).apply {
            setImageResource(icon)
            contentDescription = description
            imageTintList = act.getColorStateList(R.color.on_focus_primary)
            setBackgroundResource(R.drawable.bg_key)
            setPadding(act.dp(9), act.dp(9), act.dp(9), act.dp(9))
            isFocusable = true
            setOnClickListener { onPress() }
        }

        for (letters in KEY_ROWS) {
            val r = row()
            letters.forEach { c -> place(r, textKey(c.toString()) { type(c.toString()) }) }
        }
        val r = row()
        place(r, textKey("Space") { type(" ") }, 2f)
        // Many Kick names have one; it's on no other key.
        place(r, textKey("_") { type("_") })
        place(r, iconKey(R.drawable.ic_backspace, "Delete") { backspace() })
        place(r, iconKey(R.drawable.ic_close, "Clear") { setQuery("", 0) })
        if (voiceAvailable) place(r, iconKey(R.drawable.ic_mic, "Voice search") { runCatching { voice.launch(voiceIntent) } })
    }

    /** A remote or keyboard with letters types straight in. */
    private fun typedKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (keyCode == KeyEvent.KEYCODE_DEL) { backspace(); return true }
        val c = event.unicodeChar.toChar()
        if (event.unicodeChar != 0 && (c.isLetterOrDigit() || c == ' ' || c == '_')) {
            type(c.lowercaseChar().toString())
            return true
        }
        return false
    }

    private fun type(s: String) {
        val now = query.text.toString()
        if (s == " " && (now.isEmpty() || now.endsWith(" "))) return
        setQuery(now + s, 450)
    }

    private fun backspace() {
        val now = query.text.toString()
        if (now.isNotEmpty()) setQuery(now.dropLast(1), 450)
    }

    private fun focusResults(): Boolean =
        (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus() ?: false

    private fun setQuery(q: String, debounce: Long) {
        errorRetry.hide()
        query.setText(q)
        query.setSelection(q.length)
        search(q, debounce)
    }

    // endregion

    /** Remember what was searched once something from it gets opened. */
    private fun remember() {
        val q = query.text.toString().trim()
        if (q.length >= MIN_QUERY) prefs.recordSearch(q)
    }

    private fun open(item: GridItem) {
        if (item is GridItem.Query) {
            setQuery(item.text, 0)
            return
        }
        remember()
        Nav.onCardClick(act, item)
    }

    /** Nothing (or too little) typed: recent searches and the most-watched streams right now. */
    private fun showStart() {
        job?.cancel()
        progress.setVisible(false)
        errorRetry.hide()
        val items = mutableListOf<GridItem>()
        val typed = query.text.toString().trim()
        val recent = prefs.searchHistory.filter { it.startsWith(typed, ignoreCase = true) }
        if (recent.isNotEmpty()) {
            items += GridItem.Header("Recent searches")
            items += recent.take(6).map { GridItem.Query(it) }
        }
        val shown = popular.filterNot { prefs.isHiddenCategory(it.category) }
        if (shown.isNotEmpty()) {
            items += GridItem.Header("Popular right now")
            items += shown.take(8).map { GridItem.Stream(it) }
        }
        adapter.submit(items)
        status.text = if (items.isEmpty() && popularJob?.isActive != true) "Type a channel, game or category" else ""
        if (System.currentTimeMillis() - popularAt > POPULAR_MAX_AGE_MS && popularJob?.isActive != true) {
            popularJob = act.lifecycleScope.launch {
                val lang = prefs.language.ifEmpty { null }
                popular = runCatching { KickApi.livestreams(language = lang, limit = 12).items }.getOrDefault(emptyList())
                popularAt = System.currentTimeMillis()
                if (query.text.trim().length < MIN_QUERY) showStart()
            }
        }
    }

    private fun search(q: String, debounce: Long) {
        job?.cancel()
        // Kick only searches from three characters.
        if (q.trim().length < MIN_QUERY) {
            showStart()
            return
        }
        job = act.lifecycleScope.launch {
            delay(debounce)
            // Retrying from the error's button: it keeps its "Trying again…" line meanwhile.
            if (!errorRetry.showing) {
                progress.setVisible(adapter.itemCount == 0)
                status.text = ""
            }
            try {
                val found = KickApi.search(q.trim())
                // Live channels as stream cards, with what they're streaming and how many are watching.
                // Plus the biggest streams in the best-matching category ("gta" → GTA V streams).
                val topCategory = found.categories.firstOrNull { !prefs.isHiddenCategory(it.name) }
                val inCategory = async {
                    topCategory?.let { runCatching { KickApi.livestreams(categoryId = it.id, limit = 4).items }.getOrNull() }.orEmpty()
                }
                val streams = (found.channels.filter { it.isLive }.take(6).map { c ->
                    async { runCatching { KickApi.liveStream(c.slug, c.username, c.avatar) }.getOrNull() }
                }.awaitAll().filterNotNull() + inCategory.await())
                    .distinctBy { it.slug.lowercase() }
                    .filterNot { prefs.isHiddenCategory(it.category) }
                    .sortedByDescending { it.viewers }
                val streamSlugs = streams.map { it.slug.lowercase() }.toSet()
                val categories = found.categories.filterNot { prefs.isHiddenCategory(it.name) }
                val channels = found.channels.filter { it.slug.lowercase() !in streamSlugs }
                    .sortedWith(compareByDescending<com.diriktv.sidekick.data.SearchChannel> { it.isLive }.thenByDescending { it.followers })

                val items = mutableListOf<GridItem>()
                if (streams.isNotEmpty()) {
                    items += GridItem.Header("Live now")
                    items += streams.map { GridItem.Stream(it) }
                }
                if (categories.isNotEmpty()) {
                    items += GridItem.Header("Categories")
                    items += categories.map { GridItem.Cat(it) }
                }
                if (channels.isNotEmpty()) {
                    items += GridItem.Header("Channels")
                    items += channels.map {
                        val followers = "${Fmt.count(it.followers)} followers"
                        GridItem.Channel(it.slug, it.username, it.avatar, it.isLive, if (it.isLive) "Live • $followers" else followers)
                    }
                }
                adapter.submit(items)
                status.text = if (items.isEmpty()) "Nothing on Kick matches “${q.trim()}”" else ""
                if (errorRetry.hide()) list.post { if (!focusResults()) focusContent() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                adapter.submit(emptyList())
                errorRetry.show("Couldn't search Kick", e) { search(q, 0) }
            } finally {
                progress.setVisible(false)
            }
        }
    }

    override fun onShown() {
        if (query.text.trim().length < MIN_QUERY) showStart()
    }

    override fun focusContent(): Boolean = (lastKey ?: firstKey)?.requestFocus() ?: false
}

class SettingsPage(private val act: FragmentActivity, private val onChanged: () -> Unit) : MainPage {
    companion object {
        /** The section shown last, so returning to Settings (or a font change restarting it) lands there again. */
        private var lastSection = 0
        private val START_PAGES = listOf("Home", "Following", "Browse", "Search")
        private val CARD_SIZES = listOf("Small", "Medium", "Large")
        private val RAID_MODES = listOf("ask" to "Ask", "auto" to "Go automatically", "off" to "Ignore")
        private val PREVIEW_DELAYS = listOf(0, 1, 2, 3)
        private fun previewLabel(s: Int) = if (s == 0) "Off" else "After ${s}s"
    }

    private val prefs = Prefs(act)
    private val sectionList = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(30), act.dp(4), act.dp(14), act.dp(30))
    }
    private val sectionIndicator = NavIndicator(act)
    private val container = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(14), act.dp(4), act.dp(36), act.dp(40))
    }
    private val scroll = ScrollView(act).apply {
        addView(container)
        isVerticalScrollBarEnabled = false
    }
    override val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(act).apply {
            setTextAppearance(R.style.PageTitle)
            font(AppFonts.Weight.BLACK)
            text = "Settings"
            setPadding(act.dp(36), act.dp(28), 0, act.dp(16))
        })
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            // The same gliding highlight as the side menu, behind the section names.
            addView(android.widget.FrameLayout(act).apply {
                addView(sectionIndicator, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                addView(sectionList, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(act.dp(220), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Hidden categories changed: Home reloads once the menu closes. */
    private var categoriesChanged = false
    private val sheet by lazy {
        OptionSheet(act).apply {
            onDismiss = { if (categoriesChanged) { categoriesChanged = false; onChanged() } }
        }
    }

    private class Section(val title: String, val icon: Int, val rows: () -> List<Setting>)

    private class Setting(
        val title: () -> String,
        val summary: () -> String,
        val icon: Int,
        val value: () -> String?,
        /** Toggles show their state as a pill and flip in place instead of opening a menu. */
        val toggle: (() -> Boolean)? = null,
        /** A picture (the account's avatar) in place of the icon. */
        val avatar: (() -> String?)? = null,
        val onClick: () -> Unit,
    ) {
        constructor(
            title: String, summary: () -> String, icon: Int, value: () -> String?,
            toggle: (() -> Boolean)? = null, onClick: () -> Unit,
        ) : this({ title }, summary, icon, value, toggle, null, onClick)
    }

    private val qualities = listOf(0, 1080, 720, 480, 360, 160)
    private val pipSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
        act.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private fun qualityLabel(h: Int) = if (h == 0) "Auto" else "${h}p"
    private fun onOff(b: Boolean) = if (b) "On" else "Off"
    private val version = "v${com.diriktv.sidekick.BuildConfig.VERSION_NAME}"

    private val sections: List<Section> = listOf(
        Section("Account", R.drawable.ic_account) {
            listOf(
                Setting(
                    title = { if (Follows.signedIn) prefs.accountName ?: "Signed in" else "Sign in to Kick" },
                    summary = {
                        when {
                            Follows.sessionExpired -> "Your Kick sign-in has expired • select to sign in again"
                            Follows.signedIn -> "Signed in to Kick • your followed channels show up in Following and on Home"
                            else -> "Bring your followed channels to the TV"
                        }
                    },
                    icon = R.drawable.ic_account,
                    value = { null },
                    avatar = { if (Follows.signedIn) prefs.accountAvatar else null },
                ) { accountMenu() },
            )
        },
        Section("Playback", R.drawable.ic_playback) {
            listOf(
                Setting("Default quality", { "Used when a stream starts. Auto adapts to your connection." }, R.drawable.ic_hd,
                    { qualityLabel(prefs.preferredQuality) }) {
                    choose("Default quality", "Pick the quality new streams start at.", qualities.map { qualityLabel(it) },
                        subs = listOf("Recommended", "Source on most channels", null, null, null, "Data saver"),
                        selected = qualities.indexOf(prefs.preferredQuality)) { prefs.preferredQuality = qualities[it] }
                },
                Setting("Low latency", { "Closer to live, but more likely to buffer on slower connections" }, R.drawable.ic_bolt,
                    { onOff(prefs.lowLatency) }, toggle = { prefs.lowLatency }) {
                    prefs.lowLatency = !prefs.lowLatency
                },
                Setting("Seek step", { "How far ◀ ▶ jump in the player" }, R.drawable.ic_seek,
                    { "${prefs.seekStepSeconds}s" }) {
                    val steps = listOf(5, 10, 15, 30)
                    choose("Seek step", null, steps.map { "$it seconds" }, selected = steps.indexOf(prefs.seekStepSeconds)) {
                        prefs.seekStepSeconds = steps[it]
                    }
                },
                Setting("Progress line", { "A thin line on past broadcasts showing where you are while the controls are hidden" }, R.drawable.ic_timer,
                    { onOff(prefs.miniProgress) }, toggle = { prefs.miniProgress }) {
                    prefs.miniProgress = !prefs.miniProgress
                },
                Setting("Ask to resume", { "When you reopen a past broadcast, ask whether to pick up where you left off" }, R.drawable.ic_history,
                    { onOff(prefs.askToResume) }, toggle = { prefs.askToResume }) {
                    prefs.askToResume = !prefs.askToResume
                },
                Setting("Raids", { "When the channel you're watching sends its viewers to another" }, R.drawable.ic_raid,
                    { RAID_MODES.firstOrNull { it.first == prefs.raidMode }?.second ?: "Ask" }) {
                    choose("Raids", "When the channel you're watching raids another one.", RAID_MODES.map { it.second },
                        subs = listOf("Offer to go with them", "Go with them straight away", "Stay on the channel"),
                        selected = RAID_MODES.indexOfFirst { it.first == prefs.raidMode }) { prefs.raidMode = RAID_MODES[it].first }
                },
                Setting("Seek previews", { "A picture of where you'd land while scrubbing a past broadcast" }, R.drawable.ic_preview,
                    { onOff(prefs.seekPreviews) }, toggle = { prefs.seekPreviews }) {
                    prefs.seekPreviews = !prefs.seekPreviews
                },
                Setting("Up next when a stream ends", { "Start the next live channel by itself after 10 seconds" }, R.drawable.ic_play,
                    { onOff(prefs.upNextAutoplay) }, toggle = { prefs.upNextAutoplay }) {
                    prefs.upNextAutoplay = !prefs.upNextAutoplay
                },
                Setting("Unfollow from the player", { "Show the heart button for channels you already follow" }, R.drawable.ic_heart_outline,
                    { onOff(prefs.playerUnfollow) }, toggle = { prefs.playerUnfollow }) {
                    prefs.playerUnfollow = !prefs.playerUnfollow
                },
                Setting("Loudness equalization", { "Turns quiet talking up and sudden loud moments down" }, R.drawable.ic_volume,
                    { onOff(prefs.loudnessEq) }, toggle = { prefs.loudnessEq }) {
                    prefs.loudnessEq = !prefs.loudnessEq
                },
            ) + (if (pipSupported) listOf(
                Setting("Picture-in-picture", { "Home shrinks the stream into a corner instead of stopping it" }, R.drawable.ic_pip,
                    { onOff(prefs.autoPip) }, toggle = { prefs.autoPip }) {
                    prefs.autoPip = !prefs.autoPip
                },
            ) else emptyList())
        },
        Section("Chat", R.drawable.ic_chat) {
            listOf(
                Setting("Show chat", { "Open chat automatically on live streams" }, R.drawable.ic_chat,
                    { onOff(prefs.chatEnabled) }, toggle = { prefs.chatEnabled }) {
                    prefs.chatEnabled = !prefs.chatEnabled
                },
                Setting("Chat side", { "Which side of the screen chat sits on" }, R.drawable.ic_layout,
                    { if (prefs.chatSide == "left") "Left" else "Right" }) {
                    val sides = listOf("right", "left")
                    choose("Chat side", "Used for channels without their own layout.", listOf("Right", "Left"),
                        selected = sides.indexOf(prefs.chatSide)) { prefs.chatSide = sides[it] }
                },
                Setting("Chat position", { "Where chat sits next to the stream" }, R.drawable.ic_layout,
                    { if (prefs.chatMode == "side") "Beside video" else "Over video" }) {
                    val modes = listOf("side", "overlay")
                    choose("Chat position", null, listOf("Beside the video", "Over the video"),
                        subs = listOf("Video shrinks to make room", "See-through panel on top"),
                        selected = modes.indexOf(prefs.chatMode)) { prefs.chatMode = modes[it] }
                },
                Setting("Chat width", { "How much room chat takes beside the video" }, R.drawable.ic_width,
                    { Prefs.chatWidthLabel(prefs.chatWidth) }) {
                    val widths = Prefs.CHAT_WIDTHS
                    choose("Chat width", "How much room chat takes beside the video.", widths.map { it.second },
                        values = widths.map { "${it.first}dp" }, selected = widths.indexOfFirst { it.first == prefs.chatWidth }) {
                        prefs.chatWidth = widths[it].first
                    }
                },
                Setting("Channel layouts", { "Layouts you saved for particular channels in the player (Chat → Customize layout)" }, R.drawable.ic_layout,
                    { prefs.channelChatLayouts.let { if (it == 0) "None" else "$it saved" } }) {
                    if (prefs.channelChatLayouts == 0) return@Setting
                    OptionSheet.confirm(act, "Forget channel layouts?", "Every channel goes back to the default chat layout.",
                        "Forget layouts", R.drawable.ic_delete) {
                        prefs.clearChannelChatLayouts()
                        render()
                    }
                },
                Setting("Chat text size", { "How big chat messages are" }, R.drawable.ic_text, { "${prefs.chatTextSize}sp" }) {
                    val sizes = Prefs.CHAT_TEXT_SIZES
                    choose("Chat text size", null, sizes.map { it.second },
                        subs = sizes.map { "viewer: hi chat!" }, subSizes = sizes.map { it.first.toFloat() },
                        values = sizes.map { "${it.first}sp" }, selected = sizes.indexOfFirst { it.first == prefs.chatTextSize }) {
                        prefs.chatTextSize = sizes[it].first
                    }
                },
                Setting("Message spacing", { "Room between chat messages" }, R.drawable.ic_text,
                    { Prefs.CHAT_SPACINGS[prefs.chatSpacing].second }) {
                    choose("Message spacing", null, Prefs.CHAT_SPACINGS.map { it.second }, selected = prefs.chatSpacing) { prefs.chatSpacing = it }
                },
                Setting("Emote size", { "How big emotes are next to the text" }, R.drawable.ic_emote,
                    { Prefs.emoteSizeLabel(prefs.chatEmoteScale) }) {
                    choose("Emote size", null, Prefs.EMOTE_SIZES.map { it.second },
                        selected = Prefs.EMOTE_SIZES.indexOfFirst { it.first == prefs.chatEmoteScale }) {
                        prefs.chatEmoteScale = Prefs.EMOTE_SIZES[it].first
                    }
                },
                Setting("Pinned message", { "Show the channel's pinned message above chat" }, R.drawable.ic_pin,
                    { onOff(prefs.chatPinned) }, toggle = { prefs.chatPinned }) {
                    prefs.chatPinned = !prefs.chatPinned
                },
                Setting("Polls & predictions", { "Live results above chat" }, R.drawable.ic_poll,
                    { onOff(prefs.chatPolls) }, toggle = { prefs.chatPolls }) {
                    prefs.chatPolls = !prefs.chatPolls
                },
                Setting("Timestamps", { "Show when each message arrived" }, R.drawable.ic_clock,
                    { onOff(prefs.chatTimestamps) }, toggle = { prefs.chatTimestamps }) {
                    prefs.chatTimestamps = !prefs.chatTimestamps
                },
                Setting("Animated emotes", { "Play moving emotes in chat. Uses more memory; off by default on smaller TVs." }, R.drawable.ic_emote,
                    { onOff(prefs.chatAnimatedEmotes) }, toggle = { prefs.chatAnimatedEmotes }) {
                    prefs.chatAnimatedEmotes = !prefs.chatAnimatedEmotes
                },
                Setting("7TV emotes", { "Show channels' 7TV emotes as images" }, R.drawable.ic_emote,
                    { onOff(prefs.sevenTvEmotes) }, toggle = { prefs.sevenTvEmotes }) {
                    prefs.sevenTvEmotes = !prefs.sevenTvEmotes
                },
                Setting("Hide bot messages", { "Botrix, Kickbot and anyone with a bot badge" }, R.drawable.ic_bot,
                    { onOff(prefs.chatHideBots) }, toggle = { prefs.chatHideBots }) {
                    prefs.chatHideBots = !prefs.chatHideBots
                },
                Setting("Muted words", { "Hide messages containing these words or phrases" }, R.drawable.ic_hide,
                    { prefs.chatMutedWords.size.takeIf { it > 0 }?.toString() ?: "None" }) {
                    ChatFilterLists.mutedWords(act) { render() }
                },
                Setting("Blocked users", { "Never show these people's messages" }, R.drawable.ic_hide,
                    { prefs.chatBlockedUsers.size.takeIf { it > 0 }?.toString() ?: "None" }) {
                    ChatFilterLists.blockedUsers(act) { render() }
                },
                Setting("Hide !commands", { "Messages that start with !" }, R.drawable.ic_command,
                    { onOff(prefs.chatHideCommands) }, toggle = { prefs.chatHideCommands }) {
                    prefs.chatHideCommands = !prefs.chatHideCommands
                },
                Setting("Chat replay", { "Play a past broadcast's chat alongside it" }, R.drawable.ic_history,
                    { onOff(prefs.chatReplay) }, toggle = { prefs.chatReplay }) {
                    prefs.chatReplay = !prefs.chatReplay
                },
                Setting("Calm busy chats", { "Steady pace that stays readable; skips some messages when chat floods" }, R.drawable.ic_calm,
                    { onOff(prefs.chatCalm) }, toggle = { prefs.chatCalm }) {
                    prefs.chatCalm = !prefs.chatCalm
                },
            )
        },
        Section("Browsing", R.drawable.ic_compass) {
            listOf(
                Setting("Previews", { "Play a muted preview when a live stream stays in focus" }, R.drawable.ic_preview,
                    { previewLabel(prefs.previewDelay) }) {
                    choose("Previews", "A live card plays a muted preview once it's been in focus this long.",
                        PREVIEW_DELAYS.map { previewLabel(it) }, selected = PREVIEW_DELAYS.indexOf(prefs.previewDelay).coerceAtLeast(0)) {
                        prefs.previewDelay = PREVIEW_DELAYS[it]
                    }
                },
                Setting("Card size", { "How big the cards on Home are" }, R.drawable.ic_grid,
                    { CARD_SIZES[prefs.cardSize.coerceIn(0, 2)] }) {
                    choose("Card size", "Smaller cards fit more on each row of Home.", CARD_SIZES, selected = prefs.cardSize) {
                        if (it != prefs.cardSize) {
                            prefs.cardSize = it
                            view.postDelayed({ act.recreate() }, 200)
                        }
                    }
                },
                Setting("Start-up page", { "Where the app opens" }, R.drawable.ic_startup,
                    { START_PAGES.getOrElse(prefs.startPage) { "Home" } }) {
                    choose("Start-up page", "The page Sidekick opens on.", START_PAGES, selected = prefs.startPage) {
                        prefs.startPage = it
                    }
                },
                Setting("Stream language", { "Filters the rows on Home" }, R.drawable.ic_language,
                    { Languages.label(prefs.language) }) {
                    choose("Stream language", "Home shows streams in this language.", Languages.all.map { it.second },
                        selected = Languages.all.indexOfFirst { it.first == prefs.language }) {
                        prefs.language = Languages.all[it].first
                        onChanged()
                    }
                },
                Setting("Hide categories", { "Keep categories you don't want off Home, Browse and Search" }, R.drawable.ic_hide,
                    { prefs.hiddenCategories.size.let { if (it == 0) "None" else "$it hidden" } }) { hideCategoriesMenu() },
                Setting("Go-live alerts", { "A notice when someone you follow goes live while the app is open" }, R.drawable.ic_broadcast,
                    { onOff(prefs.liveAlerts) }, toggle = { prefs.liveAlerts }) {
                    prefs.liveAlerts = !prefs.liveAlerts
                },
                Setting("Clear search history", { "Forgets your recent searches" }, R.drawable.ic_search_clear,
                    { prefs.searchHistory.size.let { if (it == 0) "Empty" else "$it saved" } }) {
                    if (prefs.searchHistory.isEmpty()) return@Setting
                    OptionSheet.confirm(act, "Clear search history?", "Recent searches on the Search page are removed.",
                        "Clear searches", R.drawable.ic_search_clear) {
                        prefs.searchHistory = emptyList()
                        render()
                        android.widget.Toast.makeText(act, "Search history cleared", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                Setting("Clear watch history", { "Empties Continue watching and Recently watched" }, R.drawable.ic_history,
                    { null }) {
                    OptionSheet.confirm(act, "Clear watch history?", "Resume points for past broadcasts are cleared too.",
                        "Clear history", R.drawable.ic_delete) {
                        prefs.clearHistory()
                        com.diriktv.sidekick.tv.TvHome.clearWatchNext(act)
                        onChanged()
                        android.widget.Toast.makeText(act, "Watch history cleared", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
        },
        Section("Appearance", R.drawable.ic_appearance) {
            listOf(
                Setting("Font", { "The typeface used across the app" }, R.drawable.ic_font,
                    { AppFonts.current(act).label }) { fontMenu() },
                Setting("Reduce motion", { "Turns off slides, drifting pictures, pulsing glows and the opening animation" }, R.drawable.ic_calm,
                    { onOff(prefs.reduceMotion) }, toggle = { prefs.reduceMotion }) {
                    prefs.reduceMotion = !prefs.reduceMotion
                    Motion.reduced = prefs.reduceMotion
                },
                Setting("Larger text", { "Bigger titles and names on cards, easier to read from the sofa" }, R.drawable.ic_text,
                    { onOff(prefs.largerText) }, toggle = { prefs.largerText }) {
                    prefs.largerText = !prefs.largerText
                    view.postDelayed({ act.recreate() }, 200)
                },
                Setting("Page transitions", { "Pages slide in from the direction you move in the side menu" }, R.drawable.ic_layout,
                    { onOff(prefs.pageTransitions) }, toggle = { prefs.pageTransitions }) {
                    prefs.pageTransitions = !prefs.pageTransitions
                },
            )
        },
        Section("App", R.drawable.ic_tv) {
            listOf(
                Setting("About Sidekick", { "Unofficial app • not affiliated with Kick • privacy & license" }, R.drawable.ic_info,
                    { version }) { aboutMenu() },
                Setting("Check for updates", { "Updates come from github.com/${com.diriktv.sidekick.data.Updater.REPO}" }, R.drawable.ic_refresh,
                    { null }) { checkForUpdates() },
                Setting("Automatic updates", { "When to look for a new version and offer it" }, R.drawable.ic_download,
                    { prefs.updateSchedule.label }) {
                    val options = com.diriktv.sidekick.data.Updater.Schedule.entries
                    choose("Automatic updates", "New versions are offered when you open the app.", options.map { it.label },
                        subs = options.map { it.sub }, selected = options.indexOf(prefs.updateSchedule)) {
                        prefs.updateSchedule = options[it]
                    }
                },
                Setting("Clear cache", { "Saved pictures and lookups, to free up space. They come back as you browse." },
                    R.drawable.ic_delete, { cacheSize?.let { Fmt.bytes(it) } ?: "…" }) {
                    OptionSheet.confirm(act, "Clear the cache?", "Uses ${cacheSize?.let { Fmt.bytes(it) } ?: "a little"} now. " +
                        "Your settings, follows and history stay.", "Clear cache", R.drawable.ic_delete) {
                        act.lifecycleScope.launch {
                            com.diriktv.sidekick.data.CacheCleaner.clear(act)
                            cacheSize = null
                            measureCache()
                            android.widget.Toast.makeText(act, "Cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                Setting("Report a problem", { "Opens a bug report on your phone, with this TV's details (and the last crash, if there was one) filled in" },
                    R.drawable.ic_info, { null }) {
                    val crash = com.diriktv.sidekick.data.CrashReports.pending(act)
                    QrDialog.show(act, "Report a problem",
                        "Scan with your phone to open a bug report on GitHub. Sidekick's version and this TV's model" +
                            (if (crash != null) ", plus the details of the last crash," else "") +
                            " are filled in for you. Nothing is sent until you submit it.",
                        com.diriktv.sidekick.data.CrashReports.issueUrl(crash), "github.com/${com.diriktv.sidekick.data.Updater.REPO}/issues")
                },
                Setting("Back up & restore", { "Save your settings, favourites and history to your phone, or put them back on this TV" },
                    R.drawable.ic_download, { null }) { backupDialog() },
                Setting("Unpair phones", { "Phones that scanned the chat code can no longer read or send chat; the next scan gets a new code" },
                    R.drawable.ic_phone, { null }) {
                    OptionSheet.confirm(act, "Unpair phones?", "Every phone that scanned the chat code stops working until it scans the new one.",
                        "Unpair phones", R.drawable.ic_phone) {
                        com.diriktv.sidekick.data.PhoneChat.unpairAll(act)
                        android.widget.Toast.makeText(act, "Phones unpaired", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
        },
    )

    /**
     * A QR code to a page on the home network for saving a backup to the phone, or sending one
     * back. The page only works while the code is on screen; a restore is confirmed here first.
     */
    private fun backupDialog() {
        val url = com.diriktv.sidekick.data.Backup.start(act)
        if (url == null) {
            android.widget.Toast.makeText(act, "Connect the TV to your home network first", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        var dialog: android.app.Dialog? = null
        com.diriktv.sidekick.data.Backup.onRestoreOffered = { backup ->
            dialog?.dismiss()
            OptionSheet.confirm(act, "Restore this backup?",
                "It has ${com.diriktv.sidekick.data.Backup.describe(backup)}. Your settings, favourites and history on this TV are replaced; your Kick sign-in stays.",
                "Restore", R.drawable.ic_refresh) {
                com.diriktv.sidekick.data.Backup.restore(act, backup)
                android.widget.Toast.makeText(act, "Backup restored", android.widget.Toast.LENGTH_SHORT).show()
                act.recreate()
            }
        }
        dialog = QrDialog.show(act, "Back up & restore",
            "Scan with your phone (on the same Wi-Fi) to save a backup there, or to restore one. Your Kick sign-in isn't included.",
            url) { if (com.diriktv.sidekick.data.Backup.onRestoreOffered != null) com.diriktv.sidekick.data.Backup.stop() }
    }

    /** How much the cache takes, measured off the main thread when Settings opens. */
    private var cacheSize: Long? = null

    private fun measureCache() {
        act.lifecycleScope.launch {
            cacheSize = com.diriktv.sidekick.data.CacheCleaner.size(act)
            render()
        }
    }

    private var current: Int = lastSection.coerceIn(0, sections.size - 1)
    private val sectionViews: List<TextView> = sections.mapIndexed { i, sec ->
        TextView(act, null, 0, R.style.NavItem).apply {
            text = sec.title
            font(AppFonts.Weight.MEDIUM)
            setCompoundDrawablesRelativeWithIntrinsicBounds(sec.icon, 0, 0, 0)
            compoundDrawablePadding = act.dp(12)
            background = null
            setOnFocusChangeListener { v, has ->
                if (has) {
                    sectionIndicator.moveTo(v, focused = true)
                    if (current != i) select(i)
                } else {
                    // Focus went to the settings on the right (or the side menu): rest on the open section.
                    v.post { if (sectionViews.none { it.isFocused }) sectionIndicator.moveTo(sectionViews[current], focused = false) }
                }
            }
            setOnClickListener { focusFirstRow() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> focusFirstRow()
                    // The ends of the list stay put rather than wandering onto the nav rail.
                    KeyEvent.KEYCODE_DPAD_UP -> i == 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> i == sections.size - 1
                    else -> false
                }
            }
            sectionList.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dp(42)).apply {
                bottomMargin = act.dp(6)
            })
        }
    }
    private var rows: List<Setting> = emptyList()

    /** Shows a section's rows. Only the right side is rebuilt, so focus on the section list stays put. */
    private fun select(i: Int) {
        val from = current
        current = i
        lastSection = i
        sectionViews.forEachIndexed { idx, v -> v.isActivated = idx == i }
        sectionViews[i].let { v -> v.post { if (!v.isFocused && sectionViews.none { it.isFocused }) sectionIndicator.moveTo(v, focused = false, animate = false) } }
        rows = sections[i].rows()
        container.removeAllViews()
        rows.forEach { container.addView(container.inflate(R.layout.item_setting)) }
        if (sections[i].title == "App") container.addView(TextView(act).apply {
            text = "Sidekick ${com.diriktv.sidekick.BuildConfig.VERSION_NAME} • an unofficial app for Kick, not affiliated with Kick\n" +
                "Tips: hold OK (or press ☰) on a stream to open its channel.\n" +
                "In the player: ◀ ▶ seek (hold to speed up), ▲ ▼ show controls, ☰ opens settings (multi-view, sleep timer)."
            setTextColor(act.getColor(R.color.text_secondary))
            textSize = 12f
            font(AppFonts.Weight.REGULAR)
            setLineSpacing(0f, 1.3f)
            setPadding(act.dp(6), act.dp(16), 0, 0)
        })
        scroll.scrollTo(0, 0)
        render()
        // Like the side menu's pages: moving down the sections, the new one rises from below;
        // moving up, it drops in from above (Settings → Appearance → Page transitions).
        container.animate().cancel()
        container.translationX = 0f
        if (from != i && prefs.pageTransitions && !act.motionOff()) {
            container.alpha = 0f
            container.translationY = (if (i > from) 1 else -1) * act.dp(36).toFloat()
            container.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        } else {
            container.alpha = 1f
            container.translationY = 0f
        }
    }

    private fun focusFirstRow(): Boolean = container.getChildAt(0)?.requestFocus() ?: false

    /** Updates the rows' contents in place (rebuilding would drop focus). */
    private fun render() {
        rows.forEachIndexed { i, row -> container.getChildAt(i)?.let { bindRow(it, row) } }
    }

    private val iconTint by lazy { act.getColorStateList(R.color.on_focus_primary) }

    init {
        select(current)
    }

    private fun bindRow(v: View, row: Setting) {
        val avatar = row.avatar?.invoke()
        v.findViewById<ImageView>(R.id.icon).apply {
            if (avatar != null) {
                imageTintList = null
                layoutParams = layoutParams.apply { width = act.dp(30); height = act.dp(30) }
                loadAvatar(avatar, prefs.accountName)
            } else {
                imageTintList = iconTint
                layoutParams = layoutParams.apply { width = act.dp(22); height = act.dp(22) }
                setImageResource(row.icon)
            }
        }
        v.findViewById<TextView>(R.id.title).text = row.title()
        val summary = row.summary()
        v.findViewById<TextView>(R.id.summary).apply { text = summary; setVisible(summary.isNotEmpty()) }
        val value = row.value()
        v.findViewById<TextView>(R.id.value).apply {
            text = value.orEmpty()
            setVisible(value != null && row.toggle == null)
        }
        v.findViewById<LiquidToggle>(R.id.toggle).apply {
            setVisible(row.toggle != null)
            row.toggle?.let { setChecked(it(), animate = true) }
        }
        v.findViewById<View>(R.id.chevron).setVisible(row.toggle == null)
        v.setOnClickListener {
            row.onClick()
            if (row.toggle != null) render()
        }
        v.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> sectionViews[current].requestFocus()
                // The ends of the list stay put (holding ▼ used to run off onto the account at the
                // bottom of the side menu).
                KeyEvent.KEYCODE_DPAD_DOWN -> container.indexOfChild(v) == rows.size - 1
                KeyEvent.KEYCODE_DPAD_UP -> container.indexOfChild(v) == 0
                else -> false
            }
        }
    }

    private fun choose(
        title: String,
        subtitle: String?,
        options: List<String>,
        subs: List<String?> = emptyList(),
        values: List<String?> = emptyList(),
        subSizes: List<Float?> = emptyList(),
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        sheet.show(OptionSheet.Page(title, eyebrow = "Settings", subtitle = subtitle) {
            options.mapIndexed { i, label ->
                OptionSheet.Item(label, sub = subs.getOrNull(i), value = values.getOrNull(i), checked = i == selected,
                    subSizeSp = subSizes.getOrNull(i)) {
                    onPick(i)
                    it.dismiss()
                    render()
                }
            }
        })
    }

    private fun fontMenu() {
        val fonts = AppFonts.Font.entries
        sheet.show(OptionSheet.Page("Font", eyebrow = "Settings", subtitle = "Each name is shown in its own font.") {
            fonts.map { f ->
                OptionSheet.Item(f.label, sub = f.sub, checked = f == AppFonts.current(act),
                    labelFont = AppFonts.preview(act, f)) {
                    it.dismiss()
                    if (f != AppFonts.current(act)) {
                        prefs.font = f.key
                        // Every screen picks the font up as it's built: rebuild this one.
                        view.postDelayed({ act.recreate() }, 200)
                    }
                }
            }
        })
    }

    private fun hideCategoriesMenu() {
        var loaded: List<com.diriktv.sidekick.data.Category> = emptyList()
        sheet.show(OptionSheet.Page("Hide categories", eyebrow = "Settings",
            subtitle = "Streams and clips in these won't show on Home, Browse or Search.") {
            val hidden = prefs.hiddenCategories
            // Most-watched first, plus anything hidden earlier that isn't in that list.
            val names = (loaded.map { it.name } + hidden).distinctBy { it.lowercase() }
            if (loaded.isEmpty() && hidden.isEmpty()) listOf(OptionSheet.Item("Loading categories…", shimmer = true) {})
            else names.map { name ->
                val isHidden = name.lowercase() in hidden
                OptionSheet.Item(name, value = if (isHidden) "Hidden" else null, checked = isHidden) {
                    prefs.hiddenCategories = if (isHidden) hidden - name.lowercase() else hidden + name.lowercase()
                    categoriesChanged = true
                    it.refresh()
                    render()
                }
            }
        })
        act.lifecycleScope.launch {
            loaded = runCatching {
                (1..2).flatMap { KickApi.categories(it, 40).items }.distinctBy { it.id }
            }.getOrDefault(emptyList())
            if (sheet.isShowing) sheet.refresh()
        }
    }

    private fun checkForUpdates() {
        android.widget.Toast.makeText(act, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show()
        act.lifecycleScope.launch {
            try {
                // Debug builds always offer the latest release, so the update screens can be tried.
                val release = com.diriktv.sidekick.data.Updater.checkNow(force = com.diriktv.sidekick.BuildConfig.DEBUG)
                if (release != null) UpdateFlow(act, release).show()
                else android.widget.Toast.makeText(act, "You're on the latest version.", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val why = if (com.diriktv.sidekick.data.KickErrors.isOffline(e)) "Check your internet connection, then try again."
                else "GitHub didn't answer. Try again in a few minutes."
                android.widget.Toast.makeText(act, "Couldn't check for updates. $why", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun aboutMenu() {
        sheet.show(OptionSheet.Page("Sidekick ${com.diriktv.sidekick.BuildConfig.VERSION_NAME}", eyebrow = "About",
            subtitle = "A free, open-source TV app for watching Kick.") {
            listOf(
                OptionSheet.Item("Not affiliated with Kick", icon = R.drawable.ic_info,
                    sub = "Sidekick is an unofficial app. It isn't made, endorsed or supported by Kick. " +
                        "\"Kick\" is a trademark of its owner. It uses Kick's public website, which can change at any time.") {},
                OptionSheet.Item("Privacy", icon = R.drawable.ic_account,
                    sub = "No accounts, ads or tracking. Your Kick sign-in stays on this TV, encrypted, and is only sent to Kick. " +
                        "Settings and watch history never leave the device.") {},
                OptionSheet.Item("Open source • MIT license", icon = R.drawable.ic_text,
                    sub = "github.com/${com.diriktv.sidekick.data.Updater.REPO}") {},
            )
        })
    }

    /** Sign-in, or the signed-in account's options (from the account at the bottom of the menu). */
    fun openAccount() = accountMenu()

    private fun accountMenu() {
        if (!Follows.signedIn) {
            act.startActivity(android.content.Intent(act, LoginActivity::class.java))
            return
        }
        sheet.show(OptionSheet.Page(prefs.accountName ?: "Account", eyebrow = "Kick account",
            subtitle = if (Follows.sessionExpired) "Kick no longer accepts this TV's sign-in. Sign in again to bring back your follows." else null) {
            listOfNotNull(
                if (Follows.sessionExpired) OptionSheet.Item("Sign in again", icon = R.drawable.ic_account) {
                    it.dismiss()
                    act.startActivity(android.content.Intent(act, LoginActivity::class.java))
                } else null,
                OptionSheet.Item("Refresh followed channels", icon = R.drawable.ic_refresh) {
                    Follows.invalidate(); onChanged(); it.dismiss()
                },
                OptionSheet.Item("Sign out", sub = "Favorites saved on this TV are kept", icon = R.drawable.ic_logout) {
                    Follows.signOut(act); it.dismiss(); render(); onChanged()
                    (act as? MainActivity)?.showAccount()
                },
            )
        })
    }

    fun onResume() = render()

    override fun onShown() {
        render()
        measureCache()
    }

    override fun focusContent(): Boolean = sectionViews[current].requestFocus()
}
