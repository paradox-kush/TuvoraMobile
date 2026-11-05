package com.nuvio.app.features.livetv.pause

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TsSegmenterTest {
    private val store = FakeSegmentStore()
    private var now = 99_999L
    private fun segmenter(keepAll: Boolean = false, onPiece: (LivePiece) -> Unit = {}) =
        TsSegmenter(store, { now }, targetMs = 6_000L, keepAll = keepAll, onPiece = onPiece)

    private fun header() = Ts.concat(Ts.pat(), Ts.pmt())
    private fun TsSegmenter.feed(vararg parts: ByteArray) { val b = Ts.concat(*parts); write(b, 0, b.size) }

    /** PCR video packets every half second, all random-access, from 0 s through [lastHalfSecond] half seconds. */
    private fun cadence(lastHalfSecond: Int): ByteArray =
        Ts.concat(*Array(lastHalfSecond + 1) { k -> Ts.media(pcrBase = k * 45_000L, rai = true, pusi = true, tag = 0x20 + k % 20) })

    @Test
    fun `first piece is about two seconds then pieces are about the target`() {
        val s = segmenter()
        s.feed(header(), cadence(39))
        assertEquals(listOf(2_000L, 6_000L, 6_000L), s.state().pieces.map { it.durationMs })
        s.finish()
        assertEquals(listOf(2_000L, 6_000L, 6_000L, 5_500L), s.state().pieces.map { it.durationMs })
        assertEquals(19_500L, s.state().totalMs)
    }

    @Test
    fun `durations come from the PCR and not the clock`() {
        val s = segmenter()
        s.feed(header(), cadence(8)) // the clock never moves
        assertEquals(2_000L, s.state().pieces.first().durationMs)
    }

    @Test
    fun `every piece starts with the PAT and PMT`() {
        val s = segmenter()
        s.feed(header(), cadence(39))
        s.finish()
        assertTrue(store.files.size >= 4)
        for ((name, bytes) in store.files) {
            assertEquals(0, bytes.size % Ts.PACKET, "$name is whole packets")
            assertContentEquals(Ts.pat(), bytes.copyOfRange(0, Ts.PACKET), "$name starts with the PAT")
            assertContentEquals(Ts.pmt(), bytes.copyOfRange(Ts.PACKET, 2 * Ts.PACKET), "$name has the PMT second")
        }
    }

    @Test
    fun `no media packet is lost across pieces`() {
        val s = segmenter()
        s.feed(header(), cadence(39))
        s.finish()
        val video = store.files.values.sumOf { b -> Ts.packetsOf(b).count { Ts.pidOf(it) == Ts.VIDEO_PID } }
        assertEquals(40, video)
        for (p in s.state().pieces) assertEquals(store.files.getValue(p.name).size.toLong(), p.bytes, "${p.name} bytes")
    }

    @Test
    fun `a clean cut needs a random access point`() {
        val s = segmenter()
        s.feed(header(), Ts.media(pcrBase = 0, rai = true), Ts.media(pcrBase = Ts.seconds(2.0), rai = true))
        assertEquals(1, s.state().pieces.size)
        // Second piece started at 2 s. A random-access packet at 3 s is too early.
        s.feed(Ts.media(pcrBase = Ts.seconds(3.0), rai = true))
        assertEquals(1, s.state().pieces.size)
        s.feed(Ts.media(pcrBase = Ts.seconds(8.5), rai = false))
        assertEquals(1, s.state().pieces.size, "past the target but not a random access point")
        s.feed(Ts.media(pcrBase = Ts.seconds(9.0), rai = true))
        assertEquals(listOf(2_000L, 7_000L), s.state().pieces.map { it.durationMs })
    }

    private fun primedAfterFirstPiece(s: TsSegmenter) {
        s.feed(header(), Ts.media(pcrBase = 0, rai = true), Ts.media(pcrBase = Ts.seconds(2.0), rai = true))
        assertEquals(1, s.state().pieces.size)
    }

    @Test
    fun `without random access a piece is forced to cut at twice the target at a PCR payload start`() {
        val s = segmenter()
        primedAfterFirstPiece(s)
        for (j in 1..11) s.feed(Ts.media(pcrBase = Ts.seconds(2.0 + j), pusi = true))
        assertEquals(1, s.state().pieces.size)
        s.feed(Ts.media(pcrBase = Ts.seconds(14.0), pusi = true))
        assertEquals(listOf(2_000L, 12_000L), s.state().pieces.map { it.durationMs })
    }

    @Test
    fun `without any marker a piece is forced to cut at three times the target`() {
        val s = segmenter()
        primedAfterFirstPiece(s)
        for (j in 1..17) s.feed(Ts.media(pcrBase = Ts.seconds(2.0 + j)))
        assertEquals(1, s.state().pieces.size)
        s.feed(Ts.media(pcrBase = Ts.seconds(20.0)))
        assertEquals(listOf(2_000L, 18_000L), s.state().pieces.map { it.durationMs })
    }

    @Test
    fun `without a PCR the monotonic clock measures the piece`() {
        val s = segmenter()
        now = 1_000L
        s.feed(header()) // the PMT opens the first piece at clock 1000
        for (k in 1..3) { now += 500L; s.feed(Ts.media(rai = true)) }
        assertEquals(0, s.state().pieces.size, "1.5 s in")
        now += 500L
        s.feed(Ts.media(rai = true))
        assertEquals(listOf(2_000L), s.state().pieces.map { it.durationMs })
    }

    @Test
    fun `junk before the first sync byte is skipped`() {
        val clean = FakeSegmentStore()
        val a = TsSegmenter(clean, { now }); val b = TsSegmenter(store, { now })
        val stream = Ts.concat(header(), cadence(20))
        a.write(stream, 0, stream.size)
        val junk = ByteArray(300) { (it % 70).toByte() } // never 0x47
        val withJunk = Ts.concat(junk, stream)
        b.write(withJunk, 0, withJunk.size)
        assertEquals(clean.files.keys, store.files.keys)
        for (k in clean.files.keys) assertContentEquals(clean.files.getValue(k), store.files.getValue(k), k)
    }

    @Test
    fun `split writes of any size give the same pieces as one write`() {
        val stream = Ts.concat(header(), cadence(39))
        val whole = FakeSegmentStore()
        TsSegmenter(whole, { now }).also { it.write(stream, 0, stream.size); it.finish() }
        val sizes = intArrayOf(1, 7, 187, 189, 500, 3)
        val s = TsSegmenter(store, { now })
        var at = 0
        var n = 0
        while (at < stream.size) {
            val take = minOf(sizes[n++ % sizes.size], stream.size - at)
            s.write(stream, at, take)
            at += take
        }
        s.finish()
        assertEquals(whole.files.keys, store.files.keys)
        for (k in whole.files.keys) assertContentEquals(whole.files.getValue(k), store.files.getValue(k), k)
        assertEquals(whole.playlists.last(), store.playlists.last())
    }

    @Test
    fun `write honours the offset and length of the buffer`() {
        val stream = Ts.concat(header(), cadence(10))
        val padded = Ts.concat(ByteArray(13) { 0x47 }, stream, ByteArray(9) { 0x47 })
        val s = segmenter()
        s.write(padded, 13, stream.size)
        assertEquals(listOf(2_000L), s.state().pieces.map { it.durationMs })
    }

    @Test
    fun `a reconnect closes the piece and marks the next one as a discontinuity`() {
        val s = segmenter()
        s.feed(header(), Ts.media(pcrBase = 0, rai = true), Ts.media(pcrBase = Ts.seconds(1.0)))
        assertEquals(0, s.state().pieces.size)
        s.reconnected()
        assertEquals(1, s.state().pieces.size, "the open piece is closed")
        assertFalse(s.state().pieces[0].discontinuity)
        // A new connection: its clock starts over.
        s.feed(header(), Ts.media(pcrBase = 5_000_000, rai = true), Ts.media(pcrBase = 5_000_000 + Ts.seconds(2.0), rai = true))
        s.finish()
        val pieces = s.state().pieces
        assertEquals(listOf(false, true), pieces.take(2).map { it.discontinuity })
        assertEquals(2_000L, pieces[1].durationMs)
        val text = store.playlists.last()
        assertTrue(text.indexOf("#EXT-X-DISCONTINUITY") in 0 until text.indexOf("seg1.ts"), text)
        assertEquals(1, Regex("#EXT-X-DISCONTINUITY").findAll(text).count())
    }

    @Test
    fun `a reconnect before anything was saved is not a discontinuity`() {
        val s = segmenter()
        s.reconnected()
        s.feed(header(), cadence(8))
        assertFalse(s.state().pieces.first().discontinuity)
    }

    @Test
    fun `a reconnect resynchronises on the new connection`() {
        val s = segmenter()
        s.feed(header(), Ts.media(pcrBase = 0, rai = true), ByteArray(50) { 1 }) // cut mid packet
        s.reconnected()
        s.feed(header(), cadence(16)) // the 6 s target applies from the second piece on
        assertEquals(2, s.state().pieces.size)
    }

    @Test
    fun `finish closes the last piece and ends the playlist`() {
        val s = segmenter()
        s.feed(header(), cadence(10))
        assertFalse(store.playlists.last().contains("#EXT-X-ENDLIST"))
        s.finish()
        assertTrue(store.playlists.last().trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertEquals(s.state().pieces.map { it.name }, Regex("seg\\d+\\.ts").findAll(store.playlists.last()).map { it.value }.toList())
    }

    @Test
    fun `a recording playlist is EVENT type and a rewind buffer is not`() {
        val rec = FakeSegmentStore()
        TsSegmenter(rec, { now }, keepAll = true).also { it.write(Ts.concat(header(), cadence(8)), 0, 188 * 11) }
        assertContains(rec.playlists.last(), "#EXT-X-PLAYLIST-TYPE:EVENT")
        val s = segmenter()
        s.feed(header(), cadence(8))
        assertFalse(store.playlists.last().contains("EVENT"))
    }

    @Test
    fun `the target duration is fixed from the start`() {
        val s = segmenter()
        s.feed(header(), cadence(39))
        s.finish()
        assertTrue(store.playlists.isNotEmpty())
        for (text in store.playlists) assertContains(text, "#EXT-X-TARGETDURATION:20\n")
    }

    @Test
    fun `dropOldest deletes the files and advances the media sequence`() {
        val s = segmenter()
        s.feed(header(), cadence(40)) // four pieces closed, a fifth open
        assertEquals(4, s.state().pieces.size)
        s.dropOldest(2)
        assertEquals(listOf("seg0.ts", "seg1.ts"), store.deleted)
        assertFalse(store.files.containsKey("seg0.ts"))
        assertTrue(store.files.containsKey("seg2.ts"))
        val st = s.state()
        assertEquals(listOf(2L, 3L), st.pieces.map { it.sequence })
        assertEquals(8_000L, st.trimmedMs)
        assertEquals(12_000L, st.totalMs, "totalMs is what is still buffered")
        val text = store.playlists.last()
        assertContains(text, "#EXT-X-MEDIA-SEQUENCE:2\n")
        assertFalse(text.contains("seg0.ts") || text.contains("seg1.ts"))
    }

    @Test
    fun `dropOldest never empties the playlist`() {
        val s = segmenter()
        s.feed(header(), cadence(40))
        s.dropOldest(0)
        s.dropOldest(-3)
        assertEquals(4, s.state().pieces.size)
        s.dropOldest(99)
        assertEquals(0, s.state().pieces.size)
        assertEquals(listOf("seg0.ts", "seg1.ts", "seg2.ts", "seg3.ts"), store.deleted)
    }

    @Test
    fun `dropOldest rewrites the playlist before it deletes the files`() {
        val s = segmenter()
        s.feed(header(), cadence(40))
        store.events.clear()
        s.dropOldest(2)
        assertEquals(listOf("playlist", "delete:seg0.ts", "delete:seg1.ts"), store.events)
    }

    @Test
    fun `the tables that open the first piece are not written twice`() {
        val s = segmenter()
        s.feed(header(), cadence(10))
        val first = Ts.packetsOf(store.files.getValue("seg0.ts"))
        assertEquals(1, first.count { Ts.pidOf(it) == 0 })
        assertEquals(1, first.count { Ts.pidOf(it) == Ts.PMT_PID })
    }

    @Test
    fun `a piece is not judged for a cut before it holds a media packet`() {
        val s = segmenter()
        now = 0L
        s.feed(header()) // opens piece 0 with tables only
        now = 50_000L
        s.feed(Ts.media(rai = true)) // far past every limit, but the first media packet always goes in
        assertEquals(0, s.state().pieces.size)
        assertTrue(store.deleted.isEmpty(), "no empty piece was opened and thrown away")
        assertEquals(listOf("seg0.ts"), store.files.keys.toList())
    }

    @Test
    fun `the first piece target is configurable`() {
        val s = TsSegmenter(store, { now }, targetMs = 6_000L, firstTargetMs = 4_000L)
        s.feed(header(), cadence(10))
        assertEquals(listOf(4_000L), s.state().pieces.map { it.durationMs })
    }

    @Test
    fun `the target duration only grows`() {
        val s = TsSegmenter(store, { now }, targetMs = 10_000L)
        s.feed(header(), Ts.media(pcrBase = 0, rai = true), Ts.media(pcrBase = Ts.seconds(2.0), rai = true))
        assertContains(store.playlists.last(), "#EXT-X-TARGETDURATION:20\n")
        for (j in 1..30) s.feed(Ts.media(pcrBase = Ts.seconds(2.0 + j))) // no marker: forced at 3x = 30 s
        assertEquals(30_000L, s.state().pieces[1].durationMs)
        assertContains(store.playlists.last(), "#EXT-X-TARGETDURATION:30\n")
        s.feed(Ts.media(pcrBase = Ts.seconds(42.0), rai = true)) // a 10 s piece
        assertEquals(3, s.state().pieces.size)
        for (text in store.playlists.takeLast(2)) assertContains(text, "#EXT-X-TARGETDURATION:30\n")
    }

    @Test
    fun `onPiece fires after the playlist is updated`() {
        val seen = mutableListOf<Triple<String, Int, Boolean>>()
        val s = segmenter(onPiece = { p -> seen += Triple(p.name, store.playlists.size, store.playlists.last().contains(p.name)) })
        s.feed(header(), cadence(20))
        assertEquals(listOf("seg0.ts", "seg1.ts"), seen.map { it.first })
        assertTrue(seen.all { it.third })
        assertEquals(listOf(1, 2), seen.map { it.second })
    }

    @Test
    fun `a store that cannot open a piece fails the write`() {
        store.failOpen = true
        val s = segmenter()
        assertFailsWith<TestIoException> { s.feed(header(), cadence(2)) }
    }

    @Test
    fun `a store that cannot write fails the write`() {
        store.failWrite = true
        val s = segmenter()
        assertFailsWith<TestIoException> { s.feed(header(), cadence(2)) }
    }

    @Test
    fun `a store that cannot write the playlist fails the write`() {
        store.failPlaylist = true
        val s = segmenter()
        assertFailsWith<TestIoException> { s.feed(header(), cadence(10)) }
    }
}
