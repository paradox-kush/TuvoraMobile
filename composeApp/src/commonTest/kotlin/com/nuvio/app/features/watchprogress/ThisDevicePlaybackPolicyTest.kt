package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals

class ThisDevicePlaybackPolicyTest {
    @Test
    fun `recording moves a title to the most recent end without duplicating it`() {
        val ids = ThisDevicePlaybackPolicy.record(ThisDevicePlaybackPolicy.record(listOf("a", "b"), "a"), "c")
        assertEquals(listOf("b", "a", "c"), ids)
    }

    @Test
    fun `the set is capped and drops the oldest`() {
        var ids = emptyList<String>()
        repeat(ThisDevicePlaybackPolicy.MAX_IDS + 5) { ids = ThisDevicePlaybackPolicy.record(ids, "t$it") }
        assertEquals(ThisDevicePlaybackPolicy.MAX_IDS, ids.size)
        assertEquals("t5", ids.first())
    }

    @Test
    fun `blank ids are ignored and encode decode round trips`() {
        assertEquals(listOf("a"), ThisDevicePlaybackPolicy.record(listOf("a"), "  "))
        val ids = listOf("tt1", "tt2")
        assertEquals(ids, ThisDevicePlaybackPolicy.decode(ThisDevicePlaybackPolicy.encode(ids)))
        assertEquals(emptyList(), ThisDevicePlaybackPolicy.decode(null))
    }
}
