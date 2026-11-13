package com.diriktv.sidekick.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.ChannelInfo
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.LiveStream
import com.diriktv.sidekick.data.Prefs
import com.diriktv.sidekick.data.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Channel page: live status, favorite toggle, and tabs for past broadcasts and clips. */
class ChannelActivity : FragmentActivity() {
    companion object {
        const val EXTRA_SLUG = "slug"
    }

    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private var channel: ChannelInfo? = null
    private var videos: List<Video> = emptyList()
    private var clips: List<com.diriktv.sidekick.data.Clip> = emptyList()
    /** The next page of clips, or null when there are no more. */
    private var clipsNext: String? = null
    private var clipsLoading = false
    /** Which clips: Kick's day, week, month or all. */
    private var clipTime = "all"
    private val clipTimes = listOf("day" to "Today", "week" to "This week", "month" to "This month", "all" to "All time")
    private lateinit var vodAdapter: GridAdapter
    private var header: View? = null
    /** The open tab: 0 past broadcasts, 1 clips. */
    private var tab = 0
    private var loaded = false

    private val headerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (channel != null) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(parent.inflate(R.layout.item_channel_header)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            header = holder.itemView
            bindHeader(holder.itemView)
            holder.itemView.alpha = 0f
            holder.itemView.translationY = dp(14).toFloat()
            holder.itemView.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppFonts.install(this)
        setContentView(R.layout.activity_channel)
        prefs = Prefs(this)
        list = findViewById(R.id.list)
        vodAdapter = GridAdapter(onClick = { Nav.onCardClick(this, it) }, onNearEnd = { if (tab == 1) loadMoreClips() })
        val span = 4
        list.layoutManager = CenterGridLayoutManager(this, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val offset = headerAdapter.itemCount
                    if (position < offset) return span
                    return if (vodAdapter.items.getOrNull(position - offset) is GridItem.Header) span else 1
                }
            }
        }
        list.adapter = ConcatAdapter(headerAdapter, vodAdapter)
        list.itemAnimator = null
        // Moving onto a tab opens it. Coming up from the grid (or down from the buttons) lands
        // on the open tab, so passing through doesn't switch to the other one.
        list.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            val tabs = tabViews()
            val i = tabs.indexOf(new)
            if (i < 0) return@addOnGlobalFocusChangeListener
            if (old !in tabs && i != tab) tabs[tab].requestFocus()
            else if (i != tab) { tab = i; submitVideos(switched = true) }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        // Refresh resume bars after watching a VOD.
        if (videos.isNotEmpty()) submitVideos()
    }

    private val errorRetry by lazy { ErrorRetry(findViewById(R.id.status)) }
    /** Why the past broadcasts or clips couldn't load (null when they did, even if there are none). */
    private var videosError: Throwable? = null
    private var clipsError: Throwable? = null

    private fun load() {
        val slug = intent.getStringExtra(EXTRA_SLUG) ?: return finish()
        findViewById<View>(R.id.progress).setVisible(!errorRetry.showing)
        lifecycleScope.launch {
            val vids = async { runCatching { KickApi.videos(slug) } }
            val clipsJob = async { runCatching { KickApi.clips(slug, time = clipTime) } }
            try {
                channel = KickApi.channel(slug)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                findViewById<View>(R.id.progress).setVisible(false)
                // Stay on the page with a way to try again, rather than closing with a cryptic toast.
                errorRetry.show("Couldn't load this channel", e) { load() }
                errorRetry.focusable?.requestFocus()
                return@launch
            }
            findViewById<View>(R.id.status).setVisible(false)
            errorRetry.hide()
            findViewById<ImageView>(R.id.bannerBg).loadThumb(channel?.banner)
            headerAdapter.notifyItemInserted(0)
            vids.await().let { r -> videos = r.getOrDefault(emptyList()); videosError = r.exceptionOrNull() }
            clipsJob.await().let { r -> clips = r.getOrNull()?.items.orEmpty(); clipsNext = r.getOrNull()?.next; clipsError = r.exceptionOrNull() }
            if (videos.isEmpty() && videosError == null && clips.isNotEmpty()) tab = 1
            loaded = true
            submitVideos()
            findViewById<View>(R.id.progress).setVisible(false)
            list.post {
                val h = header ?: return@post
                val watch = h.findViewById<View>(R.id.btnWatch)
                if (watch.visibility == View.VISIBLE) watch.requestFocus() else h.findViewById<View>(R.id.btnFav).requestFocus()
            }
        }
    }

    private fun tabViews(): List<View> = header?.let { listOf<View>(it.findViewById(R.id.tabVods), it.findViewById(R.id.tabClips)) }.orEmpty()

    private fun submitVideos(switched: Boolean = false) {
        val c = channel ?: return
        val items: List<GridItem> = if (tab == 0) {
            // The broadcast that's still going is shown as the live stream itself, not its recording.
            val live = if (c.isLive) listOf(GridItem.Stream(LiveStream(c.slug, "Current stream", c.liveTitle.orEmpty(),
                c.liveViewers, videos.firstOrNull { it.isLive }?.thumbnail ?: c.liveThumbnail, c.avatar, c.liveCategory, null, c.liveStartTime))) else emptyList()
            live + videos.filterNot { it.isLive && c.isLive }.map { v ->
                val pos = prefs.vodPosition(v.uuid)
                GridItem.Vod(v, c.slug, if (v.durationMs > 0) pos.toFloat() / v.durationMs else 0f)
            }
        } else {
            clips.map { GridItem.Clip(it) }
        }
        when {
            switched -> vodAdapter.swap(items)
            vodAdapter.items.size == items.size -> vodAdapter.update(items)
            else -> vodAdapter.submit(items)
        }
        showTabs()
    }

    /** The next page of clips, as the grid nears its end. */
    private fun loadMoreClips() {
        val c = channel ?: return
        val cursor = clipsNext ?: return
        if (clipsLoading) return
        clipsLoading = true
        val time = clipTime
        lifecycleScope.launch {
            val page = runCatching { KickApi.clips(c.slug, cursor, time) }.getOrNull()
            clipsLoading = false
            if (page == null || time != clipTime) return@launch
            val seen = clips.map { it.id }.toSet()
            val more = page.items.filter { it.id !in seen }
            clips = clips + more
            clipsNext = page.next
            if (tab == 1) vodAdapter.append(more.map { GridItem.Clip(it) })
        }
    }

    /** Today, this week, this month or all time: the clips tab starts over with that list. */
    private fun chooseClipTime() {
        val c = channel ?: return
        OptionSheet(this).show(OptionSheet.Page("Top clips", eyebrow = c.username) {
            clipTimes.map { (key, label) ->
                OptionSheet.Item(label, checked = key == clipTime) { sheet ->
                    sheet.dismiss()
                    if (key == clipTime) return@Item
                    clipTime = key
                    header?.findViewById<TextView>(R.id.clipSort)?.text = label
                    clips = emptyList(); clipsNext = null
                    retryTab()
                }
            }
        })
    }

    /** The bio and the channel's links; a link opens on your phone through a QR code. */
    private fun showAbout() {
        val c = channel ?: return
        OptionSheet(this).show(OptionSheet.Page("About ${c.username}", subtitle = c.bio?.takeIf { it.isNotBlank() }?.take(400)) {
            c.socials.map { (name, url) ->
                OptionSheet.Item(name, sub = url.removePrefix("https://"), icon = R.drawable.ic_info) { sheet ->
                    sheet.dismiss()
                    QrDialog.show(this@ChannelActivity, "${c.username} on $name", "Scan with your phone to open it.", url, url.removePrefix("https://"))
                }
            }.ifEmpty { listOf(OptionSheet.Item("No links", sub = "${c.username} hasn't added any") { it.dismiss() }) }
        })
    }

    private fun showTabs() {
        val h = header ?: return
        tabViews().forEachIndexed { i, t -> t.isActivated = i == tab }
        h.findViewById<TextView>(R.id.clipSort).apply {
            setVisible(tab == 1)
            text = "${clipTimes.first { it.first == clipTime }.second}  ▾"
        }
        val error = if (tab == 0) videosError else clipsError
        h.findViewById<TextView>(R.id.tabEmpty).apply {
            // "Empty" and "couldn't load" are different things: a failed list says so, and OK retries it.
            text = when {
                error != null -> "Couldn't load ${if (tab == 0) "past broadcasts" else "clips"}. ${com.diriktv.sidekick.data.KickErrors.describe(error)}  Press OK to try again."
                tab == 0 -> "No past broadcasts"
                else -> "No clips yet"
            }
            setVisible(loaded && vodAdapter.items.isEmpty())
            isFocusable = error != null
            setBackgroundResource(if (error != null) R.drawable.bg_pill else 0)
            val pad = if (error != null) dp(14) else 0
            setPadding(pad, if (error != null) dp(8) else 0, pad, if (error != null) dp(8) else 0)
            setOnClickListener(if (error != null) View.OnClickListener { retryTab() } else null)
        }
    }

    /** Loads the open tab's list again after it failed. */
    private fun retryTab() {
        val c = channel ?: return
        val which = tab
        header?.findViewById<TextView>(R.id.tabEmpty)?.text = "Trying again…"
        lifecycleScope.launch {
            if (which == 0) {
                val r = runCatching { KickApi.videos(c.slug) }
                videos = r.getOrDefault(emptyList()); videosError = r.exceptionOrNull()
            } else {
                val r = runCatching { KickApi.clips(c.slug, time = clipTime) }
                clips = r.getOrNull()?.items.orEmpty(); clipsNext = r.getOrNull()?.next; clipsError = r.exceptionOrNull()
            }
            if (tab != which) return@launch
            val hadFocus = header?.findViewById<View>(R.id.tabEmpty)?.isFocused == true
            submitVideos(switched = true)
            if (hadFocus) list.post {
                list.layoutManager?.findViewByPosition(headerAdapter.itemCount)?.requestFocus()
                    ?: tabViews().getOrNull(tab)?.requestFocus()
            }
        }
    }

    private fun bindHeader(v: View) {
        val c = channel ?: return
        v.findViewById<ImageView>(R.id.avatar).loadAvatar(c.avatar, c.username)
        v.findViewById<TextView>(R.id.name).text = c.username + if (c.verified) "  ✓" else ""
        v.findViewById<TextView>(R.id.followers).text = "${Fmt.count(c.followers)} followers"
        v.findViewById<TextView>(R.id.bio).apply { text = c.bio.orEmpty(); setVisible(!c.bio.isNullOrBlank()) }
        v.findViewById<TextView>(R.id.liveInfo).apply {
            setVisible(c.isLive)
            text = listOfNotNull("● LIVE", Fmt.viewers(c.liveViewers), c.liveCategory, c.liveTitle).joinToString("  •  ")
        }
        v.findViewById<TextView>(R.id.btnWatch).apply {
            setVisible(c.isLive)
            setOnClickListener { Nav.watchLive(this@ChannelActivity, c.slug) }
            focusZoom(1.05f)
            // It sits flush with the page's left edge: grow to the right so its rounded end isn't cut off.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = 0f; v.pivotY = v.height / 2f }
        }
        val fav = v.findViewById<TextView>(R.id.btnFav)
        val alerts = v.findViewById<TextView>(R.id.btnAlerts)
        fun updateAlerts() {
            val muted = c.slug.lowercase() in prefs.mutedAlerts
            alerts.text = if (muted) "Alerts off" else "Alerts on"
            alerts.alpha = if (muted) 0.7f else 1f
            alerts.setVisible(Follows.isFollowing(this, c.slug) && prefs.liveAlerts)
        }
        alerts.focusZoom(1.05f)
        alerts.setOnClickListener {
            val key = c.slug.lowercase()
            val muted = key in prefs.mutedAlerts
            prefs.mutedAlerts = if (muted) prefs.mutedAlerts - key else prefs.mutedAlerts + key
            updateAlerts()
            android.widget.Toast.makeText(this, if (muted) "You'll be told when ${c.username} goes live" else "No more go-live alerts for ${c.username}",
                android.widget.Toast.LENGTH_SHORT).show()
        }
        v.findViewById<TextView>(R.id.btnAbout).apply {
            setVisible(c.socials.isNotEmpty() || !c.bio.isNullOrBlank())
            focusZoom(1.05f)
            setOnClickListener { showAbout() }
        }
        v.findViewById<TextView>(R.id.clipSort).setOnClickListener { chooseClipTime() }
        fun updateFav() {
            val on = Follows.isFollowing(this, c.slug)
            fav.text = if (on) "Following" else "Follow"
            fav.setCompoundDrawablesRelativeWithIntrinsicBounds(if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline, 0, 0, 0)
            updateAlerts()
        }
        fun toggle() = lifecycleScope.launch {
            val result = Follows.toggle(this@ChannelActivity, c.slug, c.username, c.avatar)
            if (result.following) Burst.at(fav)
            updateFav()
            result.accountProblem?.let { android.widget.Toast.makeText(this@ChannelActivity, it, android.widget.Toast.LENGTH_LONG).show() }
        }
        updateFav()
        fav.focusZoom(1.05f)
        // OK on a tab goes down into what it shows.
        tabViews().forEach { t ->
            t.setOnClickListener {
                list.layoutManager?.findViewByPosition(headerAdapter.itemCount)?.requestFocus()
                    ?: v.findViewById<View>(R.id.tabEmpty).takeIf { it.isFocusable && it.visibility == View.VISIBLE }?.requestFocus()
            }
        }
        showTabs()
        fav.setOnClickListener {
            if (Follows.isFollowing(this, c.slug)) {
                OptionSheet.confirm(this, "Unfollow ${c.username}?", "They'll be removed from Following.",
                    "Unfollow", R.drawable.ic_heart_outline) { toggle() }
            } else {
                toggle()
            }
        }
    }
}
