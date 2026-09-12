package com.nuvio.app.features.iptv

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B24 §4 — beyond the pure decoder: the ACTUAL repository authority decision that gates the sync
 * full-replace push, plus the outgoing payload. Exercises the real [XtreamRepository] state
 * transitions through a raw-blob test seam.
 */
class PlaylistSyncAuthorityTest {

    @AfterTest
    fun reset() = XtreamRepository.clearLocalState()

    private fun row(id: String) =
        """{"id":"$id","name":"P","baseUrl":"http://h:8080","username":"u","password":"p"}"""

    @Test
    fun `a clean non-empty store may full-replace`() {
        XtreamRepository.installRawForTest("[${row("a")},${row("b")}]")
        assertTrue(XtreamRepository.canPushFullReplace(), "a complete Valid store is authoritative")
        assertEquals(2, XtreamRepository.uiState.value.accounts.size)
    }

    @Test
    fun `an explicit empty store may full-replace — a deliberate delete-all`() {
        XtreamRepository.installRawForTest("[]")
        assertTrue(XtreamRepository.canPushFullReplace(), "[] is a deliberate deletion and stays pushable")
    }

    @Test
    fun `an absent store must NOT full-replace — corruption-reset or fresh`() {
        XtreamRepository.installRawForTest(null)
        assertFalse(XtreamRepository.canPushFullReplace(), "an absent/reset store must not wipe the server")
    }

    @Test
    fun `a partially-recovered store must NOT full-replace`() {
        XtreamRepository.installRawForTest("[${row("a")},{}]") // one row un-decodable
        assertFalse(XtreamRepository.canPushFullReplace(), "a recovered subset must not overwrite the complete server set")
        assertEquals(1, XtreamRepository.uiState.value.accounts.size, "the good row is still shown")
    }

    @Test
    fun `a corrupt store must NOT full-replace`() {
        XtreamRepository.installRawForTest("not a json array")
        assertFalse(XtreamRepository.canPushFullReplace(), "a corrupt store must not wipe the server")
    }

    @Test
    fun `a clean reload heals a previously damaged store into a pushable state`() {
        XtreamRepository.installRawForTest("[${row("a")},{}]") // Recovered → not pushable
        assertFalse(XtreamRepository.canPushFullReplace())
        XtreamRepository.installRawForTest("[${row("a")}]") // a later clean decode
        assertTrue(XtreamRepository.canPushFullReplace(), "a clean decode re-establishes a complete authoritative base")
    }

    @Test
    fun `the outgoing payload reflects the current accounts and an empty delete-all`() {
        val payloadTwo = playlistPushPayload(
            listOf(XtreamAccount("a", "P", "http://h:8080", "u", "p"), XtreamAccount("b", "Q", "http://h2:8080", "u", "p"))
        )
        assertEquals(2, payloadTwo.size)
        assertTrue(playlistPushPayload(emptyList()).isEmpty(), "a delete-all sends an empty full-replace payload")
    }
}
