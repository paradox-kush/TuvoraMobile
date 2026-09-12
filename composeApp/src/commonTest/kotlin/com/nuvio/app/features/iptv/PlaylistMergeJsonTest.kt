package com.nuvio.app.features.iptv

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B24 §3 — a Valid row can carry fields a newer build wrote and this build's serializer ignores. A
 * plain re-encode on the next edit strips them; [mergePlaylistJson] preserves them (local storage
 * only — the sync push + fixed backend columns cannot carry an unknown field regardless).
 */
class PlaylistMergeJsonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val original =
        """[{"id":"a","name":"Old","baseUrl":"http://h:8080","username":"u","password":"p","futureFlag":true,"futureNested":{"x":1,"y":[2,3]}}]"""

    @Test
    fun `a plain re-encode strips unknown fields - the defect`() {
        val loaded = json.decodeFromString<List<XtreamAccount>>(original)
        val reencoded = json.encodeToString(loaded)
        assertFalse(reencoded.contains("futureNested"), "plain re-encode loses the unknown nested field")
        assertFalse(reencoded.contains("futureFlag"), "and the unknown flat field")
    }

    @Test
    fun `merge preserves unknown flat and nested fields through a known-field edit`() {
        val loaded = json.decodeFromString<List<XtreamAccount>>(original)
        val edited = loaded.map { it.copy(name = "New") } // edit a KNOWN field
        val out = mergePlaylistJson(json, original, edited)

        assertTrue(out.contains("futureNested"), "unknown nested object survives")
        assertTrue(out.contains("\"y\""), "its nested array survives")
        assertTrue(out.contains("futureFlag"), "unknown flat field survives")
        // and the edit actually applied
        assertEquals("New", json.decodeFromString<List<XtreamAccount>>(out).single().name)
    }

    @Test
    fun `a new row with no original is encoded fresh`() {
        val newAcc = XtreamAccount(id = "b", name = "B", baseUrl = "http://h2:8080", username = "u", password = "p")
        val out = mergePlaylistJson(json, original, listOf(newAcc))
        val reloaded = json.decodeFromString<List<XtreamAccount>>(out)
        assertEquals(1, reloaded.size)
        assertEquals("b", reloaded.single().id)
    }

    @Test
    fun `a removed row is not emitted and its unknown fields do not resurrect`() {
        val out = mergePlaylistJson(json, original, emptyList())
        assertEquals("[]", out)
    }

    @Test
    fun `clearing a known optional field removes its stored value and does NOT restore it`() {
        // A row that already has userAgent set (a KNOWN field), plus an unknown forward-compat field.
        val stored =
            """[{"id":"a","name":"P","baseUrl":"http://h:8080","username":"u","password":"p","userAgent":"CustomUA","futureFlag":true}]"""
        val loaded = json.decodeFromString<List<XtreamAccount>>(stored)
        assertEquals("CustomUA", loaded.single().userAgent, "sanity: the UA loaded")

        val cleared = loaded.map { it.copy(userAgent = null) } // user cleared the UA
        val out = mergePlaylistJson(json, stored, cleared)

        assertFalse(out.contains("CustomUA"), "a cleared known field must not be restored from the original overlay")
        assertNull(json.decodeFromString<List<XtreamAccount>>(out).single().userAgent, "the cleared UA stays cleared on reload")
        assertTrue(out.contains("futureFlag"), "but a genuine unknown field is still preserved")
    }

    @Test
    fun `resetting a known field to its default value overwrites a stale non-default`() {
        val stored =
            """[{"id":"a","name":"P","baseUrl":"http://h:8080","username":"u","password":"p","autoRefreshHours":12,"futureFlag":true}]"""
        val loaded = json.decodeFromString<List<XtreamAccount>>(stored)
        assertEquals(12, loaded.single().autoRefreshHours, "sanity: the non-default loaded")

        val reset = loaded.map { it.copy(autoRefreshHours = 24) } // back to the default
        val out = mergePlaylistJson(json, stored, reset)

        assertEquals(24, json.decodeFromString<List<XtreamAccount>>(out).single().autoRefreshHours, "the default value wins over the stale 12")
        assertTrue(out.contains("futureFlag"), "the unknown field is still preserved")
    }

    @Test
    fun `a null or unreadable original merges as a plain fresh encode`() {
        val acc = XtreamAccount(id = "a", name = "A", baseUrl = "http://h:8080", username = "u", password = "p")
        assertEquals(1, json.decodeFromString<List<XtreamAccount>>(mergePlaylistJson(json, null, listOf(acc))).size)
        assertEquals(1, json.decodeFromString<List<XtreamAccount>>(mergePlaylistJson(json, "garbage", listOf(acc))).size)
    }
}
