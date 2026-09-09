package com.nuvio.app.features.epg

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Real-SQLite integration for bounded-memory channels-index ingestion, through the actual
 * EpgMirrorRepository + EpgMirrorDb (host bundled :memory: driver via openForTests — the
 * IptvContentDb idiom). Proves the streamed parse + shadow-swap commit AND that a rejected update
 * retains the previous generation — what the pure ChannelsIndexStreamParser test cannot establish.
 * Each test replaces the index with its own baseline first, so methods are order-independent.
 */
class ChannelsIndexIngestTest {
    @BeforeTest
    fun setUp() {
        EpgMirrorDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
    }

    private fun feedOf(body: String): suspend (onChunk: (String) -> Unit) -> Unit =
        { onChunk -> onChunk(body) }

    private suspend fun rows(): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        EpgMirrorDb.forEachIndexRow { out.add(Triple(it.slug, it.epgId, it.name)) }
        return out
    }

    private val gen1 = """{"sources":[{"slug":"a","channels":[{"id":"c1","names":["One"]}]}]}"""

    @Test
    fun `a valid index commits and stores the kept rows`() = runBlocking {
        assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), feedOf(gen1)))
        assertFalse(EpgMirrorDb.indexIsEmpty())
        assertEquals(listOf(Triple("a", "c1", "One")), rows())
    }

    @Test
    fun `a malformed source is rejected and the prior generation is retained`() = runBlocking {
        assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), feedOf(gen1)))
        val before = rows()
        val bad = """{"sources":[{"slug":"a","channels":[{"id":"c1","names":["One"]}]},{"slug":}]}"""
        assertFalse(EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), feedOf(bad)), "malformed update rejected")
        assertEquals(before, rows(), "prior generation retained unchanged")
    }

    @Test
    fun `a truncated response is rejected and the prior generation is retained`() = runBlocking {
        assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), feedOf(gen1)))
        val before = rows()
        val truncated = """{"sources":[{"slug":"b","channels":[{"id":"c9","names":["Nine"]}]}""" // no closing ]
        assertFalse(EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), feedOf(truncated)))
        assertEquals(before, rows(), "truncated update did not partially replace the index")
    }

    @Test
    fun `only selected regions are stored`() = runBlocking {
        val doc = """{"sources":[""" +
            """{"slug":"gb","countries":"United Kingdom","channels":[{"id":"bbc","names":["BBC"]}]},""" +
            """{"slug":"es","countries":"Spain","channels":[{"id":"tve","names":["TVE"]}]}]}"""
        assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(setOf("United Kingdom"), feedOf(doc)))
        assertEquals(listOf(Triple("gb", "bbc", "BBC")), rows())
    }

    @Test
    fun `chunk-split delivery ingests the same rows`() = runBlocking {
        val feed: suspend (onChunk: (String) -> Unit) -> Unit = { onChunk ->
            gen1.chunked(7).forEach { onChunk(it) } // arbitrary mid-token splits
        }
        assertTrue(EpgMirrorRepository.ingestChannelsIndexStream(emptySet(), feed))
        assertEquals(listOf(Triple("a", "c1", "One")), rows())
    }
}
