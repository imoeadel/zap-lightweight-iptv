package com.moe.zap.core

import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Base64

class XtreamTest {
    private fun reader(json: String) = JsonReader(StringReader(json))

    @Test fun normalizesEveryWayPeoplePasteAServer() {
        val want = "http://host.tv:8080"
        for (s in listOf(
            "host.tv:8080", " http://host.tv:8080/ ", "HTTP://host.tv:8080",
            "http://host.tv:8080/player_api.php?username=a&password=b",
            "http://host.tv:8080/get.php?username=a&password=b&type=m3u",
        )) assertEquals(s, want, XtreamUrls.normalizeServer(s))
        assertEquals("https://host.tv", XtreamUrls.normalizeServer("https://host.tv/"))
    }

    @Test fun buildsStreamUrlsAndEscapesCredentials() {
        val u = XtreamUrls(Account("host:80", "me@x y", "p/w+1"))
        assertEquals("http://host:80/live/me%40x%20y/p%2Fw%2B1/7.ts", u.live(7, "ts"))
        assertEquals("http://host:80/movie/me%40x%20y/p%2Fw%2B1/7.mkv", u.movie(7, "mkv"))
        assertEquals("http://host:80/series/me%40x%20y/p%2Fw%2B1/7.mp4", u.episode(7, ""))
        assertEquals(
            "http://host:80/player_api.php?username=me%40x%20y&password=p%2Fw%2B1&action=get_short_epg&stream_id=7",
            u.api("get_short_epg", "&stream_id=7"),
        )
    }

    @Test fun prefersTsThenHls() {
        assertEquals("ts", XtreamUrls.chooseLiveFormat(listOf("m3u8", "ts")))
        assertEquals("m3u8", XtreamUrls.chooseLiveFormat(listOf("m3u8", "rtmp")))
        assertEquals("ts", XtreamUrls.chooseLiveFormat(emptyList()))
    }

    @Test fun readsAccountWithNumbersAsStrings() {
        val a = XtreamParser.accountInfo(reader(
            """{"user_info":{"auth":1,"status":"Active","exp_date":"1799999999","max_connections":"2",
               "allowed_output_formats":["m3u8","ts"],"message":null},"server_info":{"url":"x"}}"""
        ))
        assertTrue(a.authenticated)
        assertEquals("Active", a.status)
        assertEquals(1799999999L, a.expiresAt)
        assertEquals(2, a.maxConnections)
        assertEquals(listOf("m3u8", "ts"), a.outputFormats)
    }

    @Test fun rejectedAccountIsNotAuthenticated() {
        assertFalse(XtreamParser.accountInfo(reader("""{"user_info":{"auth":0}}""")).authenticated)
        assertFalse(XtreamParser.accountInfo(reader("""{"user_info":[]}""")).authenticated)
        assertFalse(XtreamParser.accountInfo(reader("{}")).authenticated)
    }

    @Test fun readsLiveItemsWithMessyTypes() {
        val items = ArrayList<CatalogItem>()
        XtreamParser.catalog(reader(
            """[{"num":"3","name":" beIN 1 ","stream_id":"101","stream_icon":null,"added":"1700000000",
                 "category_id":5,"epg_channel_id":"bein1","tv_archive":0,"direct_source":""},
                {"num":4.0,"name":"No id"},"junk",null,
                {"stream_id":102,"name":"X","category_id":null,"rating":"7.456","container_extension":"mkv"}]"""
        ), items::add)
        assertEquals(2, items.size)
        assertEquals(CatalogItem(101, "5", "beIN 1", "", "", 3, 1700000000, "", "bein1"), items[0])
        assertEquals("7.5", items[1].rating)
        assertEquals("mkv", items[1].extension)
        assertEquals("", items[1].categoryId)
    }

    @Test fun readsSeriesShape() {
        val items = ArrayList<CatalogItem>()
        XtreamParser.catalog(reader(
            """[{"series_id":9,"name":"Show","cover":"http://i/c.jpg","last_modified":"1700000001",
                 "rating":0,"category_id":"2","backdrop_path":["a"]}]"""
        ), items::add)
        assertEquals(CatalogItem(9, "2", "Show", "http://i/c.jpg", "", 0, 1700000001, "", ""), items[0])
    }

    @Test fun readsCatchUpDaysOnlyWhenArchiveIsOn() {
        val items = ArrayList<CatalogItem>()
        XtreamParser.catalog(reader(
            """[{"stream_id":1,"tv_archive_duration":"3","tv_archive":1},
                {"stream_id":2,"tv_archive":0,"tv_archive_duration":7},
                {"stream_id":3}]"""
        ), items::add)
        assertEquals(listOf(3, 0, 0), items.map { it.archiveDays })
    }

