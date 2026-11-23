package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class StalkerLegacyVodTest {
    private val requests = mutableListOf<Map<String, String>>()
    private val account = XtreamAccount(id = "legacy-vod-${java.util.UUID.randomUUID()}", name = "test", baseUrl = "http://legacy.test",
        username = "", password = "", sourceType = "stalker", macAddress = "00:1A:79:58:B3:A6")
    private fun param(url: String, key: String) = Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)?.let {
        java.net.URLDecoder.decode(it, "UTF-8")
    }.orEmpty()
    /** Stock-Ministra behaviour: create_link answers only `/media/<id>.mpg` and `/media/file_<id>.mpg` (vod.class.php). */
    private var strict = false
    private var strictRows = ""
    private var strictFiles = ""
    private var relativeReply = false
    private var linkError = ""
    /** XC-emulator portal: create_link accepts a raw `ffmpeg http://...` cmd as-is. */
    private var xcRaw = false
    private val portal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val q = listOf("action","type","cmd","movie_id","series").associateWith { param(url,it) }
        requests += q
        if (strict) strictRespond(q) else when(q["action"]) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{"status":0}}"""
            "get_categories" -> if(q["type"] == "series") """{"js":null}""" else """{"js":[{"id":"7","title":"Library"}]}"""
            "get_ordered_list" -> if(q["movie_id"] == "10")
                """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"100","is_file":1,"cmd":"/media/file_100.mpg"}]}}"""
                else """{"js":{"total_items":2,"max_page_items":14,"data":[{"id":"10","name":"Movie","cmd":"/media/10.mpg"},{"id":"20","name":"Legacy Show","is_series":0,"series":[1,2,3],"cmd":"/media/20.mpg"}]}}"""
            "create_link" -> if(q["cmd"] == "/media/10.mpg") """{"js":{"cmd":""}}"""
                else """{"js":{"cmd":"ffmpeg http://media.test/play"}}"""
            else -> """{"js":[]}"""
        }
    }
    private fun strictRespond(q: Map<String, String>): String = when (q["action"]) {
        "handshake" -> """{"js":{"token":"T"}}"""
        "get_profile" -> """{"js":{"status":0}}"""
        "get_categories" -> if (q["type"] == "series") """{"js":null}""" else """{"js":[{"id":"7","title":"Library"}]}"""
        "get_ordered_list" -> if (q["movie_id"].orEmpty().isNotEmpty())
            """{"js":{"total_items":1,"max_page_items":14,"data":[$strictFiles]}}"""
            else """{"js":{"total_items":1,"max_page_items":14,"data":[$strictRows]}}"""
        "create_link" -> {
            val ok = Regex("""^/media/(file_)?\d+\.mpg$""").matches(q["cmd"].orEmpty()) ||
                (xcRaw && q["cmd"].orEmpty().startsWith("ffmpeg http"))
            when {
                !ok -> """{"js":{"cmd":"","error":"nothing_to_play"}}"""
                linkError.isNotEmpty() -> """{"js":{"cmd":"","error":"$linkError"}}"""
                relativeReply -> """{"js":{"cmd":"/media/play/x.m3u8?t=1"}}"""
                else -> """{"js":{"cmd":"ffmpeg http://media.test/play"}}"""
            }
        }
        else -> """{"js":[]}"""
    }
    private fun createLinkCmds() = requests.filter { it["action"] == "create_link" }.map { it["cmd"] }

    @BeforeTest fun before() {
        IptvContentDbDriver.openForTests = { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        StalkerClient.clearMemoryCachesForTest()
        StalkerClient.sessionFactory = { StalkerSession(it, portal, { u,h,c -> c(portal(u,h)) }) }
    }
    @AfterTest fun after() { StalkerClient.clearMemoryCachesForTest(); StalkerClient.sessionFactory = { StalkerSession(it) } }
    @Test fun legacyShowIsExcludedFromMoviesAndPlaysAnEpisode() = runBlocking {
        assertEquals(listOf(10), StalkerClient.vodMovies(account,"7").getOrThrow().map { it.streamId })
        assertEquals(listOf(20), StalkerClient.series(account,"7").getOrThrow().map { it.seriesId })
        assertEquals(listOf(1,2,3), StalkerClient.seriesInfo(account,20).getOrThrow()!!.episodes.map { it.episodeNum })
        assertEquals("http://media.test/play", StalkerClient.resolveEpisodeUrl(account,20,1,2))
        assertEquals("2", requests.last { it["action"] == "create_link" }["series"])
    }
    @Test fun movieFallsBackToItsPlayableFileCommand() = runBlocking {
        StalkerClient.vodMovies(account,"7").getOrThrow()
        assertEquals("http://media.test/play", StalkerClient.resolveMovieUrl(account,10))
        assertEquals(listOf("/media/10.mpg","/media/file_100.mpg"), requests.filter { it["action"] == "create_link" }.map { it["cmd"] })
        assertEquals(1, requests.count { it["action"] == "get_ordered_list" && it["movie_id"] == "10" })
    }

    @Test fun `strict A - a movie with a custom rtsp_url mints the id cmd`() = runBlocking {
        strict = true
        strictRows = """{"id":"10","name":"Movie","cmd":"http://cdn.test/a.mkv","rtsp_url":"http://cdn.test/a.mkv","has_files":0}"""
        StalkerClient.vodMovies(account, "7").getOrThrow()
        assertEquals("http://media.test/play", StalkerClient.resolveMovieUrl(account, 10), "link resolved")
        assertEquals(listOf<String?>("/media/10.mpg"), createLinkCmds(), "create_link cmd")
    }
    @Test fun `strict B - a multi-file movie mints the file id and nothing else`() = runBlocking {
        strict = true
        strictRows = """{"id":"10","name":"Movie","cmd":"http://cdn.test/f.mkv","has_files":1}"""
        strictFiles = """{"id":"100","is_file":true,"protocol":"custom","cmd":"http://cdn.test/f.mkv"}"""
        StalkerClient.vodMovies(account, "7").getOrThrow()
        assertEquals("http://media.test/play", StalkerClient.resolveMovieUrl(account, 10), "link resolved")
        assertEquals(listOf<String?>("/media/file_100.mpg"), createLinkCmds(), "the only create_link cmd")
    }
    @Test fun `strict cold start with a stored custom url cmd sends it first then falls back to the file row`() = runBlocking {
        strict = true
        strictRows = """{"id":"10","name":"Movie","cmd":"http://cdn.test/f.mkv","has_files":1}"""
        strictFiles = """{"id":"100","is_file":true,"protocol":"custom","cmd":"http://cdn.test/f.mkv"}"""
        StalkerClient.vodMovies(account, "7").getOrThrow()
        StalkerClient.clearMemoryCachesForTest()   // drop the hot row: only the write-through cmd is left
        assertEquals("http://media.test/play", StalkerClient.resolveMovieUrl(account, 10), "link resolved")
        assertEquals(listOf<String?>("http://cdn.test/f.mkv", "/media/file_100.mpg"), createLinkCmds(), "raw cmd first, then the file id")
    }
    @Test fun `XC emulator raw cmd is sent unchanged`() = runBlocking {
        strict = true
        xcRaw = true
        strictRows = """{"id":"10","name":"Movie","cmd":"ffmpeg http://localhost/ch/3","has_files":0}"""
        StalkerClient.vodMovies(account, "7").getOrThrow()
        assertEquals("http://media.test/play", StalkerClient.resolveMovieUrl(account, 10), "link resolved")
        assertEquals(listOf<String?>("ffmpeg http://localhost/ch/3"), createLinkCmds(), "the raw cmd, once")
        assertEquals(0, requests.count { it["action"] == "get_ordered_list" && it["movie_id"] == "10" }, "no file lookup")
    }
    @Test fun `strict D - a relative reply is resolved against the portal`() = runBlocking {
        strict = true; relativeReply = true
        strictRows = """{"id":"10","name":"Movie","cmd":"/media/10.mpg","has_files":0}"""
        StalkerClient.vodMovies(account, "7").getOrThrow()
        assertEquals("http://legacy.test/media/play/x.m3u8?t=1", StalkerClient.resolveMovieUrl(account, 10), "resolved link")
    }
    @Test fun `strict E - the portal's error is surfaced not swallowed`() = runBlocking {
        strict = true
        strictRows = """{"id":"10","name":"Movie","cmd":"/media/10.mpg","has_files":0}"""
        StalkerClient.vodMovies(account, "7").getOrThrow()
        linkError = "limit"
        assertEquals(StalkerLinkResult.Refused(StalkerLinkRefusal.SESSION_LIMIT), StalkerClient.resolveMovieLink(account, 10), "limit")
        linkError = ""
        strictRows = """{"id":"12","name":"Odd","cmd":"opaque-cmd","has_files":0}"""
        StalkerClient.vodMovies(account, "7").getOrThrow()
        assertEquals(StalkerLinkResult.Refused(StalkerLinkRefusal.NOTHING_TO_PLAY), StalkerClient.resolveMovieLink(account, 12), "nothing_to_play")
    }
}
