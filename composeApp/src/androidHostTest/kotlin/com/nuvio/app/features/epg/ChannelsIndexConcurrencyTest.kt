package com.nuvio.app.features.epg

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Correctness + concurrency for the two-phase channels-index ingest, through the real repository +
 * SQLite path (in-memory bundled driver). Covers late/duplicate filtering metadata, a stalled
 * transport that must not block a guide read of the previous generation, cancellation while awaiting
 * the network, and overlapping ingests.
 */
class ChannelsIndexConcurrencyTest {
    @BeforeTest
    fun setUp() {
        EpgMirrorDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
    }

    private fun feedOf(body: String): suspend (onChunk: (String) -> Unit) -> Unit = { it(body) }

    private suspend fun rows(): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        EpgMirrorDb.forEachIndexRow { out.add(Triple(it.slug, it.epgId, it.name)) }
        return out
    }

    private suspend fun commit(selection: Set<String>, body: String) {
        assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(selection, feedOf(body)))
    }

    // C1: a source whose slug/countries arrive AFTER its channels is still filtered on the final
    // metadata (field-order independent) — no channel is selected on a stale/absent value.
    @Test
    fun `late slug and countries after channels still filter correctly`() = runBlocking {
        val doc = """{"sources":[""" +
            """{"channels":[{"id":"bbc","names":["BBC"]}],"slug":"gb","countries":"United Kingdom"},""" +
            """{"channels":[{"id":"tve","names":["TVE"]}],"slug":"es","countries":"Spain"}]}"""
        commit(setOf("United Kingdom"), doc)
        assertEquals(listOf(Triple("gb", "bbc", "BBC")), rows())
    }

    // C1: a repeated metadata key resolves to the LAST value (defined behaviour) — the source is kept
    // as the final "gb"/United Kingdom, not the earlier throwaway values, and rows carry the final slug.
    @Test
    fun `duplicate metadata keys use the last value`() = runBlocking {
        val doc = """{"sources":[""" +
            """{"slug":"zz","countries":"Spain","slug":"gb","countries":"United Kingdom",""" +
            """"channels":[{"id":"bbc","names":["BBC"]}]}]}"""
        commit(setOf("United Kingdom"), doc)
        assertEquals(listOf(Triple("gb", "bbc", "BBC")), rows())
    }

    // C1/bounded: skipped-region channels are staged then dropped (never promoted) — the committed
    // index holds only the selected region.
    @Test
    fun `skipped region channels are not promoted`() = runBlocking {
        val doc = """{"sources":[""" +
            """{"slug":"gb","countries":"United Kingdom","channels":[{"id":"bbc","names":["BBC"]}]},""" +
            """{"slug":"es","countries":"Spain","channels":[{"id":"a","names":["A"]},{"id":"b","names":["B"]}]}]}"""
        commit(setOf("United Kingdom"), doc)
        assertEquals(listOf(Triple("gb", "bbc", "BBC")), rows())
    }

    // C2: while an ingest is STALLED waiting on the network (phase 1), a guide read completes against
    // the PREVIOUS generation — the network wait holds no lock that blocks reads.
    @Test
    fun `a stalled transport does not block a guide read of the previous generation`() = runBlocking {
        commit(emptySet(), """{"sources":[{"slug":"a","channels":[{"id":"c1","names":["One"]}]}]}""")
        val gen1 = rows()

        val stalled = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val job = launch {
            EpgMirrorRepository.ingestChannelsIndexStream(emptySet()) { onChunk ->
                onChunk("""{"sources":[{"slug":"b","channels":[{"id":"c9",""")
                reached.complete(Unit)
                stalled.await() // network stall in phase 1 (no DB lock held)
                onChunk(""""names":["Nine"]}]}]}""")
            }
        }
        reached.await()
        assertEquals(gen1, rows(), "guide read served the previous generation while the ingest stalled")

        stalled.complete(Unit)
        job.join()
        assertEquals(listOf(Triple("b", "c9", "Nine")), rows(), "new generation committed after the stream resumed")
    }

    // C3: cancelling while awaiting the network releases the ingest and preserves the previous
    // generation (nothing half-committed).
    @Test
    fun `cancellation while awaiting the network preserves the previous generation`() = runBlocking {
        commit(emptySet(), """{"sources":[{"slug":"a","channels":[{"id":"c1","names":["One"]}]}]}""")
        val gen1 = rows()

        val never = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val job = launch {
            EpgMirrorRepository.ingestChannelsIndexStream(emptySet()) { onChunk ->
                onChunk("""{"sources":[{"slug":"b","channels":[{"id":"c9","names":["Nine"]}""")
                reached.complete(Unit)
                never.await() // never resumes; the job is cancelled here
            }
        }
        reached.await()
        job.cancelAndJoin()

        assertEquals(gen1, rows(), "previous generation intact after cancellation")
        // The repository is usable again (the ingest lock was released) — a fresh ingest commits.
        commit(emptySet(), """{"sources":[{"slug":"c","channels":[{"id":"c3","names":["Three"]}]}]}""")
        assertEquals(listOf(Triple("c", "c3", "Three")), rows())
    }

    // C2/overlap: two ingests launched at once are serialized (they cannot interleave shadow ops); the
    // committed index is one whole generation, never a mix, and both calls succeed.
    @Test
    fun `overlapping ingests do not corrupt the committed generation`() = runBlocking {
        val docA = """{"sources":[{"slug":"a","channels":[{"id":"a1","names":["A1"]},{"id":"a2","names":["A2"]}]}]}"""
        val docB = """{"sources":[{"slug":"b","channels":[{"id":"b1","names":["B1"]}]}]}"""
        val jobA = launch { EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), feedOf(docA)) }
        val jobB = launch { EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), feedOf(docB)) }
        jobA.join(); jobB.join()

        val slugs = rows().map { it.first }.toSet()
        assertTrue(slugs == setOf("a") || slugs == setOf("b"), "one whole generation won, not a mix: $slugs")
        // Not both interleaved:
        assertFalse("a" in slugs && "b" in slugs, "the two generations did not merge")
    }
}
