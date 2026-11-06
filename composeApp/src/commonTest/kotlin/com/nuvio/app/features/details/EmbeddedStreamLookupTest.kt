package com.nuvio.app.features.details

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EmbeddedStreamLookupTest {
    private fun stream(name: String) = StreamItem(name = name, url = "https://x/$name", addonName = "Src", addonId = "src")

    private fun series(id: String, vararg episodes: Triple<String, Int, Int>) = MetaDetails(
        id = id, type = "series", name = id,
        videos = episodes.map { (vid, s, e) ->
            MetaVideo(id = vid, title = vid, season = s, episode = e, streams = listOf(stream(vid)))
        },
    )

    @Test
    fun `finds the episode streams by id`() {
        val meta = series("show", Triple("show:1:2", 1, 2))
        assertEquals("show:1:2", EmbeddedStreamLookup.streamsFor(meta, "show:1:2").single().name)
    }

    @Test
    fun `finds the episode by season and episode from a continue watching style id`() {
        val meta = series("show", Triple("opaque-77", 1, 2))
        assertEquals("opaque-77", EmbeddedStreamLookup.streamsFor(meta, "show:1:2").single().name)
    }

    @Test
    fun `no meta means no embedded streams`() {
        assertTrue(EmbeddedStreamLookup.streamsFor(null, "show:1:2").isEmpty())
    }

    @Test
    fun `with the details screen empty the fetched meta for the parent is used`() {
        val fetched = series("show", Triple("show:1:2", 1, 2))
        val picked = EmbeddedStreamLookup.pickMeta(current = null, cached = listOf(fetched), parentMetaId = "show")
        assertSame(fetched, picked)
        assertEquals(1, EmbeddedStreamLookup.streamsFor(picked, "show:1:2").size)
    }

    @Test
    fun `another title on the details screen does not win over the fetched parent meta`() {
        val other = series("other", Triple("other:1:1", 1, 1))
        val fetched = series("show", Triple("show:1:2", 1, 2))
        assertSame(fetched, EmbeddedStreamLookup.pickMeta(other, listOf(other, fetched), "show"))
    }

    @Test
    fun `the details screen meta is used when it is the requested title`() {
        val current = series("show", Triple("show:1:2", 1, 2))
        val stale = series("show", Triple("show:9:9", 9, 9))
        assertSame(current, EmbeddedStreamLookup.pickMeta(current, listOf(stale), "show"))
    }

    @Test
    fun `unknown parent keeps the legacy behaviour`() {
        val current = series("show")
        assertSame(current, EmbeddedStreamLookup.pickMeta(current, emptyList(), null))
        assertNull(EmbeddedStreamLookup.pickMeta(null, emptyList(), "show"))
    }

    @Test
    fun `a meta failure is a failure only for sources that list streams from the meta`() {
        assertTrue(EmbeddedStreamLookup.failureBlocksStreams(metaLoaded = false, streamsLiveOnlyInMeta = true))
        assertFalse(EmbeddedStreamLookup.failureBlocksStreams(metaLoaded = false, streamsLiveOnlyInMeta = false))
        assertFalse(EmbeddedStreamLookup.failureBlocksStreams(metaLoaded = true, streamsLiveOnlyInMeta = true))
    }
}
