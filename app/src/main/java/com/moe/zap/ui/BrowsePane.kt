package com.moe.zap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.moe.zap.R
import com.moe.zap.core.Category
import com.moe.zap.core.Kind
import com.moe.zap.data.Item
import com.moe.zap.data.Lock
import com.moe.zap.data.PagedItems
import com.moe.zap.data.Repo
import com.moe.zap.data.Special

/** Categories on the left, their channels or posters beside them. */
class BrowsePane(
    private val activity: MainActivity,
    val root: View,
    private val onOpen: (Item, Int, PagedItems) -> Unit,
) {
    private val categories: VerticalGridView = root.findViewById(R.id.categories)
    private val items: VerticalGridView = root.findViewById(R.id.items)
    private val empty: TextView = root.findViewById(R.id.empty)
    private val info: View = root.findViewById(R.id.info)
    private val infoLogo: ImageView = root.findViewById(R.id.infoLogo)
    private val infoName: TextView = root.findViewById(R.id.infoName)
    private val infoNowLabel: View = root.findViewById(R.id.infoNowLabel)
    private val infoNow: TextView = root.findViewById(R.id.infoNow)
    private val infoProgress: ProgressBar = root.findViewById(R.id.infoProgress)
    private val infoDesc: TextView = root.findViewById(R.id.infoDesc)
    private val infoNext: TextView = root.findViewById(R.id.infoNext)

    private var kind: Kind? = null
    private var categoryList: List<Category> = emptyList()
    private var currentCategory: String? = null
    private var paged: PagedItems? = null
    private var pendingFocus = false
    private var restoreIndex = 0
    private var infoItem: Item? = null
    private var lockVersion = Lock.version
    private val infoCatchup: TextView = root.findViewById(R.id.infoCatchup)
    private val catchupPanel: View = root.findViewById(R.id.catchupPanel)
    private val catchupTitle: TextView = root.findViewById(R.id.catchupTitle)
    private val catchupList: VerticalGridView = root.findViewById(R.id.catchupList)

    private val categoryAdapter = CategoryAdapter()
    private val pendingCategory = Runnable {
        categoryList.getOrNull(categories.selectedPosition)?.let { select(it.id, 0, focus = false) }
    }
    private val pendingEpg = Runnable { infoItem?.let(::loadEpg) }

    init {
        categories.tune()
        categories.adapter = categoryAdapter
        categories.setVerticalSpacing(activity.dp(2))
        items.tune()
        catchupList.tune()
        catchupList.setVerticalSpacing(activity.dp(2))
    }

    fun isShowing(k: Kind?) = k != null && k == kind

    /** One step back within this pane. False means the menu should take focus. */
    fun handleBack(): Boolean {
        if (catchupPanel.visibility == View.VISIBLE) {
            closeCatchup()
            return true
        }
        if (items.hasFocus()) {
            categories.requestFocus()
            return true
        }
        return false
    }

    /** Right and left keys that mean something here rather than moving focus. */
    fun onKey(e: android.view.KeyEvent): Boolean {
        val right = e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        val left = e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
        if (!right && !left) return false
        if (catchupPanel.visibility == View.VISIBLE) {
            if (left) {
                closeCatchup()
                return true
            }
            return false
        }
        if (!items.hasFocus()) return false
        if (right && kind == Kind.LIVE) {
            val item = infoItem
            if (item != null && item.archiveDays > 0) openCatchup(item)
            return true
        }
        return false
    }

    private fun openCatchup(item: Item) {
        info.visibility = View.GONE
        catchupPanel.visibility = View.VISIBLE
        catchupTitle.text = "Loading catch-up…"
        catchupList.adapter = null
        Repo.pastPrograms(item) { list ->
            if (catchupPanel.visibility != View.VISIBLE || infoItem != item) return@pastPrograms
            if (list.isEmpty()) {
                catchupTitle.text = "No past programs found"
                return@pastPrograms
            }
            catchupTitle.text = "Catch-up: ${item.name}"
            catchupList.adapter = CatchupAdapter(item, list)
            catchupList.requestFocus()
        }
    }

    private fun closeCatchup() {
        // Focus moves first: hiding a focused view would send it to the first item in the menu.
        items.requestFocus()
        catchupPanel.visibility = View.GONE
        info.visibility = View.VISIBLE
    }

    private inner class CatchupAdapter(val item: Item, val list: List<com.moe.zap.core.EpgEntry>) :
        RecyclerView.Adapter<CatchupAdapter.Holder>() {
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text) {
            init {
                text.setOnClickListener {
                    list.getOrNull(bindingAdapterPosition)?.let { e ->
                        Repo.bg({ Repo.timeshiftUrl(item, e) }) { r ->
                            r.onSuccess { url ->
                                PlayerActivity.playVod(
                                    activity,
                                    listOf(PlayerActivity.Vod(item.id, PlayerActivity.CATCHUP_KIND, item.id, item.name, e.title, "ts", url)),
                                    0,
                                    0L,
                                )
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
        )

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = list[position]
            h.text.text = "${dayAndClock(e.start)}  ${e.title}"
        }
    }

    fun focusCategories() {
        categories.requestFocus()
    }

    fun focusItems() {
        if ((paged?.count ?: 0) > 0) items.requestFocus() else categories.requestFocus()
    }

    fun show(k: Kind, focusContent: Boolean) {
        kind = k
        catchupPanel.visibility = View.GONE
        lockVersion = Lock.version
        paged = null
        currentCategory = null
        items.adapter = null
        info.visibility = View.GONE
        pendingFocus = focusContent
        val saved = Repo.prefs.getString("cat_${k.code}", null)
        restoreIndex = if (focusContent) Repo.prefs.getInt("idx_${k.code}", 0) else 0
        Repo.categories(k) { list ->
            if (kind != k) return@categories
            categoryList = list
            categoryAdapter.notifyDataSetChanged()
            val start = list.indexOfFirst { it.id == saved }.takeIf { it >= 0 } ?: defaultCategory(k, list)
            categories.selectedPosition = start
            select(list[start].id, restoreIndex, focusContent)
        }
    }

    // Open on All, not on a Favorites list that starts out empty.
    private fun defaultCategory(k: Kind, list: List<Category>): Int {
        val want = if (k == Kind.LIVE) Special.ALL else Special.RECENT
        return list.indexOfFirst { it.id == want }.coerceAtLeast(0)
    }

    private fun select(categoryId: String, index: Int, focus: Boolean) {
        if (categoryId == currentCategory && !focus) return
        val k = kind ?: return
        currentCategory = categoryId
        categoryAdapter.notifyActivated()
        Repo.prefs.edit().putString("cat_${k.code}", categoryId).apply()
        // A locked category shows nothing, not even a count, until its PIN is entered.
        if (Lock.isLockedNow(k, categoryId)) {
            paged = null
            items.adapter = null
            info.visibility = View.GONE
            catchupPanel.visibility = View.GONE
            empty.visibility = View.VISIBLE
            empty.text = "🔒 Locked. Press OK on the category to enter your PIN."
            pendingFocus = false
            if (focus) categories.requestFocus()
            return
        }
        Repo.list(k, categoryId) { list ->
            if (kind == k && currentCategory == categoryId) attach(list, index, focus)
        }
    }

    private fun attach(list: PagedItems, index: Int, focus: Boolean) {
        val k = kind ?: return
        paged = list
        val live = k == Kind.LIVE
        val params = items.layoutParams as FrameLayout.LayoutParams
        params.width = if (live) activity.dp(400) else ViewGroup.LayoutParams.MATCH_PARENT
        items.layoutParams = params
        items.setNumColumns(if (live) 1 else 5)
        items.setHorizontalSpacing(if (live) 0 else activity.dp(10))
        items.setVerticalSpacing(if (live) activity.dp(2) else activity.dp(14))
        items.adapter = ItemAdapter(
            posters = !live,
            paged = list,
            onClick = { item, i ->
                Repo.prefs.edit().putInt("idx_${item.kind.code}", i).apply()
                onOpen(item, i, list)
            },
            onFocus = if (live) ::onChannelFocus else null,
            onLongClick = { item, _ -> toggleFavorite(item) },
        )
        val target = index.coerceIn(0, (list.count - 1).coerceAtLeast(0))
        if (list.count > 0) items.selectedPosition = target
        empty.visibility = if (list.count == 0) View.VISIBLE else View.GONE
        empty.text = when (currentCategory) {
            Special.FAVORITES -> "Hold OK on anything to add it here"
            Special.CONTINUE -> "What you start watching appears here"
            else -> "Nothing here"
        }
        info.visibility = if (live && list.count > 0) View.VISIBLE else View.GONE
        if (live) list.fetch(target) { it?.let(::showChannel) }
        if (focus && pendingFocus) {
            pendingFocus = false
            if (list.count > 0) items.requestFocus() else categories.requestFocus()
        }
    }

    private fun toggleFavorite(item: Item) {
        val on = Repo.toggleFavorite(item.kind, item.id)
        Toast.makeText(activity, if (on) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
    }

    private fun onChannelFocus(item: Item, index: Int) {
        if (item == infoItem) return
        Repo.prefs.edit().putInt("idx_${item.kind.code}", index).apply()
        showChannel(item)
    }

    /** The name changes on the instant; the program guide waits until the viewer stops on a channel. */
    private fun showChannel(item: Item) {
        infoItem = item
        infoName.text = item.name
        loadImage(infoLogo, item.icon, activity.dp(96), activity.dp(64))
        infoNowLabel.visibility = View.GONE
        infoProgress.visibility = View.GONE
        infoNow.text = ""
        infoDesc.text = ""
        infoNext.text = ""
        infoCatchup.visibility = if (item.archiveDays > 0) View.VISIBLE else View.GONE
        infoCatchup.text = "▶  Catch-up: last ${item.archiveDays} days"
        root.removeCallbacks(pendingEpg)
        root.postDelayed(pendingEpg, 300)
    }

    private fun loadEpg(item: Item) {
        Repo.epg(item.id) { list ->
            if (infoItem != item) return@epg
            val now = System.currentTimeMillis() / 1000
            val current = list.firstOrNull { now in it.start until it.stop } ?: list.firstOrNull() ?: return@epg
            infoNowLabel.visibility = View.VISIBLE
            infoNow.text = current.title
            infoDesc.text = current.description
            if (current.stop > current.start && now in current.start until current.stop) {
                infoProgress.visibility = View.VISIBLE
                infoProgress.progress = ((now - current.start) * 1000 / (current.stop - current.start)).toInt()
            }
            val next = list.firstOrNull { it.start >= current.stop && it !== current }
            infoNext.text = next?.let { "Next  ${clock(it.start)}  ${it.title}" }.orEmpty()
        }
    }

    /** Back from the player: follow the channel the viewer zapped to, and refresh the lists that may have changed. */
    fun onReturn() {
        val k = kind ?: return
        if (root.visibility != View.VISIBLE) return
        if (lockVersion != Lock.version) {
            show(k, false)
            return
        }
        if (currentCategory == Special.FAVORITES || currentCategory == Special.CONTINUE) {
            val cat = currentCategory ?: return
            val index = items.selectedPosition.coerceAtLeast(0)
            val hadFocus = items.hasFocus()
            pendingFocus = hadFocus
            Repo.list(k, cat) { list ->
                if (kind == k && currentCategory == cat) attach(list, index, hadFocus)
            }
            return
        }
        PlayerActivity.takeLastLiveIndex()?.let { index ->
            if (k == Kind.LIVE && index < (paged?.count ?: 0)) items.selectedPosition = index
        }
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text) {
            init {
                text.setOnFocusChangeListener { _, has ->
                    if (has) {
                        root.removeCallbacks(pendingCategory)
                        root.postDelayed(pendingCategory, 140)
                    }
                }
                text.setOnClickListener {
                    categoryList.getOrNull(bindingAdapterPosition)?.let { c ->
                        root.removeCallbacks(pendingCategory)
                        val k = kind ?: return@let
                        if (Lock.isLockedNow(k, c.id)) {
                            PinDialog.verify(activity, "Enter PIN for ${c.name}") {
                                Lock.unlock(k, c.id)
                                lockVersion = Lock.version
                                currentCategory = null
                                categoryAdapter.notifyDataSetChanged()
                                pendingFocus = true
                                select(c.id, 0, focus = true)
                            }
                            return@let
                        }
                        pendingFocus = true
                        if (c.id == currentCategory) {
                            pendingFocus = false
                            focusItems()
                        } else {
                            select(c.id, 0, focus = true)
                        }
                    }
                }
            }
        }

        override fun getItemCount() = categoryList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
        )

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = categoryList[position]
            val k = kind
            h.text.text = if (k != null && Lock.isLockedNow(k, c.id)) "🔒 ${c.name}" else c.name
            h.text.isActivated = c.id == currentCategory
        }

        fun notifyActivated() {
            for (i in 0 until categories.childCount) {
                val child = categories.getChildAt(i)
                val pos = categories.getChildAdapterPosition(child)
                child.isActivated = categoryList.getOrNull(pos)?.id == currentCategory
            }
        }
    }
}

fun dayAndClock(epochSeconds: Long): String =
    java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochSeconds * 1000))

fun clock(epochSeconds: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochSeconds * 1000))
