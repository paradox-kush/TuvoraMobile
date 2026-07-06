package com.nuvio.app.features.iptv

/**
 * Platform hooks for the **M3U FILE** playlist source (P2): picking a document, copying it into app
 * storage, and streaming that local copy line-by-line. Kept separate from the M3U-URL path — a file
 * playlist's bytes live on-device (the source file can disappear), and its ingest reads the local
 * copy instead of the network.
 */

/** The identity/source string persisted on [XtreamAccount.sourceType] for a file playlist. */
const val SOURCE_TYPE_M3U_FILE = "m3u_file"

/** A file the user picked: its display name + a provider that yields the raw bytes (read once, to copy). */
class PickedM3UFile(
    val fileName: String,
    val readBytes: suspend () -> ByteArray,
)

/**
 * Opens the platform document picker for an M3U/M3U8/text file and invokes [onPicked] with the result
 * (or null if the user cancelled / the pick failed). Android = ACTION_OPEN_DOCUMENT via a registered
 * ActivityResult (see [M3UFilePicker.bindActivity]); iOS = UIDocumentPickerViewController.
 */
expect fun pickM3UFile(onPicked: (PickedM3UFile?) -> Unit)

/**
 * Copies a picked file's bytes into app storage at a stable per-playlist path and returns that path
 * (`…/playlists/{playlistId}.m3u`). The saved copy is what the ingest streams, so the original source
 * can be deleted/moved afterwards. Overwrites any prior copy for the same playlist id.
 */
expect suspend fun copyM3UFileToStorage(playlistId: String, picked: PickedM3UFile): String

/** Absolute path of the saved local copy for a playlist id — whether or not the file currently exists. */
expect fun m3uFileStoragePath(playlistId: String): String

/** True if a file exists at [path]. Used to detect a synced file-playlist whose local copy is absent. */
expect fun fileExists(path: String): Boolean

/** Deletes the saved local copy for a playlist id (best-effort; no-op if absent). */
expect fun deleteM3UFile(playlistId: String)

/**
 * Streams a local file's lines to [onLine], NEVER materializing the whole file (a playlist can be
 * 190+ MB). Transparently gunzips a gzip-magic-prefixed file. [onLine] runs on a background thread;
 * keep it cheap. Throws if the file is missing/unreadable. Twin of `httpStreamLines`.
 */
expect suspend fun streamFileLines(path: String, onLine: (String) -> Unit)

/**
 * Thin resolver over the file-storage expects so [M3UClient] doesn't reach platform APIs directly.
 * A file playlist stores its display file name in [XtreamAccount.userAgent]-adjacent metadata? No —
 * the local path is derived purely from the stable playlist id, so this needs only the id.
 */
internal object M3UFileStore {
    /** The local copy path for a file playlist, or null when the copy is absent (e.g. synced from another device). */
    fun localPath(acc: XtreamAccount): String? {
        val path = m3uFileStoragePath(acc.id)
        return if (fileExists(path)) path else null
    }

    /** Whether this device actually has the file playlist's bytes (false for a synced-only file playlist). */
    fun hasLocalCopy(acc: XtreamAccount): Boolean = fileExists(m3uFileStoragePath(acc.id))
}
