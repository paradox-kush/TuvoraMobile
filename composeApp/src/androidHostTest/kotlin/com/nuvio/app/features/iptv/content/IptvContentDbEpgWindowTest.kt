package com.nuvio.app.features.iptv.content

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Real-SQLite round-trips for the WP1 schema additions: the windowed EPG read (SUBSTR-projected
 * desc), the atomic per-channel refill + lazy-fetch stamp, pruning, has_archive, and the Xtream
 * channel flags. Host tests have no Android Context, so the bundled in-memory driver is
 * installed via [IptvContentDbDriver.openForTests] (the StalkerRequestCountTest idiom).
 *
 * IptvContentDb is a process-wide singleton over ONE connection: playlists are namespaced per
 * test so classes sharing the JVM never see each other's rows.
 */
class IptvContentDbEpgWindowTest {

    @BeforeTest
    fun setUpDb() {
        IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
    }

    private fun programme(
        channel: String,
        startMs: Long,
        endMs: Long,
        title: String,
        desc: String? = null,
        hasArchive: Boolean = false,
    ) = EpgProgrammeRow(channel, startMs, endMs, title, desc, hasArchive)

    /** Seeds a COMPLETE live generation the way a real refresh does: stage into the shadow, then swap
     *  it in with finishEpg. (insertEpgChunk alone now only fills the shadow, not the live guide.) */
    private suspend fun seedEpg(pid: String, rows: List<EpgProgrammeRow>) {
        IptvContentDb.beginEpg(pid)
        IptvContentDb.insertEpgChunk(pid, rows)
        IptvContentDb.finishEpg(pid, rows.size)
    }

    @Test
    fun `epg window truncates descriptions and the full-desc getter returns the whole text`() = runBlocking {
        val pid = "wp1:window"
        val longDesc = "x".repeat(2_000)
        IptvContentDb.refillChannelEpg(
            pid, "bbc.uk",
            listOf(programme("bbc.uk", 1_000L, 2_000L, "Show", desc = longDesc)),
            fetchedAtMs = 50L,
        )

        val window = IptvContentDb.epgWindow(pid, "bbc.uk", fromMs = 0L, toMs = 10_000L)
        assertEquals(1, window.size)
        // The projection truncates in SQLite — the heap never sees more than 600 chars.
        assertEquals(600, window[0].desc?.length)
        // The single-programme getter still has the whole text.
        assertEquals(2_000, IptvContentDb.epgFullDesc(pid, "bbc.uk", 1_000L)?.length)
    }

    @Test
    fun `epg window returns only programmes overlapping the range in start order`() = runBlocking {
        val pid = "wp1:overlap"
        IptvContentDb.refillChannelEpg(
            pid, "cnn.us",
            listOf(
                programme("cnn.us", 1_000L, 2_000L, "Before"),
                programme("cnn.us", 2_000L, 3_000L, "SpansFrom"),
                programme("cnn.us", 3_000L, 4_000L, "Inside"),
                programme("cnn.us", 4_000L, 5_000L, "SpansTo"),
                programme("cnn.us", 5_000L, 6_000L, "After"),
            ),
            fetchedAtMs = 50L,
        )
        val titles = IptvContentDb.epgWindow(pid, "cnn.us", fromMs = 2_500L, toMs = 4_500L).map { it.title }
        assertEquals(listOf("SpansFrom", "Inside", "SpansTo"), titles)
    }

    @Test
    fun `channel refill replaces only that channel and stamps its fetch time`() = runBlocking {
        val pid = "wp1:refill"
        seedEpg(
            pid,
            listOf(
                programme("bbc.uk", 1_000L, 2_000L, "Old A"),
                programme("cnn.us", 1_000L, 2_000L, "Other channel"),
            ),
        )

        IptvContentDb.refillChannelEpg(
            pid, "bbc.uk",
            listOf(programme("bbc.uk", 2_000L, 3_000L, "New A")),
            fetchedAtMs = 777L,
        )

        // The refilled channel shows ONLY the new batch; the sibling is untouched.
        assertEquals(listOf("New A"), IptvContentDb.epgWindow(pid, "bbc.uk", 0L, 10_000L).map { it.title })
        assertEquals(listOf("Other channel"), IptvContentDb.epgWindow(pid, "cnn.us", 0L, 10_000L).map { it.title })
        // The lazy-fetch gate: stamped for the refilled channel, absent for the other.
        assertEquals(777L, IptvContentDb.epgChannelFetchedAt(pid, "bbc.uk"))
        assertNull(IptvContentDb.epgChannelFetchedAt(pid, "cnn.us"))
    }

    @Test
    fun `an empty refill clears the channel and still stamps the gate`() = runBlocking {
        val pid = "wp1:empty-refill"
        seedEpg(pid, listOf(programme("bbc.uk", 1_000L, 2_000L, "Stale")))
        IptvContentDb.refillChannelEpg(pid, "bbc.uk", emptyList(), fetchedAtMs = 900L)
        assertTrue(IptvContentDb.epgWindow(pid, "bbc.uk", 0L, 10_000L).isEmpty())
        // A provider with no guide for the channel is remembered — the gate stops re-asking.
        assertEquals(900L, IptvContentDb.epgChannelFetchedAt(pid, "bbc.uk"))
    }

    @Test
    fun `prune drops programmes that ended before the cutoff`() = runBlocking {
        val pid = "wp1:prune"
        seedEpg(
            pid,
            listOf(
                programme("bbc.uk", 0L, 1_000L, "Long gone"),
                programme("bbc.uk", 1_000L, 2_000L, "Just ended"),
                programme("bbc.uk", 2_000L, 3_000L, "Still relevant"),
            ),
        )
        IptvContentDb.pruneEpg(pid, cutoffMs = 2_500L)
        assertEquals(listOf("Still relevant"), IptvContentDb.epgWindow(pid, "bbc.uk", 0L, 10_000L).map { it.title })
    }

