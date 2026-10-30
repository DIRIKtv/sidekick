package com.diriktv.sidekick.ui

import android.app.Activity
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Prefs

/**
 * The app's typeface, chosen in Settings. Layouts keep asking for the system families
 * (sans-serif, sans-serif-medium, sans-serif-black); an inflater factory swaps in the matching
 * weight of the chosen font, so the XML stays as it is and older Android versions work too.
 */
object AppFonts {
    enum class Weight { REGULAR, MEDIUM, BOLD, BLACK }

    enum class Font(val key: String, val label: String, val sub: String, val files: IntArray) {
        FIGTREE("figtree", "Figtree", "Friendly and rounded (default)",
            intArrayOf(R.font.figtree_regular, R.font.figtree_medium, R.font.figtree_bold, R.font.figtree_black)),
        INTER("inter", "Inter", "Clean and compact",
            intArrayOf(R.font.inter_regular, R.font.inter_medium, R.font.inter_bold, R.font.inter_black)),
        MANROPE("manrope", "Manrope", "Modern and geometric",
            intArrayOf(R.font.manrope_regular, R.font.manrope_medium, R.font.manrope_bold, R.font.manrope_black)),
        SYSTEM("system", "System", "Your TV's built-in font", IntArray(0));

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: FIGTREE
        }
    }

    private var loadedFor: Font? = null
    private val cache = arrayOfNulls<Typeface>(Weight.entries.size)

    fun current(ctx: Context): Font = Font.of(Prefs(ctx).font)

    fun get(ctx: Context, weight: Weight): Typeface {
        val font = current(ctx)
        if (font != loadedFor) {
            cache.fill(null)
            loadedFor = font
        }
        cache[weight.ordinal]?.let { return it }
        val tf = if (font == Font.SYSTEM) system(weight)
        else runCatching { ResourcesCompat.getFont(ctx, font.files[weight.ordinal]) }.getOrNull() ?: system(weight)
        cache[weight.ordinal] = tf
        return tf
    }

    /** [font] at medium weight, for showing each choice in its own face. */
    fun preview(ctx: Context, font: Font): Typeface =
        if (font == Font.SYSTEM) system(Weight.MEDIUM)
        else runCatching { ResourcesCompat.getFont(ctx, font.files[Weight.MEDIUM.ordinal]) }.getOrNull() ?: system(Weight.MEDIUM)

    private fun system(weight: Weight): Typeface = when (weight) {
        Weight.REGULAR -> Typeface.DEFAULT
        Weight.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        Weight.BOLD -> Typeface.DEFAULT_BOLD
        Weight.BLACK -> Typeface.create("sans-serif-black", Typeface.NORMAL)
    }

    /** Call before setContentView so every inflated text view picks up the font. */
    fun install(activity: Activity) {
        val inflater = LayoutInflater.from(activity)
        if (inflater.factory2 == null) inflater.factory2 = Factory(activity)
    }

    private val textAttrs = intArrayOf(android.R.attr.fontFamily, android.R.attr.textStyle)

    /** Which weight a view's XML asked for, from its family name and text style; null keeps a special family (monospace). */
    @android.annotation.SuppressLint("ResourceType") // indexes into textAttrs, not resource ids
    private fun weightOf(ctx: Context, attrs: AttributeSet): Weight? {
        val a = ctx.obtainStyledAttributes(attrs, textAttrs)
        val family = a.getString(0).orEmpty()
        val bold = a.getInt(1, 0) and Typeface.BOLD != 0
        a.recycle()
        return when {
            family == "monospace" || family == "serif" -> null
            "black" in family -> Weight.BLACK
            bold -> Weight.BOLD
            "medium" in family -> Weight.MEDIUM
            else -> Weight.REGULAR
        }
    }

    private class Factory(private val activity: Activity) : LayoutInflater.Factory2 {
        private val textClasses = HashMap<String, Boolean>()

        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
            val className = if ('.' in name) name else "android.widget.$name"
            val isText = textClasses.getOrPut(className) {
                runCatching { TextView::class.java.isAssignableFrom(Class.forName(className)) }.getOrDefault(false)
            }
            if (!isText) return null
            val view = runCatching {
                // Build it the way LayoutInflater would, then set the font.
                Class.forName(className).getConstructor(Context::class.java, AttributeSet::class.java)
                    .newInstance(context, attrs) as TextView
            }.getOrNull() ?: return null
            weightOf(context, attrs)?.let { view.typeface = get(activity, it) }
            return view
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)
    }
}

fun TextView.font(weight: AppFonts.Weight) {
    typeface = AppFonts.get(context, weight)
}

/** Bold text inside a span (chat usernames) in the app font's real bold, not a smeared regular. */
class BoldSpan(private val tf: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    private fun apply(p: Paint) {
        p.typeface = tf
    }
}
