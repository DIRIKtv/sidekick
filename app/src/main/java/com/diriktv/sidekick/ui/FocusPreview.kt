package com.diriktv.sidekick.ui

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.Prefs
import com.diriktv.sidekick.player.LoudnessLeveler
import com.diriktv.sidekick.player.PlayerFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Live cards play a muted preview in their thumbnail once they've held focus for the delay set in
 * Settings. One small player is shared by every card; it stops as soon as focus moves on or the
 * screen goes away. Pressing OK on a preview that's playing hands that player to the full-screen
 * player ([handOff]), so the stream carries straight on instead of starting from scratch.
 */
// The card and its surface are only held while the preview plays, and are let go when focus moves
// or the screen pauses (stop()), so no screen outlives its visit through them.
@android.annotation.SuppressLint("StaticFieldLeak")
object FocusPreview {
    /** A preview player passed to the full-screen player, with the leveler built into it. */
    class Handoff(val player: ExoPlayer, val leveler: LoudnessLeveler, val slug: String)

    private var leveler: LoudnessLeveler? = null
    private var playingSlug: String? = null
    private var onPlaying: (() -> Unit)? = null
    private var handoff: Handoff? = null

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var card: View? = null
    private var surface: TextureView? = null
    private var load: Job? = null
    private var pending: Runnable? = null

    private val stopOnPause = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) = stop()
    }

    /**
     * A card gained (or lost) focus; [slug] is the live channel it shows. [maxHeight] caps the
     * preview's picture (the Home hero is far bigger than a card and asks for more).
     */
    fun onFocus(cardView: View, slug: String, focused: Boolean, maxHeight: Int = 480, playing: (() -> Unit)? = null) {
        if (!focused) {
            if (card === cardView || pendingFor === cardView) stop()
            return
        }
        stop()
        warm(cardView, slug)
        val delay = Prefs(cardView.context).previewDelay
        if (delay <= 0) return
        pendingFor = cardView
        val r = Runnable { start(cardView, slug, maxHeight, playing) }
        pending = r
        main.postDelayed(r, delay * 1000L)
    }

    private var pendingFor: View? = null
    private var warmJob: Job? = null

    /**
     * Resting on a live card for a moment gets the stream ready: the channel's details are fetched
     * (the player reuses them) and a connection to the video server is opened, so pressing OK
     * starts playback sooner.
     */
    private fun warm(cardView: View, slug: String) {
        warmJob?.cancel()
        val act = cardView.context as? FragmentActivity ?: return
        warmJob = act.lifecycleScope.launch {
            kotlinx.coroutines.delay(350)
            if (!cardView.isFocused) return@launch
            val url = runCatching { KickApi.channel(slug).takeIf { it.isLive }?.playbackUrl }.getOrNull() ?: return@launch
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    KickApi.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
                }
            }
        }
    }

    private fun start(cardView: View, slug: String, maxHeight: Int, playing: (() -> Unit)?) {
        pending = null
        val act = cardView.context as? FragmentActivity ?: return
        if (!cardView.hasFocus() && !cardView.isFocused) return
        load = act.lifecycleScope.launch {
            val url = runCatching { KickApi.channel(slug).takeIf { it.isLive }?.playbackUrl }.getOrNull() ?: return@launch
            if (!cardView.isFocused || !cardView.isAttachedToWindow) return@launch
            val frame = cardView.findViewById<FrameLayout>(R.id.thumbFrame) ?: return@launch
            // Built with a leveler (off while muted) so it can become the full-screen player.
            val p = player ?: LoudnessLeveler(false).let { lv ->
                leveler = lv
                PlayerFactory.create(act.applicationContext, leveler = lv).also { player = it }
            }
            p.volume = 0f
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, maxHeight).build()
            val tv = TextureView(act).apply { alpha = 0f }
            // Above the thumbnail, under the LIVE and viewer badges.
            frame.addView(tv, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            surface = tv
            card = cardView
            playingSlug = slug
            onPlaying = playing
            p.setVideoTextureView(tv)
            p.addListener(firstFrame)
            p.setMediaItem(PlayerFactory.item(url, PlayerFactory.liveConfig(lowLatency = false)))
            p.prepare()
            p.play()
            act.lifecycle.addObserver(stopOnPause)
        }
    }

    private val firstFrame = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            val tv = surface ?: return
            player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { crop(tv, it.width * it.pixelWidthHeightRatio, it.height.toFloat()) }
            tv.animate().alpha(1f).setDuration(250).start()
            onPlaying?.invoke()
        }

        // Fill the frame like the thumbnail does (centre crop) rather than stretching the picture.
        override fun onVideoSizeChanged(size: VideoSize) {
            val tv = surface ?: return
            if (size.width == 0 || size.height == 0) return
            tv.post { crop(tv, size.width * size.pixelWidthHeightRatio, size.height.toFloat()) }
        }
    }

    private fun crop(tv: TextureView, videoW: Float, videoH: Float) {
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (w == 0f || h == 0f) return
        // The texture is stretched to the view first; undo that, then scale up to cover.
        val cover = maxOf(w / videoW, h / videoH)
        val sx = videoW * cover / w
        val sy = videoH * cover / h
        tv.setTransform(Matrix().apply { setScale(sx, sy, w / 2, h / 2) })
    }

    /**
     * OK was pressed on [slug]: if its preview is up, the player leaves here still playing and
     * waits for the full-screen player to [take] it.
     */
    fun handOff(slug: String) {
        val p = player ?: return
        if (playingSlug != slug || p.playbackState == Player.STATE_IDLE || p.playerError != null) return
        p.removeListener(firstFrame)
        handoff?.player?.release()
        handoff = Handoff(p, leveler ?: return, slug)
        player = null
        leveler = null
        playingSlug = null
    }

    /** The handed-off player for [slug], if there is one; any other is released. */
    fun take(slug: String?): Handoff? {
        val h = handoff ?: return null
        handoff = null
        if (h.slug == slug) return h
        h.player.release()
        return null
    }

    /** Stops and frees the preview player (the app went to the background). */
    fun release() {
        stop()
        player?.release()
        player = null
        leveler = null
    }

    fun stop() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
        pendingFor = null
        load?.cancel()
        load = null
        player?.let {
            it.removeListener(firstFrame)
            it.stop()
            it.clearVideoTextureView(surface)
        }
        playingSlug = null
        onPlaying = null
        surface?.let { (it.parent as? ViewGroup)?.removeView(it) }
        surface = null
        (card?.context as? FragmentActivity)?.lifecycle?.removeObserver(stopOnPause)
        card = null
    }
}
