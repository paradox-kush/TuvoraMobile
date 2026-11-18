package com.nuvio.app.features.iptv

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.iptv.match.MatchDbDriver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Regression (Apple TV device pass): a channel opened from the guide, whose list was never registered in
 * [XtreamItemRegistry] on this device, played with the engine's "libmpv" UA and no Referer. The channel's own
 * playlist headers must come from the content DB row when the registry has nothing.
 */
class M3uChannelHeadersLookupTest {

    private val account = XtreamAccount(
        id = "m3u:headers-lookup",
        name = "Headers",
        baseUrl = "",
        username = "",
        password = "",
        sourceType = SOURCE_TYPE_M3U_FILE,
        fileName = "channels.m3u",
    )

    @BeforeTest
    fun setUp() = runBlocking {
        IptvContentDb.closeForTests()
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        MatchDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        XtreamRepository.installAccountsForTest(listOf(account))
        XtreamItemRegistry.resetForProfile()
    }

    @Test
    fun `an unregistered M3U channel's headers are read from its stored row`() = runBlocking {
        val headers = mapOf("User-Agent" to "Branch/1.0", "Referer" to "https://ref.example/")
        IptvContentDb.beginIngest(account.id)
        IptvContentDb.insertChunk(
            account.id,
            channels = listOf(
                IptvStreamRow(sid = 9, name = "Hdr", logo = null, tvgId = null, categoryId = "c", url = "http://cdn.example.com/9.ts", ext = null, streamHeaders = headers),
            ),
            vod = emptyList(), series = emptyList(), episodes = emptyList(),
            categories = listOf(Triple("live", "c", "General")),
        )
        IptvContentDb.finishIngest(account.id, liveCount = 1, vodCount = 0, seriesCount = 0)

        assertEquals(headers, XtreamItemRegistry.liveRequestHeadersFor(XtreamItemRegistry.liveId(account.id, 9)))
        assertEquals(emptyMap(), XtreamItemRegistry.liveRequestHeadersFor(XtreamItemRegistry.liveId(account.id, 10)), "no row, no headers")
    }
}
