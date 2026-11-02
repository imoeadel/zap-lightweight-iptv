package com.moe.zap.data

import android.os.Process
import com.google.gson.stream.JsonReader
import com.moe.zap.core.Kind
import com.moe.zap.core.Search
import com.moe.zap.core.XtreamParser
import com.moe.zap.core.XtreamUrls
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Refreshes the local catalog in two steps, so browsing never stutters:
 * 1. Download every list to a temp file, without touching the database.
 * 2. Swap all of it in one transaction. Readers see the old catalog until it commits; a failed list keeps its old rows.
 */
object Syncer {
    data class Progress(val step: String, val items: Int)

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    @Volatile var running = false
        private set

    var onProgress: ((Progress) -> Unit)? = null
    var onFinished: ((Result<Int>) -> Unit)? = null

    fun lastSyncAt(): Long = Repo.prefs.getLong("lastSync", 0L)

    fun isStale() = System.currentTimeMillis() - lastSyncAt() > DAY_MS && !Repo.isQuiet()

    fun start() {
        if (running) return
        running = true
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val result = runCatching { run() }
            running = false
            Repo.main.post { onFinished?.invoke(result) }
        }, "zap-sync").start()
    }

    private fun report(step: String, items: Int = 0) {
        Repo.main.post { onProgress?.invoke(Progress(step, items)) }
    }

    private class Source(val kind: Kind, val label: String, val categoriesAction: String, val itemsAction: String)

    private val SOURCES = listOf(
        Source(Kind.LIVE, "channels", "get_live_categories", "get_live_streams"),
        Source(Kind.MOVIE, "movies", "get_vod_categories", "get_vod_streams"),
        Source(Kind.SERIES, "series", "get_series_categories", "get_series"),
    )

    private class Downloaded(val source: Source, val categories: File, val items: File)

    private fun run(): Int {
        val urls = Repo.urls()
        val dir = File(Repo.appContext.cacheDir, "sync").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            runCatching { Repo.saveAccountInfo(Repo.fetchAccountInfo(urls)) }
            val done = ArrayList<Downloaded>()
            var firstError: Exception? = null
            for (s in SOURCES) {
                report("Downloading ${s.label}")
                try {
                    done += Downloaded(
                        s,
                        download(urls, s.categoriesAction, File(dir, "${s.kind.code}-c.json")),
                        download(urls, s.itemsAction, File(dir, "${s.kind.code}-i.json")),
                    )
                } catch (e: IOException) {
                    firstError = firstError ?: e
                }
            }
            if (done.isEmpty()) throw firstError ?: IOException("Nothing downloaded")
            val total = store(done)
            Repo.prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply()
            return total
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun download(urls: XtreamUrls, action: String, to: File): File {
        // One request at a time with a pause between, so a refresh never looks like a burst to the provider.
        Thread.sleep(1000)
        if (Repo.isQuiet()) throw IOException("HTTP 429")
        Repo.http.newCall(Request.Builder().url(urls.api(action)).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            to.outputStream().use { out -> res.body!!.byteStream().copyTo(out, 64 * 1024) }
        }
        return to
    }

    private fun reader(f: File) = JsonReader(f.bufferedReader(Charsets.UTF_8)).apply { isLenient = true }

    private fun store(all: List<Downloaded>): Int {
        val db = Repo.db.writableDatabase
        var total = 0
        db.beginTransactionNonExclusive()
        try {
            val insertCategory = db.compileStatement("INSERT OR REPLACE INTO category(kind, id, name, pos) VALUES(?, ?, ?, ?)")
            val insertItem = db.compileStatement(
                "INSERT OR REPLACE INTO item(kind, sid, cat, name, icon, ext, num, added, rating, epg, search, pos, archive) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
            for (d in all) {
                val kind = d.source.kind.code.toLong()
                db.execSQL("DELETE FROM category WHERE kind=$kind")
                db.execSQL("DELETE FROM item WHERE kind=$kind")
                var pos = 0L
                reader(d.categories).use { r ->
                    XtreamParser.categories(r) { c ->
                        insertCategory.clearBindings()
                        insertCategory.bindLong(1, kind)
                        insertCategory.bindString(2, c.id)
                        insertCategory.bindString(3, c.name)
                        insertCategory.bindLong(4, pos++)
                        insertCategory.executeInsert()
                    }
                }
                pos = 0L
                reader(d.items).use { r ->
                    XtreamParser.catalog(r) { i ->
                        insertItem.clearBindings()
                        insertItem.bindLong(1, kind)
                        insertItem.bindLong(2, i.streamId)
                        insertItem.bindString(3, i.categoryId)
                        insertItem.bindString(4, i.name)
                        insertItem.bindString(5, i.icon)
                        insertItem.bindString(6, i.extension)
                        insertItem.bindLong(7, i.number.toLong())
                        insertItem.bindLong(8, i.added)
                        insertItem.bindString(9, i.rating)
                        insertItem.bindString(10, i.epgId)
                        insertItem.bindString(11, Search.normalize(i.name))
                        insertItem.bindLong(12, pos++)
                        insertItem.bindLong(13, i.archiveDays.toLong())
                        insertItem.executeInsert()
                        total++
                        if (total % 2000 == 0) report("Saving your library", total)
                    }
                }
            }
            report("Building search", total)
            db.execSQL("INSERT INTO item_fts(item_fts) VALUES('rebuild')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return total
    }
}
