package com.nuvio.app.features.iptv.content

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** B179 at the storage chokepoint: divider rows never become channels on either write path. */
class IptvContentDbSeparatorRowsTest {

    @BeforeTest
    fun setUpDb() {
        IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
    }

    private fun row(sid: Int, name: String, tvgId: String? = null) = IptvStreamRow(
        sid = sid, name = name, logo = null, tvgId = tvgId, categoryId = "bg", url = "http://h/$sid.ts", ext = null,
    )

    private val lineup = listOf(
        row(1, "BG: ################ Bulgaria Channels ################"),
        row(2, "BG: Bnt 2"),
        row(3, "*** UK ***"),
        row(4, "#0 Sport"),
        row(5, "*** PPV ***", tvgId = "ppv.bg"),
    )
    private val expected = listOf(2, 4, 5)

    @Test
    fun `M3U ingest chunks drop separator rows and keep real channels`() = runBlocking {
        val pid = "b179:chunk"
        IptvContentDb.insertChunk(pid, channels = lineup, vod = emptyList(), series = emptyList(), episodes = emptyList(), categories = emptyList())
        assertEquals(expected, IptvContentDb.channelsFor(pid, null).map { it.sid }.sorted())
        assertEquals(expected, IptvContentDb.pageChannels(pid, null, 0, 50).map { it.sid }.sorted())
    }

    @Test
    fun `Stalker lineup replace drops separator rows and counts only real channels`() = runBlocking {
        val pid = "b179:lineup"
        IptvContentDb.replaceLiveLineup(pid, lineup, categories = listOf("bg" to "Bulgaria"))
        assertEquals(expected, IptvContentDb.channelsFor(pid, null).map { it.sid }.sorted())
        assertEquals(expected.size, IptvContentDb.ingestMeta(pid)?.liveCount)
    }
}
