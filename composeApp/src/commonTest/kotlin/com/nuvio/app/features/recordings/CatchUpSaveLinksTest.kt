package com.nuvio.app.features.recordings

import com.nuvio.app.features.iptv.XtreamCatchUp
import com.nuvio.app.features.recordings.api.RecordingCatchUp
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.internal.CatchUpSaveLinks as L
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatchUpSaveLinksTest {
    private val from = NOW - 2 * HOUR
    private val to = NOW - HOUR

    private fun xt(ext: String?) = channel(catchUp = RecordingCatchUp.Xtream("http://p.example:8080", "user", "pass", 42, 3, ext))

    @Test
    fun `a channel without catch-up has no links`() {
        assertTrue(L.candidates(channel(), from, to, NOW).isEmpty())
        assertEquals(RecordingProblem.NO_CATCH_UP, L.problemFor(emptyList()))
    }

    @Test
    fun `xtream links are the panel's candidates with the ts form first`() {
        val expected = XtreamCatchUp.candidateUrls("http://p.example:8080", "user", "pass", 42, from, to, null, null)
        val got = L.candidates(xt(null), from, to, NOW)
        assertEquals(expected, got)
        assertTrue(got.none { L.isUnknownFormat(it) && L.isProgressive(it) }, "an extensionless php link is not progressive")
    }

    @Test
    fun `an extensionless timeshift php link is an unknown format and goes last`() {
        val got = L.candidates(xt(null), from, to, NOW)
        val known = got.filter(L::isProgressive)
        val unknown = got.filter(L::isUnknownFormat)
        assertTrue(known.isNotEmpty() && unknown.isNotEmpty(), "$got")
        assertTrue(known.any { it.endsWith(".ts") } && known.any { it.contains("extension=ts") }, "$known")
        assertEquals(got, known + unknown, "every known-progressive link precedes every unknown-format one: $got")
        assertTrue(unknown.all { "timeshift.php" in it && "extension=" !in it })
        assertTrue(unknown.none(L::isProgressive))
        assertFalse(L.isProgressive("http://p/streaming/timeshift.php?stream=1&start=2&duration=3"))
        assertFalse(L.isProgressive("http://p/timeshift.php?stream=1"))
        assertTrue(L.isUnknownFormat("http://p/streaming/timeshift.php?stream=1"))
        assertFalse(L.isUnknownFormat("http://p/streaming/timeshift.php?stream=1&extension=ts"))
        assertFalse(L.isUnknownFormat("http://p/timeshift/u/p/60/2026-11-06:02-28/1.ts"))
        assertFalse(L.isUnknownFormat("http://p/x.m3u8"))
    }

    @Test
    fun `unknown-format links alone are still worth trying but are not a known-good save`() {
        assertNull(L.problemFor(listOf("http://p/streaming/timeshift.php?stream=1")))
    }

    @Test
    fun `a panel that serves hls puts every progressive link before the playlist ones`() {
        val got = L.candidates(xt("m3u8"), from, to, NOW)
        assertTrue(got.isNotEmpty())
        val firstHls = got.indexOfFirst { !L.isProgressive(it) }
        assertTrue(firstHls > 0, "a progressive link comes first: $got")
        assertTrue(got.drop(firstHls).none(L::isProgressive), "no progressive link after the first playlist: $got")
    }

    @Test
    fun `each hls link also gets its ts twin`() {
        val got = L.candidates(xt("m3u8"), from, to, NOW)
        val hls = got.filter { it.substringBefore('?').endsWith(".m3u8") && "/timeshift/" in it }
        assertTrue(hls.isNotEmpty())
        for (h in hls) {
            val twin = h.substringBefore('?').removeSuffix(".m3u8") + ".ts"
            assertTrue(got.any { it.substringBefore('?') == twin }, "ts twin of $h in $got")
        }
    }

    @Test
    fun `the list has no duplicates`() {
        val got = L.candidates(xt("m3u8"), from, to, NOW)
        assertEquals(got.distinct(), got)
    }

    @Test
    fun `blank xtream credentials give no links`() {
        val ch = channel(catchUp = RecordingCatchUp.Xtream("http://p.example:8080", "", "", 42, 3, null))
        assertTrue(L.candidates(ch, from, to, NOW).isEmpty())
    }

    @Test
    fun `an m3u append template is built on the live url and is progressive`() {
        val ch = channel(catchUp = RecordingCatchUp.M3u("append", "?utc={utc}&end={utcend}", 3))
        val got = L.candidates(ch, from, to, NOW)
        assertEquals("${ch.liveUrl}?utc=${from / 1000}&end=${to / 1000}", got.first())
        assertTrue(got.first().let(L::isProgressive))
    }

    @Test
    fun `an m3u type the library cannot build gives no links`() {
        val ch = channel(catchUp = RecordingCatchUp.M3u("mystery", null, 3))
        assertTrue(L.candidates(ch, from, to, NOW).isEmpty())
    }

    @Test
    fun `an m3u flussonic hls link is listed after a ts twin or alone`() {
        val ch = channel(catchUp = RecordingCatchUp.M3u("flussonic", null, 3)).let {
            it.copy(liveUrl = "http://f.example/stream1/index.m3u8")
        }
        val got = L.candidates(ch, from, to, NOW)
        assertTrue(got.isNotEmpty())
        assertTrue(got.any { it.contains(".m3u8") })
        assertEquals(RecordingProblem.UNSUPPORTED_FORMAT, L.problemFor(got.filterNot(L::isProgressive)))
    }

    @Test
    fun `hls only links are an unsupported format and any progressive link is fine`() {
        assertEquals(RecordingProblem.UNSUPPORTED_FORMAT, L.problemFor(listOf("http://a/x.m3u8", "http://a/y.m3u8?token=1")))
        assertNull(L.problemFor(listOf("http://a/x.m3u8", "http://a/x.ts")))
        assertNull(L.problemFor(listOf("http://a/x.mp4")))
    }

    @Test
    fun `progressive means not a playlist`() {
        assertTrue(L.isProgressive("http://a/b/c.ts"))
        assertTrue(L.isProgressive("http://a/b/c.mp4?x=1"))
        assertTrue(L.isProgressive("http://a/b/c"))
        assertTrue(L.isProgressive("http://a/streaming/timeshift.php?stream=1&start=2&extension=ts"))
        assertFalse(L.isProgressive("http://a/b/c.m3u8"))
        assertFalse(L.isProgressive("http://a/b/C.M3U8?token=1"))
        assertFalse(L.isProgressive("http://a/streaming/timeshift.php?stream=1&extension=m3u8"))
        assertTrue(L.isProgressive("http://a/b/c.ts?next=/x.m3u8"), "the playlist name in a query value does not count")
    }

    @Test
    fun `resume asks for the rest from 30 seconds before where it got to`() {
        val resumed = L.resumeCandidates(xt(null), from, doneMs = 20 * MIN, toMs = to, nowMs = NOW)
        val expected = L.candidates(xt(null), from + 20 * MIN - 30_000L, to, NOW)
        assertEquals(expected, resumed)
        assertTrue(resumed.isNotEmpty())
    }

    @Test
    fun `resume before any footage starts from the beginning`() {
        assertEquals(L.candidates(xt(null), from, to, NOW), L.resumeCandidates(xt(null), from, 0, to, NOW))
        assertEquals(L.candidates(xt(null), from, to, NOW), L.resumeCandidates(xt(null), from, 10_000, to, NOW), "less than the overlap clamps to the start")
    }
}
