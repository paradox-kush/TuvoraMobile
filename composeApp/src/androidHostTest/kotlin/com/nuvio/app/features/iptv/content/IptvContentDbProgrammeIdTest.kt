package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * The portal's EPG programme id (what a Stalker archive create_link names) is a NULLABLE column added in place
 * to the guide tables: rows stored before it read null, nothing is dropped, and new rows carry the id.
 */
class IptvContentDbProgrammeIdTest {

    private var preBuilt: SQLiteConnection? = null

    @BeforeTest
    fun setUp() = runBlocking {
        IptvContentDb.closeForTests()
        IptvContentDbDriver.openForTests = { preBuilt ?: BundledSQLiteDriver().open(":memory:") }
    }

    @AfterTest
    fun tearDown() = runBlocking {
        IptvContentDb.closeForTests()
        preBuilt = null
    }

    @Test
    fun `the programme id round-trips through the guide swap and the windowed read`() = runBlocking {
        val pid = "pid:roundtrip"
        IptvContentDb.beginEpg(pid)
        IptvContentDb.insertEpgChunk(
            pid,
            listOf(
                EpgProgrammeRow("5", 1_000L, 2_000L, "With id", null, programmeId = "98765"),
                EpgProgrammeRow("5", 2_000L, 3_000L, "Without", null),
            ),
        )
        IptvContentDb.finishEpg(pid, 2)
        val rows = IptvContentDb.epgWindow(pid, "5", 0L, 10_000L)
        assertEquals(listOf("98765", null), rows.map { it.programmeId })
    }

    @Test
    fun `a guide stored before the column existed is still served and takes new rows`() = runBlocking {
        val pid = "pid:legacy"
        val old = BundledSQLiteDriver().open(":memory:")
        old.execSQL("CREATE TABLE epg_programmes(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT, has_archive INTEGER NOT NULL DEFAULT 0)")
        old.execSQL("CREATE TABLE epg_programmes_shadow(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT, has_archive INTEGER NOT NULL DEFAULT 0)")
        old.execSQL("PRAGMA user_version = 5")
        old.execSQL("INSERT INTO epg_programmes(playlist_id, channel_id, start_ms, end_ms, title) VALUES('$pid', '7', 1000, 2000, 'Old row')")
        preBuilt = old

        val before = IptvContentDb.epgWindow(pid, "7", 0L, 10_000L).single()
        assertEquals("Old row", before.title, "the pre-existing row is served, not wiped")
        assertNull(before.programmeId, "an old row reads no programme id")

        IptvContentDb.beginEpg(pid)
        IptvContentDb.insertEpgChunk(pid, listOf(EpgProgrammeRow("7", 3_000L, 4_000L, "New row", null, programmeId = "42")))
        IptvContentDb.finishEpg(pid, 1)
        assertEquals("42", IptvContentDb.epgWindow(pid, "7", 2_500L, 10_000L).single().programmeId)
    }
}
