package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.internal.DiagTrailPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagTrailPolicyTest {
    private fun entry(code: String = "start", fields: Map<String, Any> = emptyMap(), at: Long = 1000L) =
        DiagEntry(at, DiagArea.PLAYBACK, code, fields)

    @Test
    fun `allow-listed enum and number fields pass`() {
        val clean = DiagTrailPolicy.sanitize(entry(fields = mapOf("engine" to "exo", "duration_ms" to 1200L, "attempt" to 2)))!!
        assertEquals(mapOf("engine" to "exo", "duration_ms" to 1200L, "attempt" to 2), clean.fields)
    }

    @Test
    fun `fields not on the allow-list are dropped even when their value looks harmless`() {
        val clean = DiagTrailPolicy.sanitize(entry(fields = mapOf("channel_name" to "bbc_one", "username" to "bob", "engine" to "mpv")))!!
        assertEquals(setOf("engine"), clean.fields.keys)
    }

    @Test
    fun `urls and hosts in an allowed field are dropped not redacted`() {
        val clean = DiagTrailPolicy.sanitize(
            entry(fields = mapOf("reason" to "http://pro.provider.tv:8080/live/u/p/1.ts", "source" to "pro.provider.tv", "state" to "Free text with spaces")),
        )!!
        assertTrue(clean.fields.isEmpty(), clean.fields.toString())
    }

    @Test
    fun `an entry whose own code is not a clean code is discarded`() {
        assertNull(DiagTrailPolicy.sanitize(entry(code = "http://x.example/abc")))
        assertNull(DiagTrailPolicy.sanitize(entry(code = "Has Spaces")))
        assertNotNull(DiagTrailPolicy.sanitize(entry(code = "first_frame")))
    }

    @Test
    fun `non-finite numbers and arbitrary objects are dropped`() {
        val clean = DiagTrailPolicy.sanitize(entry(fields = mapOf("count" to Double.NaN, "total" to listOf(1), "attempt" to 1)))!!
        assertEquals(setOf("attempt"), clean.fields.keys)
    }

    @Test
    fun `serialization keeps at most 80 entries and the newest ones`() {
        val entries = (1..200).map { entry(code = "e$it", at = it * 10L) }
        val json = DiagTrailPolicy.serialize(entries, nowMs = 5000L)
        assertTrue("e200" in json)
        assertTrue("e121" in json)
        assertFalse("\"e120\"" in json)
        assertEquals(80, Regex("\"e\\d+\"").findAll(json).count())
    }

    @Test
    fun `serialization stays under the byte bound`() {
        val fields = mapOf("engine" to "exoplayer", "reason" to "a_long_but_clean_code_value", "phase" to "prepare", "outcome" to "failed")
        val entries = (1..500).map { entry(code = "playback_event_$it", fields = fields, at = it.toLong()) }
        val json = DiagTrailPolicy.serialize(entries, nowMs = 10_000L, maxEntries = 500, maxBytes = DiagTrailPolicy.MAX_BYTES)
        assertTrue(json.length <= DiagTrailPolicy.MAX_BYTES, "was ${json.length}")
        assertTrue("playback_event_500" in json)
    }

    @Test
    fun `entries carry their age so the trail reads relative to the error`() {
        val json = DiagTrailPolicy.serialize(listOf(entry(code = "a", at = 4000L)), nowMs = 9000L)
        assertTrue(json.startsWith("[[5000,\"playback\",\"a\""), json)
    }

    @Test
    fun `free text loses urls and hosts but keeps class names and frames`() {
        val text = "java.net.UnknownHostException: Unable to resolve host \"pro.provider.tv\" for http://pro.provider.tv:8080/get.php?username=bob&password=x at com.nuvio.app.Foo.bar(Foo.kt:12)"
        val out = DiagTrailPolicy.scrubText(text, 400)
        assertFalse("provider" in out, out)
        assertFalse("password" in out, out)
        assertTrue("java.net.UnknownHostException" in out, out)
        assertTrue("com.nuvio.app.Foo.bar" in out, out)
    }

    @Test
    fun `an ip address with a port is scrubbed`() {
        assertFalse("192.168.1.5" in DiagTrailPolicy.scrubText("connect to 192.168.1.5:8096 failed", 100))
    }
}
