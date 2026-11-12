package com.diriktv.sidekick.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.Prefs
import kotlinx.coroutines.launch

/** All live streams in one category, most watched first. */
class CategoryActivity : FragmentActivity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
    }

    private lateinit var adapter: GridAdapter
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: View
    private val errorRetry by lazy { ErrorRetry(status) }
    private var cursor: String? = null
    private var done = false
    private var busy = false
    private var categoryId = 0
    private var allLanguages = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppFonts.install(this)
        setContentView(R.layout.page_grid)
        categoryId = intent.getIntExtra(EXTRA_ID, 0)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        progress = findViewById<View>(R.id.progress).replaceWithSkeleton(4)
        list.setPadding(dp(32), dp(24), dp(32), dp(40))
        adapter = GridAdapter(
            onClick = { Nav.onCardClick(this, it) },
            onLongClick = { Nav.onCardLongClick(this, it) },
            onNearEnd = { load() },
        )
        list.layoutManager = CenterGridLayoutManager(this, 4).apply { spanSizeLookup = adapter.spanLookup(4) }
        list.adapter = adapter
        list.itemAnimator = null
        adapter.append(listOf(GridItem.Header(intent.getStringExtra(EXTRA_NAME).orEmpty())))
        load()
    }

    private fun load() {
        if (busy || done) return
        busy = true
        progress.setVisible(adapter.itemCount <= 1 && !errorRetry.showing)
        val lang = if (allLanguages) null else Prefs(this).language.ifEmpty { null }
        lifecycleScope.launch {
            try {
                var page = KickApi.livestreams(cursor = cursor, categoryId = categoryId, language = lang)
                // Nothing in the chosen language: fall back to every language.
                if (page.items.isEmpty() && cursor == null && lang != null) {
                    allLanguages = true
                    page = KickApi.livestreams(categoryId = categoryId)
                }
                val first = adapter.itemCount <= 1
                adapter.append(page.items.map { GridItem.Stream(it) })
                cursor = page.next
                done = page.next == null
                if (first) {
                    errorRetry.hide()
                    status.text = if (page.items.isEmpty()) "Nobody is live here right now." else ""
                    list.post { list.layoutManager?.findViewByPosition(1)?.requestFocus() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not marked done, so the button (or scrolling on, for later pages) tries again.
                if (adapter.itemCount <= 1) {
                    errorRetry.show("Couldn't load streams", e) { load() }
                    errorRetry.focusable?.requestFocus()
                }
            } finally {
                busy = false
                progress.setVisible(false)
            }
        }
    }
}
