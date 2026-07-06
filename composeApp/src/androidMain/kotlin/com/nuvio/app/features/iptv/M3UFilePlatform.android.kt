package com.nuvio.app.features.iptv

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * Android file-source platform. The picker uses ACTION_OPEN_DOCUMENT through a launcher registered by
 * [MainActivity] at create-time (Compose can't `registerForActivityResult` from a LazyColumn item, so
 * we bind the activity like EpisodeReleaseNotificationPlatform does). The picked content:// stream is
 * read via the app-provided callback; the copy lives under `filesDir/playlists/{id}.m3u`.
 */
object M3UFilePicker {
    private var appContext: Context? = null
    private var launcher: ActivityResultLauncher<Array<String>>? = null

    // The pick in flight — set when pickM3UFile is called, consumed when the ActivityResult returns.
    private var pending: ((PickedM3UFile?) -> Unit)? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** Registers the OpenDocument launcher. Call from MainActivity.onCreate (before setContent). */
    fun bindActivity(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val cb = pending
            pending = null
            if (uri == null || cb == null) {
                cb?.invoke(null)
                return@registerForActivityResult
            }
            val context = appContext ?: activity.applicationContext
            // Persist read permission so a later re-copy could work, and resolve a display name.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = queryDisplayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "playlist.m3u"
            cb(PickedM3UFile(fileName = name, readBytes = {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not open the selected file")
                }
            }))
        }
    }

    fun launch(onPicked: (PickedM3UFile?) -> Unit) {
        val l = launcher
        if (l == null) {
            onPicked(null)
            return
        }
        pending = onPicked
        // Common M3U MIME types are unreliable across providers, so accept a broad set + a wildcard.
        runCatching {
            l.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "text/plain", "*/*"))
        }.onFailure {
            pending = null
            onPicked(null)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()

    internal fun playlistsDir(): File {
        val ctx = checkNotNull(appContext) { "M3UFilePicker.initialize(context) not called" }
        return File(ctx.filesDir, "playlists").apply { mkdirs() }
    }
}

actual fun pickM3UFile(onPicked: (PickedM3UFile?) -> Unit) = M3UFilePicker.launch(onPicked)

actual suspend fun copyM3UFileToStorage(playlistId: String, picked: PickedM3UFile): String = withContext(Dispatchers.IO) {
    val bytes = picked.readBytes()
    val dest = File(M3UFilePicker.playlistsDir(), "${safeName(playlistId)}.m3u")
    dest.writeBytes(bytes)
    dest.absolutePath
}

actual fun m3uFileStoragePath(playlistId: String): String =
    File(M3UFilePicker.playlistsDir(), "${safeName(playlistId)}.m3u").absolutePath

actual fun fileExists(path: String): Boolean = File(path).exists()

actual fun deleteM3UFile(playlistId: String) {
    runCatching { File(M3UFilePicker.playlistsDir(), "${safeName(playlistId)}.m3u").delete() }
}

actual suspend fun streamFileLines(path: String, onLine: (String) -> Unit): Unit = withContext(Dispatchers.IO) {
    val file = File(path)
    if (!file.exists()) error("Playlist file not found: $path")
    // Sniff the gzip magic (0x1f 0x8b) via a 2-byte pushback so a saved .m3u.gz streams decompressed.
    val pushback = PushbackInputStream(file.inputStream().buffered(), 2)
    val b0 = pushback.read()
    val b1 = pushback.read()
    if (b1 != -1) pushback.unread(b1)
    if (b0 != -1) pushback.unread(b0)
    val gzipped = b0 == 0x1f && b1 == 0x8b
    val stream = if (gzipped) GZIPInputStream(pushback) else pushback
    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            onLine(line)
        }
    }
}

/** Playlist ids contain '://', ':', '|' — flatten to a filesystem-safe stable basename. */
private fun safeName(playlistId: String): String = buildString(playlistId.length) {
    for (c in playlistId) append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
}
