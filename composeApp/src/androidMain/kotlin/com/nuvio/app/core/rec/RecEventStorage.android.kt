package com.nuvio.app.core.rec

import android.content.Context
import android.content.SharedPreferences
import java.io.File

private const val PREFERENCES_NAME = "nuvio_rec_events"
private const val QUEUE_FILE = "rec-events-queue.jsonl"

internal actual object RecEventStorage {
    private var preferences: SharedPreferences? = null
    private var filesDir: File? = null

    /** Called from the Android app's initialisation, alongside SyncClientIdentityStorage. */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        filesDir = context.filesDir
    }

    actual fun loadString(key: String): String? = preferences?.getString(key, null)

    actual fun saveString(key: String, value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }

    actual fun removeString(key: String) {
        preferences?.edit()?.remove(key)?.commit()
    }

    actual fun loadBoolean(key: String, default: Boolean): Boolean =
        preferences?.getBoolean(key, default) ?: default

    actual fun saveBoolean(key: String, value: Boolean) {
        preferences?.edit()?.putBoolean(key, value)?.apply()
    }

    actual fun loadLong(key: String, default: Long): Long =
        preferences?.getLong(key, default) ?: default

    actual fun saveLong(key: String, value: Long) {
        preferences?.edit()?.putLong(key, value)?.apply()
    }

    actual fun loadQueue(): String? = runCatching {
        val file = File(filesDir ?: return@runCatching null, QUEUE_FILE)
        if (!file.exists()) return@runCatching null
        // Enforce the byte limit WHILE consuming the stream — the queue is file-backed here, so an
        // oversized/corrupt file must never fully land in memory (a preceding readText() would).
        // Over the cap → drop it so it can't linger and re-OOM next launch.
        val text = readBoundedUtf8(file.inputStream(), RecEventQueueRestorePolicy.MAX_QUEUE_BYTES)
        if (text == null) file.delete()
        text
    }.getOrNull()

    actual fun saveQueue(contents: String?) {
        runCatching {
            val file = File(filesDir ?: return, QUEUE_FILE)
            if (contents == null) file.delete() else file.writeText(contents)
        }
    }
}

internal actual val recAppIdentifier: String = "mobile-android"

internal actual fun recNowMillis(): Long = System.currentTimeMillis()

/**
 * Reads up to [maxBytes] of UTF-8 from [input], returning null if the content exceeds [maxBytes].
 * The limit is checked as the stream is consumed, so an oversized/growing source is rejected before
 * it fully lands in memory. Closes [input].
 */
internal fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Int): String? {
    input.use { ins ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        val cap = maxBytes.toLong()
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
