package com.nuvio.app.features.epg

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Proves the two allocation risks are gone through the real repository + SQLite path (JVM host,
 * BundledSQLite :memory:). Feeds a synthetic feed programmatically (no giant String held), so the
 * generator itself never materializes the catalog either.
 *
 *  - Retained shadow batches never exceed INDEX_BATCH, regardless of channel count (one very large
 *    source; a near-fully-selected multi-source catalog).
 *  - The count of channels does not change the max retained batch (no proportional growth).
 *  - A best-effort JVM heap delta is measured for a 200k-channel single source and asserted to be far
 *    below what retaining the whole catalog (~200k row objects) would cost — indicative, not a device
 *    figure (a Fire TV run is still needed for final tuning).
 */
class ChannelsIndexScaleTest {
    private val batches = ArrayList<Int>()

    @BeforeTest
    fun setUp() {
        EpgMirrorDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        batches.clear()
        EpgMirrorDb.indexInsertObserver = { batches.add(it) }
    }

    @AfterTest
    fun tearDown() {
        EpgMirrorDb.indexInsertObserver = null
    }

    /** Streams a channels-index of [sources] sources × [channelsPer] channels each, without ever
     *  building the whole document in memory (chunks are emitted as the shape is walked). */
    private fun syntheticFeed(sources: Int, channelsPer: Int, countries: String): suspend (onChunk: (String) -> Unit) -> Unit =
        { onChunk ->
            onChunk("""{"generatedAt":"t","sources":[""")
            for (s in 0 until sources) {
                if (s > 0) onChunk(",")
                onChunk("""{"slug":"s$s","label":"S$s","countries":"$countries","channels":[""")
                for (ch in 0 until channelsPer) {
                    if (ch > 0) onChunk(",")
                    // split each channel across two chunks to exercise chunk boundaries
                    onChunk("""{"id":"s${s}c$ch",""")
                    onChunk(""""names":["Name $s $ch"]}""")
                }
                onChunk("]}")
            }
            onChunk("]}")
        }

    private suspend fun rowCount(): Int {
        var n = 0
        EpgMirrorDb.forEachIndexRow { n++ }
        return n
    }

    @Test
    fun `one very large source streams in bounded batches`() = runBlocking {
        val channels = 120_000
        assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), syntheticFeed(1, channels, "United Kingdom")))
        assertEquals(channels, rowCount(), "all channels committed")
        assertTrue(batches.isNotEmpty(), "inserts happened during the stream")
        assertTrue(batches.max() <= 4_000, "no batch exceeded INDEX_BATCH; max was ${batches.max()}")
        // ~ceil(120k / 4k) = 30 flushes → retained rows never approached the 120k total.
        assertTrue(batches.size >= 30, "streamed in many bounded flushes, not one big insert (${batches.size})")
    }

    @Test
    fun `max retained batch does not grow with channel count`() = runBlocking {
        EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), syntheticFeed(1, 10_000, "Spain"))
        val small = batches.max()
        batches.clear()
        EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), syntheticFeed(1, 100_000, "Spain"))
        val large = batches.max()
        assertEquals(small, large, "peak retained batch is constant across a 10× larger catalog")
        assertTrue(large <= 4_000)
    }

    @Test
    fun `near-fully-selected multi-source catalog stays bounded`() = runBlocking {
        // 40 sources × 3k channels, all in the selected region → ~120k rows kept, still batch-bounded.
        assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(setOf("India"), syntheticFeed(40, 3_000, "India")))
        assertEquals(120_000, rowCount())
        assertTrue(batches.max() <= 4_000, "max batch ${batches.max()}")
    }

    @Test
    fun `retained heap does not grow proportionally with channel count`() = runBlocking {
        val rt = Runtime.getRuntime()
        val mb = 1024.0 * 1024.0
        fun retainedAfterIngesting(channels: Int): Long {
            repeat(4) { rt.gc(); Thread.sleep(25) }
            val before = rt.totalMemory() - rt.freeMemory()
            runBlocking {
                assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), syntheticFeed(1, channels, "United Kingdom")))
            }
            repeat(4) { rt.gc(); Thread.sleep(25) }
            return (rt.totalMemory() - rt.freeMemory()) - before
        }
        // Committed rows live in the native :memory: SQLite (off JVM heap), so if we are NOT retaining
        // the catalog on-heap, a 10× larger ingest leaves ~the same JVM heap behind.
        val r20k = retainedAfterIngesting(20_000)
        assertEquals(20_000, rowCount())
        val r200k = retainedAfterIngesting(200_000)
        assertEquals(200_000, rowCount())
        println(
            "ChannelsIndexScaleTest retained heap (JVM host; native :memory: DB is off-heap): " +
                "20k=${"%.1f".format(r20k / mb)}MB, 200k=${"%.1f".format(r200k / mb)}MB",
        )
        // Holding 200k EpgIndexRow(3 strings) on-heap would add tens of MB over the 20k case; bounded
        // streaming must not. Generous slack absorbs JVM GC/JIT noise.
        assertTrue(
            r200k < r20k + 12L * 1024 * 1024,
            "on-heap retention must not scale with channel count (20k=$r20k, 200k=$r200k bytes)",
        )
        assertTrue(batches.max() <= 4_000)
    }
}
