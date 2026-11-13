package com.diriktv.sidekick.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.diriktv.sidekick.SidekickApp
import com.diriktv.sidekick.data.KickApi

/** Builds players and media items the same way for the full-screen player and multi-view. */
object PlayerFactory {
    /** Plain media playback, so the TV and other apps know what kind of sound this is. */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    /**
     * [maxHeight] caps the video quality (multi-view tiles don't need source); 0 = no cap.
     * [leveler] evens out loudness; keep a reference to switch it on and off while playing.
     * [audioFocus]: the player takes audio focus, so it pauses when another app starts playing
     * and turns down for a notification. Off for muted previews and multi-view tiles (which
     * would otherwise pause each other).
     * [preview] starts with a small buffer (see [BufferControl]); switch it to full with [fullBuffer].
     */
    fun create(
        context: Context,
        maxHeight: Int = 0,
        leveler: LoudnessLeveler? = null,
        audioFocus: Boolean = true,
        preview: Boolean = false,
    ): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(KickApi.http)
        val loadControl = BufferControl(preview)
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(listOfNotNull<AudioProcessor>(leveler).toTypedArray())
                    .build()
        }.setEnableDecoderFallback(true) // a decoder that fails to start hands over to the next one on the TV
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(PrefetchPlaylists.SourceFactory(dataSource))
            .setBandwidthMeter(SidekickApp.bandwidthMeter(context))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, audioFocus)
            .setHandleAudioBecomingNoisy(audioFocus)
            .build()
        controls[player] = loadControl
        // Captions start off; the full-screen player turns them on if the viewer wants them.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .apply { if (maxHeight > 0) setMaxVideoSize(Int.MAX_VALUE, maxHeight) }
            .build()
        return player
    }

    private val controls = java.util.WeakHashMap<ExoPlayer, BufferControl>()

    /**
     * A preview player became the full-screen one: it now keeps the healthy buffer, and takes
     * audio focus like any other player.
     */
    fun promote(player: ExoPlayer) {
        controls[player]?.full = true
        player.setAudioAttributes(audioAttributes, true)
        player.setHandleAudioBecomingNoisy(true)
    }

    /**
     * Kick serves everything as HLS except the odd progressive clip. [lowLatency] live streams
     * also read the playlist's prefetch segments (see [PrefetchPlaylists]).
     */
    fun item(
        url: String,
        live: MediaItem.LiveConfiguration? = null,
        metadata: MediaMetadata? = null,
        lowLatency: Boolean = false,
    ): MediaItem =
        MediaItem.Builder().setUri(url)
            .apply { if (!url.substringBefore('?').endsWith(".mp4")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .apply { live?.let { setLiveConfiguration(it) } }
            .apply { metadata?.let { setMediaMetadata(it) } }
            .apply { if (lowLatency && live != null) setTag(PrefetchPlaylists.LOW_LATENCY_TAG) }
            .build()

    /**
     * Kick's segments are 2s. Normally the player stays well back, so a slow segment never
     * stalls it; "Low latency" reads the segments still being made and sits a couple from live.
     */
    fun liveConfig(lowLatency: Boolean): MediaItem.LiveConfiguration =
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(if (lowLatency) LOW_LATENCY_TARGET_MS else 12_000)
            .setMinOffsetMs(if (lowLatency) 2_500 else 6_000)
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(if (lowLatency) 1.05f else 1.03f)
            .build()

    /** How far behind live "Low latency" aims (ExoPlayer backs off by itself after a stall). */
    const val LOW_LATENCY_TARGET_MS = 4_000L

    /**
     * The usual buffer (20-50s, so brief network dips don't stall), except for focus previews,
     * which only keep a few seconds: browsing Home shouldn't download a minute of video per card.
     * A preview that's opened full screen switches over ([full]) without rebuffering.
     */
    private class BufferControl(preview: Boolean) : LoadControl {
        private val normal = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 50_000, 1_500, 3_000)
            .build()

        @Volatile var full = !preview

        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
            if (!full && parameters.bufferedDurationUs >= PREVIEW_BUFFER_US) false
            else normal.shouldContinueLoading(parameters)

        override fun onPrepared(playerId: PlayerId) = normal.onPrepared(playerId)
        override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
            normal.onTracksSelected(parameters, trackGroups, trackSelections)
        override fun onStopped(playerId: PlayerId) = normal.onStopped(playerId)
        override fun onReleased(playerId: PlayerId) = normal.onReleased(playerId)
        override fun getAllocator(playerId: PlayerId): Allocator = normal.getAllocator(playerId)
        override fun getBackBufferDurationUs(playerId: PlayerId) = normal.getBackBufferDurationUs(playerId)
        override fun retainBackBufferFromKeyframe(playerId: PlayerId) = normal.retainBackBufferFromKeyframe(playerId)
        override fun shouldStartPlayback(parameters: LoadControl.Parameters) = normal.shouldStartPlayback(parameters)
        override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
            normal.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

        companion object {
            const val PREVIEW_BUFFER_US = 6_000_000L
        }
    }
}
