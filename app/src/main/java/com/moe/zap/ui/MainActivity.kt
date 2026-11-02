package com.moe.zap.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.moe.zap.R
import com.moe.zap.core.Kind
import com.moe.zap.data.Item
import com.moe.zap.data.PagedItems
import com.moe.zap.data.Repo
import com.moe.zap.data.Syncer

enum class Section { SEARCH, LIVE, MOVIES, SERIES, SETTINGS }

class MainActivity : Activity() {
    private lateinit var root: ViewGroup
    private lateinit var rail: ViewGroup
    private lateinit var navs: Map<Section, TextView>
    private lateinit var browse: BrowsePane
    private lateinit var searchPane: SearchPane
    private lateinit var settings: SettingsPane
    private lateinit var syncOverlay: View
    private lateinit var syncStep: TextView
    private lateinit var syncRetry: View

    private var section = Section.LIVE
    private var lastBack = 0L
    private var started = false
    private var keyPressed = false

    // Android can pass focus through the rail by itself when a focused view disappears. Only act if it stayed.
    private val pendingSection = Runnable {
        val focus = currentFocus
        if (focus != null && rail.indexOfChild(focus) >= 0) showSection(railFocus ?: section)
    }
    private var railFocus: Section? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Repo.creds.exists()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        started = true
        root = findViewById(R.id.root)
        rail = findViewById(R.id.rail)
        navs = mapOf(
            Section.SEARCH to findViewById(R.id.navSearch),
            Section.LIVE to findViewById(R.id.navLive),
            Section.MOVIES to findViewById(R.id.navMovies),
            Section.SERIES to findViewById(R.id.navSeries),
            Section.SETTINGS to findViewById(R.id.navSettings),
        )
        browse = BrowsePane(this, findViewById(R.id.paneBrowse)) { item, index, paged -> open(item, index, paged) }
        searchPane = SearchPane(this, findViewById(R.id.paneSearch)) { open(it, -1, null) }
        settings = SettingsPane(this, findViewById(R.id.paneSettings), ::onLoggedOut)
        syncOverlay = findViewById(R.id.syncOverlay)
        syncStep = findViewById(R.id.syncStep)
        syncRetry = findViewById(R.id.syncRetry)
        syncRetry.setOnClickListener { firstSync() }

        for ((s, nav) in navs) {
            nav.setOnFocusChangeListener { _, has ->
                // The window hands the rail its first focus by itself at launch; only a key press may change section.
                if (has && keyPressed) {
                    railFocus = s
                    // A short wait, so moving through the rail does not load every section on the way.
                    root.removeCallbacks(pendingSection)
                    root.postDelayed(pendingSection, 110)
                }
            }
            nav.setOnClickListener { enterPane() }
        }

        // Up and down stay inside the menu; left to Android, they can jump to whichever button is nearest.
        val order = navs.values.toList()
        order.forEachIndexed { i, nav ->
            nav.nextFocusUpId = order[(i - 1).coerceAtLeast(0)].id
            nav.nextFocusDownId = order[(i + 1).coerceAtMost(order.lastIndex)].id
        }

        section = runCatching { Section.valueOf(Repo.prefs.getString("section", null) ?: "") }.getOrDefault(Section.LIVE)
        if (section == Section.SEARCH || section == Section.SETTINGS) section = Section.LIVE

