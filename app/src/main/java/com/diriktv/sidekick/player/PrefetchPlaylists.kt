package com.diriktv.sidekick.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Low latency for Kick's live streams.
 *
 * Kick's video service (Amazon IVS) lists, after the finished segments of a live playlist, the
 * next one or two as `#EXT-X-PREFETCH:<url>`: segments still being made, which download as they're
 * produced. ExoPlayer doesn't know that tag, so it can only start a segment once it's finished and
 * has to stay a few segments behind. Rewritten as ordinary segments, the player can fetch them
 * early and sit a couple of seconds from live. A prefetch segment keeps its address when it
 * becomes a finished one in the next playlist, so nothing is fetched twice.
 */
internal object PrefetchPlaylists {
    /** Tag on a media item that should play with the prefetch segments (see [PlayerFactory.item]). */
    const val LOW_LATENCY_TAG = "sidekick-low-latency"

    private const val PREFETCH = "#EXT-X-PREFETCH:"

    /** [playlist] with each prefetch line turned into a segment as long as the last finished one. */
    fun rewrite(playlist: String): String {
        if (!playlist.contains(PREFETCH)) return playlist
        val out = StringBuilder(playlist.length + 64)
        var duration = "2.000"
        for (line in playlist.lineSequence()) {
            when {
                line.startsWith("#EXTINF:") -> {
                    duration = line.removePrefix("#EXTINF:").substringBefore(',').ifBlank { duration }
                    out.append(line).append('\n')
                }
                line.startsWith(PREFETCH) -> {
                    out.append("#EXTINF:").append(duration).append(",live\n")
                    out.append(line.removePrefix(PREFETCH).trim()).append('\n')
                }
                else -> out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    /** Parses playlists as usual, after [rewrite]. */
    private class Factory : HlsPlaylistParserFactory {
        private val default = DefaultHlsPlaylistParserFactory()

        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = wrap(default.createPlaylistParser())

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> = wrap(default.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

        private fun wrap(inner: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser { uri: Uri, input: InputStream ->
            val text = input.readBytes().toString(Charsets.UTF_8)
            inner.parse(uri, ByteArrayInputStream(rewrite(text).toByteArray(Charsets.UTF_8)))
        }
    }

    /**
     * Builds media sources the usual way, except items tagged [LOW_LATENCY_TAG], which get an HLS
     * source that reads the prefetch segments.
     */
    class SourceFactory(dataSource: DataSource.Factory) : MediaSource.Factory {
        private val default = DefaultMediaSourceFactory(dataSource)
        private val prefetching = HlsMediaSource.Factory(dataSource).setPlaylistParserFactory(Factory())

        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
            default.setDrmSessionManagerProvider(drmSessionManagerProvider)
            prefetching.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
            default.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            prefetching.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        override fun getSupportedTypes(): IntArray = default.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val local = mediaItem.localConfiguration
            val lowLatency = local?.tag == LOW_LATENCY_TAG && local.mimeType == MimeTypes.APPLICATION_M3U8
            return if (lowLatency) prefetching.createMediaSource(mediaItem) else default.createMediaSource(mediaItem)
        }
    }
}
