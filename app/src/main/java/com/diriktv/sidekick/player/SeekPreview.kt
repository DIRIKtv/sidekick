package com.diriktv.sidekick.player

import android.graphics.Outline
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.diriktv.sidekick.ui.dp

/**
 * A frame of where you'd land, shown in the seek bubble while scrubbing a past broadcast. Kick has
 * no preview thumbnails, so a second player (muted, at the lowest quality) jumps to each spot and
 * shows the nearest keyframe. It's built on first use and let go as soon as scrubbing stops.
 */
internal class SeekPreview(private val act: PlayerActivity, private val frame: FrameLayout) {
    private var player: ExoPlayer? = null
    private var url: String? = null
    private var surface: TextureView? = null
    private var target = -1L
    private val seek = Runnable { player?.seekTo(target) }

    init {
        frame.clipToOutline = true
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, act.dp(14).toFloat())
        }
        frame.setBackgroundColor(0xFF000000.toInt())
    }

    /** Scrubbing [source] at [positionMs]: show (or move) the frame. */
    fun at(source: String, positionMs: Long) {
        val p = player ?: create()
        if (url != source) {
            url = source
            p.setMediaItem(PlayerFactory.item(source))
            p.prepare()
        }
        frame.visibility = View.VISIBLE
        target = positionMs
        // Holding an arrow moves the target many times a second; only chase where it settles.
        act.ui.removeCallbacks(seek)
        act.ui.postDelayed(seek, 90)
    }

    private fun create(): ExoPlayer {
        val p = PlayerFactory.create(act.applicationContext)
        p.volume = 0f
        p.playWhenReady = false
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setForceLowestBitrate(true)
            .build()
        val tv = TextureView(act)
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.setVideoTextureView(tv)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = release()
        })
        surface = tv
        player = p
        return p
    }

    /** The seek landed: the video itself shows the spot now, so the frame steps aside (the player stays for the next scrub). */
    fun hideFrame() {
        act.ui.removeCallbacks(seek)
        frame.visibility = View.GONE
    }

    /** Scrubbing stopped: free the player (its decoder is better spent on the stream). */
    fun release() {
        act.ui.removeCallbacks(seek)
        frame.visibility = View.GONE
        player?.release()
        player = null
        url = null
        surface?.let { frame.removeView(it) }
        surface = null
    }
}
