package com.moe.zap.data

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import com.google.gson.stream.JsonReader
import com.moe.zap.core.Account
import com.moe.zap.core.AccountInfo
import com.moe.zap.core.Category
import com.moe.zap.core.EpgEntry
import com.moe.zap.core.Kind
import com.moe.zap.core.MovieInfo
import com.moe.zap.core.RateLimit
import com.moe.zap.core.Search
import com.moe.zap.core.SeriesInfo
import com.moe.zap.core.XtreamParser
import com.moe.zap.core.XtreamUrls
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class Item(
    val kind: Kind,
    val id: Long,
    val name: String,
    val icon: String,
    val ext: String,
    val number: Int,
    val rating: String,
    val epgId: String,
    val archiveDays: Int = 0,
)

/** Categories every section has on top of the provider's own. */
object Special {
    const val FAVORITES = "__fav"
    const val ALL = "__all"
    const val RECENT = "__recent"
    const val CONTINUE = "__continue"
}

class LoginError(val reason: Reason) : Exception(reason.name) {
    enum class Reason { WRONG_LOGIN, EXPIRED, UNREACHABLE, NOT_XTREAM }
}

object Repo {
    private const val ITEM_COLS =
        "item.kind, item.sid, item.name, item.icon, item.ext, item.num, item.rating, item.epg, item.archive"

    lateinit var creds: CredentialStore
        private set
    lateinit var db: Db
        private set
    lateinit var prefs: SharedPreferences
        private set
    lateinit var appContext: Context
        private set

    val main = Handler(Looper.getMainLooper())

