package com.moe.zap.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.moe.zap.R
import com.moe.zap.core.Kind
import com.moe.zap.data.Item
import com.moe.zap.data.PagedItems
import com.moe.zap.data.Repo

/**
 * Starts a title with an invisible left-to-right mark, so Arabic and English titles line up on the same edge.
 * The letters still read correctly. textDirection would be cleaner, but Android ignores it without supportsRtl.
 */
fun lineUp(title: String) = "\u200E$title"

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private object RoundedOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, 6 * view.resources.displayMetrics.density)
    }
}

/** 16-bit images at exactly the displayed size: a poster costs about 150 KB of memory, not several MB. */
fun loadImage(view: ImageView, url: String, width: Int, height: Int) {
    val glide = Glide.with(view)
    if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
        glide.clear(view)
        view.setImageDrawable(null)
        return
    }
    glide.load(url)
        .format(DecodeFormat.PREFER_RGB_565)
        .override(width, height)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .dontAnimate()
        .placeholder(ColorDrawable(0))
        .into(view)
}

/** Shared tuning for every list: no animations, no layout surprises, and no image work while the list is flying past. */
fun VerticalGridView.tune() {
    itemAnimator = null
    setHasFixedSize(true)
    setItemViewCacheSize(8)
    focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ALIGNED
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
            val glide = Glide.with(rv)
            if (state == RecyclerView.SCROLL_STATE_IDLE) glide.resumeRequests() else glide.pauseRequests()
        }
    })
}

/** A long list of channels or posters. Rows bind from whichever page is in memory and fill in when one arrives. */
class ItemAdapter(
    private val posters: Boolean,
    private val paged: PagedItems,
    private val onClick: (Item, Int) -> Unit,
    private val onFocus: ((Item, Int) -> Unit)? = null,
    private val onLongClick: ((Item, Int) -> Unit)? = null,
) : RecyclerView.Adapter<ItemAdapter.Holder>() {

    init {
        paged.onPageLoaded = { start, size ->
            if (size > 0) notifyItemRangeChanged(start, size, FILL)
        }
    }

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val star: View = v.findViewById(R.id.star)
        val label: TextView? = v.findViewById(R.id.label)
        val logo: ImageView? = v.findViewById(R.id.logo)
        val poster: ImageView? = v.findViewById(R.id.poster)
        val fallback: TextView? = v.findViewById(R.id.fallback)
        var boundUrl: String? = null

        init {
            poster?.let {
                (it.parent as View).outlineProvider = RoundedOutline
                (it.parent as View).clipToOutline = true
            }
            v.setOnClickListener {
                val p = bindingAdapterPosition
                paged.peek(p)?.let { item -> onClick(item, p) }
            }
            v.setOnLongClickListener {
                val p = bindingAdapterPosition
                val item = paged.peek(p)
                if (item != null && onLongClick != null) {
                    onLongClick.invoke(item, p)
                    bindStar(item)
                    true
                } else {
                    false
                }
            }
            v.setOnFocusChangeListener { view, has ->
                if (posters) {
                    val s = if (has) 1.07f else 1f
                    view.animate().scaleX(s).scaleY(s).setDuration(90).start()
                }
                title.isSelected = has
                if (has) {
                    val p = bindingAdapterPosition
                    paged.peek(p)?.let { item -> onFocus?.invoke(item, p) }
                }
            }
        }

        fun bindStar(item: Item) {
            star.visibility = if (Repo.isFavorite(item.kind, item.id)) View.VISIBLE else View.GONE
        }
    }

    override fun getItemCount() = paged.count

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (posters) R.layout.item_poster else R.layout.item_row
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(h: Holder, position: Int, payloads: MutableList<Any>) = onBindViewHolder(h, position)

    override fun onBindViewHolder(h: Holder, position: Int) {
        val item = paged.peek(position)
        if (item == null) {
            h.title.text = ""
            h.label?.text = ""
            h.fallback?.text = ""
            h.star.visibility = View.GONE
            h.boundUrl = null
            (h.poster ?: h.logo)?.let { Glide.with(it).clear(it) }
            return
        }
        h.title.text = lineUp(item.name)
        h.bindStar(item)
        h.label?.text = if (item.number > 0) item.number.toString() else ""
        h.fallback?.text = item.name
        if (h.boundUrl != item.icon) {
            h.boundUrl = item.icon
            val ctx = h.itemView.context
            h.poster?.let { loadImage(it, item.icon, ctx.dp(116), ctx.dp(170)) }
            h.logo?.let { loadImage(it, item.icon, ctx.dp(44), ctx.dp(30)) }
        }
        val p = h.bindingAdapterPosition
        if (h.itemView.hasFocus() && p >= 0) onFocus?.invoke(item, p)
    }

    override fun onViewRecycled(h: Holder) {
        h.boundUrl = null
        h.itemView.animate().cancel()
        h.itemView.scaleX = 1f
        h.itemView.scaleY = 1f
    }

    private companion object {
        val FILL = Any()
    }
}

/** A mixed list of search results, each labeled with what it is. */
class ResultAdapter(
    private val onClick: (Item) -> Unit,
    private val onLongClick: (Item) -> Unit,
) : RecyclerView.Adapter<ResultAdapter.Holder>() {
    var items: List<Item> = emptyList()
        private set

    fun submit(list: List<Item>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val label: TextView = v.findViewById(R.id.label)
        val logo: ImageView = v.findViewById(R.id.logo)
        val star: View = v.findViewById(R.id.star)

        init {
            v.setOnClickListener { items.getOrNull(bindingAdapterPosition)?.let(onClick) }
            v.setOnLongClickListener {
                items.getOrNull(bindingAdapterPosition)?.let {
                    onLongClick(it)
                    star.visibility = if (Repo.isFavorite(it.kind, it.id)) View.VISIBLE else View.GONE
                }
                true
            }
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val item = items[position]
        h.title.text = lineUp(item.name)
        h.label.text = when (item.kind) {
            Kind.LIVE -> "LIVE"
            Kind.MOVIE -> "FILM"
            Kind.SERIES -> "SHOW"
        }
        h.star.visibility = if (Repo.isFavorite(item.kind, item.id)) View.VISIBLE else View.GONE
        val ctx = h.itemView.context
        loadImage(h.logo, item.icon, ctx.dp(44), ctx.dp(30))
    }
}
