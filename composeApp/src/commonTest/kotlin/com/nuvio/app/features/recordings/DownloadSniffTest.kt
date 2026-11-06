package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.internal.DownloadSniff
import com.nuvio.app.features.recordings.internal.RecordingSanityGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadSniffTest {
    private fun b(s: String) = s.encodeToByteArray()
    private val ts = byteArrayOf(0x47, 0x40, 0x11, 0x10, 0, 0, 0, 0)

    @Test
    fun `a mpegurl content type is a playlist whatever the bytes`() {
        for (t in listOf("application/vnd.apple.mpegurl", "application/x-mpegURL", "audio/mpegurl", "audio/x-mpegurl; charset=utf-8", "APPLICATION/VND.APPLE.MPEGURL")) {
            assertTrue(DownloadSniff.isHlsPlaylist(t, ts), t)
        }
    }

    @Test
    fun `the extm3u magic is a playlist whatever the type`() {
        assertTrue(DownloadSniff.isHlsPlaylist("text/plain", b("#EXTM3U\n#EXT-X-VERSION:3")))
        assertTrue(DownloadSniff.isHlsPlaylist(null, b("#EXTM3U")))
        assertTrue(DownloadSniff.isHlsPlaylist("application/octet-stream", b("#EXTM3U\r\n")))
    }

    @Test
    fun `a byte order mark or leading blank lines do not hide the magic`() {
        assertTrue(DownloadSniff.isHlsPlaylist(null, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + b("#EXTM3U")))
        assertTrue(DownloadSniff.isHlsPlaylist(null, b("\n  #EXTM3U")))
    }

    @Test
    fun `media bytes and other text are not a playlist`() {
        assertFalse(DownloadSniff.isHlsPlaylist("video/mp2t", ts))
        assertFalse(DownloadSniff.isHlsPlaylist(null, ts))
        assertFalse(DownloadSniff.isHlsPlaylist("text/plain", b("<html>nope</html>")))
        assertFalse(DownloadSniff.isHlsPlaylist(null, ByteArray(0)))
        assertFalse(DownloadSniff.isHlsPlaylist("video/mp4", b("#EXT")), "a truncated magic is not the magic")
    }

    @Test
    fun `a tiny file for a long programme is not a media file`() {
        val p30 = 30 * MIN
        assertTrue(RecordingSanityGuard.implausiblyTiny(p30, 1_024))
        assertTrue(RecordingSanityGuard.implausiblyTiny(60_000, 64L * 1024 - 1))
        assertFalse(RecordingSanityGuard.implausiblyTiny(p30, 64L * 1024))
        assertFalse(RecordingSanityGuard.implausiblyTiny(59_999, 1_024), "a programme under a minute may be tiny")
        assertFalse(RecordingSanityGuard.implausiblyTiny(0, 1_024), "unknown length = no judgement")
        assertFalse(RecordingSanityGuard.implausiblyTiny(p30, 200L * 1024 * 1024))
    }

    @Test
    fun `the guard names a playlist as unsupported and other tiny files as retryable network`() {
        assertEquals(RecordingProblem.UNSUPPORTED_FORMAT, RecordingSanityGuard.verdict(30 * MIN, 900, b("#EXTM3U\n")))
        assertEquals(RecordingProblem.NETWORK, RecordingSanityGuard.verdict(30 * MIN, 900, b("<html>")))
        assertNull(RecordingSanityGuard.verdict(30 * MIN, 500L * 1024 * 1024, ts))
        assertNull(RecordingSanityGuard.verdict(10_000, 900, b("<html>")))
    }
}
