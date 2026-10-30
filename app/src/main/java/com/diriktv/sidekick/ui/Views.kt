package com.diriktv.sidekick.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Main content area. Sends LEFT presses that would leave it to the active nav item. */
class ContentFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    var leftExit: (() -> View?)? = null

    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        if (direction == View.FOCUS_LEFT && (result == null || !isDescendant(result))) {
            return leftExit?.invoke() ?: result
        }
        return result
    }

    private fun isDescendant(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }
}

/** Horizontal row that keeps focus inside itself at either end instead of jumping to another row. */
class HRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    RecyclerView(context, attrs) {
    /** When true, LEFT at the first item may leave the row (towards the nav rail). */
    var allowLeftExit = true

    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        if (focused != null && (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT)) {
            val inside = result != null && findContainingItemView(result) != null
            if (!inside) {
                if (direction == View.FOCUS_LEFT && allowLeftExit) {
                    val pos = findContainingViewHolder(focused)?.bindingAdapterPosition ?: -1
                    if (pos == 0) return result
                }
                return focused
            }
        }
        return result
    }
}

/**
 * ImageView that sizes its height from its width. Its size never depends on the image, so
 * swapping drawables doesn't request a layout (which would ripple through every list on screen).
 */
class AspectImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ImageView(context, attrs) {
    /** height / width */
    var ratio: Float = 9f / 16f
        set(value) { field = value; requestLayout() }

    private var settingDrawable = false

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        settingDrawable = true
        super.setImageDrawable(drawable)
        settingDrawable = false
    }

    override fun requestLayout() {
        if (!settingDrawable) super.requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * ratio).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}

/** Gentle zoom on focus, the standard TV affordance. */
fun View.focusZoom(scale: Float = 1.06f, extra: ((Boolean) -> Unit)? = null) {
    setOnFocusChangeListener { v, hasFocus ->
        val s = if (hasFocus) scale else 1f
        v.animate().scaleX(s).scaleY(s).translationZ(if (hasFocus) 8f else 0f).setDuration(140).start()
        extra?.invoke(hasFocus)
    }
}

fun ImageView.loadThumb(url: String?) {
    load(url) { crossfade(150) }
}

/** A round avatar; channels without a picture (or one that won't load) get their initial on a colored disc. */
fun ImageView.loadAvatar(url: String?, name: String? = null) {
    val letter = name?.takeIf { it.isNotBlank() }?.let { LetterAvatar(context, it) }
    load(url) {
        crossfade(150)
        transformations(CircleCropTransformation())
        if (letter != null) {
            error(letter)
            fallback(letter)
        }
    }
}

/** A disc in a color picked from the name, with its first letter. */
class LetterAvatar(context: Context, name: String) : android.graphics.drawable.Drawable() {
    private val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    private val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = COLORS[(name.lowercase().hashCode() and 0x7fffffff) % COLORS.size]
    }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = AppFonts.get(context, AppFonts.Weight.BOLD)
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, disc)
        text.textSize = r * 0.95f
        canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() - (text.ascent() + text.descent()) / 2, text)
    }

    override fun setAlpha(alpha: Int) { disc.alpha = alpha; text.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { disc.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        val COLORS = intArrayOf(
            0xFF2E7D32.toInt(), 0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(),
            0xFFEF6C00.toInt(), 0xFF00838F.toInt(), 0xFF4527A0.toInt(), 0xFF5D4037.toInt(),
        )
    }
}

/**
 * A slow drift: the image eases in from its normal size to a little larger over [durationMs],
 * towards a corner that changes with [seed], so a banner never sits completely still.
 */
fun View.kenBurns(seed: Int, durationMs: Long = 14_000) {
    animate().cancel()
    scaleX = 1f
    scaleY = 1f
    if (context.motionOff()) return
    post {
        pivotX = if (seed % 2 == 0) width * 0.25f else width * 0.75f
        pivotY = if (seed % 3 == 0) height * 0.3f else height * 0.65f
        animate().scaleX(1.09f).scaleY(1.09f).setDuration(durationMs)
            .setInterpolator(android.view.animation.LinearInterpolator()).start()
    }
}

