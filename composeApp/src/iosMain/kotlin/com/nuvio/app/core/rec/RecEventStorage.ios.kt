@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.core.rec

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
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

private const val QUEUE_FILE = "rec-events-queue.jsonl"

internal actual object RecEventStorage {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    actual fun loadString(key: String): String? = defaults.stringForKey(key)

    actual fun saveString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun removeString(key: String) {
        defaults.removeObjectForKey(key)
    }

    actual fun loadBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)

    actual fun saveBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    actual fun loadLong(key: String, default: Long): Long =
        if (defaults.objectForKey(key) == null) default else defaults.integerForKey(key)

    actual fun saveLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
    }

    actual fun loadQueue(): String? = runCatching {
        val path = queuePath() ?: return@runCatching null
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return@runCatching null
        val data = try {
            // Read at most MAX_QUEUE_BYTES + 1 — the file is consumed only up to the cap, never wholly
            // mapped into memory (unlike stringWithContentsOfFile). Over the cap → oversized; drop it.
            handle.readDataOfLength((RecEventQueueRestorePolicy.MAX_QUEUE_BYTES + 1).convert())
        } finally {
            handle.closeFile()
        }
        if (data.length.toLong() > RecEventQueueRestorePolicy.MAX_QUEUE_BYTES.toLong()) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            return@runCatching null
        }
        data.toByteArray().decodeToString()
    }.getOrNull()

    actual fun saveQueue(contents: String?) {
        runCatching {
            val path = queuePath() ?: return
            if (contents == null) {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            } else {
                // NSString.create is the supported bridge; a Kotlin String cast to NSString is
                // not valid in Kotlin/Native.
                NSString.create(string = contents).writeToFile(
                    path,
                    atomically = true,
                    encoding = NSUTF8StringEncoding,
                    error = null,
                )
            }
        }
    }

    /** Documents rather than Caches: unsent events should survive storage pressure. */
    private fun queuePath(): String? {
        val documents = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: return null
        return "$documents/$QUEUE_FILE"
    }
}

internal actual val recAppIdentifier: String = "mobile-ios"

internal actual fun recNowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** Copies an NSData's bytes into a Kotlin ByteArray via its raw `bytes` pointer + memcpy (the repo's
 *  proven bridge, mirroring M3UFilePlatform.ios). Bounded by the caller reading only up to the cap. */
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val src = bytes ?: return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), src, len.convert()) }
    return out
}
