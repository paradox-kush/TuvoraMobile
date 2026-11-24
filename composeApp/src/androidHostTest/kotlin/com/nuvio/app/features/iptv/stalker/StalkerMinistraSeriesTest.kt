package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B02 (Wave 2): genuine Ministra has NO `type=series` module. A series is a `type=vod` row with
 * `is_series=1` (stock vod.class.php); get_ordered_list walks it as movie_id -> seasons,
 * +season_id -> episodes (paged), +episode_id -> files whose `cmd` is what create_link plays.
 * Before this the Series tab asked the missing module ("Stalker TV shows do not load, live +
 * movies OK", 2026-10-04). XC-family portals keep `type=series`. Twin of NuvioTV's test; the fake
 * portal is shaped like research/stalker-mock-portal's `--dialect ministra`.
 */
class StalkerMinistraSeriesTest {

    private val requests = mutableListOf<Map<String, String>>()
    private var ministra = true
    /** B02: file rows carry a custom URL as cmd, and create_link is stock-strict (only /media/<id>.mpg, /media/file_<id>.mpg). */
    private var customFiles = false
    private val episodesPerSeason = 16   // > one 14-row page: episodes must be paged

    private fun query(url: String): Map<String, String> =
        url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            k to decodeComponent(it.substringAfter('=', ""))
        }

    private fun decodeComponent(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> { append(s.substring(i + 1, i + 3).toInt(16).toChar()); i += 3 }
                c == '+' -> { append(' '); i++ }
                else -> { append(c); i++ }
            }
        }
    }

    private fun page(rows: List<String>, total: Int) =
        """{"js":{"total_items":$total,"max_page_items":14,"data":[${rows.joinToString(",")}]}}"""

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val q = query(url)
        requests += q
        respond(q)
    }

    private fun respond(q: Map<String, String>): String {
        val type = q["type"]; val action = q["action"]
        if (action == "handshake") return """{"js":{"token":"T"}}"""
        if (action == "get_profile") return """{"js":{"id":"1","status":0}}"""
        if (action == "create_link" && customFiles) {
            return if (Regex("""^/media/(file_)?\d+\.mpg$""").matches(q["cmd"].orEmpty())) """{"js":{"cmd":"ffmpeg http://media.test/play?c=1"}}"""
            else """{"js":{"cmd":"","error":"nothing_to_play"}}"""
        }
        if (action == "create_link") return """{"js":{"cmd":"ffmpeg http://media.test/play?c=1"}}"""
        if (type == "series") {
            if (ministra) return """{"js":null}"""
            return when {
                action == "get_categories" -> """{"js":[{"id":"*","title":"All"},{"id":"7","title":"Drama"}]}"""
                q["movie_id"].orEmpty().isNotEmpty() ->
                    """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"900:1","name":"Season 1","cmd":"auto /media/series/900/s1","series":["1","2"]}]}}"""
                else -> """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"900","name":"XC Show","cmd":"","category_id":"7"}]}}"""
            }
        }
        if (type == "vod") {
            if (action == "get_categories") return """{"js":[{"id":"*","title":"All"},{"id":"1","title":"Drama"}]}"""
            val movieId = q["movie_id"].orEmpty()
            val seasonId = q["season_id"].orEmpty()
            val episodeId = q["episode_id"].orEmpty()
            val p = q["p"]?.toIntOrNull() ?: 1
            return when {
                episodeId.isNotEmpty() ->
                    if (customFiles) page(listOf("""{"id":"$episodeId","name":"English / HD","is_file":true,"protocol":"custom","cmd":"http://cdn.test/e$episodeId.mkv"}"""), 1)
                    else page(listOf("""{"id":"$episodeId","name":"English / HD","is_file":true,"cmd":"/media/file_$episodeId.mpg"}"""), 1)
                seasonId.isNotEmpty() -> {
                    val sid = seasonId.toInt()
                    val all = (1..episodesPerSeason).map { e ->
                        """{"id":"${sid * 100 + e}","season_id":"$sid","series_number":"$e","name":"Episode $e. Title $e","is_episode":true}"""
                    }
                    page(all.drop((p - 1) * 14).take(14), all.size)
                }
                movieId.isNotEmpty() -> {
                    val mid = movieId.toInt()
                    page((1..2).map { n -> """{"id":"${mid * 10 + n}","season_number":"$n","name":"Season $n","is_season":true}""" }, 2)
                }
                else -> {
                    val rows = listOf(
                        """{"id":"101","name":"A Movie","cmd":"/media/101.mpg","category_id":"1","is_series":"0"}""",
                        """{"id":"500","name":"Silent Harbour","cmd":"/media/500.mpg","category_id":"1","is_series":"1","description":"A show."}""",
                        """{"id":"102","name":"Another Movie","cmd":"/media/102.mpg","category_id":"1","is_series":0}""",
                        """{"id":"501","name":"Silent Orbit","cmd":"/media/501.mpg","category_id":"1","is_series":1}""",
                    )
                    val search = q["search"].orEmpty().lowercase()
                    val hits = if (search.isEmpty()) rows else rows.filter { it.lowercase().contains(search) }
                    page(hits, hits.size)
                }
            }
        }
        return """{"js":[]}"""
    }

    // A process-unique id per account: StalkerClient is an object, and a session it already holds
    // for an id would keep talking to a PREVIOUS test's fake portal (and its request log).
    private fun account() = XtreamAccount(
        id = "stalker|ministra-${kotlin.random.Random.nextLong()}-${ministra}", name = "Ministra", baseUrl = "http://portal.test/stalker_portal/c/",
        username = "", password = "", sourceType = "stalker", macAddress = "00:1A:79:00:00:01",
    )

    @BeforeTest
    fun setUp() {
        com.nuvio.app.features.iptv.content.IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal, { u, h, c -> c(fakePortal(u, h)) }) }
    }

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
        StalkerClient.clearMemoryCachesForTest()
    }

    @Test
    fun `Ministra series categories are the vod categories, not an error`() = runBlocking {
        val cats = StalkerClient.seriesCategories(account()).getOrThrow()
        assertEquals(listOf("1"), cats.map { it.id }, "vod's categories stand in for the missing module")
    }

    @Test
    fun `Ministra series are the is_series vod rows and movies leave them out`() = runBlocking {
        val acc = account()
        StalkerClient.seriesCategories(acc).getOrThrow()
        assertEquals(listOf(500, 501), StalkerClient.series(acc, "1").getOrThrow().map { it.seriesId }, "series = is_series rows")
        assertEquals(listOf(101, 102), StalkerClient.vodMovies(acc, "1").getOrThrow().map { it.streamId }, "movies exclude series rows")
    }

    @Test
    fun `Ministra series detail walks seasons and every episode page`() = runBlocking {
        val acc = account()
        StalkerClient.seriesCategories(acc).getOrThrow()
        StalkerClient.series(acc, "1").getOrThrow()
        val detail = assertNotNull(StalkerClient.seriesInfo(acc, 500).getOrThrow())
        assertEquals(2 * episodesPerSeason, detail.episodes.size, "2 seasons x 16 episodes")
        assertEquals("500_2_16", detail.episodes.last().episodeId, "ids keep the seriesId_season_episode shape")
        assertEquals("Episode 16. Title 16", detail.episodes.last().title, "the portal's episode name")
        assertTrue(requests.filter { it["movie_id"] == "500" }.all { it["type"] == "vod" }, "no type=series on the detail path")
    }

    @Test
    fun `Ministra episode play mints the episode's file cmd`() = runBlocking {
        val acc = account()
        StalkerClient.seriesCategories(acc).getOrThrow()
        assertNotNull(StalkerClient.resolveEpisodeUrl(acc, seriesId = 500, season = 2, episodeNum = 3), "must play")
        val mint = requests.last { it["action"] == "create_link" }
        assertEquals("vod", mint["type"])
        assertEquals("/media/file_500203.mpg", mint["cmd"], "the FILE cmd, not a season container")
        assertFalse(mint.containsKey("series"), "no XC series={n} argument")
    }

    @Test
    fun `strict C - an episode whose file row is a custom url mints the file id`() = runBlocking {
        customFiles = true
        val acc = account()
        StalkerClient.seriesCategories(acc).getOrThrow()
        assertNotNull(StalkerClient.resolveEpisodeUrl(acc, seriesId = 500, season = 2, episodeNum = 3), "must play")
        assertEquals(listOf<String?>("/media/file_500203.mpg"), requests.filter { it["action"] == "create_link" }.map { it["cmd"] }, "id form only")
    }

    @Test
    fun `an episode resolves on a cold session without browsing first`() = runBlocking {
        assertNotNull(StalkerClient.resolveEpisodeUrl(account(), seriesId = 501, season = 1, episodeNum = 1))
        assertEquals("/media/file_501101.mpg", requests.last { it["action"] == "create_link" }["cmd"])
    }

    @Test
    fun `Ministra series search filters vod search to series rows`() = runBlocking {
        assertEquals(listOf(500, 501), StalkerClient.searchSeries(account(), "silent").map { it.seriesId }, "series rows only")
    }

    @Test
    fun `an XC-family portal keeps type=series end to end`() = runBlocking {
        ministra = false
        val acc = account()
        assertEquals(listOf("7"), StalkerClient.seriesCategories(acc).getOrThrow().map { it.id }, "series categories")
        assertEquals(listOf(900), StalkerClient.series(acc, "7").getOrThrow().map { it.seriesId }, "series list")
        StalkerClient.resolveEpisodeUrl(acc, seriesId = 900, season = 1, episodeNum = 2)
        val mint = requests.last { it["action"] == "create_link" }
        assertEquals("auto /media/series/900/s1", mint["cmd"], "season cmd")
        assertEquals("2", mint["series"], "episode rides as series=n")
        assertTrue(requests.none { it["type"] == "vod" && it.containsKey("season_id") }, "never walked the vod tree on XC")
    }
}
