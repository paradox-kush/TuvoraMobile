@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.core.journal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * File-backed journal in the app's Documents directory. `writeToFile(atomically = true)` writes to a
 * temp file and renames it into place; we then READ BACK and compare — a write call returning is not
 * proof of durable persistence. The blob is small (bounded by StartupJournalPolicy.MAX_BLOB_CHARS).
 */
internal actual object StartupJournalStore {
    private const val FILE = "startup-journal.json"

    private fun path(): String? {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return null
        return "$documents/$FILE"
    }

    actual fun read(): String? = runCatching {
        val p = path() ?: return@runCatching null
        val handle = NSFileHandle.fileHandleForReadingAtPath(p) ?: return@runCatching null
        val data = try {
            // Read at most cap+1 — a corrupt/tampered file is never fully mapped into memory.
            handle.readDataOfLength((StartupJournalPolicy.MAX_BLOB_BYTES + 1).convert())
        } finally {
            handle.closeFile()
        }
        if (data.length.toLong() > StartupJournalPolicy.MAX_BLOB_BYTES.toLong()) {
            NSFileManager.defaultManager.removeItemAtPath(p, error = null)
            return@runCatching null
        }
        data.toByteArray().decodeToString()
    }.getOrNull()

    actual fun writeVerified(content: String): Boolean = runCatching {
        val p = path() ?: return false
        val ok = NSString.create(string = content)
            .writeToFile(p, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        ok && NSString.stringWithContentsOfFile(p, encoding = NSUTF8StringEncoding, error = null) == content
    }.getOrDefault(false)
}

internal actual fun journalNowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** Copies an NSData's bytes into a Kotlin ByteArray (the repo's proven pinned-pointer bridge). */
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val src = bytes ?: return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), src, len.convert()) }
    return out
}
