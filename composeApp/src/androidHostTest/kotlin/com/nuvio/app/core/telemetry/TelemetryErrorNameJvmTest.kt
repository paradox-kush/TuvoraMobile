package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.TelemetryErrorName
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryErrorNameJvmTest {
    // An R8-renamed IOException subclass (an OkHttp or app class in a release build).
    private class fb4(message: String) : IOException(message)

    @Test
    fun `jvm network failures keep their own names`() {
        assertEquals("UnknownHostException", TelemetryErrorName.of(UnknownHostException("host.example")))
        assertEquals("SocketTimeoutException", TelemetryErrorName.of(SocketTimeoutException("t")))
        assertEquals("ConnectException", TelemetryErrorName.of(ConnectException("refused")))
        assertEquals("SSLHandshakeException", TelemetryErrorName.of(SSLHandshakeException("cert")))
        assertEquals("EOFException", TelemetryErrorName.of(EOFException("eof")))
        assertEquals("FileNotFoundException", TelemetryErrorName.of(FileNotFoundException("/data/x")))
    }

    @Test
    fun `a renamed io subclass reports IOException`() {
        assertEquals("IOException", TelemetryErrorName.of(fb4("secret")))
    }

    @Test
    fun `out of memory keeps its name`() {
        assertEquals("OutOfMemoryError", TelemetryErrorName.of(OutOfMemoryError("big")))
    }
}
