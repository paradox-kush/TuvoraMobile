package com.tuvora.tvos.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvTelemetryPolicyTest {
    @Test
    fun `support code is 8 chars from the unambiguous alphabet and shown in two chunks`() {
        var n = 0
        val code = TvSupportCode.generate { bound -> (n++ * 7) % bound }
        assertEquals(8, code.length)
        assertTrue(code.all { it in TvSupportCode.ALPHABET })
        assertTrue(TvSupportCode.ALPHABET.none { it in "01OIL" })
        assertEquals("K7Q2-9MPX", TvSupportCode.display("K7Q29MPX"))
    }

    @Test
    fun `lookup accepts lowercase and a missing dash and rejects look-alikes`() {
        assertEquals("K7Q29MPX", TvSupportCode.normalize("k7q2-9mpx"))
        assertEquals("K7Q29MPX", TvSupportCode.normalize(" K7Q29MPX "))
        assertNull(TvSupportCode.normalize("K7Q2-9MP0"))
        assertNull(TvSupportCode.normalize("K7Q2-9MP"))
    }

    @Test
    fun `secure random stays inside the bound`() {
        repeat(200) { assertTrue(secureRandomInt(TvSupportCode.ALPHABET.length) in 0 until TvSupportCode.ALPHABET.length) }
    }

    @Test
    fun `one report per two minutes then free again`() {
        val limit = TvReportRateLimit()
        assertEquals(0, limit.retryInSec(1_000))
        limit.markSent(1_000)
        assertEquals(120, limit.retryInSec(1_000))
        assertEquals(1, limit.retryInSec(120_000))
        assertEquals(0, limit.retryInSec(121_000))
    }

    @Test
    fun `crash record round-trips with a capped scrubbed stack and no message`() {
        val frames = (1..100).map { "at $it kfun:Foo.bar https://secret.example/x?token=abc" }
        val json = Json.parseToJsonElement(
            TvCrashRecord.encode("id-1", "K7Q29MPX", "1.0.0", 42L, "main", "kotlin.IllegalStateException", frames, 3_000_000_000L),
        ).jsonObject
        assertEquals("1", json["schema_version"]!!.jsonPrimitive.content)
        assertEquals("K7Q29MPX", json["support_code"]!!.jsonPrimitive.content)
        val stack = json["stack"] as JsonArray
        assertEquals(64, stack.size)
        assertFalse(stack.joinToString { it.jsonPrimitive.content }.contains("secret.example"))
        assertFalse(json.containsKey("message"))
    }

    @Test
    fun `framesOf keeps frames and cause headers but not the exception message`() {
        val trace = "kotlin.IllegalStateException: https://host/pw=1\n\tat 0   TuvoraCore 0x1 kfun:A.b\nCaused by: kotlin.Error: boom\n\tat 1   TuvoraCore 0x2 kfun:C.d"
        val frames = TvCrashRecord.framesOf(trace)
        assertEquals(3, frames.size)
        assertTrue(frames.none { it.contains("host") || it.contains("boom") })
    }
}
