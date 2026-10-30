package com.diriktv.sidekick.ui

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
import androidx.media3.exoplayer.ExoPlayer
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.Prefs
import com.diriktv.sidekick.player.PlayerFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Live cards play a muted preview in their thumbnail once they've held focus for the delay set in
 * Settings. One small player is shared by every card; it stops as soon as focus moves on or the
 * screen goes away.
 */
object FocusPreview {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var card: View? = null
    private var surface: TextureView? = null
    private var load: Job? = null
    private var pending: Runnable? = null

    private val stopOnPause = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) = stop()
    }

    /** A card gained (or lost) focus; [slug] is the live channel it shows. */
    fun onFocus(cardView: View, slug: String, focused: Boolean) {
        if (!focused) {
            if (card === cardView || pendingFor === cardView) stop()
            return
        }
        stop()
        warm(cardView, slug)
        val delay = Prefs(cardView.context).previewDelay
        if (delay <= 0) return
        pendingFor = cardView
        val r = Runnable { start(cardView, slug) }
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

    private fun start(cardView: View, slug: String) {
        pending = null
        val act = cardView.context as? FragmentActivity ?: return
        if (!cardView.hasFocus() && !cardView.isFocused) return
        load = act.lifecycleScope.launch {
            val url = runCatching { KickApi.channel(slug).takeIf { it.isLive }?.playbackUrl }.getOrNull() ?: return@launch
            if (!cardView.isFocused || !cardView.isAttachedToWindow) return@launch
            val frame = cardView.findViewById<FrameLayout>(R.id.thumbFrame) ?: return@launch
            val p = player ?: PlayerFactory.create(act.applicationContext, maxHeight = 480).also {
                it.volume = 0f
                player = it
            }
            val tv = TextureView(act).apply { alpha = 0f }
            // Above the thumbnail, under the LIVE and viewer badges.
            frame.addView(tv, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            surface = tv
            card = cardView
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
            surface?.animate()?.alpha(1f)?.setDuration(250)?.start()
        }
    }

    /** Stops and frees the preview player (the app went to the background). */
    fun release() {
        stop()
        player?.release()
        player = null
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
        surface?.let { (it.parent as? ViewGroup)?.removeView(it) }
        surface = null
        (card?.context as? FragmentActivity)?.lifecycle?.removeObserver(stopOnPause)
        card = null
    }
}
