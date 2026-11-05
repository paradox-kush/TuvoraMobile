package com.nuvio.app.features.livetv.pause

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A write to the capture directory failed (disk full, directory gone). Distinct from a network drop: it ends the capture. */
internal class LiveStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** java.io [LiveSegmentStore] for one capture directory. Confined to the capture coroutine. */
internal class FileLiveSegmentStore(private val dir: File) : LiveSegmentStore {
    override fun openPiece(name: String): LiveSegmentWriter {
        val out = try {
            BufferedOutputStream(FileOutputStream(File(dir, name)), WRITE_BUFFER_BYTES)
        } catch (e: IOException) {
            throw LiveStoreException("cannot open $name", e)
        }
        return object : LiveSegmentWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                try {
                    out.write(bytes, offset, length)
                } catch (e: IOException) {
                    throw LiveStoreException("cannot write $name", e)
                }
            }

            override fun close() {
                try {
                    out.close()
                } catch (e: IOException) {
                    throw LiveStoreException("cannot close $name", e)
                }
            }
        }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    override fun writePlaylistAtomically(text: String) {
        val target = File(dir, PLAYLIST_NAME)
        val temp = File(dir, "$PLAYLIST_NAME.tmp")
        try {
            temp.writeText(text)
        } catch (e: IOException) {
            throw LiveStoreException("cannot write playlist", e)
        }
        // rename(2) replaces atomically on one filesystem: the player never reads half a playlist.
        if (!temp.renameTo(target)) throw LiveStoreException("cannot publish playlist")
    }

    companion object {
        const val PLAYLIST_NAME = "index.m3u8"
        private const val WRITE_BUFFER_BYTES = 64 * 1024
    }
}

/** Housekeeping for the capture root (`cacheDir/live-pause`). */
internal object LivePauseStorage {
    const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

    /** Deletes capture directories older than [maxAgeMs] (a crash, or the app killed while paused). Returns how many. */
    fun sweepStale(root: File, nowMs: Long, maxAgeMs: Long = STALE_AFTER_MS): Int {
        val children = root.listFiles() ?: return 0
        var removed = 0
        for (child in children) {
            if (nowMs - child.lastModified() > maxAgeMs && child.deleteRecursively()) removed++
        }
        return removed
    }
}