    @Test fun readsServerTimezone() {
        val a = XtreamParser.accountInfo(reader(
            """{"server_info":{"timezone":"Africa/Cairo"},"user_info":{"auth":1}}"""
        ))
        assertEquals("Africa/Cairo", a.timezone)
    }

    @Test fun timeshiftUsesServerTimezone() {
        val u = XtreamUrls(Account("host:80", "u", "p"))
        // 2026-01-01 00:00:00 UTC
        assertEquals("http://host:80/timeshift/u/p/60/2026-01-01:00-00/7.ts", u.timeshift(7, 1767225600, 60, "UTC"))
        assertEquals("http://host:80/timeshift/u/p/60/2026-01-01:03-00/7.ts", u.timeshift(7, 1767225600, 60, "GMT+3"))
    }

    @Test fun nonListCatalogMeansEmpty() {
        val items = ArrayList<CatalogItem>()
        XtreamParser.catalog(reader("""{"user_info":{"auth":0}}"""), items::add)
        XtreamParser.catalog(reader("null"), items::add)
        assertTrue(items.isEmpty())
    }

    @Test fun readsCategories() {
        val out = ArrayList<Category>()
        XtreamParser.categories(reader(
            """[{"category_id":"1","category_name":" Sports ","parent_id":0},{"category_id":null,"category_name":"bad"}]"""
        ), out::add)
        assertEquals(listOf(Category("1", "Sports")), out)
    }

    @Test fun decodesEpgBase64IncludingArabic() {
        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        val e = XtreamParser.shortEpg(reader(
            """{"epg_listings":[{"title":"${b64("الأخبار")}","description":"${b64("Daily")}",
                "start_timestamp":"100","stop_timestamp":"200"},{"title":""}]}"""
        ))
        assertEquals(listOf(EpgEntry("الأخبار", "Daily", 100, 200)), e)
    }

    @Test fun epgLeavesPlainTextAlone() {
        assertEquals("News at 10!", XtreamParser.unbase64("News at 10!"))
        assertTrue(XtreamParser.shortEpg(reader("[]")).isEmpty())
    }

    @Test fun readsMovieInfo() {
        val m = XtreamParser.movieInfo(reader(
            """{"info":{"plot":" Good ","genre":"Drama","releasedate":"2019-05-01","rating":"8",
                "duration":"01:40:00","cast":"A, B","backdrop_path":["http://i/b.jpg"],"movie_image":"http://i/p.jpg"},
                "movie_data":{"stream_id":1,"container_extension":"mkv"}}"""
        ))
        assertEquals(MovieInfo("Good", "Drama", "2019", "8.0", "01:40:00", "A, B", "http://i/b.jpg", "http://i/p.jpg", "mkv"), m)
    }

    @Test fun movieInfoSurvivesEmptyAnswers() {
        assertEquals("", XtreamParser.movieInfo(reader("""{"info":[],"movie_data":[]}""")).plot)
        assertEquals("", XtreamParser.movieInfo(reader("[]")).plot)
    }

    @Test fun readsSeriesEpisodesInOrderFromObjectForm() {
        val s = XtreamParser.seriesInfo(reader(
            """{"seasons":[],"info":{"plot":"P","releaseDate":"2020-01-01","backdrop_path":[]},
                "episodes":{"2":[{"id":"21","episode_num":1,"title":"S2E1","container_extension":"mp4",
                   "info":{"duration":"00:40:00","plot":"x","movie_image":"m"}}],
                 "1":[{"id":"12","episode_num":"2","title":"S1E2"},{"id":"11","episode_num":"1","title":"S1E1","info":[]}]}}"""
        ))
        assertEquals("2020", s.year)
        assertEquals(listOf(11L, 12L, 21L), s.episodes.map { it.id })
        assertEquals(listOf(1, 1, 2), s.episodes.map { it.season })
        assertEquals("00:40:00", s.episodes[2].duration)
    }

    @Test fun readsSeriesEpisodesFromArrayForm() {
        val s = XtreamParser.seriesInfo(reader(
            """{"episodes":[[{"id":1,"episode_num":1}],[{"id":2,"episode_num":1,"season":7}]]}"""
        ))
        assertEquals(listOf(1 to 1L, 7 to 2L), s.episodes.map { it.season to it.id })
        assertTrue(XtreamParser.seriesInfo(reader("""{"episodes":[]}""")).episodes.isEmpty())
    }
}