    private fun lowPriority(name: String) = java.util.concurrent.ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
            r.run()
        }, name)
    }

    private val reads = Executors.newFixedThreadPool(2, lowPriority("zap-read"))
    private val searches = Executors.newSingleThreadExecutor(lowPriority("zap-search"))
    private val writes = Executors.newSingleThreadExecutor(lowPriority("zap-write"))

    // Slow servers must never hold up database reads. Three at a time also stays polite to the provider.
    private val network = Executors.newFixedThreadPool(3, lowPriority("zap-net"))

    // Many panels only serve requests from known players, so identify as one (also used for video).
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true) // Panels often redirect a stream between http and https.
            .addInterceptor { chain ->
                val request = chain.request()
                // Request type, status and timing only. URLs hold the login, so they are never logged.
                val type = request.url.queryParameter("action") ?: request.url.pathSegments.firstOrNull().orEmpty()
                val started = System.currentTimeMillis()
                try {
                    val response = chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
                    android.util.Log.w("ZapNet", "$type -> ${response.code} in ${System.currentTimeMillis() - started}ms")
                    if (response.code == 429) {
                        val wait = RateLimit.waitSeconds(response.header("Retry-After"))
                        quietUntil = System.currentTimeMillis() + wait * 1000
                        android.util.Log.w("ZapNet", "provider asked us to slow down: quiet for ${wait}s")
                    }
                    response
                } catch (e: IOException) {
                    android.util.Log.w("ZapNet", "$type -> ${e.javaClass.simpleName} in ${System.currentTimeMillis() - started}ms")
                    throw e
                }
            }
            .build()
    }

    /** While a provider's 429 is in force, nothing automatic asks it for anything. */
    @Volatile private var quietUntil = 0L

    fun isQuiet() = System.currentTimeMillis() < quietUntil

    fun quietSecondsLeft() = ((quietUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    /** Thrown instead of sending a request while the provider has asked us to wait. */
    class SlowDown : IOException("HTTP 429")

    private val favorites = HashSet<Long>()
    private val epgCache = LruCache<Long, Pair<Long, List<EpgEntry>>>(256)

    @Volatile private var cachedAccount: Account? = null
    private val accountLock = Any()

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        creds = CredentialStore(appContext)
        db = Db(appContext)
        prefs = appContext.getSharedPreferences("zap", Context.MODE_PRIVATE)
        if (creds.exists()) reads.execute {
            account()
            loadFavorites()
        }
    }

    fun <T> bg(work: () -> T, done: (Result<T>) -> Unit) {
        reads.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    private fun <T> net(work: () -> T, done: (Result<T>) -> Unit) {
        network.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    // ---- account ----

    fun account(): Account? {
        cachedAccount?.let { return it }
        synchronized(accountLock) {
            cachedAccount?.let { return it }
            return creds.load().also { cachedAccount = it }
        }
    }

    fun urls(): XtreamUrls = XtreamUrls(account() ?: throw IOException("Not logged in"))

    /** Checks the login against the server, and only stores it if the server accepts it. */
    fun login(account: Account, done: (Result<AccountInfo>) -> Unit) = bg({
        val info = fetchAccountInfo(XtreamUrls(account))
        if (!info.authenticated) throw LoginError(LoginError.Reason.WRONG_LOGIN)
        if (info.status.isNotEmpty() && !info.status.equals("active", true)) {
            throw LoginError(LoginError.Reason.EXPIRED)
        }
        val clean = account.copy(server = XtreamUrls.normalizeServer(account.server))
        creds.save(clean)
        synchronized(accountLock) { cachedAccount = clean }
        saveAccountInfo(info)
        info
    }, done)

    fun fetchAccountInfo(urls: XtreamUrls): AccountInfo {
        try {
            http.newCall(Request.Builder().url(urls.api()).build()).execute().use { res ->
                if (res.code == 401 || res.code == 403) throw LoginError(LoginError.Reason.WRONG_LOGIN)
                if (!res.isSuccessful) throw LoginError(LoginError.Reason.NOT_XTREAM)
                val body = res.body ?: throw LoginError(LoginError.Reason.NOT_XTREAM)
                JsonReader(body.charStream()).use { r ->
                    r.isLenient = true
                    return XtreamParser.accountInfo(r)
                }
            }
        } catch (e: LoginError) {
            throw e
        } catch (e: IllegalArgumentException) { // A server address OkHttp cannot parse.
            throw LoginError(LoginError.Reason.UNREACHABLE)
        } catch (e: IOException) {
            throw LoginError(
                if (e is com.google.gson.stream.MalformedJsonException || e is java.io.EOFException) {
                    LoginError.Reason.NOT_XTREAM
                } else {
                    LoginError.Reason.UNREACHABLE
                }
            )
        } catch (e: IllegalStateException) { // Valid JSON of the wrong shape, or not JSON at all.
            throw LoginError(LoginError.Reason.NOT_XTREAM)
        }
    }

    fun saveAccountInfo(info: AccountInfo) {
        prefs.edit()
            .putString("status", info.status)
            .putLong("expires", info.expiresAt)
            .putInt("maxConnections", info.maxConnections)
            .putString("liveFormat", XtreamUrls.chooseLiveFormat(info.outputFormats))
            .putString("timezone", info.timezone)
            .apply()
    }

    fun liveFormat(): String = prefs.getString("liveFormat", null) ?: "ts"

    fun logout(done: () -> Unit) = bg({
        creds.clear()
        synchronized(accountLock) { cachedAccount = null }
        synchronized(favorites) { favorites.clear() }
        Lock.forgetSession()
        epgCache.evictAll()
        prefs.edit().clear().apply()
        db.clearAll()
    }) { done() }

    // ---- catalog ----

    fun hasCatalog(done: (Boolean) -> Unit) = bg({
        db.readableDatabase.rawQuery("SELECT 1 FROM item LIMIT 1", null).use { it.moveToFirst() }
    }) { done(it.getOrDefault(false)) }

    fun categories(kind: Kind, done: (List<Category>) -> Unit) = bg({
        val out = ArrayList<Category>()
        if (kind != Kind.LIVE) out += Category(Special.CONTINUE, "Continue watching")
        out += Category(Special.FAVORITES, "Favorites")
        if (kind != Kind.LIVE) out += Category(Special.RECENT, "Recently added")
        out += Category(Special.ALL, "All")
        db.readableDatabase.rawQuery(
            "SELECT id, name FROM category WHERE kind=? ORDER BY pos", arrayOf(kind.code.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (Lock.stateOf(kind, id) != Lock.State.HIDDEN) out += Category(id, c.getString(1))
            }
        }
        out as List<Category>
    }) { done(it.getOrDefault(emptyList())) }

    fun list(kind: Kind, categoryId: String, done: (PagedItems) -> Unit) = bg({
        val k = kind.code.toString()
        val from: String
        val args: Array<String>
        var order = "item.pos"
        var cap = Int.MAX_VALUE
        when (categoryId) {
            Special.ALL -> {
                from = "item WHERE item.kind=? ${Lock.excludeClause(kind)}"
                args = arrayOf(k)
            }
            Special.FAVORITES -> {
                from = "item JOIN favorite f ON f.kind=item.kind AND f.sid=item.sid WHERE item.kind=? ${Lock.excludeClause(kind)}"
                args = arrayOf(k)
                order = "f.at DESC"
            }
            Special.RECENT -> {
                from = "item WHERE item.kind=? AND item.added>0 ${Lock.excludeClause(kind)}"
                args = arrayOf(k)
                order = "item.added DESC"
                cap = 200
            }
            Special.CONTINUE -> {
                // A movie's progress row points at itself; an episode's points at its series.
                val progressKind = if (kind == Kind.MOVIE) kind.code else EPISODE_KIND
                from = "item JOIN (SELECT parent, MAX(at) at FROM progress WHERE kind=$progressKind GROUP BY parent) p " +
                    "ON p.parent=item.sid WHERE item.kind=? ${Lock.excludeClause(kind)}"
                args = arrayOf(k)
                order = "p.at DESC"
            }
            else -> {
                from = "item WHERE item.kind=? AND item.cat=?"
                args = arrayOf(k, categoryId)
            }
        }
        val count = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $from", args).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        PagedItems(from, args, order, minOf(count, cap))
    }) { r -> r.getOrNull()?.let(done) }

    /** All channels, positioned on this one, so a channel opened from search can still change up and down. */
    fun allChannelsAt(item: Item, done: (PagedItems, Int) -> Unit) = list(Kind.LIVE, Special.ALL) { paged ->
        bg({
            db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM item WHERE kind=${Kind.LIVE.code} ${Lock.excludeClause(Kind.LIVE)} " +
                    "AND pos < (SELECT pos FROM item WHERE kind=${Kind.LIVE.code} AND sid=?)",
                arrayOf(item.id.toString()),
            ).use { c ->
                c.moveToFirst()
                c.getInt(0)
            }
        }) { r -> done(paged, r.getOrDefault(0)) }
    }

    internal fun readPage(from: String, args: Array<String>, order: String, limit: Int, offset: Int): List<Item> {
        val out = ArrayList<Item>(limit)
        db.readableDatabase.rawQuery(
            "SELECT $ITEM_COLS FROM $from ORDER BY $order LIMIT $limit OFFSET $offset", args
        ).use { c -> while (c.moveToNext()) out += readItem(c) }
        return out
    }

    private fun readItem(c: Cursor) = Item(
        Kind.of(c.getInt(0)), c.getLong(1), c.getString(2), c.getString(3),
        c.getString(4), c.getInt(5), c.getString(6), c.getString(7), c.getInt(8),
    )

    internal fun pageExecutor() = reads

    // ---- search ----

    private val searchTicket = AtomicInteger()

    /** Results for what is typed right now. A newer query cancels any older one still waiting its turn. */
    fun search(input: String, kind: Kind? = null, limit: Int = 100, done: (List<Item>, Long) -> Unit) {
        val ticket = searchTicket.incrementAndGet()
        val match = Search.ftsQuery(input)
        if (match == null) {
            done(emptyList(), 0)
            return
        }
        val plain = Search.normalize(input)
        searches.execute {
            if (searchTicket.get() != ticket) return@execute
            val started = System.nanoTime()
            val out = runCatching { runSearch(match, plain, kind, limit) }.getOrDefault(emptyList())
            val tookMs = (System.nanoTime() - started) / 1_000_000
            main.post { if (searchTicket.get() == ticket) done(out, tookMs) }
        }
    }

    private fun runSearch(match: String, plain: String, kind: Kind?, limit: Int): List<Item> {
        val only = if (kind != null) "AND item.kind=${kind.code}" else ""
        val exclude = Kind.entries.joinToString(" ") { Lock.excludeClause(it) }
        val out = ArrayList<Item>(limit)
        // Best first: exact title, then titles starting with the text, then shorter titles.
        val escaped = plain.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        db.readableDatabase.rawQuery(
            """SELECT $ITEM_COLS FROM item
               WHERE item._id IN (SELECT rowid FROM item_fts WHERE item_fts MATCH ?) $only $exclude
               ORDER BY (item.search = ?) DESC,
                        (item.search LIKE ? ESCAPE '\') DESC,
                        LENGTH(item.search),
                        item.kind
               LIMIT $limit""",
            arrayOf(match, plain, escaped),
        ).use { c -> while (c.moveToNext()) out += readItem(c) }
        return out
    }

    // ---- favorites ----

    private fun favKey(kind: Kind, id: Long) = (kind.code.toLong() shl 56) or id

    private fun loadFavorites() {
        db.readableDatabase.rawQuery("SELECT kind, sid FROM favorite", null).use { c ->
            synchronized(favorites) {
                while (c.moveToNext()) favorites += favKey(Kind.of(c.getInt(0)), c.getLong(1))
            }
        }
    }

    fun isFavorite(kind: Kind, id: Long) = synchronized(favorites) { favKey(kind, id) in favorites }

    /** Updates memory immediately and the disk behind the scenes, so the star never waits for storage. */
    fun toggleFavorite(kind: Kind, id: Long): Boolean {
        val key = favKey(kind, id)
        val nowOn = synchronized(favorites) {
            if (key in favorites) {
                favorites -= key
                false
            } else {
                favorites += key
                true
            }
        }
        writes.execute {
            val a = arrayOf<Any>(kind.code, id)
            if (nowOn) {
                db.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO favorite(kind, sid, at) VALUES(?, ?, ${System.currentTimeMillis()})", a
                )
            } else {
                db.writableDatabase.execSQL("DELETE FROM favorite WHERE kind=? AND sid=?", a)
            }
        }
        return nowOn
    }

    // ---- resume points ----

    const val EPISODE_KIND = 3
    private const val EPG_SPACING_MS = 900L
    private const val EPG_FRESH_MS = 30 * 60_000L
    private const val EPG_MAX_WAITING = 14

    fun saveProgress(kind: Int, id: Long, parent: Long, posMs: Long, durMs: Long) {
        writes.execute {
            val finished = durMs > 0 && posMs >= durMs * 95 / 100
            if (finished && kind != EPISODE_KIND) {
                db.writableDatabase.execSQL(
                    "DELETE FROM progress WHERE kind=? AND sid=?", arrayOf<Any>(kind, id)
                )
            } else if (finished || posMs >= 5_000) {
                // A finished episode stays on record, so its series knows which episode comes next.
                val pos = if (finished) durMs else posMs
                db.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO progress(kind, sid, parent, pos, dur, at) VALUES(?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(kind, id, parent, pos, durMs, System.currentTimeMillis()),
                )
            }
        }
    }

    data class Resume(val positionMs: Long, val durationMs: Long) {
        val finished get() = durationMs > 0 && positionMs >= durationMs
    }

    fun resumePoints(kind: Int, parent: Long, done: (Map<Long, Resume>, Long?) -> Unit) = bg({
        val map = HashMap<Long, Resume>()
        var latest: Long? = null
        db.readableDatabase.rawQuery(
            "SELECT sid, pos, dur FROM progress WHERE kind=? AND parent=? ORDER BY at DESC",
            arrayOf(kind.toString(), parent.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                if (latest == null) latest = c.getLong(0)
                map[c.getLong(0)] = Resume(c.getLong(1), c.getLong(2))
            }
        }
        map to latest
    }) { r -> r.getOrNull()?.let { done(it.first, it.second) } }

    // ---- server details, fetched on demand ----

    /** A short reason safe to show and log: never a message that might quote a URL. */
    fun reason(e: Throwable?): String {
        val message = e?.message.orEmpty()
        return if (message.startsWith("HTTP ")) message else e?.javaClass?.simpleName ?: "unknown"
    }

    private fun <T> getJson(url: String, read: (JsonReader) -> T): T {
        if (isQuiet()) throw SlowDown()
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            JsonReader(res.body!!.charStream()).use { r ->
                r.isLenient = true
                return read(r)
            }
        }
    }

    fun movieInfo(id: Long, done: (Result<MovieInfo>) -> Unit) = net({
        getJson(urls().api("get_vod_info", "&vod_id=$id"), XtreamParser::movieInfo)
    }, done)

    fun seriesInfo(id: Long, done: (Result<SeriesInfo>) -> Unit) = net({
        getJson(urls().api("get_series_info", "&series_id=$id"), XtreamParser::seriesInfo)
    }, done)

    // Program guide requests go one at a time, spaced out, newest first. Rows scrolled past are dropped from the
    // queue rather than asked for, so scrolling the guide never turns into a burst at the provider.
    private val epgWaiting = LinkedHashMap<Long, MutableList<(List<EpgEntry>) -> Unit>>()
    private val epgLock = Object()
    private var epgThread: Thread? = null

    fun epg(streamId: Long, done: (List<EpgEntry>) -> Unit) {
        epgCache.get(streamId)?.let { (at, list) ->
            if (System.currentTimeMillis() - at < EPG_FRESH_MS) {
                done(list)
                return
            }
        }
        synchronized(epgLock) {
            // Re-adding moves this channel to the newest position.
            val waiting = epgWaiting.remove(streamId) ?: ArrayList()
            waiting += done
            epgWaiting[streamId] = waiting
            while (epgWaiting.size > EPG_MAX_WAITING) epgWaiting.remove(epgWaiting.keys.first())
            if (epgThread == null) {
                epgThread = Thread(::runEpgQueue, "zap-epg").apply {
                    priority = Thread.MIN_PRIORITY
                    isDaemon = true
                    start()
                }
            }
            epgLock.notifyAll()
        }
    }

    private fun runEpgQueue() {
        while (true) {
            val quiet = quietUntil - System.currentTimeMillis()
            if (quiet > 0) Thread.sleep(quiet)
            val id: Long
            val waiting: List<(List<EpgEntry>) -> Unit>
            synchronized(epgLock) {
                while (epgWaiting.isEmpty()) epgLock.wait()
                id = epgWaiting.keys.last()
                waiting = epgWaiting.remove(id).orEmpty()
            }
            val result = runCatching {
                getJson(urls().api("get_short_epg", "&stream_id=$id&limit=4"), XtreamParser::shortEpg)
            }
            // A failure is not "no programs": it stays out of the cache, and the next look asks again.
            result.onSuccess { epgCache.put(id, System.currentTimeMillis() to it) }
            val list = result.getOrDefault(emptyList())
            main.post { waiting.forEach { it(list) } }
            Thread.sleep(EPG_SPACING_MS)
        }
    }

    /** What a channel has already shown, newest first, limited to what its catch-up archive still holds. */
    fun pastPrograms(item: Item, done: (List<EpgEntry>) -> Unit) = net({
        val now = System.currentTimeMillis() / 1000
        val oldest = now - item.archiveDays * 86400L
        getJson(urls().api("get_simple_data_table", "&stream_id=${item.id}"), XtreamParser::shortEpg)
            .filter { it.stop in (oldest + 1)..now }
            .sortedByDescending { it.start }
    }) { r -> done(r.getOrDefault(emptyList())) }

    fun timeshiftUrl(item: Item, e: EpgEntry): String {
        val minutes = (((e.stop - e.start) + 59) / 60).toInt().coerceAtLeast(1)
        return urls().timeshift(item.id, e.start, minutes, prefs.getString("timezone", "").orEmpty())
    }
}

