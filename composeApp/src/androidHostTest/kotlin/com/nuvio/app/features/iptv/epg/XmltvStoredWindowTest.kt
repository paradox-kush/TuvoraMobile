package com.nuvio.app.features.iptv.epg

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.M3uCatchUpInfo
import com.nuvio.app.features.iptv.M3uCatchUpType
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.content.IptvStreamRow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The guide's past for an M3U catch-up channel is read back from the stored XMLTV guide, by the same key
 * the now-and-next ladder uses. Real SQLite (bundled in-memory driver, the IptvContentDbEpgWindowTest idiom).
 */
class XmltvStoredWindowTest {

    @BeforeTest
    fun setUpDb() {
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
    }

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    private fun account(id: String) = XtreamAccount(
        id = id, name = "P", baseUrl = "", username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL,
    )

    @Test
    fun `a catch-up channel's stored past is returned for a window travelled back to`() = runBlocking {
        val pid = "sw:history"
        IptvContentDb.beginIngest(pid)
        IptvContentDb.insertChunk(
            pid,
            listOf(IptvStreamRow(5, "BBC One", null, "bbc.uk", "g", "http://h/5.ts", null, catchUp = M3uCatchUpInfo(M3uCatchUpType.SHIFT, null, 3))),
            emptyList(), emptyList(), emptyList(), listOf(Triple("live", "g", "G")),
        )
        IptvContentDb.finishIngest(pid, 1, 0, 0)
        val rows = listOf(
            EpgProgrammeRow("bbc.uk", now - 2 * day, now - 2 * day + hour, "Two days ago", null),
            EpgProgrammeRow("bbc.uk", now - hour, now, "Just finished", null),
        )
        IptvContentDb.beginEpg(pid)
        IptvContentDb.insertEpgChunk(pid, rows)
        IptvContentDb.finishEpg(pid, rows.size)

        val past = XmltvClient.storedWindow(account(pid), 5, now - 3 * day, now - day)
        assertEquals(listOf("Two days ago"), past.map { it.title })
        val recent = XmltvClient.storedWindow(account(pid), 5, now - 2 * hour, now)
        assertEquals(listOf("Just finished"), recent.map { it.title })
    }

    @Test
    fun `a channel with no stored guide has no history`() = runBlocking {
        assertEquals(emptyList(), XmltvClient.storedWindow(account("sw:none"), 9, now - day, now))
    }
}
