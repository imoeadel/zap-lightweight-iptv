package com.moe.zap.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.moe.zap.R
import com.moe.zap.core.Episode
import com.moe.zap.core.Kind
import com.moe.zap.data.Item
import com.moe.zap.data.Repo

/** Opens at once from what is already on the TV; plot and episodes fill in as the server answers. */
class DetailActivity : Activity() {
    private lateinit var item: Item
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var cast: TextView
    private lateinit var play: TextView
    private lateinit var fav: TextView
    private lateinit var seasons: HorizontalGridView
    private lateinit var episodesView: VerticalGridView
    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView

    private var extension = ""
    private var allEpisodes: List<Episode> = emptyList()
    private var seasonNumbers: List<Int> = emptyList()
    private var shown: List<Episode> = emptyList()
    private var resume: Map<Long, Repo.Resume> = emptyMap()
    private var latestEpisode: Long? = null
    private var selectedSeason = -1
    private var seriesFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val it = pending
        if (it == null) {
            finish()
            return
        }
        item = it
        setContentView(R.layout.activity_detail)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        plot = findViewById(R.id.plot)
        cast = findViewById(R.id.cast)
        play = findViewById(R.id.play)
        fav = findViewById(R.id.fav)
        seasons = findViewById(R.id.seasons)
        episodesView = findViewById(R.id.episodes)
        backdrop = findViewById(R.id.backdrop)
        poster = findViewById(R.id.poster)

        title.text = item.name
        extension = item.ext
        loadImage(poster, item.icon, dp(176), dp(260))
        updateFav()
        fav.setOnClickListener {
            Repo.toggleFavorite(item.kind, item.id)
            updateFav()
        }
        play.setOnClickListener { playMain() }
        play.requestFocus()

