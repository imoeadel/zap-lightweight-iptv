package com.moe.zap.core

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Kind(val code: Int) {
    LIVE(0), MOVIE(1), SERIES(2);

    companion object {
        fun of(code: Int) = entries.first { it.code == code }
    }
}

data class Account(val server: String, val username: String, val password: String)

data class AccountInfo(
    val authenticated: Boolean,
    val status: String,
    val expiresAt: Long,
    val maxConnections: Int,
    val outputFormats: List<String>,
    val timezone: String = "",
)

data class Category(val id: String, val name: String)

data class CatalogItem(
    val streamId: Long,
    val categoryId: String,
    val name: String,
    val icon: String,
    val extension: String,
    val number: Int,
    val added: Long,
    val rating: String,
    val epgId: String,
    val archiveDays: Int = 0,
)

data class EpgEntry(val title: String, val description: String, val start: Long, val stop: Long)

data class MovieInfo(
    val plot: String,
    val genre: String,
    val year: String,
    val rating: String,
    val duration: String,
    val cast: String,
    val backdrop: String,
    val poster: String,
    val extension: String,
)

data class Episode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val plot: String,
    val duration: String,
    val image: String,
)

data class SeriesInfo(
    val plot: String,
    val genre: String,
    val year: String,
    val rating: String,
    val cast: String,
    val backdrop: String,
    val poster: String,
    val episodes: List<Episode>,
)

/** Builds every URL of the Xtream Codes API. Holds credentials, so nothing built here may be logged. */
class XtreamUrls(account: Account) {
    private val base = normalizeServer(account.server)
    private val user = enc(account.username)
    private val pass = enc(account.password)

    fun api(action: String? = null, extra: String = ""): String {
        val a = if (action == null) "" else "&action=$action"
        return "$base/player_api.php?username=$user&password=$pass$a$extra"
    }

    fun live(streamId: Long, format: String) = "$base/live/$user/$pass/$streamId.$format"
    fun movie(streamId: Long, ext: String) = "$base/movie/$user/$pass/$streamId.${ext.ifEmpty { "mp4" }}"
    fun episode(episodeId: Long, ext: String) = "$base/series/$user/$pass/$episodeId.${ext.ifEmpty { "mp4" }}"

    /** A past program on a catch-up channel. Panels read the start time in the server's own time zone. */
    fun timeshift(streamId: Long, startEpoch: Long, durationMinutes: Int, timezone: String): String {
        val format = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        if (timezone.isNotEmpty()) format.timeZone = TimeZone.getTimeZone(timezone)
        return "$base/timeshift/$user/$pass/$durationMinutes/${format.format(Date(startEpoch * 1000))}/$streamId.ts"
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** "host:8080/", "http://host:8080/player_api.php?x=y" and "HTTP://host:8080" all become one form. */
        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return s
            val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").find(s)?.value
            s = if (scheme == null) "http://$s" else scheme.lowercase() + s.substring(scheme.length)
            s = s.substringBefore('?').substringBefore('#')
            s = s.trimEnd('/')
            if (s.endsWith("/player_api.php", ignoreCase = true)) s = s.dropLast("/player_api.php".length)
            if (s.endsWith("/get.php", ignoreCase = true)) s = s.dropLast("/get.php".length)
            return s.trimEnd('/')
        }

        /** Live TV starts fastest over a raw MPEG-TS stream; HLS is the fallback when the account forbids it. */
        fun chooseLiveFormat(allowed: List<String>): String = when {
            allowed.isEmpty() || allowed.any { it.equals("ts", true) } -> "ts"
            allowed.any { it.equals("m3u8", true) } -> "m3u8"
            else -> "ts"
        }
    }
}

/**
 * Streaming, forgiving readers for player_api.php. Panels differ wildly: numbers arrive as strings, strings as
 * numbers, missing fields as null, and an empty object can arrive as an empty array. Reading token by token
 * keeps memory flat while a catalog of 100,000 titles goes by.
 */
object XtreamParser {

    fun accountInfo(r: JsonReader): AccountInfo {
        var auth = false
        var status = ""
        var exp = 0L
        var max = 0
        var formats = emptyList<String>()
        var timezone = ""
        obj(r) { key ->
            if (key == "server_info" && r.peek() == JsonToken.BEGIN_OBJECT) {
                obj(r) { k -> if (k == "timezone") timezone = str(r).orEmpty() else r.skipValue() }
            } else if (key == "user_info" && r.peek() == JsonToken.BEGIN_OBJECT) {
                obj(r) { k ->
                    when (k) {
                        "auth" -> auth = (str(r) ?: "") .let { it == "1" || it.equals("true", true) }
                        "status" -> status = str(r).orEmpty()
                        "exp_date" -> exp = str(r)?.toLongOrNull() ?: 0L
                        "max_connections" -> max = str(r)?.toIntOrNull() ?: 0
                        "allowed_output_formats" -> formats = strings(r)
                        else -> r.skipValue()
                    }
                }
            } else {
                r.skipValue()
            }
        }
        return AccountInfo(auth, status, exp, max, formats, timezone)
    }

