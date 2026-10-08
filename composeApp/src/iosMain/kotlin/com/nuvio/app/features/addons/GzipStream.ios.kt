@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.addons

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.*
import platform.posix.memset
import platform.zlib.*

/** Emit bounded decoded chunks, sniffing bytes after Darwin's HTTP Content-Encoding decoding. */
internal suspend fun streamMaybeGzipChunks(
    channel: ByteReadChannel,
    maxBytes: Long,
    onChunk: (ByteArray, Int) -> Unit,
) {
    if (maxBytes <= 0) return
    val prefix = ByteArray(2)
    var prefixSize = 0
    while (prefixSize < 2) {
        val n = channel.readAvailable(prefix, prefixSize, 2 - prefixSize)
        if (n < 0) break
        prefixSize += n
    }
    var emitted = 0L
    fun emit(bytes: ByteArray, size: Int) {
        val take = minOf(size.toLong(), maxBytes - emitted).toInt()
        if (take > 0) { onChunk(bytes, take); emitted += take }
    }
    val input = ByteArray(64 * 1024)
    if (prefixSize < 2 || prefix[0] != 0x1f.toByte() || prefix[1] != 0x8b.toByte()) {
        emit(prefix, prefixSize)
        while (emitted < maxBytes) {
            val n = channel.readAvailable(input, 0, minOf(input.size.toLong(), maxBytes - emitted).toInt())
            if (n < 0) break
            emit(input, n)
        }
        return
    }
    // zlib owns only its fixed-size inflate state; neither compressed nor decoded guide is
    // accumulated. Pins live only around inflate, never around a suspending network read.
    memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().convert())
        check(inflateInit2_(stream.ptr, 15 + 16, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) == Z_OK) {
            "Cannot initialize gzip decoder"
        }
        val output = ByteArray(64 * 1024)
        var ended = false
        try {
            fun decode(bytes: ByteArray, size: Int) {
                if (size == 0) return
                if (ended) {
                    check(inflateReset2(stream.ptr, 15 + 16) == Z_OK)
                    ended = false
                }
                bytes.usePinned { source ->
                    stream.next_in = source.addressOf(0).reinterpret()
                    stream.avail_in = size.toUInt()
                    do {
                        val before = stream.avail_in
                        val status = output.usePinned { target ->
                            stream.next_out = target.addressOf(0).reinterpret()
                            stream.avail_out = output.size.toUInt()
                            inflate(stream.ptr, Z_NO_FLUSH)
                        }
                        val produced = output.size - stream.avail_out.toInt()
                        emit(output, produced)
                        check(status == Z_OK || status == Z_STREAM_END || status == Z_BUF_ERROR) { "Invalid gzip stream ($status)" }
                        if (emitted >= maxBytes) return
                        if (status == Z_STREAM_END) {
                            ended = true
                            if (stream.avail_in == 0u) break
                            // Concatenated gzip members are legal (e.g. an appended provider export).
                            val remaining = stream.avail_in
                            val next = stream.next_in
                            check(inflateReset2(stream.ptr, 15 + 16) == Z_OK)
                            stream.next_in = next
                            stream.avail_in = remaining
                            ended = false
                        } else if (produced == 0 && before == stream.avail_in) {
                            check(stream.avail_in == 0u) { "Gzip decoder made no progress" }
                            break
                        }
                    } while (stream.avail_in > 0u || stream.avail_out == 0u)
                }
            }
            decode(prefix, prefixSize)
            while (emitted < maxBytes) {
                val n = channel.readAvailable(input, 0, input.size)
                if (n < 0) break
                decode(input, n)
            }
            // A bounded probe may intentionally stop before the trailer. A full fetch may not.
            check(emitted >= maxBytes || ended) { "Truncated gzip stream" }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}
