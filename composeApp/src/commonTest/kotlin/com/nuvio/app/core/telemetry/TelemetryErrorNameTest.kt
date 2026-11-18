package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.TelemetryErrorName
import com.nuvio.app.features.addons.HttpStatusException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryErrorNameTest {
    // Stand-ins for R8-renamed app classes: the runtime name ("fb4") means nothing, the readable parent does.
    private class fb4(message: String) : IllegalStateException(message)
    private class u7(message: String) : kotlinx.io.IOException(message)
    private class q2(message: String) : SerializationException(message)
    private class zz : Throwable("secret")
    private class yy : Exception("secret")

    @Test
    fun `a renamed class reports its nearest readable ancestor and never the minified name`() {
        assertEquals("IllegalStateException", TelemetryErrorName.of(fb4("x")))
        assertEquals("IOException", TelemetryErrorName.of(u7("x")))
        assertEquals("SerializationException", TelemetryErrorName.of(q2("x")))
        assertEquals("Exception", TelemetryErrorName.of(yy()))
    }

    @Test
    fun `an http status failure reports the status not the class`() {
        assertEquals("http_403", TelemetryErrorName.of(HttpStatusException(403, "portal.example.com said no")))
        assertEquals("http_other", TelemetryErrorName.of(HttpStatusException(42, "odd")))
    }

    @Test
    fun `timeouts and cancellation keep their own names`() = runTest {
        val timeout = runCatching { withTimeout(10) { delay(1_000) } }.exceptionOrNull()!!
        assertEquals("TimeoutCancellationException", TelemetryErrorName.of(timeout))
        assertEquals("CancellationException", TelemetryErrorName.of(CancellationException("left")))
        assertEquals("HttpRequestTimeoutException", TelemetryErrorName.of(HttpRequestTimeoutException("https://h.example/x", 1_000L)))
    }

    @Test
    fun `common runtime types map by type`() {
        assertEquals("NumberFormatException", TelemetryErrorName.of(NumberFormatException("abc")))
        assertEquals("IllegalArgumentException", TelemetryErrorName.of(IllegalArgumentException("x")))
        assertEquals("NoSuchElementException", TelemetryErrorName.of(NoSuchElementException("x")))
        assertEquals("IndexOutOfBoundsException", TelemetryErrorName.of(IndexOutOfBoundsException("x")))
        assertEquals("UnsupportedOperationException", TelemetryErrorName.of(UnsupportedOperationException("x")))
    }

    @Test
    fun `an unknown throwable is other and null is none`() {
        assertEquals("other", TelemetryErrorName.of(zz()))
        assertEquals("none", TelemetryErrorName.of(null))
    }

    @Test
    fun `the name never carries message text`() {
        val out = TelemetryErrorName.of(IllegalStateException("user:pass@host.example.com"))
        assertEquals("IllegalStateException", out)
    }

    @Test
    fun `every name is a short closed token`() {
        val samples = listOf(fb4("a"), u7("b"), q2("c"), zz(), yy(), HttpStatusException(500, "m"), RuntimeException("r"), Error("e"))
        val token = Regex("""^[A-Za-z0-9_]{1,48}$""")
        for (s in samples) {
            val name = TelemetryErrorName.of(s)
            assertTrue(token.matches(name), "not a closed token: $name")
        }
    }
}