    @Test
    fun `has archive round-trips through insert and every epg read`() = runBlocking {
        val pid = "wp1:archive"
        seedEpg(
            pid,
            listOf(
                programme("bbc.uk", 1_000L, 2_000L, "Replayable", hasArchive = true),
                programme("bbc.uk", 2_000L, 3_000L, "Live only"),
            ),
        )
        val window = IptvContentDb.epgWindow(pid, "bbc.uk", 0L, 10_000L)
        assertEquals(listOf(true, false), window.map { it.hasArchive })
        val around = IptvContentDb.epgAround(pid, "bbc.uk", atMs = 1_500L, limit = 2)
        assertEquals(listOf(true, false), around.map { it.hasArchive })
    }

    // --- EPG generation swap: a failed / cancelled / empty refresh must not blank the guide ---

    @Test
    fun `a refresh abandoned before finishEpg keeps the previously stored generation`() = runBlocking {
        val pid = "epg:abandoned"
        seedEpg(pid, listOf(programme("bbc.uk", 2_000L, 3_000L, "Prior")))
        // A refresh that begins and stages partial rows but never finishes (network drop / cancellation):
        // the live guide is untouched because the shadow is only swapped in by finishEpg.
        IptvContentDb.beginEpg(pid)
        IptvContentDb.insertEpgChunk(pid, listOf(programme("bbc.uk", 10_000L, 11_000L, "Partial")))
        assertEquals(listOf("Prior"), IptvContentDb.epgWindow(pid, "bbc.uk", 0L, 20_000L).map { it.title })
    }

    @Test
    fun `an empty completed refresh keeps the prior generation but still throttles`() = runBlocking {
        val pid = "epg:empty-keep"
        seedEpg(pid, listOf(programme("bbc.uk", 2_000L, 3_000L, "Prior")))
        IptvContentDb.beginEpg(pid)
        IptvContentDb.finishEpg(pid, 0, keepPriorIfEmpty = true) // the real-fetch-parsed-nothing path
        assertEquals(listOf("Prior"), IptvContentDb.epgWindow(pid, "bbc.uk", 0L, 20_000L).map { it.title })
        // Meta present (throttle advanced) and consistent with what is actually live — not zero.
        assertEquals(1, IptvContentDb.epgMeta(pid)?.programmeCount)
    }

    @Test
    fun `an explicit clear empties the guide`() = runBlocking {
        val pid = "epg:clear"
        seedEpg(pid, listOf(programme("bbc.uk", 2_000L, 3_000L, "Prior")))
        IptvContentDb.beginEpg(pid)
        IptvContentDb.finishEpg(pid, 0, keepPriorIfEmpty = false) // XmltvClient.clear() / no-tvg-ids path
        assertTrue(IptvContentDb.epgWindow(pid, "bbc.uk", 0L, 20_000L).isEmpty())
        assertEquals(0, IptvContentDb.epgMeta(pid)?.programmeCount)
    }

    @Test
    fun `a successful refresh swaps the whole generation atomically leaving no old rows`() = runBlocking {
        val pid = "epg:swap"
        seedEpg(
            pid,
            listOf(
                programme("bbc.uk", 2_000L, 3_000L, "Old"),
                programme("cnn.us", 2_000L, 3_000L, "Old CNN"),
            ),
        )
        seedEpg(pid, listOf(programme("bbc.uk", 2_000L, 3_000L, "New")))
        // New generation fully visible; EVERY old row is gone, including the channel it didn't mention.
        assertEquals(listOf("New"), IptvContentDb.epgWindow(pid, "bbc.uk", 0L, 20_000L).map { it.title })
        assertTrue(IptvContentDb.epgWindow(pid, "cnn.us", 0L, 20_000L).isEmpty())
        assertEquals(1, IptvContentDb.epgMeta(pid)?.programmeCount)
    }

    @Test
    fun `xtream channel flags round-trip through ingest and lineup paths`() = runBlocking {
        val pid = "wp1:flags"
        val flagged = IptvStreamRow(
            sid = 1, name = "Flagged", logo = null, tvgId = null, categoryId = "g",
            url = "http://h/1.ts", ext = null, useHttpTmpLink = true, useLoadBalancing = true,
        )
        val plain = IptvStreamRow(
            sid = 2, name = "Plain", logo = null, tvgId = null, categoryId = "g",
            url = "http://h/2.ts", ext = null,
        )
        IptvContentDb.insertChunk(pid, channels = listOf(flagged, plain), vod = emptyList(), series = emptyList(), episodes = emptyList(), categories = emptyList())

        val bySid = IptvContentDb.channelRow(pid, 1)
        assertEquals(true, bySid?.useHttpTmpLink)
        assertEquals(true, bySid?.useLoadBalancing)
        assertEquals(false, IptvContentDb.channelRow(pid, 2)?.useHttpTmpLink)

        val paged = IptvContentDb.pageChannels(pid, categoryId = null, offset = 0, limit = 10).associateBy { it.sid }
        assertEquals(true, paged[1]?.useHttpTmpLink)
        assertEquals(true, paged[1]?.useLoadBalancing)
        assertEquals(false, paged[2]?.useLoadBalancing)

        // The Stalker lineup path persists them too.
        val pid2 = "wp1:flags-lineup"
        IptvContentDb.replaceLiveLineup(pid2, listOf(flagged), categories = listOf("g" to "Group"))
        assertEquals(true, IptvContentDb.channelsFor(pid2, null).single().useHttpTmpLink)
    }
}
