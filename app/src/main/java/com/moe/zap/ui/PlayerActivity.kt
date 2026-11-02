package com.moe.zap.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.leanback.widget.VerticalGridView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.RecyclerView
import com.moe.zap.R
import com.moe.zap.data.Item
import com.moe.zap.data.PagedItems
import com.moe.zap.data.Repo

/**
 * One player for the whole screen's life: zapping reuses it, and the old stream stops first, because most
 * subscriptions only allow one connection at a time.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity(), Player.Listener {

    data class Vod(
        val id: Long,
        val kind: Int,
        val parent: Long,
        val title: String,
        val subtitle: String,
        val ext: String,
        val url: String? = null,
    )

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var spinner: View
    private lateinit var message: View
    private lateinit var messageText: TextView
    private lateinit var overlay: View
    private lateinit var liveBadge: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySub: TextView
    private lateinit var overlayTime: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var overlayHint: TextView
    private lateinit var tracksPanel: View
    private lateinit var tracksList: VerticalGridView
    private lateinit var channelPanel: View
    private lateinit var channelList: VerticalGridView
    private var followInList = true
    private var watchingIndex = -1
    private var previousIndex = -1
    private var restoreListCallback: ((Int, Int) -> Unit)? = null
    private var sleepMinutes = 0
    private val sleep = Runnable { finish() }

    private var isLive = false
    private var retries = 0
    private var currentLive: Item? = null
    private var liveIndex = 0
    private var liveList: PagedItems? = null
    private var vodQueue: List<Vod> = emptyList()
    private var vodIndex = 0
    private var startAtMs = 0L
    private var seekPreviewMs = -1L
    private var lastError: String? = null
    private var lastStreamAt = 0L

    // Decoders that have failed on the current stream. The next attempt uses the next one, usually software.
    private val failedDecoders = HashSet<String>()
    private val ui get() = window.decorView

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val showSpinner = Runnable { spinner.visibility = View.VISIBLE }

    // Only sustained playback earns back the retries. Otherwise a stream that dies seconds after starting
    // would reconnect forever instead of telling the viewer.
    private val clearRetries = Runnable { retries = 0 }
    private val reconnect = Runnable { restartStream() }

    // A live stream can stall without ever reporting an error. Waiting this long means it is gone.
    private val stallWatchdog = Runnable { if (isLive) onStreamFailure() }
    private val startLive = Runnable {
        // Only a channel actually tuned to counts as "last channel", not those skipped past on the way.
        if (liveIndex != watchingIndex) {
            previousIndex = watchingIndex
            watchingIndex = liveIndex
        }
        currentLive?.let(::prepareLive)
    }
    private val commitSeek = Runnable {
        if (seekPreviewMs >= 0) {
            player.seekTo(seekPreviewMs)
            seekPreviewMs = -1
            refreshVodOverlay()
            scheduleHide()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!isLive) {
                if (overlay.visibility == View.VISIBLE) refreshVodOverlay()
                saveProgressCounter++
                if (saveProgressCounter % 15 == 0) saveProgress()
            }
            ui.postDelayed(this, 1000)
        }
    }
    private var saveProgressCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        frame = findViewById(R.id.frame)
        spinner = findViewById(R.id.spinner)
        message = findViewById(R.id.message)
        messageText = findViewById(R.id.messageText)
        overlay = findViewById(R.id.overlay)
        liveBadge = findViewById(R.id.liveBadge)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlaySub = findViewById(R.id.overlaySub)
        overlayTime = findViewById(R.id.overlayTime)
        overlayProgress = findViewById(R.id.overlayProgress)
        overlayHint = findViewById(R.id.overlayHint)
        tracksPanel = findViewById(R.id.tracksPanel)
        tracksList = findViewById(R.id.tracks)
        tracksList.tune()
        channelPanel = findViewById(R.id.channelPanel)
        channelList = findViewById(R.id.channelList)
        channelList.tune()
        channelList.setVerticalSpacing(dp(2))

        isLive = pendingLive != null
        buildPlayer()

        val live = pendingLive
        if (live != null) {
            liveList = pendingLiveList
            liveIndex = pendingLiveIndex
            watchingIndex = liveIndex
            followInList = pendingFollow
            restoreListCallback = liveList?.onPageLoaded
            lastLiveIndex = null
            showLive(live)
            prepareLive(live)
        } else {
            vodQueue = pendingVod
            vodIndex = pendingVodIndex
            startAtMs = pendingStartAt
            if (vodQueue.isEmpty()) {
                finish()
                return
            }
            prepareVod()
        }
        pendingLive = null
        pendingLiveList = null
        ui.post(tick)
    }

    private fun buildPlayer() {
        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setPreferredAudioLanguages("ar", "en")
                .setSelectUndeterminedTextLanguage(false)
                .build()
        }
        // Live starts as soon as a fraction of a second is buffered. Movies buffer more, for uninterrupted viewing.
        val load = DefaultLoadControl.Builder().apply {
            if (isLive) {
                setBufferDurationsMs(6_000, 20_000, 500, 1_500)
            } else {
                setBufferDurationsMs(20_000, 50_000, 1_000, 2_500)
            }
            setBackBuffer(0, false)
            setPrioritizeTimeOverSizeThresholds(false)
        }.build()
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        val data = OkHttpDataSource.Factory(Repo.http).setUserAgent(Repo.USER_AGENT)
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector { mime, secure, tunneling ->
                        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                            .filter { it.name !in failedDecoders }
                    }
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(load)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(data, extractors).setLoadErrorHandlingPolicy(NoRetryWhenLimited)
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        player.setVideoSurfaceView(findViewById<SurfaceView>(R.id.surface))
        val subtitles = findViewById<SubtitleView>(R.id.subtitles)
        player.addListener(object : Player.Listener {
            override fun onCues(cues: androidx.media3.common.text.CueGroup) = subtitles.setCues(cues.cues)
        })
        player.addListener(this)
        player.playWhenReady = true
    }

    // ---- live ----

    /** While the provider has asked us to wait, playback asks it for nothing and says so instead. */
    private fun waitingForProvider(): Boolean {
        if (!Repo.isQuiet()) return false
        lastError = "HTTP 429"
        ui.removeCallbacks(reconnect)
        player.stop()
        showError("The provider is limiting requests. Try again in ${Repo.quietSecondsLeft()} seconds: press OK.")
        return true
    }

    private fun prepareLive(item: Item) {
        if (waitingForProvider()) return
        lastStreamAt = System.currentTimeMillis()
        lastError = null
        hideMessage()
        startSpinnerTimer()
        val format = Repo.liveFormat()
        Repo.bg({ Repo.urls().live(item.id, format) }) { r ->
            r.onSuccess { url ->
                if (currentLive != item || isFinishing) return@onSuccess
                val media = MediaItem.Builder().setUri(Uri.parse(url))
                if (format == "m3u8") media.setMimeType(MimeTypes.APPLICATION_M3U8)
                player.setMediaItem(media.build())
                player.prepare()
                player.play()
            }.onFailure { showError("Could not start this channel.") }
        }
    }

    private fun showLive(item: Item) {
        currentLive = item
        liveBadge.visibility = View.VISIBLE
        overlayTitle.text = lineUp(if (item.number > 0) "${item.number}   ${item.name}" else item.name)
        overlaySub.text = ""
        overlayTime.text = ""
        overlayProgress.visibility = View.GONE
        overlayHint.text = "▲ ▼  Change channel    ◀  All channels    ▶  Last channel    Hold OK  Options"
        showOverlay()
        Repo.epg(item.id) { list ->
            if (currentLive != item) return@epg
            val now = System.currentTimeMillis() / 1000
            val current = list.firstOrNull { now in it.start until it.stop } ?: return@epg
            overlaySub.text = current.title
            overlayTime.text = "${clock(current.start)} – ${clock(current.stop)}"
            if (current.stop > current.start) {
                overlayProgress.visibility = View.VISIBLE
                overlayProgress.progress = ((now - current.start) * 1000 / (current.stop - current.start)).toInt()
            }
        }
    }

    private fun zap(delta: Int) {
        val list = liveList ?: return
        if (list.count <= 1) return
        goToChannel((liveIndex + delta + list.count) % list.count)
    }

    private fun goToChannel(index: Int) {
        val list = liveList ?: return
        liveIndex = index
        retries = 0
        ui.removeCallbacks(clearRetries)
        failedDecoders.clear()
        // The name updates on the instant. The stream waits a moment, so holding the button skips channels
        // without opening a connection for each one.
        ui.removeCallbacks(startLive)
        ui.removeCallbacks(reconnect)
        ui.removeCallbacks(stallWatchdog)
        hideMessage()
        player.stop()
        list.fetch(index) { item ->
            if (item != null && index == liveIndex) {
                showLive(item)
                if (followInList) lastLiveIndex = index
                ui.removeCallbacks(startLive)
                // Quick to react, but streams never open faster than one per STREAM_SPACING_MS.
                val sinceLast = System.currentTimeMillis() - lastStreamAt
                ui.postDelayed(startLive, maxOf(180L, STREAM_SPACING_MS - sinceLast))
            }
        }
    }

    private fun openChannels() {
        val list = liveList ?: return
        if (list.count == 0) return
        channelList.adapter = ItemAdapter(
            posters = false,
            paged = list,
            onClick = { _, i ->
                channelPanel.visibility = View.GONE
                if (i != liveIndex) goToChannel(i)
            },
            onLongClick = { item, _ -> Repo.toggleFavorite(item.kind, item.id) },
        )
        channelList.selectedPosition = liveIndex
        overlay.visibility = View.GONE
        channelPanel.visibility = View.VISIBLE
        channelList.requestFocus()
    }

    // ---- movies and episodes ----

    private fun prepareVod() {
        if (waitingForProvider()) return
        val v = vodQueue[vodIndex]
        hideMessage()
        startSpinnerTimer()
        overlayTitle.text = v.title
        overlaySub.text = v.subtitle
        liveBadge.visibility = View.GONE
        overlayHint.text = "OK  Pause    ◀ ▶  Seek    ▼  Audio & subtitles"
        overlayProgress.visibility = View.VISIBLE
        showOverlay()
        Repo.bg({
            val u = Repo.urls()
            if (v.url != null) v.url else if (v.kind == Repo.EPISODE_KIND) u.episode(v.id, v.ext) else u.movie(v.id, v.ext)
        }) { r ->
            r.onSuccess { url ->
                player.setMediaItem(MediaItem.fromUri(url), startAtMs)
                player.prepare()
                player.play()
                startAtMs = 0
            }.onFailure { showError("Could not start this title.") }
        }
    }

    private fun refreshVodOverlay() {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = if (seekPreviewMs >= 0) seekPreviewMs else player.currentPosition
        overlayTime.text = if (dur > 0) {
            "${DetailActivity.formatTime(pos)} / ${DetailActivity.formatTime(dur)}"
        } else {
            DetailActivity.formatTime(pos)
        }
        overlayProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
    }

    private fun seekBy(deltaMs: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val from = if (seekPreviewMs >= 0) seekPreviewMs else player.currentPosition
        seekPreviewMs = (from + deltaMs).coerceIn(0, dur - 1000)
        showOverlay()
        refreshVodOverlay()
        ui.removeCallbacks(hideOverlay)
        ui.removeCallbacks(commitSeek)
        ui.postDelayed(commitSeek, 450)
    }

    private fun saveProgress() {
        if (isLive || vodQueue.isEmpty() || vodQueue[vodIndex].kind == CATCHUP_KIND) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val v = vodQueue[vodIndex]
        Repo.saveProgress(v.kind, v.id, v.parent, player.currentPosition, dur)
    }

    // ---- audio and subtitle choice ----

    private class TrackRow(val label: String, val selected: Boolean, val apply: () -> Unit)

    private fun openTracks() {
        val rows = ArrayList<TrackRow>()
        val groups = player.currentTracks.groups
        val hasText = groups.any { it.type == C.TRACK_TYPE_TEXT }
        for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            val prefix = if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"
            for (g in groups) {
                if (g.type != type) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val language = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED && it != "mul" }
                    val name = f.label ?: language?.let { java.util.Locale(it).displayLanguage } ?: "Track ${i + 1}"
                    rows += TrackRow("$prefix: $name", g.isTrackSelected(i)) { selectTrack(g, i) }
                }
            }
        }
        if (hasText) {
            val anyOn = groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            rows += TrackRow("Subtitles: Off", !anyOn) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }
        for (minutes in listOf(0, 30, 60, 90, 120)) {
            val name = if (minutes == 0) "Off" else "$minutes min"
            rows += TrackRow("Stop playing after: $name", minutes == sleepMinutes) {
                sleepMinutes = minutes
                ui.removeCallbacks(sleep)
                if (minutes > 0) ui.postDelayed(sleep, minutes * 60_000L)
            }
        }
        tracksList.adapter = TrackAdapter(rows)
        tracksList.selectedPosition = rows.indexOfFirst { it.selected }.coerceAtLeast(0)
        tracksPanel.visibility = View.VISIBLE
        overlay.visibility = View.GONE
        tracksList.requestFocus()
    }

    private fun selectTrack(group: Tracks.Group, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private inner class TrackAdapter(val rows: List<TrackRow>) : RecyclerView.Adapter<TrackAdapter.Holder>() {
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text) {
            init {
                text.setOnClickListener {
                    rows.getOrNull(bindingAdapterPosition)?.apply?.invoke()
                    tracksPanel.visibility = View.GONE
                }
            }
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView)

        override fun onBindViewHolder(h: Holder, position: Int) {
            h.text.text = rows[position].label
            h.text.isActivated = rows[position].selected
        }
    }

    // ---- overlays ----

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        if (!isLive) refreshVodOverlay()
        scheduleHide()
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideOverlay)
        ui.postDelayed(hideOverlay, 4000)
    }

    private fun startSpinnerTimer() {
        ui.removeCallbacks(showSpinner)
        // Quick starts should not flash a spinner at all.
        ui.postDelayed(showSpinner, 400)
    }

    private fun stopSpinner() {
        ui.removeCallbacks(showSpinner)
        spinner.visibility = View.GONE
    }

    private fun hideMessage() {
        message.visibility = View.GONE
    }

    private fun showError(text: String) {
        stopSpinner()
        messageText.text = text
        message.visibility = View.VISIBLE
    }

    private fun retryNow() {
        lastError = null
        hideMessage()
        startSpinnerTimer()
        retries = 0
        restartStream()
    }

    private fun restartStream() {
        if (isFinishing) return
        val live = currentLive
        if (isLive && live != null) {
            // prepare() does nothing once a stream has "ended", which is how a dropped live stream looks.
            prepareLive(live)
        } else if (player.mediaItemCount == 0) {
            prepareVod()
        } else if (!waitingForProvider()) {
            player.prepare()
            player.play()
        }
    }

    // ---- player events ----

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> {
                startSpinnerTimer()
                ui.removeCallbacks(stallWatchdog)
                ui.postDelayed(stallWatchdog, STALL_LIMIT_MS)
            }
            Player.STATE_READY -> {
                stopSpinner()
                ui.removeCallbacks(stallWatchdog)
                ui.removeCallbacks(clearRetries)
                ui.postDelayed(clearRetries, 15_000)
                hideMessage()
            }
            Player.STATE_ENDED -> {
                stopSpinner()
                ui.removeCallbacks(stallWatchdog)
                if (isLive) {
                    // A live stream "ending" is the provider dropping the connection. Reconnect.
                    onStreamFailure()
                } else {
                    saveFinished()
                    if (vodIndex + 1 < vodQueue.size) {
                        vodIndex++
                        startAtMs = 0
                        retries = 0
                        failedDecoders.clear()
                        prepareVod()
                    } else {
                        finish()
                    }
                }
            }
            else -> Unit
        }
    }

    private fun saveFinished() {
        val v = vodQueue[vodIndex]
        if (v.kind == CATCHUP_KIND) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        Repo.saveProgress(v.kind, v.id, v.parent, dur, dur)
    }

    override fun onPlayerError(error: PlaybackException) {
        lastError = error.errorCodeName.removePrefix("ERROR_CODE_")
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        if (http != null) lastError = "HTTP $http"
        android.util.Log.w("ZapNet", "playback failed: $lastError, retry $retries")
        (error.cause as? MediaCodecDecoderException)?.codecInfo?.name?.let { failedDecoders += it }
        onStreamFailure()
    }

    private fun onStreamFailure() {
        ui.removeCallbacks(clearRetries)
        ui.removeCallbacks(stallWatchdog)
        ui.removeCallbacks(reconnect)
        if (lastError == "HTTP 429") {
            // Reconnecting would only prolong the block. Stop and let the viewer choose when to try again.
            ui.removeCallbacks(reconnect)
            player.stop()
            showError("The provider is limiting requests. Try again in ${Repo.quietSecondsLeft()} seconds: press OK.")
            return
        }
        if (retries >= MAX_RETRIES) {
            player.stop()
            val why = lastError?.let { " ($it)" }.orEmpty()
            showError((if (isLive) "This channel is not available right now." else "This title could not be played.") + why)
            return
        }
        retries++
        startSpinnerTimer()
        ui.postDelayed(reconnect, 800L * retries)
    }

    override fun onVideoSizeChanged(size: VideoSize) {
        if (size.height > 0 && size.width > 0) {
            frame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
        }
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tracksPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                tracksPanel.visibility = View.GONE
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
            return super.dispatchKeyEvent(event)
        }
        if (channelPanel.visibility == View.VISIBLE) {
            val closeKey = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (closeKey) {
                if (event.action == KeyEvent.ACTION_UP) channelPanel.visibility = View.GONE
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // Live has no use for a long OK otherwise, and its D-pad is taken by channel changing.
                if (isLive && event.repeatCount == 1) {
                    openTracks()
                    return true
                }
                if (event.repeatCount > 0) return true
                if (message.visibility == View.VISIBLE) {
                    retryNow()
                } else if (isLive) {
                    if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE else showOverlay()
                } else {
                    if (player.isPlaying) player.pause() else player.play()
                    showOverlay()
                    if (!player.playWhenReady) ui.removeCallbacks(hideOverlay)
                }
                return true
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (isLive && previousIndex >= 0) goToChannel(previousIndex)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) openTracks()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player.pause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (isLive) zap(1) else showOverlay()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (isLive) zap(-1) else openTracks()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (isLive) openChannels() else seekBy(-seekStep(event))
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (isLive) {
                    if (previousIndex >= 0 && event.repeatCount == 0) goToChannel(previousIndex)
                } else {
                    seekBy(seekStep(event))
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Holding the button speeds up: 10 seconds a step at first, a minute a step before long.
    private fun seekStep(e: KeyEvent) = when {
        e.repeatCount > 24 -> 60_000L
        e.repeatCount > 6 -> 30_000L
        else -> 10_000L
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        // Leaving the screen frees the subscription's connection right away.
        finish()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(startLive)
        ui.removeCallbacks(commitSeek)
        ui.removeCallbacks(hideOverlay)
        ui.removeCallbacks(showSpinner)
        ui.removeCallbacks(clearRetries)
        ui.removeCallbacks(reconnect)
        ui.removeCallbacks(stallWatchdog)
        ui.removeCallbacks(sleep)
        // The list went back to the browse screen's adapter.
        liveList?.onPageLoaded = restoreListCallback
        if (this::player.isInitialized) player.release()
        super.onDestroy()
    }

    /** The player normally retries failed requests by itself. A 429 means stop, and retrying prolongs the block. */
    private object NoRetryWhenLimited : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val limited = generateSequence<Throwable>(info.exception) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .any { it.responseCode == 429 }
            return if (limited) C.TIME_UNSET else super.getRetryDelayMsFor(info)
        }
    }

    companion object {
        private const val STREAM_SPACING_MS = 800L
        const val CATCHUP_KIND = 4
        private const val MAX_RETRIES = 3
        private const val STALL_LIMIT_MS = 12_000L

        private var pendingLive: Item? = null
        private var pendingLiveList: PagedItems? = null
        private var pendingLiveIndex = 0
        private var pendingFollow = true
        private var pendingVod: List<Vod> = emptyList()
        private var pendingVodIndex = 0
        private var pendingStartAt = 0L
        private var lastLiveIndex: Int? = null

        fun playLive(from: Context, list: PagedItems?, index: Int, item: Item, followInList: Boolean) {
            pendingLive = item
            pendingFollow = followInList
            pendingLiveList = list
            pendingLiveIndex = index.coerceAtLeast(0)
            from.startActivity(Intent(from, PlayerActivity::class.java))
        }

        fun playVod(from: Context, queue: List<Vod>, index: Int, startAtMs: Long) {
            pendingLive = null
            pendingVod = queue
            pendingVodIndex = index
            pendingStartAt = startAtMs
            from.startActivity(Intent(from, PlayerActivity::class.java))
        }

        /** The channel zapped to, once, so the list can follow it on return. */
        fun takeLastLiveIndex(): Int? = lastLiveIndex.also { lastLiveIndex = null }
    }
}
