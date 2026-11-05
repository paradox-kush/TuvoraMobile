package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.CatchUpEpgRepository
import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Device pass (Android TV emulator, twin of the TV test): a Stalker portal's Live TV guide showed NO past
 * programmes, so a replay could never be started. Portal JSON in, what the guide can travel to and show out.
 */
class StalkerCatchUpGuideTest {

    private val half = 30 * 60_000L
    private val base = System.currentTimeMillis() / half * half

    /** 30-minute programmes from now-24h to now+6h, past rows archived, ids like a real Ministra EPG. */
    private fun epgRows(): String = (-48..11).joinToString(",") { i ->
        val start = (base + i * half) / 1000
        """{"id":"${9000 + i + 48}","name":"P$i","descr":"d","start_timestamp":"$start","stop_timestamp":"${start + 1800}","mark_archive":${if (i < 0) 1 else 0}}"""
    }

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        when (Regex("action=([^&]+)").find(url)?.groupValues?.get(1)) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{"status":0}}"""
            "get_genres" -> """{"js":[{"id":"1","title":"News"}]}"""
            "get_all_channels" -> """{"js":{"data":[
                {"id":"1","name":"Archive 48h","cmd":"ffrt http://localhost/ch/1","tv_genre_id":"1","tv_archive":"1","tv_archive_duration":"48"},
                {"id":"2","name":"Archive 25h","cmd":"ffrt http://localhost/ch/2","tv_genre_id":"1","tv_archive":1,"tv_archive_duration":25},
                {"id":"3","name":"No archive","cmd":"ffrt http://localhost/ch/3","tv_genre_id":"1","tv_archive":"0","tv_archive_duration":"0"}]}}"""
            "get_epg_info" -> """{"js":{"data":{"1":[${epgRows()}]}}}"""
            else -> """{"js":[]}"""
        }
    }

    private fun account(id: String) = XtreamAccount(
        id = id, name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6",
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
    fun `a portal archive depth in hours reaches the channel as days`() = runBlocking {
        val channels = StalkerClient.liveChannels(account("scg-days"), null).getOrThrow().associateBy { it.streamId }
        assertEquals(2, channels.getValue(1).catchUpDays, "48 hours is 2 days")
        assertEquals(2, channels.getValue(2).catchUpDays, "25 hours rounds UP to 2 days")
        assertEquals(0, channels.getValue(3).catchUpDays, "no archive, no depth")
    }

    @Test
    fun `opening a Stalker channel's history lands the aired programmes with their ids`() = runBlocking {
        val acc = account("scg-history")
        StalkerClient.liveChannels(acc, null).getOrThrow()
        // What the guide awaits before reading its window: for Stalker that is the bulk guide.
        CatchUpEpgRepository.ensureHistory(acc, 1)
        val past = CatchUpEpgRepository.window(acc, 1, base - 2 * 60 * 60_000L, base)
        assertEquals(4, past.size, "four past half-hours")
        assertTrue(past.all { it.hasArchive == true }, "every past row carries the portal's mark_archive")
        assertTrue(past.all { !it.programmeId.isNullOrBlank() }, "and the portal's programme id")
    }

    @Test
    fun `the guide offers replay on a Stalker archive channel`() {
        assertTrue(CatchUpEpgRepository.supportsCatchUp(account("scg-offer"), null), "a Stalker playlist can replay its archive")
    }
}
