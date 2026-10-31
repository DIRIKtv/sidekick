package com.diriktv.sidekick.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import com.diriktv.sidekick.SidekickApp
import com.diriktv.sidekick.R
import com.diriktv.sidekick.ui.FocusPreview
import com.diriktv.sidekick.data.ChannelInfo
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.HistoryEntry
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.Prefs
import com.diriktv.sidekick.ui.Fmt
import com.diriktv.sidekick.ui.Nav
import com.diriktv.sidekick.ui.OptionSheet
import com.diriktv.sidekick.ui.setVisible
import com.diriktv.sidekick.ui.fadeIn
import com.diriktv.sidekick.ui.focusZoom
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.tv.TvHome
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Full-screen playback of a live stream (with rewind into the broadcast's recording), a past
 * broadcast or a clip. The controls overlay, chat and the settings menu live in their own classes.
 */
class PlayerActivity : FragmentActivity() {
    companion object {
        const val EXTRA_SLUG = "slug"
        const val EXTRA_VOD_URL = "vod_url"
        const val EXTRA_VOD_UUID = "vod_uuid"
        const val EXTRA_VOD_TITLE = "vod_title"
        const val EXTRA_VOD_THUMB = "vod_thumb"
        const val EXTRA_VOD_DURATION = "vod_duration"
        const val EXTRA_VOD_DATE = "vod_date"
        const val EXTRA_VOD_CATEGORY = "vod_category"
        /** Set for clips, which play like a past broadcast but without resume. */
        const val EXTRA_CLIP_ID = "clip_id"

        private const val SEEK_COMMIT_DELAY_MS = 700L
        /** Closer than this to the live edge counts as "live". */
        internal const val LIVE_EDGE_MS = 20_000L
        /** Live pauses longer than this resume from the recording instead of the (short) live window. */
        private const val LIVE_PAUSE_LIMIT_MS = 25_000L

        /** The player on screen or in picture-in-picture; opening another one replaces it. */
        private var current: WeakReference<PlayerActivity>? = null

        /** Channel playing right now, if any (so alerts don't announce what you're watching). */
        val currentSlug: String? get() = current?.get()?.takeIf { !it.isFinishing }?.slug
    }

    /** LIVE = live playlist; DVR = the in-progress recording of a live stream; VOD = a past broadcast or clip. */
    internal enum class Mode { LIVE, DVR, VOD }

    internal lateinit var prefs: Prefs
    internal lateinit var player: ExoPlayer
    internal val ui = Handler(Looper.getMainLooper())
    internal lateinit var controls: PlayerControls
    internal lateinit var chat: ChatController
    internal lateinit var menu: PlayerMenu
    internal lateinit var leveler: LoudnessLeveler
    internal val sleepTimer = SleepTimer(onWarn = { onSleepWarning() }, onExpire = { onSleepTimer() })

    private lateinit var root: View
    private lateinit var playerView: PlayerView
    internal lateinit var captions: Captions
    private lateinit var buffering: View
    internal lateinit var stats: TextView
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var errorTitle: TextView
    private lateinit var errorIcon: android.widget.ImageView
    private lateinit var retryButton: TextView
    private lateinit var errorSecondary: TextView

    internal var slug = ""
    internal var mode = Mode.LIVE
    internal var channel: ChannelInfo? = null
    /** Where the channel being watched last sent its viewers, for the end card's Up next. */
    private var lastRaid: com.diriktv.sidekick.data.RaidTarget? = null
    private val endCard by lazy { EndCard(this) }
    internal var vodUrl: String? = null
        private set
    private var vodUuid: String? = null
    internal var vodTitle: String? = null
    private var clipId: String? = null
    private var dvrSource: String? = null
    private var streamStartMs = 0L

    internal val isClip get() = clipId != null

    internal var qualityHeight = 0
    internal var speed = 1f
        private set
    internal var pendingSeek: Long? = null
    private var livePausedAt = 0L
    private var livePausedPos = 0L
    private var retries = 0
    private var droppedFrames = 0
    private var stoppedLive = false
    private var lastChannelRefresh = 0L
    private var lastPositionSave = 0L

    internal val canPip by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    internal var inPip = false
        private set

    private val commitSeekRunnable = Runnable { commitPendingSeek() }
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 500)
        }
    }

    // region lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.diriktv.sidekick.ui.AppFonts.install(this)
        current?.get()?.takeIf { it !== this }?.finish()
        current = WeakReference(this)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        qualityHeight = prefs.preferredQuality

        onBackPressedDispatcher.addCallback(this) { handleBack() }
        bindViews()
        initPlayer()
        menu = PlayerMenu(this)
        controls = PlayerControls(this)
        chat = ChatController(this)

        if (!readIntent(intent)) return finish()
        begin()
        ui.post(ticker)
    }

    /**
     * The player has its own task (single instance), so every "watch" comes here while it exists.
     * The same stream (say, picked again while it plays in picture-in-picture) just carries on
     * full screen without reloading; a different one switches in place.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveVodPosition()
        val previous = slug to vodUrl
        FocusPreview.take(null)
        adopted = false
        if (!readIntent(intent)) return
        if (previous == (slug to vodUrl) && (player.playbackState != Player.STATE_IDLE || liveLoad?.isActive == true)) return
        setIntent(intent)
        endCard.hide()
        captions.reset()
        lastRaid = null
        chat.reset()
        channel = null
        dvrSource = null
        streamStartMs = 0
        pendingSeek = null
        retries = 0
        stoppedLive = false
        menu.dismiss()
        controls.hide()
        errorView.setVisible(false)
        player.stop()
        begin()
    }

    /** Reads what to play. Returns false if there's nothing to play. */
    private fun readIntent(intent: Intent): Boolean {
        slug = intent.getStringExtra(EXTRA_SLUG) ?: return false
        vodUrl = intent.getStringExtra(EXTRA_VOD_URL)
        vodUuid = intent.getStringExtra(EXTRA_VOD_UUID)
        vodTitle = intent.getStringExtra(EXTRA_VOD_TITLE)
        clipId = intent.getStringExtra(EXTRA_CLIP_ID)
        return slug.isNotEmpty()
    }

    private fun begin() {
        mode = if (vodUrl != null) Mode.VOD else Mode.LIVE
        chat.wanted = prefs.chatEnabled
        chat.layout = prefs.chatLayout(slug)
        controls.updateMode()
        chat.applyLayout()
        controls.bindHeader()
        if (mode == Mode.VOD) {
            recordVodHistory()
            playVod()
            loadChannel(connectChat = false)
        } else {
            startLive()
        }
    }

    override fun onStart() {
        super.onStart()
        if (stoppedLive) {
            stoppedLive = false
            startLive()
        }
    }

    override fun onStop() {
        super.onStop()
        saveVodPosition(updateHomeScreen = true)
        player.pause()
        if (mode == Mode.LIVE) {
            player.stop()
            stoppedLive = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (current?.get() === this) current = null
        ui.removeCallbacksAndMessages(null)
        sleepTimer.cancel()
        chat.close()
        player.release()
    }

    // endregion

    // region picture-in-picture

    internal fun enterPip(): Boolean {
        if (!canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        menu.dismiss()
        controls.hide()
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        return runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
    }

    /** Home while watching shrinks the stream into a corner instead of stopping it. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.autoPip && !isFinishing && player.isPlaying && errorView.visibility != View.VISIBLE) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            controls.hide()
            controls.hideOsd()
            stats.setVisible(false)
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // The corner window was closed rather than expanded.
            finish()
            return
        }
        chat.applyLayout()
    }

    // endregion

    // region setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        playerView = findViewById(R.id.playerView)
        // Captions are drawn by Captions.kt, not the player's own subtitle view.
        playerView.subtitleView?.visibility = View.GONE
        captions = Captions(this, findViewById(R.id.captions))
        buffering = findViewById(R.id.buffering)
        stats = findViewById(R.id.stats)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        errorTitle = findViewById(R.id.errorTitle)
        errorIcon = findViewById(R.id.errorIcon)
        retryButton = findViewById(R.id.retryButton)
        errorSecondary = findViewById(R.id.errorSecondary)
        retryButton.focusZoom(1.05f)
        errorSecondary.focusZoom(1.05f)
    }

    /** True while the player is one handed over from a Home preview, already playing this stream. */
    private var adopted = false

    private fun initPlayer() {
        // OK on a live preview passes its player over, still playing: no reload, just unmute.
        val handoff = FocusPreview.take(intent.getStringExtra(EXTRA_SLUG).takeIf { intent.getStringExtra(EXTRA_VOD_URL) == null })
        if (handoff != null) {
            adopted = true
            leveler = handoff.leveler.also { it.enabled = prefs.loudnessEq }
            player = handoff.player
            player.volume = 1f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).build()
        } else {
            leveler = LoudnessLeveler(prefs.loudnessEq)
            player = PlayerFactory.create(this, leveler = leveler)
        }
        playerView.player = player
        player.addListener(captions)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering.setVisible(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    retries = 0
                    errorView.setVisible(false)
                }
                if (state == Player.STATE_ENDED) onEnded()
                controls.updatePlayButton()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controls.updatePlayButton()
                if (controls.up) controls.scheduleHide()
            }

            override fun onTracksChanged(tracks: Tracks) {
                menu.applyQualityPreference()
                controls.updateCaptionButton()
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFrames += count
            }
        })
    }

    // endregion

    // region loading

    /** The channel lookup that starts a live stream, while it's running. */
    private var liveLoad: Job? = null

    private fun startLive() {
        errorView.setVisible(false)
        buffering.setVisible(true)
        val target = slug
        liveLoad?.cancel()
        liveLoad = lifecycleScope.launch {
            val c = try {
                // A card that was focused a moment ago already fetched this; use it if it's fresh.
                KickApi.channel(slug, maxAgeMs = 10_000)
            } catch (e: Exception) {
                buffering.setVisible(false)
                showError("Couldn't reach Kick", "Sidekick couldn't load ${channel?.username ?: slug}. Check your internet connection, then try again.",
                    R.drawable.ic_info, ErrorAction("Try again", R.drawable.ic_refresh) { startLive() }, goBack())
                return@launch
            }
            if (target != slug) return@launch
            channel = c
            lastChannelRefresh = System.currentTimeMillis()
            onChannel(c)
            if (!c.isLive || c.playbackUrl == null) {
                buffering.setVisible(false)
                player.stop()
                showEnded(ended = false)
                return@launch
            }
            streamStartMs = Fmt.parseUtc(c.liveStartTime) ?: 0L
            playLive(c.playbackUrl)
            prefs.recordHistory(
                HistoryEntry(HistoryEntry.LIVE, c.slug, c.username, c.avatar, c.liveTitle.orEmpty(), null, category = c.liveCategory)
            )
            chat.connect(c)
        }
    }

    private fun playLive(url: String) {
        mode = Mode.LIVE
        livePausedAt = 0
        if (adopted) {
            // Already playing (from the Home preview): carry on.
            adopted = false
            buffering.setVisible(player.playbackState == Player.STATE_BUFFERING)
            menu.applyQualityPreference()
            setSpeed(1f)
            player.play()
            updateModeUi()
            return
        }
        player.setMediaItem(PlayerFactory.item(url, PlayerFactory.liveConfig(prefs.lowLatency)))
        setSpeed(1f)
        player.prepare()
        player.play()
        updateModeUi()
    }

    /** Play the recording of the current broadcast from [positionMs] after stream start. */
    private fun playDvr(positionMs: Long, announce: Boolean = true) {
        lifecycleScope.launch {
            val src = dvrSource ?: runCatching {
                KickApi.videos(slug).firstOrNull { it.isLive }?.source
            }.getOrNull()
            if (src == null) {
                controls.flashOsd("Rewind isn't available for this stream", R.drawable.ic_info)
                if (mode == Mode.LIVE) player.play()
                return@launch
            }
            dvrSource = src
            mode = Mode.DVR
            livePausedAt = 0
            player.setMediaItem(PlayerFactory.item(src), positionMs)
            player.prepare()
            player.play()
            updateModeUi()
            if (announce) controls.flashOsd("Watching ${Fmt.time(liveDurationMs() - positionMs)} behind live", R.drawable.ic_replay)
        }
    }

    internal fun goLive() {
        if (mode == Mode.VOD) return
        if (mode == Mode.LIVE) {
            player.seekToDefaultPosition()
            player.play()
            controls.flashOsd("Live", R.drawable.ic_skip_live)
            return
        }
        controls.flashOsd("Back to live", R.drawable.ic_skip_live)
        startLive()
    }

    private fun playVod() {
        val url = vodUrl ?: return
        val resume = vodUuid?.let { prefs.vodPosition(it) } ?: 0L
        player.setMediaItem(PlayerFactory.item(url), resume)
        player.prepare()
        updateModeUi()
        // Part-way through a past broadcast: ask whether to pick up there or start over.
        if (resume > 60_000 && !isClip && prefs.askToResume) {
            controls.cancelHide()
            OptionSheet(this).apply {
                onDismiss = { player.play() }
                show(OptionSheet.Page("Pick up where you left off?", eyebrow = vodTitle ?: "Past broadcast",
                    subtitle = "You stopped at ${Fmt.time(resume)}.") {
                    listOf(
                        OptionSheet.Item("Resume from ${Fmt.time(resume)}", icon = R.drawable.ic_play) { it.dismiss() },
                        OptionSheet.Item("Start over", icon = R.drawable.ic_replay) { player.seekTo(0); it.dismiss() },
                    )
                })
            }
            return
        }
        player.play()
        if (resume > 10_000) controls.flashOsd("Resuming from ${Fmt.time(resume)}", R.drawable.ic_play)
    }

    private fun loadChannel(connectChat: Boolean) {
        val target = slug
        lifecycleScope.launch {
            val c = runCatching { KickApi.channel(slug) }.getOrNull() ?: return@launch
            if (target != slug) return@launch
            channel = c
            lastChannelRefresh = System.currentTimeMillis()
            if (c.isLive && streamStartMs == 0L) streamStartMs = Fmt.parseUtc(c.liveStartTime) ?: 0L
            onChannel(c)
            if (mode == Mode.VOD) {
                recordVodHistory()
                if (!isClip) Fmt.parseUtc(intent.getStringExtra(EXTRA_VOD_DATE))?.let {
                    chat.startReplay(c, it)
                    updateModeUi()
                }
            }
            if (connectChat && c.isLive) chat.connect(c)
            if (!c.isLive && mode == Mode.LIVE && !player.isPlaying) showEnded(ended = true)
        }
    }

    private fun onChannel(c: ChannelInfo) {
        chat.setSubscriberBadges(c)
        controls.bindHeader()
    }

    /** VODs and clips go in the watch history (and VODs on the TV's Continue watching row). */
    private fun recordVodHistory() {
        val url = vodUrl ?: return
        val c = channel
        prefs.recordHistory(
            HistoryEntry(
                kind = if (isClip) HistoryEntry.CLIP else HistoryEntry.VOD,
                slug = slug,
                username = c?.username ?: slug,
                avatar = c?.avatar,
                title = vodTitle.orEmpty(),
                thumbnail = intent.getStringExtra(EXTRA_VOD_THUMB),
                videoId = vodUuid ?: clipId,
                source = url,
                durationMs = intent.getLongExtra(EXTRA_VOD_DURATION, 0L),
                createdAt = intent.getStringExtra(EXTRA_VOD_DATE),
                category = intent.getStringExtra(EXTRA_VOD_CATEGORY),
            )
        )
    }

    /** The channel being watched raided [t]: follow it there, ask, or ignore it (Settings → Playback). */
    internal fun onRaid(t: com.diriktv.sidekick.data.RaidTarget) {
        if (mode == Mode.VOD || t.slug.equals(slug, true) || isFinishing) return
        lastRaid = t
        val from = channel?.username ?: slug
        when (prefs.raidMode) {
            "off" -> return
            "auto" -> {
                controls.flashOsd("$from raided ${t.username}, taking you there", R.drawable.ic_raid)
                ui.postDelayed({ if (!isFinishing) Nav.watchLive(this, t.slug) }, 2_500)
            }
            else -> {
                controls.cancelHide()
                val viewers = if (t.viewers > 0) " with ${Fmt.count(t.viewers)} viewers" else ""
                val sheet = OptionSheet(this)
                sheet.show(OptionSheet.Page("Raid!", eyebrow = from,
                    subtitle = "$from is sending their viewers to ${t.username}$viewers.") {
                    listOf(
                        OptionSheet.Item("Go to ${t.username}", icon = R.drawable.ic_raid) { it.dismiss(); Nav.watchLive(this, t.slug) },
                        OptionSheet.Item("Stay here", icon = R.drawable.ic_close) { it.dismiss() },
                    )
                })
            }
        }
    }

    internal fun toggleFollow() {
        val c = channel ?: return
        if (Follows.isFollowing(this, c.slug)) {
            controls.cancelHide()
            OptionSheet.confirm(this, "Unfollow ${c.username}?", "They'll be removed from Following.",
                "Unfollow", R.drawable.ic_heart_outline) { setFollow(c) }
        } else {
            setFollow(c)
        }
    }

    private fun setFollow(c: ChannelInfo) {
        lifecycleScope.launch {
            val on = Follows.toggle(this@PlayerActivity, c.slug, c.username, c.avatar)
            if (on) com.diriktv.sidekick.ui.Burst.at(controls.btnFollow)
            controls.updateFollowButton()
            controls.updateTooltip(controls.btnFollow)
            controls.flashOsd(if (on) "Following ${c.username}" else "Unfollowed ${c.username}", if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        }
    }

    // endregion

    // region playback control

    internal fun togglePlayPause() {
        if (player.playbackState == Player.STATE_IDLE) {
            retry()
            return
        }
        if (player.playWhenReady) {
            player.pause()
            if (mode == Mode.LIVE) {
                livePausedAt = System.currentTimeMillis()
                livePausedPos = timelinePos()
            }
            if (!controls.up) controls.flashOsd("Paused", R.drawable.ic_pause)
        } else {
            if (player.playbackState == Player.STATE_ENDED && mode == Mode.VOD) player.seekTo(0)
            val pausedFor = if (livePausedAt > 0) System.currentTimeMillis() - livePausedAt else 0
            if (mode == Mode.LIVE && pausedFor > LIVE_PAUSE_LIMIT_MS && streamStartMs > 0) {
                // The live window is short; continue from the recording instead of skipping ahead.
                playDvr(livePausedPos, announce = true)
            } else {
                player.play()
            }
            livePausedAt = 0
            if (!controls.up) controls.flashOsd("Play", R.drawable.ic_play)
        }
        controls.updatePlayButton()
        controls.refreshTooltip(controls.btnPlay)
    }

    internal fun setSpeed(s: Float) {
        speed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    internal fun seekStep() = prefs.seekStepSeconds * 1000L

    private fun liveDurationMs() = if (streamStartMs > 0) System.currentTimeMillis() - streamStartMs else 0L

    /** Current position on the visible timeline (for live: time since the stream started). */
    internal fun timelinePos(): Long = when (mode) {
        Mode.VOD, Mode.DVR -> player.currentPosition
        Mode.LIVE -> {
            val off = player.currentLiveOffset
            if (streamStartMs > 0 && off != C.TIME_UNSET) (liveDurationMs() - off).coerceAtLeast(0) else player.currentPosition
        }
    }

    internal fun timelineMax(): Long = when (mode) {
        Mode.VOD -> player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        Mode.LIVE, Mode.DVR -> if (streamStartMs > 0) liveDurationMs() else player.duration.coerceAtLeast(0L)
    }

    internal fun isAtLive() = mode == Mode.LIVE && timelineMax() - (pendingSeek ?: timelinePos()) < LIVE_EDGE_MS

    /** Where the current run of seek presses started, for the ripple's running total. */
    private var seekFrom = 0L
    private val seekRipple by lazy {
        SeekRipple(this).also {
            findViewById<android.widget.FrameLayout>(R.id.stage).addView(it,
                android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    internal fun queueSeek(delta: Long, repeatCount: Int) {
        if (player.playbackState == Player.STATE_IDLE && mode != Mode.VOD) return
        val accel = 1 + repeatCount / 6
        if (pendingSeek == null) seekFrom = timelinePos()
        // On a past broadcast, scrubbing brings up the bar, with a frame of where you'd land.
        if (mode == Mode.VOD && !controls.up) controls.showForSeek()
        val base = pendingSeek ?: timelinePos()
        val max = timelineMax()
        val target = (base + delta * accel).coerceIn(0L, max.coerceAtLeast(0L))
        pendingSeek = target
        seekRipple.pulse(if (delta < 0) -1 else 1, target - seekFrom, atLive = mode != Mode.VOD && delta > 0 && max - target < LIVE_EDGE_MS)
        val icon = if (delta < 0) R.drawable.ic_replay else R.drawable.ic_forward
        val label = if (mode == Mode.VOD) {
            "${Fmt.time(target)} / ${Fmt.time(max)}"
        } else {
            val behind = max - target
            if (behind < LIVE_EDGE_MS) "Live" else "${Fmt.time(target)}  •  −${Fmt.time(behind)}"
        }
        controls.flashOsd(label, icon, 1500)
        controls.updateProgress()
        ui.removeCallbacks(commitSeekRunnable)
        ui.postDelayed(commitSeekRunnable, SEEK_COMMIT_DELAY_MS)
    }

    private fun commitPendingSeek() {
        val target = pendingSeek ?: return
        pendingSeek = null
        ui.removeCallbacks(commitSeekRunnable)
        when (mode) {
            Mode.VOD -> player.seekTo(target)
            Mode.LIVE -> {
                if (streamStartMs <= 0) {
                    player.seekTo((player.currentPosition + (target - timelinePos())).coerceAtLeast(0))
                    return
                }
                val max = timelineMax()
                if (max - target < 3_000) {
                    player.seekToDefaultPosition()
                    return
                }
                val windowStart = timelinePos() - player.currentPosition
                if (target >= windowStart + 2_000) {
                    player.seekTo(target - windowStart)
                } else {
                    playDvr(target)
                }
            }
            Mode.DVR -> {
                val max = timelineMax()
                val recorded = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                when {
                    max - target < LIVE_EDGE_MS -> goLive()
                    target <= recorded - 5_000 -> player.seekTo(target)
                    else -> playDvr(target, announce = false) // reload to pick up newer segments
                }
            }
        }
    }

    private fun onEnded() {
        when (mode) {
            Mode.DVR -> {
                // Caught up with the end of the recording snapshot.
                val pos = player.currentPosition
                if (liveDurationMs() - pos < 60_000) goLive() else playDvr(pos, announce = false)
            }
            Mode.VOD -> {
                vodUuid?.let { prefs.saveVodPosition(it, 0) }
                if (!sleepTimer.onVideoEnded()) controls.show()
            }
            Mode.LIVE -> loadChannel(connectChat = false)
        }
    }

    private fun handleError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        retries++
        if (retries <= 4) {
            buffering.setVisible(true)
            ui.postDelayed({ retry() }, 1500L * retries)
            return
        }
        buffering.setVisible(false)
        val again = ErrorAction("Try again", R.drawable.ic_refresh) {
            retries = 0
            retry()
        }
        val code = error.errorCode
        when {
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                showError("Lost connection", "The video stopped loading. Check your internet, then try again.",
                    R.drawable.ic_refresh, again, goBack())
            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                showError("This video isn't available", "Kick wouldn't send it. It may have been removed, or the stream may have just ended.",
                    R.drawable.ic_info, again, openChannel())
            code in 4000..4999 || code in 3000..3999 ->
                // Decoding (4xxx) or reading the format (3xxx): a lighter quality often plays.
                showError("Your TV couldn't play this", "Try a lower quality; it's easier on the TV.", R.drawable.ic_tv, again,
                    ErrorAction("Lower quality", R.drawable.ic_hd) { errorView.setVisible(false); menu.show(menu.qualityPage()) })
            else -> showError("Something went wrong", "Playback stopped unexpectedly. Trying again usually fixes it.",
                R.drawable.ic_info, again, openChannel())
        }
    }

    private fun retry() {
        errorView.setVisible(false)
        when (mode) {
            Mode.LIVE -> startLive()
            Mode.DVR -> playDvr(player.currentPosition, announce = false)
            Mode.VOD -> { player.prepare(); player.play() }
        }
    }

    /**
     * The live stream isn't on: [ended] while watching, or offline when opened. Full screen it shows
     * what's up next; in picture-in-picture there's no room, so just the note.
     */
    private fun showEnded(ended: Boolean) {
        controls.hide()
        if (inPip) showOfflineNote(ended) else endCard.show(ended, lastRaid)
    }

    /** The small note left behind when the end card is dismissed (or there's no room for it). */
    internal fun showOfflineNote(ended: Boolean = true) {
        val name = channel?.username ?: slug
        showError(if (ended) "Stream ended" else "$name is offline", "Catch up on their past broadcasts and clips on their channel.",
            R.drawable.ic_broadcast, openChannel(), goBack())
    }

    /** How long the stream that just ended ran, e.g. "5h 12m". */
    internal fun streamedFor(): String? {
        if (streamStartMs <= 0) return null
        val mins = ((System.currentTimeMillis() - streamStartMs) / 60_000).coerceAtLeast(1)
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
    }

    private class ErrorAction(val label: String, val icon: Int, val run: () -> Unit)

    private fun openChannel() = ErrorAction("Open channel", R.drawable.ic_account) {
        Nav.channel(this, slug)
        finish()
    }

    private fun goBack() = ErrorAction("Go back", R.drawable.ic_close) { finish() }

    /** The error card: an icon, what happened in plain words, and up to two things to do about it. */
    private fun showError(title: String, message: String, icon: Int, primary: ErrorAction, secondary: ErrorAction? = null) {
        controls.hide()
        errorIcon.setImageResource(icon)
        errorTitle.text = title
        errorText.text = message
        retryButton.text = primary.label
        retryButton.setCompoundDrawablesRelativeWithIntrinsicBounds(primary.icon, 0, 0, 0)
        retryButton.setOnClickListener { primary.run() }
        errorSecondary.setVisible(secondary != null)
        secondary?.let { a ->
            errorSecondary.text = a.label
            errorSecondary.setCompoundDrawablesRelativeWithIntrinsicBounds(a.icon, 0, 0, 0)
            errorSecondary.setOnClickListener { a.run() }
        }
        if (errorView.visibility != View.VISIBLE) errorView.fadeIn(220, fromY = dp(10).toFloat())
        errorView.setVisible(true)
        retryButton.requestFocus()
    }

    private fun saveVodPosition(updateHomeScreen: Boolean = false) {
        val uuid = vodUuid ?: return
        if (mode != Mode.VOD) return
        val dur = player.duration
        val pos = player.currentPosition
        val finished = dur > 0 && dur != C.TIME_UNSET && pos > dur - 60_000
        if (finished) prefs.saveVodPosition(uuid, 0)
        else if (pos > 5_000) prefs.saveVodPosition(uuid, pos)
        if (updateHomeScreen && dur > 0 && dur != C.TIME_UNSET) {
            prefs.history.firstOrNull { it.videoId == uuid }?.let { TvHome.updateWatchNext(this, it, if (finished) 0 else pos, dur) }
        }
    }

    // endregion

    // region sleep timer

    private fun onSleepWarning() {
        controls.flashOsd("Sleep timer: stopping in 1 minute", R.drawable.ic_timer, 4000)
    }

    /** Stop playback and let the screen turn off; one press brings it back. */
    private fun onSleepTimer() {
        saveVodPosition()
        player.pause()
        if (mode == Mode.LIVE) player.stop()
        menu.dismiss()
        root.keepScreenOn = false
        showError("Still watching?", "The sleep timer stopped playback.", R.drawable.ic_timer,
            ErrorAction("Keep watching", R.drawable.ic_play) {
                root.keepScreenOn = true
                errorView.setVisible(false)
                if (mode == Mode.LIVE) startLive() else player.play()
            }, goBack())
    }

    // endregion

    private fun updateModeUi() {
        if (mode == Mode.LIVE && speed != 1f) setSpeed(1f)
        controls.updateMode()
        chat.applyLayout()
    }

    private fun tick() {
        controls.tick()
        if (stats.visibility == View.VISIBLE) updateStats()
        val now = System.currentTimeMillis()
        if (mode != Mode.VOD && now - lastChannelRefresh > 60_000 && !isFinishing) {
            lastChannelRefresh = now
            loadChannel(connectChat = true)
        }
        if (mode == Mode.VOD) chat.tickReplay(player.currentPosition)
        if (mode == Mode.VOD && now - lastPositionSave > 10_000) {
            lastPositionSave = now
            saveVodPosition()
        }
    }

    private fun updateStats() {
        val f = player.videoFormat
        val bw = SidekickApp.bandwidthMeter(this).bitrateEstimate
        val lines = mutableListOf(
            "Mode       ${mode.name}",
            "Video      ${f?.let { "${it.width}x${it.height} @${it.frameRate.toInt()}fps" } ?: "—"}",
            "Codec      ${f?.codecs ?: "—"}",
            "Bitrate    ${f?.bitrate?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f Mbps", it / 1e6) } ?: "—"}",
            "Bandwidth  ${String.format(Locale.US, "%.1f Mbps", bw / 1e6)}",
            "Buffer     ${String.format(Locale.US, "%.1f s", player.totalBufferedDuration / 1000f)}",
            "Dropped    $droppedFrames frames",
            "Speed      ${player.playbackParameters.speed}x",
            "Leveling   ${if (leveler.enabled) String.format(Locale.US, "%+.1f dB", leveler.currentGainDb) else "off"}",
        )
        if (mode == Mode.LIVE && player.currentLiveOffset != C.TIME_UNSET) {
            lines.add(5, "Latency    ${String.format(Locale.US, "%.1f s", player.currentLiveOffset / 1000f)}")
        }
        stats.text = lines.joinToString("\n")
    }

    // region keys

    internal val layoutEditor by lazy { ChatLayoutEditor(this) }

    // Overriding Activity.dispatchKeyEvent is standard; lint trips on AndroidX's restricted override of it.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (layoutEditor.showing && layoutEditor.handle(event)) return true
        // The end card is ordinary buttons: let focus move between them, and stop its countdown.
        if (endCard.showing) {
            if (event.action == KeyEvent.ACTION_DOWN) endCard.onKey()
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlsShown = controls.up
            val errorShown = errorView.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (event.repeatCount == 0) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (!player.playWhenReady) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (player.playWhenReady) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    queueSeek(-seekStep() * 3, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    queueSeek(seekStep() * 3, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.repeatCount == 0) menu.show()
                    return true
                }
                KeyEvent.KEYCODE_CAPTIONS -> {
                    if (menu.textTracks().isNotEmpty()) menu.show(menu.captionsPage())
                    return true
                }
            }
            if (!controlsShown && !errorShown) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { queueSeek(-seekStep(), event.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { queueSeek(seekStep(), event.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.repeatCount == 0) controls.show()
                        return true
                    }
                }
            }
            if (controlsShown) controls.scheduleHide()
        } else if (event.action == KeyEvent.ACTION_UP && !controls.up &&
            errorView.visibility != View.VISIBLE && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            )
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        // Newer Android sends Back here rather than through the key handler.
        if (layoutEditor.showing) {
            layoutEditor.saveForChannel()
            return
        }
        if (endCard.showing) {
            endCard.stay()
            return
        }
        when {
            pendingSeek != null -> commitPendingSeek()
            controls.up -> controls.hide()
            else -> finish()
        }
    }

    // endregion
}