/** Puts loading outlines where this spinner sits (same parent, filling it) and returns them. */
fun View.replaceWithSkeleton(columns: Int, ratio: Float = 9f / 16f, captions: Boolean = true): View {
    val parent = parent as ViewGroup
    val i = parent.indexOfChild(this)
    parent.removeView(this)
    val s = SkeletonView(context, columns, ratio, captions).apply { visibility = this@replaceWithSkeleton.visibility }
    parent.addView(s, i, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    return s
}

fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

/** Fades (and optionally lifts) a view into place. Safe to call on an already-visible view. */
fun View.fadeIn(duration: Long = 200, fromY: Float = 0f, delay: Long = 0) {
    animate().cancel()
    if (visibility != View.VISIBLE) {
        alpha = 0f
        translationY = fromY
        visibility = View.VISIBLE
    }
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration)
        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
}

/** Fades a view out, then removes it from layout. */
fun View.fadeOut(duration: Long = 160, toY: Float = 0f) {
    if (visibility != View.VISIBLE) return
    animate().cancel()
    animate().alpha(0f).translationY(toY).setStartDelay(0).setDuration(duration).withEndAction {
        visibility = View.GONE
        alpha = 1f
        translationY = 0f
    }.start()
}

fun ViewGroup.inflate(layout: Int): View =
    android.view.LayoutInflater.from(context).inflate(layout, this, false)

object Img {
    private val sizeRegex = Regex("""/(160|360|480|720|1080)\.webp(\?.*)?$""")

    /** Set at startup on TVs with little memory: card images come in a size smaller. */
    @Volatile var lowMemory = false

    /** Kick thumbnails exist at 160/360/480/720/1080 px wide; ask for the one that fits. */
    fun thumb(url: String?, width: Int = 480): String? =
        url?.replace(sizeRegex, "/${if (lowMemory && width == 480) 360 else width}.webp")

    /** From an HTML srcset, the smallest image at least [minWidth] px wide. */
    fun fromSrcset(srcset: String?, minWidth: Int): String? {
        val options = srcset.orEmpty().split(',').mapNotNull { part ->
            val bits = part.trim().split(' ')
            val w = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: return@mapNotNull null
            bits[0] to w
        }.sortedBy { it.second }
        return (options.firstOrNull { it.second >= minWidth } ?: options.lastOrNull())?.first
    }
}

object Fmt {
    fun count(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0).replace(".0K", "K")
        else -> n.toString()
    }

    fun viewers(n: Int) = "${count(n)} watching"

    fun time(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Kick returns either ISO "2026-10-20T19:22:23Z" or "2026-10-20 19:22:23", both UTC. */
    fun parseUtc(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val clean = s.replace('T', ' ').removeSuffix("Z").substringBefore('.').substringBefore('+')
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(clean)?.time
        } catch (e: Exception) {
            null
        }
    }

    fun uptime(start: String?): String? {
        val t = parseUtc(start) ?: return null
        return time(System.currentTimeMillis() - t)
    }

    fun ago(date: String?): String = parseUtc(date)?.let { ago(it) } ?: ""

    fun ago(t: Long): String {
        val mins = (System.currentTimeMillis() - t) / 60000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            mins < 60 * 24 * 30 -> "${mins / (60 * 24)}d ago"
            else -> "${mins / (60 * 24 * 30)}mo ago"
        }
    }
}

/** A 64-bit hash of [key] for RecyclerView stable ids (FNV-1a; collisions are vanishingly rare). */
fun stableId(key: String): Long {
    var h = -0x340d631b7bdddcdbL
    for (c in key) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L
    }
    return h
}

/**
 * An icon with a small count bubble on its top-right corner (live follows on the Following
 * heart). Tint goes to the icon only, so the bubble keeps its own colors in every state.
 */
