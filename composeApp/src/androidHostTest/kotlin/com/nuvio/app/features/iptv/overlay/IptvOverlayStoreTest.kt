package com.nuvio.app.features.iptv.overlay

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-SQLite tests for the overlay store (host JVM via BundledSQLiteDriver, the LiveFavouriteRenumberTest
 * idiom). Temp file-backed so the persistence-across-reopen case is genuine.
 */
class IptvOverlayStoreTest {

    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("overlay_test", ".db").also { it.delete() }
        OverlayDbDriver.openForTests = { BundledSQLiteDriver().open(dbFile.absolutePath) }
        runBlocking { IptvOverlayStore.closeForTests() }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { IptvOverlayStore.closeForTests() }
        OverlayDbDriver.openForTests = null
        dbFile.delete()
    }

    @Test
    fun `channel edit round-trips`() = runBlocking {
        IptvOverlayStore.setChannel(1, "fp:v1:bbc", "pl", ChannelOverlay(hidden = true, position = 3), 100)
        val snap = IptvOverlayStore.snapshot(1)
        assertEquals(ChannelOverlay(hidden = true, position = 3), snap.channels["fp:v1:bbc"])
    }

    @Test
    fun `a no-op edit is tombstoned and absent from the snapshot`() = runBlocking {
        IptvOverlayStore.setChannel(1, "fp:v1:x", "pl", ChannelOverlay(hidden = true), 100)
        IptvOverlayStore.setChannel(1, "fp:v1:x", "pl", ChannelOverlay(), 200) // un-hide -> no-op
        assertNull(IptvOverlayStore.snapshot(1).channels["fp:v1:x"])
    }

    @Test
    fun `category edit round-trips`() = runBlocking {
        IptvOverlayStore.setCategory(1, "pl", "live", "c:v1:uk", CategoryOverlay(pinned = true, rename = "UK"), 100)
        assertEquals(CategoryOverlay(pinned = true, rename = "UK"), IptvOverlayStore.snapshot(1).categories["c:v1:uk"])
    }

    @Test
    fun `custom group stores ordered membership`() = runBlocking {
        IptvOverlayStore.putGroup(1, CustomGroup("g1", "live", null, "Sports", 0, listOf("e2", "e1", "e3")), 100)
        val g = IptvOverlayStore.snapshot(1).groups.single()
        assertEquals("Sports", g.name)
        assertEquals(listOf("e2", "e1", "e3"), g.memberEntityIds) // insertion order preserved via position
    }

    @Test
    fun `replacing a group's members drops the old ones`() = runBlocking {
        IptvOverlayStore.putGroup(1, CustomGroup("g1", "live", null, "Sports", 0, listOf("a", "b")), 100)
        IptvOverlayStore.putGroup(1, CustomGroup("g1", "live", null, "Sports", 0, listOf("c")), 200)
        assertEquals(listOf("c"), IptvOverlayStore.snapshot(1).groups.single().memberEntityIds)
    }

    @Test
    fun `profiles are isolated`() = runBlocking {
        IptvOverlayStore.setChannel(1, "fp:v1:z", "pl", ChannelOverlay(hidden = true), 100)
        assertTrue(IptvOverlayStore.snapshot(2).isEmpty)
        assertTrue(IptvOverlayStore.snapshot(1).channels.containsKey("fp:v1:z"))
    }

    @Test
    fun `edits persist across a reopen`() = runBlocking {
        IptvOverlayStore.setChannel(1, "fp:v1:keep", "pl", ChannelOverlay(pinned = true), 100)
        IptvOverlayStore.closeForTests() // next snapshot re-opens the same file
        assertEquals(ChannelOverlay(pinned = true), IptvOverlayStore.snapshot(1).channels["fp:v1:keep"])
    }

    @Test
    fun `purgePlaylist removes only that playlist's rows`() = runBlocking {
        IptvOverlayStore.setChannel(1, "fp:v1:a", "plA", ChannelOverlay(hidden = true), 100)
        IptvOverlayStore.setChannel(1, "fp:v1:b", "plB", ChannelOverlay(hidden = true), 100)
        IptvOverlayStore.purgePlaylist(1, "plA")
        val snap = IptvOverlayStore.snapshot(1)
        assertNull(snap.channels["fp:v1:a"])
        assertTrue(snap.channels.containsKey("fp:v1:b"))
    }

    // ---- delta-push / dirty tracking (2026-09-19 write-amplification fix) -------------------------

    @Test
    fun `push sends only dirty rows and an ack clears them`() = runBlocking {
        IptvOverlayStore.setChannel(1, "fp:v1:bbc", "pl", ChannelOverlay(hidden = true), 100)
        val pending = IptvOverlayStore.rowsForPush(1)
        assertEquals(1, pending.size)
        assertEquals("fp:v1:bbc", pending.single().okey)
        IptvOverlayStore.markChannelsPushed(1, pending)
        assertTrue(IptvOverlayStore.rowsForPush(1).isEmpty()) // nothing owed once the server acked it
    }

    @Test
    fun `a remote-applied row is never pushed back`() = runBlocking {
        IptvOverlayStore.applyRemoteChannel(1, "fp:v1:remote", "pl", ChannelOverlay(pinned = true), 100, false)
        assertTrue(IptvOverlayStore.rowsForPush(1).isEmpty()) // pulled edits are not local, so not dirty
        assertEquals(ChannelOverlay(pinned = true), IptvOverlayStore.snapshot(1).channels["fp:v1:remote"])
    }

    @Test
    fun `a row edited during a push stays dirty and is resent`() = runBlocking {
        IptvOverlayStore.setChannel(1, "fp:v1:x", "pl", ChannelOverlay(hidden = true), 100)
        val inflight = IptvOverlayStore.rowsForPush(1) // captured at updated_at=100
        IptvOverlayStore.setChannel(1, "fp:v1:x", "pl", ChannelOverlay(pinned = true), 200) // re-edited mid-push
        IptvOverlayStore.markChannelsPushed(1, inflight) // ack clears only the updated_at=100 version
        val still = IptvOverlayStore.rowsForPush(1)
        assertEquals(1, still.size)
        assertEquals(200, still.single().updatedAt)
    }

    @Test
    fun `a stale remote event does not clobber a newer pending local edit`() = runBlocking {
        IptvOverlayStore.setChannel(1, "fp:v1:y", "pl", ChannelOverlay(rename = "Local"), 200)
        IptvOverlayStore.applyRemoteChannel(1, "fp:v1:y", "pl", ChannelOverlay(rename = "Older"), 100, false) // older -> ignored
        assertEquals("Local", IptvOverlayStore.snapshot(1).channels["fp:v1:y"]?.rename)
        assertEquals(1, IptvOverlayStore.rowsForPush(1).size) // still owed to the server
    }
}
