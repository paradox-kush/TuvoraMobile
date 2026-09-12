package com.nuvio.app.features.iptv

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B24 §3 — the failed-write recovery contract for the local persist. A durable write that FAILS must
 * not report success, must not authorize a push, must not promote a fresh store to authoritative,
 * and must leave the in-memory edit pending (not silently reverted, not silently durable). Exercises
 * the real [XtreamRepository.persist] via its write seam.
 *
 * On Mobile/Desktop the durable write ([XtreamAccountStorage.saveAccountsJson]) is a SYNCHRONOUS
 * platform call (SharedPreferences.apply / NSUserDefaults), not a suspend function — so the
 * runCatching around it can never observe a CancellationException from coroutine cancellation; it
 * only ever captures a genuine storage failure. That is why swallowing here is safe (there is no
 * cancellation to swallow), and it is asserted indirectly: a failing write is the only way the
 * guard is reached.
 */
class PlaylistPersistFailureTest {

    @AfterTest
    fun reset() {
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
    }

    private fun account(id: String) =
        XtreamAccount(id = id, name = "P", baseUrl = "http://h:8080", username = "u", password = "p")

    @Test
    fun `a failed write from a fresh store does not promote authority`() {
        XtreamRepository.installRawForTest(null)                 // Absent → not authoritative
        assertFalse(XtreamRepository.canPushFullReplace(), "sanity: fresh store is not authoritative")
        XtreamRepository.stageEditForTest(listOf(account("a")))  // user adds a playlist (in memory)
        XtreamRepository.persistWriteForTest = { _, _ -> throw RuntimeException("disk full") }

        assertFalse(XtreamRepository.persistForTest(), "persist reports failure — no false success")
        assertFalse(XtreamRepository.authoritativeForTest, "a failed write must NOT promote authority")
        assertFalse(XtreamRepository.canPushFullReplace(), "so a later login-flush cannot full-replace from an unpersisted edit")
        assertNull(XtreamRepository.lastStoredRawForTest, "the last durable blob is unchanged (no false success)")
        assertEquals(listOf("a"), XtreamRepository.uiState.value.accounts.map { it.id }, "the edit stays pending in memory")
    }

    @Test
    fun `a successful write from a fresh store promotes authority and records the blob`() {
        XtreamRepository.installRawForTest(null)
        XtreamRepository.stageEditForTest(listOf(account("a")))
        var written: String? = null
        XtreamRepository.persistWriteForTest = { _, json -> written = json }

        assertTrue(XtreamRepository.persistForTest(), "persist reports success when the write commits")
        assertTrue(XtreamRepository.authoritativeForTest, "a successful persist is authoritative")
        assertTrue(XtreamRepository.canPushFullReplace(), "and may now full-replace")
        assertEquals(written, XtreamRepository.lastStoredRawForTest, "lastStoredRaw tracks the durable bytes")
        assertTrue(written!!.contains("\"a\""), "the persisted blob carries the edit")
    }

    @Test
    fun `a failed write on an already-authoritative store keeps the last durable blob`() {
        // A clean load: authoritative, with a known durable blob.
        XtreamRepository.installRawForTest("[${accountJson("a")}]")
        assertTrue(XtreamRepository.canPushFullReplace())
        val durableBefore = XtreamRepository.lastStoredRawForTest

        XtreamRepository.stageEditForTest(listOf(account("a"), account("b"))) // add b
        XtreamRepository.persistWriteForTest = { _, _ -> throw RuntimeException("disk full") }
        XtreamRepository.persistForTest()

        assertEquals(durableBefore, XtreamRepository.lastStoredRawForTest, "a failed write does not advance the durable blob")
        assertEquals(listOf("a", "b"), XtreamRepository.uiState.value.accounts.map { it.id }, "the edit stays pending in memory")
    }

    @Test
    fun `a damaged store never reaches the write and never pushes`() {
        XtreamRepository.installRawForTest("[${accountJson("a")},{}]") // one row un-decodable → Recovered/damaged
        assertFalse(XtreamRepository.canPushFullReplace(), "sanity: a recovered store is not pushable")
        var writeCalled = false
        XtreamRepository.persistWriteForTest = { _, _ -> writeCalled = true }

        XtreamRepository.stageEditForTest(listOf(account("a")))
        XtreamRepository.persistForTest()

        assertFalse(writeCalled, "a damaged store must not overwrite the stored bytes at all")
    }

    private fun accountJson(id: String) =
        """{"id":"$id","name":"P","baseUrl":"http://h:8080","username":"u","password":"p"}"""
}