class BadgedIcon(
    private val icon: android.graphics.drawable.Drawable,
    private val count: String,
    fill: Int,
    tf: android.graphics.Typeface,
    /** Just a small dot, no number. */
    private val dot: Boolean = false,
) : android.graphics.drawable.Drawable() {
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = tf
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val rect = android.graphics.RectF()

    override fun draw(canvas: android.graphics.Canvas) {
        icon.bounds = bounds
        icon.draw(canvas)
        val b = bounds
        val h = b.height() * (if (dot) 0.34f else 0.62f)
        text.textSize = h * 0.72f
        val w = maxOf(h, text.measureText(count) + h * 0.5f)
        val right = b.right + h * (if (dot) 0.15f else 0.38f)
        val top = b.top - h * (if (dot) 0.05f else 0.3f)
        rect.set(right - w, top, right, top + h)
        canvas.drawRoundRect(rect, h / 2, h / 2, bg)
        if (!dot) canvas.drawText(count, rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2, text)
    }

    override fun getIntrinsicWidth() = icon.intrinsicWidth
    override fun getIntrinsicHeight() = icon.intrinsicHeight
    override fun isStateful() = icon.isStateful
    override fun onStateChange(state: IntArray): Boolean = icon.setState(state).also { invalidateSelf() }
    override fun setTintList(tint: android.content.res.ColorStateList?) = icon.setTintList(tint)
    override fun setAlpha(alpha: Int) { icon.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { icon.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * The focused card's outline glows gently in and out so it's easy to find from across the room.
 * Only one card has focus at a time, so one animator serves them all.
 */
object FocusGlow {
    private var target: View? = null
    private val pulse = android.animation.ValueAnimator.ofInt(255, 120).apply {
        duration = 1100
        repeatMode = android.animation.ValueAnimator.REVERSE
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { a -> target?.foreground?.alpha = a.animatedValue as Int }
    }

    /** [card] gained or lost focus; the glow runs on the view inside it that draws the outline. */
    fun onFocus(card: View, focused: Boolean) {
        val outline = findOutline(card) ?: return
        if (focused) {
            if (outline.context.motionOff()) return
            target?.foreground?.alpha = 255
            target = outline
            pulse.start()
        } else if (target === outline) {
            pulse.cancel()
            outline.foreground?.alpha = 255
            target = null
        }
    }

    private fun findOutline(v: View): View? {
        if (v.foreground != null) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findOutline(v.getChildAt(i))?.let { return it }
        return null
    }
}

/**
 * The side menu's background: its own shade, easing into the page over the last [fadeDp] on a
 * smooth curve (many stops, dithered) so there's no visible seam or band where they meet.
 */
class SoftEdgeBackground(context: Context, private val fadeDp: Int = 64) : android.graphics.drawable.Drawable() {
    private val from = context.getColor(com.diriktv.sidekick.R.color.surface)
    private val to = context.getColor(com.diriktv.sidekick.R.color.bg)
    private val fade = context.dp(fadeDp).toFloat()
    private val paint = android.graphics.Paint().apply { isDither = true }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val start = (bounds.width() - fade).coerceAtLeast(0f)
        val n = 12
        val colors = IntArray(n + 1)
        val stops = FloatArray(n + 1)
        val argb = android.animation.ArgbEvaluator()
        for (i in 0..n) {
            val t = i / n.toFloat()
            val eased = t * t * (3 - 2 * t) // smoothstep: no hard start or end to the fade
            colors[i] = argb.evaluate(eased, from, to) as Int
            stops[i] = t
        }
        paint.shader = android.graphics.LinearGradient(bounds.left + start, 0f, bounds.right.toFloat(), 0f,
            colors, stops, android.graphics.Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: android.graphics.Canvas) = canvas.drawRect(bounds, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

/**
 * Secondary text that steps up to the medium weight while its row is selected, so it stays easy
 * to read on the green highlight (the regular weight looks thin there).
 */
class FocusWeightTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    android.widget.TextView(context, attrs) {
    private var medium: Boolean? = null

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val want = android.R.attr.state_focused in drawableState
        if (want == medium) return
        medium = want
        typeface = AppFonts.get(context, if (want) AppFonts.Weight.MEDIUM else AppFonts.Weight.REGULAR)
    }
}