        Repo.hasCatalog { has ->
            if (has) {
                showSection(section, focusContent = true)
                // Quietly refresh in the background once a day, after the app has finished starting.
                if (Syncer.isStale()) root.postDelayed({ startBackgroundSync() }, 5000)
            } else {
                firstSync()
            }
        }
    }

    private fun startBackgroundSync() {
        Syncer.onProgress = null
        Syncer.onFinished = { settings.refresh() }
        Syncer.start()
    }

    private fun firstSync() {
        syncOverlay.visibility = View.VISIBLE
        syncRetry.visibility = View.GONE
        syncStep.text = "Connecting…"
        Syncer.onProgress = { p ->
            syncStep.text = if (p.items > 0) "${p.step}: ${"%,d".format(p.items)} titles" else p.step
        }
        Syncer.onFinished = { result ->
            result.onSuccess {
                syncOverlay.visibility = View.GONE
                showSection(Section.LIVE, focusContent = true)
            }.onFailure {
                syncStep.text = "Could not download your library. Check your connection."
                syncRetry.visibility = View.VISIBLE
                syncRetry.requestFocus()
            }
        }
        Syncer.start()
    }

    private fun kindOf(s: Section) = when (s) {
        Section.LIVE -> Kind.LIVE
        Section.MOVIES -> Kind.MOVIE
        Section.SERIES -> Kind.SERIES
        else -> null
    }

    private fun showSection(s: Section, focusContent: Boolean = false) {
        root.removeCallbacks(pendingSection)
        val changed = !browse.isShowing(kindOf(s))
        section = s
        for ((k, nav) in navs) nav.isActivated = k == s
        val kind = kindOf(s)
        browse.root.visibility = if (kind != null) View.VISIBLE else View.GONE
        searchPane.root.visibility = if (s == Section.SEARCH) View.VISIBLE else View.GONE
        settings.root.visibility = if (s == Section.SETTINGS) View.VISIBLE else View.GONE
        if (kind != null) {
            Repo.prefs.edit().putString("section", s.name).apply()
            if (changed) browse.show(kind, focusContent) else if (focusContent) browse.focusItems()
        } else if (focusContent) {
            enterPane()
        }
        if (s == Section.SETTINGS) settings.refresh()
    }

    /** Moves from the rail into the current section. */
    private fun enterPane() {
        val target = railFocus ?: section
        showSection(target)
        when (section) {
            Section.SEARCH -> searchPane.focus()
            Section.SETTINGS -> settings.focus()
            else -> browse.focusCategories()
        }
    }

    private fun open(item: Item, index: Int, paged: PagedItems?) {
        when (item.kind) {
            Kind.LIVE -> if (paged != null) {
                PlayerActivity.playLive(this, paged, index, item, followInList = true)
            } else {
                // Opened from search: up and down walk through all channels, around this one.
                Repo.allChannelsAt(item) { all, at -> PlayerActivity.playLive(this, all, at, item, followInList = false) }
            }
            else -> DetailActivity.open(this, item)
        }
    }

    override fun onResume() {
        super.onResume()
        if (started && syncOverlay.visibility != View.VISIBLE) browse.onReturn()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (started && event.action == KeyEvent.ACTION_DOWN) {
            keyPressed = true
            val focus = currentFocus
            if (focus != null && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && rail.indexOfChild(focus) >= 0) {
                enterPane()
                return true
            }
            // Leaving a pane leftward always lands on the current section, never on whatever rail item is nearest.
            if (focus != null && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && rail.indexOfChild(focus) < 0) {
                val next = FocusFinder.getInstance().findNextFocus(root, focus, View.FOCUS_LEFT)
                if (next != null && rail.indexOfChild(next) >= 0) {
                    navs[section]?.requestFocus()
                    return true
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
                showSection(Section.SEARCH)
                searchPane.focus()
                return true
            }
            if (section == Section.SEARCH && searchPane.root.hasFocus() && searchPane.onKey(event)) return true
            if (browse.root.visibility == View.VISIBLE && browse.onKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val focus = currentFocus
        when {
            !started || syncOverlay.visibility == View.VISIBLE -> finish()
            focus != null && rail.indexOfChild(focus) >= 0 -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2000) {
                    finish()
                } else {
                    lastBack = now
                    Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
            browse.root.visibility == View.VISIBLE && browse.handleBack() -> Unit
            section == Section.SEARCH && searchPane.handleBack() -> Unit
            else -> navs[section]?.requestFocus()
        }
    }

    private fun onLoggedOut() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
