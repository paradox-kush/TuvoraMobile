package com.nuvio.app.features.iptv.content

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/**
 * RED-FIRST regression for the M3U/Stalker refresh atomicity gap (Overlay Build Spec v1.3.3 §5,
 * test "M3U crash mid-ingest").
 *
 * Today [IptvContentDb.beginIngest] commits a CLEAR of the whole playlist in its own transaction,
 * then [IptvContentDb.insertChunk] commits chunk by chunk, and [IptvContentDb.finishIngest] writes
 * the meta row last. A crash between the clear and the finish therefore leaves the previous
 * catalog GONE and a partial one in its place — the health gate cannot "keep previous state"
 * because nothing previous survives the clear.
 *
 * Expected after P1 (generation swap): the previous, fully-built catalog keeps serving until a
 * new generation completes and is flipped in one transaction. This test is red on the current
 * code and must go green without being rewritten.
 */
class M3uIngestCrashSafetyTest {

    @BeforeTest
    fun setUpDb() {
        IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
    }

    private fun channel(sid: Int, name: String) = IptvStreamRow(
        sid = sid, name = name, logo = null, tvgId = null, categoryId = "news",
        url = "http://m3u.example.com/$sid.ts", ext = "ts",
    )

    private val liveCategory = listOf(Triple("live", "news", "News"))

    @Test
    fun `a crash mid-ingest keeps the previous catalog serving`() = runBlocking {
        val pid = "crash-safety:m3u"

        // Ingest #1 completes: three channels, meta row written.
        IptvContentDb.beginIngest(pid)
        IptvContentDb.insertChunk(
            pid,
            channels = listOf(channel(1, "BBC ONE"), channel(2, "BBC TWO"), channel(3, "ITV")),
            vod = emptyList(), series = emptyList(), episodes = emptyList(),
            categories = liveCategory,
        )
        IptvContentDb.finishIngest(pid, liveCount = 3, vodCount = 0, seriesCount = 0)
        assertEquals(3, IptvContentDb.channelsFor(pid, null).size, "baseline catalog has 3 channels")
        assertEquals(3, IptvContentDb.ingestMeta(pid)?.liveCount, "baseline meta records 3")

        // Ingest #2 starts, writes one chunk, then the process dies before finishIngest.
        IptvContentDb.beginIngest(pid)
        IptvContentDb.insertChunk(
            pid,
            channels = listOf(channel(1, "BBC ONE")),
            vod = emptyList(), series = emptyList(), episodes = emptyList(),
            categories = liveCategory,
        )
        // (no finishIngest — simulated crash)

        // The previous, complete catalog must still be what readers see.
        val served = IptvContentDb.channelsFor(pid, null)
        assertEquals(
            3, served.size,
            "a half-built refresh must not replace the last complete catalog; readers saw ${served.size} channel(s)",
        )
        val meta = IptvContentDb.ingestMeta(pid)
        assertNotNull(meta, "the last complete build's meta row must survive an aborted refresh")
        assertEquals(3, meta.liveCount, "meta must still describe the last COMPLETE build")
    }
}
