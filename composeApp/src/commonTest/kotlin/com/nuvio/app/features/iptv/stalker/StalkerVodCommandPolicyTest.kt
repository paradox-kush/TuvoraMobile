package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.stalker.StalkerVodCommandPolicy.Plan
import com.nuvio.app.features.iptv.stalker.StalkerVodCommandPolicy.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B02: the create_link cmd of a VOD row is built from ids, never from a custom URL. */
class StalkerVodCommandPolicyTest {

    @Test
    fun `a file row mints its file cmd`() {
        assertEquals(Plan.Direct("/media/file_100.mpg"),
            StalkerVodCommandPolicy.plan(Row(id = 100, cmd = "http://cdn.test/f.mkv", isFile = true)), "file row")
    }

    @Test
    fun `a movie with a custom rtsp_url mints the movie id cmd`() {
        assertEquals(Plan.Direct("/media/10.mpg"),
            StalkerVodCommandPolicy.plan(Row(10, "http://cdn.test/a.mkv", rtspUrl = "http://cdn.test/a.mkv", hasFiles = 0)), "rtsp_url")
    }

    @Test
    fun `a movie with files resolves the file id first and never tries the id cmd`() {
        assertEquals(Plan.FilesFirst(fallback = null),
            StalkerVodCommandPolicy.plan(Row(10, "/media/10.mpg", hasFiles = 1)), "has_files with the id cmd")
        assertEquals(Plan.FilesFirst(fallback = null),
            StalkerVodCommandPolicy.plan(Row(10, "http://cdn.test/f.mkv", hasFiles = 2)), "has_files with a custom url")
    }

    @Test
    fun `a movie whose cmd is already a file cmd keeps it`() {
        assertEquals(Plan.Direct("/media/file_5.mpg"), StalkerVodCommandPolicy.plan(Row(10, "/media/file_5.mpg", hasFiles = 1)), "file form")
    }

    @Test
    fun `a media cmd is kept as is`() {
        assertEquals(Plan.Direct("/media/10.mpg"), StalkerVodCommandPolicy.plan(Row(10, "/media/10.mpg")), "media form")
    }

    @Test
    fun `an XC emulator cmd is kept as is`() {
        assertEquals(Plan.Direct("eyJ0eXBlIjoibW92aWUifQ=="), StalkerVodCommandPolicy.plan(Row(10, "eyJ0eXBlIjoibW92aWUifQ==")), "base64")
        assertEquals(Plan.Direct("auto /media/series/900/s1"), StalkerVodCommandPolicy.plan(Row(10, "auto /media/series/900/s1")), "launcher media")
    }

    @Test
    fun `XC emulator raw cmd is sent unchanged`() {
        assertEquals(Plan.Direct("ffmpeg http://localhost/ch/3", fallback = "/media/10.mpg"),
            StalkerVodCommandPolicy.plan(Row(10, "ffmpeg http://localhost/ch/3", hasFiles = 0)), "proven no files")
        assertEquals(Plan.Direct("http://cdn.test/a.mkv", fallback = "/media/10.mpg"),
            StalkerVodCommandPolicy.plan(Row(10, "http://cdn.test/a.mkv")), "cold start row")
        assertEquals(Plan.Direct("ffmpeg rtsp://cdn.test/a", fallback = "/media/10.mpg"),
            StalkerVodCommandPolicy.plan(Row(10, "ffmpeg rtsp://cdn.test/a")), "launcher plus rtsp")
    }

    @Test
    fun `a blank cmd resolves files`() {
        assertEquals(Plan.FilesFirst(fallback = null), StalkerVodCommandPolicy.plan(Row(10, "  ")), "blank")
        assertEquals(Plan.FilesFirst(fallback = null), StalkerVodCommandPolicy.plan(Row(10, null)), "null")
    }

    @Test
    fun `a raw url is rewritten only on proof of a stock custom row`() {
        val proven = listOf(
            Row(1, "http://a/b", rtspUrl = "https://a/b"), Row(1, "rtsp://a/b", hasFiles = 3), Row(1, "http://a/b", isFile = true),
        )
        proven.forEach { r ->
            val p = StalkerVodCommandPolicy.plan(r)
            assertFalse(p is Plan.Direct && StalkerVodCommandPolicy.isRawUrl(p.cmd), "raw url sent as cmd for $r")
        }
        listOf(Row(1, "http://a/b"), Row(1, "http://a/b", hasFiles = 0)).forEach { r ->
            assertEquals(Plan.Direct("http://a/b", "/media/1.mpg"), StalkerVodCommandPolicy.plan(r), "unproven $r")
        }
    }

    @Test
    fun `reply parsing resolves relative links and strips launcher prefixes`() {
        val origin = "http://portal.test:8080"
        assertEquals("http://portal.test:8080/media/x.m3u8?t=1", StalkerCreateLinkReply.urlOf("/media/x.m3u8?t=1", origin), "relative path")
        assertEquals("http://portal.test:8080/media/x.m3u8", StalkerCreateLinkReply.urlOf("ffmpeg /media/x.m3u8", origin), "prefixed relative")
        assertEquals("http://portal.test:8080/?token=abc", StalkerCreateLinkReply.urlOf("?token=abc", origin), "query only")
        assertEquals("http://cdn.test/a.m3u8", StalkerCreateLinkReply.urlOf("ffmpeg http://cdn.test/a.m3u8", origin), "absolute")
        assertEquals("http://portal.test:8080/p/q", StalkerCreateLinkReply.urlOf("weird /p/q", origin), "unknown launcher keeps the tail")
        assertEquals(null, StalkerCreateLinkReply.urlOf("", origin), "blank")
        assertEquals(null, StalkerCreateLinkReply.urlOf("link_fault", origin), "junk")
    }

    @Test
    fun `reply parsing names the portal's refusal`() {
        fun reason(err: String?) = (StalkerCreateLinkReply.parse("", err, "http://p") as StalkerLinkResult.Refused).reason
        assertEquals(StalkerLinkRefusal.NOTHING_TO_PLAY, reason("nothing_to_play"), "nothing_to_play")
        assertEquals(StalkerLinkRefusal.SESSION_LIMIT, reason("limit"), "limit")
        assertEquals(StalkerLinkRefusal.ACCESS_DENIED, reason("access_denied"), "access_denied")
        assertEquals(StalkerLinkRefusal.PORTAL_ERROR, reason("whatever"), "unknown error")
        assertEquals(StalkerLinkRefusal.NO_LINK, reason(null), "no error field")
        assertTrue(StalkerCreateLinkReply.parse("ffmpeg http://a/b", "limit", "http://p") is StalkerLinkResult.Link, "a link wins over a stale error")
    }
}