        if (item.kind == Kind.SERIES) {
            seasons.visibility = View.VISIBLE
            episodesView.visibility = View.VISIBLE
            episodesView.tune()
            episodesView.setVerticalSpacing(dp(2))
            seasons.setHorizontalSpacing(dp(4))
            seasons.adapter = SeasonAdapter()
            episodesView.adapter = EpisodeAdapter()
            play.text = "Loading…"
            loadSeries()
        } else {
            loadMovie()
        }
    }

    override fun onResume() {
        super.onResume()
        if (item.kind == Kind.SERIES) {
            if (allEpisodes.isNotEmpty()) refreshResume()
        } else {
            refreshResume()
        }
    }

    private fun updateFav() {
        fav.text = if (Repo.isFavorite(item.kind, item.id)) "★ In favorites" else "Add to favorites"
    }

    private fun setMeta(vararg parts: String) {
        meta.text = parts.filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    private fun loadMovie() {
        Repo.movieInfo(item.id) { r ->
            r.onSuccess { i ->
                if (i.extension.isNotEmpty()) extension = i.extension
                setMeta(i.year, i.rating.ifEmpty { item.rating }.let { if (it.isEmpty()) "" else "★ $it" }, i.duration, i.genre)
                plot.text = i.plot
                cast.text = i.cast
                loadImage(backdrop, i.backdrop, dp(480), dp(270))
                if (item.icon.isEmpty()) loadImage(poster, i.poster, dp(176), dp(260))
            }
        }
    }

    private fun loadSeries() {
        seriesFailed = false
        Repo.seriesInfo(item.id) { r ->
            r.onSuccess { i ->
                setMeta(i.year, i.rating.ifEmpty { item.rating }.let { if (it.isEmpty()) "" else "★ $it" }, i.genre)
                plot.text = i.plot
                cast.text = i.cast
                loadImage(backdrop, i.backdrop, dp(480), dp(270))
                if (item.icon.isEmpty()) loadImage(poster, i.poster, dp(176), dp(260))
                allEpisodes = i.episodes
                seasonNumbers = i.episodes.map { it.season }.distinct()
                seasons.adapter?.notifyDataSetChanged()
                if (allEpisodes.isEmpty()) {
                    play.text = "No episodes yet"
                } else {
                    refreshResume()
                }
            }.onFailure { e ->
                android.util.Log.w("ZapNet", "series info failed: ${Repo.reason(e)}")
                seriesFailed = true
                play.text = if (Repo.reason(e) == "HTTP 429") {
                    "Provider is busy. Press to retry"
                } else {
                    "Could not load episodes. Press to retry"
                }
            }
        }
    }

    private fun refreshResume() {
        val kind = if (item.kind == Kind.SERIES) Repo.EPISODE_KIND else Kind.MOVIE.code
        Repo.resumePoints(kind, item.id) { map, latest ->
            resume = map
            latestEpisode = latest
            if (item.kind == Kind.SERIES) {
                val next = currentEpisode()
                if (next != null) {
                    selectSeason(next.season)
                    episodesView.adapter?.notifyDataSetChanged()
                    val mid = map[next.id]?.let { !it.finished } == true
                    val verb = when {
                        mid -> "Continue"
                        latest != null && map[next.id]?.finished == true -> "Watch again"
                        latest != null -> "Next:"
                        else -> "Play"
                    }
                    play.text = "$verb S${next.season} E${next.number}"
                }
            } else {
                val r = map[item.id]
                play.text = if (r != null) "Resume from ${formatTime(r.positionMs)}" else "Play"
            }
        }
    }

    /** What Play should start: the unfinished episode, or the one after the last finished, or the first. */
    private fun currentEpisode(): Episode? {
        val index = allEpisodes.indexOfFirst { it.id == latestEpisode }
        if (index < 0) return allEpisodes.firstOrNull()
        val latest = allEpisodes[index]
        return if (resume[latest.id]?.finished == true) allEpisodes.getOrNull(index + 1) ?: latest else latest
    }

    private fun selectSeason(season: Int) {
        if (season == selectedSeason) return
        selectedSeason = season
        shown = allEpisodes.filter { it.season == season }
        episodesView.adapter?.notifyDataSetChanged()
        val i = seasonNumbers.indexOf(season)
        if (i >= 0) seasons.selectedPosition = i
        seasons.adapter?.notifyDataSetChanged()
    }

    private fun playMain() {
        if (seriesFailed) {
            play.text = "Loading…"
            loadSeries()
            return
        }
        if (item.kind == Kind.SERIES) {
            currentEpisode()?.let(::playEpisode)
        } else {
            val url = runCatching { Repo.urls().movie(item.id, extension) }
            // Reading the login can touch the Keystore; it is normally cached by now.
            url.onSuccess {
                PlayerActivity.playVod(
                    this,
                    listOf(PlayerActivity.Vod(item.id, Kind.MOVIE.code, item.id, item.name, "", extension)),
                    0,
                    resume[item.id]?.positionMs ?: 0L,
                )
            }
        }
    }

    private fun playEpisode(e: Episode) {
        val queue = allEpisodes.map {
            PlayerActivity.Vod(
                it.id, Repo.EPISODE_KIND, item.id, item.name,
                "S${it.season} E${it.number}" + if (it.title.isNotBlank()) "  ·  ${it.title}" else "",
                it.extension,
            )
        }
        val start = resume[e.id]?.takeIf { !it.finished }?.positionMs ?: 0L
        PlayerActivity.playVod(this, queue, allEpisodes.indexOf(e), start)
    }

    private inner class SeasonAdapter : RecyclerView.Adapter<SeasonAdapter.Holder>() {
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text) {
            init {
                text.setOnFocusChangeListener { _, has ->
                    if (has) seasonNumbers.getOrNull(bindingAdapterPosition)?.let(::selectSeason)
                }
            }
        }

        override fun getItemCount() = seasonNumbers.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_season, parent, false) as TextView)

        override fun onBindViewHolder(h: Holder, position: Int) {
            val s = seasonNumbers[position]
            h.text.text = "Season $s"
            h.text.isActivated = s == selectedSeason
        }
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.label)
            val titleView: TextView = v.findViewById(R.id.title)
            val logo: ImageView = v.findViewById(R.id.logo)

            init {
                v.setOnClickListener { shown.getOrNull(bindingAdapterPosition)?.let(::playEpisode) }
            }
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = shown[position]
            h.label.text = "E${e.number}"
            val mark = when (resume[e.id]?.finished) {
                true -> "✓  "
                false -> "●  "
                null -> ""
            }
            h.titleView.text = mark + e.title.ifBlank { "Episode ${e.number}" }
            loadImage(h.logo, e.image, dp(44), dp(30))
        }
    }

    companion object {
        private var pending: Item? = null

        fun open(from: Context, item: Item) {
            pending = item
            from.startActivity(Intent(from, DetailActivity::class.java))
        }

        fun formatTime(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
