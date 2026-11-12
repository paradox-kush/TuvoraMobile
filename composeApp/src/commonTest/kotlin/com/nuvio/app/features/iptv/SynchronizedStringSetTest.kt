package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * The hub's epgFetched set is added to by the UI thread, removed from by the tile-eviction callback and
 * cleared by background workers (Save reset, XMLTV ingest). A plain HashSet under that load corrupts or,
 * on Kotlin/Native, aborts the process.
 */
class SynchronizedStringSetTest {
    @Test
    fun addReportsFirstInsertionOnly() {
        val set = SynchronizedStringSet()
        assertTrue(set.add("a"), "first add is new")
        assertFalse(set.add("a"), "second add is a duplicate")
        assertTrue(set.remove("a"), "remove reports presence")
        assertTrue(set.add("a"), "add after remove is new again")
    }

    @Test
    fun concurrentAddsFromManyThreadsLoseNothing() = runBlocking {
        val set = SynchronizedStringSet()
        val workers = 8
        val perWorker = 2_000
        (0 until workers).map { w ->
            async(Dispatchers.Default) {
                repeat(perWorker) { i -> set.add("w$w-$i") }
            }
        }.awaitAll()
        assertEquals(workers * perWorker, set.size, "every distinct add from every thread must be kept")
    }

    @Test
    fun mixedAddRemoveClearFromManyThreadsStaysConsistent() = runBlocking {
        val set = SynchronizedStringSet()
        (0 until 8).map { w ->
            async(Dispatchers.Default) {
                repeat(5_000) { i ->
                    val key = "k${i % 64}"
                    when ((i + w) % 5) {
                        0 -> set.clear()
                        1, 2 -> set.add(key)
                        3 -> set.remove(key)
                        else -> set.contains(key)
                    }
                }
            }
        }.awaitAll()
        set.clear()
        assertEquals(0, set.size, "clear leaves the set empty after the storm")
        assertTrue(set.add("after"), "the set still works after concurrent mutation")
    }
}
