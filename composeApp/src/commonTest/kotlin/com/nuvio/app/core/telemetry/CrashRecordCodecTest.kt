package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.CrashFrames
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

    // --- privacy: message text is never sent (reviewer blocker) ---

    private class Leaky(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private val secrets = listOf(
        """JSON input: {"user_info":{"username":"john","password":"s3cret"}}""",
        """Unable to resolve host "MyIptv.Provider.com": No address associated with hostname""",
        "Portal rejected mac 00:1A:79:AB:CD:EF",
        "open failed: /Users/jane.doe/Library/Application Support/Tuvora/playlist.m3u",
        """open failed: C:\Users\jane.doe\AppData\Local\Tuvora\playlist.m3u""",
        "GET http://pro.provider.tv:8080/get.php?username=bob&password=pw failed",
    )

    @Test
    fun `no exception message text leaves the device for any of the leak shapes`() {
        for (secret in secrets) {
            val error = Leaky(secret, Leaky("inner: $secret"))
            val joined = CrashRecordCodec.framesOf(error).joinToString("\n")
            for (token in listOf("john", "s3cret", "MyIptv", "Provider", "00:1A", "jane", "doe", "provider.tv", "bob", "pw", "Library", "AppData", "username", "password", "inner")) {
                assertFalse(token in joined, "leaked '$token' from <$secret>:\n$joined")
            }
        }
    }

    @Test
    fun `the type of every exception in the chain survives as the only text`() {
        val lines = CrashRecordCodec.framesOf(Leaky("outer secret", Leaky("inner secret")))
        assertTrue(lines.first().endsWith("Leaky"), lines.first())
        assertTrue(lines.any { it.startsWith("Caused by: ") && it.endsWith("Leaky") }, lines.toString())
    }

    @Test
    fun `a frame keeps its file and line`() {
        assertEquals("at com.nuvio.app.Foo.bar(Foo.kt:12)", CrashFrames.parseFrame("at com.nuvio.app.Foo.bar(Foo.kt:12)"))
        assertEquals("at java.base/java.lang.Thread.run(Thread.java:840)", CrashFrames.parseFrame("at java.base/java.lang.Thread.run(Thread.java:840)"))
        assertEquals("at a.B.c(Native Method)", CrashFrames.parseFrame("at a.B.c(Native Method)"))
        assertEquals("at com.x.Y.lambda\$1(Y.kt:3)", CrashFrames.parseFrame("at com.x.Y.lambda\$1(Y.kt:3)"))
    }

    @Test
    fun `a native frame keeps the symbol and the file name but not the directory`() {
        val line = "at 3   App   0x0000000104a8c1d4  kfun:com.nuvio.app.Foo#bar(){}kotlin.Int + 120 (/Users/jane.doe/proj/Foo.kt:12:3)"
        val out = CrashFrames.parseFrame(line)!!
        assertTrue("kfun:com.nuvio.app.Foo#bar(){}kotlin.Int" in out, out)
        assertTrue("(Foo.kt:12)" in out, out)
        assertFalse("jane" in out, out)
    }

    @Test
    fun `lines that are not frames are dropped not scrubbed`() {
        assertNull(CrashFrames.parseFrame("Unable to resolve host \"x.example.com\""))
        assertNull(CrashFrames.parseFrame("at http://x.example.com/live(1)"))
        assertNull(CrashFrames.parseFrame("password=hunter2"))
    }

    @Test
    fun `a deep stack is cut to 64 lines`() {
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalStateException("deep") else deep(n - 1)
        val error = runCatching { deep(200) }.exceptionOrNull()!!
        assertTrue(CrashRecordCodec.framesOf(error).size <= CrashRecordCodec.MAX_FRAMES)
    }
}
