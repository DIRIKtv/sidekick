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
        /** Live playback buffering this long with no error is treated as stuck and reloaded. */
        private const val STALL_MS = 15_000L

        /** The player on screen or in picture-in-picture; opening another one replaces it. */
        private var current: WeakReference<PlayerActivity>? = null

        /** Channel playing right now, if any (so alerts don't announce what you're watching). */
        val currentSlug: String? get() = current?.get()?.takeIf { !it.isFinishing }?.slug

        /** Picture size choices: key, label, what it does. */
        val PICTURE_SIZES = listOf(
            Triple("fit", "Fit", "The whole picture, with black bars if it doesn't match the screen"),
            Triple("fill", "Stretch", "Stretched to fill the screen"),
            Triple("zoom", "Zoom", "Fills the screen, cutting off the edges"),
        )

        private const val ACTION_PIP_PLAY_PAUSE = "com.diriktv.sidekick.PIP_PLAY_PAUSE"
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
    /** The pending automatic retry after a playback error (cancelled when switching streams). */
    private val retryRunnable = Runnable { retry() }
    /** Raid set to "go automatically": the pending jump to the raided channel. */
    private var raidJump: Runnable? = null
    /** Times the quality was lowered for this stream because the TV couldn't decode it. */
    private var decoderStepDowns = 0
    /** When live playback started buffering without an error (0 when it isn't). */
    private var stalledSince = 0L
    /** The connection came back while playback had failed: try again straight away. */
    private val reconnected: () -> Unit = {
        if (!isFinishing && errorView.visibility == View.VISIBLE && retryAfterReconnect) {
            retries = 0
            retry()
        }
    }
    /** The error on screen is one that coming back online could fix. */
    private var retryAfterReconnect = false

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
        // A player handed over from a preview already has its tracks: apply the caption choice now.
        applyCaptionPreference()

        if (!readIntent(intent)) return finish()
        com.diriktv.sidekick.data.PhoneChat.attach(this)
        phoneAttached = true
        startSession()
        if (canPip) androidx.core.content.ContextCompat.registerReceiver(this, pipReceiver,
            android.content.IntentFilter(ACTION_PIP_PLAY_PAUSE), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        begin()
    }

    // region media session

    private var session: androidx.media3.session.MediaSession? = null

    /**
     * A media session, so the TV knows what's playing: voice commands ("pause", "rewind 30
     * seconds"), transport keys sent over HDMI-CEC and the system's Now Playing card. Commands go
     * through the same paths as the remote (rewinding a live stream into its recording, for one).
     */
    private fun startSession() {
        session = runCatching {
            androidx.media3.session.MediaSession.Builder(this, SessionPlayer())
                // Unique per player: the one being replaced may not be gone yet.
                .setId("sidekick-player-${System.nanoTime()}")
                .build()
        }.getOrNull()
    }

    private inner class SessionPlayer : androidx.media3.common.ForwardingPlayer(player) {
        override fun play() { if (!player.playWhenReady || player.playbackState == Player.STATE_IDLE) togglePlayPause() }
        override fun pause() { if (player.playWhenReady) togglePlayPause() }
        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()
        override fun stop() = finish()
        override fun seekTo(positionMs: Long) = queueSeek(positionMs - player.currentPosition, 0)
        override fun seekBack() = queueSeek(-seekStep(), 0)
        override fun seekForward() = queueSeek(seekStep(), 0)
        override fun seekToNext() = skip(1)
        override fun seekToNextMediaItem() = skip(1)
        override fun seekToPrevious() = skip(-1)
        override fun seekToPreviousMediaItem() = skip(-1)
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = mode != Mode.VOD
        override fun isCommandAvailable(command: Int) = command in extraCommands || super.isCommandAvailable(command)
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*extraCommands.toIntArray()).build()
    }

    /** Next and previous: another live followed channel, or the next clip in the row. */
    private val extraCommands = listOf(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
    )

    /** What the session (and the Now Playing card) shows for the current stream. */
    private fun metadata(): androidx.media3.common.MediaMetadata {
        val c = channel
        val title = if (mode == Mode.VOD) vodTitle?.takeIf { it.isNotBlank() } else c?.liveTitle?.takeIf { it.isNotBlank() }
        val art = (if (mode == Mode.VOD) intent.getStringExtra(EXTRA_VOD_THUMB) else c?.liveThumbnail) ?: c?.avatar
        return androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title ?: c?.username ?: slug)
            .setArtist(c?.username ?: slug)
            .setArtworkUri(art?.let { android.net.Uri.parse(it) })
            .setIsPlayable(true)
            .build()
    }

    /** The channel loaded (or changed): bring the session's title and picture up to date, without interrupting playback. */
    private fun refreshMetadata() {
        val item = player.currentMediaItem ?: return
        val m = metadata()
        if (item.mediaMetadata.title == m.title && item.mediaMetadata.artist == m.artist) return
        runCatching { player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(m).build()) }
    }

    // endregion

    private var phoneAttached = false

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
        // Anything still scheduled for the previous stream mustn't touch the new one.
        ui.removeCallbacks(retryRunnable)
        ui.removeCallbacks(streamEnded)
        raidJump?.let { ui.removeCallbacks(it) }
        raidJump = null
        stalledSince = 0
        if (decoderStepDowns > 0) {
            // A lower quality forced by the last stream's decoding trouble doesn't carry over.
            decoderStepDowns = 0
            qualityHeight = prefs.preferredQuality
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        }
        endCard.hide()
        upNext?.hide()
        liveRail?.hide()
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
        ui.removeCallbacks(ticker)
        ui.post(ticker)
        com.diriktv.sidekick.data.Network.onReconnected += reconnected
        if (stoppedLive) {
            stoppedLive = false
            startLive() // reconnects chat too
        } else if (mode == Mode.DVR) {
            channel?.let { if (it.isLive) chat.connect(it) }
        }
    }

    /**
     * Out of sight without picture-in-picture (Home, another app): stop everything that runs on
     * its own (the ticker and its channel refresh, chat and its poll checks, the end card's
     * countdown) so nothing changes, or starts playing, in the background. [onStart] picks it up.
     */
    override fun onStop() {
        super.onStop()
        saveVodPosition(updateHomeScreen = true)
        player.pause()
        if (mode == Mode.LIVE) {
            player.stop()
            stoppedLive = true
        }
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(retryRunnable)
        ui.removeCallbacks(streamEnded)
        raidJump?.let { ui.removeCallbacks(it) }
        raidJump = null
        stalledSince = 0
        com.diriktv.sidekick.data.Network.onReconnected -= reconnected
        if (mode != Mode.VOD) chat.disconnect()
        if (endCard.showing) endCard.onKey()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (current?.get() === this) current = null
        ui.removeCallbacksAndMessages(null)
        sleepTimer.cancel()
        chat.close()
        chat.adapter.stopAnimations()
        session?.release()
        session = null
        if (canPip) runCatching { unregisterReceiver(pipReceiver) }
        player.release()
        if (phoneAttached) com.diriktv.sidekick.data.PhoneChat.detach()
    }

    // endregion

    // region picture-in-picture

    internal fun enterPip(): Boolean {
        if (!canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        menu.dismiss()
        controls.hide()
        liveRail?.hide()
        return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    /** Leaving with Home shrinks the stream into a corner when it's playing (Settings → Playback). */
    private val wantsAutoPip get() = prefs.autoPip && !isFinishing && player.isPlaying && errorView.visibility != View.VISIBLE

    /**
     * The corner window's shape (the video's own, within what Android allows), its play/pause
     * button, and on Android 12+ whether Home should shrink into it by itself (smoother than
     * entering from [onUserLeaveHint]).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val v = player.videoSize
        val ratio = if (v.width > 0 && v.height > 0) {
            val r = (v.width * v.pixelWidthHeightRatio / v.height).coerceIn(1 / 2.39f, 2.39f)
            Rational((r * 1000).toInt(), 1000)
        } else Rational(16, 9)
        val playing = player.playWhenReady
        val action = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            if (playing) "Pause" else "Play", if (playing) "Pause" else "Play",
            android.app.PendingIntent.getBroadcast(this, 0, Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(action))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(wantsAutoPip) }
            .build()
    }

    /** Keeps the corner window's shape, button and auto-enter in step with playback. */
    private fun updatePip() {
        if (!canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isFinishing) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** The corner window's play/pause button. */
    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == ACTION_PIP_PLAY_PAUSE) togglePlayPause()
        }
    }

    /** Home while watching shrinks the stream into a corner instead of stopping it. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ does this itself (see pipParams), without the jump.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (wantsAutoPip) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            controls.hide()
            controls.hideOsd()
            liveRail?.hide()
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
        applyPictureSize()
    }

    private fun applyPictureSize() {
        playerView.resizeMode = when (prefs.pictureSize) {
            "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    internal fun pictureSizeLabel() = PICTURE_SIZES.firstOrNull { it.first == prefs.pictureSize }?.second ?: "Fit"

    internal fun setPictureSize(key: String) {
        prefs.pictureSize = key
        applyPictureSize()
        controls.flashOsd("Picture size: ${pictureSizeLabel()}", R.drawable.ic_tv)
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
            // From a muted preview to the real thing: the full buffer, and audio focus.
            PlayerFactory.promote(player)
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
                updatePip()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Another app took the sound (or it came back): say why it paused.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && !playWhenReady && !inPip) {
                    controls.flashOsd("Paused: another app is playing sound", R.drawable.ic_pause, 3000)
                }
                updatePip()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updatePip()

            override fun onTracksChanged(tracks: Tracks) {
                menu.applyQualityPreference()
                applyCaptionPreference()
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

    /**
     * Captions on or off as last chosen in the menu, in the language last picked; until the
     * viewer chooses, they follow the TV's own captions setting.
     */
    private fun applyCaptionPreference() {
        val tracks = menu.textTracks()
        if (tracks.isEmpty()) return
        val cm = getSystemService(CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager
        val want = prefs.captionsOn ?: (cm?.isEnabled == true)
        val params = player.trackSelectionParameters
        val disabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        if (!want) {
            if (!disabled) player.trackSelectionParameters = params.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            return
        }
        val lang = prefs.captionLanguage ?: cm?.locale?.language
        fun matches(f: androidx.media3.common.Format) = lang != null &&
            (f.language.equals(lang, true) || f.label.equals(lang, true) || f.language?.substringBefore('-').equals(lang.substringBefore('-'), true))
        val pick = tracks.firstOrNull { matches(it.format) } ?: tracks.first()
        val chosen = params.overrides[pick.group.mediaTrackGroup]?.trackIndices == listOf(pick.index)
        if (!disabled && chosen) return
        player.trackSelectionParameters = params.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(pick.group.mediaTrackGroup, pick.index))
            .build()
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                val name = channel?.username ?: slug
                val why = if (com.diriktv.sidekick.data.KickErrors.isOffline(e)) "Check your internet connection; it tries again by itself once you're back online."
                else com.diriktv.sidekick.data.KickErrors.describe(e)
                showError("Couldn't load $name", why,
                    R.drawable.ic_info, ErrorAction("Try again", R.drawable.ic_refresh) { startLive() }, goBack())
                retryAfterReconnect = true
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
            refreshMetadata()
            updateModeUi()
            return
        }
        player.setMediaItem(PlayerFactory.item(url, PlayerFactory.liveConfig(prefs.lowLatency), metadata(), lowLatency = prefs.lowLatency))
        setSpeed(1f)
        player.prepare()
        player.play()
        updateModeUi()
    }

    /** Play the recording of the current broadcast from [positionMs] after stream start. */
    private fun playDvr(positionMs: Long, announce: Boolean = true) {
        val target = slug
        lifecycleScope.launch {
            val src = dvrSource ?: runCatching {
                KickApi.videos(target).firstOrNull { it.isLive }?.source
            }.getOrNull()
            // Switched to another stream while looking the recording up.
            if (target != slug || mode == Mode.VOD) return@launch
            if (src == null) {
                controls.flashOsd("Rewind isn't available for this stream", R.drawable.ic_info)
                if (mode == Mode.LIVE) player.play()
                return@launch
            }
            dvrSource = src
            mode = Mode.DVR
            livePausedAt = 0
            player.setMediaItem(PlayerFactory.item(src, metadata = metadata()), positionMs)
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
        player.setMediaItem(PlayerFactory.item(url, metadata = metadata()), resume)
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
        refreshMetadata()
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
        // In the corner window there's no room for a sheet, and jumping channels there would be a
        // surprise: the raid is kept for the end card's Up next instead.
        if (inPip) return
        val from = channel?.username ?: slug
        when (prefs.raidMode) {
            "off" -> return
            "auto" -> {
                controls.flashOsd("$from raided ${t.username}, taking you there", R.drawable.ic_raid)
                raidJump?.let { ui.removeCallbacks(it) }
                raidJump = Runnable { if (!isFinishing && !inPip) Nav.watchLive(this, t.slug) }.also { ui.postDelayed(it, 2_500) }
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
            val result = Follows.toggle(this@PlayerActivity, c.slug, c.username, c.avatar)
            val on = result.following
            if (on) com.diriktv.sidekick.ui.Burst.at(controls.btnFollow)
            controls.updateFollowButton()
            controls.updateTooltip(controls.btnFollow)
            if (result.accountProblem != null) controls.flashOsd(result.accountProblem, R.drawable.ic_info, 5000)
            else controls.flashOsd(if (on) "Following ${c.username}" else "Unfollowed ${c.username}", if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline)
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
                if (sleepTimer.onVideoEnded()) return
                // A clip carries on with the next one from the row it came from.
                val next = if (isClip && !inPip) Nav.clipAfter(clipId) else null
                if (next != null) upNext().show(next) else controls.show()
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
        val code = error.errorCode
        // The TV couldn't decode this quality: step down one before trying again or giving up.
        if (code in 4000..4999 && stepDownQuality()) return
        retries++
        if (retries <= 4) {
            buffering.setVisible(true)
            ui.removeCallbacks(retryRunnable)
            ui.postDelayed(retryRunnable, 1500L * retries)
            return
        }
        buffering.setVisible(false)
        val again = ErrorAction("Try again", R.drawable.ic_refresh) {
            retries = 0
            retry()
        }
        val offline = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        when {
            offline ->
                showError("Lost connection", "The video stopped loading. Check your internet; it picks up again by itself once you're back online.",
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
        // Network trouble of any kind: coming back online retries by itself.
        retryAfterReconnect = offline || code in 2000..2999
    }

    /**
     * After a decoding error: caps the quality one step below what failed and reloads. Returns
     * false when there's nothing lower to try (or it already stepped down a few times).
     */
    private fun stepDownQuality(): Boolean {
        if (decoderStepDowns >= 3) return false
        val heights = menu.videoTracks().map { it.format.height }.filter { it > 0 }.distinct()
        val failing = player.videoFormat?.height?.takeIf { it > 0 }
            ?: qualityHeight.takeIf { it > 0 } ?: heights.maxOrNull() ?: return false
        val lower = heights.filter { it < failing }.maxOrNull() ?: return false
        decoderStepDowns++
        // For this stream only: the saved default quality stays as it was.
        qualityHeight = lower
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(Int.MAX_VALUE, lower)
            .build()
        controls.flashOsd("${failing}p was too much for this TV, switching to ${lower}p", R.drawable.ic_hd, 3500)
        ui.removeCallbacks(retryRunnable)
        ui.post(retryRunnable)
        return true
    }

    private fun retry() {
        errorView.setVisible(false)
        retryAfterReconnect = false
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
        liveRail?.hide()
        // The sleep timer was waiting for this.
        if (ended && sleepTimer.onStreamEnded()) return
        if (inPip) showOfflineNote(ended) else endCard.show(ended, lastRaid)
    }

    /**
     * Chat heard the broadcast end. The picture runs a few seconds behind live, so the end card
     * comes up once those last seconds have played, rather than whenever the player runs dry or
     * the next channel check (up to a minute) notices.
     */
    internal fun onStreamEnded() {
        if (mode != Mode.LIVE || isFinishing || endCard.showing) return
        lastChannelRefresh = System.currentTimeMillis()
        val behind = player.currentLiveOffset.takeIf { it != C.TIME_UNSET && player.isPlaying } ?: 0L
        ui.removeCallbacks(streamEnded)
        ui.postDelayed(streamEnded, behind.coerceIn(0, 15_000))
    }

    private val streamEnded = Runnable {
        if (mode != Mode.LIVE || isFinishing || endCard.showing) return@Runnable
        ui.removeCallbacks(retryRunnable)
        player.stop()
        buffering.setVisible(false)
        errorView.setVisible(false)
        channel = channel?.copy(isLive = false)
        showEnded(ended = true)
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
        liveRail?.hide()
        retryButton.requestFocus()
        updatePip()
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

    private var sleepSheet: OptionSheet? = null

    /** A minute to go: offer more time (if nobody answers, it stops as planned). */
    private fun onSleepWarning() {
        if (inPip) return controls.flashOsd("Sleep timer: stopping in 1 minute", R.drawable.ic_timer, 4000)
        controls.cancelHide()
        sleepSheet = OptionSheet(this).apply {
            show(OptionSheet.Page("Still watching?", eyebrow = "Sleep timer", subtitle = "Playback stops in 1 minute.") {
                listOf(
                    OptionSheet.Item("Keep watching 15 more minutes", icon = R.drawable.ic_timer) {
                        sleepTimer.extend(15)
                        controls.updateMeta()
                        controls.flashOsd("Sleep timer: ${sleepTimer.label()}", R.drawable.ic_timer)
                        it.dismiss()
                    },
                    OptionSheet.Item("Turn the sleep timer off", icon = R.drawable.ic_close) {
                        sleepTimer.cancel()
                        controls.updateMeta()
                        controls.flashOsd("Sleep timer off", R.drawable.ic_timer)
                        it.dismiss()
                    },
                    OptionSheet.Item("Stop in a minute", icon = R.drawable.ic_pause) { it.dismiss() },
                )
            })
        }
    }

    /** Stop playback and let the screen turn off; one press brings it back. */
    private fun onSleepTimer() {
        sleepSheet?.dismiss()
        sleepSheet = null
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
        checkStall(now)
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

    /**
     * Live playback can sit buffering forever without an error (a stuck segment, a playlist that
     * stopped updating). After [STALL_MS] of that, reload at the live edge.
     */
    private fun checkStall(now: Long) {
        val stuck = mode == Mode.LIVE && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING &&
            errorView.visibility != View.VISIBLE && !endCard.showing && liveLoad?.isActive != true
        if (!stuck) {
            stalledSince = 0
            return
        }
        if (stalledSince == 0L) stalledSince = now
        if (now - stalledSince < STALL_MS) return
        stalledSince = 0
        controls.flashOsd("The stream stalled, reconnecting…", R.drawable.ic_refresh, 2500)
        // Reloads the channel too, so a stream that quietly ended shows its end card.
        startLive()
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

    /** ▼ while watching: the live followed channels, to switch to one. Made on first use. */
    private var liveRail: LiveRail? = null
    private fun liveRail() = liveRail ?: LiveRail(this, findViewById(R.id.stage)).also { liveRail = it }

    /** The next clip, when one finishes. Made on first use. */
    private var upNext: ClipUpNext? = null
    private fun upNext() = upNext ?: ClipUpNext(this, findViewById(R.id.stage)).also { upNext = it }

    private var zapJob: Job? = null

    /**
     * CH+/CH− and next/previous: the next (or previous) live channel you follow, in the order
     * Following lists them; on a clip, the next clip from its row.
     */
    private fun skip(step: Int) {
        if (mode == Mode.VOD) {
            val next = if (isClip && step > 0) Nav.clipAfter(clipId) else null
            if (next != null) { upNext?.hide(); Nav.watchClip(this, next) }
            return
        }
        zapJob?.cancel()
        zapJob = lifecycleScope.launch {
            val live = runCatching { Follows.liveNow(this@PlayerActivity) }.getOrNull()
            if (live == null) return@launch controls.flashOsd("Couldn't check who's live", R.drawable.ic_info)
            val i = live.indexOfFirst { it.slug.equals(slug, true) }
            val others = live.filterNot { it.slug.equals(slug, true) }
            if (others.isEmpty()) return@launch controls.flashOsd("Nobody else you follow is live", R.drawable.ic_info, 2500)
            val target = if (i < 0) (if (step > 0) live.first() else live.last()) else live[(i + step).mod(live.size)]
            controls.flashOsd(target.username, if (step > 0) R.drawable.ic_forward else R.drawable.ic_replay)
            Nav.watchLive(this@PlayerActivity, target.slug)
        }
    }

    /** Keys the ▼ rail moves around with, rather than the player's own uses of them. */
    private val railKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    )

    // Overriding Activity.dispatchKeyEvent is standard; lint trips on AndroidX's restricted override of it.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (layoutEditor.showing && layoutEditor.handle(event)) return true
        // The end card is ordinary buttons: let focus move between them, and stop its countdown.
        if (endCard.showing) {
            if (event.action == KeyEvent.ACTION_DOWN) endCard.onKey()
            return super.dispatchKeyEvent(event)
        }
        liveRail?.takeIf { it.showing && event.keyCode in railKeys }?.let { rail ->
            if (rail.onKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        upNext?.takeIf { it.showing }?.let { card ->
            val ok = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (ok) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) card.play()
                return true
            }
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
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (event.repeatCount == 0) skip(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (event.repeatCount == 0) skip(-1)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    // Stop means done watching: leave the player (a past broadcast keeps its place).
                    if (event.repeatCount == 0) finish()
                    return true
                }
            }
            if (!controlsShown && !errorShown) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { queueSeek(-seekStep(), event.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { queueSeek(seekStep(), event.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.repeatCount == 0) controls.show()
                        return true
                    }
                    // ▼ brings up the live channels you follow, to switch to one.
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.repeatCount == 0) liveRail().show()
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
        liveRail?.takeIf { it.showing }?.let { it.hide(); return }
        upNext?.takeIf { it.showing }?.let { it.hide(); controls.show(); return }
        when {
            pendingSeek != null -> commitPendingSeek()
            controls.up -> controls.hide()
            else -> finish()
        }
    }

    // endregion
}
