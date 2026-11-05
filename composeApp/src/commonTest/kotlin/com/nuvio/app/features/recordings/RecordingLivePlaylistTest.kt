package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.RecordingLivePlaylist
import com.nuvio.app.features.recordings.internal.RecordingLivePlaylist.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingLivePlaylistTest {
    private fun part(vararg pieces: Pair<String, String>, ended: Boolean = true, discontinuityAt: Set<Int> = emptySet(), map: String? = null): String =
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:").append(if (map != null) 6 else 3).append("\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n")
            if (map != null) append("#EXT-X-MAP:URI=\"").append(map).append("\"\n")
            pieces.forEachIndexed { i, (dur, name) ->
                if (i in discontinuityAt) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:").append(dur).append(",\n").append(name).append('\n')
            }
            if (ended) append("#EXT-X-ENDLIST\n")
        }

    private val p0 = part("6.000" to "seg0.ts", "5.500" to "seg1.ts")
    private val p1 = part("4.000" to "seg0.ts", ended = false)

    @Test
    fun `parts are joined in order with their directory in front of each piece`() {
        val c = assertNotNull(RecordingLivePlaylist.combine(listOf(Part("p0", p0), Part("p1", p1))))
        assertEquals(listOf("p0/seg0.ts", "p0/seg1.ts", "p1/seg0.ts"), c.files)
        val names = c.text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(c.files, names)
    }

    @Test
    fun `the join is marked as a discontinuity and the first part is not`() {
        val lines = assertNotNull(RecordingLivePlaylist.combine(listOf(Part("p0", p0), Part("p1", p1)))).text.lines()
        assertEquals(1, lines.count { it == "#EXT-X-DISCONTINUITY" })
        val at = lines.indexOf("#EXT-X-DISCONTINUITY")
        assertEquals("p1/seg0.ts", lines[at + 2])
        assertTrue(lines.indexOf("p0/seg0.ts") < at)
    }

    @Test
    fun `the combined playlist is an ended event playlist whatever the parts said`() {
        val text = assertNotNull(RecordingLivePlaylist.combine(listOf(Part("p0", p0), Part("p1", p1)))).text
        assertTrue(text.startsWith("#EXTM3U"))
        assertTrue("#EXT-X-PLAYLIST-TYPE:EVENT" in text)
        assertTrue("#EXT-X-MEDIA-SEQUENCE:0" in text)
        assertEquals(1, text.lines().count { it == "#EXT-X-ENDLIST" }, "one ENDLIST, at the end")
        assertEquals("#EXT-X-ENDLIST", text.lines().last { it.isNotEmpty() })
    }

    @Test
    fun `the duration is the sum of the pieces and the target covers the longest`() {
        val c = assertNotNull(RecordingLivePlaylist.combine(listOf(Part("p0", p0), Part("p1", p1))))
        assertEquals(15_500L, c.durationMs)
        assertEquals(15_500L, RecordingLivePlaylist.durationMs(c.text))
        assertTrue("#EXT-X-TARGETDURATION:6" in c.text)
        val longer = part("9.200" to "seg0.ts")
        assertTrue("#EXT-X-TARGETDURATION:10" in assertNotNull(RecordingLivePlaylist.combine(listOf(Part("p0", longer)))).text)
    }

    @Test
    fun `a part with no pieces is skipped and no pieces at all is null`() {
        val empty = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
        val c = assertNotNull(RecordingLivePlaylist.combine(listOf(Part("p0", empty), Part("p1", p1))))
        assertEquals(listOf("p1/seg0.ts"), c.files)
        assertFalse("#EXT-X-DISCONTINUITY" in c.text, "the first part that has pieces is the start, not a join")
        assertNull(RecordingLivePlaylist.combine(listOf(Part("p0", empty))))
        assertNull(RecordingLivePlaylist.combine(emptyList()))
    }

    @Test
    fun `a discontinuity a part already carried is kept`() {
        val withGap = part("6.000" to "seg0.ts", "6.000" to "seg1.ts", discontinuityAt = setOf(1))
        val lines = assertNotNull(RecordingLivePlaylist.combine(listOf(Part("p0", withGap)))).text.lines()
        assertEquals(1, lines.count { it == "#EXT-X-DISCONTINUITY" })
        assertEquals("p0/seg1.ts", lines[lines.indexOf("#EXT-X-DISCONTINUITY") + 2])
    }

    @Test
    fun `an init segment follows its part and is a file of the recording`() {
        val fmp4 = part("4.000" to "seg0.m4s", map = "init.mp4")
        val fmp4b = part("4.000" to "seg0.m4s", map = "init.mp4")
        val c = assertNotNull(RecordingLivePlaylist.combine(listOf(Part("p0", fmp4), Part("p1", fmp4b))))
        assertTrue("#EXT-X-VERSION:6" in c.text)
        val lines = c.text.lines()
        assertEquals("#EXT-X-MAP:URI=\"p0/init.mp4\"", lines.first { it.startsWith("#EXT-X-MAP") })
        val join = lines.indexOf("#EXT-X-DISCONTINUITY")
        assertEquals("#EXT-X-MAP:URI=\"p1/init.mp4\"", lines[join + 1], "the next part brings its own init after the gap")
        assertTrue("p0/init.mp4" in c.files && "p1/init.mp4" in c.files)
    }

    @Test
    fun `a playlist with no EXTINF has no duration`() {
        assertEquals(0L, RecordingLivePlaylist.durationMs("#EXTM3U\n"))
    }
}
