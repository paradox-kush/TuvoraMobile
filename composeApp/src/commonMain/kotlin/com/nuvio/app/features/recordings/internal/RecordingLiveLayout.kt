package com.nuvio.app.features.recordings.internal

/**
 * Where a live recording lives on disk: ONE directory per schedule, derived from the schedule id so a retry
 * inside the slot continues into the same place. The directory name is a single plain segment (the id may hold
 * `:` and `/`); the readable part is only for a human looking at the folder, the hash keeps ids that differ
 * only in punctuation apart. The row's file name is `<dir>/index.m3u8`.
 */
internal object RecordingLiveLayout {
    const val PLAYLIST = "index.m3u8"
    private const val PREFIX = "live-"

    fun dirNameFor(scheduleId: String): String {
        val readable = scheduleId.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(24)
        return "$PREFIX$readable-${hashOf(scheduleId)}"
    }

    fun fileNameFor(scheduleId: String): String = "${dirNameFor(scheduleId)}/$PLAYLIST"

    /** The recording directory of a row's [fileName], or null for a single-file (catch-up) recording. */
    fun dirOf(fileName: String): String? = if ('/' in fileName) fileName.substringBefore('/') else null

    // FNV-1a over the UTF-16 units: stable on every platform (String.hashCode is too, but this is documented here).
    private fun hashOf(text: String): String {
        var h = 0x811C9DC5L
        for (c in text) {
            h = (h xor c.code.toLong()) and 0xFFFFFFFFL
            h = (h * 16777619L) and 0xFFFFFFFFL
        }
        return h.toString(16).padStart(8, '0')
    }
}
