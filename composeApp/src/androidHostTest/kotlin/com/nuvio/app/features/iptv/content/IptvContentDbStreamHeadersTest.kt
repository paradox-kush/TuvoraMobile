package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/**
 * An M3U channel's own playlist headers live in one nullable `stream_headers` column added in place to `channels`
 * (a version bump would drop every cached catalog). Real SQLite: the round-trip, and a catalog from before the column.
 */
class IptvContentDbStreamHeadersTest {

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

    private fun channel(sid: Int, headers: Map<String, String>) = IptvStreamRow(
        sid = sid, name = "Ch $sid", logo = null, tvgId = "id$sid", categoryId = "news",
        url = "http://h/live/u/p/$sid.ts", ext = null, streamHeaders = headers,
    )

    private val headers = mapOf("User-Agent" to "VLC/3.0.20 (KHTML, like Gecko)", "Referer" to "https://ref.example/")

    @Test
    fun `channel headers round-trip through every channel read`() = runBlocking {
        val pid = "hdr:roundtrip"
        IptvContentDb.beginIngest(pid)
        IptvContentDb.insertChunk(
            pid, listOf(channel(1, headers), channel(2, emptyMap())), emptyList(), emptyList(), emptyList(),
            listOf(Triple("live", "news", "News")),
        )
        IptvContentDb.finishIngest(pid, 2, 0, 0)

        val all = IptvContentDb.channelsFor(pid, null).associateBy { it.sid }
        assertEquals(headers, all.getValue(1).streamHeaders, "list read")
        assertEquals(emptyMap(), all.getValue(2).streamHeaders, "a channel without headers reads none")
        assertEquals(headers, IptvContentDb.channelRow(pid, 1)?.streamHeaders, "single-row read (playback)")
        assertEquals(headers, IptvContentDb.pageChannels(pid, null, 0, 10).first { it.sid == 1 }.streamHeaders, "paged read")
    }

    @Test
    fun `a catalog written before the column existed reads as no headers and takes new rows`() = runBlocking {
        val pid = "hdr:legacy"
        val old = BundledSQLiteDriver().open(":memory:")
        old.execSQL("CREATE TABLE channels(playlist_id TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, sid INTEGER NOT NULL, category_id TEXT, name TEXT NOT NULL, logo TEXT, tvg_id TEXT, url TEXT NOT NULL, cmd TEXT, tv_archive INTEGER, use_http_tmp_link INTEGER, use_load_balancing INTEGER, catchup_type TEXT, catchup_source TEXT, catchup_days INTEGER, PRIMARY KEY(playlist_id, generation, sid)) WITHOUT ROWID")
        old.execSQL("CREATE TABLE ingest_meta(playlist_id TEXT NOT NULL PRIMARY KEY, built_at INTEGER NOT NULL, live_count INTEGER NOT NULL, vod_count INTEGER NOT NULL, series_count INTEGER NOT NULL, epg_url TEXT, active_generation INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID")
        old.execSQL("PRAGMA user_version = 5")
        old.execSQL("INSERT INTO channels(playlist_id, generation, sid, category_id, name, url) VALUES('$pid', 0, 7, 'news', 'Old One', 'http://h/7.ts')")
        old.execSQL("INSERT INTO ingest_meta(playlist_id, built_at, live_count, vod_count, series_count, active_generation) VALUES('$pid', 1, 1, 0, 0, 0)")
        preBuilt = old

        val before = IptvContentDb.channelRow(pid, 7)
        assertNotNull(before, "the pre-existing row is still served (no version bump, no wipe)")
        assertEquals(emptyMap(), before.streamHeaders)

        IptvContentDb.beginIngest(pid)
        IptvContentDb.insertChunk(pid, listOf(channel(8, headers)), emptyList(), emptyList(), emptyList(), listOf(Triple("live", "news", "News")))
        IptvContentDb.finishIngest(pid, 1, 0, 0)
        assertEquals(headers, IptvContentDb.channelRow(pid, 8)?.streamHeaders, "the migrated table takes header rows")
    }
}
