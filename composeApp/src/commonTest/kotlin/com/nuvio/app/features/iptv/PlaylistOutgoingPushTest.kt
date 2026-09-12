package com.nuvio.app.features.iptv

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B24 §2 — the outgoing push is built from ONE immutable snapshot captured synchronously, so the
 * authority decision and the payload cannot desync and an edit landing DURING the network call
 * cannot change what is already in flight. Exercises the real [XtreamRepository.captureOutgoingPush]
 * through the raw-blob test seam.
 */
class PlaylistOutgoingPushTest {

    @AfterTest
    fun reset() = XtreamRepository.clearLocalState()

    private fun row(id: String) =
        """{"id":"$id","name":"P","baseUrl":"http://h:8080","username":"u","password":"p"}"""

    @Test
    fun `the snapshot captures authority and the payload from the same loaded state`() {
        XtreamRepository.installRawForTest("[${row("a")},${row("b")}]")
        val snap = XtreamRepository.captureOutgoingPush()
        assertTrue(snap.canFullReplace, "a clean array is authoritative")
        assertEquals(listOf("a", "b"), snap.accounts.map { it.id }, "payload matches the loaded state")
    }

    @Test
    fun `an edit landing during the push does not change a snapshot already captured`() {
        XtreamRepository.installRawForTest("[${row("a")},${row("b")}]")
        val inFlight = XtreamRepository.captureOutgoingPush()

        // Simulate the user editing (or a remote pull applying) while the network call is running.
        XtreamRepository.installRawForTest("[${row("a")}]")

        assertEquals(listOf("a", "b"), inFlight.accounts.map { it.id }, "the in-flight payload is immutable")
        assertEquals(listOf("a"), XtreamRepository.captureOutgoingPush().accounts.map { it.id }, "a fresh snapshot sees the new state")
    }

    @Test
    fun `a non-authoritative loaded state is withheld by the snapshot`() {
        XtreamRepository.installRawForTest("[${row("a")},{}]") // one row un-decodable => Recovered
        val snap = XtreamRepository.captureOutgoingPush()
        assertFalse(snap.canFullReplace, "a recovered subset must not full-replace")
        assertEquals(listOf("a"), snap.accounts.map { it.id }, "the good row is still captured")
    }

    @Test
    fun `an absent loaded state is withheld by the snapshot`() {
        XtreamRepository.installRawForTest(null)
        val snap = XtreamRepository.captureOutgoingPush()
        assertFalse(snap.canFullReplace, "an absent/reset store must not wipe the server")
        assertTrue(snap.accounts.isEmpty())
    }

    @Test
    fun `an explicit delete-all is authoritative and captured as an empty payload`() {
        XtreamRepository.installRawForTest("[]")
        val snap = XtreamRepository.captureOutgoingPush()
        assertTrue(snap.canFullReplace, "[] is a deliberate delete-all")
        assertTrue(snap.accounts.isEmpty(), "the payload is an empty full-replace")
    }
}
