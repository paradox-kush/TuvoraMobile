package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.livetv.pause.HlsLiveSegmentTracker.MediaSegment
import com.nuvio.app.features.livetv.pause.HlsLiveSegmentTracker.Update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsLiveSegmentTrackerTest {
    private val base = "https://cdn.example.com/live/ch1/index.m3u8"

    private fun media(seq: Long, names: List<String>, extra: String = "", target: Int = 6): String =
        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:$seq\n$extra" +
            names.joinToString("") { "#EXTINF:6.000,\n$it\n" }

    private fun names(from: Int, to: Int, prefix: String = "s") = (from..to).map { "$prefix$it.ts" }

    private fun segs(u: Update): List<MediaSegment> = when (u) {
        is Update.NewSegments -> u.segments
        is Update.SequenceReset -> u.segments
        else -> error("no segments in $u")
    }

    @Test
    fun `the first playlist yields every segment resolved against the base`() {
        val t = HlsLiveSegmentTracker()
        val u = t.onPlaylist(media(100, names(100, 102)), base)
        assertIs<Update.NewSegments>(u)
        assertEquals(
            listOf(
                MediaSegment(100, 6_000L, "https://cdn.example.com/live/ch1/s100.ts", false),
                MediaSegment(101, 6_000L, "https://cdn.example.com/live/ch1/s101.ts", false),
                MediaSegment(102, 6_000L, "https://cdn.example.com/live/ch1/s102.ts", false),
            ),
            u.segments,
        )
        assertEquals(6_000L, t.targetDurationMs)
    }

    @Test
    fun `the same playlist again is unchanged`() {
        val t = HlsLiveSegmentTracker()
        val text = media(100, names(100, 102))
        t.onPlaylist(text, base)
        assertEquals(Update.Unchanged, t.onPlaylist(text, base))
    }

    @Test
    fun `a sliding window yields only the new sequences`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(media(100, names(100, 102)), base)
        val u = t.onPlaylist(media(101, names(101, 103)), base)
        assertEquals(listOf(103L), segs(u).map { it.sequence })
        val u2 = t.onPlaylist(media(103, names(103, 105)), base)
        assertEquals(listOf(104L, 105L), segs(u2).map { it.sequence })
        assertEquals(0, t.resetCount)
    }

    @Test
    fun `a playlist slightly behind what we saw is not a reset`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(media(100, names(100, 104)), base)
        assertEquals(Update.Unchanged, t.onPlaylist(media(99, names(99, 103)), base))
        assertEquals(0, t.resetCount)
    }

    @Test
    fun `numbering that restarts low is a sequence reset with a discontinuity on the first segment`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(media(500, names(500, 503)), base)
        val u = t.onPlaylist(media(0, names(0, 3, "n")), base)
        assertIs<Update.SequenceReset>(u)
        assertEquals(listOf(0L, 1L, 2L, 3L), u.segments.map { it.sequence })
        assertEquals(listOf(true, false, false, false), u.segments.map { it.discontinuity })
        assertEquals(1, t.resetCount)
        // and it carries on from the new numbering
        assertEquals(listOf(4L), segs(t.onPlaylist(media(1, names(1, 4, "n")), base)).map { it.sequence })
    }

    @Test
    fun `every reset increments the count`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(media(500, names(500, 503)), base)
        t.onPlaylist(media(0, names(0, 3)), base)
        t.onPlaylist(media(900, names(900, 903)), base)
        t.onPlaylist(media(0, names(0, 3, "z")), base)
        assertEquals(2, t.resetCount, "the jump forward to 900 is a gap not a reset")
    }

    @Test
    fun `the same sequences with different segments are a reset`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(media(100, names(100, 102)), base)
        val u = t.onPlaylist(media(100, names(100, 102, "b")), base)
        assertIs<Update.SequenceReset>(u)
        assertTrue(segs(u).first().discontinuity)
        assertEquals(1, t.resetCount)
    }

    @Test
    fun `a changing signature on the same segment is not a reset`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(media(100, listOf("s100.ts?token=a", "s101.ts?token=a")), base)
        assertEquals(Update.Unchanged, t.onPlaylist(media(100, listOf("s100.ts?token=b", "s101.ts?token=b")), base))
        assertEquals(0, t.resetCount)
    }

    @Test
    fun `missed segments between polls mark a discontinuity`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(media(100, names(100, 101)), base)
        val u = t.onPlaylist(media(105, names(105, 106)), base)
        assertIs<Update.NewSegments>(u)
        assertEquals(listOf(true, false), u.segments.map { it.discontinuity })
    }

    @Test
    fun `an encrypted playlist is unsupported`() {
        val t = HlsLiveSegmentTracker()
        val u = t.onPlaylist(media(0, names(0, 1), "#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n"), base)
        assertIs<Update.Unsupported>(u)
        assertTrue(u.reason.contains("AES-128"))
        assertIs<Update.Unsupported>(t.onPlaylist(media(0, names(0, 1), "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n"), base))
    }

    @Test
    fun `METHOD NONE is fine`() {
        val t = HlsLiveSegmentTracker()
        assertIs<Update.NewSegments>(t.onPlaylist(media(0, names(0, 1), "#EXT-X-KEY:METHOD=NONE\n"), base))
    }

    @Test
    fun `byte range and malformed playlists are unsupported`() {
        val t = HlsLiveSegmentTracker()
        assertIs<Update.Unsupported>(t.onPlaylist(media(0, names(0, 1), "#EXT-X-BYTERANGE:1000@0\n"), base))
        assertIs<Update.Unsupported>(t.onPlaylist("<html>nope</html>", base))
        assertIs<Update.Unsupported>(t.onPlaylist("", base))
    }

    @Test
    fun `a master playlist handed to the tracker is unsupported`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nlow.m3u8\n"
        assertIs<Update.Unsupported>(HlsLiveSegmentTracker().onPlaylist(master, base))
    }

    @Test
    fun `the init segment is captured and resolved`() {
        val t = HlsLiveSegmentTracker()
        assertNull(t.initSegmentUri)
        t.onPlaylist(media(0, names(0, 1), "#EXT-X-MAP:URI=\"init/i.mp4\"\n"), base)
        assertEquals("https://cdn.example.com/live/ch1/init/i.mp4", t.initSegmentUri)
    }

    @Test
    fun `a source discontinuity tag marks the next segment only`() {
        val t = HlsLiveSegmentTracker()
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:5.5,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nb.ts\n#EXTINF:6,\nc.ts\n"
        val u = t.onPlaylist(text, base)
        assertEquals(listOf(false, true, false), segs(u).map { it.discontinuity })
        assertEquals(listOf(5_500L, 4_000L, 6_000L), segs(u).map { it.durationMs })
    }

    @Test
    fun `a segment without EXTINF takes the target duration`() {
        val t = HlsLiveSegmentTracker()
        val u = t.onPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\nx.ts\n", base)
        assertEquals(listOf(4_000L), segs(u).map { it.durationMs })
    }

    @Test
    fun `ended follows EXT-X-ENDLIST`() {
        val t = HlsLiveSegmentTracker()
        assertFalse(t.ended)
        t.onPlaylist(media(0, names(0, 1)), base)
        assertFalse(t.ended)
        t.onPlaylist(media(0, names(0, 2), "") + "#EXT-X-ENDLIST\n", base)
        assertTrue(t.ended)
    }

    @Test
    fun `an empty media playlist is unchanged`() {
        val t = HlsLiveSegmentTracker()
        assertEquals(Update.Unchanged, t.onPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n", base))
    }

    // ---- master

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1200000,AVERAGE-BANDWIDTH=900000,RESOLUTION=640x360
        low/index.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=50000,URI="iframe.m3u8"
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=5000000,RESOLUTION=1280x720

        # a comment between
        /abs/mid.m3u8?x=1
        #EXT-X-STREAM-INF:BANDWIDTH=20000000,RESOLUTION=3840x2160
        https://other.example.com/4k.m3u8
    """.trimIndent()

    @Test
    fun `a master playlist is parsed into variants`() {
        assertEquals(
            listOf(
                LivePausePolicy.HlsVariant("https://cdn.example.com/live/ch1/low/index.m3u8", 1_200_000L, 360),
                LivePausePolicy.HlsVariant("https://cdn.example.com/abs/mid.m3u8?x=1", 5_000_000L, 720),
                LivePausePolicy.HlsVariant("https://other.example.com/4k.m3u8", 20_000_000L, 2160),
            ),
            parseHlsMaster(master, base),
        )
    }

    @Test
    fun `a media playlist has no variants`() {
        assertTrue(parseHlsMaster(media(0, names(0, 2)), base).isEmpty())
        assertTrue(parseHlsMaster("", base).isEmpty())
    }

    @Test
    fun `parsed variants feed the cap so the 4K one is not picked`() {
        val picked = LivePausePolicy.pickVariant(parseHlsMaster(master, base), LivePausePolicy.maxCaptureBitrateBps(lowMemoryTier = false))
        assertEquals(5_000_000L, picked?.bandwidthBps)
        assertFalse(picked?.uri?.contains("4k") ?: true)
        assertEquals(1_200_000L, LivePausePolicy.pickVariant(parseHlsMaster(master, base), 1_000_000L)?.bandwidthBps)
    }

    // ---- URI resolution

    @Test
    fun `resolveHlsUri handles the usual references`() {
        val b = "https://h.example.com:8080/a/b/index.m3u8?token=abc"
        assertEquals("https://h.example.com:8080/a/b/seg1.ts", resolveHlsUri(b, "seg1.ts"))
        assertEquals("https://h.example.com:8080/a/b/sub/seg1.ts?t=2", resolveHlsUri(b, "./sub/seg1.ts?t=2"))
        assertEquals("https://h.example.com:8080/a/seg1.ts", resolveHlsUri(b, "../seg1.ts"))
        assertEquals("https://h.example.com:8080/seg1.ts", resolveHlsUri(b, "../../seg1.ts"))
        assertEquals("https://h.example.com:8080/seg1.ts", resolveHlsUri(b, "../../../../seg1.ts"))
        assertEquals("https://h.example.com:8080/root/seg1.ts", resolveHlsUri(b, "/root/seg1.ts"))
        assertEquals("https://h.example.com:8080/root/seg1.ts", resolveHlsUri(b, "/x/../root/seg1.ts"))
        assertEquals("https://other.com/x.ts", resolveHlsUri(b, "https://other.com/x.ts"))
        assertEquals("https://other.com/x.ts", resolveHlsUri(b, "//other.com/x.ts"))
        assertEquals("https://h.example.com:8080/a/b/index.m3u8?other=1", resolveHlsUri(b, "?other=1"))
        assertEquals("http://h.example.com/a/s.ts", resolveHlsUri("http://h.example.com/a/index.m3u8#frag", "s.ts"))
    }

    @Test
    fun `resolveHlsUri works for a base with no path and for local files`() {
        assertEquals("https://h.example.com/s.ts", resolveHlsUri("https://h.example.com", "s.ts"))
        assertEquals("https://h.example.com/s.ts", resolveHlsUri("https://h.example.com?x=1", "s.ts"))
        assertEquals("file:///data/cache/live-pause/1/seg0.ts", resolveHlsUri("file:///data/cache/live-pause/1/index.m3u8", "seg0.ts"))
    }
}
