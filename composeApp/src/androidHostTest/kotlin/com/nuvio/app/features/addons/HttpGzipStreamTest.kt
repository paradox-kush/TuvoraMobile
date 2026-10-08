package com.nuvio.app.features.addons

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpGzipStreamTest {
    @Test fun plainHttpGzipAndRawGzipYieldTheSameXml() = runBlocking {
        val xml = "<tv><channel id=\"bbc\"><display-name>BBC One</display-name></channel></tv>"
        val gzip = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray()) }
        }.toByteArray()
        for (mode in listOf("plain", "http-gzip", "raw-gzip")) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10000
                val body = if (mode == "plain") xml.toByteArray() else gzip
                val worker = thread {
                    server.accept().use { socket ->
                        socket.soTimeout = 10000
                        val request = socket.getInputStream().bufferedReader()
                        while (!request.readLine().isNullOrEmpty()) { }
                        val encoding = if (mode == "http-gzip") "Content-Encoding: gzip\r\n" else ""
                        socket.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                encoding + "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body
                        )
                    }
                }
                try {
                    val lines = mutableListOf<String>()
                    httpStreamLines("http://127.0.0.1:${server.localPort}/guide", null) { lines += it }
                    assertEquals(xml, lines.joinToString(""), mode)
                } finally { worker.join(10000) }
            }
        }
    }
}
