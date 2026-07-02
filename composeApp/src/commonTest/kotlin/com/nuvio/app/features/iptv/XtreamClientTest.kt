package com.nuvio.app.features.iptv

import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamClientTest {

    @Test
    fun decodeBase64DecodesAndPassesThroughGarbage() {
        val enc = "News at Ten".encodeToByteArray().encodeBase64()
        assertEquals("News at Ten", decodeXtreamBase64(enc))
        assertEquals("", decodeXtreamBase64(null))
        assertEquals("", decodeXtreamBase64("   "))
    }

    @Test
    fun bulkListParsingToleratesInconsistentFieldTypesAcrossPanels() {
        // Panels are wildly inconsistent: onnipsite sends `rating` as a bare number (0), not
        // "0"; tmdb/stream_id arrive as int OR quoted string. A strict typed decode throws on
        // the first such field and loses the ENTIRE catalog → the index never builds. The
        // loose parser reads each field tolerantly, so one odd value never sinks the list.
        val acc = XtreamAccount(id = "x", name = "x", baseUrl = "http://h:8080", username = "u", password = "p")
        val json = Json { ignoreUnknownKeys = true }
        val vod = (json.parseToJsonElement(
            """[
              {"stream_id":1,"name":"Quoted","rating":"7.5","container_extension":"mkv","tmdb":"869291"},
              {"stream_id":"2","name":"BareRating","rating":0,"tmdb":123},
              {"stream_id":3,"name":"NoRating"}
            ]""".trimIndent()
        ) as JsonArray).mapNotNull { XtreamClient.parseVodItem(acc, it as JsonObject) }
        assertEquals(3, vod.size)
        assertEquals("7.5", vod[0].rating)
        assertEquals(869291, vod[0].tmdb)          // tmdb as quoted string
        assertTrue(vod[0].streamUrl.endsWith("/1.mkv"))
        assertEquals("0", vod[1].rating)           // bare-number rating no longer throws
        assertEquals(2, vod[1].streamId)           // stream_id as quoted string
        assertTrue(vod[2].streamUrl.endsWith("/3.mp4")) // missing container ext -> mp4 default
        assertNull(vod[2].rating)

        val series = (json.parseToJsonElement(
            """[{"series_id":9,"name":"S","rating":8,"releaseDate":"2024-01-02"}]"""
        ) as JsonArray).mapNotNull { XtreamClient.parseSeriesItem(it as JsonObject) }
        assertEquals("8", series[0].rating)        // bare-number rating
        assertEquals(2024, series[0].year)
    }

    @Test
    fun flexIntToleratesIntStringAndBoolAcrossPanels() {
        val json = Json { ignoreUnknownKeys = true }
        // panel A: ints
        val a = json.decodeFromString<XtreamLiveStreamDto>(
            """{"name":"A","stream_id":42,"category_id":"3","tv_archive":1}"""
        )
        assertEquals(42, a.streamId)
        assertEquals(1, a.tvArchive)
        // panel B: quoted strings + bool
        val b = json.decodeFromString<XtreamLiveStreamDto>(
            """{"name":"B","stream_id":"42","category_id":"3","tv_archive":true}"""
        )
        assertEquals(42, b.streamId)
        assertEquals(1, b.tvArchive)
        // empty id -> null, no throw
        val c = json.decodeFromString<XtreamLiveStreamDto>("""{"name":"C","stream_id":""}""")
        assertNull(c.streamId)
    }

    @Test
    fun parseXtreamAccountExtractsHostPortCreds() {
        val a = parseXtreamAccount("http://provider.example.com:8080/get.php?username=user1&password=pass1&type=m3u_plus&output=mpegts")!!
        assertEquals("http://provider.example.com:8080", a.baseUrl)
        assertEquals("user1", a.username)
        assertEquals("pass1", a.password)

        val b = parseXtreamAccount("http://panel.example.net/get.php?username=u1&password=p1&type=m3u_plus&output=ts")!!
        assertEquals("http://panel.example.net", b.baseUrl)   // default port omitted
        assertEquals("panel.example.net", b.name)

        assertNull(parseXtreamAccount("http://panel.example.net/get.php?type=m3u_plus"))  // no creds
        assertNull(parseXtreamAccount("not a url"))
    }

    @Test
    fun xtreamAccountFromFieldsNormalizesServerAndRequiresCreds() {
        val a = xtreamAccountFromFields("host.example.org:8080", "demo", "secret", null)!!
        assertEquals("http://host.example.org:8080", a.baseUrl)
        assertEquals("demo", a.username)
        val b = xtreamAccountFromFields("http://panel.example.net/c/", "u", "p", "Home")!!
        assertEquals("http://panel.example.net", b.baseUrl)
        assertEquals("Home", b.name)
        assertNull(xtreamAccountFromFields("http://panel.example.net", "", "p", null))
        assertNull(xtreamAccountFromFields("", "u", "p", null))
    }

    @Test
    fun toProgramConvertsSecondsToMillisAndNowPlaying() {
        val p = XtreamEpgEntryDto(
            title = "VGl0bGU=",            // "Title"
            description = "RGVzYw==",      // "Desc"
            startTimestamp = "1700000000",
            stopTimestamp = "1700003600",
            nowPlaying = 1
        ).toProgram()
        assertEquals("Title", p.title)
        assertEquals("Desc", p.description)
        assertEquals(1700000000_000L, p.startMs)
        assertEquals(1700003600_000L, p.endMs)
        assertTrue(p.nowPlaying)
    }
}
