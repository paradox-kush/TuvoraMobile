package com.nuvio.app.features.iptv

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinSuggestionStoreTest {
    private val minute = 60 * 1000L
    private val day = 24 * 60 * minute

    private class FakeStorage : PinSuggestionStorage {
        val saved = mutableMapOf<Int, String>()
        var saves = 0
        override fun load(profileId: Int) = saved[profileId]
        override fun save(profileId: Int, json: String) {
            saved[profileId] = json
            saves++
        }
    }

    private class Harness(
        val storage: FakeStorage = FakeStorage(),
        var now: Long = 100 * 24 * 60 * 60 * 1000L,
        var profile: Int = 1,
    ) {
        val store get() = PinSuggestionStore(storage, nowMs = { now }, activeProfileId = { profile })
    }

    @Test
    fun `a recorded play is saved and read back by a new store`() {
        val h = Harness()
        val first = h.store
        first.recordPlay("a", "Alpha", "alpha.png")
        val reopened = h.store
        reopened.ensureLoaded()
        assertEquals(listOf(h.now), reopened.state.value.channels.single().plays, "the play survived a restart")
        assertEquals("Alpha", reopened.state.value.channels.single().name, "name kept")
    }

    @Test
    fun `plays on separate days build up a count`() {
        val h = Harness()
        val store = h.store
        for (i in 0 until 3) {
            store.recordPlay("a", "Alpha", null)
            h.now += day
        }
        assertEquals(3, PinSuggestionPolicy.playsInWindow(store.state.value.channels.single(), h.now), "three plays")
    }

    @Test
    fun `the store keeps at most fifty channels`() {
        val h = Harness()
        val store = h.store
        for (i in 0 until 65) {
            store.recordPlay("c$i", "c$i", null)
            h.now += 20 * minute
        }
        assertEquals(LivePlayCountsPolicy.MAX_CHANNELS, store.state.value.channels.size, "capped in the live state")
        val reopened = h.store
        reopened.ensureLoaded()
        assertEquals(LivePlayCountsPolicy.MAX_CHANNELS, reopened.state.value.channels.size, "and in what was written")
    }

    @Test
    fun `clearing play counts empties the live state and the saved copy`() {
        val h = Harness()
        val store = h.store
        store.recordPlay("a", "Alpha", null)
        store.clearPlayCounts()
        assertTrue(store.state.value.channels.isEmpty(), "live state empty")
        val reopened = h.store
        reopened.ensureLoaded()
        assertTrue(reopened.state.value.channels.isEmpty(), "saved copy empty")
    }

    @Test
    fun `clearing play counts keeps the switch and the snooze`() {
        val h = Harness()
        val store = h.store
        store.recordPlay("a", "Alpha", null)
        store.snooze()
        store.clearPlayCounts()
        assertEquals(h.now + 14 * day, store.state.value.snoozedUntilMs, "snooze kept")
        assertTrue(store.state.value.enabled, "switch kept")
    }

    @Test
    fun `Not now is remembered across a restart`() {
        val h = Harness()
        h.store.snooze()
        val reopened = h.store
        reopened.ensureLoaded()
        assertEquals(h.now + 14 * day, reopened.state.value.snoozedUntilMs, "snooze saved")
    }

    @Test
    fun `Stop suggesting is remembered and the Settings switch can undo it`() {
        val h = Harness()
        val store = h.store
        store.stop()
        val reopened = h.store
        reopened.ensureLoaded()
        assertEquals(false, reopened.state.value.enabled, "stopped stays stopped")
        reopened.setEnabled(true)
        assertTrue(reopened.state.value.enabled, "switch on again")
    }

    @Test
    fun `an unchanged state writes nothing`() {
        val h = Harness()
        val store = h.store
        store.clearPlayCounts()
        store.setEnabled(true)
        assertEquals(0, h.storage.saves, "no write for a no-op")
    }

    @Test
    fun `each profile has its own counts and choices`() {
        val h = Harness()
        val store = h.store
        store.recordPlay("a", "Alpha", null)
        store.stop()
        store.onProfileChanged(2)
        assertTrue(store.state.value.channels.isEmpty(), "profile 2 starts empty")
        assertTrue(store.state.value.enabled, "profile 2 has the default switch")
        store.recordPlay("b", "Beta", null)
        store.onProfileChanged(1)
        assertEquals(listOf("a"), store.state.value.channels.map { it.contentId }, "profile 1 kept its own")
        assertEquals(false, store.state.value.enabled, "and its own switch")
    }

    @Test
    fun `a corrupt saved copy reads as the default state`() {
        val h = Harness()
        h.storage.saved[1] = "{not json"
        val store = h.store
        store.ensureLoaded()
        assertEquals(PinSuggestionState(), store.state.value, "default")
    }

    @Test
    fun `an account wipe empties the live state without writing`() {
        val h = Harness()
        val store = h.store
        store.recordPlay("a", "Alpha", null)
        val writes = h.storage.saves
        store.clearLocalState()
        assertEquals(PinSuggestionState(), store.state.value, "empty")
        assertEquals(writes, h.storage.saves, "no write")
    }

    @Test
    fun `removing a playlist drops its channels and keeps the others`() {
        val h = Harness()
        val store = h.store
        store.recordPlay("xtream:p1:live:1", "One", null)
        h.now += 20 * minute
        store.recordPlay("xtream:p2:live:2", "Two", null)
        store.migrateIdPrefix("xtream:p1:", null)
        assertEquals(listOf("xtream:p2:live:2"), store.state.value.channels.map { it.contentId }, "only p2 left")
    }

    @Test
    fun `editing a playlist moves its channels to the new prefix`() {
        val h = Harness()
        val store = h.store
        store.recordPlay("xtream:p1:live:1", "One", null)
        store.migrateIdPrefix("xtream:p1:", "xtream:p9:")
        assertEquals("xtream:p9:live:1", store.state.value.channels.single().contentId, "re-keyed")
    }

    @Test
    fun `re-keying merges channels that land on the same id and reports how many moved`() {
        val h = Harness()
        val store = h.store
        store.recordPlay("old:1", "One", null)
        h.now += day
        store.recordPlay("new:1", "One", null)
        val moved = store.rekeyIds { if (it == "old:1") "new:1" else null }
        assertEquals(1, moved, "one id moved")
        assertEquals(2, store.state.value.channels.single().plays.size, "both channels' plays kept on the one id")
    }

    @Test
    fun `a dry-run re-key writes nothing`() {
        val h = Harness()
        val store = h.store
        store.recordPlay("a", "Alpha", null)
        val writes = h.storage.saves
        assertEquals(0, store.rekeyIds { null }, "nothing moved")
        assertEquals(writes, h.storage.saves, "no write")
    }

    @Test
    fun `nothing is written until something is played`() {
        val h = Harness()
        val store = h.store
        store.ensureLoaded()
        assertNull(h.storage.saved[1], "no saved copy yet")
        store.recordPlay("a", "Alpha", null)
        assertNotNull(h.storage.saved[1], "saved after the first play")
    }

    /**
     * Apple TV Live TV deadlock: the write to storage ran inside the store's lock, storage hopped to the main thread
     * (UserDefaults' change notification), and the main thread was waiting for that lock in ensureLoaded(). A read of
     * the store must never wait for a write in flight.
     */
    @Test
    fun `reading the store never waits for a write that is still in flight`() = runTest {
        val entered = atomic(false)
        val release = atomic(false)
        val storage = object : PinSuggestionStorage {
            override fun load(profileId: Int): String? = null
            override fun save(profileId: Int, json: String) {
                entered.value = true
                while (!release.value) { /* a slow write: the main thread is not free to finish it */ }
            }
        }
        val store = PinSuggestionStore(storage, nowMs = { 1_000L }, activeProfileId = { 1 })
        withContext(Dispatchers.Default) {
            val writer = async { store.recordPlay("a", "Alpha", null) }
            try {
                var waited = 0
                while (!entered.value && waited < 500) { delay(10); waited++ }
                assertTrue(entered.value, "the write never started")
                val reader = withTimeoutOrNull(2_000) { CoroutineScope(Dispatchers.Default).async { store.ensureLoaded(); store.state.value.channels.size }.await() }
                assertEquals(1, reader, "ensureLoaded waited for the write, or the new state was not visible")
            } finally {
                release.value = true
            }
            writer.await()
        }
    }
}
