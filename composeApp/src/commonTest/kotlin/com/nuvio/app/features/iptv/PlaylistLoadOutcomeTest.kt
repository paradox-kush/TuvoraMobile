package com.nuvio.app.features.iptv

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B24 — the persisted playlist store must decode element-wise and distinguish a genuine empty
 * collection from unreadable / partially-recovered data, so an incompatible row never becomes a
 * wipe. Only a clean decode is authoritative (may overwrite storage / full-replace the server).
 */
class PlaylistLoadOutcomeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun acc(id: String) =
        XtreamAccount(id = id, name = "P-$id", baseUrl = "http://h:8080", username = "u", password = "p")

    private fun arr(vararg rows: String) = rows.joinToString(prefix = "[", postfix = "]", separator = ",")
    private fun row(a: XtreamAccount) = json.encodeToString(a)

    @Test
    fun `a clean list decodes as Valid and is authoritative`() {
        val outcome = decodePlaylistStore(json, arr(row(acc("a")), row(acc("b"))))
        assertTrue(outcome is PlaylistLoadOutcome.Valid, "clean list is Valid")
        assertEquals(2, outcome.accounts.size)
        assertTrue(outcome.isAuthoritative, "a clean decode may push / overwrite")
    }

    @Test
    fun `one incompatible row among valid rows recovers the rest and is damaged`() {
        // The bad row is missing the required id → its element decode fails; the good one survives.
        val outcome = decodePlaylistStore(json, arr(row(acc("a")), """{"name":"B","baseUrl":"http://h2:8080","username":"u","password":"p"}"""))
        assertTrue(outcome is PlaylistLoadOutcome.Recovered, "partial decode is Recovered")
        outcome as PlaylistLoadOutcome.Recovered
        assertEquals(1, outcome.accounts.size, "the valid row is recovered")
        assertEquals("a", outcome.accounts.single().id)
        assertEquals(1, outcome.droppedCount, "one row dropped")
        assertFalse(outcome.isAuthoritative, "a partial subset must not replace local or server")
        assertTrue(outcome.isDamaged, "the stored bytes hold a row we could not read — preserve them")
    }

    @Test
    fun `an array of only-bad rows is Recovered-empty and not a genuine empty list`() {
        val outcome = decodePlaylistStore(json, """[{},{"nope":1}]""")
        assertTrue(outcome is PlaylistLoadOutcome.Recovered, "all-bad array is Recovered, not Valid")
        assertTrue(outcome.accounts.isEmpty())
        assertFalse(outcome.isAuthoritative, "must never wipe from an all-bad read")
        assertTrue(outcome.isDamaged)
    }

    @Test
    fun `a non-array blob is Corrupt and damaged`() {
        assertTrue(decodePlaylistStore(json, "{\"not\":\"an array\"}") is PlaylistLoadOutcome.Corrupt)
        assertTrue(decodePlaylistStore(json, "totally not json") is PlaylistLoadOutcome.Corrupt)
        assertFalse(decodePlaylistStore(json, "garbage").isAuthoritative, "corrupt data must not push")
        assertTrue(decodePlaylistStore(json, "garbage").isDamaged, "corrupt bytes must be preserved")
    }

    @Test
    fun `an explicit empty array is Valid and authoritative — a user who deleted every playlist still pushes the deletion`() {
        val emptyArray = decodePlaylistStore(json, "[]")
        assertTrue(emptyArray is PlaylistLoadOutcome.Valid)
        assertTrue(emptyArray.accounts.isEmpty())
        assertTrue(emptyArray.isAuthoritative, "an explicit [] is a deliberate delete-all")
        assertFalse(emptyArray.isDamaged)
    }

    @Test
    fun `blank or null storage is Absent — not authoritative and not damaged`() {
        // Absent is a fresh install / cleared store / DataStore corruption-reset (which writes an
        // empty store, not []). It must not push an empty full-replace (could wipe another device's
        // server copy) but it is not damaged — a fresh add persists normally.
        for (blank in listOf(null, "", "   ")) {
            val outcome = decodePlaylistStore(json, blank)
            assertTrue(outcome is PlaylistLoadOutcome.Absent, "blank/null is Absent, not a genuine empty")
            assertTrue(outcome.accounts.isEmpty())
            assertFalse(outcome.isAuthoritative, "an absent store must not push an empty wipe")
            assertFalse(outcome.isDamaged, "nothing was lost — a fresh add may still persist")
        }
    }

    @Test
    fun `an unknown or newer field on a valid row does not drop the row`() {
        val withFutureField =
            """[{"id":"a","name":"A","baseUrl":"http://h:8080","username":"u","password":"p","futureFieldFromNewerBuild":{"x":1,"y":[2,3]}}]"""
        val outcome = decodePlaylistStore(json, withFutureField)
        assertTrue(outcome is PlaylistLoadOutcome.Valid, "unknown top-level keys are ignored, not fatal")
        assertEquals(1, outcome.accounts.size)
        assertEquals("a", outcome.accounts.single().id)
        assertTrue(outcome.isAuthoritative)
    }
}
