package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.nuvio.app.features.iptv.M3uCatchUpInfo
import com.nuvio.app.features.iptv.M3uCatchUpType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * M3U catch-up lives on three nullable columns added in place to `channels` (a version bump would drop
 * every cached catalog). Real SQLite: the round-trip, and a catalog written BEFORE the columns existed.
 */
class IptvContentDbCatchUpTest {

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

    private fun channel(sid: Int, catchUp: M3uCatchUpInfo?) = IptvStreamRow(
        sid = sid, name = "Ch $sid", logo = null, tvgId = "id$sid", categoryId = "news",
        url = "http://h/live/u/p/$sid.ts", ext = null, catchUp = catchUp,
    )

    private suspend fun ingest(pid: String, rows: List<IptvStreamRow>) {
        IptvContentDb.beginIngest(pid)
        IptvContentDb.insertChunk(
            pid, rows, emptyList(), emptyList(), emptyList(),
            listOf(Triple("live", "news", "News")),
        )
        IptvContentDb.finishIngest(pid, rows.size, 0, 0)
    }

    @Test
    fun `catch-up round-trips through every channel read`() = runBlocking {
        val pid = "cu:roundtrip"
        val withSource = M3uCatchUpInfo(M3uCatchUpType.APPEND, "?utc={utc}&lutc={lutc}", 5)
        val noSource = M3uCatchUpInfo(M3uCatchUpType.SHIFT, null, 2)
        ingest(pid, listOf(channel(1, withSource), channel(2, noSource), channel(3, null)))

        val all = IptvContentDb.channelsFor(pid, null).associateBy { it.sid }
        assertEquals(withSource, all.getValue(1).catchUp, "source template and days survive")
        assertEquals(noSource, all.getValue(2).catchUp, "a null source stays null")
        assertNull(all.getValue(3).catchUp, "a channel without catch-up reads none")
        assertEquals(withSource, IptvContentDb.channelRow(pid, 1)?.catchUp, "single-row read")
        assertEquals(withSource, IptvContentDb.pageChannels(pid, null, 0, 10).first { it.sid == 1 }.catchUp, "paged read")
        assertEquals(mapOf(1 to 5, 2 to 2), IptvContentDb.liveCatchUpDays(pid), "days by stream id, catch-up channels only")
    }

    @Test
    fun `a catalog written before the columns existed reads as no catch-up and takes new rows`() = runBlocking {
        val pid = "cu:legacy"
        // The v5 channels table exactly as shipped before this change: no catchup_* columns.
        val old = BundledSQLiteDriver().open(":memory:")
        old.execSQL("CREATE TABLE channels(playlist_id TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, sid INTEGER NOT NULL, category_id TEXT, name TEXT NOT NULL, logo TEXT, tvg_id TEXT, url TEXT NOT NULL, cmd TEXT, tv_archive INTEGER, use_http_tmp_link INTEGER, use_load_balancing INTEGER, PRIMARY KEY(playlist_id, generation, sid)) WITHOUT ROWID")
        old.execSQL("CREATE TABLE ingest_meta(playlist_id TEXT NOT NULL PRIMARY KEY, built_at INTEGER NOT NULL, live_count INTEGER NOT NULL, vod_count INTEGER NOT NULL, series_count INTEGER NOT NULL, epg_url TEXT, active_generation INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID")
        old.execSQL("PRAGMA user_version = 5")
        old.execSQL("INSERT INTO channels(playlist_id, generation, sid, category_id, name, url) VALUES('$pid', 0, 7, 'news', 'Old One', 'http://h/7.ts')")
        old.execSQL("INSERT INTO ingest_meta(playlist_id, built_at, live_count, vod_count, series_count, active_generation) VALUES('$pid', 1, 1, 0, 0, 0)")
        preBuilt = old

        val before = IptvContentDb.channelRow(pid, 7)
        assertNotNull(before, "the pre-existing row is still served (no version bump, no wipe)")
        assertEquals("Old One", before.name)
        assertNull(before.catchUp, "an old row reads as no catch-up")

        val fresh = M3uCatchUpInfo(M3uCatchUpType.DEFAULT, "http://h/c/{utc}", 4)
        IptvContentDb.beginIngest(pid)
        IptvContentDb.insertChunk(pid, listOf(channel(8, fresh)), emptyList(), emptyList(), emptyList(), listOf(Triple("live", "news", "News")))
        IptvContentDb.finishIngest(pid, 1, 0, 0)
        assertEquals(fresh, IptvContentDb.channelRow(pid, 8)?.catchUp, "the migrated table takes catch-up rows")
    }
}
