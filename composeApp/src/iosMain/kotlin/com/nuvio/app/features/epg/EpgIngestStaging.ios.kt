@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.features.epg

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.writeData
import platform.posix.memcpy

/**
 * iOS staging: a unique temp file under NSTemporaryDirectory. Writes are buffered (~64 KB) so memory
 * stays flat; read-back streams the file in 64 KB chunks and splits on the 0x0A byte (ASCII '\n',
 * which never appears inside a UTF-8 multi-byte sequence), so the whole file is never mapped in at
 * once and no line is ever split across a codepoint. Deleted on [dispose].
 */
internal actual class EpgIngestStaging actual constructor() {
    private val path: String = NSTemporaryDirectory() + "epg-index-stage-" + NSUUID().UUIDString() + ".ndjson"
    private val buffer = StringBuilder()
    private var writeHandle: NSFileHandle?
    private var readHandle: NSFileHandle? = null
    private var carry = ByteArray(0)
    private val pending = ArrayDeque<String>()
    private var eof = false

    init {
        NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
        writeHandle = NSFileHandle.fileHandleForWritingAtPath(path)
    }

    actual fun append(line: String) {
        buffer.append(line).append('\n')
        if (buffer.length >= FLUSH_CHARS) flushBuffer()
    }

    private fun flushBuffer() {
        if (buffer.isEmpty()) return
        val data = NSString.create(string = buffer.toString()).dataUsingEncoding(NSUTF8StringEncoding)
        if (data != null) writeHandle?.writeData(data)
        buffer.clear()
    }

    actual fun openForRead() {
        flushBuffer()
        runCatching { writeHandle?.closeFile() }
        writeHandle = null
        readHandle = NSFileHandle.fileHandleForReadingAtPath(path)
    }

    actual fun nextLine(): String? {
        while (pending.isEmpty() && !eof) fillPending()
        return pending.removeFirstOrNull()
    }

    private fun fillPending() {
        val h = readHandle
        if (h == null) { eof = true; return }
        val chunk = h.readDataOfLength(CHUNK_BYTES.convert()).toByteArray()
        if (chunk.isEmpty()) {
            eof = true
            if (carry.isNotEmpty()) { pending.addLast(carry.decodeToString()); carry = ByteArray(0) }
            return
        }
        val buf = if (carry.isEmpty()) chunk else carry + chunk
        var start = 0
        var nl = buf.indexOf(NEWLINE, start)
        while (nl >= 0) {
            pending.addLast(buf.decodeToString(start, nl))
            start = nl + 1
            nl = buf.indexOf(NEWLINE, start)
        }
        carry = if (start == 0) buf else buf.copyOfRange(start, buf.size)
    }

    actual fun dispose() {
        runCatching { writeHandle?.closeFile() }
        runCatching { readHandle?.closeFile() }
        writeHandle = null
        readHandle = null
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    private companion object {
        const val FLUSH_CHARS = 64 * 1024
        const val CHUNK_BYTES = 64 * 1024
        const val NEWLINE = '\n'.code.toByte()
    }
}

private fun ByteArray.indexOf(byte: Byte, from: Int): Int {
    var i = from
    while (i < size) { if (this[i] == byte) return i; i++ }
    return -1
}

private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val src = bytes ?: return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), src, len.convert()) }
    return out
}
