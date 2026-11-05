package com.nuvio.app.features.recordings

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the fake server answers. [cutAfter]: send only this many body bytes, then close (a dropped stream). */
internal class Reply(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val cutAfter: Int? = null,
)

/** A tiny HTTP/1.1 server on a loopback port, one request per connection, recording each request's headers. */
internal class LoopbackHttpServer(private val handler: (headers: Map<String, String>) -> Reply) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val selector = SelectorManager(Dispatchers.Default)
    private var server: ServerSocket? = null
    val requests = ArrayList<Map<String, String>>()
    var port = 0
        private set

    fun url(path: String = "/show.ts") = "http://127.0.0.1:$port$path"

    suspend fun start(): LoopbackHttpServer {
        val s = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
        server = s
        port = (s.localAddress as InetSocketAddress).port
        scope.launch {
            while (isActive) {
                val socket = try { s.accept() } catch (e: Exception) { break }
                launch {
                    try {
                        val input = socket.openReadChannel()
                        val output = socket.openWriteChannel(autoFlush = false)
                        input.readUTF8Line(8192) ?: return@launch
                        val headers = LinkedHashMap<String, String>()
                        while (true) {
                            val line = input.readUTF8Line(8192) ?: break
                            if (line.isEmpty()) break
                            val i = line.indexOf(':')
                            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                        }
                        requests += headers
                        val reply = handler(headers)
                        val head = buildString {
                            append("HTTP/1.1 ${reply.status} X\r\n")
                            reply.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                            append("Content-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n")
                        }
                        output.writeStringUtf8(head)
                        val n = reply.cutAfter ?: reply.body.size
                        if (n > 0) output.writeFully(reply.body, 0, n)
                        output.flush()
                        // A dropped stream: let the client read what was sent before the connection goes away.
                        if (reply.cutAfter != null) delay(300)
                    } catch (e: Exception) {
                        // A client that hung up is the test's business, not the server's.
                    } finally {
                        socket.close()
                    }
                }
            }
        }
        return this
    }

    fun requestsSnapshot(): List<Map<String, String>> = requests.toList()

    fun stop() {
        try { server?.close() } catch (e: Exception) { }
        job.cancel()
        selector.close()
    }
}
