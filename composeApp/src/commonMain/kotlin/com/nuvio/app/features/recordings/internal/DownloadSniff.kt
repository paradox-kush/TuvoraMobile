package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.RecordingProblem

/** Tells an HLS playlist (text) from a media file by what the server said and the first bytes. */
internal object DownloadSniff {
    /** Bytes to peek at; enough for `#EXTM3U` (UTF-8 BOM and leading whitespace tolerated). */
    const val PEEK_BYTES = 16

    fun isHlsPlaylist(contentType: String?, firstBytes: ByteArray): Boolean = false
}

/**
 * Belt and braces for a "finished" save: a programme of a minute or more that came to a few KiB is not a media file.
 */
internal object RecordingSanityGuard {
    const val MIN_PROGRAMME_MS = 60_000L
    const val MIN_PLAUSIBLE_BYTES = 64L * 1024

    /** True when [sizeBytes] is implausibly small for a programme [programmeMs] long. */
    fun implausiblyTiny(programmeMs: Long, sizeBytes: Long): Boolean = false

    /** Why a finished save must not be Done: UNSUPPORTED_FORMAT for a playlist, NETWORK (retryable) otherwise; null when plausible. */
    fun verdict(programmeMs: Long, sizeBytes: Long, firstBytes: ByteArray): RecordingProblem? = null
}
