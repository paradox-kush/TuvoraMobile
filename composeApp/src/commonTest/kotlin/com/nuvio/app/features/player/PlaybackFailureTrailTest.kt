package com.nuvio.app.features.player

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackFailureTrailTest {
    private val c = PlaybackFailureCategoryPolicy

    @Test
    fun `an exoplayer http refusal is http_status`() {
        assertEquals("http_403", c.categorize("HTTP 403 Forbidden [ERROR_CODE_IO_BAD_HTTP_STATUS]\n\nThe provider refused"))
    }

    @Test
    fun `an mpv http refusal is http_status`() {
        assertEquals("http_404", c.categorize("[ffmpeg] http: HTTP error 404 Not Found\n[mpv] loading failed"))
    }

    @Test
    fun `exoplayer error codes map to the closed categories`() {
        assertEquals("network", c.categorize("Unable to connect [ERROR_CODE_IO_NETWORK_CONNECTION_FAILED] (network error)"))
        assertEquals("timeout", c.categorize("timeout [ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT]"))
        assertEquals("source_not_found", c.categorize("x [ERROR_CODE_IO_FILE_NOT_FOUND]"))
        assertEquals("decoder", c.categorize("OMX.decoder failed [ERROR_CODE_DECODER_INIT_FAILED]"))
        assertEquals("unsupported", c.categorize("The source is not a video ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"))
        assertEquals("unsupported", c.categorize("x [ERROR_CODE_DECODING_FORMAT_UNSUPPORTED]"))
        assertEquals("unknown", c.categorize("x [ERROR_CODE_BEHIND_LIVE_WINDOW]"))
    }

    @Test
    fun `mpv text maps to the closed categories`() {
        assertEquals("timeout", c.categorize("[ffmpeg] tcp: Connection timed out"))
        assertEquals("network", c.categorize("[ffmpeg] tcp: Failed to resolve hostname portal.example.com"))
        assertEquals("network", c.categorize("[ffmpeg] tls: certificate verify failed"))
        assertEquals("unsupported", c.categorize("unrecognized file format"))
        assertEquals("decoder", c.categorize("[vd] Could not open codec.\nvideo hevc decoder mediacodec"))
        assertEquals("source_not_found", c.categorize("[ffmpeg] file: No such file or directory"))
        assertEquals("unknown", c.categorize("loading failed"))
        assertEquals("unknown", c.categorize(""))
    }

    @Test
    fun `every category is a closed token whatever the text`() {
        val allowed = Regex("""^(network|decoder|source_not_found|unsupported|timeout|unknown|http_[1-5][0-9]{2})$""")
        val samples = listOf(
            "https://user:pass@portal.example.com/live/1.ts HTTP error 999",
            "HTTP 403", "ERROR_CODE_SOMETHING_NEW", "random words", "HTTP error 0042",
        )
        for (s in samples) assertTrue(allowed.matches(c.categorize(s)), "open value for: $s")
    }

    @Test
    fun `surface is catchup then live then vod`() {
        assertEquals(PlaybackFailureTrail.Surface.CATCHUP, PlaybackFailureTrail.surfaceOf("live", isCatchUpPlayback = true))
        assertEquals(PlaybackFailureTrail.Surface.LIVE, PlaybackFailureTrail.surfaceOf("live", isCatchUpPlayback = false))
        assertEquals(PlaybackFailureTrail.Surface.VOD, PlaybackFailureTrail.surfaceOf("movie", isCatchUpPlayback = false))
        assertEquals(PlaybackFailureTrail.Surface.VOD, PlaybackFailureTrail.surfaceOf(null, isCatchUpPlayback = false))
    }

    @Test
    fun `the entry carries only surface engine category and phase`() {
        val e = PlaybackFailureTrail.entry(
            nowMs = 5L,
            surface = PlaybackFailureTrail.Surface.VOD,
            engine = "exoplayer",
            category = "http_403",
            phase = PlaybackFailureTrail.Phase.OPEN,
        )
        assertEquals(DiagEntry(5L, DiagArea.PLAYBACK, "playback_failed", mapOf(
            "surface" to "vod", "engine" to "exoplayer", "category" to "http_403", "phase" to "open",
        )), e)
    }

    @Test
    fun `an unknown engine label is other`() {
        assertEquals("libmpv", PlaybackFailureTrail.engineCode("libmpv"))
        assertEquals("avplayer", PlaybackFailureTrail.engineCode("AvPlayer"))
        assertEquals("other", PlaybackFailureTrail.engineCode("https://evil.example"))
    }

    @Test
    fun `the recorder writes open before a frame and playing after and never the message`() {
        val out = ArrayList<DiagEntry>()
        var now = 100L
        val r = PlaybackFailureTrailRecorder(PlaybackFailureTrail.Surface.LIVE, nowMs = { now }, sink = { out += it })
        r.onSnapshot(PlayerPlaybackSnapshot(isLoading = true))
        r.onError("http://user:pass@portal.example.com/1.ts HTTP error 401", engine = "libmpv")
        r.onError("http://user:pass@portal.example.com/1.ts HTTP error 401", engine = "libmpv") // same report, polled again
        r.onError(null, engine = "libmpv")
        now = 200L
        r.onSnapshot(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 4_000L))
        r.onError("[ERROR_CODE_DECODING_FAILED]", engine = "exoplayer")

        assertEquals(2, out.size)
        assertEquals(mapOf(
            "surface" to "live", "engine" to "libmpv", "category" to "http_401", "phase" to "open",
            "res_class" to "unknown", "http_class" to "401_403", "attempt_n" to 1,
        ), out[0].fields)
        assertEquals(mapOf(
            "surface" to "live", "engine" to "exoplayer", "category" to "decoder", "phase" to "playing",
            "res_class" to "unknown", "http_class" to "none", "attempt_n" to 2,
        ), out[1].fields)
        assertEquals(200L, out[1].atMs)
        assertTrue(out.none { e -> e.fields.values.any { "portal" in it.toString() || "pass" in it.toString() } })
    }

    @Test
    fun `a resume position alone is not playing`() {
        val out = ArrayList<DiagEntry>()
        val r = PlaybackFailureTrailRecorder(PlaybackFailureTrail.Surface.VOD, nowMs = { 1L }, sink = { out += it })
        r.onSnapshot(PlayerPlaybackSnapshot(isLoading = true, positionMs = 600_000L))
        r.onError("HTTP 403", engine = "exoplayer")
        assertEquals("open", out.single().fields["phase"])
    }

    @Test
    fun `failure extras are closed classes and never the url`() {
        val out = ArrayList<DiagEntry>()
        val r = PlaybackFailureTrailRecorder(
            PlaybackFailureTrail.Surface.VOD,
            sourceUrl = "https://portal.example.com/movie/user/pass/123.mkv?token=abc",
            nowMs = { 1L },
            sink = { out += it },
        )
        r.onSnapshot(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, videoHeight = 1080, positionMs = 1_000L))
        r.onError("HTTP error 456", engine = "libmpv")
        val f = out.single().fields
        assertEquals("mkv", f["ext_class"])
        assertEquals("fhd", f["res_class"])
        assertEquals("456", f["http_class"])
        assertEquals(1, f["attempt_n"])
        assertTrue(f.values.none { "portal" in it.toString() || "token" in it.toString() })
    }
}
