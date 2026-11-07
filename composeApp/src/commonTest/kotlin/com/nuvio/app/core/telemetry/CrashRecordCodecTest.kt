package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.CrashRecord
import com.nuvio.app.core.telemetry.internal.CrashRecordCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashRecordCodecTest {
    private fun record() = CrashRecord(
        crash_id = "abc123", support_code = "K7Q29MPX", app_version = "1.18.1", at_ms = 1234L, thread = "main",
        exception_type = "java.lang.IllegalStateException", message = "boom",
        stack = listOf("java.lang.IllegalStateException: boom", "at com.nuvio.app.Foo.bar(Foo.kt:1)"),
        trail = "[[1,\"playback\",\"x\",{}]]", memory = mapOf("java_mb" to 120L), screen = "Player",
    )

    @Test
    fun `a record survives encode and decode`() {
        assertEquals(record(), CrashRecordCodec.decode(CrashRecordCodec.encode(record())))
    }

    @Test
    fun `unreadable or unknown-schema text decodes to null so it is deleted not retried`() {
        assertNull(CrashRecordCodec.decode("not json"))
        assertNull(CrashRecordCodec.decode(CrashRecordCodec.encode(record()).replace("\"schema_version\":1", "\"schema_version\":99")))
        assertNull(CrashRecordCodec.decode(CrashRecordCodec.encode(record().copy(crash_id = ""))))
    }

    @Test
    fun `unknown fields from a newer writer are ignored`() {
        val text = CrashRecordCodec.encode(record()).dropLast(1) + ",\"future_field\":\"x\"}"
        assertNotNull(CrashRecordCodec.decode(text))
    }

    @Test
    fun `frames keep the cause chain and stop at 64 lines`() {
        val cause = IllegalArgumentException("root")
        val top = IllegalStateException("top", cause)
        val frames = CrashRecordCodec.framesOf(top)
        assertTrue(frames.first().contains("IllegalStateException"))
        assertTrue(frames.any { it.startsWith("Caused by:") && it.contains("IllegalArgumentException") })
        assertTrue(frames.size <= CrashRecordCodec.MAX_FRAMES)
    }

    @Test
    fun `a deep stack is cut to 64 frames`() {
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalStateException("deep") else deep(n - 1)
        val error = runCatching { deep(200) }.exceptionOrNull()!!
        assertEquals(CrashRecordCodec.MAX_FRAMES, CrashRecordCodec.framesOf(error).size)
    }

    @Test
    fun `messages with a provider url are scrubbed`() {
        val msg = CrashRecordCodec.messageOf(IllegalStateException("failed http://prov.example.tv:8080/live/bob/pw/1.ts"))!!
        assertFalse("prov.example" in msg || "bob" in msg || "pw" in msg, msg)
    }
}
