package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.content.IptvContentDb
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * B179 on the storage + read path: a divider row is STORED (so a saved id still resolves) but the lineup
 * the app lists, and the count it stores, leave it out. Paging over the raw rows is covered by
 * PagedWindowSeparatorsTest.
 */
class StalkerSeparatorRowsTest {

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        when (action) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_all_channels" -> {
                val names = listOf(
                    "BG: ################ Bulgaria Channels",
                    "BG: Bnt 2",
                    "*** UK ***",
                    "#0 Sport",
                    "PPV: ------",
                )
                val data = names.mapIndexed { i, n ->
                    """{"id":"${i + 1}","name":"$n","tv_genre_id":"g1","cmd":"ffmpeg http://localhost/ch/${i + 1}"}"""
                }.joinToString(",")
                """{"js":{"data":[$data]}}"""
            }
            else -> """{"js":[]}"""
        }
    }

    private fun account() = XtreamAccount(
        id = "b179-${kotlin.random.Random.nextLong()}", name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker", macAddress = "00:1A:79:58:B3:A7",
    )

    @BeforeTest
    fun setUp() {
        com.nuvio.app.features.iptv.content.IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal, { u, h, c -> c(fakePortal(u, h)) }) }
    }

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
    }

    @Test
    fun `the listed lineup hides divider rows but the rows stay stored and counted as shown`() = runBlocking {
        val acc = account()
        val listed = StalkerClient.liveChannels(acc, null).getOrThrow().map { it.name }
        assertEquals(setOf("BG: Bnt 2", "#0 Sport", "PPV: ------"), listed.toSet(), "real channels, incl. the event slot")
        assertEquals(3, listed.size, "the two divider rows are not listed")
        assertNotNull(IptvContentDb.channelRow(acc.id, 1), "stored: a favourite saved against the divider's id still resolves")
        assertEquals(5, IptvContentDb.channelsFor(acc.id, null).size, "nothing deleted from storage")
        assertEquals(3, IptvContentDb.ingestMeta(acc.id)?.liveCount, "the stored count is what the lists show")
    }
}
