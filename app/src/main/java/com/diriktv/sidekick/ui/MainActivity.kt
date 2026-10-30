package com.diriktv.sidekick.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.diriktv.sidekick.R

class MainActivity : FragmentActivity() {
    private lateinit var content: ContentFrame
    private lateinit var navItems: List<TextView>
    private val pages = arrayOfNulls<MainPage>(5)
    private var active = -1
    private var lastBack = 0L
    /** The page the app opens on (Settings → Start-up page); Back returns there before exiting. */
    private var home = 0
    private val liveListener: () -> Unit = { showLiveCount() }
    /** Keeps the live count beside Following fresh while the main screen is up. */
    private val liveTick = object : Runnable {
        override fun run() {
            lifecycleScope.launch { runCatching { com.diriktv.sidekick.data.Follows.liveNow(this@MainActivity, maxAgeMs = 90_000) } }
            content.postDelayed(this, 120_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Sidekick) // swap out the launch screen
        super.onCreate(savedInstanceState)
        AppFonts.install(this)
        window.setBackgroundDrawableResource(R.color.bg)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        navItems = listOf(R.id.navHome, R.id.navFollowing, R.id.navBrowse, R.id.navSearch, R.id.navSettings)
            .map { findViewById(it) }
        content.leftExit = { navItems.getOrNull(active) }
        indicator = findViewById(R.id.navIndicator)

        navItems.forEachIndexed { i, item ->
            item.setOnFocusChangeListener { _, has ->
                if (!has) {
                    // Focus left the menu: the highlight settles back on the page that's open.
                    item.post { if (navItems.none { it.isFocused }) indicator.moveTo(navItems[active], focused = false) }
                    return@setOnFocusChangeListener
                }
                indicator.moveTo(item, focused = true)
                // Only a key press moves between pages. Focus also lands here when a focused card
                // disappears (a list refreshing); that mustn't switch pages, so put it back.
                if (i == active || SystemClock.uptimeMillis() - lastKeyAt < 600) show(i)
                else item.post { navItems[active].requestFocus() }
            }
            item.setOnClickListener { pages[i]?.focusContent() }
            item.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    pages[i]?.focusContent() ?: false
                } else false
            }
        }
        // Back via the dispatcher: Android 16+ no longer calls onBackPressed for apps targeting it.
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        if (savedInstanceState == null) raiseCurtain()
        home = com.diriktv.sidekick.data.Prefs(this).startPage.coerceIn(0, 3)
        // Rebuilt after a font change: stay on the page that was open.
        val start = savedInstanceState?.getInt("page", home) ?: home
        if (curtain != null) {
            if (start == 0) (page(0) as HomePage).onFirstLoad = { liftCurtain() }
            else content.postDelayed({ liftCurtain() }, 400)
        }
        show(start)
        content.post { if (!pages[start]!!.focusContent()) navItems[start].requestFocus() }
        content.postDelayed({ (page(2) as BrowsePage).preload() }, 3000)
    }

    private fun page(i: Int): MainPage = pages[i] ?: when (i) {
        0 -> HomePage(this)
        1 -> FollowingPage(this)
        2 -> BrowsePage(this)
        3 -> SearchPage(this)
        else -> SettingsPage(this) { (pages[0] as? HomePage)?.reload() }
    }.also { pages[i] = it }

    private fun show(i: Int) {
        if (i == active) return
        val from = active
        active = i
        if (navItems.none { it.isFocused }) navItems[i].post { indicator.moveTo(navItems[i], focused = navItems[i].isFocused, animate = from >= 0) }
        navItems.forEachIndexed { idx, v -> v.isActivated = idx == i }
        val p = page(i)
        content.removeAllViews()
        (p.view.parent as? ViewGroup)?.removeView(p.view)
        content.addView(p.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.view.animate().cancel()
        // The page comes in from the direction you moved in the menu: down the menu, it rises
        // from below; up the menu, it drops in from above.
        if (from >= 0 && com.diriktv.sidekick.data.Prefs(this).pageTransitions && !motionOff()) {
            val dir = if (i > from) 1 else -1
            p.view.alpha = 0f
            p.view.translationY = dir * dp(36).toFloat()
            p.view.translationX = 0f
            p.view.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        } else {
            p.view.alpha = 1f
            p.view.translationY = 0f
        }
        p.onShown()
    }

    private lateinit var indicator: NavIndicator
    private val clockTick = object : Runnable {
        override fun run() {
            val now = java.util.Date()
            findViewById<TextView>(R.id.navClock).text = android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(now)
            findViewById<TextView>(R.id.navDate).text = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(now)
            content.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 200)
        }
    }

    /** The account at the bottom of the menu: avatar and name, or a nudge to sign in. */
    private fun showAccount() {
        val prefs = com.diriktv.sidekick.data.Prefs(this)
        val name = prefs.accountName?.takeIf { com.diriktv.sidekick.data.Follows.signedIn }
        findViewById<TextView>(R.id.navName).text = name ?: "Not signed in"
        findViewById<android.widget.ImageView>(R.id.navAvatar).apply {
            if (name != null) { imageTintList = null; loadAvatar(prefs.accountAvatar, name) }
            else { setImageResource(R.drawable.ic_account); imageTintList = getColorStateList(R.color.text_secondary) }
        }
    }

    /** A dot on Settings while a new version is waiting. */
    private fun showUpdateDot() {
        val icon = getDrawable(R.drawable.ic_tune)!!.mutate()
        val waiting = com.diriktv.sidekick.data.Updater.available != null
        navItems[4].setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (waiting) BadgedIcon(icon, "", getColor(R.color.kick_green), AppFonts.get(this, AppFonts.Weight.BOLD), dot = true) else icon,
            null, null, null,
        )
    }

    private var curtain: LaunchCurtain? = null
    private var curtainAt = 0L

    /** Covers the screen with the launch logo until Home has something to show. */
    private fun raiseCurtain() {
        val c = LaunchCurtain(this)
        (window.decorView as ViewGroup).addView(c, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        curtain = c
        curtainAt = SystemClock.uptimeMillis()
        findViewById<View>(R.id.navLogo).alpha = 0f
        // Never wait on a slow network for more than a few seconds.
        content.postDelayed({ liftCurtain() }, 6_000)
    }

    /**
     * The logo flies into its place in the side menu, the menu items slide in after it one by one,
     * and the page rises into place.
     */
    private fun liftCurtain() {
        val c = curtain ?: return
        val wait = 650 - (SystemClock.uptimeMillis() - curtainAt)
        if (wait > 0) {
            content.postDelayed({ liftCurtain() }, wait)
            return
        }
        curtain = null
        val logo = findViewById<View>(R.id.navLogo)
        val loc = IntArray(2).also { logo.getLocationInWindow(it) }
        c.reveal(android.graphics.Rect(loc[0], loc[1], loc[0] + logo.width, loc[1] + logo.height)) { logo.alpha = 1f }
        if (motionOff()) return
        val ease = android.view.animation.DecelerateInterpolator(2f)
        indicator.alpha = 0f
        indicator.animate().alpha(1f).setStartDelay(300L + active * 55L).setDuration(380).start()
        navItems.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationX = -dp(28).toFloat()
            v.animate().alpha(1f).translationX(0f).setStartDelay(300L + i * 55L).setDuration(380).setInterpolator(ease).start()
        }
        content.alpha = 0f
        content.translationY = dp(32).toFloat()
        content.animate().alpha(1f).translationY(0f).setStartDelay(380).setDuration(460).setInterpolator(ease).start()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Restoring the views puts focus back on the nav rail; send it into the page instead.
        content.post { pages[active]?.focusContent() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", active)
    }

    /** Each time the app is opened, look for a release (per the update setting) once Home has settled. */
    private fun checkForUpdateOnOpen() {
        if (!com.diriktv.sidekick.data.Updater.openedPending) return
        com.diriktv.sidekick.data.Updater.openedPending = false
        content.postDelayed({
            lifecycleScope.launch {
                val release = com.diriktv.sidekick.data.Updater.checkIfDue(this@MainActivity)
                showUpdateDot()
                if (release == null) return@launch
                if (!isFinishing && hasWindowFocus()) UpdateFlow(this@MainActivity, release).show()
            }
        }, 2500)
    }

    /** A red count of live followed channels on the Following heart (nothing when none are). */
    private fun showLiveCount() {
        val n = com.diriktv.sidekick.data.Follows.liveCount
        val heart = getDrawable(R.drawable.ic_heart)!!.mutate()
        val icon = if (n > 0) BadgedIcon(heart, if (n > 99) "99+" else n.toString(), getColor(R.color.live_red),
            AppFonts.get(this, AppFonts.Weight.BOLD)) else heart
        navItems[1].setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        navItems[1].contentDescription = if (n > 0) "Following, $n live" else "Following"
    }

    override fun onStart() {
        super.onStart()
        com.diriktv.sidekick.data.Follows.liveListeners += liveListener
        showLiveCount()
        content.post(liveTick)
        content.post(clockTick)
        showAccount()
        showUpdateDot()
    }

    override fun onStop() {
        com.diriktv.sidekick.data.Follows.liveListeners -= liveListener
        content.removeCallbacks(liveTick)
        content.removeCallbacks(clockTick)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        checkForUpdateOnOpen()
        // Favorites may have changed in the player or channel screen.
        if (active == 1) pages[1]?.onShown()
        if (active == 0) pages[0]?.onShown()
        if (active == 4) pages[4]?.onShown()
    }

    private fun handleBack() {
        val focus = currentFocus
        if (focus != null && focus !in navItems) {
            navItems[active].requestFocus()
            return
        }
        if (active != home) {
            navItems[home].requestFocus()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) {
            finish()
        } else {
            lastBack = now
            android.widget.Toast.makeText(this, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var lastKeyAt = 0L

    // Overriding Activity.dispatchKeyEvent is standard; lint trips on AndroidX's restricted override of it.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) lastKeyAt = SystemClock.uptimeMillis()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Menu on the home screen refreshes.
        if (keyCode == KeyEvent.KEYCODE_MENU && active == 0) {
            (pages[0] as? HomePage)?.reload()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
