package com.nuvio.app.features.livetv.pause

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalHlsPlaylistTest {
    private val pieces = listOf(
        LivePiece(4, "seg4.ts", 6_000L),
        LivePiece(5, "seg5.ts", 1_234L, discontinuity = true),
        LivePiece(6, "seg6.ts", 5_007L),
    )

    @Test
    fun `renders a live rewind buffer`() {
        val expected = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:20\n#EXT-X-MEDIA-SEQUENCE:4\n" +
            "#EXTINF:6.000,\nseg4.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:1.234,\nseg5.ts\n" +
            "#EXTINF:5.007,\nseg6.ts\n"
        assertEquals(expected, LocalHlsPlaylist.render(pieces, 20, 4, ended = false, eventType = false, initSegment = null))
    }

    @Test
    fun `a recording is EVENT type and an ended one has ENDLIST`() {
        val text = LocalHlsPlaylist.render(pieces.take(1), 20, 0, ended = true, eventType = true, initSegment = null)
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:20\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n" +
                "#EXTINF:6.000,\nseg4.ts\n#EXT-X-ENDLIST\n",
            text,
        )
    }

    @Test
    fun `an init segment is declared before the pieces and raises the version`() {
        val text = LocalHlsPlaylist.render(pieces.take(1), 8, 9, ended = false, eventType = false, initSegment = "init.mp4")
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:8\n#EXT-X-MEDIA-SEQUENCE:9\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                "#EXTINF:6.000,\nseg4.ts\n",
            text,
        )
    }

    @Test
    fun `seconds are formatted with three decimals`() {
        assertEquals("0.000", LocalHlsPlaylist.formatSeconds(0))
        assertEquals("0.007", LocalHlsPlaylist.formatSeconds(7))
        assertEquals("12.300", LocalHlsPlaylist.formatSeconds(12_300))
        assertEquals("0.000", LocalHlsPlaylist.formatSeconds(-5))
    }
}