    fun categories(r: JsonReader, onItem: (Category) -> Unit) {
        arr(r) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue()
            } else {
                var id = ""
                var name = ""
                obj(r) { k ->
                    when (k) {
                        "category_id" -> id = str(r).orEmpty()
                        "category_name" -> name = str(r).orEmpty()
                        else -> r.skipValue()
                    }
                }
                if (id.isNotEmpty()) onItem(Category(id, name.trim()))
            }
        }
    }

    /** Reads get_live_streams, get_vod_streams and get_series, which share one shape with different id keys. */
    fun catalog(r: JsonReader, onItem: (CatalogItem) -> Unit) {
        arr(r) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue()
            } else {
                var id = 0L
                var cat = ""
                var name = ""
                var icon = ""
                var ext = ""
                var num = 0
                var added = 0L
                var rating = ""
                var epg = ""
                var archive = false
                var archiveDays = 0
                obj(r) { k ->
                    when (k) {
                        "tv_archive" -> archive = str(r) == "1"
                        "tv_archive_duration" -> archiveDays = str(r)?.toIntOrNull() ?: 0
                        "stream_id", "series_id" -> id = str(r)?.toLongOrNull() ?: id
                        "category_id" -> cat = str(r).orEmpty()
                        "name", "title" -> {
                            val v = str(r).orEmpty()
                            if (name.isEmpty()) name = v
                        }
                        "stream_icon", "cover" -> {
                            val v = str(r).orEmpty()
                            if (icon.isEmpty()) icon = v
                        }
                        "container_extension" -> ext = str(r).orEmpty()
                        "num" -> num = str(r)?.toDoubleOrNull()?.toInt() ?: 0
                        "added", "last_modified" -> {
                            val v = str(r)?.toLongOrNull() ?: 0L
                            if (v > added) added = v
                        }
                        "rating" -> rating = cleanRating(str(r))
                        "epg_channel_id" -> epg = str(r).orEmpty()
                        else -> r.skipValue()
                    }
                }
                if (id != 0L) onItem(CatalogItem(id, cat, name.trim(), icon.trim(), ext, num, added, rating, epg, if (archive) archiveDays else 0))
            }
        }
    }

    /** get_short_epg: titles and descriptions are Base64 on nearly every panel, plain text on a few. */
    fun shortEpg(r: JsonReader): List<EpgEntry> {
        val out = ArrayList<EpgEntry>()
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return out
        }
        obj(r) { key ->
            if (key == "epg_listings" && r.peek() == JsonToken.BEGIN_ARRAY) {
                arr(r) {
                    var title = ""
                    var desc = ""
                    var start = 0L
                    var stop = 0L
                    obj(r) { k ->
                        when (k) {
                            "title" -> title = unbase64(str(r).orEmpty())
                            "description" -> desc = unbase64(str(r).orEmpty())
                            "start_timestamp" -> start = str(r)?.toLongOrNull() ?: 0L
                            "stop_timestamp" -> stop = str(r)?.toLongOrNull() ?: 0L
                            else -> r.skipValue()
                        }
                    }
                    if (title.isNotEmpty()) out += EpgEntry(title, desc, start, stop)
                }
            } else {
                r.skipValue()
            }
        }
        return out
    }

    fun movieInfo(r: JsonReader): MovieInfo {
        var plot = ""
        var genre = ""
        var year = ""
        var rating = ""
        var duration = ""
        var cast = ""
        var backdrop = ""
        var poster = ""
        var ext = ""
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return MovieInfo("", "", "", "", "", "", "", "", "")
        }
        obj(r) { key ->
            when {
                key == "info" && r.peek() == JsonToken.BEGIN_OBJECT -> obj(r) { k ->
                    when (k) {
                        "plot", "description" -> str(r).orEmpty().let { if (plot.isEmpty()) plot = it.trim() }
                        "genre" -> genre = str(r).orEmpty()
                        "releasedate", "release_date" -> str(r).orEmpty().let { if (year.isEmpty()) year = it.take(4) }
                        "rating" -> rating = cleanRating(str(r))
                        "duration" -> duration = str(r).orEmpty()
                        "cast", "actors" -> str(r).orEmpty().let { if (cast.isEmpty()) cast = it }
                        "backdrop_path" -> backdrop = firstString(r)
                        "movie_image", "cover_big" -> str(r).orEmpty().let { if (poster.isEmpty()) poster = it }
                        else -> r.skipValue()
                    }
                }
                key == "movie_data" && r.peek() == JsonToken.BEGIN_OBJECT -> obj(r) { k ->
                    if (k == "container_extension") ext = str(r).orEmpty() else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        return MovieInfo(plot, genre, year, rating, duration, cast, backdrop, poster, ext)
    }

    fun seriesInfo(r: JsonReader): SeriesInfo {
        var plot = ""
        var genre = ""
        var year = ""
        var rating = ""
        var cast = ""
        var backdrop = ""
        var poster = ""
        val episodes = ArrayList<Episode>()
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return SeriesInfo("", "", "", "", "", "", "", episodes)
        }
        obj(r) { key ->
            when {
                key == "info" && r.peek() == JsonToken.BEGIN_OBJECT -> obj(r) { k ->
                    when (k) {
                        "plot" -> plot = str(r).orEmpty().trim()
                        "genre" -> genre = str(r).orEmpty()
                        "releaseDate", "release_date" -> str(r).orEmpty().let { if (year.isEmpty()) year = it.take(4) }
                        "rating" -> rating = cleanRating(str(r))
                        "cast" -> cast = str(r).orEmpty()
                        "backdrop_path" -> backdrop = firstString(r)
                        "cover" -> poster = str(r).orEmpty()
                        else -> r.skipValue()
                    }
                }
                key == "episodes" -> readEpisodes(r, episodes)
                else -> r.skipValue()
            }
        }
        episodes.sortWith(compareBy({ it.season }, { it.number }))
        return SeriesInfo(plot, genre, year, rating, cast, backdrop, poster, episodes)
    }

    // Normally {"1": [...], "2": [...]}, but some panels send [[...], [...]] or an empty [].
    private fun readEpisodes(r: JsonReader, out: MutableList<Episode>) {
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> obj(r) { season -> episodeList(r, season.toIntOrNull() ?: 0, out) }
            JsonToken.BEGIN_ARRAY -> {
                var index = 1
                arr(r) {
                    if (r.peek() == JsonToken.BEGIN_ARRAY) episodeList(r, index++, out) else readEpisode(r, 0, out)
                }
            }
            else -> r.skipValue()
        }
    }

    private fun episodeList(r: JsonReader, season: Int, out: MutableList<Episode>) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return
        }
        arr(r) { readEpisode(r, season, out) }
    }

    private fun readEpisode(r: JsonReader, defaultSeason: Int, out: MutableList<Episode>) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return
        }
        var id = 0L
        var season = defaultSeason
        var num = 0
        var title = ""
        var ext = ""
        var plot = ""
        var duration = ""
        var image = ""
        obj(r) { k ->
            when (k) {
                "id" -> id = str(r)?.toLongOrNull() ?: 0L
                "season" -> season = str(r)?.toIntOrNull()?.takeIf { it > 0 } ?: season
                "episode_num" -> num = str(r)?.toIntOrNull() ?: 0
                "title" -> title = str(r).orEmpty().trim()
                "container_extension" -> ext = str(r).orEmpty()
                "info" -> if (r.peek() == JsonToken.BEGIN_OBJECT) {
                    obj(r) { i ->
                        when (i) {
                            "plot" -> plot = str(r).orEmpty().trim()
                            "duration" -> duration = str(r).orEmpty()
                            "movie_image" -> image = str(r).orEmpty()
                            else -> r.skipValue()
                        }
                    }
                } else {
                    r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        if (id != 0L) out += Episode(id, season, num, title, ext, plot, duration, image)
    }

    private fun cleanRating(v: String?): String {
        val n = v?.trim()?.toDoubleOrNull() ?: return ""
        if (n <= 0.0) return ""
        return String.format(java.util.Locale.US, "%.1f", n)
    }

    fun unbase64(s: String): String {
        if (s.isEmpty()) return s
        return try {
            String(Base64.getMimeDecoder().decode(s), Charsets.UTF_8).let {
                if (it.contains('�')) s else it
            }
        } catch (_: IllegalArgumentException) {
            s
        }
    }

    private inline fun obj(r: JsonReader, onKey: (String) -> Unit) {
        r.beginObject()
        while (r.hasNext()) onKey(r.nextName())
        r.endObject()
    }

    private inline fun arr(r: JsonReader, onItem: () -> Unit) {
        // Some panels answer {} or null where a list belongs, meaning "nothing here".
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return
        }
        r.beginArray()
        while (r.hasNext()) onItem()
        r.endArray()
    }

    private fun str(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> {
            r.nextNull()
            null
        }
        else -> {
            r.skipValue()
            null
        }
    }

    private fun strings(r: JsonReader): List<String> {
        val out = ArrayList<String>()
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return out
        }
        arr(r) { str(r)?.let { out += it } }
        return out
    }

    private fun firstString(r: JsonReader): String =
        if (r.peek() == JsonToken.BEGIN_ARRAY) strings(r).firstOrNull().orEmpty() else str(r).orEmpty()
}
