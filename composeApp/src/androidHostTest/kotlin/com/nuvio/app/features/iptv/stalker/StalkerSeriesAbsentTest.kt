package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamHubSection
import com.nuvio.app.features.iptv.XtreamHubSections
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UX147 end to end through the real client: the series probe's verdict is what the hub's Series tab
 * is built from. A portal that answers "nothing" for `type=series` AND has no series-flagged vod loses
 * the tab; anything less certain keeps it.
 */
class StalkerSeriesAbsentTest {

    private var seriesReply = """{"js":null}"""
    private var vodCategories = """{"js":[{"id":"*","title":"All"}]}"""

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        val type = Regex("type=([^&]+)").find(url)?.groupValues?.get(1)
        when {
            action == "handshake" -> """{"js":{"token":"T"}}"""
            action == "get_profile" -> """{"js":{"id":"1","status":0}}"""
            type == "series" && action == "get_categories" -> seriesReply
            type == "vod" && action == "get_categories" -> vodCategories
            else -> """{"js":[]}"""
        }
    }

    private fun account() = XtreamAccount(
        id = "stalker|absent-${kotlin.random.Random.nextLong()}", name = "Portal", baseUrl = "http://portal.test/stalker_portal/c/",
        username = "", password = "", sourceType = "stalker", macAddress = "00:1A:79:00:00:02",
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
        StalkerClient.clearMemoryCachesForTest()
    }

    private fun tabs(acc: XtreamAccount) = runBlocking {
        XtreamHubSections.visible({ true }, seriesAbsent = StalkerClient.seriesAbsent(acc))
    }

    @Test
    fun `a portal with no series module and no series vod loses the Series tab`() = runBlocking {
        val acc = account()
        assertTrue(StalkerClient.seriesCategories(acc).isFailure, "the card path: the portal said nothing for Series")
        assertTrue(StalkerClient.seriesAbsent(acc))
        assertFalse(XtreamHubSection.SERIES in tabs(acc))
        assertEquals(listOf(XtreamHubSection.LIVE, XtreamHubSection.MOVIES), tabs(acc))
    }

    @Test
    fun `a Ministra portal that serves series through vod keeps the tab`() = runBlocking {
        vodCategories = """{"js":[{"id":"*","title":"All"},{"id":"1","title":"Drama"}]}"""
        val acc = account()
        assertEquals(listOf("1"), StalkerClient.seriesCategories(acc).getOrThrow().map { it.id })
        assertFalse(StalkerClient.seriesAbsent(acc))
        assertTrue(XtreamHubSection.SERIES in tabs(acc))
    }

    @Test
    fun `an XC portal with series keeps the tab`() = runBlocking {
        seriesReply = """{"js":[{"id":"*","title":"All"},{"id":"7","title":"Drama"}]}"""
        val acc = account()
        assertEquals(listOf("7"), StalkerClient.seriesCategories(acc).getOrThrow().map { it.id })
        assertTrue(XtreamHubSection.SERIES in tabs(acc))
    }

    @Test
    fun `an empty list without a module report is unknown and keeps the tab`() = runBlocking {
        seriesReply = """{"js":[]}"""
        val acc = account()
        StalkerClient.seriesCategories(acc)
        assertFalse(StalkerClient.seriesAbsent(acc), "empty alone is not positive evidence")
        assertTrue(XtreamHubSection.SERIES in tabs(acc))
    }

    @Test
    fun `a refreshed playlist forgets the verdict`() = runBlocking {
        val acc = account()
        StalkerClient.seriesCategories(acc)
        assertTrue(StalkerClient.seriesAbsent(acc))
        StalkerClient.forgetSeriesVerdict(acc.id)
        assertFalse(StalkerClient.seriesAbsent(acc))
    }
}
