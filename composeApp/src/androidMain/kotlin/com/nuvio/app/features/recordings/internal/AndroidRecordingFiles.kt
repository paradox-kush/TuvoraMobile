package com.nuvio.app.features.recordings.internal

import android.os.StatFs
import com.nuvio.app.features.recordings.api.RecordingFiles
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-private storage: state files in [stateDir], recordings in [recordingsDir]. A state write goes
 * to a temporary file, is fsynced, then moved over the old one, so a crash leaves the old state or
 * the new one and never half of each.
 */
internal class AndroidRecordingFiles(private val stateDir: File, private val recordingsDir: File) : RecordingFiles {

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        File(stateDir, name).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        check(stateDir.isDirectory || stateDir.mkdirs()) { "Cannot create recordings state directory" }
        val tmp = File(stateDir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // rename(2) within one directory is atomic and replaces the old file (minSdk 24 has no java.nio.file).
        check(tmp.renameTo(File(stateDir, name))) { "Cannot replace recordings state" }
    }

    override suspend fun keepAside(name: String) = withContext(Dispatchers.IO) {
        val f = File(stateDir, name)
        if (f.exists()) f.renameTo(File(stateDir, "$name.corrupt-${System.currentTimeMillis()}"))
        Unit
    }

    /**
     * A plain file name, or `<directory>/<file>` for a live recording (its pieces live in a directory): every
     * segment must be a plain name, so nothing can climb out of [recordingsDir].
     */
    override fun pathFor(fileName: String): String {
        val segments = fileName.split('/')
        require(segments.size in 1..MAX_SEGMENTS && segments.all { it.isNotBlank() && it != "." && it != ".." && File(it).name == it }) {
            "not a plain file name"
        }
        return File(recordingsDir, fileName).path
    }

    override fun freeBytes(): Long {
        recordingsDir.mkdirs()
        return StatFs(recordingsDir.path).availableBytes
    }

    override fun sizeOf(path: String): Long = File(path).takeIf { it.isFile }?.length() ?: 0L

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        File(path).delete()
        Unit
    }

    override suspend fun deleteTree(path: String) = withContext(Dispatchers.IO) {
        File(path).deleteRecursively()
        Unit
    }

    override fun treeSizeOf(path: String): Long =
        File(path).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private companion object {
        /** `<live directory>/<file>`; nothing deeper is ever produced. */
        const val MAX_SEGMENTS = 2
    }
}