/**
 * A long list that is cheap to hold: only the pages near the viewer are in memory.
 * A category with 50,000 titles opens as fast as one with 50.
 */
class PagedItems(
    private val from: String,
    private val args: Array<String>,
    private val order: String,
    val count: Int,
) {
    private val pages = HashMap<Int, List<Item>>()
    private val loading = HashSet<Int>()
    private val waiters = HashMap<Int, MutableList<(Item?) -> Unit>>()
    private var lastPage = 0

    var onPageLoaded: ((start: Int, size: Int) -> Unit)? = null

    /** The item if it is loaded; otherwise null, and it (and its neighbors) start loading. Main thread only. */
    fun peek(index: Int): Item? {
        if (index !in 0 until count) return null
        val p = index / PAGE
        lastPage = p
        load(p)
        val offset = index % PAGE
        if (offset < PAGE / 3 && p > 0) load(p - 1)
        if (offset > PAGE * 2 / 3) load(p + 1)
        return pages[p]?.getOrNull(offset)
    }

    /** Hands over one item, waiting for its page if needed. Main thread only. */
    fun fetch(index: Int, done: (Item?) -> Unit) {
        if (index !in 0 until count) {
            done(null)
            return
        }
        val p = index / PAGE
        val offset = index % PAGE
        val page = pages[p]
        if (page != null) {
            done(page.getOrNull(offset))
            return
        }
        waiters.getOrPut(p) { ArrayList() }.add { _ -> done(pages[p]?.getOrNull(offset)) }
        load(p)
    }

    private fun load(p: Int) {
        if (p < 0 || p * PAGE >= count || p in pages || p in loading) return
        loading += p
        Repo.pageExecutor().execute {
            val rows = runCatching { Repo.readPage(from, args, order, PAGE, p * PAGE) }.getOrDefault(emptyList())
            Repo.main.post {
                loading -= p
                pages[p] = rows
                while (pages.size > MAX_PAGES) {
                    pages.keys.filter { !waiters.containsKey(it) }
                        .maxByOrNull { kotlin.math.abs(it - lastPage) }
                        ?.let { pages.remove(it) } ?: break
                }
                waiters.remove(p)?.forEach { it(null) }
                onPageLoaded?.invoke(p * PAGE, rows.size)
            }
        }
    }

    private companion object {
        const val PAGE = 60
        const val MAX_PAGES = 6
    }
}
