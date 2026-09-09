package com.nuvio.app.features.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The transport adapter's cleanup contract, over a REAL loopback socket (not the pure line
 * accumulator): when the `onLine` callback throws — which is exactly what the channels-index
 * parser does on an oversized/malformed record — [httpStreamLines] must propagate the throw AND
 * close the response, so the upstream read stops instead of draining the rest of a large body.
 *
 * The server offers a body far larger than any kernel send buffer and records whether it managed
 * to write the whole thing. The client throws from the first line; if cleanup works, the server's
 * later writes fail (connection reset) — proving the socket was closed early, not read to the end.
 */
class HttpStreamLinesCleanupTest {
    private lateinit var server: ServerSocket
    private val bodyBytes = 16 * 1024 * 1024 // 16 MB — well past any socket send buffer

    @AfterTest
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun `a throw from onLine closes the response and stops upstream reading`() {
        val wroteEntireBody = AtomicBoolean(false)
        val serverClosedEarly = AtomicBoolean(false)
        val started = CountDownLatch(1)

        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        val serverThread = thread(name = "cleanup-test-server") {
            runCatching {
                started.countDown()
                server.accept().use { sock ->
                    sock.soTimeout = 10_000
                    // Drain the request headers up to the blank line.
                    val input = sock.getInputStream()
                    val req = StringBuilder()
                    while (!req.endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b == -1) return@use
                        req.append(b.toChar())
                    }
                    val out = sock.getOutputStream()
                    // No Content-Length + Connection: close → OkHttp reads until the socket closes.
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(),
                    )
                    // A newline-terminated first line so the client's onLine fires immediately.
                    out.write("PING\n".toByteArray())
                    out.flush()
                    // Now try to push a large body. The client throws on "PING" and closes; these
                    // writes must eventually fail once the reset propagates.
                    val chunk = ByteArray(64 * 1024) { 'y'.code.toByte() }
                    var written = 0
                    try {
                        while (written < bodyBytes) {
                            out.write(chunk)
                            out.flush()
                            written += chunk.size
                        }
                        wroteEntireBody.set(true)
                    } catch (_: SocketException) {
                        serverClosedEarly.set(true)
                    } catch (_: IOException) {
                        serverClosedEarly.set(true)
                    }
                }
            }
        }

        started.await(5, TimeUnit.SECONDS)
        val port = server.localPort
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        assertFailsWith<RuntimeException> {
            runBlocking {
                httpStreamLines("http://127.0.0.1:$port/index", null) { _ ->
                    calls.incrementAndGet()
                    throw RuntimeException("reject on first line")
                }
            }
        }

        serverThread.join(10_000)
        assertTrue(calls.get() >= 1, "onLine should have been called at least once")
        assertTrue(
            serverClosedEarly.get() && !wroteEntireBody.get(),
            "server should observe the client closing the connection before the 16 MB body was fully sent " +
                "(closedEarly=${serverClosedEarly.get()}, wroteEntireBody=${wroteEntireBody.get()})",
        )
    }

    @Test
    fun `cancelling the coroutine stops the blocking read instead of waiting for the server`() = runBlocking {
        // Server sends headers + one line, then STALLS for 30s without sending the rest of the body,
        // so the client blocks reading. If cancellation did not close the call, cancelAndJoin would
        // hang for the whole stall; the withTimeout below is the proof that the read actually stopped.
        val started = CountDownLatch(1)
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 30_000
        val serverThread = thread(name = "cancel-test-server") {
            runCatching {
                started.countDown()
                server.accept().use { sock ->
                    sock.soTimeout = 30_000
                    val input = sock.getInputStream()
                    val req = StringBuilder()
                    while (!req.endsWith("\r\n\r\n")) {
                        val b = input.read(); if (b == -1) return@use; req.append(b.toChar())
                    }
                    val out = sock.getOutputStream()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: 100000000\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write("PING\n".toByteArray())
                    out.flush()
                    runCatching { Thread.sleep(30_000) } // stall; the client's read blocks waiting for more body
                }
            }
        }
        started.await(5, TimeUnit.SECONDS)
        val port = server.localPort
        val lines = AtomicInteger(0)
        val job = launch(Dispatchers.IO) {
            httpStreamLines("http://127.0.0.1:$port/x", null) { _ -> lines.incrementAndGet() }
        }
        // Wait until the client has read the first line and is now blocked on the (never-arriving) body.
        withTimeout(5_000) { while (lines.get() == 0) delay(20) }
        // The cancel must interrupt the blocking read promptly — not wait out the 30s server stall.
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(lines.get() >= 1, "the first line was read before the stall")
        serverThread.join(2_000)
    }
}
